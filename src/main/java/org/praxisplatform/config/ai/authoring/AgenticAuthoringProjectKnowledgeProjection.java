package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringProjectKnowledgeProjection(
        String knowledgeId,
        String conceptKey,
        String kind,
        Scope scope,
        Status status,
        String visibility,
        String sourceSummary,
        String influence,
        String summary,
        List<String> evidence
) {

    public record Scope(
            String tenantId,
            String environment,
            String contextKey,
            String resourceKey
    ) {
    }

    public record Status(
            String lifecycle,
            String curationStatus
    ) {
    }
}
