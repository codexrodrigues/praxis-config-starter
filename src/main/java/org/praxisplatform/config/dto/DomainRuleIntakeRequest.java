package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainRuleIntakeRequest(
        String prompt,
        String assistantMessage,
        String ruleKey,
        String ruleType,
        String contextKey,
        String resourceKey,
        String serviceKey,
        JsonNode definition,
        JsonNode parameters,
        JsonNode condition,
        JsonNode governance,
        String createdByType,
        String createdBy
) {
}
