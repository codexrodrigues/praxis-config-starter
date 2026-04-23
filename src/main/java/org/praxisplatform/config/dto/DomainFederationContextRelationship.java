package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainFederationContextRelationship(
        String relationshipKey,
        String sourceContextKey,
        String targetContextKey,
        String relationshipType,
        String contractKey,
        String direction,
        String ownership,
        Double confidence,
        String status,
        JsonNode evidence
) {
}
