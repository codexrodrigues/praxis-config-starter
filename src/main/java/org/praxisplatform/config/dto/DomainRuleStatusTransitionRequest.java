package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainRuleStatusTransitionRequest(
        String status,
        String decidedByType,
        String decidedBy,
        JsonNode validationResult
) {
}
