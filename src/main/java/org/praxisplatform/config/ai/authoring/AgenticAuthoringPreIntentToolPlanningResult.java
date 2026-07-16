package org.praxisplatform.config.ai.authoring;

import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

public record AgenticAuthoringPreIntentToolPlanningResult(
        boolean planned,
        AgenticAuthoringPreIntentToolPlan plan,
        String skipReason,
        String errorCode,
        List<AiProviderInvocationTelemetry> providerInvocations
) {

    public AgenticAuthoringPreIntentToolPlanningResult {
        skipReason = skipReason == null ? "" : skipReason.trim();
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (!planned) {
            plan = null;
        }
        providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
    }

    public static AgenticAuthoringPreIntentToolPlanningResult planned(
            AgenticAuthoringPreIntentToolPlan plan) {
        return planned(plan, List.of());
    }

    public static AgenticAuthoringPreIntentToolPlanningResult planned(
            AgenticAuthoringPreIntentToolPlan plan,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        return new AgenticAuthoringPreIntentToolPlanningResult(true, plan, "", "", providerInvocations);
    }

    public static AgenticAuthoringPreIntentToolPlanningResult skipped(String skipReason) {
        return skipped(skipReason, List.of());
    }

    public static AgenticAuthoringPreIntentToolPlanningResult skipped(
            String skipReason,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        return new AgenticAuthoringPreIntentToolPlanningResult(
                false, null, skipReason, "", providerInvocations);
    }

    public static AgenticAuthoringPreIntentToolPlanningResult failed(
            String skipReason,
            String errorCode) {
        return failed(skipReason, errorCode, List.of());
    }

    public static AgenticAuthoringPreIntentToolPlanningResult failed(
            String skipReason,
            String errorCode,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        return new AgenticAuthoringPreIntentToolPlanningResult(
                false, null, skipReason, errorCode, providerInvocations);
    }
}
