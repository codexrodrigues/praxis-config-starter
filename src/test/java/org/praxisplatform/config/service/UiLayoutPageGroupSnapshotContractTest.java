package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;

/** Exercises the real integrity owner and frozen resolver, without a database or test-only API. */
@Tag("unit")
class UiLayoutPageGroupSnapshotContractTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString());
  private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("author", "tenant", "unit", "test", "context",
      new UiLayoutCompositionRegistration(root, List.of(root)));
  private final PageCreationContext context = new PageCreationContext("application", "/pages", invocation);
  private final UUID releaseId = UUID.randomUUID(), draftId = UUID.randomUUID();
  private final PageCreationHost host = mock(PageCreationHost.class);
  private final UiLayoutPageGroupHeadStore store = mock(UiLayoutPageGroupHeadStore.class,
      withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  private final UiLayoutLifecycleAdmission admission = mock(UiLayoutLifecycleAdmission.class);
  private final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);
  private final UiLayoutMetadataCapturePersistence metadata = mock(UiLayoutMetadataCapturePersistence.class);
  private final UiLayoutBaselineMetadataAdmission metadataAdmission = mock(UiLayoutBaselineMetadataAdmission.class);
  private final UiLayoutDraftWorkspaceSource workspaceSource = mock(UiLayoutDraftWorkspaceSource.class);
  private final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  private final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  private final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
  private final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
  private final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
  private final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
  private UiLayoutPageGroupHeadIntegrity owner;

  @BeforeEach
  void setUp() {
    when(host.resolveLifecycle(invocation)).thenReturn(context);
    when(host.navigationGroupsRoot(context)).thenReturn(root);
    owner = new UiLayoutPageGroupHeadIntegrity(host, store, admission, structure, metadata,
        metadataAdmission, workspaceSource, releases, drafts, members, revisions, definitions,
        assignments, mapper, hashes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown.editor", "praxis.table.editor", UiLayoutPageRevisionReproduction.KIND})
  void foreignDescriptorCannotBypassTheGroupsContract(String foreignKind) {
    persistSnapshot(foreignKind, groupsDocument());
    assertRejectedBeforeLocks();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "duplicate"})
  void foreignDescriptorCannotMakeMalformedGroupsLookLikeAnEmptyOrUniqueSet(String corruption) {
    var document = groupsDocument();
    if (corruption.equals("missing")) {
      ((ObjectNode) document.get("config")).remove("groups");
    } else {
      var groups = (com.fasterxml.jackson.databind.node.ArrayNode) document.at("/config/groups");
      groups.add(groups.get(0).deepCopy());
    }
    persistSnapshot("unknown.editor", document);
    assertRejectedBeforeLocks();
  }

  @Test
  void correlatedNativeGroupsReachTheLockBoundary() {
    persistSnapshot(UiLayoutNavigationGroupsRevisionReproduction.KIND, groupsDocument());
    var reachedLock = new IllegalStateException("Test reached physical root lock");
    doThrow(reachedLock).when(store).lockRoot(invocation);
    assertThatThrownBy(this::require).isSameAs(reachedLock);
    verify(store).lockGroup(eq(context), any(UUID.class));
    verify(store).lockRoot(invocation);
    verifyNoInteractions(metadata, metadataAdmission, structure);
  }

  private void assertRejectedBeforeLocks() {
    assertThatThrownBy(this::require).isInstanceOf(UiLayoutLifecycleException.class)
        .hasMessage("The page/group publication dependencies are not satisfied.")
        .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    verify(store, never()).lockGroup(any(), any());
    verify(store, never()).lockRoot(any());
    verifyNoInteractions(metadata, metadataAdmission, structure, admission);
  }

  private void require() {
    try (var attempt = UiLayoutValidationAttempt.start(invocation,
        UiLayoutLifecycleOperation.PUBLISH_RELEASE, Duration.ofMinutes(1))) {
      owner.require(invocation, UiLayoutLifecycleOperation.PUBLISH_RELEASE, releaseId,
          new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.FROZEN_RELEASE));
    }
  }

  private ObjectNode groupsDocument() {
    var document = mapper.createObjectNode().put("kind", UiLayoutNavigationGroupsRevisionReproduction.KIND).put("version", 1);
    document.putObject("config").putArray("groups").addObject()
        .put("id", "11111111-1111-4111-8111-111111111111").put("label", "Operations").put("icon", "home").put("order", 0);
    return document;
  }

  private void persistSnapshot(String documentType, ObjectNode document) {
    UUID definitionId = UUID.randomUUID(), revisionId = UUID.randomUUID(), assignmentId = UUID.randomUUID();
    ObjectNode patch = ((ObjectNode) document.get("config")).deepCopy();
    String patchHash = hashes.sha256Exact(patch);
    String documentHash = hashes.sha256Exact(document);
    var selector = new UiLayoutAudienceSelectorCommand("tenant", null, null, null, null, null);
    var state = new UiLayoutDraftWorkspaceDocument.TargetState(root,
        new UiLayoutAuthoringDocumentDescriptor(documentType, "fixture:groups", "1"),
        new UiLayoutPatchDocumentDescriptor("fixture:groups-patch", "praxis.ui-layout/v1"),
        new UiLayoutDraftWorkspaceDocument.BaselineDocument("fixture:groups-source", documentHash, document),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(documentHash, document),
        new UiLayoutDraftWorkspaceDocument.RevisionSelection(revisionId, 1, patchHash, "praxis.ui-layout/v1", UUID.randomUUID()),
        new UiLayoutDraftWorkspaceDocument.AssignmentSelection(assignmentId, revisionId, "TENANT", selector,
            (short) 0, "groups", UUID.randomUUID()));
    var workspace = new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
        List.of(state), UUID.randomUUID());
    var release = UiLayoutRelease.builder().id(releaseId).tenantId("tenant").environment("test")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).sourceDraftId(draftId).build();
    var draft = UiLayoutDraft.builder().id(draftId).tenantId("tenant").environment("test")
        .rootComponentType(root.componentType()).rootComponentId(root.componentId()).state("RELEASED")
        .draftEtag(UUID.randomUUID()).draftDocument(new UiLayoutDraftWorkspaceCodec(mapper, hashes).encode(workspace)).build();
    when(releases.findByIdAndTenantIdAndEnvironment(releaseId, "tenant", "test")).thenReturn(Optional.of(release));
    when(releases.findBySourceDraftId(draftId)).thenReturn(Optional.of(release));
    when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(draftId, "tenant", "test",
        root.componentType(), root.componentId())).thenReturn(Optional.of(draft));
    when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(List.of(
        UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(releaseId).memberOrder(0)
            .componentType(root.componentType()).componentId(root.componentId()).contentRevisionId(revisionId)
            .assignmentRevisionId(assignmentId).contributionKey("groups").build()));
    when(definitions.findById(definitionId)).thenReturn(Optional.of(UiLayoutDefinition.builder().id(definitionId)
        .tenantId("tenant").environment("test").componentType(root.componentType()).componentId(root.componentId())
        .schemaVersion("praxis.ui-layout/v1").build()));
    when(revisions.findById(revisionId)).thenReturn(Optional.of(UiLayoutRevision.builder().id(revisionId)
        .definitionId(definitionId).revisionNumber(1L).contentHash(patchHash).schemaVersion("praxis.ui-layout/v1")
        .patchDocument(patch.toString()).build()));
    when(assignments.findById(assignmentId)).thenReturn(Optional.of(UiLayoutAssignmentRevision.builder().id(assignmentId)
        .tenantId("tenant").environment("test").revisionId(revisionId).layerClass("TENANT")
        .selectorDocument(mapper.valueToTree(selector).toString()).priority((short) 0).contributionKey("groups").build()));
  }
}
