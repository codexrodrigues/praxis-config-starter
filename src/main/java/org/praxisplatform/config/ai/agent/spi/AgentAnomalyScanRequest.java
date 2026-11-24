package org.praxisplatform.config.ai.agent.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Neutral anomaly scan request passed from agent tools to {@link AgentResourceExecutionBridge}.
 */
public record AgentAnomalyScanRequest(
        String entityKeyOrResourcePath,
        List<String> methods,
        Map<String, String> columnMapping,
        Double alpha,
        BigDecimal materialityThreshold,
        Double madThreshold,
        Integer minCohortSize,
        Integer minGroupSize,
        Boolean enableBenford,
        Integer minBenfordSamples,
        Integer minBucketCount,
        Integer maxFindings,
        Long seed,
        LocalDate asOfDate,
        Map<String, Object> filters
) {
    public AgentAnomalyScanRequest {
        methods = AgentMapCopies.immutableList(methods);
        columnMapping = AgentMapCopies.immutableOrderedMap(columnMapping);
        filters = AgentMapCopies.immutableOrderedMap(filters);
    }
}
