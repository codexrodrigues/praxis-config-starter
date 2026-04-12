package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringDryRunResult(
        boolean valid,
        List<AgenticAuthoringGateResult> gates,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode patch
) {
}
