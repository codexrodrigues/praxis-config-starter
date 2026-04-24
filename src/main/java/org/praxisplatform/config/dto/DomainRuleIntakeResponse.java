package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleIntakeResponse(
        UUID intakeId,
        String tenantId,
        String environment,
        String ruleKey,
        String ruleType,
        String contextKey,
        String resourceKey,
        String serviceKey,
        String status,
        JsonNode grounding,
        DomainRuleDefinitionResponse definition,
        Instant createdAt
) {
}
