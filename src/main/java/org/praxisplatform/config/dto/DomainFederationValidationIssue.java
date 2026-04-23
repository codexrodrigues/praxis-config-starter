package org.praxisplatform.config.dto;

public record DomainFederationValidationIssue(
        String severity,
        String code,
        String pointer,
        String message
) {
}
