package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** Target-specific preview input used before a governed palette is published. */
public record GovernedColorPalettePreviewRequest(
        @Schema(description = "Stable identity of the governed palette being previewed.")
        String paletteKey,
        @Schema(description = "Candidate palette payload to validate without persisting it.")
        JsonNode palette) {}
