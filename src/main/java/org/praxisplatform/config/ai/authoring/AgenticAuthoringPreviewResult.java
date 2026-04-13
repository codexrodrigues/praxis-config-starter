package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringPreviewResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode minimalFormPlan,
        JsonNode compiledFormPatch,
        AgenticAuthoringPreviewDiagnostics diagnostics
) {
    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, null);
    }
}
