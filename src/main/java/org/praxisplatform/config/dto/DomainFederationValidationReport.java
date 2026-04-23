package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationValidationReport(
        boolean valid,
        int errorCount,
        int warningCount,
        List<DomainFederationValidationIssue> issues
) {
}
