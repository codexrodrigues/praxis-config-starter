package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutCreateRequest;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCreateRequest;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyMutationResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutReadinessResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Inicializador governado e fail-closed de baseline de produto ({@code PRODUCT_DEFAULT} {@code v1})
 * para escopos estritamente virgens ({@code PLAT-EVO-06}).
 *
 * <p>Elimina a necessidade de scripts manuais de bootstrap em hosts consumidores ao materializar a
 * revisão {@code v1} da baseline canônica de produto exatamente pelo pipeline governado oficial
 * (política {@code REQUIRED} com quórum positivo -> {@code prepareCompositionManifest} -> 2
 * aprovações distintas Maker-Checker -> {@code publish} -> {@code rollout.create} ->
 * {@code probeCandidate} contra {@code RuleFactContract} -> {@code activatePublished}).
 *
 * <p>Invariante de segurança fail-closed:
 * <ul>
 *   <li>Jamais atua sobre escopos que já possuam {@code DomainRuleSnapshotHead} (esteja ele
 *       {@code ACTIVE} <strong>ou {@code WITHDRAWN}</strong>);</li>
 *   <li>Jamais atua sobre escopos que já possuam histórico prévio de snapshots publicados
 *       ({@code findMaximumPublicationRevision != null}).</li>
 * </ul>
 */
public final class ProductBaselineGenesisInitializer implements ApplicationRunner {

    public static final String GENESIS_POLICY_KEY = "genesis-product-baseline";
    public static final String ACTOR_POLICY_AUTHOR = "system:genesis-policy-author";
    public static final String ACTOR_POLICY_APPROVER = "system:genesis-policy-approver";
    public static final String ACTOR_COMP_APPROVER_1 = "system:genesis-approver-1";
    public static final String ACTOR_COMP_APPROVER_2 = "system:genesis-approver-2";
    public static final String ACTOR_PUBLISHER = "system:genesis-publisher";
    public static final String ACTOR_PROBE = "system:genesis-probe";
    public static final String ACTOR_OPERATOR = "system:genesis-operator";

    /** Resumo determinístico de execução do inicializador de gênese. */
    public record GenesisReport(
            int initializedOperations, int skippedExistingOrWithdrawnOperations) {}

    private final GovernedRuleFamilyRegistry familyRegistry;
    private final DomainRuleSnapshotHeadRepository headRepository;
    private final DomainRuleSnapshotRepository snapshotRepository;
    private final DomainRuleSnapshotService snapshotService;
    private final DomainRuleRolloutPolicyService rolloutPolicyService;
    private final DomainRuleRolloutService rolloutService;
    private final DomainRuleImplementationCatalog implementationCatalog;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<String> tenantIds;
    private final String environment;
    private final String validFromUtc;

    public ProductBaselineGenesisInitializer(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleSnapshotHeadRepository headRepository,
            DomainRuleSnapshotRepository snapshotRepository,
            DomainRuleSnapshotService snapshotService,
            DomainRuleRolloutPolicyService rolloutPolicyService,
            DomainRuleRolloutService rolloutService,
            DomainRuleImplementationCatalog implementationCatalog,
            ObjectMapper objectMapper,
            Clock clock,
            List<String> tenantIds,
            String environment,
            String validFromUtc) {
        this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry");
        this.headRepository = Objects.requireNonNull(headRepository, "headRepository");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.rolloutPolicyService =
                Objects.requireNonNull(rolloutPolicyService, "rolloutPolicyService");
        this.rolloutService = Objects.requireNonNull(rolloutService, "rolloutService");
        this.implementationCatalog =
                Objects.requireNonNull(implementationCatalog, "implementationCatalog");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantIds = normalizeTenantIds(tenantIds);
        this.environment = requireText(environment, "environment");
        this.validFromUtc = requireText(validFromUtc, "validFromUtc");
    }

    @Override
    public void run(ApplicationArguments args) {
        initializeAll();
    }

