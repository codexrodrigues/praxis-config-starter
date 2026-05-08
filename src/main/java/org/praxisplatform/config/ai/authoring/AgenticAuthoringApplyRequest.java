package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringApplyRequest(
        JsonNode compiledFormPatch,
        String componentType,
        String componentId,
        String scope,
        JsonNode tags,
        AgenticAuthoringSemanticDecision semanticDecision
) {
}
