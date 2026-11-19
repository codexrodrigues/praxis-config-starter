package org.praxisplatform.config.ai.agent.spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de agregacao estatistica governada pronto para renderizacao em grafico e consumo seguro
 * pelo modelo.
 */
public record AgentStatsResult(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String title,
        String subtitle,
        String operationKind,
        String chartKind,
        String field,
        String fieldLabel,
        String metricOperation,
        String metricField,
        String granularity,
        long totalElements,
        List<AgentStatsBucket> buckets,
        String emptyReason
) {
    public AgentStatsResult {
        buckets = AgentMapCopies.immutableList(buckets);
    }

    public record AgentStatsBucket(
            String key,
            String label,
            BigDecimal value,
            long count,
            String rangeFrom,
            String rangeTo
    ) {
    }
}
