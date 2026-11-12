package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeRequest;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.TenantEnvironmentScope;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleImplementationCatalogFingerprint;
import org.praxisplatform.config.service.DomainRuleImplementationScope;
import org.praxisplatform.config.service.DomainRuleProductCatalog;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.snapshot.CompiledRuleSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registro auto-descoberto ({@code PLAT-EVO-05}) de todas as famílias de regras governadas
 * ({@link GovernedRuleFamilyDescriptor}) publicadas como beans Spring no host aplicativo.
 *
 * <p>Suporta tanto famílias com chaves de operação globais exclusivas (ex.:
 * {@code "ergon.vinculos:create"}) quanto múltiplas famílias compartilhando verbos genéricos (ex.:
 * {@code "validate"} ou {@code "EVALUATE"}), indexando de forma determinística pelo par composto
 * {@code (familyKey, operationKey)} e preservando semântica HTTP fail-closed idêntica ao plano de
 * controle de rollout ({@code 400}, {@code 403}, {@code 404}, {@code 409} e código canônico
 * {@link #FAILURE_CODE_RUNTIME_INCOMPATIBLE}).
 */
public final class GovernedRuleFamilyRegistry {

    /** Código canônico de rejeição de preload exigido por {@code DomainRuleRolloutService}. */
    public static final String FAILURE_CODE_RUNTIME_INCOMPATIBLE = "RUNTIME_INCOMPATIBLE";

    private final List<GovernedRuleFamilyDescriptor<?>> families;
    private final Map<String, GovernedRuleFamilyDescriptor<?>> byFamilyKey;
    private final Map<String, List<GovernedRuleFamilyDescriptor<?>>> byOperationKey;
    private final Map<String, GovernedRuleFamilyDescriptor<?>> byCompositeKey;

    public GovernedRuleFamilyRegistry(List<GovernedRuleFamilyDescriptor<?>> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        Map<String, GovernedRuleFamilyDescriptor<?>> familyIndex = new LinkedHashMap<>();
        Map<String, List<GovernedRuleFamilyDescriptor<?>>> operationIndex = new LinkedHashMap<>();
        Map<String, GovernedRuleFamilyDescriptor<?>> compositeIndex = new LinkedHashMap<>();
        for (GovernedRuleFamilyDescriptor<?> descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor");
            GovernedRuleFamilyDescriptor<?> prevFamily =
                    familyIndex.putIfAbsent(descriptor.familyKey(), descriptor);
            if (prevFamily != null) {
                throw new IllegalArgumentException(
                        "Duplicate GovernedRuleFamilyDescriptor for familyKey: "
                                + descriptor.familyKey());
            }
            for (String opKey : descriptor.operationKeys()) {
                compositeIndex.put(compositeKey(descriptor.familyKey(), opKey), descriptor);
                operationIndex
                        .computeIfAbsent(opKey, ignored -> new ArrayList<>())
                        .add(descriptor);
            }
        }
        Map<String, List<GovernedRuleFamilyDescriptor<?>>> immutableOperationIndex =
                new LinkedHashMap<>();
        operationIndex.forEach(
                (key, list) -> immutableOperationIndex.put(key, List.copyOf(list)));
        this.families = List.copyOf(descriptors);
        this.byFamilyKey = Collections.unmodifiableMap(familyIndex);
        this.byOperationKey = Collections.unmodifiableMap(immutableOperationIndex);
        this.byCompositeKey = Collections.unmodifiableMap(compositeIndex);
    }

    public List<GovernedRuleFamilyDescriptor<?>> families() {
        return families;
    }

    public Optional<GovernedRuleFamilyDescriptor<?>> findByFamilyKey(String familyKey) {
        if (familyKey == null || familyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFamilyKey.get(familyKey.trim()));
    }

    public GovernedRuleFamilyDescriptor<?> requireByFamilyKey(String familyKey) {
        return findByFamilyKey(familyKey)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown governed rule familyKey: " + familyKey));
    }

    public Optional<GovernedRuleFamilyDescriptor<?>> findByFamilyAndOperation(
            String familyKey, String operationKey) {
        if (familyKey == null
                || familyKey.isBlank()
                || operationKey == null
                || operationKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                byCompositeKey.get(compositeKey(familyKey.trim(), operationKey.trim())));
    }

    public List<GovernedRuleFamilyDescriptor<?>> findAllByOperationKey(String operationKey) {
        if (operationKey == null || operationKey.isBlank()) {
            return List.of();
        }
        return byOperationKey.getOrDefault(operationKey.trim(), List.of());
    }

    /**
     * Resolve o descritor único associado a {@code operationKey} quando não ambíguo. Caso múltiplas
     * famílias compartilhem o mesmo verbo (ex.: {@code "validate"}), falha fechado exigindo
     * desambiguação por {@code familyKey} via {@link #findByFamilyAndOperation(String, String)}.
     */
    public Optional<GovernedRuleFamilyDescriptor<?>> findByOperationKey(String operationKey) {
        List<GovernedRuleFamilyDescriptor<?>> matches = findAllByOperationKey(operationKey);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "OperationKey '"
                            + operationKey.trim()
                            + "' is shared by multiple governed rule families; resolve via"
                            + " findByFamilyAndOperation(familyKey, operationKey).");
        }
        return Optional.of(matches.get(0));
    }

    public GovernedRuleFamilyDescriptor<?> requireByOperationKey(String operationKey) {
        return findByOperationKey(operationKey)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown governed rule operationKey: " + operationKey));
    }

    /**
     * Resolve o {@link RuleFactCatalogDocument} curado registrado para {@code familyKey}, quando
     * disponível.
     */
    public Optional<RuleFactCatalogDocument> findFactCatalogByFamilyKey(String familyKey) {
        return findByFamilyKey(familyKey).flatMap(GovernedRuleFamilyDescriptor::factCatalogDocument);
    }

    /**
     * Constrói um {@link DomainRuleProductCatalog} canônico derivado automaticamente de todas as
     * famílias registradas.
     */
    public DomainRuleProductCatalog toProductCatalog() {
        List<DomainRuleProductProfile> allProfiles =
                families.stream().flatMap(f -> f.productProfiles().stream()).toList();
        return (scope, identity) -> {
            if (scope == null || identity == null) {
                return Optional.empty();
            }
            return allProfiles.stream()
                    .filter(p -> p.ownerServiceKey().equals(scope.ownerServiceKey()))
                    .filter(p -> p.ruleSet().ref().sameIdentity(identity))
                    .findFirst();
        };
    }

    /**
     * Executa o probe de candidato em rollout para um código numérico de organização/tenant sem
     * cadeias {@code if/else} por família.
     */
    public DomainRuleCandidateProbeResponse probeCandidate(
            int organization,
            String operationKey,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        if (organization <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A organização do probe deve ser positiva.");
        }
        return probeCandidate(
                Integer.toString(organization),
                operationKey,
                rolloutId,
                principal,
                snapshots,
                rollouts,
                implementations,
                mapper,
                clock);
    }

    /**
     * Executa o probe de candidato em rollout qualificado explicitamente por {@code ruleSetKey} e
     * {@code operationKey} para um código numérico de organização/tenant.
     */
    public DomainRuleCandidateProbeResponse probeCandidate(
            int organization,
            String ruleSetKey,
            String operationKey,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        if (organization <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A organização do probe deve ser positiva.");
        }
        return probeCandidate(
                Integer.toString(organization),
                ruleSetKey,
                operationKey,
                rolloutId,
                principal,
                snapshots,
                rollouts,
                implementations,
                mapper,
                clock);
    }

    /**
     * Executa o probe fail-closed de candidato em rollout qualificado explicitamente por
     * {@code ruleSetKey} e {@code operationKey}.
     */
    public DomainRuleCandidateProbeResponse probeCandidate(
            String tenantId,
            String ruleSetKey,
            String operationKey,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        validateProbeInputs(tenantId, rolloutId, principal, snapshots, rollouts, implementations, mapper, clock);
        String normalizedFamily = ruleSetKey == null ? "" : ruleSetKey.trim();
        String normalizedOp = operationKey == null ? "" : operationKey.trim();
        GovernedRuleFamilyDescriptor<?> descriptor =
                findByFamilyAndOperation(normalizedFamily, normalizedOp)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Família/operação governada não registrada no host: "
                                                        + normalizedFamily
                                                        + "::"
                                                        + normalizedOp));
        DomainRulePendingRolloutResponse pending =
                rollouts.pending(descriptor.familyKey(), normalizedOp, principal)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Não há rollout pendente para a operação informada."));
        if (!rolloutId.equals(pending.rolloutId()) || !normalizedOp.equals(pending.operationKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O rollout informado não corresponde ao candidato pendente da operação.");
        }
        return executeCandidateProbe(
                descriptor, normalizedOp, rolloutId, pending, principal, snapshots, rollouts, implementations, mapper, clock);
    }

    /**
     * Executa o probe fail-closed de candidato em rollout resolvendo automaticamente o
     * {@link GovernedRuleFamilyDescriptor} pela {@code operationKey} (suportando tanto operações
     * exclusivas quanto operações compartilhadas entre famílias via correlação determinística com
     * {@code rolloutId}).
     */
    public DomainRuleCandidateProbeResponse probeCandidate(
            String tenantId,
            String operationKey,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        validateProbeInputs(tenantId, rolloutId, principal, snapshots, rollouts, implementations, mapper, clock);
        String normalizedOp = operationKey == null ? "" : operationKey.trim();
        List<GovernedRuleFamilyDescriptor<?>> candidates = findAllByOperationKey(normalizedOp);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Operação governada não registrada no host: " + normalizedOp);
        }

        GovernedRuleFamilyDescriptor<?> matchedDescriptor = null;
        DomainRulePendingRolloutResponse matchedPending = null;
        boolean sawOtherPendingRollout = false;

        for (GovernedRuleFamilyDescriptor<?> candidate : candidates) {
            Optional<DomainRulePendingRolloutResponse> pendingOpt =
                    rollouts.pending(candidate.familyKey(), normalizedOp, principal);
            if (pendingOpt.isEmpty()) {
                continue;
            }
            DomainRulePendingRolloutResponse pending = pendingOpt.get();
            if (rolloutId.equals(pending.rolloutId())
                    && normalizedOp.equals(pending.operationKey())
                    && candidate.familyKey().equals(pending.ruleSetKey())) {
                matchedDescriptor = candidate;
                matchedPending = pending;
                break;
            }
            sawOtherPendingRollout = true;
        }

        if (matchedDescriptor == null || matchedPending == null) {
            if (sawOtherPendingRollout) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O rollout informado não corresponde ao candidato pendente da operação.");
            }
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Não há rollout pendente para a operação informada.");
        }

        return executeCandidateProbe(
                matchedDescriptor,
                normalizedOp,
                rolloutId,
                matchedPending,
                principal,
                snapshots,
                rollouts,
                implementations,
                mapper,
                clock);
    }

    private static void validateProbeInputs(
            String tenantId,
            UUID rolloutId,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        Objects.requireNonNull(rolloutId, "rolloutId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(rollouts, "rollouts");
        Objects.requireNonNull(implementations, "implementations");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(clock, "clock");
        if (tenantId == null || tenantId.isBlank() || !tenantId.trim().equals(principal.tenantId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A organização do probe diverge do escopo autenticado.");
        }
    }

    private DomainRuleCandidateProbeResponse executeCandidateProbe(
            GovernedRuleFamilyDescriptor<?> descriptor,
            String normalizedOp,
            UUID rolloutId,
            DomainRulePendingRolloutResponse pending,
            DomainRuleGovernancePrincipal principal,
            DomainRuleSnapshotService snapshots,
            DomainRuleRolloutService rollouts,
            DomainRuleImplementationCatalog implementations,
            ObjectMapper mapper,
            Clock clock) {
        DomainRuleSnapshotStoredResponse stored =
                snapshots
                        .findSnapshot(
                                principal.tenantId(),
                                principal.environment(),
                                pending.candidateSnapshotKey())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "O snapshot candidato do rollout não está publicado."));
        if (!pending.candidateContentHash().equals(stored.snapshotContentHash())
                || stored.snapshot() == null
                || !normalizedOp.equals(stored.snapshot().ruleSet().ref().operationKey())
                || !descriptor.familyKey().equals(stored.snapshot().ruleSet().ref().ruleSetKey())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O snapshot candidato diverge da identidade ou do hash esperado no rollout.");
        }

        RuleRuntimeCompatibility compatibility = RuleRuntimeCompatibility.current();
        DomainRuleImplementationScope implScope =
                new DomainRuleImplementationScope(
                        principal.tenantId(),
                        principal.environment(),
                        descriptor.ownerServiceKey());
        Collection<RuleImplementationRef> allowedImpls =
                implementations.allowedImplementations(implScope);
        String catalogDigest =
                DomainRuleImplementationCatalogFingerprint.sha256(mapper, implScope, allowedImpls);

        boolean preloadReady;
        String failureCode;
        try {
            CompiledRuleSnapshot admitted =
                    descriptor.admitCandidateSnapshot(
                            new TenantEnvironmentScope(
                                    principal.tenantId(), principal.environment()),
                            stored.snapshot());
            preloadReady =
                    stored.snapshotContentHash().equals(admitted.snapshotContentHash());
            failureCode = preloadReady ? null : FAILURE_CODE_RUNTIME_INCOMPATIBLE;
        } catch (RuntimeException rejected) {
            preloadReady = false;
            failureCode = FAILURE_CODE_RUNTIME_INCOMPATIBLE;
        }

        return rollouts.probe(
                rolloutId,
                new DomainRuleCandidateProbeRequest(
                        pending.candidateSnapshotKey(),
                        pending.candidateContentHash(),
                        preloadReady,
                        descriptor.hostContractVersion(),
                        compatibility.engineContractVersion(),
                        compatibility.jsonLogicDialectVersion(),
                        compatibility.jsonLogicCorpusSha256(),
                        catalogDigest,
                        failureCode,
                        clock.instant()),
                principal);
    }

    private static String compositeKey(String familyKey, String operationKey) {
        return familyKey + "::" + operationKey;
    }
}
