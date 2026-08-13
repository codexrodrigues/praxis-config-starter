package org.praxisplatform.config.dto;

import java.math.BigDecimal;

/** Immutable authoring input for one versioned rollout-quorum policy. */
public record DomainRuleRolloutPolicyCreateRequest(
    String ruleSetKey,
    String policyKey,
    String enforcementMode,
    Integer minimumFreshProbes,
    BigDecimal minimumReadyRatio,
    Boolean blockOnIncompatible,
    Long staleAfterSeconds,
    Long maximumRolloutAgeSeconds) {}
