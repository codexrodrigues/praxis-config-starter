package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Requisicao para simulacao nao persistente de um endpoint de determinacao reativa
 * ({@code /determinations/{determinationId}}) em modo somente leitura / rollback.
 */
public record AgentDeterminationRequest(
        String entityKeyOrResourcePath,
        String determinationId,
        Map<String, Object> draftFields
) {
    public AgentDeterminationRequest {
        draftFields = AgentMapCopies.immutableOrderedMap(draftFields);
    }
}
