package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe immutable revision identity returned after authoring a target patch. */
@Schema(description = "Immutable UI layout content revision receipt without the unpublished patch document.")
public record UiLayoutRevisionReceipt(
    @Schema(description = "Opaque immutable content revision identity.", requiredMode = Schema.RequiredMode.REQUIRED) String revisionRef,
    @Schema(description = "Exact registered target whose patch was validated.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget target,
    @Schema(description = "Canonical SHA-256 hash of the immutable patch, not a mutable write validator.", requiredMode = Schema.RequiredMode.REQUIRED) String contentHash,
    @Schema(description = "Pinned public patch schema version used when the patch was accepted; it is independent from the native authoring document version.", requiredMode = Schema.RequiredMode.REQUIRED) String schemaVersion,
    @Schema(description = "Monotonic revision number within the target definition.", requiredMode = Schema.RequiredMode.REQUIRED) long revisionNumber) {}
