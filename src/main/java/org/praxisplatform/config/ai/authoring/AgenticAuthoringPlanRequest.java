package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringPlanRequest(
        String userPrompt,
        String provider,
        String model,
        String apiKey,
        JsonNode currentPage,
        AgenticAuthoringIntentResolutionResult intentResolution,
        String sessionId,
        String clientTurnId,
        List<AgenticAuthoringConversationMessage> conversationMessages,
        AgenticAuthoringPendingClarification pendingClarification,
        List<AgenticAuthoringAttachmentSummary> attachmentSummaries,
        JsonNode contextHints
) {
    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey) {
        this(userPrompt, provider, model, apiKey, null, null, null, null, null, null, null, null);
    }

    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        this(userPrompt, provider, model, apiKey, null, intentResolution, null, null, null, null, null, null);
    }

    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey,
            JsonNode currentPage,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        this(userPrompt, provider, model, apiKey, currentPage, intentResolution, null, null, null, null, null, null);
    }

    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey,
            JsonNode currentPage,
            AgenticAuthoringIntentResolutionResult intentResolution,
            String sessionId,
            String clientTurnId,
            List<AgenticAuthoringConversationMessage> conversationMessages,
            AgenticAuthoringPendingClarification pendingClarification) {
        this(
                userPrompt,
                provider,
                model,
                apiKey,
                currentPage,
                intentResolution,
                sessionId,
                clientTurnId,
                conversationMessages,
                pendingClarification,
                null,
                null);
    }

    public AgenticAuthoringPlanRequest(
            String userPrompt,
            String provider,
            String model,
            String apiKey,
            JsonNode currentPage,
            AgenticAuthoringIntentResolutionResult intentResolution,
            String sessionId,
            String clientTurnId,
            List<AgenticAuthoringConversationMessage> conversationMessages,
            AgenticAuthoringPendingClarification pendingClarification,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        this(
                userPrompt,
                provider,
                model,
                apiKey,
                currentPage,
                intentResolution,
                sessionId,
                clientTurnId,
                conversationMessages,
                pendingClarification,
                attachmentSummaries,
                null);
    }
}
