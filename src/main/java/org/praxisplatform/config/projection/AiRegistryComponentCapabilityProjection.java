package org.praxisplatform.config.projection;

/**
 * Minimal database projection used to materialize the governed component capability catalog.
 * Large config schemas, embeddings and unrelated registry metadata remain inside PostgreSQL.
 */
public record AiRegistryComponentCapabilityProjection(
        String registryKey,
        String componentDescription,
        String friendlyName,
        String selector,
        String tagsJson,
        String authoringManifestJson) {
}
