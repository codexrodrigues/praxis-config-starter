package org.praxisplatform.config.dto;

import java.util.List;

public record DomainKnowledgeChangeSetValidationResponse(
        boolean valid,
        int errorCount,
        int warningCount,
        List<DomainKnowledgeChangeSetValidationIssue> issues,
        List<String> proposedOperationTypes,
        List<String> executableOperationTypes,
        List<String> executablePatchOperationTypes,
        List<String> nonExecutableOperationTypes
) {
    public DomainKnowledgeChangeSetValidationResponse(
            boolean valid,
            int errorCount,
            int warningCount,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        this(valid, errorCount, warningCount, issues, List.of(), List.of(), List.of(), List.of());
    }

    public DomainKnowledgeChangeSetValidationResponse {
        issues = issues == null ? List.of() : List.copyOf(issues);
        proposedOperationTypes = proposedOperationTypes == null ? List.of() : List.copyOf(proposedOperationTypes);
        executableOperationTypes = executableOperationTypes == null ? List.of() : List.copyOf(executableOperationTypes);
        executablePatchOperationTypes = executablePatchOperationTypes == null ? List.of() : List.copyOf(executablePatchOperationTypes);
        nonExecutableOperationTypes = nonExecutableOperationTypes == null ? List.of() : List.copyOf(nonExecutableOperationTypes);
    }
}
