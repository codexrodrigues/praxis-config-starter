package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringResolveTargetRequest(
        JsonNode config,
        String operationId,
        JsonNode target,
        JsonNode input
) {
}
