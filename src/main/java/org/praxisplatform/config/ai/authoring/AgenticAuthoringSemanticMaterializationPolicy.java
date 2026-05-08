package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class AgenticAuthoringSemanticMaterializationPolicy {

    static final String CHART_REQUIRED_FAILURE = "semantic-preview-chart-required";
    static final String DASHBOARD_REQUIRED_FAILURE = "semantic-preview-dashboard-required";
    static final String RESOURCE_BINDING_MISMATCH_FAILURE = "semantic-preview-resource-binding-mismatch";
    static final String AXIS_SCHEMA_VERIFICATION_REQUIRED_FAILURE = "semantic-preview-axis-schema-verification-required";
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
        if (semanticDecision != null
                && semanticDecision.reviewRequired()
                && !reviewRequirementRegroundedByMaterialization(semanticDecision, materialization)) {
            failureCodes.add(reviewRequiredFailure(semanticDecision));
            warnings.add("semantic-decision-review-required");
        }
        if (requiresChartMaterialization(semanticDecision)
                && !containsComponent(materialization, "praxis-chart")) {
            failureCodes.add(CHART_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if ("dashboard".equals(safe(semanticDecision.artifactKind()))
                && !containsComponent(materialization, "praxis-chart")) {
            failureCodes.add(DASHBOARD_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasResourceBindingMismatch(semanticDecision, materialization)) {
            failureCodes.add(RESOURCE_BINDING_MISMATCH_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasUnverifiedSemanticAxes(materialization)) {
            failureCodes.add(AXIS_SCHEMA_VERIFICATION_REQUIRED_FAILURE);
            warnings.add("semantic-axis-schema-verification-pending");
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
                || "create_chart".equals(safe(semanticDecision.changeKind()))
                || "charts".equals(safe(semanticDecision.visualIntent()));
    }

    private static boolean hasResourceBindingMismatch(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        if (semanticDecision == null || semanticDecision.selectedResource() == null) {
            return false;
        }
        String expectedResource = normalizePath(semanticDecision.selectedResource().resourcePath());
        if (expectedResource.isBlank()) {
            return false;
        }
        List<String> bindings = new ArrayList<>();
        collectResourceBindings(materialization, bindings);
        return bindings.stream()
                .map(AgenticAuthoringSemanticMaterializationPolicy::normalizePath)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> !value.equals(expectedResource)
                        && !value.startsWith(expectedResource + "/")
                        && !expectedResource.startsWith(value + "/"));
    }

    private static void collectResourceBindings(JsonNode node, List<String> bindings) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (String field : List.of("resourcePath", "submitUrl", "dataUrl", "sourceUrl")) {
                String value = node.path(field).asText("");
                if (!value.isBlank() && value.startsWith("/api/")) {
                    bindings.add(value);
                }
            }
            for (JsonNode child : node) {
                collectResourceBindings(child, bindings);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectResourceBindings(child, bindings);
            }
        }
    }

    private static boolean hasUnverifiedSemanticAxes(JsonNode materialization) {
        JsonNode axes = materialization == null
                ? null
                : materialization.path("diagnostics").path("semanticAxes");
        if (axes == null || !axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (!axis.path("schemaVerified").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reviewRequirementRegroundedByMaterialization(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        if (semanticDecision == null
                || !"weak-lexical-evidence".equals(safe(semanticDecision.reviewReason()))
                || materialization == null
                || materialization.isMissingNode()
                || materialization.isNull()) {
            return false;
        }
        JsonNode grounding = materialization.path("diagnostics").path("resourceSchemaGrounding");
        return grounding.path("verified").asBoolean(false)
                && "schemas.filtered".equals(safe(grounding.path("source").asText("")));
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

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    record ValidationResult(List<String> failureCodes, List<String> warnings) {
        boolean valid() {
            return failureCodes == null || failureCodes.isEmpty();
        }
    }
}
