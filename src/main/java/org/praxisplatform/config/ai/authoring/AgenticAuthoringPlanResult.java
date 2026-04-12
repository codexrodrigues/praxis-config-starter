package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringPlanResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode minimalFormPlan
) {
}
