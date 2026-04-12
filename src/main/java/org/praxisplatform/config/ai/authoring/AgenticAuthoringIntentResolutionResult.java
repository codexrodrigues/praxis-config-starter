package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringIntentResolutionResult(
        boolean valid,
        String operationKind,
        String artifactKind,
        String changeKind,
        String authoringProfile,
        String targetApp,
        String targetComponentId,
        AgenticAuthoringTarget target,
        AgenticAuthoringCandidate selectedCandidate,
        List<AgenticAuthoringCandidate> candidates,
        AgenticAuthoringGateResult gate,
        List<String> clarificationQuestions,
        List<String> warnings,
        List<String> failureCodes,
        JsonNode currentPageSummary
) {
}
