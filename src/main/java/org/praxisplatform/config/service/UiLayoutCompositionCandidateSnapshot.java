package org.praxisplatform.config.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Host-supplied permitted candidates and presentation policy for one request, before release substitution. */
public record UiLayoutCompositionCandidateSnapshot(
    Map<UiLayoutTarget, List<UiLayoutCompositionBaselineContribution>> candidates,
    Set<UiLayoutTarget> authorizedTargets,
    UiLayoutCandidateSource policySource) {
  public UiLayoutCompositionCandidateSnapshot {
    if (candidates == null) candidates = Map.of();
    java.util.Map<UiLayoutTarget, List<UiLayoutCompositionBaselineContribution>> copy = new java.util.HashMap<>();
    candidates.forEach((target, values) -> copy.put(target, values == null ? List.of() : List.copyOf(values)));
    candidates = Map.copyOf(copy);
    authorizedTargets = authorizedTargets == null ? Set.of() : Set.copyOf(authorizedTargets);
    if (policySource == null) throw new IllegalArgumentException("policySource is required.");
  }
}
