package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringLlmIntentResolution(
        boolean resolved,
        String operationKind,
        String artifactKind,
        String changeKind,
        String selectedResourcePath,
        String followUpKind,
        String assistantMessage,
        List<AgenticAuthoringQuickReply> quickReplies,
        List<String> clarificationQuestions,
        List<String> warnings
) {
}
