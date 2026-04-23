package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record DomainRuleMaterializationRequest(
        UUID ruleDefinitionId,
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
        String appliedBy
) {
}
