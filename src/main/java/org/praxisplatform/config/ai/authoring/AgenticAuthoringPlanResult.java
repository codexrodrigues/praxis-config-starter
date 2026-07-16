package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

public record AgenticAuthoringPlanResult(
        boolean valid,
        List<String> failureCodes,
        List<String> warnings,
        JsonNode minimalFormPlan,
        @JsonIgnore List<AiProviderInvocationTelemetry> providerInvocations
) {
    public AgenticAuthoringPlanResult(
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            JsonNode minimalFormPlan) {
        this(valid, failureCodes, warnings, minimalFormPlan, List.of());
    }

    public AgenticAuthoringPlanResult {
        providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
    }
}
