package org.praxisplatform.config.ai.agent.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Neutral statistical inference request passed from agent tools to {@link AgentResourceExecutionBridge}.
 */
public record AgentStatisticalInferenceRequest(
        String entityKeyOrResourcePath,
        List<String> tests,
        String groupByField,
        String expectedField,
        String observedField,
        String residualField,
        Boolean variableOrRetroactiveEligible,
        Double alpha,
        BigDecimal materialityThreshold,
        Integer minBucketCount,
        Long seed,
        LocalDate asOfDate,
        Map<String, Object> filters
) {
    public AgentStatisticalInferenceRequest {
        tests = AgentMapCopies.immutableList(tests);
        filters = AgentMapCopies.immutableOrderedMap(filters);
    }
}
