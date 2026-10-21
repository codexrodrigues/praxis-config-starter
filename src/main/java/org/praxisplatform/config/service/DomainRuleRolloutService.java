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
  private final DomainRuleSnapshotVerifier snapshotVerifier;
  private final Clock clock;
  private final DomainRuleSnapshotScopeLock scopeLock;
  private final DomainRuleEntityRefresh entityRefresh;

  public DomainRuleRolloutService(DomainRuleRolloutPolicyRepository policies,
      DomainRuleSnapshotRolloutRepository rollouts, DomainRuleCandidateProbeRepository probes,
      DomainRuleSnapshotRolloutEventRepository events, DomainRuleSnapshotRepository snapshots,
      DomainRuleSnapshotHeadRepository heads, DomainRuleRolloutPolicyService policyService,
      ObjectMapper objectMapper, Clock clock, DomainRuleImplementationCatalog implementationCatalog, DomainRuleSnapshotScopeLock scopeLock, DomainRuleEntityRefresh entityRefresh) {
    this.policies = policies; this.rollouts = rollouts; this.probes = probes; this.events = events;
    this.snapshots = snapshots; this.heads = heads; this.policyService = policyService;
    this.objectMapper = objectMapper; this.clock = clock;
    this.snapshotVerifier = new DomainRuleSnapshotVerifier(objectMapper, implementationCatalog);
    this.entityRefresh = java.util.Objects.requireNonNull(entityRefresh);
    this.scopeLock = java.util.Objects.requireNonNull(scopeLock);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutResponse create(DomainRuleRolloutCreateRequest request,
      DomainRuleGovernancePrincipal principal, String ifMatch, String ifNoneMatch) {
    if (request == null || request.candidateSnapshotKey() == null) throw bad("candidateSnapshotKey is required");
    entityRefresh.requireCleanSnapshotEntry();
    DomainRuleSnapshot candidate = snapshots.findByTenantIdAndEnvironmentAndSnapshotKey(
        principal.tenantId(), principal.environment(), request.candidateSnapshotKey())
        .orElseThrow(() -> missing("Candidate snapshot was not found"));
    scopeLock.lock(principal.tenantId(), principal.environment(), candidate.getRuleSetKey());
    var head = heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        principal.tenantId(), principal.environment(), candidate.getRuleSetKey()).orElse(null);
    if (head != null) entityRefresh.snapshotHead(head);
    verifyHeadPrecondition(head, ifMatch, ifNoneMatch);
    if (head != null && candidate.getId().equals(head.getActiveSnapshotId())) throw conflict("Candidate is already active");
    String candidateState = snapshotVerifier.governanceState(candidate);
    if (!"READY".equals(candidateState)) throw conflict("Candidate is not READY [" + candidateState + "]");
    if (!rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        principal.tenantId(), principal.environment(), candidate.getRuleSetKey(), OPEN).isEmpty())
      throw conflict("An open rollout already exists for this RuleSet");
    DomainRuleRolloutPolicy policy = head == null
        ? policyService.requireBootstrapPolicy(candidate.getRuleSetKey(), principal)
        : policyService.requireActiveOrBootstrap(candidate.getRuleSetKey(), principal);
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
        .expectedActiveSnapshotId(head == null ? null : head.getActiveSnapshotId()).expectedHeadEtag(head == null ? null : head.getHeadEtag())
        .policyId(policy.getId()).status("PREPARING").createdBy(principal.actorRef())
        .createdAt(now).updatedAt(now).expiresAt(expiresAt).build());
    append(rollout, "CREATED", principal.actorRef(), now);
    DomainRuleSnapshot active = head == null ? null : snapshots.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        head.getActiveSnapshotId(), principal.tenantId(), principal.environment(), candidate.getRuleSetKey()).orElseThrow();
    return response(rollout, candidate, active, policy);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleCandidateProbeResponse probe(UUID rolloutId, DomainRuleCandidateProbeRequest request,
      DomainRuleGovernancePrincipal principal) {
    entityRefresh.requireCleanSnapshotEntry();
    var rollout = rollouts.findByIdAndTenantIdAndEnvironment(
            rolloutId, principal.tenantId(), principal.environment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    entityRefresh.snapshotRollout(rollout);
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
        .filter(rollout -> expectedHeadMatches(rollout, heads.findByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), key).orElse(null)))
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
          boolean current = expectedHeadMatches(rollout, head);
          var actions = new java.util.ArrayList<String>();
          if (canOperate) actions.add("CANCEL");
          if (canOperate && !expired && current && summary.activationReady())
            actions.add("ACTIVATE_CANDIDATE");
          DomainRuleSnapshot candidate = snapshots.findById(rollout.getCandidateSnapshotId())
              .orElseThrow(() -> new IllegalStateException("Rollout references missing candidate"));
          DomainRuleSnapshot active = rollout.getExpectedActiveSnapshotId() == null ? null : snapshots
              .findByIdAndTenantIdAndEnvironmentAndRuleSetKey(rollout.getExpectedActiveSnapshotId(),
                  principal.tenantId(), principal.environment(), key)
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
    DomainRuleSnapshot candidate = snapshots.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
            rollout.getCandidateSnapshotId(), principal.tenantId(), principal.environment(), rollout.getRuleSetKey())
        .orElseThrow(() -> new IllegalStateException("Rollout references missing candidate"));
    boolean candidateReady = "READY".equals(snapshotVerifier.governanceState(candidate));
    var currentHead = heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        principal.tenantId(), principal.environment(), rollout.getRuleSetKey()).orElse(null);
    boolean current = expectedHeadMatches(rollout, currentHead);
    boolean bootstrap = rollout.getExpectedActiveSnapshotId() == null;
    boolean bootstrapAuthority = true;
    if (bootstrap) {
      try { DomainRuleRolloutPolicyService.requireBootstrapAuthority(policy); }
      catch (DomainRuleSnapshotControlPlaneException deniedBootstrap) { bootstrapAuthority = false; }
    }
    boolean activationReady = candidateReady && current && bootstrapAuthority
        && (bootstrap ? requiredReady : ("OBSERVE_ONLY".equals(policy.getEnforcementMode()) || requiredReady));
    String derivedStatus = candidateReady && requiredReady ? "READY" : "BLOCKED";
    return new DomainRuleRolloutReadinessResponse(rolloutId, rollout.getRuleSetKey(),
        candidate.getSnapshotKey(),
        derivedStatus, policy.getEnforcementMode(), policy.getMinimumFreshProbes(),
        policy.getMinimumReadyRatio().doubleValue(), all.size(), ready, incompatible, unavailable, stale,
        activationReady, staleBefore);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public void cancel(UUID rolloutId, DomainRuleGovernancePrincipal principal) {
    entityRefresh.requireCleanSnapshotEntry();
    String key = rollouts.findRuleSetKeyByIdAndTenantIdAndEnvironment(
        rolloutId, principal.tenantId(), principal.environment()).orElseThrow(() -> missing("Rollout was not found"));
    scopeLock.lock(principal.tenantId(), principal.environment(), key);
    var rollout = rollouts.findByIdAndTenantIdAndEnvironment(
        rolloutId, principal.tenantId(), principal.environment()).orElseThrow(() -> missing("Rollout was not found"));
    entityRefresh.snapshotRollout(rollout);
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
    if (activePolicy != null) entityRefresh.rolloutPolicy(activePolicy);
    boolean bootstrap = currentHead == null;
    if (bootstrap) DomainRuleRolloutPolicyService.requireBootstrapAuthority(activePolicy);
    boolean required = bootstrap || (activePolicy != null && "REQUIRED".equals(activePolicy.getEnforcementMode()));
    if (rolloutId == null) {
      if (required) throw conflict("A READY rollout is required by the active policy");
      return;
    }
    var rollout = rollouts.findByIdAndTenantIdAndEnvironment(
            rolloutId, target.getTenantId(), target.getEnvironment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    entityRefresh.snapshotRollout(rollout);
    if (!target.getId().equals(rollout.getCandidateSnapshotId())
        || !expectedHeadMatches(rollout, currentHead))
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
    var rollout = rollouts.findByIdAndTenantIdAndEnvironment(
            rolloutId, target.getTenantId(), target.getEnvironment())
        .orElseThrow(() -> missing("Rollout was not found in the requested scope"));
    entityRefresh.snapshotRollout(rollout);
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
        c.getContentHash(), a == null ? null : a.getSnapshotKey(),
        r.getExpectedHeadEtag() == null ? null : r.getExpectedHeadEtag().toString(), p.getPolicyKey(),
        p.getPolicyVersion(), p.getEnforcementMode(), r.getStatus(), r.getCreatedAt(), r.getExpiresAt());
  }
  private static boolean expectedHeadMatches(DomainRuleSnapshotRollout rollout,
      org.praxisplatform.config.domain.DomainRuleSnapshotHead head) {
    if (rollout.getExpectedActiveSnapshotId() == null || rollout.getExpectedHeadEtag() == null)
      return rollout.getExpectedActiveSnapshotId() == null && rollout.getExpectedHeadEtag() == null && head == null;
    return head != null && rollout.getExpectedActiveSnapshotId().equals(head.getActiveSnapshotId())
        && rollout.getExpectedHeadEtag().equals(head.getHeadEtag());
  }

  private static void verifyHeadPrecondition(org.praxisplatform.config.domain.DomainRuleSnapshotHead head,
      String ifMatch, String ifNoneMatch) {
    var match = HttpEntityTagCondition.parse(ifMatch);
    var absent = HttpEntityTagCondition.parse(ifNoneMatch);
    if (!match.isEmpty() && !absent.isEmpty()) throw bad("Use exactly one head precondition");
    if (head == null) {
      if (!match.isEmpty()) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_FAILED, "RuleSet head is absent");
      if (!absent.wildcard()) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_REQUIRED, "Bootstrap requires If-None-Match: *");
    } else {
      if (!absent.isEmpty()) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_FAILED, "RuleSet head already exists");
      if (match.isEmpty()) throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
      if (match.wildcard() || !match.matchesStrong(head.getHeadEtag().toString()))
        throw new DomainRuleSnapshotControlPlaneException(HttpStatus.PRECONDITION_FAILED, "Head ETag is stale");
    }
  }

  private static DomainRuleSnapshotControlPlaneException bad(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, m); }
  private static DomainRuleSnapshotControlPlaneException missing(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.NOT_FOUND, m); }
  private static DomainRuleSnapshotControlPlaneException conflict(String m) { return new DomainRuleSnapshotControlPlaneException(HttpStatus.CONFLICT, m); }
  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw bad(field + " is required");
    return value.trim();
  }
}
