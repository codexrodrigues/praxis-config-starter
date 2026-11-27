package org.praxisplatform.config.ai.agent.spi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Neutral Monte Carlo / QMC stochastic simulation request passed from agent tools to
 * {@link AgentResourceExecutionBridge}.
 */
public record AgentStochasticSimulationRequest(
        String entityKeyOrResourcePath,
        String datasetKey,
        Integer scenarioCount,
        Integer horizonPeriods,
        Long seed,
        LocalDate asOfDate,
        String samplingStrategy,
        String dependenceMethod,
        List<Map<String, Object>> modelPoints,
        List<Map<String, Object>> stochasticFactors,
        List<Map<String, Object>> correlations,
        List<Map<String, Object>> thresholds,
        List<Double> quantiles,
        List<Double> anchorQuantiles,
        Integer minBucketCount,
        Map<String, Object> filters
) {
    public AgentStochasticSimulationRequest {
        modelPoints = immutableMapList(modelPoints);
        stochasticFactors = immutableMapList(stochasticFactors);
        correlations = immutableMapList(correlations);
        thresholds = immutableMapList(thresholds);
        quantiles = AgentMapCopies.immutableList(quantiles);
        anchorQuantiles = AgentMapCopies.immutableList(anchorQuantiles);
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
