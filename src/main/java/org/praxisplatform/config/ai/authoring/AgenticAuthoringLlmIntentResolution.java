package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringLlmIntentResolution(
        boolean resolved,
        String operationKind,
        String artifactKind,
        String changeKind,
        String selectedResourcePath,
        String resourceSearchQuery,
        String followUpKind,
        String assistantMessage,
        List<AgenticAuthoringQuickReply> quickReplies,
        List<String> clarificationQuestions,
        List<String> warnings,
        AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan,
        AgenticAuthoringVisualizationDecision visualizationDecision,
        boolean requiresGovernedAuthoring
) {
    public AgenticAuthoringLlmIntentResolution(
            boolean resolved,
            String operationKind,
            String artifactKind,
            String changeKind,
            String selectedResourcePath,
            String resourceSearchQuery,
            String followUpKind,
            String assistantMessage,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> clarificationQuestions,
            List<String> warnings,
            AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        this(
                resolved,
                operationKind,
                artifactKind,
                changeKind,
                selectedResourcePath,
                resourceSearchQuery,
                followUpKind,
                assistantMessage,
                quickReplies,
                clarificationQuestions,
                warnings,
                consultativeRetrievalPlan,
                visualizationDecision,
                false);
    }

    public AgenticAuthoringLlmIntentResolution(
            boolean resolved,
            String operationKind,
            String artifactKind,
            String changeKind,
            String selectedResourcePath,
            String resourceSearchQuery,
            String followUpKind,
            String assistantMessage,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> clarificationQuestions,
            List<String> warnings) {
        this(
                resolved,
                operationKind,
                artifactKind,
                changeKind,
                selectedResourcePath,
                resourceSearchQuery,
                followUpKind,
                assistantMessage,
                quickReplies,
                clarificationQuestions,
                warnings,
                null,
                null,
                false);
    }

    public AgenticAuthoringLlmIntentResolution(
            boolean resolved,
            String operationKind,
            String artifactKind,
            String changeKind,
            String selectedResourcePath,
            String resourceSearchQuery,
            String followUpKind,
            String assistantMessage,
            List<AgenticAuthoringQuickReply> quickReplies,
            List<String> clarificationQuestions,
            List<String> warnings,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        this(
                resolved,
                operationKind,
                artifactKind,
                changeKind,
                selectedResourcePath,
                resourceSearchQuery,
                followUpKind,
                assistantMessage,
                quickReplies,
                clarificationQuestions,
                warnings,
                null,
                visualizationDecision,
                false);
    }
}
