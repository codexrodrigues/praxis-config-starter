package org.praxisplatform.config.dto;

import java.util.List;

/** Discoverable operations for the governed color-palette lifecycle. */
public record GovernedColorPaletteCapabilitiesResponse(
        String ruleType,
        String targetLayer,
        String targetArtifactType,
        List<Operation> operations,
        List<String> supportedPurposes) {

    public record Operation(String id, String requiredRole, boolean allowed) {}
}
