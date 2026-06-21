package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringResourceCandidatesRequest(
        String retrievalQuery,
        String userPrompt,
        String artifactKind,
        Integer limit,
        AgenticAuthoringResourceSearchFocus resourceSearchFocus
) {

    public AgenticAuthoringResourceCandidatesRequest(
            String retrievalQuery,
            String userPrompt,
            String artifactKind,
            Integer limit) {
        this(retrievalQuery, userPrompt, artifactKind, limit, null);
    }
}
