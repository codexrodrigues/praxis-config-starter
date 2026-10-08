package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sanitized diagnostic produced while resolving an effective UI layout.")
public record UiLayoutResolutionDiagnostic(
        @Schema(description = "Stable machine-readable diagnostic code.") String code,
        @Schema(description = "Severity: INFO, WARNING or ERROR.") String severity,
        @Schema(description = "Safe summary without audience or payload values.") String summary,
        @Schema(description = "Optional JSON Pointer for an authorable presentation property.") String path) {}
