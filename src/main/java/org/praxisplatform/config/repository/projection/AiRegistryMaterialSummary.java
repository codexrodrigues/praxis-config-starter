package org.praxisplatform.config.repository.projection;

import java.util.UUID;

/**
 * Lightweight registry projection for material comparison and obsolete-entry pruning.
 *
 * <p>The vector embedding is deliberately excluded. Bootstrap and reconciliation compare only
 * canonical identity and payload, so hydrating every persisted embedding adds substantial remote
 * database transfer without contributing to the decision.</p>
 */
public record AiRegistryMaterialSummary(
        UUID id,
        String registryKey,
        String payload) {
}
