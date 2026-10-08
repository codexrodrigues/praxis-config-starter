package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Immutable persisted corporate contribution pinned by an aggregate release. */
public record UiLayoutCompositionContribution(
    int memberOrder,
    UiLayoutTarget target,
    String contributionKey,
    String assignmentRevisionRef,
    String contentRevisionRef,
    String contentHash,
    UiLayoutResolutionCandidate candidate) {}
