package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringConsultativeApiCatalogProjection(
        String query,
        String assistantMessage,
        List<Resource> resources,
        List<String> warnings
) {
    public boolean hasResources() {
        return resources != null && !resources.isEmpty();
    }

    public record Resource(
            String resourceKey,
            String resourcePath,
            String label,
            String role,
            String description,
            List<Field> fields,
            List<Action> actions,
            List<Endpoint> endpoints,
            List<String> evidence,
            List<QuickFilter> quickFilters
    ) {
        public Resource(
                String resourceKey,
                String resourcePath,
                String label,
                String role,
                String description,
                List<Field> fields,
                List<Action> actions,
                List<Endpoint> endpoints,
                List<String> evidence
        ) {
            this(resourceKey, resourcePath, label, role, description, fields, actions, endpoints, evidence, List.of());
        }
    }

    public record QuickFilter(
            String id,
            String label,
            String description,
            String tone,
            boolean isDefault,
            List<String> filterFields
    ) {
    }

    public record Field(
            String name,
            String label,
            String description
    ) {
    }

    public record Action(
            String name,
            String label,
            String description
    ) {
    }

    public record Endpoint(
            String kind,
            String method,
            String path,
            String label
    ) {
    }
}
