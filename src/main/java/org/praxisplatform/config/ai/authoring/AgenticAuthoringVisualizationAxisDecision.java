package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
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
