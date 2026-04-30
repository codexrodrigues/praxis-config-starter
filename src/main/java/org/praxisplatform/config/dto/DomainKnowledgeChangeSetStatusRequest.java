package org.praxisplatform.config.dto;

public record DomainKnowledgeChangeSetStatusRequest(
        String status,
        String reviewerId,
        String reason
) {
}
