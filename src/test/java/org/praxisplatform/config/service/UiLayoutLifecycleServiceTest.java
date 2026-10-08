package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseEventRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseApprovalRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

@Tag("unit")
class UiLayoutLifecycleServiceTest {
  private final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
  private final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
  private final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
  private final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  private final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  private final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
  private final UiLayoutReleaseReviewRepository reviews = mock(UiLayoutReleaseReviewRepository.class);
  private final UiLayoutReleaseApprovalRepository approvals = mock(UiLayoutReleaseApprovalRepository.class);
  private final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
  private final UiLayoutReleaseEventRepository events = mock(UiLayoutReleaseEventRepository.class);
  private final UiLayoutRevisionAllocationLock revisionAllocationLock = mock(UiLayoutRevisionAllocationLock.class);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail");
  private final UiLayoutLifecycleService.Scope scope = new UiLayoutLifecycleService.Scope("tenant-a", "procurement", "lab", root);
  private UiLayoutLifecycleService service;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();
    service = new UiLayoutLifecycleService(definitions, revisions, assignments, drafts, releases, members,
        reviews, approvals, heads, events, revisionAllocationLock, new CanonicalJsonHashService(mapper), mapper,
        (operation, actor, tenant, unit, environment, target) -> true,
        (release, releaseMembers) -> {}, Clock.fixed(Instant.parse("2026-09-10T15:00:00Z"), ZoneOffset.UTC));
    when(drafts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(releases.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(reviews.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(heads.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(heads.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void draftCasRejectsASecondAuthorWithThePreviousStrongEtag() {
    UUID current = UUID.randomUUID();
    UiLayoutDraft draft = draft(current, "DRAFT");
    when(drafts.findForUpdateById(draft.getId())).thenReturn(Optional.of(draft));

    assertThatThrownBy(() -> service.updateDraftWorkspace(scope, draft.getId(), UUID.randomUUID(), "{}", "author-b"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);

    verify(drafts, never()).save(draft);
  }

  @Test
  void absentPolicyDeniesBeforePersistence() {
    UiLayoutLifecycleService denied = new UiLayoutLifecycleService(definitions, revisions, assignments, drafts, releases, members,
        reviews, approvals, heads, events, revisionAllocationLock, new CanonicalJsonHashService(new ObjectMapper()), new ObjectMapper(), null, null, Clock.systemUTC());

    assertThatThrownBy(() -> denied.createDraft(scope, "{}", "author", "create-1"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    verify(drafts, never()).save(any());
  }

  @Test
  void publishDoesNotMoveHeadWhenOneFixedMemberFailsCurrentValidation() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutRelease release = release(releaseId);
    UiLayoutReleaseHead head = head(UUID.randomUUID(), UUID.randomUUID());
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release));
    when(reviews.findByReleaseId(releaseId)).thenReturn(Optional.of(review(releaseId, "APPROVED")));
    when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(List.of(member(releaseId, 0, root), member(releaseId, 1, new UiLayoutTarget("praxis-table", "procurement-lines"))));
    when(heads.findForUpdate("tenant-a", "lab", root.componentType(), root.componentId())).thenReturn(Optional.of(head));
    service = withValidator((candidate, releaseMembers) -> { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "child invalid now"); });

    assertThatThrownBy(() -> service.publish(scope, releaseId, head.getHeadEtag(), "publisher", "publish procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);

    verify(heads, never()).saveAndFlush(any());
  }

  @Test
  void publishRequiresAnApprovedReviewBeforeItCanLoadTheHead() {
    UUID releaseId = UUID.randomUUID();
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release(releaseId)));
    when(reviews.findByReleaseId(releaseId)).thenReturn(Optional.of(review(releaseId, "SUBMITTED")));

    assertThatThrownBy(() -> service.publish(scope, releaseId, UUID.randomUUID(), "publisher", "publish procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);
    verify(heads, never()).findForUpdate(any(), any(), any(), any());
  }

  @Test
  void releaseLookupRejectsTheSameTenantReleaseWhenItsRootTargetDiffers() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutRelease otherRoot = UiLayoutRelease.builder().id(releaseId).tenantId("tenant-a").environment("lab")
        .rootComponentType("praxis-dynamic-page").rootComponentId("incident-master-detail").createdBy("author").createdAt(Instant.now()).build();
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(otherRoot));

    assertThatThrownBy(() -> service.publish(scope, releaseId, UUID.randomUUID(), "publisher", "publish procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
  }

  @Test
  void publishMovesOneAggregateHeadAfterValidationAndKeepsBothMembers() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutRelease release = release(releaseId);
    UUID oldRelease = UUID.randomUUID();
    UiLayoutReleaseHead head = head(oldRelease, UUID.randomUUID());
    List<UiLayoutReleaseMember> fixed = List.of(member(releaseId, 0, root), member(releaseId, 1, new UiLayoutTarget("praxis-table", "procurement-lines")));
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release));
    when(reviews.findByReleaseId(releaseId)).thenReturn(Optional.of(review(releaseId, "APPROVED")));
    when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(fixed);
    when(heads.findForUpdate("tenant-a", "lab", root.componentType(), root.componentId())).thenReturn(Optional.of(head));

    UiLayoutReleaseHead moved = service.publish(scope, releaseId, head.getHeadEtag(), "publisher", "publish procurement");

    assertThat(moved.getActiveReleaseId()).isEqualTo(releaseId);
    verify(heads).saveAndFlush(head);
    verify(events).save(any());
  }

  @Test
  void withdrawOnlyClearsTheAggregateHeadAndDoesNotMutateRelease() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutReleaseHead head = head(releaseId, UUID.randomUUID());
    when(heads.findForUpdate("tenant-a", "lab", root.componentType(), root.componentId())).thenReturn(Optional.of(head));
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release(releaseId)));

    service.withdraw(scope, head.getHeadEtag(), "publisher", "withdraw procurement");

    assertThat(head.getActiveReleaseId()).isNull();
    verify(heads).save(head);
    verify(releases, never()).save(any());
  }

