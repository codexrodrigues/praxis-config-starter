package org.praxisplatform.config.dto;

import java.time.Instant;

/** Monotonic candidate-probe ingestion result. */
public record DomainRuleCandidateProbeResponse(boolean updated, Instant observedAtUtc) {}
