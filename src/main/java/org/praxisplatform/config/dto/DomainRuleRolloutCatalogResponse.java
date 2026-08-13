package org.praxisplatform.config.dto;

import java.util.List;

/** Server-scoped catalog used by human governance workstations after reload. */
public record DomainRuleRolloutCatalogResponse(
    String ruleSetKey,
    List<DomainRuleRolloutCatalogItemResponse> rollouts) {}
