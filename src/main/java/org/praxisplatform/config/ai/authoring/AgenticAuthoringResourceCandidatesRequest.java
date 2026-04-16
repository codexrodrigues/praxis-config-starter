package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringResourceCandidatesRequest(
        String retrievalQuery,
        String userPrompt,
        String artifactKind,
        Integer limit
) {
}
