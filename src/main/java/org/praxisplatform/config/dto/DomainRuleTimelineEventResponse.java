package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

public record DomainRuleTimelineEventResponse(
        String eventType,
        Instant occurredAt,
        String actorType,
        String actor,
        String summary,
        String status,
        String targetLayer,
        String targetArtifactType,
        String targetArtifactKey,
        UUID materializationId,
        String materializationKey,
        String sourceHash,
        String visibility
) {
}
