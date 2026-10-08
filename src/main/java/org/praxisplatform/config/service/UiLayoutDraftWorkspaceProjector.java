package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.dto.UiLayoutDraftReceipt;
import org.praxisplatform.config.dto.UiLayoutDraftWorkspaceReceipt;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

/** Verifies persisted refs and projects an authorized workspace without replacing its historical evidence. */
public class UiLayoutDraftWorkspaceProjector {
  private final UiLayoutDraftWorkspaceCodec codec;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutWorkspaceSelectionVerifier selections;
  private final UiLayoutLifecycleStructureValidator structure;

  public UiLayoutDraftWorkspaceProjector(UiLayoutDraftWorkspaceCodec codec, UiLayoutDefinitionRepository definitions,
      UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments,
      UiLayoutReleaseRepository releases, ObjectMapper mapper, UiLayoutLifecycleStructureValidator structure) {
    this.codec = codec;
    this.releases = releases; this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.selections = new UiLayoutWorkspaceSelectionVerifier(definitions, revisions, assignments, mapper, new CanonicalJsonHashService(mapper));
  }

  public UiLayoutDraftWorkspaceReceipt project(UiLayoutLifecycleInvocation invocation, UiLayoutDraft draft, UiLayoutValidationContext validation) {
    validation.requirePurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ);
    validation.require(invocation);
    UiLayoutDraftWorkspaceDocument document = codec.decode(draft.getDraftDocument(), invocation.composition());
    List<UiLayoutDraftWorkspaceReceipt.Target> targets = new ArrayList<>();
    for (UiLayoutDraftWorkspaceDocument.TargetState state : document.targets()) {
      structure.validateTarget(invocation, state.target(), validation);
      selections.verify(invocation, state);
      targets.add(target(state));
    }
    String frozenReleaseRef = releases.findBySourceDraftId(draft.getId()).map(value -> {
      verifyRelease(invocation, draft, value);
      return value.getId().toString();
    }).orElse(null);
    boolean released = "RELEASED".equals(draft.getState());
    if (released != (frozenReleaseRef != null) || released != (document.freezeCommandRef() != null)) {
      throw invalid("Workspace release state, freeze command and authoritative source release are inconsistent.");
    }
    validation.require(invocation);
    return new UiLayoutDraftWorkspaceReceipt(document.schemaVersion(), draft(draft), targets, frozenReleaseRef,
        document.freezeCommandRef());
  }

  public UiLayoutDraftWorkspaceDocument decode(UiLayoutLifecycleInvocation invocation, UiLayoutDraft draft) {
    return codec.decode(draft.getDraftDocument(), invocation.composition());
  }

  private UiLayoutDraftWorkspaceReceipt.Target target(UiLayoutDraftWorkspaceDocument.TargetState state) {
    var baseline = new UiLayoutDraftWorkspaceReceipt.AuthoringDocument(state.baseline().sourceRef(),
        state.baseline().contentHash(), state.baseline().document());
    var working = new UiLayoutDraftWorkspaceReceipt.AuthoringDocument(null,
        state.working().contentHash(), state.working().document());
    var revision = state.selectedRevision() == null ? null : new UiLayoutDraftWorkspaceReceipt.RevisionSelection(
        state.selectedRevision().revisionRef(), state.selectedRevision().revisionNumber(),
        state.selectedRevision().contentHash(), state.selectedRevision().schemaVersion(),
        state.selectedRevision().acceptedCommandRef());
    var assignment = state.selectedAssignment() == null ? null : new UiLayoutDraftWorkspaceReceipt.AssignmentSelection(
        state.selectedAssignment().assignmentRevisionRef(), state.selectedAssignment().revisionRef(),
        state.selectedAssignment().layerClass(), state.selectedAssignment().selector(), state.selectedAssignment().priority(),
        state.selectedAssignment().contributionKey(), state.selectedAssignment().acceptedCommandRef());
    return new UiLayoutDraftWorkspaceReceipt.Target(state.target(), state.authoring().documentType(),
        state.authoring().schemaRef(), state.authoring().schemaVersion(), state.patch().schemaRef(),
        state.patch().schemaVersion(), baseline, working, revision, assignment);
  }

  private void verifyRelease(UiLayoutLifecycleInvocation invocation, UiLayoutDraft draft, UiLayoutRelease release) {
    if (!draft.getId().equals(release.getSourceDraftId())
        || !invocation.tenantId().equals(release.getTenantId())
        || !invocation.environment().equals(release.getEnvironment())
        || !draft.getRootComponentType().equals(release.getRootComponentType())
        || !draft.getRootComponentId().equals(release.getRootComponentId())) {
      throw invalid("Workspace frozen release is outside its source draft scope.");
    }
  }

  public UiLayoutDraftReceipt draft(UiLayoutDraft value) {
    return new UiLayoutDraftReceipt(value.getId().toString(),
        new org.praxisplatform.config.dto.UiLayoutTarget(value.getRootComponentType(), value.getRootComponentId()),
        value.getState(), value.getDraftEtag().toString(), value.getCreatedAt(), value.getUpdatedAt());
  }

  private UiLayoutLifecycleException invalid(String message) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, message);
  }
}
