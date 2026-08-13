package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.repository.*;
import org.springframework.http.HttpStatus;

@Tag("unit")
class DomainRuleRolloutServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final String HASH = "A".repeat(64);
  private static final String CATALOG = "B".repeat(64);
  private final DomainRuleRolloutPolicyRepository policies = mock(DomainRuleRolloutPolicyRepository.class);
  private final DomainRuleSnapshotRolloutRepository rollouts = mock(DomainRuleSnapshotRolloutRepository.class);
  private final DomainRuleCandidateProbeRepository probes = mock(DomainRuleCandidateProbeRepository.class);
  private final DomainRuleSnapshotRolloutEventRepository events = mock(DomainRuleSnapshotRolloutEventRepository.class);
  private final DomainRuleSnapshotRepository snapshots = mock(DomainRuleSnapshotRepository.class);
  private final DomainRuleSnapshotHeadRepository heads = mock(DomainRuleSnapshotHeadRepository.class);
  private final DomainRuleRolloutPolicyService policyService = mock(DomainRuleRolloutPolicyService.class);
  private final DomainRuleGovernancePrincipal principal =
      new DomainRuleGovernancePrincipal("tenant-a", "operator-a", "dev");
  private DomainRuleRolloutService service;

  @BeforeEach void setUp() {
    service = new DomainRuleRolloutService(policies, rollouts, probes, events, snapshots, heads,
        policyService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test void createBindsCandidateToLockedHeadAndBootstrapsObserveOnlyPolicy() {
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var active = snapshot(UUID.randomUUID(), "active-v1", 1);
    UUID etag = UUID.randomUUID();
    var head = DomainRuleSnapshotHead.builder().id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility").activeSnapshotId(active.getId())
        .activationRevision(1L).headEtag(etag).updatedAt(NOW).rowVersion(0L).build();
    when(snapshots.findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "dev", "candidate-v2"))
        .thenReturn(Optional.of(candidate));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet())).thenReturn(List.of());
    when(policyService.requireActiveOrBootstrap("benefit.eligibility", principal))
        .thenReturn(DomainRuleRolloutPolicy.builder().id(UUID.randomUUID())
            .policyKey("platform-observe-only").policyVersion(1)
            .enforcementMode("OBSERVE_ONLY").build());
    when(rollouts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(snapshots.findById(active.getId())).thenReturn(Optional.of(active));

    var response = service.create(new DomainRuleRolloutCreateRequest("candidate-v2", null),
        principal, '"' + etag.toString() + '"');

    assertThat(response.enforcementMode()).isEqualTo("OBSERVE_ONLY");
    assertThat(response.expectedActiveSnapshotKey()).isEqualTo("active-v1");
    assertThat(response.expectedHeadEtag()).isEqualTo(etag.toString());
    verify(events).save(argThat(event -> "CREATED".equals(event.getEventType())));
  }

  @Test void createRejectsStaleHeadBeforePersistingAnything() {
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var head = DomainRuleSnapshotHead.builder().id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility").activeSnapshotId(UUID.randomUUID())
        .activationRevision(1L).headEtag(UUID.randomUUID()).updatedAt(NOW).rowVersion(0L).build();
    when(snapshots.findByTenantIdAndEnvironmentAndSnapshotKey(any(), any(), any()))
        .thenReturn(Optional.of(candidate));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any()))
        .thenReturn(Optional.of(head));

    assertThatThrownBy(() -> service.create(
        new DomainRuleRolloutCreateRequest("candidate-v2", null), principal, '"' + UUID.randomUUID().toString() + '"'))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    verifyNoInteractions(policies, policyService, probes, events);
  }

  @Test void createAppliesTheActivePolicyMaximumRolloutAge() {
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var active = snapshot(UUID.randomUUID(), "active-v1", 1);
    UUID etag = UUID.randomUUID();
    var head = DomainRuleSnapshotHead.builder().id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility").activeSnapshotId(active.getId())
        .activationRevision(1L).headEtag(etag).updatedAt(NOW).rowVersion(0L).build();
    when(snapshots.findByTenantIdAndEnvironmentAndSnapshotKey("tenant-a", "dev", "candidate-v2"))
        .thenReturn(Optional.of(candidate));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet())).thenReturn(List.of());
    when(policyService.requireActiveOrBootstrap("benefit.eligibility", principal))
        .thenReturn(DomainRuleRolloutPolicy.builder().id(UUID.randomUUID()).policyKey("safe")
            .policyVersion(2).enforcementMode("REQUIRED").maximumRolloutAgeSeconds(300L).build());

    assertThatThrownBy(() -> service.create(new DomainRuleRolloutCreateRequest(
        "candidate-v2", NOW.plusSeconds(301)), principal, '"' + etag.toString() + '"'))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("maximum age");
    verify(rollouts, never()).save(any());
  }

  @Test void incompatibleSuccessClaimIsStoredFailClosedAndAggregatedWithoutActorIdentity() {
    UUID rolloutId = UUID.randomUUID();
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var rollout = rollout(rolloutId, candidate.getId());
    when(rollouts.findForUpdateByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(snapshots.findById(candidate.getId())).thenReturn(Optional.of(candidate));
    when(probes.upsertIfNewer(any(), eq(rolloutId), eq("tenant-a"), eq("dev"), any(),
        eq("operator-a"), eq(candidate.getId()), eq("candidate-v2"), eq(HASH), eq(false),
        eq("quickstart/1"), eq("wrong-engine"), eq("dialect/1"), eq("C".repeat(64)),
        eq(CATALOG), eq("RUNTIME_INCOMPATIBLE"), eq(NOW), eq(NOW))).thenReturn(1);

    var result = service.probe(rolloutId, new DomainRuleCandidateProbeRequest(
        "candidate-v2", HASH, true, "quickstart/1", "wrong-engine", "dialect/1",
        "C".repeat(64), CATALOG, null, NOW), principal);

    assertThat(result.updated()).isTrue();
  }

  @Test void requiredPolicyDerivesExactQuorumAndBlocksOnIncompatibleProbe() {
    UUID rolloutId = UUID.randomUUID();
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var rollout = rollout(rolloutId, candidate.getId());
    var policy = DomainRuleRolloutPolicy.builder().id(rollout.getPolicyId())
        .enforcementMode("REQUIRED").minimumFreshProbes(2).minimumReadyRatio(new BigDecimal("0.5000"))
        .blockOnIncompatible(true).staleAfterSeconds(120L).build();
    when(rollouts.findByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(policies.findById(rollout.getPolicyId())).thenReturn(Optional.of(policy));
    when(snapshots.findById(candidate.getId())).thenReturn(Optional.of(candidate));
    when(probes.findByRolloutId(rolloutId)).thenReturn(List.of(
        probe(true, null, NOW.minusSeconds(5)),
        probe(false, "RUNTIME_INCOMPATIBLE", NOW.minusSeconds(10))));

    var response = service.readiness(rolloutId, principal);

    assertThat(response.readyProbes()).isEqualTo(1);
    assertThat(response.incompatibleProbes()).isEqualTo(1);
    assertThat(response.activationReady()).isFalse();
    assertThat(response.status()).isEqualTo("BLOCKED");
  }

  @Test void pendingDiscoveryReturnsOnlyCandidateBoundToTheCurrentHead() {
    UUID rolloutId = UUID.randomUUID();
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    UUID activeId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var rollout = rollout(rolloutId, candidate.getId());
    rollout.setExpectedActiveSnapshotId(activeId);
    rollout.setExpectedHeadEtag(etag);
    rollout.setCreatedAt(NOW.minusSeconds(5));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet()))
        .thenReturn(List.of(rollout));
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey("tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(DomainRuleSnapshotHead.builder()
            .activeSnapshotId(activeId).headEtag(etag).build()));
    when(snapshots.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        candidate.getId(), "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(candidate));

    var response = service.pending("benefit.eligibility", principal);

    assertThat(response).isPresent();
    assertThat(response.orElseThrow().candidateSnapshotKey()).isEqualTo("candidate-v2");
    assertThat(response.orElseThrow().candidateContentHash()).isEqualTo(HASH);
  }

  @Test void pendingDiscoverySuppressesExpiredOrHeadStaleRollout() {
    var rollout = rollout(UUID.randomUUID(), UUID.randomUUID());
    rollout.setExpectedActiveSnapshotId(UUID.randomUUID());
    rollout.setExpectedHeadEtag(UUID.randomUUID());
    rollout.setExpiresAt(NOW);
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet()))
        .thenReturn(List.of(rollout));

    assertThat(service.pending("benefit.eligibility", principal)).isEmpty();
    verifyNoInteractions(heads);
  }

  @Test void humanCatalogIsScopeBoundAndDerivesActivationAndCancelActions() {
    UUID rolloutId = UUID.randomUUID();
    UUID policyId = UUID.randomUUID();
    UUID activeId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var active = snapshot(activeId, "active-v1", 1);
    var rollout = rollout(rolloutId, candidate.getId());
    rollout.setPolicyId(policyId);
    rollout.setExpectedActiveSnapshotId(activeId);
    rollout.setExpectedHeadEtag(etag);
    rollout.setCreatedAt(NOW.minusSeconds(5));
    rollout.setExpiresAt(NOW.plusSeconds(60));
    var policy = requiredPolicy(policyId, 1, true);
    policy.setPolicyKey("safe");
    policy.setPolicyVersion(3);
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet()))
        .thenReturn(List.of(rollout));
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(DomainRuleSnapshotHead.builder()
            .activeSnapshotId(activeId).headEtag(etag).build()));
    when(rollouts.findByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(policies.findById(policyId)).thenReturn(Optional.of(policy));
    when(probes.findByRolloutId(rolloutId))
        .thenReturn(List.of(probe(true, null, NOW.minusSeconds(1))));
    when(snapshots.findById(candidate.getId())).thenReturn(Optional.of(candidate));
    when(snapshots.findById(activeId)).thenReturn(Optional.of(active));

    var catalog = service.catalog("benefit.eligibility", principal);

    assertThat(catalog.rollouts()).hasSize(1);
    assertThat(catalog.rollouts().getFirst().availableActions())
        .containsExactly("CANCEL", "ACTIVATE_CANDIDATE");
    assertThat(catalog.rollouts().getFirst().expectedHeadCurrent()).isTrue();
    assertThat(catalog.rollouts().getFirst().readiness().activationReady()).isTrue();
    verify(rollouts).findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet());
  }

  @Test void humanCatalogAllowsCleanupButWithholdsActivationFromExpiredRollout() {
    UUID rolloutId = UUID.randomUUID();
    UUID policyId = UUID.randomUUID();
    UUID activeId = UUID.randomUUID();
    var candidate = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var active = snapshot(activeId, "active-v1", 1);
    var rollout = rollout(rolloutId, candidate.getId());
    rollout.setPolicyId(policyId);
    rollout.setExpectedActiveSnapshotId(activeId);
    rollout.setExpectedHeadEtag(UUID.randomUUID());
    rollout.setExpiresAt(NOW);
    var policy = requiredPolicy(policyId, 1, true);
    policy.setPolicyKey("safe");
    policy.setPolicyVersion(1);
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusInOrderByCreatedAtDesc(
        any(), any(), any(), anySet())).thenReturn(List.of(rollout));
    when(rollouts.findByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(policies.findById(policyId)).thenReturn(Optional.of(policy));
    when(probes.findByRolloutId(rolloutId)).thenReturn(List.of());
    when(snapshots.findById(candidate.getId())).thenReturn(Optional.of(candidate));
    when(snapshots.findById(activeId)).thenReturn(Optional.of(active));

    var item = service.catalog("benefit.eligibility", principal).rollouts().getFirst();

    assertThat(item.expired()).isTrue();
    assertThat(item.availableActions()).containsExactly("CANCEL");
  }

  @Test void requiredActivationGateRejectsMissingRollout() {
    var target = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(requiredPolicy(UUID.randomUUID(), 1, false)));

    assertThatThrownBy(() -> service.requireAllowed(null, target,
        DomainRuleSnapshotHead.builder().activeSnapshotId(UUID.randomUUID())
            .headEtag(UUID.randomUUID()).build(), "operator"))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("READY rollout is required");
  }

  @Test void observeOnlyActivationAllowsOmissionButValidatesAnExplicitRollout() {
    UUID policyId = UUID.randomUUID();
    UUID rolloutId = UUID.randomUUID();
    UUID activeId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var target = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var policy = DomainRuleRolloutPolicy.builder().id(policyId).enforcementMode("OBSERVE_ONLY")
        .minimumFreshProbes(0).minimumReadyRatio(BigDecimal.ZERO)
        .blockOnIncompatible(false).staleAfterSeconds(120L).build();
    var rollout = rollout(rolloutId, UUID.randomUUID());
    rollout.setPolicyId(policyId); rollout.setExpectedActiveSnapshotId(activeId);
    rollout.setExpectedHeadEtag(etag); rollout.setUpdatedAt(NOW);
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(policy));
    var head = DomainRuleSnapshotHead.builder().activeSnapshotId(activeId).headEtag(etag).build();

    service.requireAllowed(null, target, head, "operator");

    when(rollouts.findForUpdateByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    assertThatThrownBy(() -> service.requireAllowed(rolloutId, target, head, "operator"))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("no longer matches activation");
  }

  @Test void requiredActivationGateAcceptsLockedExactQuorumAndClosesRolloutAfterActivation() {
    UUID rolloutId = UUID.randomUUID();
    UUID policyId = UUID.randomUUID();
    UUID activeId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var target = snapshot(UUID.randomUUID(), "candidate-v2", 2);
    var rollout = rollout(rolloutId, target.getId());
    rollout.setPolicyId(policyId); rollout.setExpectedActiveSnapshotId(activeId);
    rollout.setExpectedHeadEtag(etag); rollout.setUpdatedAt(NOW);
    var policy = requiredPolicy(policyId, 1, true);
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(policy));
    when(rollouts.findForUpdateByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(rollouts.findByIdAndTenantIdAndEnvironment(rolloutId, "tenant-a", "dev"))
        .thenReturn(Optional.of(rollout));
    when(policies.findById(policyId)).thenReturn(Optional.of(policy));
    when(probes.findByRolloutId(rolloutId)).thenReturn(List.of(probe(true, null, NOW.minusSeconds(1))));
    when(snapshots.findById(target.getId())).thenReturn(Optional.of(target));
    when(rollouts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var head = DomainRuleSnapshotHead.builder().activeSnapshotId(activeId).headEtag(etag).build();

    service.requireAllowed(rolloutId, target, head, "operator");
    service.activationCompleted(rolloutId, target, "operator");

    assertThat(rollout.getStatus()).isEqualTo("ACTIVATED");
    verify(events).save(argThat(event -> "ACTIVATED".equals(event.getEventType())));
  }

  private static DomainRuleSnapshot snapshot(UUID id, String key, int revision) {
    return DomainRuleSnapshot.builder().id(id).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").snapshotKey(key).contentHash(HASH)
        .publicationRevision(revision).snapshotPayload("""
            {"requiredHostContractVersion":"quickstart/1","ruleSet":{"compatibility":{
            "engineContractVersion":"engine/1","jsonLogicDialectVersion":"dialect/1",
            "jsonLogicCorpusSha256":"%s"}}}
            """.formatted("C".repeat(64)))
        .compositionManifest("{\"implementationCatalogDigest\":\"" + CATALOG + "\"}").build();
  }
  private static DomainRuleSnapshotRollout rollout(UUID id, UUID candidateId) {
    return DomainRuleSnapshotRollout.builder().id(id).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").candidateSnapshotId(candidateId).policyId(UUID.randomUUID())
        .status("PREPARING").build();
  }
  private static DomainRuleCandidateProbe probe(boolean ready, String failure, Instant observed) {
    return DomainRuleCandidateProbe.builder().preloadReady(ready).failureCode(failure)
        .observedAt(observed).build();
  }
  private static DomainRuleRolloutPolicy requiredPolicy(UUID id, int minimum, boolean block) {
    return DomainRuleRolloutPolicy.builder().id(id).enforcementMode("REQUIRED")
        .minimumFreshProbes(minimum).minimumReadyRatio(BigDecimal.ONE)
        .blockOnIncompatible(block).staleAfterSeconds(120L).build();
  }
}
