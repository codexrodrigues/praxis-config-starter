package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Minimal server-scoped candidate identity exposed to authenticated preload workers. */
public record DomainRulePendingRolloutResponse(
    UUID rolloutId,
    String ruleSetKey,
    String candidateSnapshotKey,
    String candidateContentHash,
    String status,
    Instant expiresAtUtc) {}
