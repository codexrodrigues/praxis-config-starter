package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reconciles pre-intent semantic orientation plans ({@link AgenticAuthoringPreIntentToolPlan})
 * with post-retrieval LLM intent resolutions ({@link AgenticAuthoringLlmIntentResolution}),
 * including constrained query predicates, primary component compatibility, parent-child target
 * selection, and approval/action workbench layout continuity.
 */
public final class AgenticAuthoringSemanticOrientationReconciler {

    public static final AgenticAuthoringSemanticOrientationReconciler INSTANCE =
            new AgenticAuthoringSemanticOrientationReconciler();

    public AgenticAuthoringVisualizationDecision preIntentVisualizationDecision(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        String primaryComponent = semanticOrientation == null
                ? ""
                : valueOrDefault(semanticOrientation.primaryComponent(), "");
        String authoredLayoutKind = semanticOrientation == null
                ? ""
                : valueOrDefault(semanticOrientation.layoutKind(), "");
        if (primaryComponent.isBlank() && authoredLayoutKind.isBlank()) {
            return null;
        }
        boolean dashboardArtifact = semanticOrientation != null
                && "dashboard".equalsIgnoreCase(valueOrDefault(semanticOrientation.artifactKind(), ""));
        String layoutKind = !authoredLayoutKind.isBlank() ? authoredLayoutKind : switch (primaryComponent) {
            case "praxis-crud" -> "resource-crud";
            case "praxis-table" -> "single-table";
            case "praxis-dynamic-form" -> "single-form";
            case "praxis-chart" -> dashboardArtifact ? "resource-dashboard" : "single-chart";
            default -> dashboardArtifact ? "resource-dashboard" : "single-component";
        };
        boolean resourceDashboard = "resource-dashboard".equals(layoutKind);
        boolean detailTable = resourceDashboard
                || "resource-master-detail".equals(layoutKind)
                || "approval-workbench".equals(layoutKind)
                || "resource-crud".equals(layoutKind)
                || "praxis-crud".equals(primaryComponent)
                || "praxis-table".equals(primaryComponent);
        return new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "pre-intent-semantic-composition",
                layoutKind,
                primaryComponent.isBlank() && resourceDashboard ? "praxis-chart" : primaryComponent,
                List.of(),
                resourceDashboard,
                detailTable,
                List.of(),
                true,
                resourceDashboard,
                "llm-pre-intent-semantic-orientation");
    }

    public boolean requiresAdditionalIntentResolution(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientation != null
                && (requiresGovernedRelatedResourceTargetSelection(semanticOrientation)
                        || semanticOrientation.requiresFullIntentResolution());
    }

    public boolean requiresGovernedRelatedResourceTargetSelection(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientation != null
                && "authoring_or_other".equals(semanticOrientation.semanticIntentClass())
                && "page".equals(semanticOrientation.artifactKind())
                && "parent-child-related-resource".equals(semanticOrientation.layoutKind())
                && "praxis-related-resource-outlet".equals(semanticOrientation.primaryComponent());
    }

    public boolean hasCompleteCompactResourceComposition(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (semanticOrientation == null
                || !"authoring_or_other".equals(semanticOrientation.semanticIntentClass())) {
            return false;
        }
        JsonNode constraints = semanticOrientation.queryConstraints();
        if (constraints == null
                || !constraints.path("appliesToDataSelection").isBoolean()
                || !constraints.path("filters").isArray()) {
            return false;
        }
        if (constraints.path("appliesToDataSelection").asBoolean(false)
                && constraints.path("filters").isEmpty()) {
            return false;
        }
        return switch (valueOrDefault(semanticOrientation.layoutKind(), "")) {
            case "single-table" -> "table".equals(semanticOrientation.artifactKind())
                    && "praxis-table".equals(semanticOrientation.primaryComponent());
            case "resource-master-detail", "approval-workbench" -> "page".equals(semanticOrientation.artifactKind())
                    && "praxis-table".equals(semanticOrientation.primaryComponent());
            case "resource-crud" -> "page".equals(semanticOrientation.artifactKind())
                    && "praxis-crud".equals(semanticOrientation.primaryComponent());
            case "tabs_layout" -> "page".equals(semanticOrientation.artifactKind())
                    && "praxis-tabs".equals(semanticOrientation.primaryComponent());
            default -> false;
        };
    }

    public AgenticAuthoringLlmIntentResolution preIntentSemanticOrientationResolution(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (!shouldResolveLlmIntent || semanticOrientation == null) {
            return null;
        }
        if (semanticOrientation.resolvesGovernedDomainDiscovery()
                && hasProgressiveGovernedDomainKnowledge(request)) {
            return new AgenticAuthoringLlmIntentResolution(
                    true,
                    "explore",
                    "api_catalog",
                    "answer_api_catalog_question",
                    null,
                    null,
                    "none",
                    null,
                    List.of(),
                    List.of(),
                    List.of(
                            "llm-semantic-orientation-used",
                            "llm-governed-domain-discovery-grounded-by-project-knowledge"),
                    null,
                    null,
                    false,
                    "domain_knowledge");
        }
        if (!semanticOrientation.resolvesPlatformGuidance()) {
            return null;
        }
        return new AgenticAuthoringLlmIntentResolution(
                true,
                "explain",
                "component",
                "answer_component_catalog_question",
                null,
                null,
                "none",
                semanticOrientation.assistantMessage(),
                List.of(),
                List.of(),
                List.of(
                        "llm-semantic-orientation-used",
                        "llm-platform-guidance-grounded-by-platform-domain-surface"),
                null,
                null,
                false,
                "platform_guidance");
    }

    public AgenticAuthoringLlmIntentResolution normalizeConstrainedAuthoringResolution(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            List<AgenticAuthoringCandidate> candidates) {
        JsonNode governedConstraints = resolution == null ? null : resolution.queryConstraints();
        String governedArtifactKind = constrainedOrientationArtifactKind(semanticOrientation, resolution);
        if (resolution == null
                || !resolution.resolved()
                || !"component_authoring".equals(resolution.semanticIntentClass())
                || !"create".equals(resolution.operationKind())
                || resolution.requiresGovernedAuthoring()
                || (resolution.clarificationQuestions() != null && !resolution.clarificationQuestions().isEmpty())
                || semanticOrientation == null
                || !semanticOrientation.requiresFullIntentResolution()
                || !"authoring_or_other".equals(semanticOrientation.semanticIntentClass())
                || candidates == null
                || candidates.size() != 1
                || !normalizePath(candidates.get(0).resourcePath()).equals(normalizePath(resolution.selectedResourcePath()))
                || !List.of("table", "page", "dashboard", "chart").contains(governedArtifactKind)) {
            return resolution;
        }
        List<String> warnings = new ArrayList<>(resolution.warnings() == null ? List.of() : resolution.warnings());
        if (!preservesPreIntentQueryConstraints(semanticOrientation.queryConstraints(), governedConstraints)) {
            warnings.add("llm-pre-intent-query-constraints-not-preserved");
            return new AgenticAuthoringLlmIntentResolution(
                    false, "unknown", "unknown", "needs_clarification", null,
                    resolution.resourceSearchQuery(), resolution.followUpKind(),
                    "Não consegui preservar todos os filtros solicitados. A prévia permanece bloqueada para revisão.",
                    List.of(), List.of(), List.copyOf(warnings), resolution.consultativeRetrievalPlan(),
                    resolution.visualizationDecision(), false, "unknown", governedConstraints,
                    resolution.providerInvocations());
        }
        if (governedConstraints == null || !governedConstraints.path("filters").isArray()
                || governedConstraints.path("filters").isEmpty()) {
            return resolution;
        }
        if (!warnings.contains("llm-constrained-authoring-resource-confirmation-normalized")) {
            warnings.add("llm-constrained-authoring-resource-confirmation-normalized");
        }
        AgenticAuthoringVisualizationDecision visualizationDecision =
                reconcileConstrainedPrimaryComponent(resolution, semanticOrientation);
        if (visualizationDecision != resolution.visualizationDecision()
                && !warnings.contains("llm-pre-intent-primary-component-reconciled")) {
            warnings.add("llm-pre-intent-primary-component-reconciled");
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                visualizationDecision,
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                governedConstraints,
                resolution.providerInvocations());
    }

    public boolean preservesPreIntentQueryConstraints(JsonNode planned, JsonNode resolved) {
        if (planned == null || !planned.path("filters").isArray() || planned.path("filters").isEmpty()) {
            return true;
        }
        if (resolved == null || !resolved.path("filters").isArray()
                || (planned.path("appliesToDataSelection").asBoolean(false)
                        && !resolved.path("appliesToDataSelection").asBoolean(false))) {
            return false;
        }
        for (JsonNode predicate : planned.path("filters")) {
            boolean retained = false;
            for (JsonNode candidate : resolved.path("filters")) {
                boolean sameIdentity = List.of("field", "concept", "fieldSemantic").stream()
                        .anyMatch(key -> !predicate.path(key).asText("").isBlank()
                                && predicate.path(key).equals(candidate.path(key)));
                if (sameIdentity && predicate.path("operator").equals(candidate.path("operator"))
                        && predicate.path("value").equals(candidate.path("value"))) {
                    retained = true;
                    break;
                }
            }
            if (!retained) {
                return false;
            }
        }
        return true;
    }

    public AgenticAuthoringVisualizationDecision reconcileConstrainedPrimaryComponent(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (resolution == null || semanticOrientation == null) {
            return resolution == null ? null : resolution.visualizationDecision();
        }
        AgenticAuthoringVisualizationDecision oriented = preIntentVisualizationDecision(semanticOrientation);
        String orientedPrimaryComponent = oriented == null
                ? ""
                : valueOrDefault(oriented.primaryComponent(), "");
        String orientedLayoutKind = oriented == null
                ? ""
                : valueOrDefault(oriented.layoutKind(), "");
        String artifactKind = constrainedOrientationArtifactKind(semanticOrientation, resolution);
        if (!orientedPrimaryComponent.isBlank()
                && !isCompatiblePrimaryComponent(artifactKind, orientedPrimaryComponent)) {
            return resolution.visualizationDecision();
        }
        AgenticAuthoringVisualizationDecision current = resolution.visualizationDecision();
        if (orientedPrimaryComponent.isBlank() && orientedLayoutKind.isBlank()) {
            return current;
        }
        if (current != null
                && (orientedPrimaryComponent.isBlank()
                        || orientedPrimaryComponent.equals(current.primaryComponent()))
                && (orientedLayoutKind.isBlank()
                        || orientedLayoutKind.equals(current.layoutKind()))) {
            return current;
        }
        if (oriented == null) {
            return current;
        }
        String effectivePrimaryComponent = orientedPrimaryComponent.isBlank()
                ? current == null ? oriented.primaryComponent() : current.primaryComponent()
                : orientedPrimaryComponent;
        List<AgenticAuthoringVisualizationAxisDecision> reconciledAxes =
                ("dashboard".equals(artifactKind)
                        || "chart".equals(artifactKind)
                        || "praxis-chart".equals(effectivePrimaryComponent))
                        && current != null
                        && current.axes() != null
                        ? current.axes()
                        : List.of();
        return new AgenticAuthoringVisualizationDecision(
                current == null ? oriented.schemaVersion() : current.schemaVersion(),
                current == null || valueOrDefault(current.intent(), "").isBlank()
                        ? oriented.intent()
                        : current.intent(),
                orientedLayoutKind.isBlank()
                        ? current == null ? oriented.layoutKind() : current.layoutKind()
                        : orientedLayoutKind,
                effectivePrimaryComponent,
                reconciledAxes,
                oriented.includeSummary(),
                oriented.includeDetailTable(),
                current == null ? oriented.excludedComponentIds() : current.excludedComponentIds(),
                oriented.includeFilters(),
                oriented.includeKpis(),
                "llm-pre-intent-semantic-orientation+full-intent-reconciliation",
                current == null ? oriented.targetSurfaceId() : current.targetSurfaceId());
    }

    public boolean isCompatiblePrimaryComponent(String artifactKind, String primaryComponent) {
        return switch (valueOrDefault(artifactKind, "")) {
            case "page" -> Set.of(
                    "praxis-crud",
                    "praxis-table",
                    "praxis-dynamic-form",
                    "praxis-list",
                    "praxis-tabs",
                    "praxis-expansion",
                    "praxis-related-resource-outlet").contains(primaryComponent);
            case "table" -> "praxis-table".equals(primaryComponent);
            case "form" -> "praxis-dynamic-form".equals(primaryComponent);
            case "chart", "dashboard" -> "praxis-chart".equals(primaryComponent);
            default -> false;
        };
    }

    public String constrainedOrientationArtifactKind(
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            AgenticAuthoringLlmIntentResolution resolution) {
        if (semanticOrientation != null
                && List.of("table", "page", "dashboard", "chart").contains(semanticOrientation.artifactKind())) {
            return semanticOrientation.artifactKind();
        }
        if (semanticOrientation != null && semanticOrientation.toolCalls() != null) {
            for (AgenticAuthoringToolCall toolCall : semanticOrientation.toolCalls()) {
                if (toolCall != null
                        && toolCall.payload() instanceof AgenticAuthoringResourceCandidatesRequest resourceRequest
                        && List.of("table", "page", "dashboard", "chart").contains(resourceRequest.artifactKind())) {
                    return resourceRequest.artifactKind();
                }
            }
        }
        return resolution == null ? "unknown" : resolution.artifactKind();
    }

    public boolean hasProgressiveGovernedDomainKnowledge(AgenticAuthoringIntentResolutionRequest request) {
        if (request == null || request.contextHints() == null) {
            return false;
        }
        JsonNode projectKnowledge = request.contextHints().path("projectKnowledge");
        return "domain_knowledge_concept".equals(projectKnowledge.path("source").asText(""))
                && projectKnowledge.path("entries").isArray()
                && !projectKnowledge.path("entries").isEmpty();
    }

    public AgenticAuthoringLlmIntentResolution reconcilePreIntentWorkbenchOrientation(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (resolution == null
                || !resolution.resolved()
                || !"component_authoring".equals(resolution.semanticIntentClass())
                || !"create".equals(resolution.operationKind())
                || semanticOrientation == null) {
            return resolution;
        }
        String orientedLayoutKind = valueOrDefault(semanticOrientation.layoutKind(), "");
        if (!Set.of("approval-workbench", "resource-action-workbench", "workflow-action-workbench")
                .contains(orientedLayoutKind)) {
            return resolution;
        }
        AgenticAuthoringVisualizationDecision current = resolution.visualizationDecision();
        String currentLayout = current == null ? "" : valueOrDefault(current.layoutKind(), "");
        if (!currentLayout.isBlank()
                && !"resource-master-detail".equals(currentLayout)
                && !"single-table".equals(currentLayout)) {
            return resolution;
        }
        AgenticAuthoringVisualizationDecision reconciled = new AgenticAuthoringVisualizationDecision(
                current == null || valueOrDefault(current.schemaVersion(), "").isBlank()
                        ? "praxis-agentic-authoring-visualization-decision.v1"
                        : current.schemaVersion(),
                current == null || valueOrDefault(current.intent(), "").isBlank()
                        ? "create_page"
                        : current.intent(),
                orientedLayoutKind,
                "praxis-table",
                current == null || current.axes() == null ? List.of() : current.axes(),
                false,
                true,
                current == null || current.excludedComponentIds() == null ? List.of() : current.excludedComponentIds(),
                current == null ? true : current.includeFilters(),
                false,
                "llm-pre-intent-workbench-orientation-reconciled",
                current == null ? null : current.targetSurfaceId());
        List<String> warnings = new ArrayList<>(resolution.warnings() == null ? List.of() : resolution.warnings());
        if (!warnings.contains("llm-pre-intent-workbench-orientation-reconciled")) {
            warnings.add("llm-pre-intent-workbench-orientation-reconciled");
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                "page",
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                reconciled,
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
