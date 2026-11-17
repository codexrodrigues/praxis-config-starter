package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.util.StringUtils;

/**
 * Bootstrapper idempotente multi-tenant e multi-ambiente para convergência do plano de controle
 * governado ({@code DomainRuleSnapshotHead} + seed opcional de definições de {@link
 * GovernedRulesStudioDefinitionSeeder}).
 *
 * <p>Delega a publicação e ativação de cada par {@code (tenantId, environment)} ao {@link
 * ProductBaselineGenesisInitializer}, garantindo que heads já ativos ou retirados ({@code
 * withdrawn}) nunca sejam sobrescritos silenciosamente.
 */
public class GovernedMultiTenantSnapshotBootstrapper implements ApplicationRunner {

  private static final Logger log =
      LoggerFactory.getLogger(GovernedMultiTenantSnapshotBootstrapper.class);

  private final GovernedRuleFamilyRegistry familyRegistry;
  private final DomainRuleSnapshotHeadRepository headRepository;
  private final DomainRuleSnapshotRepository snapshotRepository;
  private final DomainRuleSnapshotService snapshotService;
  private final DomainRuleRolloutPolicyService rolloutPolicyService;
  private final DomainRuleRolloutService rolloutService;
  private final DomainRuleImplementationCatalog implementationCatalog;
  private final GovernedRulesStudioDefinitionSeeder definitionSeeder;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final List<String> tenantIds;
  private final List<String> environments;
  private final String validFromUtc;
  private final boolean seedDefinitions;
  private final boolean failFast;

  public GovernedMultiTenantSnapshotBootstrapper(
      GovernedRuleFamilyRegistry familyRegistry,
      DomainRuleSnapshotHeadRepository headRepository,
      DomainRuleSnapshotRepository snapshotRepository,
      DomainRuleSnapshotService snapshotService,
      DomainRuleRolloutPolicyService rolloutPolicyService,
      DomainRuleRolloutService rolloutService,
      DomainRuleImplementationCatalog implementationCatalog,
      GovernedRulesStudioDefinitionSeeder definitionSeeder,
      ObjectMapper objectMapper,
      Clock clock,
      List<String> tenantIds,
      List<String> environments,
      String validFromUtc,
      boolean seedDefinitions,
      boolean failFast) {
    this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry is required");
    this.headRepository = Objects.requireNonNull(headRepository, "headRepository is required");
    this.snapshotRepository =
        Objects.requireNonNull(snapshotRepository, "snapshotRepository is required");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService is required");
    this.rolloutPolicyService =
        Objects.requireNonNull(rolloutPolicyService, "rolloutPolicyService is required");
    this.rolloutService = Objects.requireNonNull(rolloutService, "rolloutService is required");
    this.implementationCatalog =
        Objects.requireNonNull(implementationCatalog, "implementationCatalog is required");
    this.definitionSeeder = definitionSeeder;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.tenantIds = normalizeScopes(tenantIds, List.of("default"));
    this.environments = normalizeScopes(environments, List.of("local"));
    this.validFromUtc =
        StringUtils.hasText(validFromUtc) ? validFromUtc.trim() : "2026-01-01T00:00:00Z";
    this.seedDefinitions = seedDefinitions;
    this.failFast = failFast;
  }

  /** Relatório consolidado por escopo {@code (tenantId, environment)}. */
  public record ScopeBootstrapResult(
      String tenantId,
      String environment,
      int activatedOperations,
      int skippedOperations,
      int definitionsSeeded) {}

  /** Relatório global de convergência multi-tenant. */
  public record BootstrapReport(
      int scopesProcessed,
      int totalActivatedOperations,
      int totalSkippedOperations,
      int totalDefinitionsSeeded,
      List<ScopeBootstrapResult> scopes) {}

  @Override
  public void run(ApplicationArguments args) {
    try {
      BootstrapReport report = bootstrapAll();
      if (report.totalActivatedOperations() > 0 || report.totalDefinitionsSeeded() > 0) {
        log.info(
            "Governed multi-tenant bootstrap converged {} scope(s): {} operation(s) activated, {} skipped, {} definition(s) seeded.",
            report.scopesProcessed(),
            report.totalActivatedOperations(),
            report.totalSkippedOperations(),
            report.totalDefinitionsSeeded());
      }
    } catch (RuntimeException ex) {
      if (failFast) {
        throw ex;
      }
      log.warn(
          "Governed multi-tenant snapshot bootstrap deferred/skipped on startup ({}): {}",
          ex.getClass().getSimpleName(),
          ex.getMessage());
    }
  }

  /**
   * Executa o bootstrap idempotente para todas as combinações configuradas de {@code tenantIds} e
   * {@code environments}.
   */
  public BootstrapReport bootstrapAll() {
    List<ScopeBootstrapResult> results = new ArrayList<>();
    int totalActivated = 0;
    int totalSkipped = 0;
    int totalSeeded = 0;
    for (String environment : environments) {
      for (String tenantId : tenantIds) {
        ScopeBootstrapResult result = bootstrapScope(tenantId, environment);
        results.add(result);
        totalActivated += result.activatedOperations();
        totalSkipped += result.skippedOperations();
        totalSeeded += result.definitionsSeeded();
      }
    }
    return new BootstrapReport(
        results.size(), totalActivated, totalSkipped, totalSeeded, List.copyOf(results));
  }

  /** Executa o bootstrap idempotente para um único escopo {@code (tenantId, environment)}. */
  public ScopeBootstrapResult bootstrapScope(String tenantId, String environment) {
    String normalizedTenant = requireScope(tenantId, "tenantId");
    String normalizedEnv = requireScope(environment, "environment");

    ProductBaselineGenesisInitializer genesis =
        new ProductBaselineGenesisInitializer(
            familyRegistry,
            headRepository,
            snapshotRepository,
            snapshotService,
            rolloutPolicyService,
            rolloutService,
            implementationCatalog,
            objectMapper,
            clock,
            List.of(normalizedTenant),
            normalizedEnv,
            validFromUtc);
    ProductBaselineGenesisInitializer.GenesisReport genesisReport =
        genesis.initializeScope(normalizedTenant, normalizedEnv);

    int seededCount = 0;
    if (seedDefinitions && definitionSeeder != null) {
      seededCount = definitionSeeder.seedScope(normalizedTenant, normalizedEnv);
    }
    return new ScopeBootstrapResult(
        normalizedTenant,
        normalizedEnv,
        genesisReport.initializedOperations(),
        genesisReport.skippedExistingOrWithdrawnOperations(),
        seededCount);
  }

  private static List<String> normalizeScopes(List<String> values, List<String> fallback) {
    if (values == null || values.isEmpty()) {
      return fallback;
    }
    List<String> normalized =
        values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    return normalized.isEmpty() ? fallback : normalized;
  }

  private static String requireScope(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
