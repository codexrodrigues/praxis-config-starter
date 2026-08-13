package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Server-scoped capability projection for governed domain-rule definitions.")
public record DomainRuleDefinitionCapabilitiesResponse(
        @Schema(description = "Tenant resolved from the authenticated principal, never from browser authority.") String tenantId,
        @Schema(description = "Environment resolved from the authenticated principal.") String environment,
        @Schema(description = "Per-definition actions available to the authenticated principal.")
        List<DomainRuleDefinitionCapability> definitions) {
}
