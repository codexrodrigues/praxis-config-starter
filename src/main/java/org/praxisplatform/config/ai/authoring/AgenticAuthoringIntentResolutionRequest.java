package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringIntentResolutionRequest(
        String userPrompt,
        String targetApp,
        String targetComponentId,
        String currentRoute,
        JsonNode currentPage,
        String selectedWidgetKey,
        String provider,
        String model,
        String apiKey
) {
}
