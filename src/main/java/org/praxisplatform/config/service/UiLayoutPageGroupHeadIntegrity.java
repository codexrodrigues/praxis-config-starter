package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mandatory Config-owned cross-head invariant; neither a host permit SPI nor a second head. */
final class UiLayoutPageGroupHeadIntegrity {
  private final PageCreationHost host;
  private final UiLayoutPageGroupHeadStore store;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutLifecycleStructureValidator structure;
  private final UiLayoutMetadataCapturePersistence metadata;
  private final UiLayoutBaselineMetadataAdmission metadataAdmission;
  private final UiLayoutMetadataCaptureCodec metadataCodec;
  private final UiLayoutDraftWorkspaceSource workspaceSource;
  private final UiLayoutPageChildCaptures pageChildren;
  private final UiLayoutFrozenWorkspaceResolver frozen;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutDefinitionRepository definitions;
  private final UiLayoutAssignmentRevisionRepository assignments;

  UiLayoutPageGroupHeadIntegrity(PageCreationHost host, UiLayoutPageGroupHeadStore store,
      UiLayoutLifecycleAdmission admission, UiLayoutLifecycleStructureValidator structure,
      UiLayoutMetadataCapturePersistence metadata, UiLayoutBaselineMetadataAdmission metadataAdmission,
      UiLayoutDraftWorkspaceSource workspaceSource,
      UiLayoutReleaseRepository releases, UiLayoutDraftRepository drafts,
      UiLayoutReleaseMemberRepository members, UiLayoutRevisionRepository revisions,
      UiLayoutDefinitionRepository definitions, UiLayoutAssignmentRevisionRepository assignments,
      ObjectMapper mapper, CanonicalJsonHashService hashes) {
    this.host = Objects.requireNonNull(host);
    this.store = store;
    this.admission = admission;
    this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.metadata = metadata;
    this.metadataAdmission = metadataAdmission;
    this.workspaceSource = Objects.requireNonNull(workspaceSource);
    this.pageChildren = new UiLayoutPageChildCaptures(hashes);
    this.metadataCodec = new UiLayoutMetadataCaptureCodec(mapper, hashes);
    this.releases = releases;
    this.drafts = drafts;
    this.members = members;
    this.revisions = revisions;
    this.definitions = definitions;
    this.assignments = assignments;
    this.frozen = new UiLayoutFrozenWorkspaceResolver(releases, drafts, members, definitions,
        revisions, assignments, mapper, hashes);
  }

  static boolean governs(UiLayoutTarget root) {
    return isPage(root) || UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(root.componentType());
  }

