package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

public record DomainFederationReleaseResponse(
        UUID id,
        String releaseKey,
        String tenantId,
        String environment,
        String status,
        String payloadHash,
        String createdBy,
        Instant createdAt,
        Instant activatedAt
) {
}
