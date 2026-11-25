package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
        String visibility,
        String summaryCode,
        Map<String, Object> summaryArgs
) {

    public DomainRuleTimelineEventResponse {
        if (summaryArgs != null) {
            Map<String, Object> safeCopy = new LinkedHashMap<>();
            summaryArgs.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    safeCopy.put(key, value);
                }
            });
            summaryArgs = safeCopy.isEmpty() ? null : Collections.unmodifiableMap(safeCopy);
        }
    }

    public DomainRuleTimelineEventResponse(
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
            String visibility) {
        this(
                eventType,
                occurredAt,
                actorType,
                actor,
                summary,
                status,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                materializationId,
                materializationKey,
                sourceHash,
                visibility,
                null,
                null);
    }
}
