package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringConsultativeAnswer(
        String category,
        String changeKind,
        String assistantMessage,
        AgenticAuthoringConsultativeApiCatalogProjection apiCatalogProjection,
        List<String> warnings
) {
}
