package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Contrato canonico de inicializacao de turno SSE do assistente operacional.
 */
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
        JsonNode contextHints
) {
}
