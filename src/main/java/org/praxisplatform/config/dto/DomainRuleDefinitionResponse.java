package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleDefinitionResponse(
        UUID id,
        String tenantId,
        String environment,
        String ruleKey,
        Integer version,
        String ruleType,
        String status,
        String contextKey,
        String resourceKey,
        String serviceKey,
        String semanticOwner,
        String steward,
        UUID sourceReleaseId,
        UUID sourceChangeSetId,
        JsonNode definition,
        JsonNode parameters,
        JsonNode condition,
        JsonNode governance,
        JsonNode validationResult,
        String createdByType,
        String createdBy,
        String approvedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant approvedAt,
        Instant activatedAt
) {
}
