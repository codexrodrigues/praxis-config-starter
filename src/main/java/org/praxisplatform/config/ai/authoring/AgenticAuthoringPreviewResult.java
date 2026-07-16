package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

public record AgenticAuthoringPreviewResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode minimalFormPlan,
        JsonNode compiledFormPatch,
        AgenticAuthoringPreviewDiagnostics diagnostics,
        JsonNode uiCompositionPlan,
        String assistantMessage,
        @JsonIgnore List<AiProviderInvocationTelemetry> providerInvocations
) {
    public AgenticAuthoringPreviewResult {
        providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch,
            AgenticAuthoringPreviewDiagnostics diagnostics,
            JsonNode uiCompositionPlan,
            String assistantMessage) {
        this(
                valid,
                failureCodes,
                warnings,
                minimalFormPlan,
                compiledFormPatch,
                diagnostics,
                uiCompositionPlan,
                assistantMessage,
                List.of());
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, null, null, null, List.of());
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch,
            AgenticAuthoringPreviewDiagnostics diagnostics) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, diagnostics, null, null, List.of());
    }

    public AgenticAuthoringPreviewResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch,
            AgenticAuthoringPreviewDiagnostics diagnostics,
            JsonNode uiCompositionPlan) {
        this(valid, failureCodes, warnings, minimalFormPlan, compiledFormPatch, diagnostics, uiCompositionPlan, null, List.of());
    }
}
