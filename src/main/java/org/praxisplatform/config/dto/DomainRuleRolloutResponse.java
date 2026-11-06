package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Safe rollout identity and lifecycle projection. */
public record DomainRuleRolloutResponse(
    UUID rolloutId, String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Operation bound to the immutable candidate and expected head for this rollout.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey, String candidateSnapshotKey, String candidateContentHash,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Last selected snapshot bound to the expected head, including a withdrawn head for audit and concurrency. This identifier does not grant execution authority. Null with expectedHeadEtag only for verified head absence.", nullable = true)
    String expectedActiveSnapshotKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Expected head concurrency token; null with expectedActiveSnapshotKey for bootstrap absence.", nullable = true)
    String expectedHeadEtag, String policyKey, int policyVersion,
    String enforcementMode, String status, Instant createdAtUtc, Instant expiresAtUtc) {}
