package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Typed immutable assignment request; it never accepts actor, scope or policy facts. */
@Schema(description = "Create one immutable audience assignment for a validated content revision.")
public record UiLayoutAssignmentCommandRequest(
    @Schema(description = "Client-known correlation of the accepted selection. It is not an idempotency key.", requiredMode = Schema.RequiredMode.REQUIRED) UUID commandRef,
    @Schema(description = "Registered target that must equal the selected revision definition.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget target,
    @Schema(description = "Closed resolver layer class compatible with selector.", allowableValues = {"TENANT", "ORGANIZATION", "SECTOR", "GROUP", "PROFILE", "USER"}, requiredMode = Schema.RequiredMode.REQUIRED) String layerClass,
    @Schema(description = "Closed selector whose tenant must equal the host-confirmed scope.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutAudienceSelectorCommand selector,
    @Schema(description = "Deterministic precedence within layerClass.", requiredMode = Schema.RequiredMode.REQUIRED) short priority,
    @Schema(description = "Stable logical contribution identity; never derived from selector or priority.", requiredMode = Schema.RequiredMode.REQUIRED) String contributionKey) {}
