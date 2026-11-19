package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Resultado paginado de consulta operacional com dupla projecao ({@code uiRecords} e {@code modelSafeRecords}).
 */
public record AgentQueryResult(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String title,
        long totalElements,
        int page,
        int size,
        List<Map<String, Object>> uiRecords,
        List<Map<String, Object>> modelSafeRecords,
        List<AgentActionAffordance> collectionActions,
        List<AgentFieldDescriptor> fieldDescriptors
) {
    public AgentQueryResult {
        uiRecords = uiRecords == null ? List.of() : List.copyOf(uiRecords);
        modelSafeRecords = modelSafeRecords == null ? List.of() : List.copyOf(modelSafeRecords);
        collectionActions = collectionActions == null ? List.of() : List.copyOf(collectionActions);
        fieldDescriptors = AgentMapCopies.immutableList(fieldDescriptors);
    }

    public AgentQueryResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            long totalElements,
            int page,
            int size,
            List<Map<String, Object>> uiRecords,
            List<Map<String, Object>> modelSafeRecords,
            List<AgentActionAffordance> collectionActions) {
        this(
                entityKey,
                resourceKey,
                resourcePath,
                title,
                totalElements,
                page,
                size,
                uiRecords,
                modelSafeRecords,
                collectionActions,
                List.of());
    }
}
