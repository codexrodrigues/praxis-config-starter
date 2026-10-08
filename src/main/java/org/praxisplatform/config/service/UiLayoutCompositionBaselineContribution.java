package org.praxisplatform.config.service;

/** One baseline or mutable overlay contribution with its stable logical contribution key. */
public record UiLayoutCompositionBaselineContribution(String contributionKey, UiLayoutResolutionCandidate candidate) {
  public UiLayoutCompositionBaselineContribution {
    if (contributionKey == null || contributionKey.isBlank()) throw new IllegalArgumentException("contributionKey is required.");
    if (candidate == null) throw new IllegalArgumentException("candidate is required.");
    candidate = new UiLayoutResolutionCandidate(candidate.target(), candidate.revisionRef(), candidate.contentHash(),
        candidate.layerClass(), candidate.priority(), candidate.selector(), candidate.patch(), candidate.safeLabel());
  }
}
