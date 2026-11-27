package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Contrato canonico de inicializacao de turno SSE do assistente operacional.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperationalAgentTurnStreamRequest(
        String userPrompt,
        String sessionId,
        String clientTurnId,
        String activeScreenResourcePath,
        String activeTopicKey,
        Map<String, Object> activeSelectionHints,
        List<PraxisOperationalAgentLoop.ConversationHistoryEntry> conversationMessages,
        String provider,
        String model,
        String apiKey,
        JsonNode contextHints,
        String authoringWorkspaceRoute,
        String authoringWorkspaceLabel
) {
    public OperationalAgentTurnStreamRequest(
            String userPrompt,
            String sessionId,
            String clientTurnId,
            String activeScreenResourcePath,
            String activeTopicKey,
            Map<String, Object> activeSelectionHints,
            List<PraxisOperationalAgentLoop.ConversationHistoryEntry> conversationMessages,
            String provider,
            String model,
            String apiKey,
            JsonNode contextHints) {
        this(
                userPrompt,
                sessionId,
                clientTurnId,
                activeScreenResourcePath,
                activeTopicKey,
                activeSelectionHints,
                conversationMessages,
                provider,
                model,
                apiKey,
                contextHints,
                null,
                null);
    }
}
