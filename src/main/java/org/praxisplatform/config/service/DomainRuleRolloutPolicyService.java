package org.praxisplatform.config.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicy;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyEvent;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCreateRequest;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyEventResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyMutationResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyEventRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyHeadRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** Governs immutable rollout-policy versions and their maker-checker active head. */
public class DomainRuleRolloutPolicyService {
  private static final Pattern POLICY_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
  private static final Set<String> OPEN_ROLLOUT = Set.of("PREPARING", "READY", "BLOCKED");
  private final DomainRuleRolloutPolicyRepository policies;
  private final DomainRuleRolloutPolicyHeadRepository policyHeads;
  private final DomainRuleRolloutPolicyEventRepository policyEvents;
  private final DomainRuleSnapshotHeadRepository snapshotHeads;
  private final DomainRuleSnapshotRolloutRepository rollouts;
  private final Clock clock;

  public DomainRuleRolloutPolicyService(DomainRuleRolloutPolicyRepository policies,
      DomainRuleRolloutPolicyHeadRepository policyHeads,
      DomainRuleRolloutPolicyEventRepository policyEvents,
      DomainRuleSnapshotHeadRepository snapshotHeads,
      DomainRuleSnapshotRolloutRepository rollouts, Clock clock) {
    this.policies = policies;
    this.policyHeads = policyHeads;
    this.policyEvents = policyEvents;
    this.snapshotHeads = snapshotHeads;
    this.rollouts = rollouts;
    this.clock = clock;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutPolicyMutationResponse create(
      DomainRuleRolloutPolicyCreateRequest request, DomainRuleGovernancePrincipal principal) {
    ValidatedPolicy input = validate(request);
    lockSnapshotHead(principal, input.ruleSetKey());
    DomainRuleRolloutPolicyHead head = ensureHead(principal, input.ruleSetKey());
    int version = policies.findMaximumVersion(principal.tenantId(), principal.environment(),
        input.ruleSetKey(), input.policyKey()).orElse(0) + 1;
    Instant now = clock.instant();
    DomainRuleRolloutPolicy policy = policies.save(DomainRuleRolloutPolicy.builder()
        .id(UUID.randomUUID()).tenantId(principal.tenantId()).environment(principal.environment())
        .ruleSetKey(input.ruleSetKey()).policyKey(input.policyKey()).policyVersion(version)
        .enforcementMode(input.enforcementMode()).minimumFreshProbes(input.minimumFreshProbes())
        .minimumReadyRatio(input.minimumReadyRatio())
        .blockOnIncompatible(input.blockOnIncompatible())
        .staleAfterSeconds(input.staleAfterSeconds())
        .maximumRolloutAgeSeconds(input.maximumRolloutAgeSeconds())
        .active(false).status("DRAFT").createdBy(requireActor(principal)).createdAt(now).build());
    append(policy, "CREATED", principal.actorRef(), null, now);
    return mutation(policy, head);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutPolicyMutationResponse approve(
      UUID policyId, DomainRuleGovernancePrincipal principal) {
    DomainRuleRolloutPolicy policy = scopedForUpdate(policyId, principal);
    DomainRuleRolloutPolicyHead head = requiredHead(principal, policy.getRuleSetKey());
    if (!"DRAFT".equals(policy.getStatus())) {
      if ("APPROVED".equals(policy.getStatus())) return mutation(policy, head);
      throw conflict("Only a DRAFT rollout policy can be approved");
    }
    if (policy.getCreatedBy().equals(requireActor(principal)))
      throw conflict("The rollout policy author cannot approve the same version");
    Instant now = clock.instant();
    policy.setStatus("APPROVED");
    policy.setApprovedBy(principal.actorRef());
    policy.setApprovedAt(now);
    policies.save(policy);
    append(policy, "APPROVED", principal.actorRef(), null, now);
    return mutation(policy, head);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutPolicyMutationResponse activate(
      UUID policyId, String ifMatch, DomainRuleGovernancePrincipal principal) {
    DomainRuleRolloutPolicy visible = scoped(policyId, principal);
    lockSnapshotHead(principal, visible.getRuleSetKey());
    DomainRuleRolloutPolicyHead head = requiredHeadForUpdate(principal, visible.getRuleSetKey());
    requireStrongMatch(ifMatch, head.getHeadEtag());
    DomainRuleRolloutPolicy target = scopedForUpdate(policyId, principal);
    if (policyId.equals(head.getActivePolicyId())) return mutation(target, head);
    if (!Set.of("APPROVED", "SUPERSEDED").contains(target.getStatus()))
      throw conflict("Only an APPROVED or SUPERSEDED rollout policy can become active");
    if (!rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        principal.tenantId(), principal.environment(), target.getRuleSetKey(), OPEN_ROLLOUT).isEmpty())
      throw conflict("An open rollout must finish or be cancelled before policy activation");

    Instant now = clock.instant();
    UUID nextEtag = UUID.randomUUID();
    if (head.getActivePolicyId() != null) {
      DomainRuleRolloutPolicy previous = policies
          .findForUpdateByIdAndTenantIdAndEnvironment(
              head.getActivePolicyId(), principal.tenantId(), principal.environment())
          .orElseThrow(() -> new IllegalStateException("Policy head references a missing active policy"));
      previous.setActive(false);
      previous.setStatus("SUPERSEDED");
      policies.save(previous);
      append(previous, "SUPERSEDED", principal.actorRef(), nextEtag, now);
    }
    target.setActive(true);
    target.setStatus("ACTIVE");
    target.setActivatedBy(requireActor(principal));
    target.setActivatedAt(now);
    policies.save(target);
    head.setActivePolicyId(target.getId());
    head.setActivationRevision(head.getActivationRevision() + 1);
    head.setHeadEtag(nextEtag);
    head.setUpdatedBy(principal.actorRef());
    head.setUpdatedAt(now);
    policyHeads.save(head);
    append(target, "ACTIVATED", principal.actorRef(), nextEtag, now);
    return mutation(target, head);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleRolloutPolicyCatalogResponse> catalog(
      String ruleSetKey, DomainRuleGovernancePrincipal principal) {
    String key = requireText(ruleSetKey, "ruleSetKey", 512);
    return policyHeads.findByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), key)
        .map(head -> new DomainRuleRolloutPolicyCatalogResponse(
            key, head.getActivationRevision(), head.getHeadEtag().toString(),
            head.getActivePolicyId() == null ? null : policies
                .findByIdAndTenantIdAndEnvironment(
                    head.getActivePolicyId(), principal.tenantId(), principal.environment())
                .map(this::response).orElseThrow(),
            policies.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtDesc(
                    principal.tenantId(), principal.environment(), key)
                .stream().map(this::response).toList()));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleRolloutPolicyEventResponse> timeline(
      String ruleSetKey, DomainRuleGovernancePrincipal principal) {
    String key = requireText(ruleSetKey, "ruleSetKey", 512);
    return policyEvents.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtAsc(
            principal.tenantId(), principal.environment(), key).stream()
        .map(event -> new DomainRuleRolloutPolicyEventResponse(
            event.getId(), event.getPolicyId(), event.getEventType(), event.getActorRef(),
            event.getHeadEtag() == null ? null : event.getHeadEtag().toString(),
            event.getCreatedAt()))
        .toList();
  }

  /** Server bootstrap used only when a RuleSet has no policy yet. */
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleRolloutPolicy requireActiveOrBootstrap(
      String ruleSetKey, DomainRuleGovernancePrincipal principal) {
    Optional<DomainRuleRolloutPolicy> active = policies
        .findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
            principal.tenantId(), principal.environment(), ruleSetKey);
    if (active.isPresent()) {
      ensureHead(principal, ruleSetKey);
      return active.orElseThrow();
    }
    Instant now = clock.instant();
    int version = policies.findMaximumVersion(
        principal.tenantId(), principal.environment(), ruleSetKey, "platform-observe-only")
        .orElse(0) + 1;
    DomainRuleRolloutPolicy policy = policies.save(DomainRuleRolloutPolicy.builder()
        .id(UUID.randomUUID()).tenantId(principal.tenantId()).environment(principal.environment())
        .ruleSetKey(ruleSetKey).policyKey("platform-observe-only").policyVersion(version)
        .enforcementMode("OBSERVE_ONLY").minimumFreshProbes(0)
        .minimumReadyRatio(BigDecimal.ZERO).blockOnIncompatible(false).staleAfterSeconds(120L)
        .active(true).status("ACTIVE").createdBy("platform-default").createdAt(now)
        .approvedBy("platform-default").approvedAt(now)
        .activatedBy("platform-default").activatedAt(now).build());
    DomainRuleRolloutPolicyHead head = policyHeads
        .findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), ruleSetKey)
        .orElseGet(() -> DomainRuleRolloutPolicyHead.builder()
            .id(UUID.randomUUID()).tenantId(principal.tenantId()).environment(principal.environment())
            .ruleSetKey(ruleSetKey).activationRevision(0L).rowVersion(0L).build());
    head.setActivePolicyId(policy.getId());
    head.setActivationRevision(head.getActivationRevision() + 1);
    head.setHeadEtag(UUID.randomUUID());
    head.setUpdatedBy("platform-default");
    head.setUpdatedAt(now);
    policyHeads.save(head);
    append(policy, "CREATED", "platform-default", null, now);
    append(policy, "APPROVED", "platform-default", null, now);
    append(policy, "ACTIVATED", "platform-default", head.getHeadEtag(), now);
    return policy;
  }

  private DomainRuleRolloutPolicyHead ensureHead(
      DomainRuleGovernancePrincipal principal, String ruleSetKey) {
    return policyHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), ruleSetKey)
        .orElseGet(() -> {
          Optional<DomainRuleRolloutPolicy> active = policies
              .findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
                  principal.tenantId(), principal.environment(), ruleSetKey);
          Instant now = clock.instant();
          return policyHeads.save(DomainRuleRolloutPolicyHead.builder().id(UUID.randomUUID())
              .tenantId(principal.tenantId()).environment(principal.environment())
              .ruleSetKey(ruleSetKey).activePolicyId(active.map(DomainRuleRolloutPolicy::getId).orElse(null))
              .activationRevision(active.isPresent() ? 1L : 0L).headEtag(UUID.randomUUID())
              .updatedBy(active.map(DomainRuleRolloutPolicy::getActivatedBy)
                  .filter(value -> value != null && !value.isBlank()).orElse(requireActor(principal)))
              .updatedAt(active.map(DomainRuleRolloutPolicy::getActivatedAt).orElse(now))
              .rowVersion(0L).build());
        });
  }

  private DomainRuleRolloutPolicyHead requiredHead(
      DomainRuleGovernancePrincipal principal, String ruleSetKey) {
    return policyHeads.findByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), ruleSetKey)
        .orElseThrow(() -> new IllegalStateException("Rollout policy head is missing"));
  }

  private DomainRuleRolloutPolicyHead requiredHeadForUpdate(
      DomainRuleGovernancePrincipal principal, String ruleSetKey) {
    return policyHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), ruleSetKey)
        .orElseThrow(() -> new IllegalStateException("Rollout policy head is missing"));
  }

  private void lockSnapshotHead(DomainRuleGovernancePrincipal principal, String ruleSetKey) {
    snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
            principal.tenantId(), principal.environment(), ruleSetKey)
        .orElseThrow(() -> missing("RuleSet head was not found"));
  }

  private DomainRuleRolloutPolicy scoped(UUID id, DomainRuleGovernancePrincipal principal) {
    return policies.findByIdAndTenantIdAndEnvironment(id, principal.tenantId(), principal.environment())
        .orElseThrow(() -> missing("Rollout policy was not found in the requested scope"));
  }

  private DomainRuleRolloutPolicy scopedForUpdate(UUID id, DomainRuleGovernancePrincipal principal) {
    return policies.findForUpdateByIdAndTenantIdAndEnvironment(
            id, principal.tenantId(), principal.environment())
        .orElseThrow(() -> missing("Rollout policy was not found in the requested scope"));
  }

  private void requireStrongMatch(String ifMatch, UUID current) {
    HttpEntityTagCondition condition;
    try {
      condition = HttpEntityTagCondition.parse(ifMatch);
    } catch (IllegalArgumentException invalid) {
      throw bad(invalid.getMessage());
    }
    if (condition.isEmpty())
      throw new DomainRuleSnapshotControlPlaneException(
          HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
    if (condition.wildcard() || !condition.matchesStrong(current.toString()))
      throw new DomainRuleSnapshotControlPlaneException(
          HttpStatus.PRECONDITION_FAILED, "Rollout policy head changed; reload before retrying");
  }

  private ValidatedPolicy validate(DomainRuleRolloutPolicyCreateRequest request) {
    if (request == null) throw bad("request is required");
    String ruleSetKey = requireText(request.ruleSetKey(), "ruleSetKey", 512);
    String policyKey = requireText(request.policyKey(), "policyKey", 128);
    if (!POLICY_KEY.matcher(policyKey).matches())
      throw bad("policyKey must be a lowercase stable identifier");
    if (policyKey.startsWith("platform-"))
      throw bad("policyKey prefix platform- is reserved for server-owned defaults");
    String mode = requireText(request.enforcementMode(), "enforcementMode", 32);
    if (!Set.of("OBSERVE_ONLY", "REQUIRED").contains(mode))
      throw bad("enforcementMode must be OBSERVE_ONLY or REQUIRED");
    int probes = request.minimumFreshProbes() == null ? -1 : request.minimumFreshProbes();
    BigDecimal ratio = request.minimumReadyRatio();
    long stale = request.staleAfterSeconds() == null ? -1 : request.staleAfterSeconds();
    if (probes < 0 || ratio == null || ratio.compareTo(BigDecimal.ZERO) < 0
        || ratio.compareTo(BigDecimal.ONE) > 0 || request.blockOnIncompatible() == null
        || stale <= 0 || (request.maximumRolloutAgeSeconds() != null
            && request.maximumRolloutAgeSeconds() <= 0))
      throw bad("Rollout policy quorum and timing values are invalid");
    if ("REQUIRED".equals(mode) && (probes < 1 || ratio.compareTo(BigDecimal.ZERO) <= 0))
      throw bad("REQUIRED policies need at least one fresh probe and a positive ready ratio");
    return new ValidatedPolicy(ruleSetKey, policyKey, mode, probes, ratio,
        request.blockOnIncompatible(), stale, request.maximumRolloutAgeSeconds());
  }

  private void append(DomainRuleRolloutPolicy policy, String type, String actor,
      UUID headEtag, Instant now) {
    policyEvents.save(DomainRuleRolloutPolicyEvent.builder().id(UUID.randomUUID())
        .policyId(policy.getId()).tenantId(policy.getTenantId())
        .environment(policy.getEnvironment()).ruleSetKey(policy.getRuleSetKey())
        .eventType(type).actorRef(actor).headEtag(headEtag).createdAt(now).build());
  }

  private DomainRuleRolloutPolicyMutationResponse mutation(
      DomainRuleRolloutPolicy policy, DomainRuleRolloutPolicyHead head) {
    return new DomainRuleRolloutPolicyMutationResponse(
        response(policy), head.getActivationRevision(), head.getHeadEtag().toString());
  }

  private DomainRuleRolloutPolicyResponse response(DomainRuleRolloutPolicy policy) {
    return new DomainRuleRolloutPolicyResponse(policy.getId(), policy.getRuleSetKey(),
        policy.getPolicyKey(), policy.getPolicyVersion(), policy.getStatus(),
        policy.getEnforcementMode(), policy.getMinimumFreshProbes(),
        policy.getMinimumReadyRatio(), Boolean.TRUE.equals(policy.getBlockOnIncompatible()),
        policy.getStaleAfterSeconds(), policy.getMaximumRolloutAgeSeconds(),
        policy.getCreatedBy(), policy.getCreatedAt(), policy.getApprovedBy(), policy.getApprovedAt(),
        policy.getActivatedBy(), policy.getActivatedAt());
  }

  private static String requireActor(DomainRuleGovernancePrincipal principal) {
    return requireText(principal == null ? null : principal.actorRef(), "actorRef", 255);
  }

  private static String requireText(String value, String field, int max) {
    if (value == null || value.isBlank()) throw bad(field + " is required");
    String clean = value.trim();
    if (clean.length() > max) throw bad(field + " is too long");
    return clean;
  }

  private static DomainRuleSnapshotControlPlaneException bad(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, message);
  }
  private static DomainRuleSnapshotControlPlaneException missing(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.NOT_FOUND, message);
  }
  private static DomainRuleSnapshotControlPlaneException conflict(String message) {
    return new DomainRuleSnapshotControlPlaneException(HttpStatus.CONFLICT, message);
  }

  private record ValidatedPolicy(String ruleSetKey, String policyKey, String enforcementMode,
      int minimumFreshProbes, BigDecimal minimumReadyRatio, boolean blockOnIncompatible,
      long staleAfterSeconds, Long maximumRolloutAgeSeconds) {}
}
