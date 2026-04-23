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
        String sourceMode,
        List<String> retrievalGuidance,
        DomainFederationRetrievalPolicyReport policyReport,
        DomainCatalogContextResponse context,
        List<DomainCatalogItemResponse> relationships
) {
}
