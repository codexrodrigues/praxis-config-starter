package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Internal provider capability for refining a server-reconciled semantic source. */
interface AgenticAuthoringPersistedUiCompositionPlanProvider {

    Optional<AgenticAuthoringUiCompositionPlanResult> plan(
            AgenticAuthoringPlanRequest request,
            JsonNode persistedUiCompositionPlan);
}
