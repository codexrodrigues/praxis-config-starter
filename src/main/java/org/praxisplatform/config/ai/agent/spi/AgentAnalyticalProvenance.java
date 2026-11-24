package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Deterministic provenance metadata attached to analytical stats results and rendered chart blocks.
 */
public record AgentAnalyticalProvenance(
        String mode,
        String recipeId,
        String recipeTitle,
        String recipeVersion,
        String governanceTier,
        String provenanceDigest,
        Map<String, Object> lockedFilters,
        Map<String, Object> appliedParameters
) {
    public static final String MODE_VERIFIED_RECIPE = "VERIFIED_RECIPE";
    public static final String MODE_EXPLORATORY_UNVERIFIED = "EXPLORATORY_UNVERIFIED";

    public AgentAnalyticalProvenance {
        lockedFilters = AgentMapCopies.immutableOrderedMap(lockedFilters);
        appliedParameters = AgentMapCopies.immutableOrderedMap(appliedParameters);
    }

    public static AgentAnalyticalProvenance verifiedRecipe(
            String recipeId,
            String recipeTitle,
            String recipeVersion,
            String governanceTier,
            String provenanceDigest,
            Map<String, Object> lockedFilters,
            Map<String, Object> appliedParameters
    ) {
        return new AgentAnalyticalProvenance(
                MODE_VERIFIED_RECIPE,
                recipeId,
                recipeTitle,
                recipeVersion,
                governanceTier,
                provenanceDigest,
                lockedFilters,
                appliedParameters
        );
    }

    public static AgentAnalyticalProvenance exploratoryUnverified(Map<String, Object> appliedFilters) {
        return new AgentAnalyticalProvenance(
                MODE_EXPLORATORY_UNVERIFIED,
                null,
                null,
                null,
                "EXPLORATORY",
                null,
                Map.of(),
                appliedFilters
        );
    }
}
