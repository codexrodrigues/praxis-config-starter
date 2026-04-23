package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationContextQueryResponse(
        String schemaVersion,
        String tenantId,
        String environment,
        String serviceKey,
        String resourceKey,
        String query,
        String contextKey,
        String itemType,
        String nodeType,
        String relationshipType,
        int limit,
        boolean federated,
        List<String> retrievalGuidance,
        DomainCatalogContextResponse context,
        List<DomainCatalogItemResponse> relationships
) {
}
