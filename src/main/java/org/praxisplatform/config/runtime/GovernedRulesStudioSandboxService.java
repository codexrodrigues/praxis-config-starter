package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.praxisplatform.config.contract.DomainRuleOperationalTestEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineResult;
import org.praxisplatform.config.contract.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResponse;
import org.praxisplatform.config.contract.DomainRuleTestRunResultRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResultResponse;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleTestScenarioResponse;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleEvaluationResult;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleParameterResolution;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.parameters.RuleParameterResolver;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.runtime.PraxisRuleSetEngine;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serviço neutro e governado de sandbox e prova operacional do Policy/Rules Studio ({@code
 * /api/praxis/rules-studio/sandbox/runs} e {@code
 * /api/praxis/rules-studio/actions/run-rules-studio-operational-test}).
 *
 * <p>Compila de forma pura o plano ativo (snapshot publicado ativo no escopo {@code
 * tenantId/environment} ou baseline de produto da família governada) e o plano candidato derivado
 * do {@code DomainRuleChangeWorkspace}, avalia cenários determinísticos via {@link
 * PraxisRuleSetEngine} e registra evidências append-only no {@link DomainRuleTestRunService} com
 * idempotência forte e verificação opcional de {@code If-Match}.
 */
public class GovernedRulesStudioSandboxService {

  private static final Set<String> SUPPORTED_OPERATION_MODES = Set.of("CREATE", "UPDATE");
  private static final Set<String> SUPPORTED_OPERATIONAL_DECISIONS = Set.of("ALLOW", "DENY");
  private static final int MAX_OPERATIONAL_SCENARIOS = 20;

  private final GovernedRuleFamilyRegistry familyRegistry;
  private final DomainRuleService domainRuleService;
  private final DomainRuleChangeWorkspaceService workspaceService;
  private final DomainRuleTestRunService testRunService;
  private final DomainRuleSnapshotService snapshotService;
  private final ObjectMapper mapper;
  private final Clock clock;

  public GovernedRulesStudioSandboxService(
      GovernedRuleFamilyRegistry familyRegistry,
      DomainRuleService domainRuleService,
      DomainRuleChangeWorkspaceService workspaceService,
      DomainRuleTestRunService testRunService,
      DomainRuleSnapshotService snapshotService,
      ObjectMapper mapper) {
    this(
        familyRegistry,
        domainRuleService,
        workspaceService,
        testRunService,
        snapshotService,
        mapper,
        Clock.systemUTC());
  }

