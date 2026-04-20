package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringManifestEditPlanRequest(
        JsonNode config,
        JsonNode plan
) {
}
