package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;

public record DomainKnowledgeChangeSetTimelineResponse(
        UUID changeSetId,
        String tenantId,
        String environment,
        String changeSetKey,
        String status,
        String authorType,
        String authorId,
        String reviewerId,
        List<DomainKnowledgeChangeSetTimelineEventResponse> events
) {
}
