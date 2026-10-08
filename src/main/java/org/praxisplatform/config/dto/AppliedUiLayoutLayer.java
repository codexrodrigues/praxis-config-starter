package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Safe provenance for one immutable layout revision applied during resolution.")
public record AppliedUiLayoutLayer(
        @Schema(description = "Precedence class used by the resolver.") String layerClass,
        @Schema(description = "Opaque immutable revision reference.") String revisionRef,
        @Schema(description = "Canonical SHA-256 content hash.") String contentHash,
        @Schema(description = "Explicit priority within the layer class.") short priority,
        @Schema(description = "Optional non-sensitive release label.") String label) {}
