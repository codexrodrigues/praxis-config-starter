package org.praxisplatform.config.ai.agent.model;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Item neutro de historico trocado com o provedor LLM, preservando chamadas de ferramenta,
 * resultados e estado opaco de provedor entre rodadas do loop ReAct.
 */
public record AgentConversationItem(
        AgentConversationRole role,
        String text,
        List<AgentToolCall> toolCalls,
        List<AgentToolResult> toolResults,
        Map<String, Object> providerOpaqueState
) {
    public AgentConversationItem {
        toolCalls = AgentMapCopies.immutableList(toolCalls);
        toolResults = AgentMapCopies.immutableList(toolResults);
        providerOpaqueState = AgentMapCopies.immutableOrderedMap(providerOpaqueState);
    }

    public static AgentConversationItem userText(String text) {
        return new AgentConversationItem(AgentConversationRole.USER, text, List.of(), List.of(), Map.of());
    }

    public static AgentConversationItem assistantText(String text) {
        return new AgentConversationItem(AgentConversationRole.ASSISTANT, text, List.of(), List.of(), Map.of());
    }

    public static AgentConversationItem assistantToolCalls(
            String text,
            List<AgentToolCall> toolCalls,
            Map<String, Object> providerOpaqueState) {
        return new AgentConversationItem(
                AgentConversationRole.ASSISTANT,
                text,
                toolCalls,
                List.of(),
                providerOpaqueState);
    }

    public static AgentConversationItem toolResults(List<AgentToolResult> toolResults) {
        return new AgentConversationItem(
                AgentConversationRole.TOOL_RESULT,
                null,
                List.of(),
                toolResults,
                Map.of());
    }
}
