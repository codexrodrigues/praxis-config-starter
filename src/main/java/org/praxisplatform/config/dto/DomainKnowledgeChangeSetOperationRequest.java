package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record DomainKnowledgeChangeSetOperationRequest(
        String operationId,
        String operationType,
        JsonNode target,
        String reason,
        List<String> evidenceRefs,
        Double confidence,
        JsonNode payload
) {
}
