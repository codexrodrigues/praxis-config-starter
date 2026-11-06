package org.praxisplatform.config.dto;

import java.util.List;

/** Server-scoped catalog used by human governance workstations after reload. */
public record DomainRuleRolloutCatalogResponse(
    String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Operation whose pending rollouts and activation actions are listed; the family rollout policy governs all its operations.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey,
    List<DomainRuleRolloutCatalogItemResponse> rollouts,
    List<String> availableActions) {}