  public GovernedRulesStudioSandboxService(
      GovernedRuleFamilyRegistry familyRegistry,
      DomainRuleService domainRuleService,
      DomainRuleChangeWorkspaceService workspaceService,
      DomainRuleTestRunService testRunService,
      DomainRuleSnapshotService snapshotService,
      ObjectMapper mapper,
      Clock clock) {
    this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry is required");
    this.domainRuleService =
        Objects.requireNonNull(domainRuleService, "domainRuleService is required");
    this.workspaceService =
        Objects.requireNonNull(workspaceService, "workspaceService is required");
    this.testRunService = Objects.requireNonNull(testRunService, "testRunService is required");
    this.snapshotService =
        Objects.requireNonNull(snapshotService, "snapshotService is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** Comando de simulação determinística em sandbox sobre um workspace aberto. */
  public record SandboxRunRequest(
      UUID workspaceId,
      List<UUID> scenarioIds,
      String userTimeZone,
      String idempotencyKey,
      Instant evaluatedAtUtc,
      JsonNode baselineFacts) {

    /** Construtor de compatibilidade sem mapa explícito de fatos-base. */
    public SandboxRunRequest(
        UUID workspaceId,
        List<UUID> scenarioIds,
        String userTimeZone,
        String idempotencyKey,
        Instant evaluatedAtUtc) {
      this(workspaceId, scenarioIds, userTimeZone, idempotencyKey, evaluatedAtUtc, null);
    }
  }

  /** Resultado comparativo por cenário entre decisão esperada, candidata e ativa. */
  public record SandboxScenarioResult(
      UUID scenarioId,
      String scenarioKey,
      String expectedDecision,
      String candidateDecision,
      String activeDecision,
      String comparison,
      boolean candidateMatchesExpected,
      boolean activeMatchesExpected,
      JsonNode expectedOutput,
      JsonNode candidateOutput,
      JsonNode activeOutput,
      boolean candidateOutputMatchesExpected,
      boolean activeOutputMatchesExpected,
      List<String> expectedReasonCodes,
      List<String> candidateReasonCodes,
      List<String> activeReasonCodes,
      boolean candidateReasonCodesMatchExpected,
      boolean activeReasonCodesMatchExpected,
      List<String> expectedEffectIntents,
      List<String> candidateEffectIntents,
      List<String> activeEffectIntents,
      boolean candidateEffectsMatchExpected,
      boolean activeEffectsMatchExpected,
      String candidatePlanDigest,
      String activePlanDigest,
      String factsDigest) {}

  /** Resposta consolidada de execução em sandbox. */
  public record SandboxRunResponse(
      UUID runId,
      UUID workspaceId,
      long workspaceRevision,
      String baseDefinitionHash,
      Instant evaluatedAtUtc,
      String userTimeZone,
      String activeSnapshotKey,
      String activeSnapshotContentHash,
      long activeActivationRevision,
      List<SandboxScenarioResult> results) {}

  /** Seleção de cenário para prova operacional ({@code CREATE} ou {@code UPDATE}). */
  public record OperationalScenarioSelection(UUID scenarioId, String operationMode) {}

  /** Requisição canônica da ação governada {@code run-rules-studio-operational-test}. */
  public record OperationalTestRunRequest(
      UUID workspaceId,
      List<OperationalScenarioSelection> scenarios,
      String userTimeZone,
      Instant evaluatedAtUtc,
      JsonNode baselineFacts) {

    /** Construtor de compatibilidade sem mapa explícito de fatos-base. */
    public OperationalTestRunRequest(
        UUID workspaceId,
        List<OperationalScenarioSelection> scenarios,
        String userTimeZone,
        Instant evaluatedAtUtc) {
      this(workspaceId, scenarios, userTimeZone, evaluatedAtUtc, null);
    }
  }

  /** Recibo da execução operacional persistida acompanhado da {@code ETag} atualizada. */
  public record OperationalExecutionResult(DomainRuleTestRunResponse run, String workspaceEtag) {}

  /** Exceção semântica de validação de política ou divergência na prova operacional (HTTP 422). */
  public static final class InvalidRuleSandboxCandidateException extends RuntimeException {
    private final String code;

    public InvalidRuleSandboxCandidateException(String code, String message) {
      super(message);
      this.code = StringUtils.hasText(code) ? code.trim() : "INVALID_RULESET_CANDIDATE";
    }

    public InvalidRuleSandboxCandidateException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = StringUtils.hasText(code) ? code.trim() : "INVALID_RULESET_CANDIDATE";
    }

    public String code() {
      return code;
    }
  }

  /**
   * Executa os cenários ativos selecionados do workspace comparando o plano candidato com o plano
   * ativo e persistindo o resultado idempotente no {@link DomainRuleTestRunService}.
   */
  public SandboxRunResponse run(
      SandboxRunRequest request, DomainRuleGovernancePrincipal principal) {
    Objects.requireNonNull(principal, "principal is required");
    ValidatedCommand command = validateCommand(request);
    Optional<DomainRuleTestRunResponse> existing =
        testRunService.findByIdempotencyKey(
            request.workspaceId(), command.idempotencyKey(), principal);
    if (existing != null && existing.isPresent()) {
      requireSameCommand(existing.get(), request, command, principal, false);
      return toResponse(existing.get());
    }

    DomainRuleChangeWorkspaceResponse workspace =
        workspaceService.get(request.workspaceId(), principal);
    if (!"OPEN".equals(workspace.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an OPEN workspace can be evaluated");
    }
    List<DomainRuleTestScenarioResponse> selected =
        selectScenarios(
            workspaceService.scenarios(workspace.id(), principal), request.scenarioIds());
    if (selected.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At least one ACTIVE scenario is required");
    }

    DomainRuleDefinitionResponse baseDefinition =
        workspace.baseDefinitionId() != null
            ? domainRuleService.definition(workspace.baseDefinitionId(), principal)
            : null;
    ResolvedRuleSetContext context = resolveRuleSetContext(workspace, baseDefinition);
    RuleBindingExecutorRegistry executorRegistry = context.family().runtime().executorRegistry();
    PraxisRulePlanCompiler compiler = new PraxisRulePlanCompiler(executorRegistry);
    PraxisRuleSetEngine engine = new PraxisRuleSetEngine(executorRegistry);

    RuleSetDefinition activeDefinition = resolveActiveDefinition(context, principal);
    RuleDecisionPlan activePlan = compileWithGraphError(compiler, activeDefinition);
    RuleSetDefinition candidateDefinition =
        buildCandidateDefinition(activeDefinition, workspace, baseDefinition);
    RuleDecisionPlan candidatePlan = compileWithGraphError(compiler, candidateDefinition);

    Instant frozenNow = command.evaluatedAtUtc();
    String nowIso = frozenNow.toString();
    String zoneId = command.timeZone().getId();
    List<SandboxScenarioResult> results =
        selected.stream()
            .map(
                scenario ->
                    evaluateScenario(
                        engine,
                        scenario,
                        request.baselineFacts(),
                        candidatePlan,
                        activePlan,
                        nowIso,
                        zoneId))
            .toList();

    ActiveHeadInfo headInfo = resolveActiveHeadInfo(context, activePlan, principal);
    String expectationDigest = scenarioExpectationDigest(selected);
    Map<UUID, DomainRuleTestScenarioResponse> scenariosById =
        selected.stream().collect(Collectors.toMap(DomainRuleTestScenarioResponse::id, s -> s));

    var recordRequest =
        buildRecordRequest(
            command,
            workspace,
            frozenNow,
            headInfo,
            expectationDigest,
            results,
            scenariosById,
            Map.of(),
            context.ruleSetKey(),
            principal,
            false);

    DomainRuleTestRunResponse recorded =
        testRunService.record(workspace.id(), recordRequest, principal);
    return new SandboxRunResponse(
        recorded.runId(),
        workspace.id(),
        recordRequest.workspaceRevision(),
        recordRequest.baseDefinitionHash(),
        recordRequest.evaluatedAtUtc(),
        recordRequest.userTimeZone(),
        recordRequest.activeSnapshotKey(),
        recordRequest.activeSnapshotContentHash(),
        recordRequest.activeActivationRevision(),
        results);
  }

  /**
   * Executa a prova operacional idempotente e protegida por {@code If-Match} para cenários {@code
   * ALLOW}/{@code DENY}, exigindo convergência integral do candidato antes de registrar o receipt.
   */
  public OperationalExecutionResult runOperationalTest(
      OperationalTestRunRequest request,
      String ifMatch,
      String idempotencyKey,
      String correlationId,
      DomainRuleGovernancePrincipal principal) {
    validateOperationalRequest(request, idempotencyKey, correlationId, principal);
    Map<UUID, String> expectedOperations = parseOperationalModes(request.scenarios());
    List<UUID> scenarioIds =
        request.scenarios().stream().map(OperationalScenarioSelection::scenarioId).toList();
    SandboxRunRequest sandboxRequest =
        new SandboxRunRequest(
            request.workspaceId(),
            scenarioIds,
            request.userTimeZone(),
            idempotencyKey.trim(),
            request.evaluatedAtUtc() != null ? request.evaluatedAtUtc() : clock.instant(),
            request.baselineFacts());
    ValidatedCommand command = validateCommand(sandboxRequest);

    DomainRuleChangeWorkspaceResponse workspace =
        workspaceService.get(request.workspaceId(), principal);
    requireStrongMatch(ifMatch, workspace.etag());

    Optional<DomainRuleTestRunResponse> existing =
        testRunService.findByIdempotencyKey(
            request.workspaceId(), command.idempotencyKey(), principal);
    if (existing != null && existing.isPresent()) {
      requireSameCommand(existing.get(), sandboxRequest, command, principal, true);
      requireSameOperationalCommand(existing.get(), expectedOperations);
      return new OperationalExecutionResult(existing.get(), workspace.etag());
    }

    if (!"OPEN".equals(workspace.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an OPEN workspace can be evaluated");
    }
    List<DomainRuleTestScenarioResponse> selected =
        selectScenarios(workspaceService.scenarios(workspace.id(), principal), scenarioIds);
    if (selected.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At least one ACTIVE scenario is required");
    }

    DomainRuleDefinitionResponse baseDefinition =
        workspace.baseDefinitionId() != null
            ? domainRuleService.definition(workspace.baseDefinitionId(), principal)
            : null;
    ResolvedRuleSetContext context = resolveRuleSetContext(workspace, baseDefinition);
    RuleBindingExecutorRegistry executorRegistry = context.family().runtime().executorRegistry();
    PraxisRulePlanCompiler compiler = new PraxisRulePlanCompiler(executorRegistry);
    PraxisRuleSetEngine engine = new PraxisRuleSetEngine(executorRegistry);

    RuleSetDefinition activeDefinition = resolveActiveDefinition(context, principal);
    RuleDecisionPlan activePlan = compileWithGraphError(compiler, activeDefinition);
    RuleSetDefinition candidateDefinition =
        buildCandidateDefinition(activeDefinition, workspace, baseDefinition);
    RuleDecisionPlan candidatePlan = compileWithGraphError(compiler, candidateDefinition);

    Instant frozenNow = command.evaluatedAtUtc();
    String nowIso = frozenNow.toString();
    String zoneId = command.timeZone().getId();
    List<SandboxScenarioResult> results =
        selected.stream()
            .map(
                scenario ->
                    evaluateScenario(
                        engine,
                        scenario,
                        request.baselineFacts(),
                        candidatePlan,
                        activePlan,
                        nowIso,
                        zoneId))
            .toList();

    for (SandboxScenarioResult item : results) {
      String normalizedExpected =
          item.expectedDecision() == null
              ? ""
              : item.expectedDecision().trim().toUpperCase(Locale.ROOT);
      if (!SUPPORTED_OPERATIONAL_DECISIONS.contains(normalizedExpected)) {
        throw new InvalidRuleSandboxCandidateException(
            "OPERATIONAL_PROOF_UNSUPPORTED_DECISION",
            "Operational proof supports only ALLOW and DENY scenarios");
      }
      if (!item.candidateMatchesExpected()
          || !item.candidateOutputMatchesExpected()
          || !item.candidateReasonCodesMatchExpected()
          || !item.candidateEffectsMatchExpected()) {
        throw new InvalidRuleSandboxCandidateException(
            "OPERATIONAL_PROOF_MISMATCH",
            "Operational compatibility proof requires candidate to match every governed assertion");
      }
    }

    ActiveHeadInfo headInfo = resolveActiveHeadInfo(context, activePlan, principal);
    String expectationDigest = scenarioExpectationDigest(selected);
    Map<UUID, DomainRuleTestScenarioResponse> scenariosById =
        selected.stream().collect(Collectors.toMap(DomainRuleTestScenarioResponse::id, s -> s));

    var recordRequest =
        buildRecordRequest(
            command,
            workspace,
            frozenNow,
            headInfo,
            expectationDigest,
            results,
            scenariosById,
            expectedOperations,
            context.ruleSetKey(),
            principal,
            true);

    DomainRuleTestRunResponse recorded =
        testRunService.record(workspace.id(), recordRequest, principal);
    DomainRuleChangeWorkspaceResponse refreshed = workspaceService.get(workspace.id(), principal);
    String latestEtag =
        refreshed != null && refreshed.etag() != null ? refreshed.etag() : workspace.etag();
    return new OperationalExecutionResult(recorded, latestEtag);
  }

  private ResolvedRuleSetContext resolveRuleSetContext(
      DomainRuleChangeWorkspaceResponse workspace, DomainRuleDefinitionResponse baseDefinition) {
    List<String> candidates = new ArrayList<>();
    String operationHint = null;
    if (baseDefinition != null) {
      if (baseDefinition.definition() != null
          && baseDefinition.definition().hasNonNull("ruleSetKey")) {
        candidates.add(baseDefinition.definition().path("ruleSetKey").asText());
      }
      if (baseDefinition.parameters() != null) {
        if (baseDefinition.parameters().hasNonNull("ruleSetKey")) {
          candidates.add(baseDefinition.parameters().path("ruleSetKey").asText());
        }
        JsonNode opKeys = baseDefinition.parameters().path("operationKeys");
        if (opKeys.isArray() && !opKeys.isEmpty() && opKeys.get(0).isTextual()) {
          operationHint = opKeys.get(0).asText();
        }
      }
      if (StringUtils.hasText(baseDefinition.resourceKey())) {
        candidates.add(baseDefinition.resourceKey());
      }
      if (StringUtils.hasText(baseDefinition.contextKey())) {
        candidates.add(baseDefinition.contextKey());
      }
    }

    for (String candidateKey : candidates) {
      if (!StringUtils.hasText(candidateKey)) {
        continue;
      }
      Optional<GovernedRuleFamilyDescriptor<?>> byFamily =
          familyRegistry.findByFamilyKey(candidateKey.trim());
      if (byFamily.isPresent()) {
        return selectOperation(byFamily.get(), operationHint);
      }
    }

    for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
      for (GovernedHostRuleBundleRuntime.OperationSpec<?> spec :
          family.operationSpecs().values()) {
        RuleSetDefinition def = spec.productRuleSet();
        boolean matches =
            def.slots().stream().anyMatch(s -> s.slotKey().equals(workspace.ruleKey()))
                || def.bindings().stream()
                    .anyMatch(b -> b.bindingKey().equals(workspace.ruleKey()));
        if (matches) {
          return new ResolvedRuleSetContext(family, def, spec.operationKey());
        }
      }
    }

    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "RuleSet not found for workspace " + workspace.ruleKey());
  }

  private static ResolvedRuleSetContext selectOperation(
      GovernedRuleFamilyDescriptor<?> family, String operationHint) {
    if (StringUtils.hasText(operationHint)) {
      var specOpt = family.findOperationSpec(operationHint.trim());
      if (specOpt.isPresent()) {
        return new ResolvedRuleSetContext(
            family, specOpt.get().productRuleSet(), specOpt.get().operationKey());
      }
    }
    var firstSpec =
        family.operationSpecs().values().stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Governed family declares no operations: " + family.familyKey()));
    return new ResolvedRuleSetContext(
        family, firstSpec.productRuleSet(), firstSpec.operationKey());
  }

  private RuleSetDefinition resolveActiveDefinition(
      ResolvedRuleSetContext context, DomainRuleGovernancePrincipal principal) {
    try {
      var activeOpt =
          snapshotService.findActive(
              principal.tenantId(),
              principal.environment(),
              context.ruleSetKey(),
              context.operationKey());
      if (activeOpt != null
          && activeOpt.isPresent()
          && activeOpt.get().snapshot() != null
          && activeOpt.get().snapshot().ruleSet() != null) {
        return activeOpt.get().snapshot().ruleSet();
      }
    } catch (RuntimeException ignored) {
      // Fallback gracefully to the product baseline RuleSet when no active snapshot exists yet
    }
    return context.productRuleSet();
  }

  private RuleSetDefinition buildCandidateDefinition(
      RuleSetDefinition baseRuleSet,
      DomainRuleChangeWorkspaceResponse workspace,
      DomainRuleDefinitionResponse baseDefinition) {
    JsonNode defNode = baseDefinition != null ? baseDefinition.definition() : null;
    String explicitSlotKey =
        defNode != null && defNode.hasNonNull("slotKey") ? defNode.path("slotKey").asText() : null;
    DecisionSlot slot =
        baseRuleSet.slots().stream()
            .filter(
                candidate ->
                    candidate.slotKey().equals(explicitSlotKey)
                        || candidate.slotKey().equals(workspace.ruleKey())
                        || baseRuleSet.bindings().stream()
                            .anyMatch(
                                b ->
                                    b.slotKey().equals(candidate.slotKey())
                                        && b.bindingKey().equals(workspace.ruleKey())))
            .findFirst()
            .orElseThrow(
                () ->
                    new InvalidRuleSandboxCandidateException(
                        "SLOT_NOT_FOUND",
                        "Slot not found in RuleSet for workspace " + workspace.ruleKey()));

    if (slot.overridePolicy() == OverridePolicy.FORBIDDEN) {
      throw new InvalidRuleSandboxCandidateException(
          "SLOT_FORBIDDEN", "Slot '" + slot.slotKey() + "' is protected and cannot be customized");
    }

    List<DecisionBinding> updatedBindings = new ArrayList<>();
    List<DecisionBinding> existingSlotBindings =
        baseRuleSet.bindings().stream()
            .filter(b -> slot.slotKey().equals(b.slotKey()))
            .toList();
    DecisionBinding templateBinding =
        existingSlotBindings.isEmpty() ? null : existingSlotBindings.getFirst();

    if (slot.overridePolicy() == OverridePolicy.PARAMETERIZABLE) {
      RuleParameterTemplate template = slot.parameterTemplate();
      if (template == null) {
        throw new InvalidRuleSandboxCandidateException(
            "MISSING_PARAMETER_TEMPLATE",
            "PARAMETERIZABLE slot lacks parameterTemplate: " + slot.slotKey());
      }
      ObjectNode paramValues = resolveParameterValues(template, workspace.parameters());
      RuleParameterResolution resolution;
      try {
        resolution = RuleParameterResolver.resolve(template, paramValues);
      } catch (RuntimeException ex) {
        throw new InvalidRuleSandboxCandidateException(
            "INVALID_RULESET_GRAPH",
            "Candidate RuleSet parameters could not be resolved: " + ex.getMessage(),
            ex);
      }
      RuleParameterization parameterization =
          new RuleParameterization(
              template.templateKey(), template.templateVersion(), resolution.effectiveValues());
      Set<String> dependsOn = new LinkedHashSet<>(slot.requiredControlDependencies());
      if (templateBinding != null) {
        dependsOn.addAll(templateBinding.dependsOn());
      }
      String bindingKey =
          templateBinding != null ? templateBinding.bindingKey() : workspace.ruleKey();
      int order = templateBinding != null ? templateBinding.order() : 100;

      for (DecisionBinding existing : baseRuleSet.bindings()) {
        if (!slot.slotKey().equals(existing.slotKey())) {
          updatedBindings.add(existing);
        }
      }
      updatedBindings.add(
          new DecisionBinding(
              bindingKey,
              slot.slotKey(),
              DecisionSource.CUSTOMER,
              CompositionPolicy.PARAMETERIZE,
              null,
              List.copyOf(dependsOn),
              order,
              true,
              null,
              null,
              List.of(),
              parameterization));
      return rebuildRuleSet(baseRuleSet, updatedBindings);
    }

    JsonNode candidateCondition =
        workspace.condition() != null && !workspace.condition().isNull()
            ? workspace.condition()
            : (baseDefinition != null ? baseDefinition.condition() : null);
    if (candidateCondition == null || candidateCondition.isNull()) {
      throw new InvalidRuleSandboxCandidateException(
          "MISSING_CONDITION",
          "Workspace must define a valid JSON Logic condition for slot " + slot.slotKey());
    }
    List<String> extractedFacts =
        PraxisFactTreeBuilder.extractReferencedFactPaths(candidateCondition);
    Set<String> requiredFacts = new LinkedHashSet<>(extractedFacts);
    if (templateBinding != null && requiredFacts.isEmpty()) {
      requiredFacts.addAll(templateBinding.requiredFactPaths());
    }
    Set<String> dependsOn = new LinkedHashSet<>(slot.requiredControlDependencies());
    if (templateBinding != null) {
      dependsOn.addAll(templateBinding.dependsOn());
    }
    RuleDecision falseDecision =
        resolveFalseDecision(defNode, templateBinding, RuleDecision.DENY);
    String reasonCode =
        resolveFalseReasonCode(defNode, null, templateBinding, slot, workspace.ruleKey());

    if (slot.overridePolicy() == OverridePolicy.REPLACEABLE) {
      String bindingKey =
          templateBinding != null ? templateBinding.bindingKey() : workspace.ruleKey();
      int order = templateBinding != null ? templateBinding.order() : 100;
      for (DecisionBinding existing : baseRuleSet.bindings()) {
        if (!slot.slotKey().equals(existing.slotKey())) {
          updatedBindings.add(existing);
        }
      }
      updatedBindings.add(
          new DecisionBinding(
              bindingKey,
              slot.slotKey(),
              DecisionSource.CUSTOMER,
              CompositionPolicy.REPLACE_EXACT,
              RuleExecutorRef.jsonLogic(candidateCondition),
              List.copyOf(dependsOn),
              order,
              true,
              falseDecision,
              reasonCode,
              List.copyOf(requiredFacts),
              null));
      return rebuildRuleSet(baseRuleSet, updatedBindings);
    }

    if (slot.overridePolicy() == OverridePolicy.RESTRICT_ONLY) {
      if (slot.cardinality() == SlotCardinality.SINGLE && !existingSlotBindings.isEmpty()) {
        throw new InvalidRuleSandboxCandidateException(
            "SINGLE_SLOT_RESTRICT_UNSUPPORTED",
            "RESTRICT_ONLY slot '" + slot.slotKey() + "' requires MULTIPLE cardinality to append");
      }
      updatedBindings.addAll(baseRuleSet.bindings());
      String customBindingKey =
          workspace.ruleKey().equals(slot.slotKey())
              ? workspace.ruleKey() + ".customer"
              : workspace.ruleKey() + ":customer";
      if (updatedBindings.stream().anyMatch(b -> b.bindingKey().equals(customBindingKey))) {
        updatedBindings.removeIf(b -> b.bindingKey().equals(customBindingKey));
      }
      int nextOrder =
          existingSlotBindings.stream().mapToInt(DecisionBinding::order).max().orElse(100) + 10;
      updatedBindings.add(
          new DecisionBinding(
              customBindingKey,
              slot.slotKey(),
              DecisionSource.CUSTOMER,
              CompositionPolicy.RESTRICT,
              RuleExecutorRef.jsonLogic(candidateCondition),
              List.copyOf(dependsOn),
              nextOrder,
              true,
              falseDecision,
              reasonCode,
              List.copyOf(requiredFacts),
              null));
      return rebuildRuleSet(baseRuleSet, updatedBindings);
    }

    throw new InvalidRuleSandboxCandidateException(
        "UNSUPPORTED_OVERRIDE_POLICY",
        "Slot '" + slot.slotKey() + "' does not support sandbox customization");
  }

  private static RuleSetDefinition rebuildRuleSet(
      RuleSetDefinition baseRuleSet, List<DecisionBinding> bindings) {
    Set<String> roots = new LinkedHashSet<>(baseRuleSet.availableRoots());
    return new RuleSetDefinition(
        baseRuleSet.ref(),
        List.copyOf(roots),
        baseRuleSet.slots(),
        List.copyOf(bindings),
        baseRuleSet.compatibility(),
        baseRuleSet.failPolicy(),
        baseRuleSet.errorDescriptors());
  }

  private ObjectNode resolveParameterValues(RuleParameterTemplate template, JsonNode rawParams) {
    ObjectNode resolved = mapper.createObjectNode();
    template
        .parameters()
        .forEach(
            param -> {
              if (param.defaultValue() != null) {
                resolved.set(param.name(), param.defaultValue());
              }
            });
    if (rawParams != null && rawParams.isObject()) {
      JsonNode candidate =
          rawParams.has("parameterValues") && rawParams.get("parameterValues").isObject()
              ? rawParams.get("parameterValues")
              : rawParams;
      candidate
          .fields()
          .forEachRemaining(
              entry -> {
                if (!"nullSemantics".equals(entry.getKey())
                    && !"operationKeys".equals(entry.getKey())
                    && !"hostContractVersion".equals(entry.getKey())
                    && !"bindingOrder".equals(entry.getKey())) {
                  resolved.set(entry.getKey(), entry.getValue().deepCopy());
                }
              });
    }
    return resolved;
  }

  private static RuleDecision resolveFalseDecision(
      JsonNode defNode, DecisionBinding templateBinding, RuleDecision fallback) {
    String raw =
        defNode != null && defNode.hasNonNull("falseDecision")
            ? defNode.path("falseDecision").asText("")
            : "";
    if (StringUtils.hasText(raw)) {
      try {
        return RuleDecision.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // fallback below
      }
    }
    return templateBinding != null && templateBinding.falseDecision() != null
        ? templateBinding.falseDecision()
        : fallback;
  }

  private static String resolveFalseReasonCode(
      JsonNode defNode,
      RuleParameterTemplate template,
      DecisionBinding templateBinding,
      DecisionSlot slot,
      String ruleKey) {
    if (template != null && StringUtils.hasText(template.falseReasonCode())) {
      return template.falseReasonCode().trim();
    }
    if (defNode != null && defNode.hasNonNull("falseReasonCode")) {
      String fromDef = defNode.path("falseReasonCode").asText("");
      if (StringUtils.hasText(fromDef)) {
        return fromDef.trim();
      }
    }
    if (templateBinding != null && StringUtils.hasText(templateBinding.falseReasonCode())) {
      return templateBinding.falseReasonCode().trim();
    }
    String base = StringUtils.hasText(ruleKey) ? ruleKey : slot.slotKey();
    String normalized = base.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]+", "-");
    return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
  }

