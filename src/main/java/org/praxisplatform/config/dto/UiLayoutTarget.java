package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Exact opaque identity of one configurable Praxis UI component instance.")
public record UiLayoutTarget(
        @Schema(description = "Canonical Praxis component selector.", example = "praxis-table")
                String componentType,
        @Schema(description = "Exact host-registered component instance key.") String componentId) {

    public UiLayoutTarget {
        componentType = required(componentType, "componentType", 64);
        componentId = required(componentId, "componentId", 255);
    }

    private static String required(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain at most " + maximumLength + " characters.");
        }
        return normalized;
    }
}
