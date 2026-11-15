package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.contract.RuleSetCompositionAction;
import org.praxisplatform.config.contract.RuleSetCompositionCandidate;
import org.praxisplatform.config.contract.RuleSetCompositionCandidateCommand;
import org.praxisplatform.config.contract.RuleSetCompositionCandidateRequest;
import org.praxisplatform.config.contract.RuleSetCompositionSource;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionApprovalResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.parameters.RuleParameterResolver;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.TypedRuleDecisionPlan;
import org.praxisplatform.rules.snapshot.RuleSetProductAdmissionVerifier;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Motor canônico de composição e preload de candidatos para o {@code praxis-policy-studio},
 * suportado integralmente pela plataforma ({@code praxis-config-starter}) a partir do
 * {@link GovernedRuleFamilyRegistry}.
 *
 * <p>Absorve todo o boilerplate de composição de slots ({@code PARAMETERIZABLE},
 * {@code REPLACEABLE}, {@code RESTRICT_ONLY}), verificação de admissão contra a baseline de
 * produto ({@link RuleSetProductAdmissionVerifier}) e compilação tipada contra o
 * {@code RuleFactContract} da família, eliminando controladores e serviços manuais nos hosts.
 */
public class GovernedRulesStudioCompositionService {

    private static final Set<String> PUBLISHABLE_STATUSES = Set.of("approved", "active");
    private static final Set<String> SUPPORTED_SLOT_RULE_TYPES =
            Set.of("selection_eligibility", "form_rule", "field_validation", "domain_rule");

    private final GovernedRuleFamilyRegistry familyRegistry;
    private final DomainRuleService definitions;
    private final DomainRuleSnapshotService snapshots;
    private final Optional<DomainRuleRolloutService> rollouts;
    private final DomainRuleImplementationCatalog implementations;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GovernedRulesStudioCompositionService(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleService definitions,
            DomainRuleSnapshotService snapshots,
            Optional<DomainRuleRolloutService> rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.rollouts = Objects.requireNonNull(rollouts, "rollouts");
        this.implementations = Objects.requireNonNull(implementations, "implementations");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(
            transactionManager = ConfigTransactionManagerNames.CONFIG,
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public RuleSetCompositionCandidate prepareCandidate(
            String ruleSetKey,
            RuleSetCompositionCandidateRequest request,
            DomainRuleGovernancePrincipal principal,
            List<RuleSetCompositionAction> authorizedActions) {
        PreparedCanonicalCandidate prepared = prepareInternal(ruleSetKey, request, principal);
        return new RuleSetCompositionCandidate(
                prepared.candidate().ruleSetKey(),
                prepared.candidate().ruleSetVersion(),
                prepared.candidate().compositionDigest(),
                prepared.candidate().implementationCatalogDigest(),
                prepared.candidate().currentHeadEtag(),
                prepared.candidate().sources(),
                authorizedActions);
    }

    @Transactional(
            transactionManager = ConfigTransactionManagerNames.CONFIG,
            isolation = Isolation.REPEATABLE_READ)
    public DomainRuleCompositionApprovalResponse approveCandidate(
            String ruleSetKey,
            RuleSetCompositionCandidateCommand command,
            DomainRuleGovernancePrincipal principal) {
        if (command == null || command.candidateRequest() == null) {
            throw new IllegalArgumentException("candidateRequest is required");
        }
        PreparedCanonicalCandidate prepared =
                prepareInternal(ruleSetKey, command.candidateRequest(), principal);
        requireExpectedDigest(
                command.expectedCompositionDigest(), prepared.candidate().compositionDigest());
        return snapshots.approveComposition(
                prepared.manifestRequest(),
                principal.tenantId(),
                principal.environment(),
                principal.actorRef());
    }

    @Transactional(
            transactionManager = ConfigTransactionManagerNames.CONFIG,
            isolation = Isolation.REPEATABLE_READ)
    public DomainRuleSnapshotStoredResponse publishCandidate(
            String ruleSetKey,
            RuleSetCompositionCandidateCommand command,
            String ifMatch,
            String ifNoneMatch,
            DomainRuleGovernancePrincipal principal) {
        if (command == null || command.candidateRequest() == null) {
            throw new IllegalArgumentException("candidateRequest is required");
        }
        PreparedCanonicalCandidate prepared =
                prepareInternal(ruleSetKey, command.candidateRequest(), principal);
        requireExpectedDigest(
                command.expectedCompositionDigest(), prepared.candidate().compositionDigest());
        DomainRuleCompositionManifestRequest req = prepared.manifestRequest();
        return snapshots.publish(
                new DomainRuleSnapshotPublicationRequest(
                        req.ruleSet(),
                        req.sourceDefinitionIds(),
                        req.ownerServiceKey(),
                        req.requiredHostContractVersion(),
                        req.validFromUtc(),
                        req.validUntilUtc(),
                        prepared.candidate().compositionDigest()),
                principal.tenantId(),
                principal.environment(),
                ifMatch,
                ifNoneMatch,
                principal.actorRef());
    }

    public DomainRuleCandidateProbeResponse probeRolloutCandidate(
            String ruleSetKey,
            String operationKey,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal) {
        DomainRuleRolloutService rolloutService =
                rollouts.orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        "Rollout control plane is not enabled on this host."));
        GovernedRuleFamilyDescriptor<?> family =
                familyRegistry
                        .findByFamilyKey(ruleSetKey)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Unknown governed RuleSet family: " + ruleSetKey));
        String resolvedOp = resolveProbeOperationKey(family, operationKey, rolloutId, rolloutService, principal);
        return familyRegistry.probeCandidate(
                principal.tenantId(),
                family.familyKey(),
                resolvedOp,
                rolloutId,
                principal,
                snapshots,
                rolloutService,
                implementations,
                mapper,
                clock);
    }

