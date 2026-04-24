package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DomainRulePublicationResponse(
        UUID publicationId,
        String tenantId,
        String environment,
        String publicationStatus,
        String publicationReadiness,
        UUID ruleDefinitionId,
        String ruleKey,
        Integer ruleVersion,
        String ruleType,
        String resourceKey,
        String serviceKey,
        DomainRuleDefinitionResponse definition,
        List<DomainRuleMaterializationResponse> materializations,
        JsonNode explainability,
        Instant processedAt
) {
}
