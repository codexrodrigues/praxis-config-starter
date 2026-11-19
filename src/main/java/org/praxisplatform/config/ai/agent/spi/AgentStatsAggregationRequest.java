package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Requisicao governada de agregacao analitica in-process sobre um recurso metadata-driven.
 */
public record AgentStatsAggregationRequest(
        String entityKeyOrResourcePath,
        String operationKind,
        String field,
        String metricOperation,
        String metricField,
        String granularity,
        String distributionMode,
        String chartKind,
        String title,
        Map<String, Object> filters,
        int limit
) {
    public AgentStatsAggregationRequest {
        filters = AgentMapCopies.immutableOrderedMap(filters);
        if (limit <= 0) {
            limit = 12;
        }
        if (limit > 50) {
            limit = 50;
        }
    }
}
