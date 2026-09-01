package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

final class AgenticAuthoringSemanticMaterializationPolicy {

    static final String CHART_REQUIRED_FAILURE = "semantic-preview-chart-required";
    static final String DASHBOARD_REQUIRED_FAILURE = "semantic-preview-dashboard-required";
    static final String PRIMARY_COMPONENT_REQUIRED_FAILURE = "semantic-preview-primary-component-required";
    static final String LAYOUT_REQUIRED_FAILURE = "semantic-preview-layout-required";
    static final String RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE =
            "semantic-preview-resource-workspace-grounding-required";
    static final String RELATED_RESOURCE_GROUNDING_REQUIRED_FAILURE =
            "semantic-preview-related-resource-grounding-required";
    static final String RESOURCE_BINDING_MISMATCH_FAILURE = "semantic-preview-resource-binding-mismatch";
    static final String AXIS_SCHEMA_VERIFICATION_REQUIRED_FAILURE = "semantic-preview-axis-schema-verification-required";
    static final String AXIS_STATS_CAPABILITY_VERIFICATION_REQUIRED_FAILURE =
            "semantic-preview-axis-stats-capability-verification-required";
    static final String MATERIALIZATION_MISMATCH_WARNING = "semantic-preview-materialization-mismatch";

    private AgenticAuthoringSemanticMaterializationPolicy() {
    }

    static ValidationResult validate(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        return validate(semanticDecision, materialization, materialization);
    }

