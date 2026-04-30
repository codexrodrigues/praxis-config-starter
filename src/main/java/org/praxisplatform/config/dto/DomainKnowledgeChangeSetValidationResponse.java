package org.praxisplatform.config.dto;

import java.util.List;

public record DomainKnowledgeChangeSetValidationResponse(
        boolean valid,
        int errorCount,
        int warningCount,
        List<DomainKnowledgeChangeSetValidationIssue> issues
) {
}
