package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Safe rollout identity and lifecycle projection. */
public record DomainRuleRolloutResponse(
    UUID rolloutId, String ruleSetKey, String candidateSnapshotKey, String candidateContentHash,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Expected active snapshot, or null with expectedHeadEtag for verified head absence.", nullable = true)
    String expectedActiveSnapshotKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Expected head concurrency token; null with expectedActiveSnapshotKey for bootstrap absence.", nullable = true)
    String expectedHeadEtag, String policyKey, int policyVersion,
    String enforcementMode, String status, Instant createdAtUtc, Instant expiresAtUtc) {}