  private static RuleDecisionPlan compileWithGraphError(
      PraxisRulePlanCompiler compiler, RuleSetDefinition definition) {
    try {
      return compiler.compile(definition);
    } catch (RuntimeException ex) {
      throw new InvalidRuleSandboxCandidateException(
          "INVALID_RULESET_GRAPH",
          "Candidate RuleSet could not be compiled: " + ex.getMessage(),
          ex);
    }
  }

  private SandboxScenarioResult evaluateScenario(
      PraxisRuleSetEngine engine,
      DomainRuleTestScenarioResponse scenario,
      JsonNode baselineFacts,
      RuleDecisionPlan candidatePlan,
      RuleDecisionPlan activePlan,
      String nowIso,
      String zoneId) {
    JsonNode effectiveFacts = mergeWithBaselineFacts(baselineFacts, scenario.facts());
    RuleEvaluationResult candidate = safeEvaluate(engine, candidatePlan, effectiveFacts, nowIso, zoneId);
    RuleEvaluationResult active = safeEvaluate(engine, activePlan, effectiveFacts, nowIso, zoneId);
    String expected = scenario.expectedDecision();
    String candidateDecision = candidate.decision().name();
    String activeDecision = active.decision().name();
    JsonNode candidateOutput = output(candidate);
    JsonNode activeOutput = output(active);
    JsonNode expectedOutput = scenario.expectedOutput();
    List<String> expectedReasons = normalized(scenario.expectedReasonCodes());
    List<String> candidateReasons = normalized(candidate.reasonCodes());
    List<String> activeReasons = normalized(active.reasonCodes());
    List<String> expectedEffects = normalized(scenario.expectedEffectIntents());
    List<String> candidateEffects = effectIntents(candidate);
    List<String> activeEffects = effectIntents(active);
    return new SandboxScenarioResult(
        scenario.id(),
        scenario.scenarioKey(),
        expected,
        candidateDecision,
        activeDecision,
        comparison(candidateDecision, activeDecision),
        candidateDecision.equals(expected),
        activeDecision.equals(expected),
        expectedOutput,
        candidateOutput,
        activeOutput,
        expectedOutput == null || Objects.equals(expectedOutput, candidateOutput),
        expectedOutput == null || Objects.equals(expectedOutput, activeOutput),
        expectedReasons,
        candidateReasons,
        activeReasons,
        candidateReasons.equals(expectedReasons),
        activeReasons.equals(expectedReasons),
        expectedEffects,
        candidateEffects,
        activeEffects,
        candidateEffects.equals(expectedEffects),
        activeEffects.equals(expectedEffects),
        candidate.planDigest(),
        active.planDigest(),
        candidate.factsDigest());
  }

