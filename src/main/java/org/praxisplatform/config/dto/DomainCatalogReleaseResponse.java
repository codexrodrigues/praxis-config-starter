package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

public record DomainCatalogReleaseResponse(
        UUID id,
        String releaseKey,
        String schemaVersion,
        String serviceKey,
        String serviceName,
        String serviceVersion,
        Instant generatedAt,
        String sourceHash,
        String tenantId,
        String environment,
        Instant createdAt
) {
}
