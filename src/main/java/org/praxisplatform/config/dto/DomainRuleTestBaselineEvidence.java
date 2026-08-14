package org.praxisplatform.config.dto;

import java.time.Instant;

/** Safe provenance for the authority used to establish scenario expectations. */
public record DomainRuleTestBaselineEvidence(
    String authorityType,
    String artifactRef,
    String artifactDigest,
    Instant observedAtUtc,
    String eligibility) {}
