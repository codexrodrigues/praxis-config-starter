package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

public record AgenticAuthoringComponentEditPlanResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode plan,
        JsonNode compiledPatch,
        @JsonIgnore List<AiProviderInvocationTelemetry> providerInvocations
) {
    public AgenticAuthoringComponentEditPlanResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode plan,
            JsonNode compiledPatch) {
        this(valid, failureCodes, warnings, plan, compiledPatch, List.of());
    }

    public AgenticAuthoringComponentEditPlanResult {
        providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
    }
}
