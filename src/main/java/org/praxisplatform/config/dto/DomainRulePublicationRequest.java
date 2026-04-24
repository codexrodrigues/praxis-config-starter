package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record DomainRulePublicationRequest(
        UUID ruleDefinitionId,
        List<UUID> materializationIds,
        Boolean applyEligibleMaterializations,
        String publishedByType,
        String publishedBy,
        JsonNode publicationNotes
) {
}
