package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** Status decision payload; actor identity is always resolved by the server. */
@Schema(description = "Definition lifecycle decision whose actor and governed scope are resolved from server authentication.")
public record DomainRuleDefinitionStatusTransitionRequest(
    @Schema(description = "Canonical target lifecycle status. Draft/proposed authoring requires RULE_DEFINITION_AUTHOR; other decisions require RULE_DEFINITION_APPROVER.", requiredMode = Schema.RequiredMode.REQUIRED)
    String status,
    @Schema(description = "Optional safe validation evidence produced by the governed review; never carries actor identity.")
    JsonNode validationResult
) {}
