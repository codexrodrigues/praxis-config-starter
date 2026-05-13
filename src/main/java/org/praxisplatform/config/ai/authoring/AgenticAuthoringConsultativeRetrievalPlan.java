package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringConsultativeRetrievalPlan(
        String schemaVersion,
        List<String> requiredContext,
        List<String> semanticQueries,
        String answerStrategy,
        List<String> expectedEvidence
) {
}
