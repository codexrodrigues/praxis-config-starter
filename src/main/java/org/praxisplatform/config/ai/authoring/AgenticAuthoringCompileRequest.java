package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;

public record AgenticAuthoringCompileRequest(
        JsonNode minimalFormPlan
) {
}
