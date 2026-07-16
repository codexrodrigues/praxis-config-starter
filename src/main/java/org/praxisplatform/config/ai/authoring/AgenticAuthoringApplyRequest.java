package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record AgenticAuthoringApplyRequest(
        JsonNode compiledFormPatch,
        String componentType,
        String componentId,
        String scope,
        JsonNode tags,
        AgenticAuthoringSemanticDecision semanticDecision,
        UUID streamId,
        UUID resultEventId
) {
    public AgenticAuthoringApplyRequest(
            JsonNode compiledFormPatch,
            String componentType,
            String componentId,
            String scope,
            JsonNode tags,
            AgenticAuthoringSemanticDecision semanticDecision) {
        this(compiledFormPatch, componentType, componentId, scope, tags, semanticDecision, null, null);
    }
}
