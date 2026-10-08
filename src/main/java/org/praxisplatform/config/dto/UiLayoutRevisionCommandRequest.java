package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Typed authoring request for one target-specific immutable patch revision. */
@Schema(description = "Submit complete native authorship for one registered target. Config derives the immutable contribution against the draft's original pinned baseline before validation and persistence.", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UiLayoutRevisionCommandRequest(
    @Schema(description = "Client-known correlation of the accepted selection. It is not an idempotency key.", requiredMode = Schema.RequiredMode.REQUIRED) UUID commandRef,
    @Schema(description = "Registered draft target whose pinned baseline, native format and presentation policy govern this revision.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget target,
    @Schema(description = "Complete native authoring document accepted for deterministic reopen; never published as a release member.", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode authoringDocument,
    @Schema(description = "Bounded business reason for the immutable revision.", requiredMode = Schema.RequiredMode.REQUIRED) String reason) {}
