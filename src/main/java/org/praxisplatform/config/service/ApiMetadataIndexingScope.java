package org.praxisplatform.config.service;

public record ApiMetadataIndexingScope(
        String tenantId,
        String environment,
        String serviceKey,
        String releaseId
) {
}
