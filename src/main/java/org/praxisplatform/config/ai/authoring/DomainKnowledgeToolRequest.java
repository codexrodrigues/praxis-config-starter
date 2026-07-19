package org.praxisplatform.config.ai.authoring;

/**
 * Scope-only input for progressive Domain Knowledge discovery.
 *
 * <p>Tenant and environment deliberately come from the authenticated principal, never from the
 * model-authored tool payload.
 */
record DomainKnowledgeToolRequest(
        String contextKey,
        String resourceKey,
        int limit
) {
}
