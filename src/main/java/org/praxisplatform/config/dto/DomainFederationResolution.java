package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainFederationResolution(
        String resolutionKey,
        String sourceConceptKey,
        String targetConceptKey,
        String sourceContextKey,
        String targetContextKey,
        String resolutionType,
        Double confidence,
        String status,
        String reviewOwner,
        JsonNode evidence
) {
}