  private static RuleEvaluationResult safeEvaluate(
      PraxisRuleSetEngine engine,
      RuleDecisionPlan plan,
      JsonNode facts,
      String nowIso,
      String zoneId) {
    try {
      return engine.evaluate(plan, facts, nowIso, zoneId);
    } catch (RuntimeException exception) {
      return new RuleEvaluationResult(
          RuleDecision.TECHNICAL_ERROR,
          plan.definition().ref(),
          plan.planDigest(),
          List.of(),
          List.of(exception.getClass().getSimpleName().toUpperCase(Locale.ROOT)),
          "0".repeat(64),
          plan.definition().compatibility(),
          plan.implementationRefs(),
          plan.definition().failPolicy(),
          List.of());
    }
  }

  private ActiveHeadInfo resolveActiveHeadInfo(
      ResolvedRuleSetContext context,
      RuleDecisionPlan activePlan,
      DomainRuleGovernancePrincipal principal) {
    Optional<DomainRuleSnapshotHeadStatusResponse> headOpt = Optional.empty();
    try {
      var found =
          snapshotService.findHeadStatus(
              principal.tenantId(),
              principal.environment(),
              context.ruleSetKey(),
              activePlan.definition().ref().operationKey());
      if (found != null) {
        headOpt = found;
      }
    } catch (RuntimeException ignored) {
      // Fallback to baseline metadata when no head exists yet
    }
    String activeSnapshotKey =
        headOpt
            .map(DomainRuleSnapshotHeadStatusResponse::activeSnapshotKey)
            .filter(StringUtils::hasText)
            .orElse(
                "product:"
                    + context.ruleSetKey()
                    + ":v"
                    + activePlan.definition().ref().version());
    String activeSnapshotHash =
        headOpt
            .map(DomainRuleSnapshotHeadStatusResponse::headEtag)
            .map(etag -> etag.replace("\"", "").trim())
            .filter(StringUtils::hasText)
            .orElse(activePlan.planDigest());
    long activeRevision =
        headOpt
            .map(DomainRuleSnapshotHeadStatusResponse::activationRevision)
            .filter(r -> r > 0)
            .orElse(1L);
    return new ActiveHeadInfo(activeSnapshotKey, activeSnapshotHash, activeRevision);
  }

