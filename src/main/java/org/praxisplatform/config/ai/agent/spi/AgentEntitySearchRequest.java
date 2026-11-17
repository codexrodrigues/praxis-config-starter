package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Parametros normalizados para resolucao polimorfica de entidades por identificador,
 * documento (com hints Mod-11 pre-extraidos) ou texto livre.
 */
public record AgentEntitySearchRequest(
        String rawQuery,
        String resolvedTaxId,
        boolean validTaxIdMod11,
        List<String> candidateEntityKeys,
        int maxResultsPerEntity
) {
    public AgentEntitySearchRequest {
        candidateEntityKeys = candidateEntityKeys == null ? List.of() : List.copyOf(candidateEntityKeys);
        if (maxResultsPerEntity <= 0) {
            maxResultsPerEntity = 5;
        }
    }
}
