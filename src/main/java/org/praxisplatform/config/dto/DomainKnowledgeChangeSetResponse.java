package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DomainKnowledgeChangeSetResponse(
        UUID id,
        String tenantId,
        String environment,
        String changeSetKey,
        String status,
        String authorType,
        String authorId,
        String reviewerId,
        String intent,
        String reason,
        int operationCount,
        String validationStatus,
        List<DomainKnowledgeChangeSetOperationSummary> safeOperationSummary,
        JsonNode validationResult,
        Instant createdAt,
        Instant reviewedAt,
        Instant appliedAt
) {
}
