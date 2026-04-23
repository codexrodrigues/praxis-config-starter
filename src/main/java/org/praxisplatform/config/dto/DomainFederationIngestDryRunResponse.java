package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationIngestDryRunResponse(
        String schemaVersion,
        boolean dryRun,
        boolean valid,
        int previewCount,
        DomainFederationValidationReport validation,
        List<DomainFederationIngestPreviewItemResponse> previews
) {
}
