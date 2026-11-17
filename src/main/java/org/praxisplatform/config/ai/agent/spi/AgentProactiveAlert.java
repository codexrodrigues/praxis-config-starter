package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Alerta operacional proativo associado a uma entidade consultada (ex.: pendencia, expiracao ou
 * inconsistencia cadastral).
 */
public record AgentProactiveAlert(
        String code,
        String severity,
        String title,
        String description,
        String entityKey,
        String resourcePath,
        String entityId,
        String suggestedActionId,
        String suggestedActionLabel,
        Map<String, Object> metadata
) {
    public AgentProactiveAlert {
        metadata = AgentMapCopies.immutableOrderedMap(metadata);
    }
}
