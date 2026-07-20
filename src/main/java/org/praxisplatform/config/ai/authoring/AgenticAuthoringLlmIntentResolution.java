package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
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
        JsonNode queryConstraints,
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
            String semanticIntentClass,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        this(
                resolved, operationKind, artifactKind, changeKind, selectedResourcePath,
                resourceSearchQuery, followUpKind, assistantMessage, quickReplies,
                clarificationQuestions, warnings, consultativeRetrievalPlan, visualizationDecision,
                requiresGovernedAuthoring, semanticIntentClass, null, providerInvocations);
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
                null,
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
                null,
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
                null,
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
                null,
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
                null,
                List.of());
    }
}