  void require(UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation,
      UUID candidateRelease, UiLayoutValidationContext validation) {
    validation.require(invocation);
    store.flush();
    var context = context(invocation, validation);
    var groupsRoot = groupsRoot(context, validation);
    if (isPage(invocation.rootTarget())) store.requireCatalog(context);
    var previous = store.head(invocation, invocation.rootTarget());
    var previousGroups = store.head(invocation, groupsRoot);
    var oldIds = ids(invocation, active(previous), null, validation);
    var candidateIds = ids(invocation, candidateRelease, null, validation);
    var locks = new TreeSet<UUID>();
    locks.addAll(oldIds);
    locks.addAll(candidateIds);
    for (UUID groupId : locks) {
      validation.run(invocation, () -> store.lockGroup(context, groupId));
    }
    validation.run(invocation, () -> store.lockRoot(invocation));
    store.flush();
    store.lockAndRefreshHead(invocation);
    if (!Objects.equals(previous, store.head(invocation, invocation.rootTarget()))
        || !Objects.equals(previousGroups, store.head(invocation, groupsRoot))) store.conflict();
    requireSameContext(context, groupsRoot, validation);
    check(context, groupsRoot, candidateRelease, candidateIds, validation);
    requireAuthority(context, groupsRoot, operation, validation);

    // All canonical transitions retain their locks until actual transaction completion. A later
    // transition of this same root is legitimate; verify the final state, not the earlier candidate.
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void beforeCommit(boolean readOnly) {
        validation.require(invocation);
        store.flush();
        requireSameContext(context, groupsRoot, validation);
        var finalHead = store.head(invocation, invocation.rootTarget());
        UUID finalRelease = active(finalHead);
        check(context, groupsRoot, finalRelease, ids(invocation, finalRelease, null, validation), validation);
        requireAuthority(context, groupsRoot, operation, validation);
      }
    });
    validation.require(invocation);
  }

  private void check(PageCreationContext context, UiLayoutTarget groupsRoot, UUID candidateRelease,
      Set<UUID> candidateIds, UiLayoutValidationContext validation) {
    var invocation = context.invocation();
    if (isPage(invocation.rootTarget())) {
      store.requireCatalog(context);
      if (!candidateIds.isEmpty()) {
        var groupHead = store.head(invocation, groupsRoot);
        if (active(groupHead) == null) throw invalid();
        var groupInvocation = context.forTarget(groupsRoot);
        var snapshot = snapshot(groupInvocation, groupHead.activeReleaseId(), null, validation);
        var available = ids(snapshot);
        if (!available.containsAll(candidateIds)) throw invalid();
        admitFrozen(groupInvocation, snapshot, validation);
      }
      if (candidateRelease != null) {
        admitFrozen(invocation, snapshot(invocation, candidateRelease, null, validation), validation);
      }
      // Page metadata/structure may revoke the dependency grant. Recheck it after those callbacks.
      if (!candidateIds.isEmpty()) {
        var groupInvocation = context.forTarget(groupsRoot);
        validation.run(invocation, () -> admission.require(UiLayoutLifecycleOperation.READ_HEAD, groupInvocation));
      }
    } else {
      store.requireCompleteCatalog(context);
      if (candidateRelease != null) {
        admitFrozen(invocation, snapshot(invocation, candidateRelease, null, validation), validation);
      }
      UUID after = null;
      while (true) {
        validation.require(invocation);
        var pages = store.activePages(context, after);
        if (pages.isEmpty()) break;
        var previouslyManagedDefinitions = store.managedDefinitions();
        var batch = UiLayoutFrozenRecordBatch.load(pages.stream().map(UiLayoutPageGroupHeadStore.ActivePage::releaseId).toList(),
            releases, drafts, members, revisions, definitions, assignments);
        try {
          for (var page : pages) {
            // Internal integrity only: no caller visibility grant and no content leaves this owner.
            if (!candidateIds.containsAll(ids(context.forTarget(page.root()), page.releaseId(), batch, validation))) {
              throw invalid();
            }
          }
        } finally {
          store.detach(batch, previouslyManagedDefinitions);
        }
        after = pages.getLast().pageId();
        validation.require(invocation);
      }
    }
  }

  private Set<UUID> ids(UiLayoutLifecycleInvocation invocation, UUID release,
      UiLayoutFrozenRecordBatch batch, UiLayoutValidationContext outer) {
    return release == null ? Set.of() : ids(snapshot(invocation, release, batch, outer));
  }

  private Set<UUID> ids(UiLayoutFrozenWorkspaceResolver.Snapshot snapshot) {
    var state = snapshot.document().targets().getFirst();
    String collection = isPage(state.target()) ? "navigation" : "groups";
    String identifier = isPage(state.target()) ? "groupId" : "id";
    var result = new HashSet<UUID>();
    for (var item : state.working().document().path("config").path(collection)) {
      result.add(UUID.fromString(item.path(identifier).textValue()));
    }
    return Set.copyOf(result);
  }

  private UiLayoutFrozenWorkspaceResolver.Snapshot snapshot(UiLayoutLifecycleInvocation invocation,
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

  private void admitFrozen(UiLayoutLifecycleInvocation invocation,
      UiLayoutFrozenWorkspaceResolver.Snapshot snapshot, UiLayoutValidationContext outer) {
    outer.attempt().requireActive();
    try (var attempt = UiLayoutValidationAttempt.start(invocation, outer.attempt().operation(),
        outer.attempt().remainingBudget())) {
      var validation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.FROZEN_RELEASE);
      boolean dependency = !invocation.rootTarget().equals(outer.attempt().invocation().rootTarget());
      if (dependency) validation.run(invocation, () -> admission.require(UiLayoutLifecycleOperation.READ_HEAD, invocation));
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
      var capture = validation.call(invocation, () -> metadata.read(invocation, binding, access));
      metadataCodec.verifyAndRead(invocation, binding, capture, access);
      pageChildren.verify(invocation, snapshot.draftId(), state.target(), state.baseline().document(),
          state.working().document(), state.pageChildren(), workspaceSource, metadataAdmission, validation);
      // Current metadata and producer callbacks run before the final structural policy check.
      validation.run(invocation, () -> structure.validateFrozenRelease(invocation, List.of(contribution), validation));
      if (dependency) validation.run(invocation, () -> admission.require(UiLayoutLifecycleOperation.READ_HEAD, invocation));
      validation.require(invocation);
    } finally {
      outer.attempt().requireActive();
    }
  }

  private PageCreationContext context(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
    var current = validation.call(validation.attempt().invocation(), () -> host.resolveLifecycle(invocation));
    if (current == null || !invocation.equals(current.invocation())) throw stale();
    return current;
  }

  private UiLayoutTarget groupsRoot(PageCreationContext context, UiLayoutValidationContext validation) {
    var root = validation.call(context.invocation(), () -> host.navigationGroupsRoot(context));
    if (root == null || !UiLayoutNavigationGroupsRevisionReproduction.TYPE.equals(root.componentType())) throw stale();
    // The composition constructor also enforces the canonical UUID and a single registered root.
    var groupInvocation = context.forTarget(root);
    var inverse = context(groupInvocation, validation);
    if (!context.applicationId().equals(inverse.applicationId())
        || !context.routeNamespace().equals(inverse.routeNamespace())
        || (!isPage(context.invocation().rootTarget()) && !root.equals(context.invocation().rootTarget()))) throw stale();
    return root;
  }

  private void requireSameContext(PageCreationContext expected, UiLayoutTarget expectedGroupsRoot,
      UiLayoutValidationContext validation) {
    var current = context(expected.invocation(), validation);
    if (!expected.equals(current) || !expectedGroupsRoot.equals(groupsRoot(current, validation))) throw stale();
  }

  private void requireAuthority(PageCreationContext context, UiLayoutTarget groupsRoot,
      UiLayoutLifecycleOperation operation, UiLayoutValidationContext validation) {
    requireSameContext(context, groupsRoot, validation);
    validation.run(context.invocation(), () -> admission.require(operation, context.invocation()));
    requireSameContext(context, groupsRoot, validation);
    validation.require(context.invocation());
  }

  private static UUID active(UiLayoutPageGroupHeadStore.Head head) { return head == null ? null : head.activeReleaseId(); }
  private static boolean isPage(UiLayoutTarget root) { return UiLayoutPageRevisionReproduction.TYPE.equals(root.componentType()); }
  private UiLayoutLifecycleException invalid() { return new UiLayoutLifecycleException(
      UiLayoutLifecycleException.Code.INVALID_RELEASE, "The page/group publication dependencies are not satisfied."); }
  private UiLayoutLifecycleException stale() { return new UiLayoutLifecycleException(
      UiLayoutLifecycleException.Code.CONTEXT_STALE, "The registered page/group context is no longer current."); }
}
