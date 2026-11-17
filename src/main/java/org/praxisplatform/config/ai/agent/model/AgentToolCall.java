package org.praxisplatform.config.ai.agent.model;

import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Representa uma chamada de ferramenta emitida pelo modelo, preservando assinaturas opacas
 * exigidas por provedores (como {@code thoughtSignature} no Gemini ou {@code callId} na OpenAI).
 */
public record AgentToolCall(
        String callId,
        String name,
        Map<String, Object> arguments,
        String thoughtSignature,
        String rawArgumentsJson
) {
    public AgentToolCall {
        arguments = AgentMapCopies.immutableOrderedMap(arguments);
    }
}
