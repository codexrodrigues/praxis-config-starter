package org.praxisplatform.config.dto;

import java.util.List;

public record DomainKnowledgeChangeSetOperationSummary(
        String operationId,
        String operationType,
        List<String> targetConceptKeys
) {
}
