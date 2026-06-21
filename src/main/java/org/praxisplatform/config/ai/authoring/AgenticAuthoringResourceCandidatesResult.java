package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

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
        @JsonIgnore AgenticAuthoringResourceSearchFocus resourceSearchFocus,
        AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection,
        @JsonIgnore Map<String, Object> diagnostics
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
                null,
                null,
                Map.of());
    }

    public AgenticAuthoringResourceCandidatesResult(
            boolean valid,
            String tool,
            String retrievalQuery,
            String artifactKind,
            String assistantMessage,
            JsonNode assistantContent,
            List<AgenticAuthoringCandidate> candidates,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> warnings,
            AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection) {
        this(
                valid,
                tool,
                retrievalQuery,
                artifactKind,
                assistantMessage,
                assistantContent,
                candidates,
                quickReplies,
                warnings,
                null,
                consultativeProjection,
                Map.of());
    }

    public AgenticAuthoringResourceCandidatesResult {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
