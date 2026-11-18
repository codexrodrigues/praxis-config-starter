package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Solicitacao de preparacao de proposta governada de mutacao ou comando operacional antes da
 * confirmacao humana explicita.
 */
public record AgentPrepareActionRequest(
        String entityKeyOrResourcePath,
        String entityId,
        String actionId,
        Map<String, Object> proposedInputs
) {
    public AgentPrepareActionRequest {
        proposedInputs = AgentMapCopies.immutableOrderedMap(proposedInputs);
    }
}
