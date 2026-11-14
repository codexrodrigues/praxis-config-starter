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
            List<String> filterFields,
            java.util.Map<String, Object> filter
    ) {
        public QuickFilter(
                String id,
                String label,
                String description,
                String tone,
                boolean isDefault,
                List<String> filterFields
        ) {
            this(id, label, description, tone, isDefault, filterFields, java.util.Map.of());
        }

        public QuickFilter {
            filterFields = filterFields == null ? List.of() : List.copyOf(filterFields);
            filter = filter == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filter));
        }
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
