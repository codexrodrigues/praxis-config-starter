package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Resumo compacto de schema e capacidades enviado ao LLM sob demanda, evitando despejar
 * OpenAPI completo na janela de contexto.
 */
public record CompactAgentSchemaDigest(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String title,
        String description,
        String idFieldName,
        String titleField,
        String subtitleField,
        List<String> metadataFields,
        List<FilterFieldDigest> filterableFields,
        List<ResponseFieldDigest> responseFields,
        List<AgentActionAffordance> availableActions,
        List<AgentSurfaceSummary> availableSurfaces
) {
    public CompactAgentSchemaDigest {
        metadataFields = metadataFields == null ? List.of() : List.copyOf(metadataFields);
        filterableFields = filterableFields == null ? List.of() : List.copyOf(filterableFields);
        responseFields = responseFields == null ? List.of() : List.copyOf(responseFields);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        availableSurfaces = availableSurfaces == null ? List.of() : List.copyOf(availableSurfaces);
    }

    public record FilterFieldDigest(
            String name,
            String label,
            String description,
            String operation,
            List<String> relations
    ) {
        public FilterFieldDigest {
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record ResponseFieldDigest(
            String name,
            String label,
            String description,
            String controlType,
            String aiVisibility,
            boolean quickViewVisible
    ) {
    }
}