    static ValidationResult validate(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization,
            JsonNode semanticEvidence) {
        List<String> failureCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode structuralMaterialization = structuralMaterialization(materialization);
        if (semanticDecision == null) {
            failureCodes.add("semantic-decision-required");
            warnings.add("semantic-decision-required");
            return new ValidationResult(failureCodes, warnings);
        }
        if (semanticDecision != null
                && semanticDecision.reviewRequired()
                && !reviewRequirementRegroundedByMaterialization(
                        semanticDecision,
                        structuralMaterialization,
                        semanticEvidence)) {
            failureCodes.add(reviewRequiredFailure(semanticDecision));
            warnings.add("semantic-decision-review-required");
        }
        String primaryComponent = requestedPrimaryComponent(semanticDecision);
        if (!primaryComponent.isBlank()
                && !containsComponent(structuralMaterialization, primaryComponent)
                && !primaryComponentSatisfiedByCompositeMaterialization(primaryComponent, structuralMaterialization)) {
            failureCodes.add(PRIMARY_COMPONENT_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasLayoutMaterializationMismatch(semanticDecision, structuralMaterialization)) {
            failureCodes.add(LAYOUT_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasUnverifiedResourceWorkspace(structuralMaterialization, semanticEvidence)) {
            failureCodes.add(RESOURCE_WORKSPACE_GROUNDING_REQUIRED_FAILURE);
            warnings.add("semantic-resource-workspace-grounding-required");
        }
        if (hasUnverifiedRelatedResource(semanticDecision, structuralMaterialization, semanticEvidence)) {
            failureCodes.add(RELATED_RESOURCE_GROUNDING_REQUIRED_FAILURE);
            warnings.add("semantic-related-resource-grounding-required");
        }
        if (requiresChartMaterialization(semanticDecision)
                && !containsComponent(structuralMaterialization, "praxis-chart")) {
            failureCodes.add(CHART_REQUIRED_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasResourceBindingMismatch(semanticDecision, structuralMaterialization)) {
            failureCodes.add(RESOURCE_BINDING_MISMATCH_FAILURE);
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasUnverifiedSemanticAxes(semanticEvidence)) {
            failureCodes.add(AXIS_SCHEMA_VERIFICATION_REQUIRED_FAILURE);
            warnings.add("semantic-axis-schema-verification-pending");
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        if (hasUnverifiedStatsAxes(semanticEvidence)) {
            failureCodes.add(AXIS_STATS_CAPABILITY_VERIFICATION_REQUIRED_FAILURE);
            warnings.add("semantic-axis-stats-capability-verification-pending");
            warnings.add(MATERIALIZATION_MISMATCH_WARNING);
        }
        return new ValidationResult(failureCodes, warnings);
    }

    static boolean requiresChartMaterialization(AgenticAuthoringSemanticDecision semanticDecision) {
        if (semanticDecision == null) {
            return false;
        }
        AgenticAuthoringVisualizationDecision visualizationDecision = semanticDecision.visualizationDecision();
        return visualizationDecision != null
                && "praxis-chart".equals(safe(visualizationDecision.primaryComponent()));
    }

    private static String requestedPrimaryComponent(AgenticAuthoringSemanticDecision semanticDecision) {
        if (semanticDecision == null || semanticDecision.visualizationDecision() == null) {
            return "";
        }
        String componentId = safe(semanticDecision.visualizationDecision().primaryComponent());
        if (componentId.isBlank() || "unknown".equals(componentId)) {
            return "";
        }
        return isGovernedComponentId(componentId) ? componentId : "";
    }

    private static boolean isGovernedComponentId(String componentId) {
        String value = safe(componentId);
        return value.startsWith("praxis-")
                || value.startsWith("pdx-")
                || value.contains("-");
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
        List<String> normalizedBindings = bindings.stream()
                .map(AgenticAuthoringSemanticMaterializationPolicy::normalizePath)
                .filter(value -> !value.isBlank())
                .toList();
        if (normalizedBindings.isEmpty()) {
            return requiresCanonicalResourceBinding(semanticDecision, materialization);
        }
        return normalizedBindings.stream()
                .anyMatch(value -> !value.equals(expectedResource)
                        && !value.startsWith(expectedResource + "/")
                        && !expectedResource.startsWith(value + "/"));
    }

    private static boolean requiresCanonicalResourceBinding(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        String semanticLayout = semanticDecision == null || semanticDecision.visualizationDecision() == null
                ? ""
                : safe(semanticDecision.visualizationDecision().layoutKind());
        return "resource-master-detail".equals(semanticLayout)
                || "parent-child-related-resource".equals(semanticLayout)
                || "single-table".equals(semanticLayout)
                || materialization != null
                        && List.of("master-detail-dashboard", "single-table-page").contains(
                                safe(materialization.path("layoutPreset").asText()));
    }

    private static boolean hasUnverifiedResourceWorkspace(
            JsonNode structuralMaterialization,
            JsonNode evidenceMaterialization) {
        if (structuralMaterialization == null
                || !"master-detail-dashboard".equals(
                        safe(structuralMaterialization.path("layoutPreset").asText()))) {
            return false;
        }
        JsonNode diagnostics = evidenceMaterialization == null
                ? structuralMaterialization.path("diagnostics")
                : evidenceMaterialization.path("diagnostics").isObject()
                        ? evidenceMaterialization.path("diagnostics")
                        : structuralMaterialization.path("diagnostics");
        return !"verified".equals(safe(diagnostics
                .path("resourceWorkspaceGrounding")
                .path("status")
                .asText()));
    }

    private static boolean hasUnverifiedRelatedResource(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode structuralMaterialization,
            JsonNode evidenceMaterialization) {
        String semanticLayout = semanticDecision == null || semanticDecision.visualizationDecision() == null
                ? ""
                : safe(semanticDecision.visualizationDecision().layoutKind());
        if (!"parent-child-related-resource".equals(semanticLayout)) {
            return false;
        }
        if (structuralMaterialization == null
                || structuralMaterialization.isMissingNode()
                || structuralMaterialization.isNull()) {
            return true;
        }
        JsonNode diagnostics = evidenceMaterialization == null
                ? structuralMaterialization.path("diagnostics")
                : evidenceMaterialization.path("diagnostics").isObject()
                        ? evidenceMaterialization.path("diagnostics")
                        : structuralMaterialization.path("diagnostics");
        return !"verified".equals(safe(diagnostics
                .path("relatedResourceGrounding")
                .path("status")
                .asText()));
    }

    private static JsonNode structuralMaterialization(JsonNode materialization) {
        if (materialization == null) {
            return null;
        }
        JsonNode page = materialization.path("patch").path("page");
        return page.isObject() ? page : materialization;
    }

    private static boolean hasLayoutMaterializationMismatch(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode materialization) {
        if (semanticDecision == null || semanticDecision.visualizationDecision() == null) {
            return false;
        }
        String semanticLayout = safe(semanticDecision.visualizationDecision().layoutKind());
        String expectedLayoutPreset = switch (semanticLayout) {
            case "single-table" -> "single-table-page";
            case "resource-master-detail" -> "master-detail-dashboard";
            case "parent-child-related-resource" -> "master-detail-dashboard";
            case "resource-crud" -> "resource-crud";
            default -> "";
        };
        if (expectedLayoutPreset.isBlank()) {
            return false;
        }
        String materializedLayout = materialization == null
                ? ""
                : safe(materialization.path("layoutPreset").asText(""));
        return !expectedLayoutPreset.equals(materializedLayout)
                || "single-table".equals(semanticLayout)
                        && countComponents(materialization, "praxis-table") != 1;
    }

    private static int countComponents(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || componentId == null || componentId.isBlank()) {
            return 0;
        }
        if (node.isObject()) {
            int count = componentId.equals(node.path("componentId").asText(""))
                    || componentId.equals(node.path("definition").path("id").asText("")) ? 1 : 0;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!"diagnostics".equals(field.getKey())) {
                    count += countComponents(field.getValue(), componentId);
                }
            }
            return count;
        }
        if (node.isArray()) {
            int count = 0;
            for (JsonNode child : node) {
                count += countComponents(child, componentId);
            }
            return count;
        }
        return 0;
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
            node.fields().forEachRemaining(field -> {
                if (!"diagnostics".equals(field.getKey())) {
                    collectResourceBindings(field.getValue(), bindings);
                }
            });
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
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (!axis.path("schemaVerified").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnverifiedStatsAxes(JsonNode materialization) {
        JsonNode axes = materialization == null
                ? null
                : materialization.path("diagnostics").path("semanticAxes");
        if (axes == null || !axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (axis.path("statsVerified").isBoolean() && !axis.path("statsVerified").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static boolean reviewRequirementRegroundedByMaterialization(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode structuralMaterialization,
            JsonNode semanticEvidence) {
        if (semanticDecision == null
                || semanticEvidence == null
                || semanticEvidence.isMissingNode()
                || semanticEvidence.isNull()) {
            return false;
        }
        JsonNode grounding = semanticEvidence.path("diagnostics").path("resourceSchemaGrounding");
        boolean schemaGrounded = grounding.path("verified").asBoolean(false)
                && "schemas.filtered".equals(safe(grounding.path("source").asText("")));
        if (!schemaGrounded) {
            return false;
        }
        String reason = safe(semanticDecision.reviewReason());
        if ("weak-lexical-evidence".equals(reason)) {
            return semanticDecision.selectedResource() != null
                    && !safe(semanticDecision.selectedResource().resourcePath()).isBlank()
                    && !hasResourceBindingMismatch(semanticDecision, structuralMaterialization);
        }
        if ("prompt-alignment-selection".equals(reason)) {
            return hasGovernedResourceEvidence(semanticDecision)
                    && semanticDecision.selectedResource() != null
                    && !safe(semanticDecision.selectedResource().resourcePath()).isBlank()
                    && !hasResourceBindingMismatch(semanticDecision, structuralMaterialization);
        }
        return "keyword-fallback-fail-safe".equals(reason)
                && (semanticDecision.refinement() != null
                && semanticDecision.refinement().preservesResource()
                || hasGovernedResourceEvidence(semanticDecision)
                && semanticDecision.selectedResource() != null
                && !safe(semanticDecision.selectedResource().resourcePath()).isBlank()
                && !hasResourceBindingMismatch(semanticDecision, structuralMaterialization));
    }

    private static boolean hasGovernedResourceEvidence(AgenticAuthoringSemanticDecision semanticDecision) {
        AgenticAuthoringSemanticDecision.RetrievalEvidence retrievalEvidence =
                semanticDecision == null ? null : semanticDecision.retrievalEvidence();
        if (retrievalEvidence == null || retrievalEvidence.evidence() == null) {
            return false;
        }
        return retrievalEvidence.evidence().contains("tool-search-api-resources")
                || "semantic_retrieval".equals(safe(retrievalEvidence.retrievalSource()));
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
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                // Diagnostics describe considered and rejected candidates; they are evidence about
                // authoring, not part of the materialized UI. Treating them as widgets makes a
                // rejected component look present and corrupts validation and assistant copy.
                if ("diagnostics".equals(field.getKey())) {
                    continue;
                }
                if (containsComponent(field.getValue(), componentId)) {
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

    private static boolean primaryComponentSatisfiedByCompositeMaterialization(
            String primaryComponent,
            JsonNode materialization) {
        if (isPageBuilderComponent(primaryComponent)) {
            return isPageBuilderMaterialization(materialization);
        }
        return false;
    }

    private static boolean isPageBuilderComponent(String primaryComponent) {
        String value = safe(primaryComponent);
        return "praxis-page-builder".equals(value) || "praxis-dynamic-page-builder".equals(value);
    }

    private static boolean isPageBuilderMaterialization(JsonNode materialization) {
        if (materialization == null || materialization.isMissingNode() || materialization.isNull()) {
            return false;
        }
        if ("praxis.ui-composition-plan".equals(safe(materialization.path("kind").asText("")))) {
            return true;
        }
        JsonNode page = materialization.path("patch").path("page");
        if (!page.isObject()) {
            page = materialization;
        }
        // The compiled authoring envelope preserves governance diagnostics while its canonical
        // page projection intentionally no longer carries the authoring-plan discriminator.
        return page.path("widgets").isArray()
                && page.path("canvas").isObject()
                && !safe(page.path("layoutPreset").asText("")).isBlank();
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
