package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringVisualizationAxisDecision(
        String concept,
        String field,
        String label,
        String chartType,
        String orientation,
        String metricAggregation,
        String metricField,
        String metricLabel,
        String provenance
) {
}
