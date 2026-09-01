package org.praxisplatform.config.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for catalog release discovery.
 *
 * <p>The immutable raw catalog payload is deliberately excluded. Runtime discovery only needs
 * release identity and provenance; hydrating {@code raw_payload} for every historical resource
 * release can transfer hundreds of megabytes before semantic orientation starts.</p>
 */
public record DomainCatalogReleaseSummary(
        UUID id,
        String releaseKey,
        String schemaVersion,
        String serviceKey,
        String serviceName,
        String serviceVersion,
        String resourceKey,
        Instant generatedAt,
        String sourceHash,
        String tenantId,
        String environment,
        Instant createdAt) {
}
