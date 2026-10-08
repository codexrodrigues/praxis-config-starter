package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

@Tag("unit")
class JpaUiLayoutCompositionReleaseReaderTest {
  private static final String TENANT = "tenant-a";
  private static final String ENVIRONMENT = "lab";
  private static final UiLayoutTarget ROOT = new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void absentHeadReturnsNoActiveReleaseWithoutLookingUpAnyOtherScope() {
    Fixtures fixtures = fixtures();
    when(fixtures.heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(TENANT, ENVIRONMENT,
        ROOT.componentType(), ROOT.componentId())).thenReturn(Optional.empty());

    assertThat(fixtures.reader.read(TENANT, ENVIRONMENT, ROOT).releaseRef()).isNull();

    verify(fixtures.heads).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(TENANT, ENVIRONMENT,
        ROOT.componentType(), ROOT.componentId());
  }

  @Test
  void activeReleaseRejectsEmptyMembersAndNeverFallsBackToMutableOverlays() {
    Fixtures fixtures = activeFixtures();
    when(fixtures.members.findByReleaseIdOrderByMemberOrder(fixtures.release.getId())).thenReturn(List.of());

    assertIntegrityFailure(() -> fixtures.reader.read(TENANT, ENVIRONMENT, ROOT));
  }

  @Test
  void activeReleaseRejectsMissingRootAndNonContiguousMemberOrder() {
    Fixtures fixtures = activeFixtures();
    UiLayoutReleaseMember childOnly = member(1, new UiLayoutTarget("praxis-table", "procurement-lines"), fixtures.assignment.getId(), fixtures.revision.getId());
    when(fixtures.members.findByReleaseIdOrderByMemberOrder(fixtures.release.getId())).thenReturn(List.of(childOnly));

    assertIntegrityFailure(() -> fixtures.reader.read(TENANT, ENVIRONMENT, ROOT));
  }

  @Test
  void activeReleaseRejectsUnknownSelectorFieldsAndCrossTenantDefinitions() {
    Fixtures selectorFixtures = activeFixtures();
    selectorFixtures.assignment.setSelectorDocument("{\"tenant\":\"tenant-a\",\"unknown\":\"x\"}");
    assertIntegrityFailure(() -> selectorFixtures.reader.read(TENANT, ENVIRONMENT, ROOT));

    Fixtures tenantFixtures = activeFixtures();
    tenantFixtures.definition.setTenantId("tenant-b");
    assertIntegrityFailure(() -> tenantFixtures.reader.read(TENANT, ENVIRONMENT, ROOT));
  }

  @Test
  void activeReleaseRejectsAContentHashThatDoesNotMatchThePinnedPatch() {
    Fixtures fixtures = activeFixtures();
    fixtures.revision.setContentHash("not-the-canonical-patch-hash");

    assertIntegrityFailure(() -> fixtures.reader.read(TENANT, ENVIRONMENT, ROOT));
  }

  @Test
  void validReleaseReturnsOnlyItsPinnedContribution() {
    Fixtures fixtures = activeFixtures();

    var snapshot = fixtures.reader.read(TENANT, ENVIRONMENT, ROOT);

    assertThat(snapshot.releaseRef()).isEqualTo(fixtures.release.getId().toString());
    assertThat(snapshot.contributions()).singleElement().satisfies(value -> {
      assertThat(value.target()).isEqualTo(ROOT);
      assertThat(value.contributionKey()).isEqualTo("root-title");
      assertThat(value.candidate().patch()).isEqualTo(mapper.valueToTree(java.util.Map.of("title", "Pinned")));
    });
  }

