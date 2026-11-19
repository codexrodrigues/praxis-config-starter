package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Solicita o preparo e a avaliacao sem efeitos colaterais de uma operacao em lote governada
 * ({@code UNIFORM_UPDATE}, {@code PER_ITEM_UPDATE} ou {@code DOMAIN_COMMAND}) diretamente em processo.
 */
public record AgentPrepareBulkRequest(
        String entityKeyOrResourcePath,
        String bulkMode,
        String actionId,
        String selectionMode,
        Map<String, Object> filters,
        List<String> targetIds,
        List<String> excludedIds,
        List<Map<String, Object>> changes,
        List<Map<String, Object>> items,
        Map<String, Object> parameters,
        String rationale
) {
    public AgentPrepareBulkRequest {
        filters = AgentMapCopies.immutableOrderedMap(filters);
        targetIds = AgentMapCopies.immutableList(targetIds);
        excludedIds = AgentMapCopies.immutableList(excludedIds);
        changes = AgentMapCopies.immutableList(changes);
        items = AgentMapCopies.immutableList(items);
        parameters = AgentMapCopies.immutableOrderedMap(parameters);
    }
}