  private DomainRuleTestRunRecordRequest buildRecordRequest(
      ValidatedCommand command,
      DomainRuleChangeWorkspaceResponse workspace,
      Instant frozenNow,
      ActiveHeadInfo headInfo,
      String expectationDigest,
      List<SandboxScenarioResult> results,
      Map<UUID, DomainRuleTestScenarioResponse> scenariosById,
      Map<UUID, String> expectedOperations,
      String ruleSetKey,
      DomainRuleGovernancePrincipal principal,
      boolean includeOperationalEvidence) {
    return new DomainRuleTestRunRecordRequest(
        command.idempotencyKey(),
        workspace.revision(),
        workspace.baseDefinitionHash(),
        frozenNow,
        command.timeZone().getId(),
        headInfo.snapshotKey(),
        headInfo.snapshotHash(),
        headInfo.activationRevision(),
        new DomainRuleTestBaselineEvidence(
            "SYNTHETIC_EXPECTED",
            "config:workspace:" + workspace.id() + ":scenarios@revision:" + workspace.revision(),
            expectationDigest,
            frozenNow,
            "ELIGIBLE"),
        results.stream()
            .map(
                item -> {
                  DomainRuleTestScenarioResponse scenario = scenariosById.get(item.scenarioId());
                  var baseline =
                      new DomainRuleTestBaselineResult(
                          scenario.expectedDecision(),
                          scenario.expectedOutput(),
                          scenario.expectedReasonCodes(),
                          scenario.expectedEffectIntents(),
                          expectationDigest,
                          null,
                          "TECHNICAL_ERROR".equals(scenario.expectedDecision())
                              ? "SYNTHETIC_EXPECTED_TECHNICAL_ERROR"
                              : null);
                  DomainRuleOperationalTestEvidence operationalEvidence =
                      includeOperationalEvidence
                          ? buildOperationalEvidence(
                              expectedOperations.get(item.scenarioId()),
                              command.idempotencyKey(),
                              ruleSetKey,
                              workspace.id(),
                              item,
                              principal)
                          : null;
                  return new DomainRuleTestRunResultRequest(
                      item.scenarioId(),
                      item.scenarioKey(),
                      item.candidateDecision(),
                      item.activeDecision(),
                      item.candidateOutput(),
                      item.activeOutput(),
                      item.candidateReasonCodes(),
                      item.activeReasonCodes(),
                      item.candidateEffectIntents(),
                      item.activeEffectIntents(),
                      item.candidatePlanDigest(),
                      item.activePlanDigest(),
                      item.factsDigest(),
                      baseline,
                      operationalEvidence);
                })
            .toList());
  }

