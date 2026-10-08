package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;

@Tag("unit")
class UiLayoutHistoricalEvidenceServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutDraftWorkspaceCodec codec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
  private final UiLayoutLifecycleInvocationProvider invocations = mock(UiLayoutLifecycleInvocationProvider.class);
  private final UiLayoutLifecycleAdmission admission = mock(UiLayoutLifecycleAdmission.class);
  private final UiLayoutHistoricalEvidenceAccess access = mock(UiLayoutHistoricalEvidenceAccess.class);
  private final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  private final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  private final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
  private final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
  private final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
  private final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
  private final Principal principal = () -> "author";
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final UiLayoutTarget removed = new UiLayoutTarget("praxis-dynamic-form", "old-editor");
  private final UUID releaseId = UUID.randomUUID();
  private final UUID draftId = UUID.randomUUID();
  private UiLayoutLifecycleInvocation invocation;
  private UiLayoutRelease release;
  private UiLayoutDraft draft;
  private List<UiLayoutReleaseMember> pinned;
  private List<UiLayoutDraftWorkspaceDocument.TargetState> states;
  private UiLayoutHistoricalEvidenceService service;

  @BeforeEach
  void setUp() throws Exception {
    invocation = new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx",
        new UiLayoutCompositionRegistration(root, List.of(root)));
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation);
    release = UiLayoutRelease.builder().id(releaseId).tenantId("tenant").environment("lab")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).sourceDraftId(draftId).build();
    draft = UiLayoutDraft.builder().id(draftId).tenantId("tenant").environment("lab")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).state("RELEASED")
        .draftEtag(UUID.randomUUID()).build();
    states = new ArrayList<>(); pinned = new ArrayList<>();
    for (UiLayoutTarget target : List.of(root, removed)) {
      UUID definitionId = UUID.randomUUID(), revisionId = UUID.randomUUID(), assignmentId = UUID.randomUUID();
      var b0 = mapper.readTree("{\"kind\":\"native\",\"config\":{\"header\":\"original\"}}");
      var c0 = mapper.readTree("{\"kind\":\"native\",\"config\":{\"header\":\"authored\"}}");
      var patch = mapper.readTree("{\"header\":\"authored\"}");
      String patchHash = hashes.sha256Exact(patch);
      var selector = new UiLayoutAudienceSelectorCommand("tenant", null, null, null, "private-profile", null);
      states.add(new UiLayoutDraftWorkspaceDocument.TargetState(target,
          new UiLayoutAuthoringDocumentDescriptor("native", "urn:native", "1"),
          new UiLayoutPatchDocumentDescriptor("urn:patch", "1"),
          new UiLayoutDraftWorkspaceDocument.BaselineDocument("host:b0", hashes.sha256Exact(b0), b0),
          new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(c0), c0),
          new UiLayoutDraftWorkspaceDocument.RevisionSelection(revisionId, 1, patchHash, "1", UUID.randomUUID()),
          new UiLayoutDraftWorkspaceDocument.AssignmentSelection(assignmentId, revisionId, "PROFILE", selector,
              (short) 1, "main", UUID.randomUUID())));
      when(definitions.findById(definitionId)).thenReturn(Optional.of(UiLayoutDefinition.builder()
          .id(definitionId).tenantId("tenant").environment("lab").componentType(target.componentType())
          .componentId(target.componentId()).schemaVersion("1").build()));
      when(revisions.findById(revisionId)).thenReturn(Optional.of(UiLayoutRevision.builder().id(revisionId)
          .definitionId(definitionId).revisionNumber(1L).contentHash(patchHash).schemaVersion("1").patchDocument(patch.toString()).build()));
      when(assignments.findById(assignmentId)).thenReturn(Optional.of(UiLayoutAssignmentRevision.builder()
          .id(assignmentId).tenantId("tenant").environment("lab").revisionId(revisionId).layerClass("PROFILE")
          .selectorDocument(mapper.valueToTree(selector).toString()).priority((short) 1).contributionKey("main").build()));
      pinned.add(UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(releaseId).memberOrder(pinned.size())
          .componentType(target.componentType()).componentId(target.componentId()).contentRevisionId(revisionId)
          .assignmentRevisionId(assignmentId).contributionKey("main").build());
    }
    draft.setDraftDocument(codec.encode(new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
        states, UUID.randomUUID())));
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant", "lab")).thenReturn(Optional.of(release));
    when(releases.findBySourceDraftId(draftId)).thenReturn(Optional.of(release));
    when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(draftId, "tenant", "lab",
        root.componentType(), root.componentId())).thenReturn(Optional.of(draft));
    when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(pinned);
    service = service(admission, access);
  }

  private UiLayoutHistoricalEvidenceService service(UiLayoutLifecycleAdmission gate, UiLayoutHistoricalEvidenceAccess content) {
    return new UiLayoutHistoricalEvidenceService(invocations, gate, content, releases, drafts, members,
        definitions, revisions, assignments, mapper, hashes, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
  }

  private UiLayoutHistoricalEvidenceReceipt recover() { return service.recover(principal, "ctx", root, releaseId); }

  private void changeAfterFinalAccess(java.util.concurrent.atomic.AtomicBoolean changed) {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> { if (calls.incrementAndGet() == 4) changed.set(true); return null; })
        .when(access).require(eq(invocation), any(), any(UiLayoutValidationContext.class));
  }

  @Test void operationRevokedAfterFinalAccessWithholdsHistoricalDocuments() {
    var revoked = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalAccess(revoked);
    doAnswer(call -> {
      if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private-revocation");
      return null;
    }).when(admission).require(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, invocation);
    fails(UiLayoutLifecycleException.Code.DENIED);
    verify(releases, never()).save(any()); verify(drafts, never()).save(any());
  }

  @Test void actorChangedAfterFinalAccessWithholdsDocumentsWithSameContextVersion() {
    var changed = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalAccess(changed);
    var other = new UiLayoutLifecycleInvocation("another-actor", invocation.tenantId(), invocation.administrativeUnit(),
        invocation.environment(), invocation.contextVersion(), invocation.composition());
    when(invocations.resolve(principal, "ctx", root)).thenAnswer(call -> changed.get() ? other : invocation);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }

  @Test void finalAuthorityLookupCannotAcceptAnExpiredAttempt() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var captured = new java.util.concurrent.atomic.AtomicReference<UiLayoutValidationContext>();
    doAnswer(call -> { captured.set(call.getArgument(2)); return null; })
        .when(access).require(eq(invocation), any(), any(UiLayoutValidationContext.class));
    when(invocations.resolve(principal, "ctx", root)).thenAnswer(call -> {
      if (calls.incrementAndGet() == 3) captured.get().attempt().close(); return invocation;
    });
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(captured.get().attempt().remainingBudget()).isZero();
  }

  @Test void finalOperationTechnicalFailureIsSanitizedAndClosesTheAttempt() {
    var changed = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalAccess(changed);
    var captured = new java.util.concurrent.atomic.AtomicReference<UiLayoutValidationContext>();
    doAnswer(call -> { captured.set(call.getArgument(2)); return null; })
        .when(access).requireTarget(eq(invocation), any(), any(UiLayoutValidationContext.class));
    doAnswer(call -> { if (changed.get()) throw new IllegalStateException("private-credential"); return null; })
        .when(admission).require(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, invocation);
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(captured.get().attempt().remainingBudget()).isZero();
  }

  @Test void nestedRecoveryRetainsCallerAttemptAndDoesNotSelectAnotherBudget() {
    var nested = new UiLayoutHistoricalEvidenceService(invocations, admission, access, releases, drafts, members,
        definitions, revisions, assignments, mapper, hashes, (op, inv) -> {
          throw new AssertionError("Nested recovery must not select a new budget");
        });
    var validation = new UiLayoutValidationContext(UiLayoutValidationAttempt.start(invocation,
        UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, java.time.Duration.ofMinutes(1)),
        UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
    try (var attempt = validation.attempt()) {
      var first = nested.recoverWithin(principal, "ctx", root, releaseId, validation);
      assertThat(nested.recoverWithin(principal, "ctx", root, releaseId, validation)).isEqualTo(first);
      validation.require(invocation);
      verify(access, times(8)).require(eq(invocation), any(), same(validation));
      verify(admission, times(6)).require(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, invocation);
      assertThat(attempt.operation()).isEqualTo(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION);
    }
  }

  private void fails(UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(this::recover).isInstanceOf(UiLayoutLifecycleException.class)
        .hasMessage("Historical layout evidence is unavailable.")
        .extracting(e -> ((UiLayoutLifecycleException) e).getCode()).isEqualTo(code);
  }

  @Test
  void recoversRemovedTargetWithVerifiedEvidenceAndNoSelectorsOrMutation() throws Exception {
    String original = draft.getDraftDocument();
    var receipt = recover();
    assertThat(receipt.targets()).extracting(UiLayoutHistoricalEvidenceReceipt.Target::target).containsExactly(root, removed);
    assertThat(receipt.targets().getLast().baseline().document().at("/config/header").asText()).isEqualTo("original");
    assertThat(receipt.targets().getLast().working().document().at("/config/header").asText()).isEqualTo("authored");
    assertThat(receipt.releaseRef()).isEqualTo(releaseId);
    assertThat(mapper.writeValueAsString(receipt)).doesNotContain("private-profile", "selector", "layerClass", "createdBy");
    ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.targets().getLast().baseline().document()).put("changed", true);
    assertThat(receipt.targets().getLast().baseline().document().has("changed")).isFalse();
    assertThat(draft.getDraftDocument()).isEqualTo(original);
    verify(admission, times(3)).require(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, invocation);
    verify(access, times(4)).require(eq(invocation), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(releases, never()).save(any()); verify(drafts, never()).save(any());
    assertThatThrownBy(() -> codec.decode(original, invocation.composition())).isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void absentAdmissionOrContentProviderDeniesBeforeRepositories() {
    service = service(null, access); fails(UiLayoutLifecycleException.Code.DENIED);
    service = service(admission, null); fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(releases, drafts, members, definitions, revisions, assignments);
  }

  @Test
  void sessionAndStaleContextFailBeforeRepositories() {
    assertThatThrownBy(() -> service.recover(null, "ctx", root, releaseId)).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(e -> ((UiLayoutLifecycleException) e).getCode()).isEqualTo(UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    when(invocations.resolve(principal, "ctx", root)).thenReturn(null);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    verifyNoInteractions(admission, access, releases, drafts, members);
  }

  @Test
  void anotherTenantEnvironmentOrRootIsNotDisclosed() {
    release.setTenantId("other"); fails(UiLayoutLifecycleException.Code.NOT_FOUND);
    release.setTenantId("tenant"); release.setEnvironment("other"); fails(UiLayoutLifecycleException.Code.NOT_FOUND);
    release.setEnvironment("lab"); release.setRootComponentId("other"); fails(UiLayoutLifecycleException.Code.NOT_FOUND);
    verifyNoInteractions(drafts, access);
  }

  @Test
  void missingSourceOrLegacyEnvelopeDoesNotUseCurrentHead() {
    release.setSourceDraftId(null); fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    release.setSourceDraftId(draftId); draft.setDraftDocument("{}"); fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(members, access);
  }

  @Test
  void sourceDraftScopeAndFreezeStateAreRequired() {
    draft.setEnvironment("other"); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    draft.setEnvironment("lab"); draft.setState("DRAFT"); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    draft.setState("RELEASED"); draft.setDraftDocument(codec.encode(new UiLayoutDraftWorkspaceDocument(
        UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, states, null)));
    fails(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test
  void deniedRemovedTargetPreventsAnyReceiptAndDoesNotLeakPolicyReason() {
    doAnswer(call -> {
      UiLayoutTarget target = call.getArgument(1);
      if (removed.equals(target)) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private policy reason");
      return null;
    }).when(access).requireTarget(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(members, revisions, definitions, assignments);
  }

  @Test
  void contentSafetyFailureIsSanitizedAndNotPartiallyReturned() {
    doThrow(new IllegalStateException("secret token value")).when(access).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }

  @Test
  void membershipMustMatchFrozenTargetsOrderAndRefs() {
    pinned.getLast().setMemberOrder(0); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    pinned.getLast().setMemberOrder(1); pinned.getLast().setAssignmentRevisionId(UUID.randomUUID());
    fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    pinned.removeLast(); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test
  void corruptedNativeOrPatchHashFailsClosed() throws Exception {
    String original = draft.getDraftDocument();
    var envelope = mapper.readTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.at("/targets/0/baseline/document")).put("forged", true);
    draft.setDraftDocument(envelope.toString()); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    draft.setDraftDocument(original);
    var revision = revisions.findById(states.getFirst().selectedRevision().revisionRef()).orElseThrow();
    revision.setPatchDocument("{\"forged\":true}"); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test
  void immutableDefinitionOrSelectorDriftFailsClosed() {
    var revision = revisions.findById(states.getLast().selectedRevision().revisionRef()).orElseThrow();
    var definition = definitions.findById(revision.getDefinitionId()).orElseThrow();
    definition.setTenantId("other"); fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    definition.setTenantId("tenant");
    assignments.findById(states.getLast().selectedAssignment().assignmentRevisionRef()).orElseThrow()
        .setSelectorDocument("{\"tenant\":\"other\"}");
    fails(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test
  void sourceWorkspaceMustBeFrozenByThisExactRelease() {
    when(releases.findBySourceDraftId(draftId)).thenReturn(Optional.of(UiLayoutRelease.builder()
        .id(UUID.randomUUID()).sourceDraftId(draftId).tenantId("tenant").environment("lab")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).build()));
    fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    verifyNoInteractions(access, members, revisions);
  }

  @Test
  void selectedRecordIdentityCannotDifferFromFrozenReference() {
    revisions.findById(states.getLast().selectedRevision().revisionRef()).orElseThrow().setId(UUID.randomUUID());
    fails(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test
  void contextChangedDuringRecoveryDiscardsEvidence() {
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation, null);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }

  @Test
  void returnsExactVerifiedPatchFromOneRevisionReadAndDefendsItsContent() {
    var receipt = recover();
    for (int i = 0; i < states.size(); i++) {
      var evidence = receipt.targets().get(i);
      assertThat(evidence.revisionPatch().contentHash()).isEqualTo(states.get(i).selectedRevision().contentHash());
      assertThat(evidence.revisionPatch().document().path("header").asText()).isEqualTo("authored");
      ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.revisionPatch().document()).put("header", "changed");
      assertThat(evidence.revisionPatch().document().path("header").asText()).isEqualTo("authored");
      verify(revisions).findById(evidence.revisionRef());
    }
    verify(access, times(4)).requireTarget(eq(invocation), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(access, times(4)).require(eq(invocation), argThat(evidence -> evidence.revisionPatch() != null), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }

  @Test
  void oldDocumentOnlyProviderCannotImplicitlyAuthorizePatchContent() {
    service = service(admission, (inv, evidence, validation) -> {});
    fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(members, revisions, definitions, assignments);
  }

  @Test
  void protectedPatchWithholdsCompleteReceiptAndSanitizesReason() {
    doAnswer(call -> {
      UiLayoutHistoricalEvidenceReceipt.Target evidence = call.getArgument(1);
      if (removed.equals(evidence.target())) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private patch content");
      return null;
    }).when(access).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test
  void finalPatchAccessRevocationDiscardsEvidence() {
    doNothing().doNothing().doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked"))
        .when(access).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test
  void ambiguousOrOversizedPersistedPatchCannotBeReturned() {
    var revision = revisions.findById(states.getFirst().selectedRevision().revisionRef()).orElseThrow();
    for (String invalid : List.of("{\"header\":\"hidden\",\"header\":\"authored\"}",
        "{\"header\":\"authored\"} {}", "[]", "{\"value\":\"" + "x".repeat(UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES) + "\"}")) {
      revision.setPatchDocument(invalid);
      fails(UiLayoutLifecycleException.Code.INVALID_STATE);
    }
    verify(access, never()).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }

  @Test
  void preservesNullFalseAndEmptyArrayWithoutInferringTheirMeaning() throws Exception {
    var patch = mapper.readTree("{\"reset\":null,\"disabled\":false,\"items\":[]}");
    var state = states.getFirst();
    String hash = hashes.sha256Exact(patch);
    var selected = state.selectedRevision();
    var revision = revisions.findById(selected.revisionRef()).orElseThrow();
    revision.setPatchDocument(patch.toString()); revision.setContentHash(hash);
    states.set(0, new UiLayoutDraftWorkspaceDocument.TargetState(state.target(), state.authoring(), state.patch(),
        state.baseline(), state.working(), new UiLayoutDraftWorkspaceDocument.RevisionSelection(selected.revisionRef(),
            selected.revisionNumber(), hash, selected.schemaVersion(), selected.acceptedCommandRef()), state.selectedAssignment()));
    draft.setDraftDocument(codec.encode(new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
        states, UUID.randomUUID())));
    assertThat(recover().targets().getFirst().revisionPatch().document()).isEqualTo(patch);
  }
}
