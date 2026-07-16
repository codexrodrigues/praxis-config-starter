package org.praxisplatform.config.ai.authoring;

import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

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
        String semanticIntentClass,
        List<AiProviderInvocationTelemetry> providerInvocations
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
            boolean requiresGovernedAuthoring,
            String semanticIntentClass) {
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
                semanticIntentClass,
                List.of());
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
                "unknown",
                List.of());
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
                "unknown",
                List.of());
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
                "unknown",
                List.of());
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
                "unknown",
                List.of());
    }
}
