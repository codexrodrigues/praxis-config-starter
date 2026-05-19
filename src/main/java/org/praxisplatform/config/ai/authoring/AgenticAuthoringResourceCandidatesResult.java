package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringResourceCandidatesResult(
        boolean valid,
        String tool,
        String retrievalQuery,
        String artifactKind,
        String assistantMessage,
        JsonNode assistantContent,
        List<AgenticAuthoringCandidate> candidates,
        List<AgenticAuthoringQuickReply> quickReplies,
        List<String> warnings,
        AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection
) {
    public AgenticAuthoringResourceCandidatesResult(
            boolean valid,
            String tool,
            String retrievalQuery,
            String artifactKind,
            String assistantMessage,
            List<AgenticAuthoringCandidate> candidates,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> warnings) {
        this(
                valid,
                tool,
                retrievalQuery,
                artifactKind,
                assistantMessage,
                null,
                candidates,
                quickReplies,
                warnings,
                null);
    }
}
