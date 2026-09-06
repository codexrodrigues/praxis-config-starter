package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Runtime projection of an approved corporate color palette. */
public record GovernedColorPaletteResponse(
        String paletteKey,
        String displayName,
        String familyKey,
        Variant variant,
        int version,
        String status,
        String tenantId,
        String environment,
        JsonNode scope,
        List<ColorTokenEntry> entries,
        List<ContrastEvidence> contrastEvidence,
        JsonNode provenance,
        Validation validation,
        Instant publishedAt,
        String etag) {

    public record ColorTokenEntry(
            String tokenId,
            String displayName,
            List<String> aliases,
            String semanticRole,
            String cssReference,
            String fallback,
            String fallbackTokenId,
            List<String> purposes) {}

    public record Variant(String key, String displayName, JsonNode dimensions) {}

    /** Ratio and pass/fail are server-calculated from the resolved fallback colors. */
    public record ContrastEvidence(
            String foregroundTokenId,
            String backgroundTokenId,
            double ratio,
            String requiredLevel,
            boolean passes) {}

    public record Validation(boolean valid, List<String> errors, List<String> warnings) {}
}
