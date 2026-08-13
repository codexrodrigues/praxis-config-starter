package org.praxisplatform.config.dto;

import java.util.List;

/** Human-facing rollout projection with server-derived readiness and lifecycle actions. */
public record DomainRuleRolloutCatalogItemResponse(
    DomainRuleRolloutResponse rollout,
    DomainRuleRolloutReadinessResponse readiness,
    boolean expectedHeadCurrent,
    boolean expired,
    List<String> availableActions) {}
