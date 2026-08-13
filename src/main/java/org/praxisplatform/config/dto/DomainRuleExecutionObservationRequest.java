package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** One redacted, idempotent host observation of an exact governed snapshot evaluation. */
public record DomainRuleExecutionObservationRequest(
    UUID observationId,
    String snapshotKey,
    String snapshotContentHash,
    long activationRevision,
    String outcome,
    long durationMicros,
    Instant observedAtUtc) {}
