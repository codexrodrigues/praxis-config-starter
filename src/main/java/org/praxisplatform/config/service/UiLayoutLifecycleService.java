package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseApproval;
import org.praxisplatform.config.domain.UiLayoutReleaseEvent;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.praxisplatform.config.domain.UiLayoutRevision;
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
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * B1a persistence core. It creates immutable revisions/releases and moves one aggregate head;
 * it intentionally has no controller, resolver activation, HTTP parsing, or in-memory runtime.
 */
/** Package-private persistence core; public callers must use UiLayoutLifecycleCommandService. */
class UiLayoutLifecycleService {
  private final UiLayoutDefinitionRepository definitions;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutAssignmentRevisionRepository assignments;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutReleaseReviewRepository reviews;
  private final UiLayoutReleaseApprovalRepository approvals;
  private final UiLayoutReleaseHeadRepository heads;
  private final UiLayoutReleaseEventRepository events;
  private final UiLayoutRevisionAllocationLock revisionAllocationLock;
  private final CanonicalJsonHashService hashes;
  private final ObjectMapper objectMapper;
  private final UiLayoutLifecyclePolicy policy;
  private final UiLayoutReleaseValidator validator;
  private final Clock clock;

  public UiLayoutLifecycleService(
      UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments, UiLayoutDraftRepository drafts,
      UiLayoutReleaseRepository releases, UiLayoutReleaseMemberRepository members,
      UiLayoutReleaseReviewRepository reviews, UiLayoutReleaseApprovalRepository approvals,
      UiLayoutReleaseHeadRepository heads, UiLayoutReleaseEventRepository events,
      UiLayoutRevisionAllocationLock revisionAllocationLock, CanonicalJsonHashService hashes, ObjectMapper objectMapper,
      UiLayoutLifecyclePolicy policy, UiLayoutReleaseValidator validator, Clock clock) {
    this.definitions = definitions; this.revisions = revisions; this.assignments = assignments;
    this.drafts = drafts; this.releases = releases; this.members = members; this.reviews = reviews; this.approvals = approvals;
    this.heads = heads; this.events = events; this.revisionAllocationLock = revisionAllocationLock;
    this.hashes = hashes; this.objectMapper = objectMapper;
    this.policy = policy == null ? UiLayoutLifecyclePolicy.denyAll() : policy;
    this.validator = validator == null ? UiLayoutReleaseValidator.denyAll() : validator;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutDraft createDraft(Scope scope, String document, String actor, String creationIdempotencyKey) {
    requirePolicy(UiLayoutLifecycleOperation.CREATE_DRAFT, scope, actor);
    Instant now = Instant.now(clock);
    return drafts.save(UiLayoutDraft.builder().id(UUID.randomUUID()).tenantId(scope.tenantId())
        .environment(scope.environment()).rootComponentType(scope.rootTarget().componentType())
        .rootComponentId(scope.rootTarget().componentId()).draftDocument(requireJsonObject(document, "draftDocument"))
        .draftEtag(UUID.randomUUID()).state("DRAFT").createdBy(require(actor, "actor"))
        .creationIdempotencyKey(require(creationIdempotencyKey, "creationIdempotencyKey"))
        .createdAt(now).updatedAt(now).rowVersion(0L).build());
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  UiLayoutDraft updateDraftWorkspace(Scope scope, UUID draftId, UUID ifMatch, String document, String actor) {
    requirePolicy(UiLayoutLifecycleOperation.EDIT_DRAFT, scope, actor);
    UiLayoutDraft draft = editableDraft(scope, draftId, ifMatch);
    draft.setDraftDocument(requireJsonObject(document, "draftDocument"));
    draft.setDraftEtag(UUID.randomUUID());
    draft.setUpdatedAt(Instant.now(clock));
    return drafts.save(draft);
  }

  UiLayoutDraft requireEditableDraft(Scope scope, UUID draftId, UUID ifMatch, String actor) {
    requirePolicy(UiLayoutLifecycleOperation.EDIT_DRAFT, scope, actor);
    return editableDraft(scope, draftId, ifMatch);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutRevision createRevision(RevisionCommand command, String actor) {
    requirePolicy(UiLayoutLifecycleOperation.EDIT_DRAFT, command.scope(), actor);
    UiLayoutDraft draft = editableDraft(command.scope(), command.draftId(), command.ifMatch());
    String patch = requireJsonObject(command.patchDocument(), "patchDocument");
    requireRevisionAllocationLock(command.scope(), command.target());
    UiLayoutDefinition definition = definitions.findForUpdateByTarget(
        command.scope().tenantId(), command.scope().environment(), command.target().componentType(), command.target().componentId())
        .orElseGet(() -> definitions.save(UiLayoutDefinition.builder().id(UUID.randomUUID())
            .tenantId(command.scope().tenantId()).environment(command.scope().environment())
            .componentType(command.target().componentType()).componentId(command.target().componentId())
            .schemaVersion(require(command.schemaVersion(), "schemaVersion")).createdBy(require(actor, "actor"))
            .createdAt(Instant.now(clock)).build()));
    Long maximumRevision = revisions.findMaximumRevisionNumber(definition.getId());
    long revisionNumber = maximumRevision == null ? 1L : maximumRevision + 1L;
    UiLayoutRevision revision = revisions.save(UiLayoutRevision.builder().id(UUID.randomUUID())
        .definitionId(definition.getId()).revisionNumber(revisionNumber).patchDocument(patch)
        .contentHash(hashExact(patch)).schemaVersion(require(command.schemaVersion(), "schemaVersion"))
        .createdBy(require(actor, "actor")).createdReason(require(command.reason(), "reason"))
        .createdAt(Instant.now(clock)).build());
    return revision;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutAssignmentRevision createAssignment(AssignmentCommand command, String actor) {
    requirePolicy(UiLayoutLifecycleOperation.EDIT_DRAFT, command.scope(), actor);
    UiLayoutDraft draft = editableDraft(command.scope(), command.draftId(), command.ifMatch());
    UiLayoutRevision revision = revisions.findById(command.revisionId())
        .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Layout revision was not found."));
    UiLayoutDefinition definition = definitions.findById(revision.getDefinitionId())
        .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Layout definition was not found."));
    if (!command.scope().tenantId().equals(definition.getTenantId()) || !command.scope().environment().equals(definition.getEnvironment())
        || !command.target().componentType().equals(definition.getComponentType()) || !command.target().componentId().equals(definition.getComponentId())) {
      fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "An assignment revision must reference content in the requested tenant and environment.");
    }
    UiLayoutAssignmentRevision assignment = assignments.save(UiLayoutAssignmentRevision.builder().id(UUID.randomUUID())
        .tenantId(command.scope().tenantId()).environment(command.scope().environment()).revisionId(revision.getId())
        .layerClass(require(command.layerClass(), "layerClass")).selectorDocument(requireJsonObject(command.selectorDocument(), "selectorDocument"))
        .priority(command.priority()).contributionKey(require(command.contributionKey(), "contributionKey"))
        .createdBy(require(actor, "actor")).createdAt(Instant.now(clock)).build());
    return assignment;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutRelease createRelease(ReleaseCommand command, String actor) {
    requirePolicy(UiLayoutLifecycleOperation.SUBMIT_RELEASE, command.scope(), actor);
    UiLayoutDraft draft = editableDraft(command.scope(), command.draftId(), command.ifMatch());
    String releasedDraftDocument = requireJsonObject(command.releasedDraftDocument(), "releasedDraftDocument");
    if (command.members() == null || command.members().isEmpty()) fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A release requires at least one exact target member.");
    List<MemberCommand> ordered = command.members().stream().sorted(Comparator.comparingInt(MemberCommand::memberOrder)).toList();
    for (int index = 0; index < ordered.size(); index++) if (ordered.get(index).memberOrder() != index) fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "Release member order must be contiguous from zero.");
    if (ordered.stream().noneMatch(member -> command.scope().rootTarget().equals(member.target()))) {
      fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A composition release must include its exact root target as a member.");
    }
    UiLayoutRelease release = releases.save(UiLayoutRelease.builder().id(UUID.randomUUID()).tenantId(command.scope().tenantId())
        .environment(command.scope().environment()).rootComponentType(command.scope().rootTarget().componentType())
        .rootComponentId(command.scope().rootTarget().componentId()).sourceDraftId(draft.getId())
        .createdBy(require(actor, "actor")).createdAt(Instant.now(clock)).build());
    List<UiLayoutReleaseMember> persisted = new ArrayList<>();
    Set<String> releaseContributionKeys = new HashSet<>();
    for (MemberCommand member : ordered) {
      UiLayoutAssignmentRevision assignment = assignments.findById(member.assignmentRevisionId())
          .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Assignment revision was not found."));
      if (!command.scope().tenantId().equals(assignment.getTenantId()) || !command.scope().environment().equals(assignment.getEnvironment())) {
        fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "An assignment belongs to a different release scope.");
      }
      UiLayoutRevision content = revisions.findById(member.contentRevisionId())
          .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Content revision was not found."));
      if (!assignment.getRevisionId().equals(content.getId())) {
        fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A release member must pin its assignment to the exact content revision.");
      }
      UiLayoutDefinition definition = definitions.findById(content.getDefinitionId())
          .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Content definition was not found."));
      if (!command.scope().tenantId().equals(definition.getTenantId()) || !command.scope().environment().equals(definition.getEnvironment())
          || !member.target().componentType().equals(definition.getComponentType()) || !member.target().componentId().equals(definition.getComponentId())) {
        fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A release member target must match its exact content definition.");
      }
      String contributionIdentity = member.target().componentType() + "|" + member.target().componentId() + "|" + require(assignment.getContributionKey(), "assignment contributionKey");
      if (!releaseContributionKeys.add(contributionIdentity)) {
        fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A release cannot contain incompatible duplicate contribution keys for one target.");
      }
      persisted.add(UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(release.getId())
          .memberOrder(member.memberOrder()).componentType(member.target().componentType()).componentId(member.target().componentId())
          .assignmentRevisionId(member.assignmentRevisionId()).contentRevisionId(member.contentRevisionId())
          .contributionKey(require(assignment.getContributionKey(), "assignment contributionKey")).build());
    }
    members.saveAll(persisted);
    reviews.save(UiLayoutReleaseReview.builder().id(UUID.randomUUID()).releaseId(release.getId()).state("DRAFT")
        .reviewEtag(UUID.randomUUID()).rowVersion(0L).build());
    draft.setDraftDocument(releasedDraftDocument);
    draft.setState("RELEASED"); draft.setDraftEtag(UUID.randomUUID()); draft.setUpdatedAt(Instant.now(clock)); drafts.save(draft);
    append("RELEASE_CREATED", release, null, null, null, actor, "Release created from draft.");
    return release;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public void submit(Scope scope, UUID releaseId, UUID ifMatch, String actor) {
    transitionReview(scope, releaseId, ifMatch, actor, "DRAFT", "SUBMITTED",
        UiLayoutLifecycleOperation.SUBMIT_RELEASE, "RELEASE_SUBMITTED", "Release submitted for approval.");
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public void approve(Scope scope, UUID releaseId, UUID ifMatch, String actor, String reason) {
    transitionReview(scope, releaseId, ifMatch, actor, "SUBMITTED", "APPROVED",
        UiLayoutLifecycleOperation.APPROVE_RELEASE, "RELEASE_APPROVED", reason);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutReleaseHead publish(Scope scope, UUID releaseId, UUID ifMatch, String actor, String reason) {
    return moveHead(scope, releaseId, ifMatch, actor, reason, UiLayoutLifecycleOperation.PUBLISH_RELEASE, "PUBLISHED", false);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutReleaseHead withdraw(Scope scope, UUID ifMatch, String actor, String reason) {
    requirePolicy(UiLayoutLifecycleOperation.WITHDRAW_RELEASE, scope, actor);
    UiLayoutReleaseHead head = head(scope); requireMatch(ifMatch, head.getHeadEtag());
    UUID previous = head.getActiveReleaseId();
    if (previous == null) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "There is no active release to withdraw.");
    head.setActiveReleaseId(null); head.setHeadEtag(UUID.randomUUID()); head.setUpdatedAt(Instant.now(clock)); heads.save(head);
    UiLayoutRelease release = release(scope, previous); append("WITHDRAWN", release, previous, null, head.getHeadEtag(), actor, reason);
    return head;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public UiLayoutReleaseHead rollback(Scope scope, UUID releaseId, UUID ifMatch, String actor, String reason) {
    return moveHead(scope, releaseId, ifMatch, actor, reason, UiLayoutLifecycleOperation.ROLLBACK_RELEASE, "ROLLED_BACK", true);
  }

  private UiLayoutReleaseHead moveHead(Scope scope, UUID releaseId, UUID ifMatch, String actor, String reason,
      UiLayoutLifecycleOperation operation, String eventType, boolean rollback) {
    requirePolicy(operation, scope, actor); UiLayoutRelease release = release(scope, releaseId); requireApproved(releaseId);
    List<UiLayoutReleaseMember> releaseMembers = members.findByReleaseIdOrderByMemberOrder(releaseId);
    if (releaseMembers.isEmpty()) fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, "A release without members cannot be activated.");
    validator.validate(release, List.copyOf(releaseMembers));
    var existingHead = heads.findForUpdate(scope.tenantId(), scope.environment(), scope.rootTarget().componentType(), scope.rootTarget().componentId());
    UiLayoutReleaseHead head = existingHead.orElseGet(() -> UiLayoutReleaseHead.builder().id(UUID.randomUUID()).tenantId(scope.tenantId())
        .environment(scope.environment()).rootComponentType(scope.rootTarget().componentType()).rootComponentId(scope.rootTarget().componentId())
        .headEtag(UUID.randomUUID()).updatedAt(Instant.now(clock)).rowVersion(0L).build());
    if (existingHead.isPresent()) requireMatch(ifMatch, head.getHeadEtag());
    else if (ifMatch != null) fail(UiLayoutLifecycleException.Code.PRECONDITION_FAILED, "A creation precondition cannot name a head that does not exist.");
    if (releaseId.equals(head.getActiveReleaseId())) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "The requested release is already active.");
    UUID previous = head.getActiveReleaseId();
    if (rollback && previous == null) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "Rollback requires an active release to replace.");
    head.setActiveReleaseId(releaseId); head.setHeadEtag(UUID.randomUUID()); head.setUpdatedAt(Instant.now(clock));
    try { heads.saveAndFlush(head); } catch (DataIntegrityViolationException exception) {
      throw failure(UiLayoutLifecycleException.Code.PRECONDITION_FAILED, "Publication head changed concurrently; reload the scoped state.");
    }
    append(eventType, release, previous, releaseId, head.getHeadEtag(), actor, reason); return head;
  }

  private void transitionReview(Scope scope, UUID releaseId, UUID ifMatch, String actor, String from, String to,
      UiLayoutLifecycleOperation operation, String eventType, String reason) {
    requirePolicy(operation, scope, actor); UiLayoutRelease release = release(scope, releaseId);
    UiLayoutReleaseReview review = reviews.findForUpdateByReleaseId(releaseId)
        .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Release review was not found."));
    requireMatch(ifMatch, review.getReviewEtag());
    if (!from.equals(review.getState())) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "Release review is not in the required state.");
    review.setState(to); review.setReviewEtag(UUID.randomUUID());
    if ("SUBMITTED".equals(to)) { review.setSubmittedBy(require(actor, "actor")); review.setSubmittedAt(Instant.now(clock)); }
    reviews.save(review);
    if ("APPROVED".equals(to)) approvals.save(UiLayoutReleaseApproval.builder().id(UUID.randomUUID()).releaseId(releaseId)
        .actor(require(actor, "actor")).reason(require(reason, "reason")).approvedAt(Instant.now(clock)).build());
    append(eventType, release, null, releaseId, null, actor, reason);
  }

  private UiLayoutReleaseHead head(Scope scope) {
    return heads.findForUpdate(scope.tenantId(), scope.environment(), scope.rootTarget().componentType(), scope.rootTarget().componentId())
        .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Release head was not found for the requested composition."));
  }
  private UiLayoutRelease release(Scope scope, UUID id) { return releases.findByIdAndTenantIdAndEnvironment(id, scope.tenantId(), scope.environment())
      .filter(value -> scope.rootTarget().componentType().equals(value.getRootComponentType()) && scope.rootTarget().componentId().equals(value.getRootComponentId()))
      .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Release was not found in the requested scope.")); }
  private UiLayoutDraft draft(Scope scope, UUID id) { return drafts.findForUpdateById(id)
      .filter(value -> scope.tenantId().equals(value.getTenantId()) && scope.environment().equals(value.getEnvironment())
          && scope.rootTarget().componentType().equals(value.getRootComponentType()) && scope.rootTarget().componentId().equals(value.getRootComponentId()))
      .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND, "Draft was not found in the requested scope.")); }
  private UiLayoutDraft editableDraft(Scope scope, UUID id, UUID ifMatch) {
    UiLayoutDraft draft = draft(scope, id); requireMatch(ifMatch, draft.getDraftEtag());
    if (!"DRAFT".equals(draft.getState())) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "Only a draft can be changed.");
    return draft;
  }
  private void requireApproved(UUID releaseId) { if (!reviews.findByReleaseId(releaseId).map(value -> "APPROVED".equals(value.getState())).orElse(false)) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "Only an approved release can be activated."); }
  private void append(String eventType, UiLayoutRelease release, UUID from, UUID to, UUID headEtag, String actor, String reason) { events.save(UiLayoutReleaseEvent.builder().id(UUID.randomUUID()).tenantId(release.getTenantId()).environment(release.getEnvironment())
      .rootComponentType(release.getRootComponentType()).rootComponentId(release.getRootComponentId()).eventType(eventType).fromReleaseId(from).toReleaseId(to)
      .headEtag(headEtag).actor(require(actor, "actor")).reason(require(reason, "reason")).createdAt(Instant.now(clock)).build()); }
  private void requirePolicy(UiLayoutLifecycleOperation operation, Scope scope, String actor) { if (!policy.allows(operation, require(actor, "actor"), scope.tenantId(), scope.administrativeUnit(), scope.environment(), scope.rootTarget())) fail(UiLayoutLifecycleException.Code.DENIED, "Host lifecycle policy denied the requested operation."); }
  private void requireRevisionAllocationLock(Scope scope, UiLayoutTarget target) {
    if (revisionAllocationLock == null) fail(UiLayoutLifecycleException.Code.INVALID_STATE, "No revision allocation lock is installed.");
    revisionAllocationLock.lock(scope.tenantId(), scope.environment(), target);
  }
  private void requireMatch(UUID expected, UUID current) { if (expected == null || !expected.equals(current)) fail(UiLayoutLifecycleException.Code.PRECONDITION_FAILED, "The supplied strong ETag does not match the current representation."); }
  private String requireJsonObject(String json, String name) { try { JsonNode node = objectMapper.readTree(require(json, name)); if (node == null || !node.isObject()) fail(UiLayoutLifecycleException.Code.INVALID_RELEASE, name + " must be a JSON object."); return objectMapper.writeValueAsString(node); } catch (UiLayoutLifecycleException exception) { throw exception; } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, name + " must be valid JSON."); } }
  private String hashExact(String json) { try { return hashes.sha256Exact(objectMapper.readTree(json)); } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, "Cannot hash layout revision."); } }
  private static String require(String value, String name) { if (value == null || value.isBlank()) throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, name + " is required."); return value.trim(); }
  private static void fail(UiLayoutLifecycleException.Code code, String message) { throw failure(code, message); }
  private static UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code, String message) { return new UiLayoutLifecycleException(code, message); }

  public record Scope(String tenantId, String administrativeUnit, String environment, UiLayoutTarget rootTarget) {
    public Scope { tenantId = require(tenantId, "tenantId"); administrativeUnit = require(administrativeUnit, "administrativeUnit"); environment = require(environment, "environment"); if (rootTarget == null) throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, "rootTarget is required."); }
  }
  public record RevisionCommand(Scope scope, UUID draftId, UUID ifMatch, UiLayoutTarget target, String patchDocument, String schemaVersion, String reason) { public RevisionCommand { if (target == null) throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, "target is required."); } }
  public record AssignmentCommand(Scope scope, UUID draftId, UUID ifMatch, UUID revisionId, UiLayoutTarget target, String layerClass, String selectorDocument, short priority, String contributionKey) {
    public AssignmentCommand { if (target == null) throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, "target is required."); }
  }
  public record MemberCommand(int memberOrder, UiLayoutTarget target, UUID assignmentRevisionId, UUID contentRevisionId) { public MemberCommand { if (target == null) throw failure(UiLayoutLifecycleException.Code.INVALID_RELEASE, "member target is required."); } }
  public record ReleaseCommand(Scope scope, UUID draftId, UUID ifMatch, List<MemberCommand> members,
      String releasedDraftDocument) {}
}
