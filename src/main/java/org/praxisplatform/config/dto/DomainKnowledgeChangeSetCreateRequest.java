package org.praxisplatform.config.dto;

import java.util.List;

public record DomainKnowledgeChangeSetCreateRequest(
        String changeSetKey,
        String status,
        String authorType,
        String authorId,
        String intent,
        String reason,
        List<DomainKnowledgeChangeSetOperationRequest> patch
) {
}
