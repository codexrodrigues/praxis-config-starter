package org.praxisplatform.config.ai.authoring;

import java.util.List;

final class AgenticAuthoringSemanticDecisionPolicy {

    AgenticAuthoringSemanticDecision apply(Input input) {
        if (input == null) {
            return null;
        }
        String operationKind = valueOrDefault(input.operationKind(), "unknown");
        String artifactKind = valueOrDefault(input.artifactKind(), "unknown");
        String changeKind = valueOrDefault(input.changeKind(), "unknown");
        if ("provider_error".equals(changeKind)) {
            return new AgenticAuthoringSemanticDecision("unknown", "unknown", "provider_error", null);
        }
        AgenticAuthoringCandidate selectedCandidate = input.selectedCandidate();
        List<AgenticAuthoringCandidate> candidates = input.candidates() == null ? List.of() : input.candidates();
        if (isCanonicalSingleTableDecision(input.llmIntent())) {
            return new AgenticAuthoringSemanticDecision(
                    operationKind,
                    artifactKind,
                    changeKind,
                    selectedCandidate);
        }
        boolean optionalDataSourceHint = input.optionalDataSourceHint();
        boolean dataSourceChoiceAnswer = input.resourceChoiceClarificationAnswer();
        boolean llmResolved = input.llmIntent() != null && input.llmIntent().resolved();
        boolean llmExplicitlySelectedResource = llmResolved
                && selectedCandidate != null
                && input.llmIntent().selectedResourcePath() != null
                && !input.llmIntent().selectedResourcePath().isBlank();

        if (optionalDataSourceHint) {
            selectedCandidate = null;
        }
        if (AgenticAuthoringIntentCandidateScoringSupport.isBareDomainPrompt(input.prompt())
                && !llmExplicitlySelectedResource) {
            selectedCandidate = null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && !llmExplicitlySelectedResource
                && (!llmResolved || candidates.size() > 1)
                && input.contextHintCandidate() == null) {
            selectedCandidate = null;
        }
        if ("form".equals(artifactKind)
                && "create".equals(operationKind)
                && selectedCandidate != null
                && candidates.size() > 1
                && isBroadArtifactDiscoveryOnly(candidates)) {
            selectedCandidate = null;
        }

        if (!optionalDataSourceHint
                && input.governedResourceConfirmation()
                && (input.contextHintCandidate() != null || selectedCandidate != null)) {
            selectedCandidate = input.contextHintCandidate() != null ? input.contextHintCandidate() : selectedCandidate;
            artifactKind = valueOrDefault(input.contextHintArtifactKind(), "dashboard");
            operationKind = valueOrDefault(input.contextHintOperationKind(), "create");
            changeKind = valueOrDefault(input.contextHintChangeKind(), "create_artifact");
        } else if (dataSourceChoiceAnswer) {
            selectedCandidate = selectedCandidate != null ? selectedCandidate : input.contextHintCandidate();
            artifactKind = "dashboard";
            operationKind = "explore";
            changeKind = "recommend_dashboard_visualization";
        }
        return new AgenticAuthoringSemanticDecision(operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean isCanonicalSingleTableDecision(AgenticAuthoringLlmIntentResolution llmIntent) {
        if (llmIntent == null || !llmIntent.resolved() || llmIntent.visualizationDecision() == null) {
            return false;
        }
        AgenticAuthoringVisualizationDecision decision = llmIntent.visualizationDecision();
        return "table".equals(llmIntent.artifactKind())
                && "single-table".equals(decision.layoutKind())
                && "praxis-table".equals(decision.primaryComponent());
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    record Input(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate contextHintCandidate,
            AgenticAuthoringLlmIntentResolution llmIntent,
            boolean optionalDataSourceHint,
            boolean resourceChoiceClarificationAnswer,
            boolean governedResourceConfirmation,
            String contextHintOperationKind,
            String contextHintArtifactKind,
            String contextHintChangeKind) {
    }

    record AgenticAuthoringSemanticDecision(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
    }
}
