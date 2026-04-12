package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringApplyResult(
        boolean applied,
        String componentType,
        String componentId,
        String environment,
        String scope,
        long version,
        String etag,
        JsonNode payload,
        JsonNode tags,
        List<String> warnings
) {
}
