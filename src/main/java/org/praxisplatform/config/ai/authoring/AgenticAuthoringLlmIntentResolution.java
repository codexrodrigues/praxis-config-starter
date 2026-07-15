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
        boolean requiresGovernedAuthoring,
        String semanticIntentClass
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
            AgenticAuthoringVisualizationDecision visualizationDecision,
            boolean requiresGovernedAuthoring) {
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
                requiresGovernedAuthoring,
                "unknown");
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
                false,
                "unknown");
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
                false,
                "unknown");
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
                false,
                "unknown");
    }
}
