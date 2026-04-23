package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record DomainRuleMaterializationResponse(
        UUID id,
        String tenantId,
        String environment,
        UUID ruleDefinitionId,
        String ruleKey,
        Integer ruleVersion,
        String materializationKey,
        String targetLayer,
        String targetArtifactType,
        String targetArtifactKey,
        String targetPointer,
        String targetReleaseKey,
        String materializedRuleId,
        String status,
        JsonNode materializedPayload,
        String sourceHash,
        JsonNode validationResult,
        String appliedByType,
        String appliedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant appliedAt
) {
}
