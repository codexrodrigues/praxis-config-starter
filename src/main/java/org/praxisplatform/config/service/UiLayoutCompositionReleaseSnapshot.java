package org.praxisplatform.config.service;

import java.util.List;

/** Release captured once for a composition read; null releaseRef denotes no active corporate release. */
public record UiLayoutCompositionReleaseSnapshot(String releaseRef, List<UiLayoutCompositionContribution> contributions) {
  public UiLayoutCompositionReleaseSnapshot {
    releaseRef = releaseRef == null || releaseRef.isBlank() ? null : releaseRef.trim();
    contributions = contributions == null ? List.of() : List.copyOf(contributions);
    if (releaseRef == null && !contributions.isEmpty()) {
      throw new IllegalArgumentException("Inactive composition snapshots cannot contain release contributions.");
    }
  }

  public static UiLayoutCompositionReleaseSnapshot withoutActiveRelease() {
    return new UiLayoutCompositionReleaseSnapshot(null, List.of());
  }
}
