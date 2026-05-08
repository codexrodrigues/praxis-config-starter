package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class AgenticAuthoringSemanticMaterializationPolicy {

    static final String CHART_REQUIRED_FAILURE = "semantic-preview-chart-required";
    static final String MATERIALIZATION_MISMATCH_WARNING = "semantic-preview-materialization-mismatch";

    private AgenticAuthoringSemanticMaterializationPolicy() {
    }

    static ValidationResult validate(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        List<String> failureCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (semanticDecision == null) {
            failureCodes.add("semantic-decision-required");
            warnings.add("semantic-decision-required");
            return new ValidationResult(failureCodes, warnings);
        }
        if (semanticDecision != null && semanticDecision.reviewRequired()) {
            failureCodes.add(reviewRequiredFailure(semanticDecision));
            warnings.add("semantic-decision-review-required");
        }
        if (requiresChartMaterialization(semanticDecision)
                && !containsComponent(materialization, "praxis-chart")) {
            failureCodes.add(CHART_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        return new ValidationResult(failureCodes, warnings);
    }

    static boolean requiresChartMaterialization(AgenticAuthoringSemanticDecision semanticDecision) {
        if (semanticDecision == null) {
            return false;
        }
        AgenticAuthoringVisualizationDecision visualizationDecision = semanticDecision.visualizationDecision();
        if (visualizationDecision != null && "praxis-chart".equals(safe(visualizationDecision.primaryComponent()))) {
            return true;
        }
        return "chart".equals(safe(semanticDecision.artifactKind()))
                || "create_chart".equals(safe(semanticDecision.changeKind()));
    }

    private static String reviewRequiredFailure(AgenticAuthoringSemanticDecision semanticDecision) {
        String reason = semanticDecision.reviewReason();
        if (reason == null || reason.isBlank()) {
            return "semantic-decision-review-required";
        }
        return "semantic-decision-review-required:" + reason.trim();
    }

    static boolean containsComponent(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || componentId == null || componentId.isBlank()) {
            return false;
        }
        if (node.isObject()) {
            if (componentId.equals(node.path("componentId").asText(""))) {
                return true;
            }
            if (componentId.equals(node.path("definition").path("id").asText(""))) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsComponent(child, componentId)) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsComponent(child, componentId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record ValidationResult(List<String> failureCodes, List<String> warnings) {
        boolean valid() {
            return failureCodes == null || failureCodes.isEmpty();
        }
    }
}
