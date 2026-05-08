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
        String provenance
) {
}
