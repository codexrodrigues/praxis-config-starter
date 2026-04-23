package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainFederationSource(
        String sourceKey,
        String sourceType,
        String serviceKey,
        String serviceName,
        String tenantId,
        String environment,
        String semanticOwner,
        String technicalOwner,
        String trustLevel,
        String status,
        String latestReleaseKey,
        JsonNode evidence
) {
}
