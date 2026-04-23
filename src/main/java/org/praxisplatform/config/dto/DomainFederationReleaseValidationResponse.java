package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainFederationReleaseValidationResponse(
        UUID id,
        String releaseKey,
        String tenantId,
        String environment,
        String status,
        String payloadHash,
        JsonNode validationReport,
        Instant createdAt
) {
}
