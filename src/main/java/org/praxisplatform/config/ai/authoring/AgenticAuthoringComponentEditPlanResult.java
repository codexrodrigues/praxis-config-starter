package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringComponentEditPlanResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode plan,
        JsonNode compiledPatch
) {
}
