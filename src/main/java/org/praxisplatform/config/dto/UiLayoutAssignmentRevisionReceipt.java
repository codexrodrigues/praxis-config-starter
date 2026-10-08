package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe immutable audience-assignment identity; its selector is intentionally not disclosed. */
@Schema(description = "Immutable authorized layout assignment receipt without its audience selector.")
public record UiLayoutAssignmentRevisionReceipt(
    @Schema(description = "Opaque immutable assignment revision identity.", requiredMode = Schema.RequiredMode.REQUIRED) String assignmentRevisionRef,
    @Schema(description = "Opaque immutable content revision selected by the assignment.", requiredMode = Schema.RequiredMode.REQUIRED) String revisionRef,
    @Schema(description = "Exact registered target of the selected content revision.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget target,
    @Schema(description = "Stable logical contribution key within the target and tenant/environment scope.", requiredMode = Schema.RequiredMode.REQUIRED) String contributionKey,
    @Schema(description = "Closed audience-layer class applied by the resolver.", requiredMode = Schema.RequiredMode.REQUIRED) String layerClass,
    @Schema(description = "Deterministic precedence within the layer class.", requiredMode = Schema.RequiredMode.REQUIRED) short priority) {}
