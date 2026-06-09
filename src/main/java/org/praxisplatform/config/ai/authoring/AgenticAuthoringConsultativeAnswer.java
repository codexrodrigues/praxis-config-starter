package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringConsultativeAnswer(
        String category,
        String changeKind,
        String assistantMessage,
        AgenticAuthoringConsultativeApiCatalogProjection apiCatalogProjection,
        List<String> warnings,
        JsonNode evidenceBundle,
        List<AgenticAuthoringQuickReply> quickReplies
) {
    public AgenticAuthoringConsultativeAnswer(
            String category,
            String changeKind,
            String assistantMessage,
            AgenticAuthoringConsultativeApiCatalogProjection apiCatalogProjection,
            List<String> warnings,
            JsonNode evidenceBundle) {
        this(category, changeKind, assistantMessage, apiCatalogProjection, warnings, evidenceBundle, List.of());
    }

    public AgenticAuthoringConsultativeAnswer(
            String category,
            String changeKind,
            String assistantMessage,
            AgenticAuthoringConsultativeApiCatalogProjection apiCatalogProjection,
            List<String> warnings) {
        this(category, changeKind, assistantMessage, apiCatalogProjection, warnings, null, List.of());
    }
}
