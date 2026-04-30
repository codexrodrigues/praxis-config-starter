package org.praxisplatform.config.dto;

public record DomainKnowledgeChangeSetValidationIssue(
        String severity,
        String code,
        String pointer,
        String message
) {
}
