package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringCompileRequest(
        JsonNode minimalFormPlan,
        JsonNode currentPage,
        AgenticAuthoringIntentResolutionResult intentResolution
) {
    public AgenticAuthoringCompileRequest(JsonNode minimalFormPlan) {
        this(minimalFormPlan, null, null);
    }

    public AgenticAuthoringCompileRequest(
            JsonNode minimalFormPlan,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        this(minimalFormPlan, null, intentResolution);
    }
}
