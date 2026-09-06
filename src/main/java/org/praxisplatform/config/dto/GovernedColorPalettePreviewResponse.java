package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.ColorTokenEntry;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.ContrastEvidence;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.Validation;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.Variant;

/** Non-persistent preview of the exact runtime projection produced by palette authoring. */
public record GovernedColorPalettePreviewResponse(
        String ruleType,
        String targetLayer,
        String targetArtifactType,
        String targetArtifactKey,
        String displayName,
        String familyKey,
        Variant variant,
        JsonNode scope,
        List<ColorTokenEntry> entries,
        List<ContrastEvidence> contrastEvidence,
        JsonNode provenance,
        Validation validation) {}
