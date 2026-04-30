package org.praxisplatform.config.ai.authoring;

import java.util.List;

final class AgenticAuthoringRepairClassificationPolicy {

    static final String RETRYABLE = "retryable";
    static final String NON_RETRYABLE = "non_retryable";
    static final String ROUTE_REQUIRED = "route_required";
    static final String USER_CLARIFICATION_REQUIRED = "user_clarification_required";

    private AgenticAuthoringRepairClassificationPolicy() {
    }

    static String classify(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        if (isRouteRequired(intentResolution, preview)) {
            return ROUTE_REQUIRED;
        }
        if (isUserClarificationRequired(intentResolution, preview)) {
            return USER_CLARIFICATION_REQUIRED;
        }
        if (preview != null && !preview.valid()) {
            return RETRYABLE;
        }
        return NON_RETRYABLE;
    }

    private static boolean isRouteRequired(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        return hasGateStatus(intentResolution, ROUTE_REQUIRED)
                || hasAny(intentResolution == null ? null : intentResolution.failureCodes(),
                        "shared-rule-authoring-required",
                        "intent-resolution-shared-rule-route-required")
                || hasAny(preview == null ? null : preview.failureCodes(),
                        "shared-rule-authoring-required",
                        "intent-resolution-shared-rule-route-required");
    }

    private static boolean isUserClarificationRequired(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        return hasGateStatus(intentResolution, "clarification_required")
                || hasAny(intentResolution == null ? null : intentResolution.failureCodes(),
                        "resource-candidate-required",
                        "target-widget-required",
                        "intent-confirmation-required",
                        "analytics-breakdown-required",
                        "analytics-custom-breakdown-required")
                || hasAny(preview == null ? null : preview.failureCodes(),
                        "intent-resolution-selected-candidate-required",
                        "resource-candidate-required",
                        "target-widget-required",
                        "intent-confirmation-required",
                        "analytics-breakdown-required",
                        "analytics-custom-breakdown-required");
    }

    private static boolean hasGateStatus(
            AgenticAuthoringIntentResolutionResult intentResolution,
            String status) {
        return intentResolution != null
                && intentResolution.gate() != null
                && status.equals(intentResolution.gate().status());
    }

    private static boolean hasAny(List<String> values, String... expectedValues) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String expected : expectedValues) {
            if (values.contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
