package org.praxisplatform.config.ai.agent.model;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;
import org.praxisplatform.config.service.AiCallConfig;

/**
 * Requisicao neutra para uma rodada de inferencia com suporte a function calling,
 * JSON Schema estrito opcional e contexto de execucao para politicas de elegibilidade.
 */
public record AgentModelTurnRequest(
        String systemInstruction,
        List<AgentConversationItem> conversation,
        List<AgentToolDeclaration> tools,
        AiCallConfig callConfig,
        boolean disableToolCalling,
        Map<String, Object> responseJsonSchema,
        AgentExecutionContext executionContext
) {
    public AgentModelTurnRequest {
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        tools = tools == null ? List.of() : List.copyOf(tools);
        responseJsonSchema = AgentMapCopies.immutableOrderedMap(responseJsonSchema);
    }

    public AgentModelTurnRequest(
            String systemInstruction,
            List<AgentConversationItem> conversation,
            List<AgentToolDeclaration> tools,
            AiCallConfig callConfig) {
        this(systemInstruction, conversation, tools, callConfig, false, Map.of(), null);
    }

    public AgentModelTurnRequest(
            String systemInstruction,
            List<AgentConversationItem> conversation,
            List<AgentToolDeclaration> tools,
            AiCallConfig callConfig,
            boolean disableToolCalling) {
        this(systemInstruction, conversation, tools, callConfig, disableToolCalling, Map.of(), null);
    }

    public AgentModelTurnRequest(
            String systemInstruction,
            List<AgentConversationItem> conversation,
            List<AgentToolDeclaration> tools,
            AiCallConfig callConfig,
            boolean disableToolCalling,
            Map<String, Object> responseJsonSchema) {
        this(systemInstruction, conversation, tools, callConfig, disableToolCalling, responseJsonSchema, null);
    }
}
