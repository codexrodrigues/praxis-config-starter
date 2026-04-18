package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringPreviewResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode minimalFormPlan,
        JsonNode compiledFormPatch,
        AgenticAuthoringPreviewDiagnostics diagnostics,
        JsonNode uiCompositionPlan,
        String assistantMessage
) {
    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, null, null, null);
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch,
            AgenticAuthoringPreviewDiagnostics diagnostics) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, diagnostics, null, null);
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch,
            AgenticAuthoringPreviewDiagnostics diagnostics,
            JsonNode uiCompositionPlan) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, diagnostics, uiCompositionPlan, null);
    }
}
