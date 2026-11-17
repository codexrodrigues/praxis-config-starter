package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Candidato encontrado durante resolucao polimorfica de entidades antes da consolidacao de um dossie 360.
 */
public record AgentEntityMatch(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String entityTitle,
        String entityId,
        String title,
        String subtitle,
        String matchedBy,
        double confidence,
        Map<String, Object> uiSummary,
        Map<String, Object> modelSafeSummary,
        boolean fromActiveScreenScope
) {
    public AgentEntityMatch {
        uiSummary = AgentMapCopies.immutableOrderedMap(uiSummary);
        modelSafeSummary = AgentMapCopies.immutableOrderedMap(modelSafeSummary);
    }
}
