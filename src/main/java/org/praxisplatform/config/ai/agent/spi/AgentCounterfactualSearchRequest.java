package org.praxisplatform.config.ai.agent.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Neutral constrained counterfactual recourse search request passed from agent tools to
 * {@link AgentResourceExecutionBridge}.
 */
public record AgentCounterfactualSearchRequest(
        String entityKeyOrResourcePath,
        String datasetKey,
        String targetMetricKey,
        String direction,
        BigDecimal baselineValue,
        BigDecimal targetValue,
        BigDecimal targetUpperBound,
        List<Map<String, Object>> mutableLevers,
        Integer maxCandidates,
        Integer maxChangedLevers,
        Long seed,
        LocalDate asOfDate,
        Map<String, Object> filters
) {
    public AgentCounterfactualSearchRequest {
        mutableLevers = immutableMapList(mutableLevers);
        filters = AgentMapCopies.immutableOrderedMap(filters);
    }

    private static List<Map<String, Object>> immutableMapList(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .map(AgentMapCopies::immutableOrderedMap)
                .toList();
    }
}
