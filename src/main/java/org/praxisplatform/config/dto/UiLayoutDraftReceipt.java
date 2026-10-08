package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Sanitized mutable workspace identity; draft content is intentionally not published in B1c. */
@Schema(description = "Scoped mutable UI layout workspace receipt without unpublished draft content.")
public record UiLayoutDraftReceipt(
    @Schema(description = "Opaque draft identity within the authorized composition scope.", requiredMode = Schema.RequiredMode.REQUIRED) String draftRef,
    @Schema(description = "Registered composition root that owns this workspace.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget rootTarget,
    @Schema(description = "Current lifecycle state of the workspace, such as DRAFT or RELEASED.", requiredMode = Schema.RequiredMode.REQUIRED) String state,
    @Schema(description = "Opaque strong validator for the mutable draft representation. It is returned as the HTTP ETag for subsequent draft writes.", requiredMode = Schema.RequiredMode.REQUIRED) String draftEtag,
    @Schema(description = "Server timestamp when this workspace was created.", requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
    @Schema(description = "Server timestamp of the last workspace mutation.", requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {}
