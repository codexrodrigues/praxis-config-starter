package org.praxisplatform.config.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Request-local Config-owned combination of a fixed corporate release and current host overlays. */
final class PinnedUiLayoutCandidateSource implements UiLayoutCandidateSource {
  private final Map<UiLayoutTarget, List<UiLayoutResolutionCandidate>> effective;
  private final UiLayoutCandidateSource policySource;

  PinnedUiLayoutCandidateSource(UiLayoutCompositionCandidateSnapshot baseline, UiLayoutCompositionReleaseSnapshot release) {
    this.policySource = baseline.policySource();
    java.util.Map<UiLayoutTarget, Set<String>> releasedKeys = new java.util.HashMap<>();
    for (UiLayoutCompositionContribution contribution : release.contributions()) {
      releasedKeys.computeIfAbsent(contribution.target(), ignored -> new HashSet<>()).add(contribution.contributionKey());
    }
    java.util.Map<UiLayoutTarget, List<UiLayoutResolutionCandidate>> values = new java.util.HashMap<>();
    for (Map.Entry<UiLayoutTarget, List<UiLayoutCompositionBaselineContribution>> entry : baseline.candidates().entrySet()) {
      Set<String> seen = new HashSet<>();
      List<UiLayoutResolutionCandidate> candidates = new ArrayList<>();
      for (UiLayoutCompositionBaselineContribution contribution : entry.getValue()) {
        if (!seen.add(contribution.contributionKey())) throw duplicate(entry.getKey());
        if (!releasedKeys.getOrDefault(entry.getKey(), Set.of()).contains(contribution.contributionKey())) candidates.add(contribution.candidate());
      }
      values.put(entry.getKey(), candidates);
    }
    for (UiLayoutCompositionContribution contribution : release.contributions()) {
      values.computeIfAbsent(contribution.target(), ignored -> new ArrayList<>()).add(contribution.candidate());
    }
    this.effective = Map.copyOf(values);
  }

  @Override public List<UiLayoutResolutionCandidate> findActive(String tenant, String environment, UiLayoutTarget target) {
    return List.copyOf(effective.getOrDefault(target, List.of()));
  }
  @Override public Set<String> authorablePaths(UiLayoutTarget target) { return policySource.authorablePaths(target); }
  @Override public Set<String> removablePaths(UiLayoutTarget target) { return policySource.removablePaths(target); }
  @Override public void validatePatch(UiLayoutTarget target, com.fasterxml.jackson.databind.JsonNode patch) { policySource.validatePatch(target, patch); }
  private UiLayoutResolutionException duplicate(UiLayoutTarget target) {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY,
        "Baseline contains duplicate contribution keys for one target.", null);
  }
}
