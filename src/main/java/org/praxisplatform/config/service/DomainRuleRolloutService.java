package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicy;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotRollout;
import org.praxisplatform.config.domain.DomainRuleSnapshotRolloutEvent;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeRequest;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutCreateRequest;
import org.praxisplatform.config.dto.DomainRuleRolloutCatalogItemResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutReadinessResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleCandidateProbeRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** Owns the observational phase of staged snapshot rollout; it never mutates the active head. */
public class DomainRuleRolloutService implements DomainRuleSnapshotActivationGate {
  private static final Set<String> OPEN = Set.of("PREPARING", "READY", "BLOCKED");
  private final DomainRuleRolloutPolicyRepository policies;
  private final DomainRuleSnapshotRolloutRepository rollouts;
  private final DomainRuleCandidateProbeRepository probes;
  private final DomainRuleSnapshotRolloutEventRepository events;
  private final DomainRuleSnapshotRepository snapshots;
  private final DomainRuleSnapshotHeadRepository heads;
  private final DomainRuleRolloutPolicyService policyService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DomainRuleRolloutService(DomainRuleRolloutPolicyRepository policies,
      DomainRuleSnapshotRolloutRepository rollouts, DomainRuleCandidateProbeRepository probes,
      DomainRuleSnapshotRolloutEventRepository events, DomainRuleSnapshotRepository snapshots,
      DomainRuleSnapshotHeadRepository heads, DomainRuleRolloutPolicyService policyService,
      ObjectMapper objectMapper, Clock clock) {
    this.policies = policies; this.rollouts = rollouts; this.probes = probes; this.events = events;
    this.snapshots = snapshots; this.heads = heads; this.policyService = policyService;
    this.objectMapper = objectMapper; this.clock = clock;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutResponse create(DomainRuleRolloutCreateRequest request,
      DomainRuleGovernancePrincipal principal, String ifMatch) {
    if (request == null || request.candidateSnapshotKey() == null) throw bad("candidateSnapshotKey is required");
    DomainRuleSnapshot candidate = snapshots.findByTenantIdAndEnvironmentAndSnapshotKey(
        principal.tenantId(), principal.environment(), request.candidateSnapshotKey())
        .orElseThrow(() -> missing("Candidate snapshot was not found"));
    var head = heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        principal.tenantId(), principal.environment(), candidate.getRuleSetKey())
        .orElseThrow(() -> missing("RuleSet head was not found"));
    var condition = HttpEntityTagCondition.parse(ifMatch);
    if (condition.isEmpty()) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
    if (!condition.matchesStrong(head.getHeadEtag().toString())) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_FAILED, "Head ETag is stale");
    if (candidate.getId().equals(head.getActiveSnapshotId())) throw conflict("Candidate is already active");
    if (!rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        principal.tenantId(), principal.environment(), candidate.getRuleSetKey(), OPEN).isEmpty())
      throw conflict("An open rollout already exists for this RuleSet");
    DomainRuleRolloutPolicy policy = policyService.requireActiveOrBootstrap(
        candidate.getRuleSetKey(), principal);
    Instant now = clock.instant();
    Instant expiresAt = request.expiresAtUtc();
    if (expiresAt != null && !expiresAt.isAfter(now))
      throw bad("expiresAtUtc must be in the future");
    if (policy.getMaximumRolloutAgeSeconds() != null) {
      Instant maximumExpiry = now.plusSeconds(policy.getMaximumRolloutAgeSeconds());
      if (expiresAt == null) expiresAt = maximumExpiry;
      else if (expiresAt.isAfter(maximumExpiry))
        throw bad("expiresAtUtc exceeds the active rollout policy maximum age");
    }
    var rollout = rollouts.save(DomainRuleSnapshotRollout.builder().id(UUID.randomUUID())
        .tenantId(principal.tenantId()).environment(principal.environment())
        .ruleSetKey(candidate.getRuleSetKey()).candidateSnapshotId(candidate.getId())
        .expectedActiveSnapshotId(head.getActiveSnapshotId()).expectedHeadEtag(head.getHeadEtag())
        .policyId(policy.getId()).status("PREPARING").createdBy(principal.actorRef())
        .createdAt(now).updatedAt(now).expiresAt(expiresAt).rowVersion(0L).build());
    append(rollout, "CREATED", principal.actorRef(), now);
    DomainRuleSnapshot active = snapshots.findById(head.getActiveSnapshotId()).orElseThrow();
    return response(rollout, candidate, active, policy);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleCandidateProbeResponse probe(UUID rolloutId, DomainRuleCandidateProbeRequest request,
      DomainRuleGovernancePrincipal principal) {
    var rollout = rollouts.findForUpdateByIdAndTenantIdAndEnvironment(
            rolloutId, principal.tenantId(), principal.environment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    if (!OPEN.contains(rollout.getStatus())) throw conflict("Rollout no longer accepts probes");
    DomainRuleSnapshot candidate = snapshots.findById(rollout.getCandidateSnapshotId()).orElseThrow();
    if (request == null || request.observedAtUtc() == null
        || !candidate.getSnapshotKey().equals(request.candidateSnapshotKey())
        || !candidate.getContentHash().equals(request.candidateContentHash())) throw bad("Candidate identity does not match rollout");
    boolean compatible = candidateCompatible(candidate, request);
    boolean storedReady = request.preloadReady() && compatible;
    String failureCode = request.preloadReady() && !compatible
        ? "RUNTIME_INCOMPATIBLE" : request.failureCode();
    int changed = probes.upsertIfNewer(UUID.randomUUID(), rolloutId, principal.tenantId(),
        principal.environment(), rollout.getRuleSetKey(), principal.actorRef(), candidate.getId(),
        candidate.getSnapshotKey(), candidate.getContentHash(), storedReady,
        request.hostContractVersion(), request.engineContractVersion(), request.jsonLogicDialectVersion(),
        request.jsonLogicCorpusSha256(), request.implementationCatalogDigest(), failureCode,
        request.observedAtUtc(), clock.instant());
    return new DomainRuleCandidateProbeResponse(changed == 1, request.observedAtUtc());
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRulePendingRolloutResponse> pending(
      String ruleSetKey, DomainRuleGovernancePrincipal principal) {
    String key = requireText(ruleSetKey, "ruleSetKey");
    Instant now = clock.instant();
    return rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
            principal.tenantId(), principal.environment(), key, OPEN).stream()
        .filter(rollout -> rollout.getExpiresAt() == null || rollout.getExpiresAt().isAfter(now))
        .filter(rollout -> heads.findByTenantIdAndEnvironmentAndRuleSetKey(
                principal.tenantId(), principal.environment(), key)
            .filter(head -> head.getActiveSnapshotId().equals(rollout.getExpectedActiveSnapshotId())
                && head.getHeadEtag().equals(rollout.getExpectedHeadEtag()))
            .isPresent())
        .findFirst()
        .map(rollout -> {
          DomainRuleSnapshot candidate = snapshots.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
                  rollout.getCandidateSnapshotId(), principal.tenantId(), principal.environment(), key)
              .orElseThrow(() -> new IllegalStateException("Rollout references missing candidate"));
          return new DomainRulePendingRolloutResponse(
              rollout.getId(), key, candidate.getSnapshotKey(), candidate.getContentHash(),
              rollout.getStatus(), rollout.getExpiresAt());
        });
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleRolloutCatalogResponse catalog(
      String ruleSetKey, DomainRuleGovernancePrincipal principal, boolean canOperate) {
    String key = requireText(ruleSetKey, "ruleSetKey");
    Instant now = clock.instant();
    var head = heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        principal.tenantId(), principal.environment(), key).orElse(null);
    var items = rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
            principal.tenantId(), principal.environment(), key, OPEN).stream()
        .map(rollout -> {
          var summary = readiness(rollout.getId(), principal);
          boolean expired = rollout.getExpiresAt() != null && !rollout.getExpiresAt().isAfter(now);
          boolean current = head != null
              && head.getActiveSnapshotId().equals(rollout.getExpectedActiveSnapshotId())
              && head.getHeadEtag().equals(rollout.getExpectedHeadEtag());
          var actions = new java.util.ArrayList<String>();
          if (canOperate) actions.add("CANCEL");
          if (canOperate && !expired && current && summary.activationReady())
            actions.add("ACTIVATE_CANDIDATE");
          DomainRuleSnapshot candidate = snapshots.findById(rollout.getCandidateSnapshotId())
              .orElseThrow(() -> new IllegalStateException("Rollout references missing candidate"));
          DomainRuleSnapshot active = snapshots.findById(rollout.getExpectedActiveSnapshotId())
              .orElseThrow(() -> new IllegalStateException("Rollout references missing active snapshot"));
          DomainRuleRolloutPolicy policy = policies.findById(rollout.getPolicyId()).orElseThrow();
          return new DomainRuleRolloutCatalogItemResponse(
              response(rollout, candidate, active, policy), summary, current, expired,
              List.copyOf(actions));
        }).toList();
    return new DomainRuleRolloutCatalogResponse(
        key, items, canOperate && items.isEmpty() ? List.of("CREATE_ROLLOUT") : List.of());
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleRolloutReadinessResponse readiness(UUID rolloutId, DomainRuleGovernancePrincipal principal) {
    var rollout = scoped(rolloutId, principal);
    var policy = policies.findById(rollout.getPolicyId()).orElseThrow();
    var all = probes.findByRolloutId(rolloutId);
    Instant staleBefore = clock.instant().minusSeconds(policy.getStaleAfterSeconds());
    long stale = all.stream().filter(p -> p.getObservedAt().isBefore(staleBefore)).count();
    var fresh = all.stream().filter(p -> !p.getObservedAt().isBefore(staleBefore)).toList();
    long ready = fresh.stream().filter(p -> Boolean.TRUE.equals(p.getPreloadReady())).count();
    long incompatible = fresh.stream().filter(p -> "RUNTIME_INCOMPATIBLE".equals(p.getFailureCode())).count();
    long unavailable = fresh.size() - ready - incompatible;
    double ratio = fresh.isEmpty() ? 0 : ((double) ready / fresh.size());
    boolean enough = fresh.size() >= policy.getMinimumFreshProbes()
        && ratio >= policy.getMinimumReadyRatio().doubleValue();
    boolean requiredReady = enough
        && (!Boolean.TRUE.equals(policy.getBlockOnIncompatible()) || incompatible == 0);
    boolean activationReady = "OBSERVE_ONLY".equals(policy.getEnforcementMode()) || requiredReady;
    String derivedStatus = requiredReady ? "READY" : "BLOCKED";
    return new DomainRuleRolloutReadinessResponse(rolloutId, rollout.getRuleSetKey(),
        snapshots.findById(rollout.getCandidateSnapshotId()).orElseThrow().getSnapshotKey(),
        derivedStatus, policy.getEnforcementMode(), policy.getMinimumFreshProbes(),
        policy.getMinimumReadyRatio().doubleValue(), all.size(), ready, incompatible, unavailable, stale,
        activationReady, staleBefore);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public void cancel(UUID rolloutId, DomainRuleGovernancePrincipal principal) {
    var rollout = scoped(rolloutId, principal);
    if (!OPEN.contains(rollout.getStatus())) throw conflict("Rollout is already terminal");
    rollout.setStatus("CANCELLED"); rollout.setUpdatedAt(clock.instant()); rollouts.save(rollout);
    append(rollout, "CANCELLED", principal.actorRef(), clock.instant());
  }

  @Override
  public void requireAllowed(UUID rolloutId, DomainRuleSnapshot target,
      org.praxisplatform.config.domain.DomainRuleSnapshotHead currentHead, String actorRef) {
    DomainRuleRolloutPolicy activePolicy = policies
        .findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
            target.getTenantId(), target.getEnvironment(), target.getRuleSetKey())
        .orElse(null);
    boolean required = activePolicy != null
        && "REQUIRED".equals(activePolicy.getEnforcementMode());
    if (rolloutId == null) {
      if (required) throw conflict("A READY rollout is required by the active policy");
      return;
    }
    var rollout = rollouts.findForUpdateByIdAndTenantIdAndEnvironment(
            rolloutId, target.getTenantId(), target.getEnvironment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    if (!target.getId().equals(rollout.getCandidateSnapshotId())
        || !currentHead.getActiveSnapshotId().equals(rollout.getExpectedActiveSnapshotId())
        || !currentHead.getHeadEtag().equals(rollout.getExpectedHeadEtag()))
      throw conflict("Rollout candidate or expected head no longer matches activation");
    if (activePolicy != null && !activePolicy.getId().equals(rollout.getPolicyId()))
      throw conflict("Rollout policy is no longer the active policy");
    if (!OPEN.contains(rollout.getStatus())
        || (rollout.getExpiresAt() != null && !rollout.getExpiresAt().isAfter(clock.instant())))
      throw conflict("Rollout is terminal or expired");
    if (!required) return;
    var summary = readiness(rolloutId,
        new DomainRuleGovernancePrincipal(target.getTenantId(), actorRef, target.getEnvironment()));
    if (!summary.activationReady()) throw conflict("Candidate preload quorum is not ready");
  }

  @Override
  public void activationCompleted(UUID rolloutId, DomainRuleSnapshot target, String actorRef) {
    if (rolloutId == null) return;
    var rollout = rollouts.findForUpdateByIdAndTenantIdAndEnvironment(
            rolloutId, target.getTenantId(), target.getEnvironment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    if (!target.getId().equals(rollout.getCandidateSnapshotId())
        || !OPEN.contains(rollout.getStatus()))
      throw conflict("Rollout cannot be completed for the activated snapshot");
    rollout.setStatus("ACTIVATED"); rollout.setUpdatedAt(clock.instant()); rollouts.save(rollout);
    append(rollout, "ACTIVATED", actorRef, clock.instant());
  }

  private DomainRuleSnapshotRollout scoped(UUID id, DomainRuleGovernancePrincipal p) {
    return rollouts.findByIdAndTenantIdAndEnvironment(id, p.tenantId(), p.environment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
  }
  private void append(DomainRuleSnapshotRollout r, String type, String actor, Instant now) {
    events.save(DomainRuleSnapshotRolloutEvent.builder().id(UUID.randomUUID()).rolloutId(r.getId())
        .tenantId(r.getTenantId()).environment(r.getEnvironment()).ruleSetKey(r.getRuleSetKey())
        .eventType(type).actorRef(actor).safeMetadata("{}").createdAt(now).build());
  }
  private boolean candidateCompatible(DomainRuleSnapshot candidate,
      DomainRuleCandidateProbeRequest request) {
    try {
      var snapshot = objectMapper.readTree(candidate.getSnapshotPayload());
      var runtime = snapshot.path("ruleSet").path("compatibility");
      var manifest = objectMapper.readTree(candidate.getCompositionManifest());
      return snapshot.path("requiredHostContractVersion").asText().equals(request.hostContractVersion())
          && runtime.path("engineContractVersion").asText().equals(request.engineContractVersion())
          && runtime.path("jsonLogicDialectVersion").asText().equals(request.jsonLogicDialectVersion())
          && runtime.path("jsonLogicCorpusSha256").asText().equals(request.jsonLogicCorpusSha256())
          && manifest.path("implementationCatalogDigest").asText()
              .equals(request.implementationCatalogDigest());
    } catch (Exception invalidStoredCandidate) {
      throw new IllegalStateException("Stored rollout candidate compatibility could not be read",
          invalidStoredCandidate);
    }
  }
  private DomainRuleRolloutResponse response(DomainRuleSnapshotRollout r, DomainRuleSnapshot c,
      DomainRuleSnapshot a, DomainRuleRolloutPolicy p) {
    return new DomainRuleRolloutResponse(r.getId(), r.getRuleSetKey(), c.getSnapshotKey(),
        c.getContentHash(), a.getSnapshotKey(), r.getExpectedHeadEtag().toString(), p.getPolicyKey(),
        p.getPolicyVersion(), p.getEnforcementMode(), r.getStatus(), r.getCreatedAt(), r.getExpiresAt());
  }
  private static DomainRuleSnapshotControlPlaneException bad(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, m); }
  private static DomainRuleSnapshotControlPlaneException missing(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.NOT_FOUND, m); }
  private static DomainRuleSnapshotControlPlaneException conflict(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.CONFLICT, m); }
  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw bad(field + " is required");
    return value.trim();
  }
}