  @Test
  void anExistingWithdrawnHeadRequiresItsCurrentEtagBeforeRepublish() {
    UUID oldRelease = UUID.randomUUID();
    UUID nextRelease = UUID.randomUUID();
    UiLayoutReleaseHead head = head(oldRelease, UUID.randomUUID());
    when(heads.findForUpdate("tenant-a", "lab", root.componentType(), root.componentId())).thenReturn(Optional.of(head));
    when(releases.findByIdAndTenantIdAndEnvironment(oldRelease, "tenant-a", "lab")).thenReturn(Optional.of(release(oldRelease)));
    service.withdraw(scope, head.getHeadEtag(), "publisher", "withdraw procurement");
    UUID current = head.getHeadEtag();
    when(releases.findByIdAndTenantIdAndEnvironment(nextRelease, "tenant-a", "lab")).thenReturn(Optional.of(release(nextRelease)));
    when(reviews.findByReleaseId(nextRelease)).thenReturn(Optional.of(review(nextRelease, "APPROVED")));
    when(members.findByReleaseIdOrderByMemberOrder(nextRelease)).thenReturn(List.of(member(nextRelease, 0, root)));

    assertThatThrownBy(() -> service.publish(scope, nextRelease, null, "publisher", "republish procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    assertThatThrownBy(() -> service.publish(scope, nextRelease, UUID.randomUUID(), "publisher", "republish procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);

    assertThat(service.publish(scope, nextRelease, current, "publisher", "republish procurement").getActiveReleaseId()).isEqualTo(nextRelease);
  }

  @Test
  void submitRequiresTheCurrentReviewEtag() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutReleaseReview review = review(releaseId, "DRAFT");
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release(releaseId)));
    when(reviews.findForUpdateByReleaseId(releaseId)).thenReturn(Optional.of(review));

    assertThatThrownBy(() -> service.submit(scope, releaseId, null, "author"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    assertThatThrownBy(() -> service.submit(scope, releaseId, UUID.randomUUID(), "author"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    service.submit(scope, releaseId, review.getReviewEtag(), "author");
    assertThat(review.getState()).isEqualTo("SUBMITTED");
  }

  @Test
  void approveRequiresTheCurrentReviewEtagAndWritesSeparateApprovalEvidence() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutReleaseReview review = review(releaseId, "SUBMITTED");
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release(releaseId)));
    when(reviews.findForUpdateByReleaseId(releaseId)).thenReturn(Optional.of(review));

    assertThatThrownBy(() -> service.approve(scope, releaseId, UUID.randomUUID(), "approver", "approved procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);

    service.approve(scope, releaseId, review.getReviewEtag(), "approver", "approved procurement");

    assertThat(review.getState()).isEqualTo("APPROVED");
    verify(approvals).save(any());
  }

  @Test
  void releasedDraftCannotBeReusedForRevisionAssignmentOrRelease() {
    UiLayoutDraft released = draft(UUID.randomUUID(), "RELEASED");
    when(drafts.findForUpdateById(released.getId())).thenReturn(Optional.of(released));
    UiLayoutLifecycleService.RevisionCommand revision = new UiLayoutLifecycleService.RevisionCommand(scope, released.getId(), released.getDraftEtag(), root, "{}", "1", "adjust");
    UiLayoutLifecycleService.AssignmentCommand assignment = new UiLayoutLifecycleService.AssignmentCommand(scope, released.getId(), released.getDraftEtag(), UUID.randomUUID(), root, "TENANT", "{}", (short) 0, "tenant-default");
    UiLayoutLifecycleService.ReleaseCommand release = new UiLayoutLifecycleService.ReleaseCommand(
        scope, released.getId(), released.getDraftEtag(), List.of(), "{}");

    assertInvalidState(() -> service.createRevision(revision, "author"));
    assertInvalidState(() -> service.createAssignment(assignment, "author"));
    assertInvalidState(() -> service.createRelease(release, "author"));
  }

  @Test
  void assignmentRejectsRevisionWhoseDefinitionIsOutsideTheTenantScope() {
    UiLayoutDraft draft = draft(UUID.randomUUID(), "DRAFT");
    UUID revisionId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    when(drafts.findForUpdateById(draft.getId())).thenReturn(Optional.of(draft));
    when(revisions.findById(revisionId)).thenReturn(Optional.of(org.praxisplatform.config.domain.UiLayoutRevision.builder().id(revisionId).definitionId(definitionId).build()));
    when(definitions.findById(definitionId)).thenReturn(Optional.of(org.praxisplatform.config.domain.UiLayoutDefinition.builder().id(definitionId).tenantId("tenant-b").environment("lab").build()));
    UiLayoutLifecycleService.AssignmentCommand command = new UiLayoutLifecycleService.AssignmentCommand(scope, draft.getId(), draft.getDraftEtag(), revisionId, root, "TENANT", "{}", (short) 0, "tenant-default");

    assertThatThrownBy(() -> service.createAssignment(command, "author"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    verify(assignments, never()).save(any());
  }

  @Test
  void rollbackRevalidatesTheWholeReleaseBeforeChangingTheHead() {
    UUID releaseId = UUID.randomUUID();
    UiLayoutReleaseHead head = head(UUID.randomUUID(), UUID.randomUUID());
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant-a", "lab")).thenReturn(Optional.of(release(releaseId)));
    when(reviews.findByReleaseId(releaseId)).thenReturn(Optional.of(review(releaseId, "APPROVED")));
    when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(List.of(member(releaseId, 0, root), member(releaseId, 1, new UiLayoutTarget("praxis-table", "procurement-lines"))));
    service = withValidator((candidate, releaseMembers) -> { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "schema changed"); });

    assertThatThrownBy(() -> service.rollback(scope, releaseId, head.getHeadEtag(), "publisher", "rollback procurement"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    verify(heads, never()).saveAndFlush(any());
  }

  private UiLayoutLifecycleService withValidator(UiLayoutReleaseValidator validator) {
    ObjectMapper mapper = new ObjectMapper();
    return new UiLayoutLifecycleService(definitions, revisions, assignments, drafts, releases, members,
        reviews, approvals, heads, events, revisionAllocationLock, new CanonicalJsonHashService(mapper), mapper,
        (operation, actor, tenant, unit, environment, target) -> true, validator, Clock.systemUTC());
  }

  private UiLayoutDraft draft(UUID etag, String state) {
    return UiLayoutDraft.builder().id(UUID.randomUUID()).tenantId("tenant-a").environment("lab")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).draftDocument("{}")
        .draftEtag(etag).state(state).createdBy("author").createdAt(Instant.now()).updatedAt(Instant.now()).rowVersion(0L).build();
  }
  private UiLayoutRelease release(UUID id) { return UiLayoutRelease.builder().id(id).tenantId("tenant-a").environment("lab")
      .rootComponentType(root.componentType()).rootComponentId(root.componentId()).createdBy("author").createdAt(Instant.now()).build(); }
  private UiLayoutReleaseReview review(UUID releaseId, String state) { return UiLayoutReleaseReview.builder().id(UUID.randomUUID()).releaseId(releaseId).state(state).reviewEtag(UUID.randomUUID()).rowVersion(0L).build(); }
  private UiLayoutReleaseHead head(UUID active, UUID etag) { return UiLayoutReleaseHead.builder().id(UUID.randomUUID()).tenantId("tenant-a").environment("lab")
      .rootComponentType(root.componentType()).rootComponentId(root.componentId()).activeReleaseId(active).headEtag(etag).updatedAt(Instant.now()).rowVersion(0L).build(); }
  private UiLayoutReleaseMember member(UUID releaseId, int order, UiLayoutTarget target) { return UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(releaseId).memberOrder(order)
      .componentType(target.componentType()).componentId(target.componentId()).assignmentRevisionId(UUID.randomUUID()).contentRevisionId(UUID.randomUUID()).contributionKey("key-" + order).build(); }
  private void assertInvalidState(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);
  }
}
