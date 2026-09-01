package org.praxisplatform.config.repository.projection;

import java.util.UUID;

/** Lightweight item projection that carries release identity without loading its raw payload. */
public record DomainCatalogItemSummary(
        UUID id,
        String releaseKey,
        String itemType,
        String itemKey,
        String contextKey,
        String nodeType,
        String bindingType,
        String edgeType,
        String payload) {
}
