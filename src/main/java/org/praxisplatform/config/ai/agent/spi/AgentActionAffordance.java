package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Acao governada materializavel como botao ou abertura de formulario/drawer no cliente Angular.
 */
public record AgentActionAffordance(
        String actionId,
        String label,
        String description,
        String icon,
        String tone,
        String resourceKey,
        String resourcePath,
        String entityId,
        String operationKind,
        String formMode,
        String method,
        String submitUrl,
        String schemaUrl,
        String route,
        Map<String, Object> queryParams,
        Map<String, Object> prefill,
        boolean confirmRequired,
        boolean disabled,
        String disabledReason,
        int order
) {
    public AgentActionAffordance {
        queryParams = AgentMapCopies.immutableOrderedMap(queryParams);
        prefill = AgentMapCopies.immutableOrderedMap(prefill);
    }
}
