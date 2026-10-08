package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Effective schema-validated presentation document and sanitized resolution receipt.")
public record EffectiveUiLayoutResponse(
        @Schema(description = "Version of this response contract.") String schemaVersion,
        UiLayoutTarget target,
        @Schema(description = "Opaque version of the authoritative runtime context.") String contextVersion,
        @Schema(description = "Effective presentation document.") JsonNode document,
        @Schema(description = "Canonical validator for the exact target and context.") String compositeEtag,
        List<AppliedUiLayoutLayer> appliedLayers,
        List<UiLayoutResolutionDiagnostic> diagnostics,
        Instant resolvedAt) {

    public EffectiveUiLayoutResponse {
        document = document == null ? null : document.deepCopy();
        appliedLayers = appliedLayers == null ? List.of() : List.copyOf(appliedLayers);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    @Override
    public JsonNode document() {
        return document == null ? null : document.deepCopy();
    }
}
