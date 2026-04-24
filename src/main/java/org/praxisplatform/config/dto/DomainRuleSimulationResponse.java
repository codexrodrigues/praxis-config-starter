package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleSimulationResponse(
        UUID simulationId,
        UUID ruleDefinitionId,
        String tenantId,
        String environment,
        String ruleKey,
        Integer ruleVersion,
        String ruleType,
        String contextKey,
        String resourceKey,
        String serviceKey,
        String result,
        JsonNode grounding,
        JsonNode existingCoverage,
        JsonNode predictedMaterializations,
        JsonNode requiredApprovals,
        JsonNode warnings,
        JsonNode explainability,
        Instant simulatedAt
) {
}
