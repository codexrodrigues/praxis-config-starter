package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.List;

public class AgenticAuthoringCandidateEligibilityGate {

    public AgenticAuthoringGateResult evaluate(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringTarget target,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> messages = new ArrayList<>();
        if ("unknown".equals(operationKind)) {
            messages.add("intent-operation-unknown");
        }
        boolean apiCatalogQuestion = "explore".equals(operationKind)
                && "api_catalog".equals(artifactKind)
                && "answer_api_catalog_question".equals(changeKind);
        if ("explore".equals(operationKind) && !apiCatalogQuestion) {
            messages.add("intent-confirmation-required");
        }
        if ("unknown".equals(artifactKind)) {
            messages.add("intent-artifact-unknown");
        }
        boolean targetlessDashboardAddition = "modify".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && "add_dashboard_widget".equals(changeKind);
        if (("modify".equals(operationKind) || "remove".equals(operationKind) || "connect".equals(operationKind))
                && !targetlessDashboardAddition) {
            if (target == null || target.widgetKey() == null || target.widgetKey().isBlank()) {
                messages.add("target-widget-required");
            }
        }
        boolean hasCandidates = candidates != null && !candidates.isEmpty();
        if ("create".equals(operationKind) && selectedCandidate == null && !hasCandidates) {
            messages.add("resource-candidate-required");
        }
        if (!apiCatalogQuestion && selectedCandidate == null && candidates != null && candidates.size() > 1) {
            messages.add("resource-candidate-ambiguous");
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", status, List.copyOf(messages));
    }
}