    private String resolveProbeOperationKey(
            GovernedRuleFamilyDescriptor<?> family,
            String explicitOperationKey,
            UUID rolloutId,
            DomainRuleRolloutService rolloutService,
            DomainRuleGovernancePrincipal principal) {
        if (explicitOperationKey != null && !explicitOperationKey.isBlank()) {
            return explicitOperationKey.trim();
        }
        if (family.operationKeys().size() == 1) {
            return family.operationKeys().get(0);
        }
        for (String candidateOp : family.operationKeys()) {
            Optional<DomainRulePendingRolloutResponse> pending =
                    rolloutService.pending(family.familyKey(), candidateOp, principal);
            if (pending.isPresent() && rolloutId.equals(pending.get().rolloutId())) {
                return candidateOp;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No pending rollout matching rolloutId was found for family " + family.familyKey());
    }

    private PreparedCanonicalCandidate prepareInternal(
            String rawRuleSetKey,
            RuleSetCompositionCandidateRequest request,
            DomainRuleGovernancePrincipal principal) {
        if (request == null || request.promotedDefinitionId() == null) {
            throw new IllegalArgumentException("promotedDefinitionId is required");
        }
        String ruleSetKey = requireText(rawRuleSetKey, "ruleSetKey");
        GovernedRuleFamilyDescriptor<?> family =
                familyRegistry
                        .findByFamilyKey(ruleSetKey)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "RuleSet is not registered in GovernedRuleFamilyRegistry: "
                                                        + ruleSetKey));

        DomainRuleDefinitionResponse promoted =
                definitions.definition(request.promotedDefinitionId(), principal);
        validatePromotedSource(family, promoted);

        String operationKey = resolveOperationKey(family, promoted);
        OperationSpec<?> spec =
                family.findOperationSpec(operationKey)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Operation '"
                                                        + operationKey
                                                        + "' is outside family contract "
                                                        + ruleSetKey));
        DomainRuleProductProfile productProfile =
                family.productProfiles().stream()
                        .filter(p -> p.ruleSet().ref().operationKey().equals(operationKey))
                        .findFirst()
                        .orElseThrow();

        RuleSetDefinition baseline = productProfile.ruleSet();
        if (!baseline.ref().boundedContextKey().equals(promoted.contextKey())) {
            throw new IllegalArgumentException(
                    "Promoted definition boundedContextKey mismatches RuleSet baseline");
        }

        ComposedGraph composed = applyPromotedCustomization(family, baseline, promoted, principal.tenantId());
        PraxisRulePlanCompiler planCompiler =
                new PraxisRulePlanCompiler(family.runtime().executorRegistry());
        RuleSetDefinition compiledDefinition = planCompiler.compile(composed.definition()).definition();

        int maxVersion =
                snapshots.findMaximumRuleSetVersion(
                        principal.tenantId(), principal.environment(), ruleSetKey, operationKey);
        RuleSetRef ref = compiledDefinition.ref();
        RuleSetRef versionedRef =
                new RuleSetRef(
                        ref.domainKey(),
                        ref.boundedContextKey(),
                        ref.ruleSetKey(),
                        ref.operationKey(),
                        Math.addExact(maxVersion, 1));
        RuleSetDefinition versionedRuleSet =
                new RuleSetDefinition(
                        versionedRef,
                        compiledDefinition.availableRoots(),
                        compiledDefinition.slots(),
                        compiledDefinition.bindings(),
                        compiledDefinition.compatibility(),
                        compiledDefinition.failPolicy(),
                        composed.errorDescriptors());

        if (spec.factContract() != null) {
            Set<String> permittedRoots = new HashSet<>();
            spec.factContract()
                    .schema()
                    .schema()
                    .path("properties")
                    .fieldNames()
                    .forEachRemaining(permittedRoots::add);
            RuleSetProductAdmissionVerifier.require(
                    spec.productRuleSet(),
                    versionedRuleSet,
                    permittedRoots,
                    family.reservedProductErrorCatalog().orElse(null));
            TypedRuleDecisionPlan.compile(
                    versionedRuleSet, family.runtime().executorRegistry(), spec.factContract());
        } else {
            Set<String> permittedRoots = new HashSet<>(spec.productRuleSet().availableRoots());
            RuleSetProductAdmissionVerifier.require(
                    spec.productRuleSet(),
                    versionedRuleSet,
                    permittedRoots,
                    family.reservedProductErrorCatalog().orElse(null));
            planCompiler.compile(versionedRuleSet);
        }

        String validFrom = requireText(request.validFromUtc(), "validFromUtc");
        String validUntil = normalizeNullable(request.validUntilUtc());
        DomainRuleCompositionManifestRequest manifestRequest =
                new DomainRuleCompositionManifestRequest(
                        versionedRuleSet,
                        List.of(promoted.id()),
                        family.ownerServiceKey(),
                        family.hostContractVersion(),
                        validFrom,
                        validUntil);
        DomainRuleCompositionManifestResponse manifestResponse =
                snapshots.prepareCompositionManifest(
                        manifestRequest, principal.tenantId(), principal.environment());

        String headEtag =
                snapshots
                        .findHeadStatus(
                                principal.tenantId(),
                                principal.environment(),
                                ruleSetKey,
                                operationKey)
                        .map(status -> status.headEtag())
                        .orElse(null);

        List<RuleSetCompositionSource> safeSources =
                List.of(
                        new RuleSetCompositionSource(
                                promoted.id(),
                                promoted.ruleKey(),
                                promoted.version(),
                                promoted.status()));

        RuleSetCompositionCandidate candidate =
                new RuleSetCompositionCandidate(
                        ruleSetKey,
                        versionedRef.version(),
                        manifestResponse.compositionDigest(),
                        manifestResponse.implementationCatalogDigest(),
                        headEtag,
                        safeSources,
                        List.of());
        return new PreparedCanonicalCandidate(manifestRequest, candidate);
    }

    private void validatePromotedSource(
            GovernedRuleFamilyDescriptor<?> family, DomainRuleDefinitionResponse source) {
        if (!PUBLISHABLE_STATUSES.contains(source.status())) {
            throw new IllegalArgumentException("Promoted definition must be approved or active");
        }
        if (!SUPPORTED_SLOT_RULE_TYPES.contains(source.ruleType())) {
            throw new IllegalArgumentException(
                    "Unsupported ruleType for RuleSet slot composition: " + source.ruleType());
        }
        if (!family.ownerServiceKey().equals(source.serviceKey())
                || !family.familyKey().equals(source.resourceKey())) {
            throw new IllegalArgumentException(
                    "Promoted definition serviceKey/resourceKey mismatches governed family");
        }
        if (source.definition() == null || source.parameters() == null) {
            throw new IllegalArgumentException(
                    "Promoted definition requires non-null definition and parameters");
        }
        String declaredContract =
                source.parameters().path("hostContractVersion").asText(null);
        if (declaredContract != null
                && !declaredContract.isBlank()
                && !family.hostContractVersion().equals(declaredContract)) {
            throw new IllegalArgumentException(
                    "Promoted definition hostContractVersion mismatches governed family");
        }
        String lifecycleBoundary =
                source.governance() == null
                        ? null
                        : source.governance().path("lifecycleBoundary").asText(null);
        if (!"REFERENCE_DRAFT_ONLY".equals(lifecycleBoundary)) {
            throw new IllegalArgumentException(
                    "Promoted definition must declare lifecycleBoundary=REFERENCE_DRAFT_ONLY");
        }
    }

    private static String resolveOperationKey(
            GovernedRuleFamilyDescriptor<?> family, DomainRuleDefinitionResponse promoted) {
        JsonNode opArray = promoted.parameters().path("operationKeys");
        if (opArray.isArray() && !opArray.isEmpty()) {
            String firstOp = opArray.get(0).asText(null);
            if (firstOp != null && !firstOp.isBlank()) {
                return firstOp.trim();
            }
        }
        if (family.operationKeys().size() == 1) {
            return family.operationKeys().get(0);
        }
        throw new IllegalArgumentException(
                "Multi-operation family requires explicit parameters.operationKeys in definition");
    }

    private ComposedGraph applyPromotedCustomization(
            GovernedRuleFamilyDescriptor<?> family,
            RuleSetDefinition baseline,
            DomainRuleDefinitionResponse source,
            String tenantId) {
        String explicitSlotKey = source.definition().path("slotKey").asText(null);
        DecisionSlot slot =
                baseline.slots().stream()
                        .filter(
                                candidate ->
                                        candidate.slotKey().equals(explicitSlotKey)
                                                || candidate.slotKey().equals(source.ruleKey())
                                                || baseline.bindings().stream()
                                                        .anyMatch(
                                                                b ->
                                                                        b.slotKey()
                                                                                        .equals(
                                                                                                candidate
                                                                                                        .slotKey())
                                                                                && b.bindingKey()
                                                                                        .equals(
                                                                                                source
                                                                                                        .ruleKey())))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Slot not found in RuleSet for definition: "
                                                        + source.ruleKey()));

        OverridePolicy policy = slot.overridePolicy();
        Map<String, RuleErrorDescriptor> customErrors =
                new HashMap<>(
                        baseline.errorDescriptors() == null
                                ? Map.of()
                                : baseline.errorDescriptors());
        String sanitizedTenant =
                tenantId.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase(Locale.ROOT);

        if (policy == OverridePolicy.PARAMETERIZABLE) {
            RuleParameterTemplate template = slot.parameterTemplate();
            if (template == null) {
                throw new IllegalArgumentException(
                        "PARAMETERIZABLE slot " + slot.slotKey() + " has no parameterTemplate");
            }
            if (!mapper.valueToTree(template)
                    .equals(source.definition().path("parameterTemplate"))) {
                throw new IllegalArgumentException(
                        "Declared parameterTemplate diverges from governed slot template");
            }
            JsonNode valuesNode = source.parameters().path("parameterValues");
            if (!valuesNode.isObject() || valuesNode.isEmpty()) {
                throw new IllegalArgumentException("parameterValues is required for PARAMETERIZABLE slot");
            }
            ObjectNode paramValues = ((ObjectNode) valuesNode).deepCopy();
            var resolved = RuleParameterResolver.resolve(template, paramValues);
            if (!resolved.executor().expression().equals(source.condition())) {
                throw new IllegalArgumentException(
                        "Materialized template predicate diverges from approved condition");
            }
            DecisionBinding productBinding = requireProductBinding(baseline, slot.slotKey());
            DecisionBinding replacement =
                    new DecisionBinding(
                            "customer.t_" + sanitizedTenant + ".parameters." + slot.slotKey(),
                            slot.slotKey(),
                            DecisionSource.CUSTOMER,
                            CompositionPolicy.PARAMETERIZE,
                            null,
                            productBinding.dependsOn(),
                            productBinding.order(),
                            true,
                            null,
                            null,
                            List.of(),
                            new RuleParameterization(
                                    template.templateKey(),
                                    template.templateVersion(),
                                    paramValues));
            List<DecisionBinding> nextBindings =
                    replaceProductSlotBinding(baseline.bindings(), slot.slotKey(), productBinding, replacement);
            return new ComposedGraph(withBindings(baseline, nextBindings), customErrors);
        }

        if (source.condition() == null || !source.condition().isObject()) {
            throw new IllegalArgumentException("Approved JSON Logic condition object is required");
        }
        List<String> referencedPaths =
                PraxisFactTreeBuilder.extractReferencedFactPaths(source.condition());
        Optional<RuleErrorCatalog> reservedCatalog = family.reservedProductErrorCatalog();

        if (policy == OverridePolicy.REPLACEABLE) {
            DecisionBinding productBinding = requireProductBinding(baseline, slot.slotKey());
            String code = resolveCustomerReasonCode(source, slot, reservedCatalog);
            String message =
                    nonBlankOr(
                            source.definition().path("messagePtBr").asText(null),
                            "Regra governada customizada para o slot " + slot.slotKey() + ".");
            String targetField =
                    nonBlankOr(source.definition().path("targetField").asText(null), "draft");
            String bindingKey = "customer.t_" + sanitizedTenant + ".replace." + slot.slotKey();
            DecisionBinding replacement =
                    new DecisionBinding(
                            bindingKey,
                            slot.slotKey(),
                            DecisionSource.CUSTOMER,
                            CompositionPolicy.REPLACE_EXACT,
                            RuleExecutorRef.jsonLogic(source.condition()),
                            productBinding.dependsOn(),
                            productBinding.order(),
                            true,
                            RuleDecision.DENY,
                            code,
                            referencedPaths,
                            null);
            customErrors.put(
                    bindingKey,
                    new RuleErrorDescriptor(
                            code, targetField, message, "PRAXIS_RULES_STUDIO", "CUSTOMER"));
            List<DecisionBinding> nextBindings =
                    replaceProductSlotBinding(baseline.bindings(), slot.slotKey(), productBinding, replacement);
            return new ComposedGraph(withBindings(baseline, nextBindings), customErrors);
        }

        if (policy == OverridePolicy.RESTRICT_ONLY) {
            if (slot.cardinality() != SlotCardinality.MULTIPLE) {
                throw new IllegalArgumentException(
                        "RESTRICT_ONLY slot " + slot.slotKey() + " requires MULTIPLE cardinality");
            }
            String code = resolveCustomerReasonCode(source, slot, reservedCatalog);
            String message =
                    nonBlankOr(
                            source.definition().path("messagePtBr").asText(null),
                            "Restrição adicional governada para o slot " + slot.slotKey() + ".");
            String targetField =
                    nonBlankOr(source.definition().path("targetField").asText(null), "draft");
            String bindingKey =
                    "customer.t_" + sanitizedTenant + ".augment." + slot.slotKey() + "." + code;
            int nextOrder =
                    baseline.bindings().stream().mapToInt(DecisionBinding::order).max().orElse(0) + 1;
            DecisionBinding addition =
                    new DecisionBinding(
                            bindingKey,
                            slot.slotKey(),
                            DecisionSource.CUSTOMER,
                            CompositionPolicy.AUGMENT,
                            RuleExecutorRef.jsonLogic(source.condition()),
                            List.of(),
                            nextOrder,
                            true,
                            RuleDecision.DENY,
                            code,
                            referencedPaths,
                            null);
            customErrors.put(
                    bindingKey,
                    new RuleErrorDescriptor(
                            code, targetField, message, "PRAXIS_RULES_STUDIO", "CUSTOMER"));
            List<DecisionBinding> nextBindings = new ArrayList<>(baseline.bindings());
            nextBindings.add(addition);
            return new ComposedGraph(withBindings(baseline, nextBindings), customErrors);
        }

        throw new IllegalArgumentException(
                "Slot " + slot.slotKey() + " (" + policy + ") does not permit customization");
    }

    private static DecisionBinding requireProductBinding(
            RuleSetDefinition baseline, String slotKey) {
        return baseline.bindings().stream()
                .filter(b -> b.slotKey().equals(slotKey) && b.source() == DecisionSource.PRODUCT)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Slot " + slotKey + " has no baseline PRODUCT binding"));
    }

    private static List<DecisionBinding> replaceProductSlotBinding(
            List<DecisionBinding> original,
            String slotKey,
            DecisionBinding productBinding,
            DecisionBinding replacement) {
        List<DecisionBinding> updated = new ArrayList<>();
        for (DecisionBinding item : original) {
            if (item.slotKey().equals(slotKey)) {
                if (item.source() == DecisionSource.PRODUCT
                        && !item.bindingKey().equals(replacement.bindingKey())) {
                    updated.add(copyBinding(item, item.dependsOn(), false));
                }
                continue;
            }
            List<String> rewiredDeps =
                    item.dependsOn().stream()
                            .map(
                                    dep ->
                                            dep.equals(productBinding.bindingKey())
                                                    ? replacement.bindingKey()
                                                    : dep)
                            .distinct()
                            .toList();
            updated.add(copyBinding(item, rewiredDeps, item.enabled()));
        }
        updated.add(replacement);
        return updated;
    }

    private static DecisionBinding copyBinding(
            DecisionBinding source, List<String> dependsOn, boolean enabled) {
        return new DecisionBinding(
                source.bindingKey(),
                source.slotKey(),
                source.source(),
                source.compositionPolicy(),
                source.executor(),
                dependsOn,
                source.order(),
                enabled,
                source.falseDecision(),
                source.falseReasonCode(),
                source.requiredFactPaths(),
                source.parameterization());
    }

    private static RuleSetDefinition withBindings(
            RuleSetDefinition base, List<DecisionBinding> bindings) {
        return new RuleSetDefinition(
                base.ref(),
                base.availableRoots(),
                base.slots(),
                bindings,
                base.compatibility(),
                base.failPolicy(),
                base.errorDescriptors());
    }

    private static String resolveCustomerReasonCode(
            DomainRuleDefinitionResponse source,
            DecisionSlot slot,
            Optional<RuleErrorCatalog> reservedCatalog) {
        String raw = source.definition().path("falseReasonCode").asText("").trim();
        boolean collidesWithProduct =
                !raw.isEmpty()
                        && reservedCatalog.map(cat -> cat.find(raw).isPresent()).orElse(false);
        String candidate;
        if (!raw.isEmpty() && !collidesWithProduct && (raw.startsWith("CUST-") || raw.startsWith("CERG-"))) {
            candidate = raw;
        } else {
            String suffix =
                    (raw.isEmpty() ? slot.slotKey() : raw)
                            .toUpperCase(Locale.ROOT)
                            .replaceAll("[^A-Z0-9]+", "-")
                            .replaceAll("^-+|-+$", "");
            candidate = "CUST-" + (suffix.isEmpty() ? "RULE" : suffix);
        }
        return candidate.length() > 48 ? candidate.substring(0, 48) : candidate;
    }

    private static void requireExpectedDigest(String expected, String actual) {
        String value = requireText(expected, "expectedCompositionDigest");
        if (!MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(
                    "Composition changed after inspection; prepare a new candidate");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ComposedGraph(
            RuleSetDefinition definition, Map<String, RuleErrorDescriptor> errorDescriptors) {}

    private record PreparedCanonicalCandidate(
            DomainRuleCompositionManifestRequest manifestRequest,
            RuleSetCompositionCandidate candidate) {}
}
