package org.praxisplatform.config.ai.authoring;

public record AgenticAuthoringPreIntentToolPlanningResult(
        boolean planned,
        AgenticAuthoringPreIntentToolPlan plan,
        String skipReason,
        String errorCode
) {

    public AgenticAuthoringPreIntentToolPlanningResult {
        skipReason = skipReason == null ? "" : skipReason.trim();
        errorCode = errorCode == null ? "" : errorCode.trim();
        if (!planned) {
            plan = null;
        }
    }

    public static AgenticAuthoringPreIntentToolPlanningResult planned(
            AgenticAuthoringPreIntentToolPlan plan) {
        return new AgenticAuthoringPreIntentToolPlanningResult(true, plan, "", "");
    }

    public static AgenticAuthoringPreIntentToolPlanningResult skipped(String skipReason) {
        return new AgenticAuthoringPreIntentToolPlanningResult(false, null, skipReason, "");
    }

    public static AgenticAuthoringPreIntentToolPlanningResult failed(
            String skipReason,
            String errorCode) {
        return new AgenticAuthoringPreIntentToolPlanningResult(false, null, skipReason, errorCode);
    }
}
