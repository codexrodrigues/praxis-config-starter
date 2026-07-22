package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cache boundary for manifest-derived operation compatibility graphs. */
final class AgenticAuthoringComponentOperationCompatibilityGraphService {
    private final Map<String, CachedGraph> cache = new ConcurrentHashMap<>();

    AgenticAuthoringComponentOperationCompatibilityGraph.Resolution resolve(String componentId, JsonNode manifest, java.util.List<String> operationIds) {
        String key = componentId + ":" + manifest.path("manifestVersion").asText("");
        String digest = manifest == null ? "" : manifest.toString();
        CachedGraph cached = cache.compute(key, (ignored, current) -> current != null && current.digest().equals(digest)
                ? current
                : new CachedGraph(digest, AgenticAuthoringComponentOperationCompatibilityGraph.derive(manifest)));
        return cached.graph().resolve(operationIds);
    }

    private record CachedGraph(String digest, AgenticAuthoringComponentOperationCompatibilityGraph graph) { }
}
