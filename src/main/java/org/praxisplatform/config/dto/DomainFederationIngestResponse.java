package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;

public record DomainFederationIngestResponse(
        String schemaVersion,
        boolean dryRun,
        boolean valid,
        boolean persisted,
        UUID releaseId,
        String releaseKey,
        int itemCount,
        DomainFederationValidationReport validation,
        List<DomainFederationIngestPreviewItemResponse> previews
) {
}