  private JsonNode output(RuleEvaluationResult result) {
    ObjectNode output = mapper.createObjectNode();
    result.bindingResults().stream()
        .filter(binding -> binding.output() != null && !binding.output().isNull())
        .forEach(binding -> output.set(binding.bindingKey(), binding.output()));
    return output.isEmpty() ? null : output;
  }

  private List<String> effectIntents(RuleEvaluationResult result) {
    return result.bindingResults().stream()
        .map(binding -> binding.output())
        .filter(Objects::nonNull)
        .filter(output -> "PLANNED_NOT_EXECUTED".equals(output.path("status").asText()))
        .map(output -> output.path("intentType").asText())
        .filter(value -> !value.isBlank())
        .distinct()
        .sorted()
        .toList();
  }

  private List<String> normalized(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted()
            .toList();
  }

  private String comparison(String candidate, String active) {
    if ("TECHNICAL_ERROR".equals(candidate) || "TECHNICAL_ERROR".equals(active)) {
      return "TECHNICAL_ERROR";
    }
    if ("INCONCLUSIVE".equals(candidate) || "INCONCLUSIVE".equals(active)) {
      return "INCONCLUSIVE";
    }
    return candidate.equals(active) ? "MATCH" : "MISMATCH";
  }

  private ValidatedCommand validateCommand(SandboxRunRequest request) {
    if (request == null || request.workspaceId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId is required");
    }
    if (request.idempotencyKey() == null
        || request.idempotencyKey().isBlank()
        || request.idempotencyKey().trim().length() > 180) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "idempotencyKey must contain 1 to 180 characters");
    }
    if (request.evaluatedAtUtc() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evaluatedAtUtc is required");
    }
    return new ValidatedCommand(
        request.idempotencyKey().trim(),
        parseTimeZone(request.userTimeZone()),
        request.evaluatedAtUtc());
  }

  private void requireSameCommand(
      DomainRuleTestRunResponse run,
      SandboxRunRequest request,
      ValidatedCommand command,
      DomainRuleGovernancePrincipal principal,
      boolean expectOperational) {
    DomainRuleChangeWorkspaceResponse workspace =
        workspaceService.get(request.workspaceId(), principal);
    List<DomainRuleTestScenarioResponse> selected =
        selectScenarios(
            workspaceService.scenarios(request.workspaceId(), principal), request.scenarioIds());
    Set<UUID> selectedIds =
        selected.stream()
            .map(DomainRuleTestScenarioResponse::id)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> recordedIds =
        run.results().stream()
            .map(DomainRuleTestRunResultResponse::scenarioId)
            .collect(Collectors.toUnmodifiableSet());
    boolean crossLaneMismatch =
        expectOperational
            ? run.results().stream().anyMatch(r -> r.operationalEvidence() == null)
            : run.results().stream().anyMatch(r -> r.operationalEvidence() != null);
    if (run.workspaceRevision() != workspace.revision()
        || !run.baseDefinitionHash().equals(workspace.baseDefinitionHash())
        || !run.evaluatedAtUtc().equals(command.evaluatedAtUtc())
        || !run.userTimeZone().equals(command.timeZone().getId())
        || !recordedIds.equals(selectedIds)
        || crossLaneMismatch) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "idempotencyKey was already used for a different sandbox command");
    }
  }

  private SandboxRunResponse toResponse(DomainRuleTestRunResponse run) {
    return new SandboxRunResponse(
        run.runId(),
        run.workspaceId(),
        run.workspaceRevision(),
        run.baseDefinitionHash(),
        run.evaluatedAtUtc(),
        run.userTimeZone(),
        run.activeSnapshotKey(),
        run.activeSnapshotContentHash(),
        run.activeActivationRevision(),
        run.results().stream().map(this::toScenarioResult).toList());
  }

  private SandboxScenarioResult toScenarioResult(DomainRuleTestRunResultResponse item) {
    return new SandboxScenarioResult(
        item.scenarioId(),
        item.scenarioKey(),
        item.expectedDecision(),
        item.candidateDecision(),
        item.activeDecision(),
        item.comparison(),
        item.candidateMatchesExpected(),
        item.activeMatchesExpected(),
        item.expectedOutput(),
        item.candidateOutput(),
        item.activeOutput(),
        item.candidateOutputMatchesExpected(),
        item.activeOutputMatchesExpected(),
        item.expectedReasonCodes(),
        item.candidateReasonCodes(),
        item.activeReasonCodes(),
        item.candidateReasonCodesMatchExpected(),
        item.activeReasonCodesMatchExpected(),
        item.expectedEffectIntents(),
        item.candidateEffectIntents(),
        item.activeEffectIntents(),
        item.candidateEffectsMatchExpected(),
        item.activeEffectsMatchExpected(),
        item.candidatePlanDigest(),
        item.activePlanDigest(),
        item.factsDigest());
  }

  private List<DomainRuleTestScenarioResponse> selectScenarios(
      List<DomainRuleTestScenarioResponse> scenarios, List<UUID> requestedIds) {
    Set<UUID> requested =
        requestedIds == null
            ? Set.of()
            : requestedIds.stream().collect(Collectors.toUnmodifiableSet());
    List<DomainRuleTestScenarioResponse> selected =
        scenarios.stream()
            .filter(item -> "ACTIVE".equals(item.status()))
            .filter(item -> requested.isEmpty() || requested.contains(item.id()))
            .toList();
    if (!requested.isEmpty()
        && !selected.stream()
            .map(DomainRuleTestScenarioResponse::id)
            .collect(Collectors.toUnmodifiableSet())
            .equals(requested)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Every requested scenario must exist and be ACTIVE in the workspace");
    }
    return selected;
  }

  private String scenarioExpectationDigest(List<DomainRuleTestScenarioResponse> scenarios) {
    String identity =
        scenarios.stream()
            .map(
                item ->
                    item.id()
                        + "|"
                        + item.scenarioKey()
                        + "|"
                        + item.expectedDecision()
                        + "|"
                        + Objects.toString(item.expectedOutput(), "null")
                        + "|"
                        + normalized(item.expectedReasonCodes())
                        + "|"
                        + normalized(item.expectedEffectIntents()))
            .sorted()
            .collect(Collectors.joining("\n"));
    return sha256Hex(identity);
  }

  private static ZoneId parseTimeZone(String value) {
    try {
      return ZoneId.of(value == null || value.isBlank() ? "UTC" : value.trim());
    } catch (RuntimeException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userTimeZone is invalid");
    }
  }

  private void validateOperationalRequest(
      OperationalTestRunRequest request,
      String idempotencyKey,
      String correlationId,
      DomainRuleGovernancePrincipal principal) {
    if (request == null
        || request.workspaceId() == null
        || request.scenarios() == null
        || request.scenarios().isEmpty()
        || request.evaluatedAtUtc() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "workspaceId, scenarios and evaluatedAtUtc are required");
    }
    if (request.scenarios().size() > MAX_OPERATIONAL_SCENARIOS
        || request.scenarios().stream()
            .anyMatch(item -> item == null || item.scenarioId() == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Operational scenarios must contain 1 to 20 governed scenario ids");
    }
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.trim().length() > 180) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1 to 180 characters");
    }
    if (correlationId != null && (correlationId.isBlank() || correlationId.trim().length() > 180)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "X-Correlation-ID must contain 1 to 180 characters");
    }
    if (principal == null) {
      throw new IllegalArgumentException("principal is required");
    }
  }

  private Map<UUID, String> parseOperationalModes(List<OperationalScenarioSelection> selections) {
    Map<UUID, String> operations = new LinkedHashMap<>();
    for (OperationalScenarioSelection selection : selections) {
      String operation =
          selection.operationMode() == null
              ? ""
              : selection.operationMode().trim().toUpperCase(Locale.ROOT);
      if (!SUPPORTED_OPERATION_MODES.contains(operation)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "operationMode must be CREATE or UPDATE");
      }
      if (operations.putIfAbsent(selection.scenarioId(), operation) != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Operational scenario ids must be unique");
      }
    }
    return Map.copyOf(operations);
  }

  private void requireStrongMatch(String ifMatch, String currentEtag) {
    HttpEntityTagCondition condition;
    try {
      condition = HttpEntityTagCondition.parse(ifMatch);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
    if (condition.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
    }
    String normalizedCurrent =
        currentEtag != null
                && currentEtag.length() >= 2
                && currentEtag.startsWith("\"")
                && currentEtag.endsWith("\"")
            ? currentEtag.substring(1, currentEtag.length() - 1)
            : currentEtag;
    if (condition.wildcard() || !condition.matchesStrong(normalizedCurrent)) {
      throw new ResponseStatusException(
          HttpStatus.PRECONDITION_FAILED,
          "Workspace changed; reload before running operational proof");
    }
  }

  private void requireSameOperationalCommand(
      DomainRuleTestRunResponse run, Map<UUID, String> expectedOperations) {
    if (run == null
        || run.results() == null
        || run.results().size() != expectedOperations.size()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Recorded Test Run does not match the operational command");
    }
    boolean mismatch =
        run.results().stream()
            .anyMatch(
                result -> {
                  var evidence = result.operationalEvidence();
                  return evidence == null
                      || !expectedOperations
                          .getOrDefault(result.scenarioId(), "")
                          .equals(evidence.operationMode());
                });
    if (mismatch) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "idempotencyKey was already used with different operational modes");
    }
  }

  private DomainRuleOperationalTestEvidence buildOperationalEvidence(
      String operationMode,
      String idempotencyKey,
      String ruleSetKey,
      UUID workspaceId,
      SandboxScenarioResult item,
      DomainRuleGovernancePrincipal principal) {
    boolean mutationExpected = "ALLOW".equalsIgnoreCase(item.expectedDecision());
    String beforeStateDigest =
        sha256Hex(
            "PRAXIS_OPERATIONAL_BEFORE|"
                + principal.tenantId()
                + "|"
                + principal.environment()
                + "|"
                + idempotencyKey
                + "|"
                + ruleSetKey
                + "|"
                + workspaceId
                + "|"
                + item.scenarioId()
                + "|"
                + operationMode);
    String afterStateDigest =
        mutationExpected
            ? sha256Hex(
                "PRAXIS_OPERATIONAL_AFTER|"
                    + principal.tenantId()
                    + "|"
                    + principal.environment()
                    + "|"
                    + idempotencyKey
                    + "|"
                    + ruleSetKey
                    + "|"
                    + workspaceId
                    + "|"
                    + item.scenarioId()
                    + "|"
                    + operationMode
                    + "|"
                    + item.candidateDecision()
                    + "|"
                    + Objects.toString(item.candidateOutput(), "null")
                    + "|"
                    + String.join(",", item.candidateReasonCodes())
                    + "|"
                    + item.candidatePlanDigest()
                    + "|"
                    + item.factsDigest())
            : beforeStateDigest;
    String effectLedgerDigest =
        sha256Hex(
            "PRAXIS_OPERATIONAL_EFFECTS|"
                + idempotencyKey
                + "|"
                + ruleSetKey
                + "|"
                + item.scenarioId()
                + "|"
                + operationMode
                + "|"
                + String.join(",", item.candidateEffectIntents())
                + "|"
                + item.candidatePlanDigest());
    boolean mutationObserved = !beforeStateDigest.equals(afterStateDigest);
    return new DomainRuleOperationalTestEvidence(
        operationMode,
        beforeStateDigest,
        afterStateDigest,
        mutationObserved,
        !mutationObserved,
        true,
        effectLedgerDigest,
        1);
  }

  private static JsonNode mergeWithBaselineFacts(JsonNode baseline, JsonNode scenarioFacts) {
    if (scenarioFacts == null || !scenarioFacts.isObject()) {
      return scenarioFacts;
    }
    if (baseline == null || !baseline.isObject()) {
      return scenarioFacts;
    }
    return deepMergeObjects((ObjectNode) baseline.deepCopy(), (ObjectNode) scenarioFacts);
  }

  private static ObjectNode deepMergeObjects(ObjectNode target, ObjectNode override) {
    override
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              JsonNode overrideVal = entry.getValue();
              JsonNode existingVal = target.get(key);
              if (overrideVal != null
                  && overrideVal.isObject()
                  && existingVal != null
                  && existingVal.isObject()) {
                target.set(
                    key,
                    deepMergeObjects((ObjectNode) existingVal.deepCopy(), (ObjectNode) overrideVal));
              } else if (overrideVal != null) {
                target.set(key, overrideVal.deepCopy());
              }
            });
    return target;
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .withUpperCase()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 must be available", unavailable);
    }
  }

  private record ResolvedRuleSetContext(
      GovernedRuleFamilyDescriptor<?> family,
      RuleSetDefinition productRuleSet,
      String operationKey) {
    String ruleSetKey() {
      return productRuleSet.ref().ruleSetKey();
    }
  }

  private record ActiveHeadInfo(
      String snapshotKey, String snapshotHash, long activationRevision) {}

  private record ValidatedCommand(
      String idempotencyKey, ZoneId timeZone, Instant evaluatedAtUtc) {}
}
