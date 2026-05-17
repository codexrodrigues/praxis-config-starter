package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringVisualizationDecision(
        String schemaVersion,
        String intent,
        String layoutKind,
        String primaryComponent,
        List<AgenticAuthoringVisualizationAxisDecision> axes,
        boolean includeSummary,
        boolean includeDetailTable,
        List<String> excludedComponentIds,
        boolean includeFilters,
        boolean includeKpis,
        String provenance
) {
    public AgenticAuthoringVisualizationDecision {
        excludedComponentIds = excludedComponentIds == null ? List.of() : List.copyOf(excludedComponentIds);
    }

    public AgenticAuthoringVisualizationDecision(
            String schemaVersion,
            String intent,
            String layoutKind,
            String primaryComponent,
            List<AgenticAuthoringVisualizationAxisDecision> axes,
            boolean includeSummary,
            boolean includeDetailTable,
            String provenance) {
        this(
                schemaVersion,
                intent,
                layoutKind,
                primaryComponent,
                axes,
                includeSummary,
                includeDetailTable,
                List.of(),
                true,
                true,
                provenance);
    }
}
