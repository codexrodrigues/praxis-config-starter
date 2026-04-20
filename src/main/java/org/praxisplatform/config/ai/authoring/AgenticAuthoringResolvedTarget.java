package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringResolvedTarget(
        String status,
        String componentId,
        String operationId,
        String kind,
        String resolver,
        String path,
        JsonNode value,
        List<String> candidates,
        List<String> failures
) {
}
