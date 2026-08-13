package org.praxisplatform.config.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Safe governed projection of one immutable rollout-policy version and its lifecycle. */
public record DomainRuleRolloutPolicyResponse(
    UUID policyId,
    String ruleSetKey,
    String policyKey,
    int policyVersion,
    String status,
    String enforcementMode,
    int minimumFreshProbes,
    BigDecimal minimumReadyRatio,
    boolean blockOnIncompatible,
    long staleAfterSeconds,
    Long maximumRolloutAgeSeconds,
    String createdBy,
    Instant createdAt,
    String approvedBy,
    Instant approvedAt,
    String activatedBy,
    Instant activatedAt) {}
