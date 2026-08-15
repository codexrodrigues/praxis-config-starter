package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import org.praxisplatform.config.domain.DomainRuleRolloutPolicy;
import org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCreateRequest;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyEventRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyHeadRepository;
import org.praxisplatform.config.repository.DomainRuleRolloutPolicyRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRolloutRepository;
import org.springframework.http.HttpStatus;

@Tag("unit")
class DomainRuleRolloutPolicyServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-13T15:00:00Z");
  private final DomainRuleRolloutPolicyRepository policies = mock(DomainRuleRolloutPolicyRepository.class);
  private final DomainRuleRolloutPolicyHeadRepository heads = mock(DomainRuleRolloutPolicyHeadRepository.class);
  private final DomainRuleRolloutPolicyEventRepository events = mock(DomainRuleRolloutPolicyEventRepository.class);
  private final DomainRuleSnapshotHeadRepository snapshotHeads = mock(DomainRuleSnapshotHeadRepository.class);
  private final DomainRuleSnapshotRolloutRepository rollouts = mock(DomainRuleSnapshotRolloutRepository.class);
  private final DomainRuleGovernancePrincipal author =
      new DomainRuleGovernancePrincipal("tenant-a", "author-a", "dev");
  private DomainRuleRolloutPolicyService service;

  @BeforeEach void setUp() {
    service = new DomainRuleRolloutPolicyService(policies, heads, events, snapshotHeads, rollouts,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(policies.save(any())).thenAnswer(call -> call.getArgument(0));
    when(heads.save(any())).thenAnswer(call -> call.getArgument(0));
    when(events.save(any())).thenAnswer(call -> call.getArgument(0));
  }

  @Test void createsTheNextImmutableDraftUnderTheScopedSnapshotLock() {
    var head = policyHead(null, 0, UUID.randomUUID());
    when(snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(snapshotHead()));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));
    when(policies.findMaximumVersion(
        "tenant-a", "dev", "benefit.eligibility", "safe-rollout")).thenReturn(Optional.of(1));

    var result = service.create(requiredRequest(), author);

    assertThat(result.policy().status()).isEqualTo("DRAFT");
    assertThat(result.policy().policyVersion()).isEqualTo(2);
    assertThat(result.policy().createdBy()).isEqualTo("author-a");
    assertThat(result.headEtag()).isEqualTo(head.getHeadEtag().toString());
    verify(events).save(argThat(event -> "CREATED".equals(event.getEventType())
        && event.getHeadEtag() == null));
  }

  @Test void requiredPolicyRejectsAZeroQuorum() {
    var invalid = new DomainRuleRolloutPolicyCreateRequest(
        "benefit.eligibility", "unsafe", "REQUIRED", 0, BigDecimal.ZERO,
        true, 120L, 600L);

    assertThatThrownBy(() -> service.create(invalid, author))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("at least one fresh probe");
    verifyNoInteractions(snapshotHeads, heads, policies, events);
  }

  @Test void authorCannotApproveTheSamePolicyVersion() {
    var policy = draft(UUID.randomUUID(), "author-a");
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(
        policy.getId(), "tenant-a", "dev")).thenReturn(Optional.of(policy));
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(policyHead(null, 0, UUID.randomUUID())));

    assertThatThrownBy(() -> service.approve(policy.getId(), author))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("author cannot approve");
    verify(events, never()).save(any());
  }

  @Test void distinctReviewerApprovesAndAppendsEvidence() {
    var policy = draft(UUID.randomUUID(), "author-a");
    var reviewer = new DomainRuleGovernancePrincipal("tenant-a", "reviewer-b", "dev");
    var head = policyHead(null, 0, UUID.randomUUID());
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(
        policy.getId(), "tenant-a", "dev")).thenReturn(Optional.of(policy));
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));

    var result = service.approve(policy.getId(), reviewer);

    assertThat(result.policy().status()).isEqualTo("APPROVED");
    assertThat(result.policy().approvedBy()).isEqualTo("reviewer-b");
    verify(events).save(argThat(event -> "APPROVED".equals(event.getEventType())));
  }

  @Test void activationUsesStrongHeadEtagSupersedesAndRotatesAntiAbaIdentity() {
    UUID targetId = UUID.randomUUID();
    UUID previousId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var target = approved(targetId);
    var previous = approved(previousId);
    previous.setActive(true); previous.setStatus("ACTIVE");
    previous.setActivatedBy("operator-old"); previous.setActivatedAt(NOW.minusSeconds(60));
    var head = policyHead(previousId, 4, etag);
    var operator = new DomainRuleGovernancePrincipal("tenant-a", "operator-c", "dev");
    when(policies.findByIdAndTenantIdAndEnvironment(targetId, "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(snapshotHead()));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(targetId, "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(previousId, "tenant-a", "dev"))
        .thenReturn(Optional.of(previous));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet()))
        .thenReturn(List.of());

    var result = service.activate(targetId, '"' + etag.toString() + '"', operator);

    assertThat(result.policy().status()).isEqualTo("ACTIVE");
    assertThat(result.activationRevision()).isEqualTo(5);
    assertThat(result.headEtag()).isNotEqualTo(etag.toString());
    assertThat(previous.getStatus()).isEqualTo("SUPERSEDED");
    assertThat(previous.getActive()).isFalse();
    verify(events).save(argThat(event -> "SUPERSEDED".equals(event.getEventType())));
    verify(events).save(argThat(event -> "ACTIVATED".equals(event.getEventType())
        && event.getHeadEtag().toString().equals(result.headEtag())));
  }

  @Test void activationRejectsStaleEtagBeforeMutatingLifecycle() {
    var target = approved(UUID.randomUUID());
    when(policies.findByIdAndTenantIdAndEnvironment(target.getId(), "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(snapshotHead()));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(policyHead(UUID.randomUUID(), 1, UUID.randomUUID())));

    assertThatThrownBy(() -> service.activate(target.getId(), '"' + UUID.randomUUID().toString() + '"', author))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    verify(policies, never()).save(any());
    verify(events, never()).save(any());
  }

  @Test void aSupersededImmutablePolicyCanBeReselectedWithANewAntiAbaEtag() {
    UUID targetId = UUID.randomUUID();
    UUID currentId = UUID.randomUUID();
    UUID etag = UUID.randomUUID();
    var target = approved(targetId);
    target.setStatus("SUPERSEDED"); target.setActivatedBy("operator-old");
    target.setActivatedAt(NOW.minusSeconds(120));
    var current = approved(currentId);
    current.setStatus("ACTIVE"); current.setActive(true);
    current.setActivatedBy("operator-current"); current.setActivatedAt(NOW.minusSeconds(60));
    var head = policyHead(currentId, 8, etag);
    when(policies.findByIdAndTenantIdAndEnvironment(targetId, "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any()))
        .thenReturn(Optional.of(snapshotHead()));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any()))
        .thenReturn(Optional.of(head));
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(targetId, "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(currentId, "tenant-a", "dev"))
        .thenReturn(Optional.of(current));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet())).thenReturn(List.of());

    var result = service.activate(targetId, '"' + etag.toString() + '"', author);

    assertThat(result.policy().status()).isEqualTo("ACTIVE");
    assertThat(result.activationRevision()).isEqualTo(9);
    assertThat(result.headEtag()).isNotEqualTo(etag.toString());
  }

  @Test void activationCannotInvalidateAnOpenRollout() {
    var target = approved(UUID.randomUUID());
    UUID etag = UUID.randomUUID();
    when(policies.findByIdAndTenantIdAndEnvironment(target.getId(), "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(snapshotHeads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any()))
        .thenReturn(Optional.of(snapshotHead()));
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(any(), any(), any()))
        .thenReturn(Optional.of(policyHead(UUID.randomUUID(), 1, etag)));
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(target.getId(), "tenant-a", "dev"))
        .thenReturn(Optional.of(target));
    when(rollouts.findByTenantIdAndEnvironmentAndRuleSetKeyAndStatusIn(
        eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), anySet()))
        .thenReturn(List.of(org.praxisplatform.config.domain.DomainRuleSnapshotRollout.builder().build()));

    assertThatThrownBy(() -> service.activate(target.getId(), '"' + etag.toString() + '"', author))
        .isInstanceOf(DomainRuleSnapshotControlPlaneException.class)
        .hasMessageContaining("open rollout");
    verify(policies, never()).save(any());
  }

  @Test void bootstrapReusesAHeadCreatedByDraftAuthoring() {
    var existingHead = policyHead(null, 0, UUID.randomUUID());
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyAndActiveTrue(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.empty());
    when(policies.findMaximumVersion(
        "tenant-a", "dev", "benefit.eligibility", "platform-observe-only"))
        .thenReturn(Optional.empty());
    when(heads.findForUpdateByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(existingHead));

    var policy = service.requireActiveOrBootstrap("benefit.eligibility", author);

    assertThat(policy.getStatus()).isEqualTo("ACTIVE");
    assertThat(existingHead.getActivePolicyId()).isEqualTo(policy.getId());
    assertThat(existingHead.getActivationRevision()).isEqualTo(1);
    verify(heads).save(existingHead);
  }

  @Test void approvalCannotCrossTheServerResolvedTenantScope() {
    UUID policyId = UUID.randomUUID();
    when(policies.findForUpdateByIdAndTenantIdAndEnvironment(policyId, "tenant-a", "dev"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(policyId,
        new DomainRuleGovernancePrincipal("tenant-a", "reviewer-b", "dev")))
        .isInstanceOfSatisfying(DomainRuleSnapshotControlPlaneException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(heads, events);
  }

  @Test void catalogPublishesOnlyPrincipalOwnedLifecycleActions() {
    UUID etag = UUID.randomUUID();
    var ownDraft = draft(UUID.randomUUID(), "reviewer-b");
    var independentDraft = draft(UUID.randomUUID(), "author-a");
    var reviewer = new DomainRuleGovernancePrincipal("tenant-a", "reviewer-b", "dev");
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(Optional.of(policyHead(null, 0, etag)));
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtDesc(
        "tenant-a", "dev", "benefit.eligibility"))
        .thenReturn(List.of(ownDraft, independentDraft));

    var catalog = service.catalog(
        "benefit.eligibility", reviewer, false, true, false);

    assertThat(catalog.availableActions()).isEmpty();
    assertThat(catalog.versions().get(0).availableActions()).isEmpty();
    assertThat(catalog.versions().get(1).availableActions()).containsExactly("APPROVE");
  }

  @Test void authorCanDiscoverInitialPolicyCreationBeforeAHeadExists() {
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.empty());
    when(policies.findByTenantIdAndEnvironmentAndRuleSetKeyOrderByCreatedAtDesc(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(List.of());

    var catalog = service.catalog("benefit.eligibility", author, true, false, false);

    assertThat(catalog.activationRevision()).isZero();
    assertThat(catalog.headEtag()).isNull();
    assertThat(catalog.availableActions()).containsExactly("CREATE_POLICY_VERSION");
  }

  private static DomainRuleRolloutPolicyCreateRequest requiredRequest() {
    return new DomainRuleRolloutPolicyCreateRequest("benefit.eligibility", "safe-rollout",
        "REQUIRED", 2, new BigDecimal("0.7500"), true, 120L, 600L);
  }

  private static DomainRuleRolloutPolicy draft(UUID id, String actor) {
    return DomainRuleRolloutPolicy.builder().id(id).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").policyKey("safe-rollout").policyVersion(2)
        .enforcementMode("REQUIRED").minimumFreshProbes(2)
        .minimumReadyRatio(new BigDecimal("0.7500")).blockOnIncompatible(true)
        .staleAfterSeconds(120L).maximumRolloutAgeSeconds(600L).active(false).status("DRAFT")
        .createdBy(actor).createdAt(NOW.minusSeconds(30)).build();
  }

  private static DomainRuleRolloutPolicy approved(UUID id) {
    var policy = draft(id, "author-a");
    policy.setStatus("APPROVED"); policy.setApprovedBy("reviewer-b");
    policy.setApprovedAt(NOW.minusSeconds(10));
    return policy;
  }

  private static DomainRuleRolloutPolicyHead policyHead(UUID active, long revision, UUID etag) {
    return DomainRuleRolloutPolicyHead.builder().id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility").activePolicyId(active)
        .activationRevision(revision).headEtag(etag).updatedBy("operator")
        .updatedAt(NOW).rowVersion(0L).build();
  }

  private static DomainRuleSnapshotHead snapshotHead() {
    return DomainRuleSnapshotHead.builder().id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility")
        .activeSnapshotId(UUID.randomUUID()).activationRevision(1L)
        .headEtag(UUID.randomUUID()).updatedAt(NOW).rowVersion(0L).build();
  }
}
