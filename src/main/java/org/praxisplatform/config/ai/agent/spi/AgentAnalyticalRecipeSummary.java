package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Compact catalog summary of a server-verified analytical recipe published by a Praxis resource.
 */
public record AgentAnalyticalRecipeSummary(
        String recipeId,
        String title,
        String description,
        String intent,
        String operation,
        String field,
        String governanceTier,
        String version,
        String provenanceDigest,
        String endpointPath,
        Map<String, Object> lockedFilters,
        List<AgentAnalyticalRecipeParameterSummary> adjustableParams
) {
    public AgentAnalyticalRecipeSummary {
        lockedFilters = AgentMapCopies.immutableOrderedMap(lockedFilters);
        adjustableParams = AgentMapCopies.immutableList(adjustableParams);
    }

    public record AgentAnalyticalRecipeParameterSummary(
            String name,
            String filterField,
            String description,
            String dataType,
            boolean required,
            String defaultValue,
            List<String> allowedValues,
            String minValue,
            String maxValue
    ) {
        public AgentAnalyticalRecipeParameterSummary {
            allowedValues = AgentMapCopies.immutableList(allowedValues);
        }
    }
}