    /**
     * Inicializa todos os tenants configurados no ambiente alvo, ignorando escopos já existentes ou
     * retirados ({@code withdrawn}).
     */
    public GenesisReport initializeAll() {
        int initialized = 0;
        int skipped = 0;
        for (String tenantId : tenantIds) {
            GenesisReport partial = initializeScope(tenantId, environment);
            initialized += partial.initializedOperations();
            skipped += partial.skippedExistingOrWithdrawnOperations();
        }
        return new GenesisReport(initialized, skipped);
    }

    /**
     * Inicializa de forma idempotente e fail-closed apenas as operações estritamente virgens de um
     * escopo {@code (tenantId, environment)}.
     */
    public GenesisReport initializeScope(String tenantId, String targetEnvironment) {
        String tenant = requireText(tenantId, "tenantId");
        String env = requireText(targetEnvironment, "targetEnvironment");
        int initialized = 0;
        int skipped = 0;

        DomainRuleGovernancePrincipal policyAuthor =
                new DomainRuleGovernancePrincipal(tenant, ACTOR_POLICY_AUTHOR, env);
        DomainRuleGovernancePrincipal policyApprover =
                new DomainRuleGovernancePrincipal(tenant, ACTOR_POLICY_APPROVER, env);
        DomainRuleGovernancePrincipal probePrincipal =
                new DomainRuleGovernancePrincipal(tenant, ACTOR_PROBE, env);
        DomainRuleGovernancePrincipal operatorPrincipal =
                new DomainRuleGovernancePrincipal(tenant, ACTOR_OPERATOR, env);

        for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
            for (DomainRuleProductProfile profile : family.productProfiles()) {
                RuleSetRef ref = profile.ruleSet().ref();
                String ruleSetKey = ref.ruleSetKey();
                String operationKey = ref.operationKey();

                // Fail-closed guard 1: never touch an existing head (ACTIVE or WITHDRAWN)
                if (headRepository
                        .findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
                                tenant, env, ruleSetKey, operationKey)
                        .isPresent()) {
                    skipped++;
                    continue;
                }
                // Fail-closed guard 2: never touch a scope with any prior immutable snapshot
                if (snapshotRepository.findMaximumPublicationRevision(
                                tenant, env, ruleSetKey, operationKey)
                        != null) {
                    skipped++;
                    continue;
                }

                ensureRequiredBootstrapRolloutPolicy(
                        ruleSetKey, policyAuthor, policyApprover, operatorPrincipal);

                RuleSetDefinition productV1 = ensureVersionOne(profile.ruleSet());
                DomainRuleCompositionManifestRequest manifestRequest =
                        new DomainRuleCompositionManifestRequest(
                                productV1,
                                List.of(),
                                profile.ownerServiceKey(),
                                profile.hostContractVersion(),
                                validFromUtc,
                                null);
                DomainRuleCompositionManifestResponse manifest =
                        snapshotService.prepareCompositionManifest(manifestRequest, tenant, env);

                snapshotService.approveComposition(
                        manifestRequest, tenant, env, ACTOR_COMP_APPROVER_1);
                snapshotService.approveComposition(
                        manifestRequest, tenant, env, ACTOR_COMP_APPROVER_2);

                DomainRuleSnapshotStoredResponse stored =
                        snapshotService.publish(
                                new DomainRuleSnapshotPublicationRequest(
                                        productV1,
                                        List.of(),
                                        profile.ownerServiceKey(),
                                        profile.hostContractVersion(),
                                        validFromUtc,
                                        null,
                                        manifest.compositionDigest()),
                                tenant,
                                env,
                                null,
                                "*",
                                ACTOR_PUBLISHER);

                DomainRuleRolloutResponse rollout =
                        rolloutService.create(
                                new DomainRuleRolloutCreateRequest(
                                        stored.snapshot().snapshotKey(), null),
                                operatorPrincipal,
                                null,
                                "*");

                familyRegistry.probeCandidate(
                        tenant,
                        ruleSetKey,
                        operationKey,
                        rollout.rolloutId(),
                        probePrincipal,
                        snapshotService,
                        rolloutService,
                        implementationCatalog,
                        objectMapper,
                        clock);

                DomainRuleRolloutReadinessResponse readiness =
                        rolloutService.readiness(rollout.rolloutId(), operatorPrincipal);
                if (!readiness.activationReady()) {
                    throw new IllegalStateException(
                            "Product baseline genesis probe failed readiness check for "
                                    + ruleSetKey
                                    + "::"
                                    + operationKey
                                    + " [status="
                                    + readiness.status()
                                    + ", incompatible="
                                    + readiness.incompatibleProbes()
                                    + "]");
                }

                snapshotService.activatePublished(
                        stored.snapshot().snapshotKey(),
                        ACTOR_OPERATOR,
                        tenant,
                        env,
                        null,
                        "*",
                        rollout.rolloutId());
                initialized++;
            }
        }
        return new GenesisReport(initialized, skipped);
    }

    private void ensureRequiredBootstrapRolloutPolicy(
            String ruleSetKey,
            DomainRuleGovernancePrincipal policyAuthor,
            DomainRuleGovernancePrincipal policyApprover,
            DomainRuleGovernancePrincipal operator) {
        DomainRuleRolloutPolicyResponse active =
                rolloutPolicyService.catalog(ruleSetKey, operator, true, true, true).activePolicy();
        if (hasValidBootstrapAuthority(active)) {
            return;
        }
        DomainRuleRolloutPolicyMutationResponse created =
                rolloutPolicyService.create(
                        new DomainRuleRolloutPolicyCreateRequest(
                                ruleSetKey,
                                GENESIS_POLICY_KEY,
                                "REQUIRED",
                                1,
                                BigDecimal.ONE,
                                true,
                                300L,
                                3600L),
                        policyAuthor);
        DomainRuleRolloutPolicyMutationResponse approved =
                rolloutPolicyService.approve(created.policy().policyId(), policyApprover);
        rolloutPolicyService.activate(
                approved.policy().policyId(), quoteEtag(approved.headEtag()), operator);
    }

    private static boolean hasValidBootstrapAuthority(DomainRuleRolloutPolicyResponse policy) {
        return policy != null
                && "ACTIVE".equals(policy.status())
                && "REQUIRED".equals(policy.enforcementMode())
                && policy.minimumFreshProbes() >= 1
                && policy.minimumReadyRatio() != null
                && policy.minimumReadyRatio().signum() > 0
                && policy.createdBy() != null
                && policy.approvedBy() != null
                && policy.activatedBy() != null
                && !policy.createdBy().equals(policy.approvedBy());
    }

    private static RuleSetDefinition ensureVersionOne(RuleSetDefinition ruleSet) {
        if (ruleSet.ref().version() == 1) {
            return ruleSet;
        }
        RuleSetRef ref = ruleSet.ref();
        return new RuleSetDefinition(
                new RuleSetRef(
                        ref.domainKey(),
                        ref.boundedContextKey(),
                        ref.ruleSetKey(),
                        ref.operationKey(),
                        1),
                ruleSet.availableRoots(),
                ruleSet.slots(),
                ruleSet.bindings(),
                ruleSet.compatibility(),
                ruleSet.failPolicy(),
                ruleSet.errorDescriptors());
    }

    private static String quoteEtag(String raw) {
        String trimmed = requireText(raw, "headEtag");
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed;
        }
        return '"' + trimmed + '"';
    }

    private static List<String> normalizeTenantIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of("1");
        }
        List<String> cleaned =
                raw.stream()
                        .filter(Objects::nonNull)
                        .flatMap(item -> Arrays.stream(item.split(",")))
                        .map(String::trim)
                        .filter(item -> !item.isEmpty())
                        .distinct()
                        .toList();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("At least one genesis tenantId is required");
        }
        return cleaned;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
