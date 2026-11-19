package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Requisicao de execucao em processo de uma proposta previamente confirmada pelo usuario com
 * precondicao {@code If-Match} e {@code Idempotency-Key}.
 */
public record AgentExecuteActionRequest(
        String entityKeyOrResourcePath,
        String entityId,
        String actionId,
        String expectedVersionTag,
        String idempotencyKey,
        String organizationId,
        Map<String, Object> confirmedInputs
) {
    public AgentExecuteActionRequest {
        confirmedInputs = AgentMapCopies.immutableOrderedMap(confirmedInputs);
    }
}
