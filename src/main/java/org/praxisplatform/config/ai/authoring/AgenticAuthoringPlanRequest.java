package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringPlanRequest(
        String userPrompt,
        String provider,
        String model,
        String apiKey,
        AgenticAuthoringIntentResolutionResult intentResolution
) {
    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey) {
        this(userPrompt, provider, model, apiKey, null);
    }
}
