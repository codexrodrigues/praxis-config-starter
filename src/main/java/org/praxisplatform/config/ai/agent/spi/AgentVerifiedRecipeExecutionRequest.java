package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Request contract passed to {@link AgentResourceExecutionBridge#executeVerifiedAnalyticalRecipe}
 * to invoke a backend-verified analytical recipe with fail-closed bounded parameters.
 */
public record AgentVerifiedRecipeExecutionRequest(
        String entityKeyOrResourcePath,
        String recipeId,
        Map<String, Object> parameters,
        String timezone,
        String chartKind,
        String title
) {
    public AgentVerifiedRecipeExecutionRequest {
        parameters = AgentMapCopies.immutableOrderedMap(parameters);
    }
}
