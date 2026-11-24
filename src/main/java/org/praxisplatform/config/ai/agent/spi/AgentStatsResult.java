package org.praxisplatform.config.ai.agent.spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de agregacao estatistica governada pronto para renderizacao em grafico e consumo seguro
 * pelo modelo, acompanhado por {@link AgentAnalyticalProvenance} quando aplicavel.
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
        String emptyReason,
        AgentAnalyticalProvenance analyticalProvenance
) {
    public AgentStatsResult {
        buckets = AgentMapCopies.immutableList(buckets);
    }

    public AgentStatsResult(
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
        this(
                entityKey,
                resourceKey,
                resourcePath,
                title,
                subtitle,
                operationKind,
                chartKind,
                field,
                fieldLabel,
                metricOperation,
                metricField,
                granularity,
                totalElements,
                buckets,
                emptyReason,
                null);
    }

    public record AgentStatsBucket(
            String key,
            String label,
            BigDecimal value,
            long count,
            String rangeFrom,
            String rangeTo,
            BigDecimal previousValue,
            BigDecimal delta,
            Double deltaPercent
    ) {
        public AgentStatsBucket(
                String key,
                String label,
                BigDecimal value,
                long count,
                String rangeFrom,
                String rangeTo) {
            this(key, label, value, count, rangeFrom, rangeTo, null, null, null);
        }
    }
}