  private void assertIntegrityFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(UiLayoutResolutionException.class)
        .extracting(error -> ((UiLayoutResolutionException) error).code())
        .isEqualTo(UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY);
  }

  private Fixtures activeFixtures() {
    Fixtures fixtures = fixtures();
    UiLayoutReleaseHead head = UiLayoutReleaseHead.builder().id(UUID.randomUUID()).tenantId(TENANT).environment(ENVIRONMENT)
        .rootComponentType(ROOT.componentType()).rootComponentId(ROOT.componentId()).activeReleaseId(fixtures.release.getId())
        .headEtag(UUID.randomUUID()).updatedAt(Instant.EPOCH).rowVersion(0L).build();
    when(fixtures.heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(TENANT, ENVIRONMENT,
        ROOT.componentType(), ROOT.componentId())).thenReturn(Optional.of(head));
    when(fixtures.releases.findByIdAndTenantIdAndEnvironment(fixtures.release.getId(), TENANT, ENVIRONMENT)).thenReturn(Optional.of(fixtures.release));
    when(fixtures.members.findByReleaseIdOrderByMemberOrder(fixtures.release.getId()))
        .thenReturn(List.of(member(0, ROOT, fixtures.assignment.getId(), fixtures.revision.getId())));
    when(fixtures.assignments.findById(fixtures.assignment.getId())).thenReturn(Optional.of(fixtures.assignment));
    when(fixtures.revisions.findById(fixtures.revision.getId())).thenReturn(Optional.of(fixtures.revision));
    when(fixtures.definitions.findById(fixtures.definition.getId())).thenReturn(Optional.of(fixtures.definition));
    return fixtures;
  }

  private Fixtures fixtures() {
    UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
    UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
    UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
    UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
    UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
    UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
    UUID definitionId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UiLayoutDefinition definition = UiLayoutDefinition.builder().id(definitionId).tenantId(TENANT).environment(ENVIRONMENT)
        .componentType(ROOT.componentType()).componentId(ROOT.componentId()).schemaVersion("v1").createdBy("tester").createdAt(Instant.EPOCH).build();
    UiLayoutRevision revision = UiLayoutRevision.builder().id(revisionId).definitionId(definitionId).revisionNumber(1L)
        .patchDocument("{\"title\":\"Pinned\"}").contentHash(new CanonicalJsonHashService(mapper).sha256Exact(java.util.Map.of("title", "Pinned")))
        .schemaVersion("v1").createdBy("tester").createdReason("test").createdAt(Instant.EPOCH).build();
    UiLayoutAssignmentRevision assignment = UiLayoutAssignmentRevision.builder().id(UUID.randomUUID()).tenantId(TENANT).environment(ENVIRONMENT)
        .revisionId(revisionId).layerClass("TENANT").selectorDocument("{\"tenant\":\"tenant-a\"}").priority((short) 0)
        .contributionKey("root-title").createdBy("tester").createdAt(Instant.EPOCH).build();
    UiLayoutRelease release = UiLayoutRelease.builder().id(UUID.randomUUID()).tenantId(TENANT).environment(ENVIRONMENT)
        .rootComponentType(ROOT.componentType()).rootComponentId(ROOT.componentId()).createdBy("tester").createdAt(Instant.EPOCH).build();
    JpaUiLayoutCompositionReleaseReader reader = new JpaUiLayoutCompositionReleaseReader(heads, releases, members, assignments,
        revisions, definitions, mapper, new CanonicalJsonHashService(mapper));
    return new Fixtures(reader, heads, releases, members, assignments, revisions, definitions, release, assignment, revision, definition);
  }

  private UiLayoutReleaseMember member(int order, UiLayoutTarget target, UUID assignmentId, UUID revisionId) {
    return UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(UUID.randomUUID()).memberOrder(order)
        .componentType(target.componentType()).componentId(target.componentId()).assignmentRevisionId(assignmentId)
        .contentRevisionId(revisionId).contributionKey("root-title").build();
  }

  private record Fixtures(JpaUiLayoutCompositionReleaseReader reader, UiLayoutReleaseHeadRepository heads,
      UiLayoutReleaseRepository releases, UiLayoutReleaseMemberRepository members, UiLayoutAssignmentRevisionRepository assignments,
      UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions, UiLayoutRelease release,
      UiLayoutAssignmentRevision assignment, UiLayoutRevision revision, UiLayoutDefinition definition) {}
}
