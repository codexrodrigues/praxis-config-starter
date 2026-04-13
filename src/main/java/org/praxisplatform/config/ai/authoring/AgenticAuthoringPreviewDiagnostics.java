package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringPreviewDiagnostics(
        boolean derivedCurrentPageSummary,
        String targetWidgetKey,
        String operationKind,
        String changeKind,
        String fieldScopeDecision
) {
}
