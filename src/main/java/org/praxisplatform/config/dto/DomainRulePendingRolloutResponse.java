package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Minimal server-scoped candidate identity exposed to authenticated preload workers. */
public record DomainRulePendingRolloutResponse(
    UUID rolloutId,
    String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Operation of this candidate and its expected active head; the preload must not be used for another operation.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey,
    String candidateSnapshotKey,
    String candidateContentHash,
    String status,
    Instant expiresAtUtc) {}
