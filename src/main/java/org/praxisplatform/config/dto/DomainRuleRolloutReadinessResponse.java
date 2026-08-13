package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

/** Safe aggregate candidate-preload readiness; host identities are intentionally absent. */
public record DomainRuleRolloutReadinessResponse(
    UUID rolloutId, String ruleSetKey, String candidateSnapshotKey, String status,
    String enforcementMode, int minimumFreshProbes, double minimumReadyRatio,
    long totalProbes, long readyProbes, long incompatibleProbes, long unavailableProbes,
    long staleProbes, boolean activationReady, Instant staleBeforeUtc) {}
