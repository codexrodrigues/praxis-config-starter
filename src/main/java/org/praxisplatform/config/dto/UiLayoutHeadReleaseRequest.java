package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Select an already approved immutable release as the active head target. */
@Schema(description = "Select one approved immutable release for publication or rollback.")
public record UiLayoutHeadReleaseRequest(
    @Schema(description = "Approved immutable release to select for the registered root.", requiredMode = Schema.RequiredMode.REQUIRED) UUID releaseId,
    @Schema(description = "Business justification for publication or rollback.", requiredMode = Schema.RequiredMode.REQUIRED) String reason) {}
