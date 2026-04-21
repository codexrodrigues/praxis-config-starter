package org.praxisplatform.config.dto;

import java.util.UUID;

public record DomainCatalogIngestionResponse(
    UUID releaseId,
    String releaseKey,
    int itemCount
) {
}
