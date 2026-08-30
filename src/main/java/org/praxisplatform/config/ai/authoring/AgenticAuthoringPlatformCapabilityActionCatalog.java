package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical catalog for backend-issued continuations exposed by platform capability guidance.
 *
 * <p>The catalog is consulted only after semantic intent has resolved the turn as governed
 * platform guidance. Its identifiers are server-owned action references, not keywords or labels
 * parsed from user text.</p>
 */
final class AgenticAuthoringPlatformCapabilityActionCatalog {

    static final String SCHEMA_VERSION = "praxis-agentic-authoring-platform-action.v1";

    private static final Map<String, Action> ACTIONS = Map.of(
            "platform-create-admin-dashboard",
            new Action("create", "dashboard", "create_artifact", true, List.of()),
            "platform-create-requested-dashboard",
            new Action("create", "dashboard", "create_artifact", true, List.of()),
            "platform-refine-dashboard-metrics",
            new Action("explore", "dashboard", "recommend_dashboard_visualization", true, List.of()),
            "platform-review-dashboard-data",
            new Action("explore", "api_catalog", "answer_api_catalog_question", true, List.of()),
            "platform-create-form",
            new Action("create", "form", "create_artifact", true, List.of()),
            "platform-explore-components",
            new Action("explain", "component", "answer_component_catalog_question", false, List.of()));

    private AgenticAuthoringPlatformCapabilityActionCatalog() {
    }

    static Optional<Action> find(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTIONS.get(actionId.trim()));
    }

    record Action(
            String operationKind,
            String artifactKind,
            String changeKind,
            boolean resourceGroundingRequired,
            List<String> conceptKeys) {

        Action {
            operationKind = safe(operationKind);
            artifactKind = safe(artifactKind);
            changeKind = safe(changeKind);
            conceptKeys = conceptKeys == null ? List.of() : List.copyOf(conceptKeys);
            if (operationKind.isBlank() || artifactKind.isBlank() || changeKind.isBlank()) {
                throw new IllegalArgumentException("Platform capability actions require complete semantic coordinates.");
            }
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
