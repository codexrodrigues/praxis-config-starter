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
        List<AgentSurfaceSummary> availableSurfaces,
        StatsCapabilityDigest statsCapability,
        List<QuickFilterSummary> quickFilters,
        List<ResourceFactSummary> resourceFacts,
        boolean calculationMemoryAvailable,
        List<String> calculationTrailIds,
        List<DeterminationSummary> determinations
) {
    public CompactAgentSchemaDigest(
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
            List<AgentSurfaceSummary> availableSurfaces) {
        this(
                entityKey,
                resourceKey,
                resourcePath,
                title,
                description,
                idFieldName,
                titleField,
                subtitleField,
                metadataFields,
                filterableFields,
                responseFields,
                availableActions,
                availableSurfaces,
                null,
                List.of(),
                List.of(),
                false,
                List.of(),
                List.of());
    }

    public CompactAgentSchemaDigest(
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
            List<AgentSurfaceSummary> availableSurfaces,
            StatsCapabilityDigest statsCapability,
            List<QuickFilterSummary> quickFilters,
            List<ResourceFactSummary> resourceFacts,
            boolean calculationMemoryAvailable,
            List<String> calculationTrailIds) {
        this(
                entityKey,
                resourceKey,
                resourcePath,
                title,
                description,
                idFieldName,
                titleField,
                subtitleField,
                metadataFields,
                filterableFields,
                responseFields,
                availableActions,
                availableSurfaces,
                statsCapability,
                quickFilters,
                resourceFacts,
                calculationMemoryAvailable,
                calculationTrailIds,
                List.of());
    }

    public CompactAgentSchemaDigest {
        metadataFields = metadataFields == null ? List.of() : List.copyOf(metadataFields);
        filterableFields = filterableFields == null ? List.of() : List.copyOf(filterableFields);
        responseFields = responseFields == null ? List.of() : List.copyOf(responseFields);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        availableSurfaces = availableSurfaces == null ? List.of() : List.copyOf(availableSurfaces);
        quickFilters = quickFilters == null ? List.of() : List.copyOf(quickFilters);
        resourceFacts = resourceFacts == null ? List.of() : List.copyOf(resourceFacts);
        calculationTrailIds = calculationTrailIds == null ? List.of() : List.copyOf(calculationTrailIds);
        determinations = determinations == null ? List.of() : List.copyOf(determinations);
    }

    public record DeterminationSummary(
            String id,
            String title,
            String description,
            String path,
            List<String> inputFields
    ) {
        public DeterminationSummary {
            inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        }
    }

    public record StatsCapabilityDigest(
            String groupByMode,
            String timeSeriesMode,
            String distributionMode,
            List<StatsFieldDigest> fields
    ) {
        public StatsCapabilityDigest {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record StatsFieldDigest(
            String field,
            String labelPropertyPath,
            List<String> metrics,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible
    ) {
        public StatsFieldDigest {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    public record QuickFilterSummary(
            String id,
            String label,
            String description,
            String category
    ) {
    }

    public record ResourceFactSummary(
            String key,
            String label,
            String dataType,
            String description,
            String authority
    ) {
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
