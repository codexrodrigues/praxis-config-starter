package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutCompositionReadServiceTest {
  private static final Principal PRINCIPAL = () -> "reader";
  private static final UiLayoutTarget ROOT = new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail");
  private static final UiLayoutTarget CHILD = new UiLayoutTarget("praxis-table", "procurement-lines");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void withdrawnHeadKeepsTheRegisteredRootAndChildOnPermittedBaseline() {
    UiLayoutCompositionReadService service = service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(),
        source(Map.of(ROOT, List.of(candidate(ROOT, "base-root", "Base root")), CHILD, List.of(candidate(CHILD, "base-child", "Base child")))));

    var receipt = service.resolve(PRINCIPAL, "ctx-1", ROOT);

    assertThat(receipt.releaseRef()).isNull();
    assertThat(receipt.members()).extracting(member -> member.target()).containsExactly(ROOT, CHILD);
    assertThat(receipt.members()).allSatisfy(member -> assertThat(member.effectiveLayout()).isNotNull());
    assertThat(receipt.members()).allSatisfy(member -> assertThat(member.appliedContributions()).isEmpty());
  }

  @Test
  void receiptPreservesTwoAppliedContributionsForOneTargetWithoutLeakingOtherTargets() {
    UiLayoutResolutionCandidate first = candidate(ROOT, "revision-a", "A");
    UiLayoutResolutionCandidate second = candidate(ROOT, "revision-b", "B", (short) 1);
    UiLayoutCompositionReleaseSnapshot release = new UiLayoutCompositionReleaseSnapshot("release-1", List.of(
        contribution(0, ROOT, "root-a", first), contribution(1, ROOT, "root-b", second),
        contribution(2, CHILD, "child", candidate(CHILD, "revision-c", "C"))));
    UiLayoutCompositionReadService service = service(release, source(Map.of()));

    var receipt = service.resolve(PRINCIPAL, "ctx-1", ROOT);

    assertThat(receipt.members()).hasSize(2);
    assertThat(receipt.members().getFirst().appliedContributions()).extracting(value -> value.contentRevisionRef())
        .containsExactly("revision-a", "revision-b");
    assertThat(receipt.members().get(1).appliedContributions()).extracting(value -> value.contentRevisionRef())
        .containsExactly("revision-c");
  }

  @Test
  void deniedChildFailsTheEntireAggregateInsteadOfReturningPartialReceipt() {
    UiLayoutCompositionCandidateSnapshotSource denied = (audience, registration) -> {
      throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED, "child denied", null);
    };
    UiLayoutCompositionReadService service = service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(), denied);

    assertThatThrownBy(() -> service.resolve(PRINCIPAL, "ctx-1", ROOT))
        .isInstanceOf(UiLayoutResolutionException.class)
        .extracting(error -> ((UiLayoutResolutionException) error).code())
        .isEqualTo(UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED);
  }

  @Test
  void aggregateEtagChangesWithEffectiveBaselineAndNotWithResolvedAt() {
    UiLayoutCompositionReadService first = service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(),
        source(Map.of(ROOT, List.of(candidate(ROOT, "base-root", "One")), CHILD, List.of(candidate(CHILD, "base-child", "Child")))));
    UiLayoutCompositionReadService second = service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(),
        source(Map.of(ROOT, List.of(candidate(ROOT, "base-root", "Two")), CHILD, List.of(candidate(CHILD, "base-child", "Child")))));

    String firstEtag = first.resolve(PRINCIPAL, "ctx-1", ROOT).receiptEtag();
    String changedEtag = second.resolve(PRINCIPAL, "ctx-1", ROOT).receiptEtag();

    assertThat(changedEtag).isNotEqualTo(firstEtag);
  }

  @Test
  void fixedReleaseContributionExcludesTheSameStableBaselineKeyExactlyOnce() {
    UiLayoutResolutionCandidate baseline = candidate(ROOT, "baseline-revision", "Baseline");
    UiLayoutResolutionCandidate pinned = candidate(ROOT, "pinned-revision", "Pinned");
    UiLayoutCompositionCandidateSnapshot snapshot = new UiLayoutCompositionCandidateSnapshot(
        Map.of(ROOT, List.of(new UiLayoutCompositionBaselineContribution("root-title", baseline))), Set.of(ROOT, CHILD), policy());
    UiLayoutCompositionReleaseSnapshot release = new UiLayoutCompositionReleaseSnapshot("release-1",
        List.of(contribution(0, ROOT, "root-title", pinned)));

    UiLayoutCandidateSource combined = new PinnedUiLayoutCandidateSource(snapshot, release);

    assertThat(combined.findActive("tenant-a", "lab", ROOT)).extracting(UiLayoutResolutionCandidate::revisionRef)
        .containsExactly("pinned-revision");
  }

  @Test
  void provenanceIncludesOnlyTheMatchingAssignmentWhenAssignmentsReuseOneContentRevision() {
    UiLayoutResolutionCandidate allowed = candidate(ROOT, "shared-revision", "Allowed");
    UiLayoutResolutionCandidate denied = new UiLayoutResolutionCandidate(ROOT, "shared-revision", allowed.contentHash(),
        UiLayoutResolutionCandidate.LayerClass.TENANT, (short) 1,
        new UiLayoutAudienceSelector("tenant-b", null, null, null, null, null), allowed.patch(), null);
    UiLayoutCompositionReleaseSnapshot release = new UiLayoutCompositionReleaseSnapshot("release-1", List.of(
        contribution(0, ROOT, "allowed", allowed), contribution(1, ROOT, "denied", denied)));

    var receipt = service(release, source(Map.of())).resolve(PRINCIPAL, "ctx-1", ROOT);

    assertThat(receipt.members().getFirst().appliedContributions()).extracting(value -> value.assignmentRevisionRef())
        .containsExactly("assignment-allowed");
  }

  @Test
  void omittedTargetGrantFailsEvenWhenTheRegisteredTargetHasNoOverlay() {
    UiLayoutCompositionCandidateSnapshotSource missingChildGrant = (audience, registration) ->
        new UiLayoutCompositionCandidateSnapshot(Map.of(), Set.of(ROOT), policy());

    assertThatThrownBy(() -> service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(), missingChildGrant)
        .resolve(PRINCIPAL, "ctx-1", ROOT))
        .isInstanceOf(UiLayoutResolutionException.class)
        .extracting(error -> ((UiLayoutResolutionException) error).code())
        .isEqualTo(UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED);
  }

  @Test
  void explicitlyGrantedTargetsMayHaveNoEffectiveOverlay() {
    var receipt = service(UiLayoutCompositionReleaseSnapshot.withoutActiveRelease(), source(Map.of()))
        .resolve(PRINCIPAL, "ctx-1", ROOT);

    assertThat(receipt.members()).hasSize(2).allSatisfy(member -> assertThat(member.effectiveLayout()).isNull());
  }

  private UiLayoutCompositionReadService service(UiLayoutCompositionReleaseSnapshot release, UiLayoutCompositionCandidateSnapshotSource snapshots) {
    EffectiveUiAudienceProvider audiences = (principal, expected) -> new EffectiveUiAudience("tenant-a", "lab", "user-a", null, null, null, Set.of(), "ctx-1");
    UiLayoutCompositionRegistry registry = (audience, root) -> new UiLayoutCompositionRegistration(root, List.of(ROOT, CHILD));
    UiLayoutCompositionReleaseReader releases = (tenant, environment, root) -> release;
    UiLayoutResolutionService engine = new UiLayoutResolutionService(mapper, new CanonicalJsonHashService(mapper), audiences,
        source(Map.of()).snapshot(null, null).policySource(), Clock.fixed(Instant.parse("2026-09-10T15:00:00Z"), ZoneOffset.UTC));
    return new UiLayoutCompositionReadService(audiences, registry, releases, snapshots, engine,
        new CanonicalJsonHashService(mapper), Clock.fixed(Instant.parse("2026-09-10T15:01:00Z"), ZoneOffset.UTC));
  }

  private UiLayoutCompositionCandidateSnapshotSource source(Map<UiLayoutTarget, List<UiLayoutResolutionCandidate>> candidates) {
    return (audience, registration) -> new UiLayoutCompositionCandidateSnapshot(candidates.entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream()
            .map(candidate -> new UiLayoutCompositionBaselineContribution("baseline-" + candidate.revisionRef(), candidate)).toList())), Set.of(ROOT, CHILD), policy());
  }

  private UiLayoutCandidateSource policy() {
    return new UiLayoutCandidateSource() {
      @Override public List<UiLayoutResolutionCandidate> findActive(String tenant, String environment, UiLayoutTarget target) { return List.of(); }
      @Override public Set<String> authorablePaths(UiLayoutTarget target) { return Set.of("/title"); }
      @Override public Set<String> removablePaths(UiLayoutTarget target) { return Set.of(); }
      @Override public void validatePatch(UiLayoutTarget target, com.fasterxml.jackson.databind.JsonNode patch) {}
    };
  }

  private UiLayoutResolutionCandidate candidate(UiLayoutTarget target, String revisionRef, String title) {
    return candidate(target, revisionRef, title, (short) 0);
  }

  private UiLayoutResolutionCandidate candidate(UiLayoutTarget target, String revisionRef, String title, short priority) {
    return new UiLayoutResolutionCandidate(target, revisionRef, new CanonicalJsonHashService(mapper).sha256Exact(Map.of("title", title)),
        UiLayoutResolutionCandidate.LayerClass.TENANT, priority,
        new UiLayoutAudienceSelector("tenant-a", null, null, null, null, null), mapper.valueToTree(Map.of("title", title)), null);
  }

  private UiLayoutCompositionContribution contribution(int order, UiLayoutTarget target, String key, UiLayoutResolutionCandidate candidate) {
    return new UiLayoutCompositionContribution(order, target, key, "assignment-" + key, candidate.revisionRef(), candidate.contentHash(), candidate);
  }
}
