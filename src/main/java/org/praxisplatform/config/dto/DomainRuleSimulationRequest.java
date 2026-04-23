package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record DomainRuleSimulationRequest(
        UUID ruleDefinitionId,
        String ruleKey,
        String ruleType,
        String contextKey,
        String resourceKey,
        String serviceKey,
        JsonNode definition,
        JsonNode parameters,
        JsonNode condition,
        JsonNode governance
) {
}
