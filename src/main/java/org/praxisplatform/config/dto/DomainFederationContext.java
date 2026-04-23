package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainFederationContext(
        String contextKey,
        String sourceKey,
        String contextType,
        String label,
        String description,
        String semanticOwner,
        String technicalOwner,
        String tenantId,
        String environment,
        String status,
        String latestReleaseKey,
        JsonNode evidence
) {
}
