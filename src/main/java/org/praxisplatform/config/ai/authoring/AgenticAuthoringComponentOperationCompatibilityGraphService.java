package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cache boundary for manifest-derived operation compatibility graphs. */
final class AgenticAuthoringComponentOperationCompatibilityGraphService {
    private final Map<String, AgenticAuthoringComponentOperationCompatibilityGraph> cache = new ConcurrentHashMap<>();

    AgenticAuthoringComponentOperationCompatibilityGraph.Resolution resolve(String componentId, JsonNode manifest, java.util.List<String> operationIds) {
        String key = componentId + ":" + manifest.path("manifestVersion").asText("");
        return cache.computeIfAbsent(key, ignored -> AgenticAuthoringComponentOperationCompatibilityGraph.derive(manifest)).resolve(operationIds);
    }
}
