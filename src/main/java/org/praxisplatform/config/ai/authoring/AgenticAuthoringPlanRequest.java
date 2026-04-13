package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringPlanRequest(
        String userPrompt,
        String provider,
        String model,
        String apiKey,
        JsonNode currentPage,
        AgenticAuthoringIntentResolutionResult intentResolution
) {
    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey) {
        this(userPrompt, provider, model, apiKey, null, null);
    }

    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        this(userPrompt, provider, model, apiKey, null, intentResolution);
    }
}
