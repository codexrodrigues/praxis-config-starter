package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadActivationType;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSet;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleEvaluationResult;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.TypedRuleEvaluationResult;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.runtime.PraxisRuleSetEngine;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.snapshot.CompiledRuleSnapshot;
import org.praxisplatform.rules.snapshot.PraxisRuleSnapshotCompiler;
import org.praxisplatform.rules.snapshot.RuleSetProductAdmissionVerifier;
import org.praxisplatform.rules.snapshot.TypedCompiledRuleSnapshot;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Canonical thread-safe governed rule bundle runtime for a resource family {@code O extends Enum<O>}.
 *
 * <p>Replaces per-resource {@code <Resource>Runtime} and {@code <Resource>Runtimes} boilerplate by
 * providing:
 * <ul>
 *   <li>Atomic head-set resolution via {@link PublishedRuleSnapshotHeadSetReader};</li>
 *   <li>Product-baseline verification via {@link RuleSetProductAdmissionVerifier} before compilation;</li>
 *   <li>Double-checked head fingerprint verification around compilation and atomic cache swap;</li>
 *   <li>Transactional {@code CommitFence} ({@link TransactionSynchronization#beforeCommit(boolean)})
 *       that re-verifies head-set fingerprint and temporal validity at commit time;</li>
 *   <li>Deterministic {@code policyBundleDigest} and {@code businessFactsDigest} evidence capture.</li>
 * </ul>
 *
 * @param <O> operation enum of the governed resource family
 */
public final class GovernedHostRuleBundleRuntime<O extends Enum<O>> {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Operation specification binding an enum constant {@code O} to its product rule set and
     * optional typed {@link RuleFactContract} (when {@code null}, admission validates against
     * {@link RuleSetDefinition#availableRoots()}).
     */
    public record OperationSpec<O extends Enum<O>>(
            O operation,
            String operationKey,
            RuleSetDefinition productRuleSet,
            RuleFactContract factContract) {
        public OperationSpec {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(operationKey, "operationKey");
            Objects.requireNonNull(productRuleSet, "productRuleSet");
        }

        public static <O extends Enum<O>> OperationSpec<O> ofUntyped(
                O operation, String operationKey, RuleSetDefinition productRuleSet) {
            return new OperationSpec<>(operation, operationKey, productRuleSet, null);
        }

        public boolean isTyped() {
            return factContract != null;
        }
    }

    /**
     * Tenant and environment scope key for governed rule bundle caching.
     */
    public record TenantEnvironmentScope(String tenantId, String environment) {
        public TenantEnvironmentScope {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId is required");
            }
            if (environment == null || environment.isBlank()) {
                throw new IllegalArgumentException("environment is required");
            }
            tenantId = tenantId.trim();
            environment = environment.trim();
        }
    }

    /**
     * Deterministic evaluation and evidence outcome for one governed operation invocation.
     */
    public record Admission(
            TypedRuleEvaluationResult evaluation,
            RuleDecisionPlan plan,
            String policyBundleDigest,
            String businessFactsDigest) {
        public JsonNode evidence() {
            return JSON.createObjectNode()
                    .put("policyBundle", policyBundleDigest)
                    .put("businessFacts", businessFactsDigest);
        }
    }

    private record PreparedOperation(
            CompiledRuleSnapshot compiledSnapshot,
            TypedCompiledRuleSnapshot typedSnapshot,
            String admissionDigest,
            String factContractDigest) {
        private PreparedOperation {
            Objects.requireNonNull(compiledSnapshot, "compiledSnapshot");
            Objects.requireNonNull(admissionDigest, "admissionDigest");
            Objects.requireNonNull(factContractDigest, "factContractDigest");
        }

        static PreparedOperation ofTyped(TypedCompiledRuleSnapshot typed) {
            return new PreparedOperation(
                    typed.snapshot(),
                    typed,
                    typed.typedPlan().admissionDigest(),
                    typed.typedPlan().contract().digest());
        }

        static PreparedOperation ofUntyped(
                CompiledRuleSnapshot compiled,
                RuleSetDefinition productRuleSet,
                String hostContractVersion,
                String operationKey) {
            String rootsDigest =
                    PraxisCanonicalJson.sha256(
                            JSON.valueToTree(productRuleSet.availableRoots()));
            String untypedAdmissionDigest =
                    PraxisCanonicalJson.sha256(
                            JSON.createObjectNode()
                                    .put("planDigest", compiled.plan().planDigest())
                                    .put("rootsDigest", rootsDigest)
                                    .put("hostContractVersion", hostContractVersion)
                                    .put("operationKey", operationKey));
            return new PreparedOperation(compiled, null, untypedAdmissionDigest, rootsDigest);
        }
    }

    /**
     * Prepared immutable bundle of compiled operation snapshots for a single {@link TenantEnvironmentScope}.
     */
    public static final class PreparedBundle<O extends Enum<O>> {
        private final TenantEnvironmentScope scope;
        private final Map<O, PreparedOperation> prepared;
        private final String bundleDigest;
        private final String hostContractVersion;
        private final String factProviderVersion;
        private final PraxisRuleSetEngine engine;
        private final BiFunction<RuleDecisionPlan, TypedRuleEvaluationResult, RuntimeException>
                violationExceptionFactory;

        private PreparedBundle(
                TenantEnvironmentScope scope,
                Map<O, PreparedOperation> prepared,
                Map<O, PublishedRuleSnapshotHead> activations,
                Map<O, OperationSpec<O>> specs,
                String hostContractVersion,
                String factProviderVersion,
                PraxisRuleSetEngine engine,
                BiFunction<RuleDecisionPlan, TypedRuleEvaluationResult, RuntimeException>
                        violationExceptionFactory) {
            this.scope = scope;
            this.prepared = Map.copyOf(prepared);
            this.hostContractVersion = hostContractVersion;
            this.factProviderVersion = factProviderVersion;
            this.engine = engine;
            this.violationExceptionFactory = violationExceptionFactory;
            ObjectNode identities = JSON.createObjectNode();
            prepared.entrySet().stream()
                    .sorted(Comparator.comparing(e -> specs.get(e.getKey()).operationKey()))
                    .forEach(
                            entry -> {
                                PublishedRuleSnapshotHead activation = activations.get(entry.getKey());
                                String opKey = specs.get(entry.getKey()).operationKey();
                                identities
                                        .putObject(opKey)
                                        .put(
                                                "content",
                                                entry.getValue()
                                                        .compiledSnapshot()
                                                        .snapshotContentHash())
                                        .put("admission", entry.getValue().admissionDigest())
                                        .put("headEtag", activation.headEtag())
                                        .put("activationRevision", activation.activationRevision());
                            });
            this.bundleDigest = PraxisCanonicalJson.sha256(identities);
        }

        public TenantEnvironmentScope scope() {
            return scope;
        }

        public String bundleDigest() {
            return bundleDigest;
        }

        public CompiledRuleSnapshot compiledSnapshot(O operation) {
            return Objects.requireNonNull(prepared.get(operation), "Unknown operation: " + operation)
                    .compiledSnapshot();
        }

        public TypedCompiledRuleSnapshot compiled(O operation) {
            PreparedOperation op =
                    Objects.requireNonNull(prepared.get(operation), "Unknown operation: " + operation);
            if (op.typedSnapshot() == null) {
                throw new IllegalStateException(
                        "Operation '"
                                + operation
                                + "' has no typed RuleFactContract; use compiledSnapshot(...) or admit(...) instead.");
            }
            return op.typedSnapshot();
        }

        public Admission admit(O operation, ObjectNode facts, String nowUtc, String zone) {
            return admit(operation, facts, nowUtc, zone, null);
        }

        public Admission admit(
                O operation,
                ObjectNode facts,
                String nowUtc,
                String zone,
                java.util.Set<String> authorizedOrganizationIds) {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(facts, "facts");
            PreparedOperation preparedOp =
                    Objects.requireNonNull(prepared.get(operation), "Unknown operation: " + operation);
            requireContextAndTemporalValidity(
                    preparedOp, facts, nowUtc, zone, authorizedOrganizationIds);
            String opKey = preparedOp.compiledSnapshot().snapshot().ruleSet().ref().operationKey();
            TypedRuleEvaluationResult result;
            if (preparedOp.typedSnapshot() != null) {
                result =
                        engine.evaluate(
                                preparedOp.typedSnapshot().typedPlan(),
                                hostContractVersion,
                                opKey,
                                factProviderVersion,
                                facts,
                                nowUtc,
                                zone);
            } else {
                RuleEvaluationResult untypedResult =
                        engine.evaluate(
                                preparedOp.compiledSnapshot().plan(),
                                facts,
                                nowUtc,
                                zone);
                result =
                        new TypedRuleEvaluationResult(
                                untypedResult,
                                preparedOp.admissionDigest(),
                                preparedOp.factContractDigest(),
                                hostContractVersion,
                                opKey,
                                factProviderVersion);
            }
            ObjectNode dependencies = facts.deepCopy();
            if (dependencies.get("context") instanceof ObjectNode ctxObj) {
                ctxObj.remove("operation");
            }
            return new Admission(
                    result,
                    preparedOp.compiledSnapshot().plan(),
                    bundleDigest,
                    PraxisCanonicalJson.sha256(dependencies));
        }

        public Admission enforceAdmission(O operation, ObjectNode facts, String nowUtc, String zone) {
            return enforceAdmission(operation, facts, nowUtc, zone, null);
        }

        public Admission enforceAdmission(
                O operation,
                ObjectNode facts,
                String nowUtc,
                String zone,
                java.util.Set<String> authorizedOrganizationIds) {
            Admission admission =
                    admit(operation, facts, nowUtc, zone, authorizedOrganizationIds);
            if (admission.evaluation().result().decision() != RuleDecision.ALLOW) {
                throw violationExceptionFactory.apply(admission.plan(), admission.evaluation());
            }
            return admission;
        }

        public TypedRuleEvaluationResult enforce(
                O operation, ObjectNode facts, String nowUtc, String zone) {
            return enforceAdmission(operation, facts, nowUtc, zone, null).evaluation();
        }

        public TypedRuleEvaluationResult enforce(
                O operation,
                ObjectNode facts,
                String nowUtc,
                String zone,
                java.util.Set<String> authorizedOrganizationIds) {
            return enforceAdmission(operation, facts, nowUtc, zone, authorizedOrganizationIds)
                    .evaluation();
        }

        private void requireContextAndTemporalValidity(
                PreparedOperation preparedOp,
                ObjectNode facts,
                String nowUtc,
                String zone,
                java.util.Set<String> authorizedOrganizationIds) {
            Instant instant = Instant.parse(Objects.requireNonNull(nowUtc, "nowUtc"));
            ZoneId zoneId = ZoneId.of(Objects.requireNonNull(zone, "zone"));
            JsonNode ctx = facts.path("context");
            if (ctx.isObject()) {
                JsonNode ctxNow = ctx.get("nowUtc");
                boolean hasExplicitContextClock =
                        ctxNow != null && ctxNow.isTextual() && !ctxNow.asText().isBlank();
                if (hasExplicitContextClock && !ctxNow.asText().equals(nowUtc)) {
                    throw new IllegalArgumentException(
                            "Relógio congelado nowUtc em context diverge da admissão");
                }
                JsonNode ctxZone = ctx.get("userTimeZone");
                if (ctxZone != null && ctxZone.isTextual() && !ctxZone.asText().isBlank()
                        && !ctxZone.asText().equals(zone)) {
                    throw new IllegalArgumentException(
                            "Fuso horário userTimeZone em context diverge da admissão");
                }
                JsonNode ctxRefDate = ctx.get("referenceDate");
                if ((preparedOp.typedSnapshot() != null || hasExplicitContextClock)
                        && ctxRefDate != null
                        && ctxRefDate.isTextual()
                        && !ctxRefDate.asText().isBlank()
                        && !ctxRefDate.asText().equals(instant.atZone(zoneId).toLocalDate().toString())) {
                    throw new IllegalArgumentException(
                            "Data de referência em context diverge do relógio congelado");
                }
                TenantScopeResolver.verifyFactsContextScope(
                        ctx, scope.tenantId(), authorizedOrganizationIds);
            }
            for (PreparedOperation policy : prepared.values()) {
                PublishedRuleSnapshot snapshot = policy.compiledSnapshot().snapshot();
                if (instant.isBefore(Instant.parse(snapshot.validFromUtc()))
                        || (snapshot.validUntilUtc() != null
                                && !instant.isBefore(Instant.parse(snapshot.validUntilUtc())))) {
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "A política autoritativa está fora da vigência.");
                }
            }
        }
    }

    /**
     * Scoped admission handle returned by {@link #require(TenantEnvironmentScope, Instant, boolean)}.
     */
    public record AdmissionScope<O extends Enum<O>>(
            PreparedBundle<O> runtime, Instant referenceInstant, String nowUtc, String zone) {
        public java.time.LocalDate referenceDate() {
            return referenceInstant.atZone(ZoneId.of(zone)).toLocalDate();
        }
    }

    private record Selection<O extends Enum<O>>(String fingerprint, PreparedBundle<O> runtime) {}

    private final Class<O> operationType;
    private final String ownerServiceKey;
    private final String ruleSetKey;
    private final String hostContractVersion;
    private final String factProviderVersion;
    private final Map<O, OperationSpec<O>> operationSpecs;
    private final List<String> operationKeys;
    private final PublishedRuleSnapshotHeadSetReader reader;
    private final Clock clock;
    private final ZoneId zone;
    private final RuleBindingExecutorRegistry executorRegistry;
    private final RuleErrorCatalog reservedProductErrorCatalog;
    private final BiFunction<RuleDecisionPlan, TypedRuleEvaluationResult, RuntimeException>
            violationExceptionFactory;
    private final PraxisRuleSetEngine engine;
    private final PraxisRuleSnapshotCompiler compiler;
    private final Map<TenantEnvironmentScope, AtomicReference<Selection<O>>> selections =
            new ConcurrentHashMap<>();

    public GovernedHostRuleBundleRuntime(
            Class<O> operationType,
            String ownerServiceKey,
            String ruleSetKey,
            String hostContractVersion,
            String factProviderVersion,
            List<OperationSpec<O>> specs,
            PublishedRuleSnapshotHeadSetReader reader,
            Clock clock,
            ZoneId zone,
            RuleBindingExecutorRegistry executorRegistry,
            RuleErrorCatalog reservedProductErrorCatalog) {
        this(
                operationType,
                ownerServiceKey,
                ruleSetKey,
                hostContractVersion,
                factProviderVersion,
                specs,
                reader,
                clock,
                zone,
                executorRegistry,
                reservedProductErrorCatalog,
                (plan, eval) ->
                        new org.praxisplatform.rules.violation.PraxisRuleViolationException(
                                plan,
                                eval.result(),
                                Objects.requireNonNull(
                                        reservedProductErrorCatalog,
                                        "reservedProductErrorCatalog is required for default violation mapping")));
    }

    public GovernedHostRuleBundleRuntime(
            Class<O> operationType,
            String ownerServiceKey,
            String ruleSetKey,
            String hostContractVersion,
            String factProviderVersion,
            List<OperationSpec<O>> specs,
            PublishedRuleSnapshotHeadSetReader reader,
            Clock clock,
            ZoneId zone,
            RuleBindingExecutorRegistry executorRegistry,
            RuleErrorCatalog reservedProductErrorCatalog,
            BiFunction<RuleDecisionPlan, TypedRuleEvaluationResult, RuntimeException>
                    violationExceptionFactory) {
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        this.ownerServiceKey = Objects.requireNonNull(ownerServiceKey, "ownerServiceKey");
        this.ruleSetKey = Objects.requireNonNull(ruleSetKey, "ruleSetKey");
        this.hostContractVersion = Objects.requireNonNull(hostContractVersion, "hostContractVersion");
        this.factProviderVersion = Objects.requireNonNull(factProviderVersion, "factProviderVersion");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.executorRegistry =
                executorRegistry == null ? RuleBindingExecutorRegistry.empty() : executorRegistry;
        this.reservedProductErrorCatalog = reservedProductErrorCatalog;
        this.violationExceptionFactory =
                Objects.requireNonNull(violationExceptionFactory, "violationExceptionFactory");
        this.engine = new PraxisRuleSetEngine(this.executorRegistry);
        this.compiler = new PraxisRuleSnapshotCompiler(this.executorRegistry);

        PraxisRulePlanCompiler planCompiler = new PraxisRulePlanCompiler(this.executorRegistry);
        EnumMap<O, OperationSpec<O>> map = new EnumMap<>(operationType);
        for (OperationSpec<O> spec : Objects.requireNonNull(specs, "specs")) {
            RuleSetDefinition canonicalProductRuleSet =
                    planCompiler.compile(spec.productRuleSet()).definition();
            OperationSpec<O> canonicalSpec =
                    new OperationSpec<>(
                            spec.operation(),
                            spec.operationKey(),
                            canonicalProductRuleSet,
                            spec.factContract());
            if (map.put(canonicalSpec.operation(), canonicalSpec) != null) {
                throw new IllegalArgumentException("Duplicate OperationSpec for " + spec.operation());
            }
        }
        if (!map.keySet().equals(Set.of(operationType.getEnumConstants()))) {
            throw new IllegalArgumentException(
                    "All enum constants of " + operationType.getSimpleName() + " must be configured");
        }
        this.operationSpecs = Map.copyOf(map);
        this.operationKeys =
                map.values().stream().map(OperationSpec::operationKey).toList();
    }

    public Class<O> operationType() {
        return operationType;
    }

    public String ownerServiceKey() {
        return ownerServiceKey;
    }

    public String ruleSetKey() {
        return ruleSetKey;
    }

    public String hostContractVersion() {
        return hostContractVersion;
    }

    public String factProviderVersion() {
        return factProviderVersion;
    }

    public Map<O, OperationSpec<O>> operationSpecs() {
        return operationSpecs;
    }

    public List<String> operationKeys() {
        return operationKeys;
    }

    public RuleBindingExecutorRegistry executorRegistry() {
        return executorRegistry;
    }

    public RuleErrorCatalog reservedProductErrorCatalog() {
        return reservedProductErrorCatalog;
    }

    /**
     * Admits and compiles a single candidate {@link PublishedRuleSnapshot} against the typed
     * {@link RuleFactContract} and product baseline.
     */
    public TypedCompiledRuleSnapshot admitCandidate(
            TenantEnvironmentScope scope, PublishedRuleSnapshot snapshot) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(snapshot, "snapshot");
        OperationSpec<O> spec = resolveVerifiedSpec(scope, snapshot);
        if (spec.factContract() == null) {
            throw new IllegalStateException(
                    "Operation '"
                            + spec.operationKey()
                            + "' of "
                            + ruleSetKey
                            + " has no typed RuleFactContract; use admitSnapshot(...) instead.");
        }
        Set<String> permittedRoots = new HashSet<>();
        spec.factContract()
                .schema()
                .schema()
                .path("properties")
                .fieldNames()
                .forEachRemaining(permittedRoots::add);
        RuleSetProductAdmissionVerifier.require(
                spec.productRuleSet(),
                snapshot.ruleSet(),
                permittedRoots,
                reservedProductErrorCatalog);
        return compiler.compileForOperation(snapshot, spec.factContract());
    }

    /**
     * Admits and compiles a single candidate {@link PublishedRuleSnapshot} for either a typed
     * ({@link RuleFactContract}-backed) or untyped ({@link RuleSetDefinition#availableRoots()}-backed)
     * governed operation.
     */
    public org.praxisplatform.rules.snapshot.CompiledRuleSnapshot admitSnapshot(
            TenantEnvironmentScope scope, PublishedRuleSnapshot snapshot) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(snapshot, "snapshot");
        OperationSpec<O> spec = resolveVerifiedSpec(scope, snapshot);
        if (spec.factContract() != null) {
            return admitCandidate(scope, snapshot).snapshot();
        }
        Set<String> permittedRoots = new HashSet<>(spec.productRuleSet().availableRoots());
        RuleSetProductAdmissionVerifier.require(
                spec.productRuleSet(),
                snapshot.ruleSet(),
                permittedRoots,
                reservedProductErrorCatalog);
        return compiler.compile(snapshot, hostContractVersion);
    }

    private OperationSpec<O> resolveVerifiedSpec(
            TenantEnvironmentScope scope, PublishedRuleSnapshot snapshot) {
        OperationSpec<O> spec =
                operationSpecs.values().stream()
                        .filter(s -> s.operationKey().equals(snapshot.ruleSet().ref().operationKey()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Operação fora do contrato de " + ruleSetKey));
        if (!snapshot.tenantId().equals(scope.tenantId())
                || !snapshot.environment().equals(scope.environment())
                || !snapshot.ownerServiceKey().equals(ownerServiceKey)
                || !snapshot.ruleSet().ref().sameIdentity(spec.productRuleSet().ref())) {
            throw new IllegalArgumentException("Identidade ou escopo do snapshot divergente");
        }
        return spec;
    }

    /**
     * Prepares a complete immutable bundle of compiled operation snapshots for {@code scope}.
     */
    public PreparedBundle<O> prepare(
            TenantEnvironmentScope scope, Map<O, PublishedRuleSnapshotHead> snapshots) {
        Objects.requireNonNull(scope, "scope");
        Map<O, PublishedRuleSnapshotHead> copy = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        if (!copy.keySet().equals(operationSpecs.keySet())) {
            throw new IllegalArgumentException("Todos os perfis da família devem estar preparados");
        }
        EnumMap<O, PreparedOperation> prepared = new EnumMap<>(operationType);
        for (Map.Entry<O, OperationSpec<O>> entry : operationSpecs.entrySet()) {
            O operation = entry.getKey();
            OperationSpec<O> spec = entry.getValue();
            PublishedRuleSnapshotHead activation = Objects.requireNonNull(copy.get(operation));
            PublishedRuleSnapshot snapshot = Objects.requireNonNull(activation.snapshot());
            if (!snapshot.ruleSet().ref().sameIdentity(spec.productRuleSet().ref())) {
                throw new IllegalArgumentException(
                        "O snapshot não corresponde à operação selecionada no conjunto.");
            }
            if (activation.activationType() != PublishedRuleSnapshotHeadActivationType.ACTIVE
                    || activation.activationRevision() < 1
                    || activation.headEtag() == null
                    || activation.headEtag().isBlank()
                    || activation.headEtag().length() > 512) {
                throw new IllegalArgumentException("Ativação autoritativa completa obrigatória.");
            }
            PreparedOperation preparedOperation;
            if (spec.isTyped()) {
                TypedCompiledRuleSnapshot compiled = admitCandidate(scope, snapshot);
                preparedOperation = PreparedOperation.ofTyped(compiled);
            } else {
                CompiledRuleSnapshot compiled = admitSnapshot(scope, snapshot);
                preparedOperation =
                        PreparedOperation.ofUntyped(
                                compiled,
                                spec.productRuleSet(),
                                hostContractVersion,
                                spec.operationKey());
            }
            if (!preparedOperation
                    .compiledSnapshot()
                    .snapshotContentHash()
                    .equals(activation.snapshotContentHash())) {
                throw new IllegalArgumentException("Conteúdo diverge da ativação autoritativa.");
            }
            prepared.put(operation, preparedOperation);
        }
        return new PreparedBundle<>(
                scope,
                prepared,
                copy,
                operationSpecs,
                hostContractVersion,
                factProviderVersion,
                engine,
                violationExceptionFactory);
    }

    /**
     * Resolves the authoritative bundle for {@code authorizedScope}, compiling on cache miss or
     * head rotation and registering a transactional {@code CommitFence} when {@code requireSyncTransaction}
     * is true or a mutable transaction is active.
     */
    public AdmissionScope<O> require(
            TenantEnvironmentScope authorizedScope,
            Instant explicitReferenceInstant,
            boolean requireSyncTransaction) {
        Objects.requireNonNull(authorizedScope, "authorizedScope");
        if (requireSyncTransaction
                && (!TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.isSynchronizationActive()
                        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "A admissão mutável exige transação sincronizada e gravável.");
        }
        PublishedRuleSnapshotHeadSetScope requested =
                new PublishedRuleSnapshotHeadSetScope(
                        authorizedScope.tenantId(),
                        authorizedScope.environment(),
                        ruleSetKey,
                        operationKeys);
        AtomicReference<Selection<O>> state =
                selections.computeIfAbsent(authorizedScope, ignored -> new AtomicReference<>());
        Selection<O> previous = state.get();
        try {
            PublishedRuleSnapshotHeadSet selected = current(requested);
            requireEffective(selected, clock.instant());
            PreparedBundle<O> admitted;
            if (previous != null && selected.fingerprint().equals(previous.fingerprint())) {
                admitted = previous.runtime();
            } else {
                EnumMap<O, PublishedRuleSnapshotHead> heads = new EnumMap<>(operationType);
                for (Map.Entry<O, OperationSpec<O>> entry : operationSpecs.entrySet()) {
                    heads.put(
                            entry.getKey(),
                            selected.heads().get(entry.getValue().operationKey()));
                }
                PreparedBundle<O> candidate = prepare(authorizedScope, heads);
                PublishedRuleSnapshotHeadSet confirmed = current(requested);
                requireEffective(confirmed, clock.instant());
                if (!selected.fingerprint().equals(confirmed.fingerprint())) {
                    throw new IllegalStateException("Head selection changed during compilation");
                }
                Selection<O> replacement = new Selection<>(confirmed.fingerprint(), candidate);
                if (!state.compareAndSet(previous, replacement)) {
                    Selection<O> winner = state.get();
                    if (winner == null || !winner.fingerprint().equals(replacement.fingerprint())) {
                        throw new IllegalStateException(
                                "Another policy refresh superseded this compilation");
                    }
                    admitted = winner.runtime();
                } else {
                    admitted = candidate;
                }
            }
            bindCommitFence(authorizedScope, requested, selected.fingerprint());
            Instant reference =
                    explicitReferenceInstant != null ? explicitReferenceInstant : clock.instant();
            return new AdmissionScope<>(admitted, reference, reference.toString(), zone.getId());
        } catch (ResponseStatusException explicitStatus) {
            state.compareAndSet(previous, null);
            throw explicitStatus;
        } catch (RuntimeException unavailable) {
            state.compareAndSet(previous, null);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "A política autoritativa para " + ruleSetKey + " está indisponível.",
                    unavailable);
        }
    }

    private void bindCommitFence(
            TenantEnvironmentScope scope,
            PublishedRuleSnapshotHeadSetScope requested,
            String fingerprint) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return;
        }
        for (TransactionSynchronization existing :
                TransactionSynchronizationManager.getSynchronizations()) {
            if (existing instanceof GovernedHostRuleBundleRuntime<?>.CommitFence fence
                    && fence.owner() == this
                    && fence.scope.equals(scope)) {
                if (!fence.fingerprint.equals(fingerprint)) {
                    throw unavailablePolicy();
                }
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(
                new CommitFence(scope, requested, fingerprint));
    }

    private final class CommitFence implements TransactionSynchronization {
        private final TenantEnvironmentScope scope;
        private final PublishedRuleSnapshotHeadSetScope requested;
        private final String fingerprint;

        private CommitFence(
                TenantEnvironmentScope scope,
                PublishedRuleSnapshotHeadSetScope requested,
                String fingerprint) {
            this.scope = scope;
            this.requested = requested;
            this.fingerprint = fingerprint;
        }

        private GovernedHostRuleBundleRuntime<O> owner() {
            return GovernedHostRuleBundleRuntime.this;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (readOnly) {
                return;
            }
            try {
                PublishedRuleSnapshotHeadSet finalSelection = current(requested);
                requireEffective(finalSelection, clock.instant());
                if (!fingerprint.equals(finalSelection.fingerprint())) {
                    throw new IllegalStateException("Policy changed before final admission");
                }
            } catch (RuntimeException changed) {
                AtomicReference<Selection<O>> state = selections.get(scope);
                if (state != null) {
                    state.set(null);
                }
                throw unavailablePolicy();
            }
        }
    }

    private static ResponseStatusException unavailablePolicy() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A política autoritativa mudou antes da admissão final da transação.");
    }

    private PublishedRuleSnapshotHeadSet current(PublishedRuleSnapshotHeadSetScope requested) {
        PublishedRuleSnapshotHeadSet set =
                reader.findActive(requested)
                        .orElseThrow(
                                () -> new IllegalStateException("Required operation head is absent"));
        if (!requested.equals(set.scope())) {
            throw new IllegalStateException("Control-plane selection scope differs");
        }
        return set;
    }

    private static void requireEffective(PublishedRuleSnapshotHeadSet set, Instant now) {
        for (PublishedRuleSnapshotHead head : set.heads().values()) {
            PublishedRuleSnapshot snapshot = head.snapshot();
            if (now.isBefore(Instant.parse(snapshot.validFromUtc()))
                    || (snapshot.validUntilUtc() != null
                            && !now.isBefore(Instant.parse(snapshot.validUntilUtc())))) {
                throw new IllegalStateException(
                        "A required operation policy is not currently effective");
            }
        }
    }
}
