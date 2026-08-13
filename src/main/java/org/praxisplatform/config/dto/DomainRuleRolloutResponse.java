package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Safe rollout identity and lifecycle projection. */
public record DomainRuleRolloutResponse(
    UUID rolloutId, String ruleSetKey, String candidateSnapshotKey, String candidateContentHash,
    String expectedActiveSnapshotKey, String expectedHeadEtag, String policyKey, int policyVersion,
    String enforcementMode, String status, Instant createdAtUtc, Instant expiresAtUtc) {}
