package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.List;

public class AgenticAuthoringCandidateEligibilityGate {

    public AgenticAuthoringGateResult evaluate(
            String operationKind,
            String artifactKind,
            AgenticAuthoringTarget target,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> messages = new ArrayList<>();
        if ("unknown".equals(operationKind)) {
            messages.add("intent-operation-unknown");
        }
        if ("unknown".equals(artifactKind)) {
            messages.add("intent-artifact-unknown");
        }
        if ("modify".equals(operationKind) || "remove".equals(operationKind) || "connect".equals(operationKind)) {
            if (target == null || target.widgetKey() == null || target.widgetKey().isBlank()) {
                messages.add("target-widget-required");
            }
        }
        if ("create".equals(operationKind) && selectedCandidate == null) {
            messages.add("resource-candidate-required");
        }
        if (selectedCandidate == null && candidates != null && candidates.size() > 1) {
            messages.add("resource-candidate-ambiguous");
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", status, List.copyOf(messages));
    }
}
