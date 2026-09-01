package org.praxisplatform.config.projection;

/**
 * Lightweight API candidate evidence for broad post-intent discovery.
 *
 * <p>This projection retains the compact semantic identity used by governed candidate ranking
 * while avoiding hydration and wire transfer of embeddings, raw OpenAPI fragments, schemas, and
 * parameters for the complete catalog. Exact candidates can still be hydrated by path and method
 * when their detailed evidence is required.</p>
 */
public interface ApiMetadataCandidateProjection extends ApiMetadataCandidateEvidence {
}
