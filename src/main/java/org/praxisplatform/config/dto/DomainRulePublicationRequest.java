package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record DomainRulePublicationRequest(
        @Schema(description = "Immutable identifier of the approved or already active rule definition to publish.")
        UUID ruleDefinitionId,
        @Schema(description = "Optional ordered selection of existing materializations. When absent, the service resolves eligible derived targets deterministically.")
        List<UUID> materializationIds,
        @Schema(description = "Whether eligible derived materializations may be created or applied as part of publication. Defaults to true.")
        Boolean applyEligibleMaterializations,
        @Schema(description = "Safe operational notes about this publication request. Governed actor identity is resolved exclusively from server authentication.")
        JsonNode publicationNotes
) {
}
