package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringPreviewDiagnostics(
        boolean derivedCurrentPageSummary,
        String targetWidgetKey,
        String operationKind,
        String changeKind,
        String fieldScopeDecision,
        JsonNode projectKnowledgeAudit
) {

    public AgenticAuthoringPreviewDiagnostics(
            boolean derivedCurrentPageSummary,
            String targetWidgetKey,
            String operationKind,
            String changeKind,
            String fieldScopeDecision) {
        this(
                derivedCurrentPageSummary,
                targetWidgetKey,
                operationKind,
                changeKind,
                fieldScopeDecision,
                null);
    }
}
