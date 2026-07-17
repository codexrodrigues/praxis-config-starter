package org.praxisplatform.config.registry;

import java.time.Instant;

public record AiRegistryComponentDefinitionsChangedEvent(
        String releaseId,
        int componentCount,
        Instant occurredAt) {
}
