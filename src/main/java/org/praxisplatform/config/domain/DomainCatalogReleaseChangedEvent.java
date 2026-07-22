package org.praxisplatform.config.domain;

import java.time.Instant;

/** Internal signal that derived domain-catalog projections must be refreshed. */
public record DomainCatalogReleaseChangedEvent(
        String tenantId,
        String environment,
        String serviceKey,
        String resourceKey,
        String releaseKey,
        Instant occurredAt) {
}
