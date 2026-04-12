package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringPlanRequest(
        String userPrompt,
        String provider,
        String model,
        String apiKey
) {
}
