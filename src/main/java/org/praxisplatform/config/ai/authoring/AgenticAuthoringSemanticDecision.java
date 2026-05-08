package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Canonical semantic authoring decision produced before UI materialization.
 *
 * <p>Legacy fields such as operationKind, artifactKind, changeKind and selectedCandidate remain
 * response projections. New orchestration, preview and apply gates should use this decision as the
 * governed source of truth.</p>
 */
public record AgenticAuthoringSemanticDecision(
        String schemaVersion,
        String decisionId,
        String operationKind,
        String artifactKind,
        String changeKind,
        SelectedResource selectedResource,
        AgenticAuthoringVisualizationDecision visualizationDecision,
        RetrievalEvidence retrievalEvidence,
        boolean reviewRequired,
        String reviewReason,
        String previousDecisionRef,
        String refinementOf,
        String conversationId,
        String turnId,
        String userGoal,
        String activeObjective,
        String artifactIntent,
        String visualIntent,
        JsonNode constraints,
        String previousDecisionId,
        String rationale,
        Double confidence
) {

    static final String SCHEMA_VERSION = "praxis-agentic-authoring-semantic-decision.v1";

    public AgenticAuthoringSemanticDecision(
            String schemaVersion,
            String decisionId,
            String operationKind,
            String artifactKind,
            String changeKind,
            SelectedResource selectedResource,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            RetrievalEvidence retrievalEvidence,
            boolean reviewRequired,
            String reviewReason,
            String previousDecisionRef,
            String refinementOf) {
        this(
                schemaVersion,
                decisionId,
                operationKind,
                artifactKind,
                changeKind,
                selectedResource,
                visualizationDecision,
                retrievalEvidence,
                reviewRequired,
                reviewReason,
                previousDecisionRef,
                refinementOf,
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                "",
                "",
                null);
    }

    static AgenticAuthoringSemanticDecision from(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<String> warnings,
            JsonNode llmDiagnostics,
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringSemanticDecision activeDecision,
            String conversationId,
            String turnId,
            String userGoal,
            String activeObjective,
            String rationale) {
        boolean keywordFallback = contains(warnings, "keyword-fallback-applied")
                || contains(warnings, "keyword-fallback-fail-safe-applied")
                || keywordFallbackApplied(llmDiagnostics);
        boolean domainAnchorSelection = contains(warnings, "resource-selection-domain-anchor-selected")
                || selectedCandidateHasEvidence(selectedCandidate, "domain-anchor");
        boolean visualProjectionRefinement = contains(warnings, "semantic-policy-refined-visual-projection");
        boolean decisionMemoryRefinement = contains(warnings, "semantic-decision-memory-refinement-applied");
        String followUpKind = llmIntent == null ? "" : safe(llmIntent.followUpKind());
        String previousDecisionId = activeDecision == null ? "" : safe(activeDecision.decisionId());
        boolean refinement = visualProjectionRefinement
                || decisionMemoryRefinement
                || "refinement".equals(followUpKind);
        boolean activeDecisionReviewRequired = refinement
                && activeDecision != null
                && activeDecision.reviewRequired();
        boolean reviewRequired = keywordFallback || domainAnchorSelection || activeDecisionReviewRequired;
        String refinementOf = refinement
                ? (previousDecisionId.isBlank() ? "previous-conversation-decision" : previousDecisionId)
                : "";
        String visualIntent = visualIntent(artifactKind, visualizationDecision, activeDecision, refinement);
        return new AgenticAuthoringSemanticDecision(
                SCHEMA_VERSION,
                decisionId(
                        operationKind,
                        artifactKind,
                        changeKind,
                        selectedCandidate,
                        visualizationDecision,
                        conversationId,
                        turnId,
                        previousDecisionId),
                safe(operationKind),
                safe(artifactKind),
                safe(changeKind),
                SelectedResource.from(selectedCandidate),
                visualizationDecision,
                RetrievalEvidence.from(selectedCandidate, candidates),
                reviewRequired,
                reviewReason(keywordFallback, domainAnchorSelection, activeDecisionReviewRequired, activeDecision),
                visualProjectionRefinement ? "current-page-bound-resource" : refinementOf,
                refinementOf,
                safe(conversationId),
                safe(turnId),
                safe(userGoal),
                safe(activeObjective).isBlank() ? safe(userGoal) : safe(activeObjective),
                artifactIntent(operationKind, artifactKind, changeKind),
                visualIntent,
                null,
                previousDecisionId,
                safe(rationale),
                confidence(reviewRequired, selectedCandidate, refinement));
    }

    static AgenticAuthoringSemanticDecision from(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<String> warnings,
            JsonNode llmDiagnostics,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return from(
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                visualizationDecision,
                warnings,
                llmDiagnostics,
                llmIntent,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    static AgenticAuthoringSemanticDecision from(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<String> warnings) {
        return from(
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                visualizationDecision,
                warnings,
                null,
                null);
    }

    static AgenticAuthoringSemanticDecision from(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            List<String> warnings,
            JsonNode llmDiagnostics) {
        return from(
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                visualizationDecision,
                warnings,
                llmDiagnostics,
                null);
    }

    private static String decisionId(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            String conversationId,
            String turnId,
            String previousDecisionId) {
        String resourcePath = selectedCandidate == null ? "" : safe(selectedCandidate.resourcePath());
        String visualizationIntent = visualizationDecision == null ? "" : safe(visualizationDecision.intent());
        int hash = Objects.hash(
                safe(operationKind),
                safe(artifactKind),
                safe(changeKind),
                resourcePath,
                visualizationIntent,
                safe(conversationId),
                safe(turnId),
                safe(previousDecisionId));
        return "agentic-authoring-decision-" + Integer.toHexString(hash);
    }

    private static boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private static boolean keywordFallbackApplied(JsonNode llmDiagnostics) {
        return llmDiagnostics != null
                && llmDiagnostics.path("resolutionTelemetry").path("keywordFallbackApplied").asBoolean(false);
    }

    private static boolean selectedCandidateHasEvidence(AgenticAuthoringCandidate selectedCandidate, String evidence) {
        return selectedCandidate != null
                && selectedCandidate.evidence() != null
                && selectedCandidate.evidence().contains(evidence);
    }

    private static String reviewReason(
            boolean keywordFallback,
            boolean domainAnchorSelection,
            boolean activeDecisionReviewRequired,
            AgenticAuthoringSemanticDecision activeDecision) {
        if (keywordFallback) {
            return "keyword-fallback-fail-safe";
        }
        if (domainAnchorSelection) {
            return "resource-selection-domain-anchor";
        }
        if (activeDecisionReviewRequired) {
            String reason = activeDecision == null ? "" : safe(activeDecision.reviewReason());
            return reason.isBlank() ? "active-decision-review-required" : reason;
        }
        return "";
    }

    private static String artifactIntent(String operationKind, String artifactKind, String changeKind) {
        return String.join(":",
                safe(operationKind),
                safe(artifactKind),
                safe(changeKind)).replaceAll(":+$", "");
    }

    private static String visualIntent(
            String artifactKind,
            AgenticAuthoringVisualizationDecision visualizationDecision,
            AgenticAuthoringSemanticDecision activeDecision,
            boolean refinement) {
        if (refinement && ("dashboard".equals(safe(artifactKind)) || "chart".equals(safe(artifactKind)))) {
            return "charts";
        }
        if (visualizationDecision != null && !safe(visualizationDecision.intent()).isBlank()) {
            return safe(visualizationDecision.intent());
        }
        if ("dashboard".equals(safe(artifactKind)) || "chart".equals(safe(artifactKind))) {
            return "charts";
        }
        if ("table".equals(safe(artifactKind))) {
            return "table";
        }
        if (refinement && activeDecision != null && !safe(activeDecision.visualIntent()).isBlank()) {
            return safe(activeDecision.visualIntent());
        }
        return safe(artifactKind);
    }

    private static Double confidence(
            boolean reviewRequired,
            AgenticAuthoringCandidate selectedCandidate,
            boolean refinement) {
        if (reviewRequired) {
            return 0.5d;
        }
        if (selectedCandidate != null) {
            return refinement ? 0.86d : 0.82d;
        }
        return refinement ? 0.72d : 0.68d;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record SelectedResource(
            String resourcePath,
            String operation,
            String schemaUrl,
            String submitUrl,
            String submitMethod
    ) {

        static SelectedResource from(AgenticAuthoringCandidate candidate) {
            if (candidate == null) {
                return null;
            }
            return new SelectedResource(
                    safe(candidate.resourcePath()),
                    safe(candidate.operation()),
                    safe(candidate.schemaUrl()),
                    safe(candidate.submitUrl()),
                    safe(candidate.submitMethod()));
        }
    }

    public record RetrievalEvidence(
            String retrievalSource,
            List<String> evidence,
            int candidateCount
    ) {

        static RetrievalEvidence from(
                AgenticAuthoringCandidate selectedCandidate,
                List<AgenticAuthoringCandidate> candidates) {
            return new RetrievalEvidence(
                    AgenticAuthoringCandidateProvenancePolicy.retrievalSource(selectedCandidate, candidates),
                    selectedCandidate == null || selectedCandidate.evidence() == null
                            ? List.of()
                            : List.copyOf(selectedCandidate.evidence()),
                    candidates == null ? 0 : candidates.size());
        }
    }
}
