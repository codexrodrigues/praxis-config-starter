package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringProjectKnowledgeQuery(
        String tenantId,
        String environment,
        String contextKey,
        String resourceKey,
        List<String> kinds,
        String nodeType,
        int limit,
        String semanticQuery
) {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    public AgenticAuthoringProjectKnowledgeQuery {
        tenantId = clean(tenantId);
        environment = clean(environment);
        contextKey = clean(contextKey);
        resourceKey = clean(resourceKey);
        nodeType = clean(nodeType);
        semanticQuery = clean(semanticQuery);
        kinds = kinds == null
                ? List.of()
                : kinds.stream()
                        .map(AgenticAuthoringProjectKnowledgeQuery::clean)
                        .filter(kind -> kind != null && !kind.isBlank())
                        .distinct()
                        .toList();
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
    }

    public AgenticAuthoringProjectKnowledgeQuery(
            String tenantId,
            String environment,
            String contextKey,
            String resourceKey,
            List<String> kinds,
            String nodeType,
            int limit) {
        this(tenantId, environment, contextKey, resourceKey, kinds, nodeType, limit, null);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
