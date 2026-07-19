package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public record DomainRuleStatusTransitionRequest(
        @Schema(description = "Canonical target lifecycle status for the materialization.")
        String status,
        @Schema(description = "Machine validation evidence associated with the transition. Actor identity and scope are resolved exclusively from server authentication.")
        JsonNode validationResult
) {
}
