package org.praxisplatform.config.ai.agent.model;

import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Resultado estruturado da execucao de uma ferramenta local enviado de volta ao modelo.
 */
public record AgentToolResult(
        String callId,
        String name,
        Map<String, Object> output,
        boolean error
) {
    public AgentToolResult {
        output = AgentMapCopies.immutableOrderedMap(output);
    }
}
