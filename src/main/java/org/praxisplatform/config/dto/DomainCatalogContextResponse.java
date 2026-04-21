package org.praxisplatform.config.dto;

import java.util.List;

public record DomainCatalogContextResponse(
        String schemaVersion,
        DomainCatalogReleaseResponse release,
        String query,
        String itemType,
        String contextKey,
        String nodeType,
        List<String> retrievalGuidance,
        List<DomainCatalogItemResponse> items
) {
}
