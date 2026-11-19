package org.praxisplatform.config.ai.agent.runtime;

import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentMapCopies;

/**
 * Payload de confirmacao enviado pelo cliente para efetivar uma {@code AiAgentActionProposal}
 * previamente preparada pelo assistente operacional.
 */
public record AgentProposalConfirmRequest(
        Map<String, Object> inputs,
        String ifMatch,
        String idempotencyKey
) {
    public AgentProposalConfirmRequest {
        inputs = AgentMapCopies.immutableOrderedMap(inputs);
    }
}
