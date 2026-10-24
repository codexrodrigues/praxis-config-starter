package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;

/** Shared native history/admission only. Callers own current grants, audience and group dependency policy. */
final class UiLayoutNativeSnapshotReader {
  private final UiLayoutLifecycleStructureValidator structure;
  private final UiLayoutMetadataCapturePersistence metadata;
  private final UiLayoutBaselineMetadataAdmission metadataAdmission;
  private final UiLayoutDraftWorkspaceSource workspaceSource;
  private final UiLayoutPageChildCaptures pageChildren;
  private final UiLayoutFrozenWorkspaceResolver frozen;

  UiLayoutNativeSnapshotReader(UiLayoutLifecycleStructureValidator structure,
      UiLayoutMetadataCapturePersistence metadata, UiLayoutBaselineMetadataAdmission metadataAdmission,
      UiLayoutDraftWorkspaceSource workspaceSource, UiLayoutReleaseRepository releases,
      UiLayoutDraftRepository drafts, UiLayoutReleaseMemberRepository members,
      UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions,
      UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper, CanonicalJsonHashService hashes) {
    this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.metadata = Objects.requireNonNull(metadata);
    this.metadataAdmission = Objects.requireNonNull(metadataAdmission);
    this.workspaceSource = Objects.requireNonNull(workspaceSource);
    this.pageChildren = new UiLayoutPageChildCaptures(hashes);
    this.frozen = new UiLayoutFrozenWorkspaceResolver(releases, drafts, members, definitions,
        revisions, assignments, mapper, hashes);
  }

  UiLayoutFrozenWorkspaceResolver.Snapshot read(UiLayoutLifecycleInvocation invocation,
      UUID release, UiLayoutFrozenRecordBatch batch, UiLayoutValidationContext outer) {
    outer.attempt().requireActive();
    var snapshot = frozen.resolve(invocation, release, target -> {
      if (!invocation.rootTarget().equals(target)) throw invalid();
    }, batch);
    if (snapshot.document().targets().size() != 1) throw invalid();
    var state = snapshot.document().targets().getFirst();
    var patch = snapshot.patches().getFirst();
    boolean page = isPage(state.target());
    String expectedKind = page ? UiLayoutPageRevisionReproduction.KIND
        : UiLayoutNavigationGroupsRevisionReproduction.KIND;
    // Bind dispatch to the governed root; an unrelated descriptor must not skip its verifier.
    if (state.authoring() == null || !expectedKind.equals(state.authoring().documentType())) throw invalid();
    if (page) {
      UiLayoutPageRevisionReproduction.require(state.target(), state.authoring(), state.baseline().document(),
          state.working().document(), state.patch(), patch);
    } else {
      UiLayoutNavigationGroupsRevisionReproduction.require(state.target(), state.authoring(), state.baseline().document(),
          state.working().document(), state.patch(), patch);
    }
    outer.attempt().requireActive();
    return snapshot;
  }

  void admit(UiLayoutLifecycleInvocation invocation,
      UiLayoutFrozenWorkspaceResolver.Snapshot snapshot, UiLayoutValidationContext outer) {
    outer.attempt().requireActive();
    try (var attempt = outer.attempt().correlate(invocation)) {
      var validation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.FROZEN_RELEASE);
      var state = snapshot.document().targets().getFirst();
      var assignment = state.selectedAssignment();
      var revision = state.selectedRevision();
      var selector = assignment.selector();
      var contribution = new UiLayoutLifecycleFrozenContribution(state.target(), assignment.contributionKey(),
          new UiLayoutResolutionCandidate(state.target(), revision.revisionRef().toString(), revision.contentHash(),
              UiLayoutResolutionCandidate.LayerClass.valueOf(assignment.layerClass()), assignment.priority(),
              new UiLayoutAudienceSelector(selector.tenant(), selector.organization(), selector.sector(),
                  selector.group(), selector.profile(), selector.user()), snapshot.patches().getFirst(), null),
          new UiLayoutLifecycleFrozenContribution.NativeAuthoring(snapshot.releaseId(), snapshot.draftId(),
              snapshot.document().freezeCommandRef(), state.authoring(), state.patch(), state.baseline().sourceRef(),
              state.baseline().contentHash(), state.baseline().document(), state.working().contentHash(), state.working().document()));
      var binding = new UiLayoutMetadataCapture.Binding(snapshot.draftId(),
          new UiLayoutMetadataCapture.Scope(invocation.tenantId(), invocation.environment(), invocation.rootTarget(), state.target()),
          state.baseline().sourceRef(), state.authoring(), state.baseline().contentHash());
      UiLayoutMetadataCaptureAccess access = (current, capture) -> validation.run(current, () ->
          metadataAdmission.require(current, state.target(), state.authoring(), state.baseline().sourceRef(),
              capture.baselineDocument(), new UiLayoutBaselineMetadataSeed(capture.source(), capture.reproduction(),
                  capture.observation(), capture.rawInputText())));
      validation.call(invocation, () -> metadata.readVerified(invocation, binding, access));
      pageChildren.verify(invocation, snapshot.draftId(), state.target(), state.baseline().document(),
          state.working().document(), state.pageChildren(), workspaceSource, validation);
      // Current metadata and producer callbacks run before the final structural policy check.
      validation.run(invocation, () -> structure.validateFrozenRelease(invocation, List.of(contribution), validation));
      validation.require(invocation);
    } finally {
      outer.attempt().requireActive();
    }
  }

  private boolean isPage(UiLayoutTarget root) {
    return UiLayoutPageRevisionReproduction.TYPE.equals(root.componentType());
  }

  private UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_RELEASE,
        "The page/group publication dependencies are not satisfied.");
  }
}
