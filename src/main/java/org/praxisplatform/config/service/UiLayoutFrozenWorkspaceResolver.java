package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;

/** Internal storage correlation shared by authorized historical reads and frozen head mutations.
 * Never resolves current head, runs a producer or grants access. Callers supply their own current gate.
 */
final class UiLayoutFrozenWorkspaceResolver {
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutDraftWorkspaceCodec codec;
  private final UiLayoutWorkspaceSelectionVerifier selections;

  UiLayoutFrozenWorkspaceResolver(UiLayoutReleaseRepository releases, UiLayoutDraftRepository drafts,
      UiLayoutReleaseMemberRepository members, UiLayoutDefinitionRepository definitions,
      UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments,
      ObjectMapper mapper, CanonicalJsonHashService hashes) {
    this.releases = releases; this.drafts = drafts; this.members = members;
    this.codec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
    this.selections = new UiLayoutWorkspaceSelectionVerifier(definitions, revisions, assignments, mapper, hashes);
  }

  Snapshot resolve(UiLayoutLifecycleInvocation invocation, UUID releaseId, Consumer<UiLayoutTarget> targetAdmission) {
    UiLayoutTarget root = invocation.rootTarget();
    try {
      UiLayoutRelease release = releases.findByIdAndTenantIdAndEnvironment(releaseId,
          invocation.tenantId(), invocation.environment()).orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND));
      if (!releaseId.equals(release.getId()) || !sameScope(invocation, release.getTenantId(), release.getEnvironment(),
          release.getRootComponentType(), release.getRootComponentId())) throw failure(UiLayoutLifecycleException.Code.NOT_FOUND);
      if (release.getSourceDraftId() == null) throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      UiLayoutDraft draft = drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
          release.getSourceDraftId(), invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId())
          .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
      if (!release.getSourceDraftId().equals(draft.getId()) || !sameScope(invocation, draft.getTenantId(),
          draft.getEnvironment(), draft.getRootComponentType(), draft.getRootComponentId())
          || !"RELEASED".equals(draft.getState()) || draft.getDraftEtag() == null) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      UiLayoutRelease sourceRelease = releases.findBySourceDraftId(draft.getId())
          .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
      if (!releaseId.equals(sourceRelease.getId()) || !draft.getId().equals(sourceRelease.getSourceDraftId())
          || !sameScope(invocation, sourceRelease.getTenantId(), sourceRelease.getEnvironment(),
              sourceRelease.getRootComponentType(), sourceRelease.getRootComponentId())) {
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      }
      if (draft.getDraftDocument() == null || "{}".equals(draft.getDraftDocument().trim())) {
        throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      }
      UiLayoutDraftWorkspaceDocument document = codec.decodeHistorical(draft.getDraftDocument());
      if (document.freezeCommandRef() == null || !root.equals(document.targets().getFirst().target())) {
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      }
      List<com.fasterxml.jackson.databind.JsonNode> patches = new ArrayList<>();
      for (var state : document.targets()) {
        if (state.selectedRevision() == null || state.selectedAssignment() == null) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        targetAdmission.accept(state.target());
      }
      List<UiLayoutReleaseMember> pinned = members.findByReleaseIdOrderByMemberOrder(releaseId);
      if (pinned == null || pinned.size() != document.targets().size()) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      for (int i = 0; i < pinned.size(); i++) {
        var state = document.targets().get(i);
        var member = pinned.get(i);
        if (member == null || !releaseId.equals(member.getReleaseId()) || member.getMemberOrder() != i
            || !Objects.equals(member.getComponentType(), state.target().componentType())
            || !Objects.equals(member.getComponentId(), state.target().componentId())
            || !Objects.equals(member.getContentRevisionId(), state.selectedRevision().revisionRef())
            || !Objects.equals(member.getAssignmentRevisionId(), state.selectedAssignment().assignmentRevisionRef())
            || !Objects.equals(member.getContributionKey(), state.selectedAssignment().contributionKey())) {
          throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        }
        var patch = selections.verifyHistorical(invocation, state);
        patches.add(patch);
      }
      return new Snapshot(releaseId, draft.getId(), draft.getDraftEtag(), document, List.copyOf(patches));
    } catch (UiLayoutLifecycleException known) { throw failure(known.getCode()); }
    catch (RuntimeException unavailable) { throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE); }
  }

  private boolean sameScope(UiLayoutLifecycleInvocation invocation, String tenant, String environment, String type, String id) {
    return invocation.tenantId().equals(tenant) && invocation.environment().equals(environment)
        && invocation.rootTarget().componentType().equals(type) && invocation.rootTarget().componentId().equals(id);
  }
  private UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Frozen native workspace is unavailable.");
  }
  record Snapshot(UUID releaseId, UUID draftId, UUID draftEtag, UiLayoutDraftWorkspaceDocument document, List<JsonNode> patches) {}
}
