package org.praxisplatform.config.dto;

public record ApiMetadataRagReconcileResponse(
        String schemaVersion,
        String tenantId,
        String environment,
        String serviceKey,
        String releaseId,
        boolean ragPublicationEnabled,
        boolean vectorStoreAvailable,
        long expectedDocumentCount,
        long publishedDocumentCount,
        ApiMetadataRagStatusResponse status
) {
}
