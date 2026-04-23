package org.praxisplatform.config.dto;

public record DomainFederationIngestPreviewItemResponse(
        String contextKey,
        String sourceKey,
        String serviceKey,
        String query,
        boolean previewAvailable,
        String previewError,
        DomainFederationContextQueryResponse contextPreview
) {
}
