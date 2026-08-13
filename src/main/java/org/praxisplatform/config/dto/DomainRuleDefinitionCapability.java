package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Server-owned actions available to the authenticated principal for one governed rule definition.")
public record DomainRuleDefinitionCapability(
        @Schema(description = "Immutable identifier of the governed definition.") UUID definitionId,
        @Schema(description = "Canonical decision key shared by all versions of the definition.") String ruleKey,
        @Schema(description = "Version represented by this capability projection.") Integer version,
        @Schema(description = "Actions authorized by the server for this principal and definition.")
        List<String> availableActions) {
}
