package org.praxisplatform.config.dto;

import java.util.UUID;

public record DomainFederationIngestResponse(
        String schemaVersion,
        boolean dryRun,
        boolean valid,
        UUID releaseId,
        String releaseKey,
        String status,
        String payloadHash,
        DomainFederationIngestCountsResponse persistedCounts,
        DomainFederationValidationReport validation
) {
}
