package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.config.dto.UiLayoutTarget;

public record UiLayoutResolutionCandidate(
        UiLayoutTarget target,
        String revisionRef,
        String contentHash,
        LayerClass layerClass,
        short priority,
        UiLayoutAudienceSelector selector,
        JsonNode patch,
        String safeLabel) {

    public enum LayerClass {
        TENANT,
        ORGANIZATION,
        SECTOR,
        GROUP,
        PROFILE,
        USER
    }

    public UiLayoutResolutionCandidate {
        if (target == null || selector == null || patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("A layout candidate requires target, selector and object patch.");
        }
        if (revisionRef == null || revisionRef.isBlank() || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("A layout candidate requires immutable revision and content hash.");
        }
        if (layerClass == null) throw new IllegalArgumentException("layerClass is required.");
        revisionRef = revisionRef.trim();
        contentHash = contentHash.trim();
        safeLabel = safeLabel == null || safeLabel.isBlank() ? null : safeLabel.trim();
        patch = patch.deepCopy();
        validateLayerSelector(layerClass, selector);
    }

    @Override
    public JsonNode patch() {
        return patch.deepCopy();
    }

    private static void validateLayerSelector(LayerClass layerClass, UiLayoutAudienceSelector selector) {
        boolean valid = switch (layerClass) {
            case TENANT -> selector.organization() == null
                    && selector.sector() == null
                    && selector.group() == null
                    && selector.profile() == null
                    && selector.user() == null;
            case ORGANIZATION -> selector.organization() != null;
            case SECTOR -> selector.organization() != null && selector.sector() != null;
            case GROUP -> selector.group() != null;
            case PROFILE -> selector.profile() != null;
            case USER -> selector.user() != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Layout layer class is incompatible with its audience selector.");
        }
    }
}
