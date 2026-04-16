package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringIntentResolutionRequest(
        String userPrompt,
        String targetApp,
        String targetComponentId,
        String currentRoute,
        JsonNode currentPage,
        String selectedWidgetKey,
        String provider,
        String model,
        String apiKey,
        String sessionId,
        String clientTurnId,
        List<AgenticAuthoringConversationMessage> conversationMessages,
        AgenticAuthoringPendingClarification pendingClarification,
        List<AgenticAuthoringAttachmentSummary> attachmentSummaries,
        JsonNode contextHints
) {
    public AgenticAuthoringIntentResolutionRequest(
            String userPrompt,
            String targetApp,
            String targetComponentId,
            String currentRoute,
            JsonNode currentPage,
            String selectedWidgetKey,
            String provider,
            String model,
            String apiKey) {
        this(
                userPrompt,
                targetApp,
                targetComponentId,
                currentRoute,
                currentPage,
                selectedWidgetKey,
                provider,
                model,
                apiKey,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public AgenticAuthoringIntentResolutionRequest(
            String userPrompt,
            String targetApp,
            String targetComponentId,
            String currentRoute,
            JsonNode currentPage,
            String selectedWidgetKey,
            String provider,
            String model,
            String apiKey,
            String sessionId,
            String clientTurnId,
            List<AgenticAuthoringConversationMessage> conversationMessages,
            AgenticAuthoringPendingClarification pendingClarification) {
        this(
                userPrompt,
                targetApp,
                targetComponentId,
                currentRoute,
                currentPage,
                selectedWidgetKey,
                provider,
                model,
                apiKey,
                sessionId,
                clientTurnId,
                conversationMessages,
                pendingClarification,
                null,
                null);
    }

    public AgenticAuthoringIntentResolutionRequest(
            String userPrompt,
            String targetApp,
            String targetComponentId,
            String currentRoute,
            JsonNode currentPage,
            String selectedWidgetKey,
            String provider,
            String model,
            String apiKey,
            String sessionId,
            String clientTurnId,
            List<AgenticAuthoringConversationMessage> conversationMessages,
            AgenticAuthoringPendingClarification pendingClarification,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        this(
                userPrompt,
                targetApp,
                targetComponentId,
                currentRoute,
                currentPage,
                selectedWidgetKey,
                provider,
                model,
                apiKey,
                sessionId,
                clientTurnId,
                conversationMessages,
                pendingClarification,
                attachmentSummaries,
                null);
    }
}
