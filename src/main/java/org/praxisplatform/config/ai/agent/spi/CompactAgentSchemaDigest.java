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
        List<DeterminationSummary> determinations,
        BulkCapabilityDigest bulkCapability,
        List<AgentAnalyticalRecipeSummary> verifiedRecipes
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
                List.of(),
                null,
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
                List.of(),
                null,
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
            List<String> calculationTrailIds,
            List<DeterminationSummary> determinations) {
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
                determinations,
                null,
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
            List<String> calculationTrailIds,
            List<DeterminationSummary> determinations,
            BulkCapabilityDigest bulkCapability) {
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
                determinations,
                bulkCapability,
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
        verifiedRecipes = verifiedRecipes == null ? List.of() : List.copyOf(verifiedRecipes);
    }

    public record BulkEditableFieldDigest(
            String name,
            String label,
            String controlType,
            List<String> modes,
            boolean allowClear
    ) {
        public BulkEditableFieldDigest {
            modes = modes == null ? List.of() : List.copyOf(modes);
        }
    }

    public record BulkCommandDigest(
            String actionId,
            String operationId,
            String title,
            String description,
            String atomicity,
            int maxTargets,
            String evaluationPath,
            String confirmPath,
            List<AgentActionInputField> inputFields
    ) {
        public BulkCommandDigest {
            inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        }
    }

    public record BulkCapabilityDigest(
            boolean uniformUpdateSupported,
            boolean perItemUpdateSupported,
            int uniformMaxTargets,
            List<String> uniformSelectionModes,
            List<BulkEditableFieldDigest> editableFields,
            List<BulkCommandDigest> domainCommands
    ) {
        public BulkCapabilityDigest {
            uniformSelectionModes = uniformSelectionModes == null ? List.of() : List.copyOf(uniformSelectionModes);
            editableFields = editableFields == null ? List.of() : List.copyOf(editableFields);
            domainCommands = domainCommands == null ? List.of() : List.copyOf(domainCommands);
        }
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
            String inferenceMode,
            String anomalyMode,
            String simulationMode,
            String counterfactualMode,
            List<StatsFieldDigest> fields
    ) {
        public StatsCapabilityDigest(
                String groupByMode,
                String timeSeriesMode,
                String distributionMode,
                List<StatsFieldDigest> fields
        ) {
            this(groupByMode, timeSeriesMode, distributionMode, "DISABLED", "DISABLED", "DISABLED", "DISABLED", fields);
        }

        public StatsCapabilityDigest(
                String groupByMode,
                String timeSeriesMode,
                String distributionMode,
                String inferenceMode,
                String anomalyMode,
                List<StatsFieldDigest> fields
        ) {
            this(groupByMode, timeSeriesMode, distributionMode, inferenceMode, anomalyMode, "DISABLED", "DISABLED", fields);
        }

        public StatsCapabilityDigest {
            inferenceMode = (inferenceMode == null || inferenceMode.isBlank()) ? "DISABLED" : inferenceMode;
            anomalyMode = (anomalyMode == null || anomalyMode.isBlank()) ? "DISABLED" : anomalyMode;
            simulationMode = (simulationMode == null || simulationMode.isBlank()) ? "DISABLED" : simulationMode;
            counterfactualMode = (counterfactualMode == null || counterfactualMode.isBlank()) ? "DISABLED" : counterfactualMode;
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
