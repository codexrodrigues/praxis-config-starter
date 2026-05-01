package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record DomainKnowledgeChangeSetTimelineEventResponse(
        String eventType,
        Instant occurredAt,
        String actorType,
        String actor,
        String summary,
        String status,
        String validationStatus,
        int operationCount,
        List<String> operationTypes,
        List<String> targetConceptKeys,
        String visibility
) {
}
