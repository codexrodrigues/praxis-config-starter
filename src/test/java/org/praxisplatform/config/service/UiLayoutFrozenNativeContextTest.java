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
class UiLayoutFrozenNativeContextTest {
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
  private UiLayoutLifecycleCommandService service;
  private final UiLayoutLifecycleService core = mock(UiLayoutLifecycleService.class);
  private final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
  private final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class, CALLS_REAL_METHODS);
  private final UUID headEtag = UUID.randomUUID();
  private final UUID originalHead = UUID.randomUUID();
  private final UiLayoutReleaseHead head = UiLayoutReleaseHead.builder().headEtag(headEtag).activeReleaseId(originalHead).build();
  private final Manager transactions = new Manager();

  @BeforeEach
  void setUp() throws Exception {
    invocation = new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx",
        new UiLayoutCompositionRegistration(root, List.of(root, removed)));
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
    doNothing().when(structure).validateAuthoringBaseline(any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    doNothing().when(structure).validateAuthoringRevision(any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    when(heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(any(), any(), any(), any())).thenReturn(Optional.of(head));
    when(core.publish(any(), eq(releaseId), any(), any(), any())).thenReturn(head);
    when(core.rollback(any(), eq(releaseId), any(), any(), any())).thenReturn(head);
    service = new UiLayoutLifecycleCommandService(core, invocations, admission, structure, drafts,
        mock(UiLayoutReleaseReviewRepository.class), heads, mapper,
        new org.springframework.transaction.support.TransactionTemplate(transactions), releases, members, assignments,
        revisions, definitions, hashes, null, null, null, null, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
  }

  private void publish() { service.publish(principal, "ctx", root,
      UiLayoutLifecycleCommandService.HeadWriteCondition.match(headEtag.toString()), releaseId, "publish"); }
  private void rollback() { service.rollback(principal, "ctx", root,
      UiLayoutLifecycleCommandService.HeadWriteCondition.match(headEtag.toString()), releaseId, "rollback"); }
  private void rejectsWithoutHeadWrite() {
    assertThatThrownBy(this::publish).isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(this::rollback).isInstanceOf(UiLayoutLifecycleException.class);
    verify(core, never()).publish(any(), any(), any(), any(), any());
    verify(core, never()).rollback(any(), any(), any(), any(), any());
    assertThat(head.getActiveReleaseId()).isEqualTo(originalHead);
    assertThat(head.getHeadEtag()).isEqualTo(headEtag);
    assertThat(transactions.rollbacks).isEqualTo(2);
  }

  @Test void transportsExactNativeContextThroughDefaultValidatorOnPublishAndRollback() {
    publish(); rollback();
    var captured = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(structure, times(2)).validateFrozenRelease(eq(invocation), captured.capture(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    List<UiLayoutLifecycleFrozenContribution> values = captured.getValue();
    assertThat(values).hasSize(2);
    var first = values.getFirst(); var context = first.authoring();
    assertThat(context.releaseId()).isEqualTo(releaseId);
    assertThat(context.sourceDraftId()).isEqualTo(draftId);
    assertThat(context.baselineDocument().at("/config/header").asText()).isEqualTo("original");
    assertThat(context.candidateDocument().at("/config/header").asText()).isEqualTo("authored");
    assertThat(context.baselineHash()).isEqualTo(hashes.sha256Exact(context.baselineDocument()));
    assertThat(context.candidateHash()).isEqualTo(hashes.sha256Exact(context.candidateDocument()));
    assertThat(first.candidate().revisionRef()).isEqualTo(states.getFirst().selectedRevision().revisionRef().toString());
    verify(structure, times(4)).validateAuthoringBaseline(eq(invocation), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(structure, times(4)).validateAuthoringRevision(eq(invocation), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(admission, never()).require(eq(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE), any());
    verifyNoInteractions(access);
    ((com.fasterxml.jackson.databind.node.ObjectNode)context.candidateDocument()).put("mutated", true);
    assertThat(context.candidateDocument().has("mutated")).isFalse();
    assertThat(transactions.commits).isEqualTo(2);
  }

  @Test void inconsistentNativeFormIsRejectedBeforePublishOrRollbackHeadMutation() throws Exception {
    var previous = states.get(1);
    var baseline = mapper.readTree("{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{\"header\":\"original\"}}");
    var candidate = mapper.readTree("{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{\"header\":\"unreproduced\"}}");
    var selected = previous.selectedRevision();
    var persisted = revisions.findById(selected.revisionRef()).orElseThrow(); persisted.setSchemaVersion("praxis.ui-layout/v1");
    definitions.findById(persisted.getDefinitionId()).orElseThrow().setSchemaVersion("praxis.ui-layout/v1");
    states.set(1, new UiLayoutDraftWorkspaceDocument.TargetState(previous.target(),
        new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "test.form", "1"),
        new UiLayoutPatchDocumentDescriptor("test.patch", "praxis.ui-layout/v1"),
        new UiLayoutDraftWorkspaceDocument.BaselineDocument("test-only:form", hashes.sha256Exact(baseline), baseline),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(candidate), candidate),
        new UiLayoutDraftWorkspaceDocument.RevisionSelection(selected.revisionRef(), selected.revisionNumber(),
            selected.contentHash(), "praxis.ui-layout/v1", selected.acceptedCommandRef()), previous.selectedAssignment()));
    draft.setDraftDocument(codec.encode(new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, states, UUID.randomUUID())));
    rejectsWithoutHeadWrite();
    verify(structure, never()).validateFrozenRelease(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }
  @Test void missingSourceDraftFailsBeforeHeadWrite() { release.setSourceDraftId(null); rejectsWithoutHeadWrite(); }
  @Test void wrongSourceDraftScopeFailsBeforeHeadWrite() { draft.setTenantId("other"); rejectsWithoutHeadWrite(); }
  @Test void wrongSourceReleaseFailsBeforeHeadWrite() {
    when(releases.findBySourceDraftId(draftId)).thenReturn(Optional.of(UiLayoutRelease.builder().id(UUID.randomUUID()).build()));
    rejectsWithoutHeadWrite();
  }
  @Test void notReleasedWorkspaceFailsBeforeHeadWrite() { draft.setState("EDITING"); rejectsWithoutHeadWrite(); }
  @Test void forgedBaselineHashFailsBeforeHeadWrite() throws Exception {
    var node=mapper.readTree(draft.getDraftDocument());
    ((com.fasterxml.jackson.databind.node.ObjectNode)node.at("/targets/0/baseline/document")).put("forged",true);
    draft.setDraftDocument(node.toString()); rejectsWithoutHeadWrite();
  }
  @Test void wrongPinnedRevisionFailsBeforeHeadWrite() { pinned.getFirst().setContentRevisionId(UUID.randomUUID()); rejectsWithoutHeadWrite(); }
  @Test void wrongPinnedAssignmentFailsBeforeHeadWrite() { pinned.getFirst().setAssignmentRevisionId(UUID.randomUUID()); rejectsWithoutHeadWrite(); }
  @Test void forgedPatchFailsBeforeHeadWrite() {
    revisions.findById(states.getFirst().selectedRevision().revisionRef()).orElseThrow().setPatchDocument("{\"forged\":true}");
    rejectsWithoutHeadWrite();
  }
  @Test void staleCompositionFailsBeforeHeadWrite() {
    invocation = new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", new UiLayoutCompositionRegistration(root,List.of(root)));
    when(invocations.resolve(principal,"ctx",root)).thenReturn(invocation); rejectsWithoutHeadWrite();
  }
  @Test void lateOwnerValidationFailureRollsBackWithoutHeadWrite() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,"owner failure"))
        .when(structure).validateAuthoringRevision(any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    rejectsWithoutHeadWrite();
  }
  private static final class Manager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
    int commits, rollbacks;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {}
    @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
    @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
  }
}
