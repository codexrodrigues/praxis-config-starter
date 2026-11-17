package org.praxisplatform.config.ai.agent.model;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Resposta neutra de uma rodada de inferencia do provedor LLM, contendo texto final ou chamadas de ferramenta.
 */
public record AgentModelTurnResponse(
        String provider,
        String model,
        String text,
        List<AgentToolCall> toolCalls,
        AgentTokenUsage usage,
        String finishReason,
        String rawResponseId,
        Map<String, Object> providerOpaqueState
) {
    public AgentModelTurnResponse {
        toolCalls = AgentMapCopies.immutableList(toolCalls);
        usage = usage == null ? AgentTokenUsage.ZERO : usage;
        providerOpaqueState = AgentMapCopies.immutableOrderedMap(providerOpaqueState);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
