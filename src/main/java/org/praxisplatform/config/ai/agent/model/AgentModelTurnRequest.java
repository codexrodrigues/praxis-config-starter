package org.praxisplatform.config.ai.agent.model;

import java.util.List;
import org.praxisplatform.config.service.AiCallConfig;

/**
 * Requisicao neutra para uma rodada de inferencia com suporte a function calling.
 */
public record AgentModelTurnRequest(
        String systemInstruction,
        List<AgentConversationItem> conversation,
        List<AgentToolDeclaration> tools,
        AiCallConfig callConfig,
        boolean disableToolCalling
) {
    public AgentModelTurnRequest {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public AgentModelTurnRequest(
            String systemInstruction,
            List<AgentConversationItem> conversation,
            List<AgentToolDeclaration> tools,
            AiCallConfig callConfig) {
        this(systemInstruction, conversation, tools, callConfig, false);
    }
}
