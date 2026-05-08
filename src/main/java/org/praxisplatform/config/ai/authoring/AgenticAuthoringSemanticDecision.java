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
        AgenticAuthoringEvidenceBundle retrievedEvidence,
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
        AgenticAuthoringSemanticRefinement refinement,
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
                null,
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
            String rationale,
            AgenticAuthoringSemanticRefinement semanticRefinement) {
        boolean keywordFallback = contains(warnings, "keyword-fallback-applied")
                || contains(warnings, "keyword-fallback-fail-safe-applied")
                || keywordFallbackApplied(llmDiagnostics);
        AgenticAuthoringEvidenceBundle retrievedEvidence = retrievedEvidence(selectedCandidate);
        boolean strongGroundingEvidence = strongGroundingEvidence(retrievedEvidence, selectedCandidate);
        boolean domainAnchorSelection = (contains(warnings, "resource-selection-domain-anchor-selected")
                || selectedCandidateHasEvidence(selectedCandidate, "domain-anchor"))
                && !strongGroundingEvidence;
        boolean visualProjectionRefinement = contains(warnings, "semantic-policy-refined-visual-projection");
        boolean decisionMemoryRefinement = contains(warnings, "semantic-decision-memory-refinement-applied");
        boolean semanticRefinementApplied = contains(warnings, "semantic-refinement-applied");
        String followUpKind = llmIntent == null ? "" : safe(llmIntent.followUpKind());
        String previousDecisionId = activeDecision == null ? "" : safe(activeDecision.decisionId());
        boolean refinement = visualProjectionRefinement
                || decisionMemoryRefinement
                || semanticRefinementApplied
                || "refinement".equals(followUpKind);
        boolean activeDecisionReviewRequired = refinement
                && activeDecision != null
                && activeDecision.reviewRequired();
        boolean weakLexicalEvidence = weakLexicalEvidence(retrievedEvidence, selectedCandidate);
        boolean reviewRequired = keywordFallback
                || domainAnchorSelection
                || activeDecisionReviewRequired
                || weakLexicalEvidence;
        String refinementOf = refinement
                ? (previousDecisionId.isBlank() ? "previous-conversation-decision" : previousDecisionId)
                : "";
        String visualIntent = visualIntent(artifactKind, visualizationDecision, activeDecision, semanticRefinement, refinement);
        String decisionArtifactKind = replacement(semanticRefinement, "artifactKind", artifactKind);
        return new AgenticAuthoringSemanticDecision(
                SCHEMA_VERSION,
                decisionId(
                        operationKind,
                        decisionArtifactKind,
                        changeKind,
                        selectedCandidate,
                        visualizationDecision,
                        conversationId,
                        turnId,
                        previousDecisionId),
                safe(operationKind),
                safe(decisionArtifactKind),
                safe(changeKind),
                SelectedResource.from(selectedCandidate),
                visualizationDecision,
                RetrievalEvidence.from(selectedCandidate, candidates),
                retrievedEvidence,
                reviewRequired,
                reviewReason(keywordFallback, domainAnchorSelection, activeDecisionReviewRequired, weakLexicalEvidence, activeDecision),
                visualProjectionRefinement ? "current-page-bound-resource" : refinementOf,
                refinementOf,
                safe(conversationId),
                safe(turnId),
                safe(userGoal),
                safe(activeObjective).isBlank() ? safe(userGoal) : safe(activeObjective),
                artifactIntent(operationKind, artifactKind, changeKind),
                visualIntent,
                null,
                normalizeRefinement(semanticRefinement),
                previousDecisionId,
                safe(rationale),
                confidence(reviewRequired, weakLexicalEvidence, retrievedEvidence, selectedCandidate, refinement));
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
                activeDecision,
                conversationId,
                turnId,
                userGoal,
                activeObjective,
                rationale,
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
            boolean weakLexicalEvidence,
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
        if (weakLexicalEvidence) {
            return "weak-lexical-evidence";
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
            AgenticAuthoringSemanticRefinement semanticRefinement,
            boolean refinement) {
        String replacement = replacement(semanticRefinement, "visualIntent", "");
        if (!replacement.isBlank()) {
            return replacement;
        }
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

    private static AgenticAuthoringSemanticRefinement normalizeRefinement(
            AgenticAuthoringSemanticRefinement semanticRefinement) {
        return semanticRefinement != null && semanticRefinement.active()
                ? semanticRefinement
                : null;
    }

    private static String replacement(
            AgenticAuthoringSemanticRefinement semanticRefinement,
            String key,
            String fallback) {
        if (semanticRefinement == null || !semanticRefinement.active()) {
            return safe(fallback);
        }
        String replacement = semanticRefinement.replacement(key);
        return replacement.isBlank() ? safe(fallback) : replacement;
    }

    private static Double confidence(
            boolean reviewRequired,
            boolean weakLexicalEvidence,
            AgenticAuthoringEvidenceBundle retrievedEvidence,
            AgenticAuthoringCandidate selectedCandidate,
            boolean refinement) {
        if (weakLexicalEvidence) {
            return weakEvidenceConfidence(retrievedEvidence);
        }
        if (reviewRequired) {
            return 0.5d;
        }
        if (selectedCandidate != null) {
            return refinement ? 0.86d : 0.82d;
        }
        return refinement ? 0.72d : 0.68d;
    }

    private static boolean weakLexicalEvidence(
            AgenticAuthoringEvidenceBundle retrievedEvidence,
            AgenticAuthoringCandidate selectedCandidate) {
        if (selectedCandidateHasEvidence(selectedCandidate, "lexical-fallback")
                || selectedCandidateHasEvidence(selectedCandidate, "weak-evidence")) {
            return true;
        }
        if (retrievedEvidence == null) {
            return false;
        }
        if ("lexical_fallback".equals(safe(retrievedEvidence.retrievalSource()))) {
            return true;
        }
        return retrievedEvidence.evidence().stream()
                .anyMatch(evidence -> "weak_lexical_match".equals(safe(evidence.kind())));
    }

    private static double weakEvidenceConfidence(AgenticAuthoringEvidenceBundle retrievedEvidence) {
        if (retrievedEvidence == null || retrievedEvidence.evidence().isEmpty()) {
            return 0.49d;
        }
        double strongestWeakEvidence = retrievedEvidence.evidence().stream()
                .filter(evidence -> "weak_lexical_match".equals(safe(evidence.kind()))
                        || "lexical_fallback".equals(safe(retrievedEvidence.retrievalSource())))
                .mapToDouble(AgenticAuthoringEvidenceBundle.Evidence::confidence)
                .max()
                .orElse(0.49d);
        return Math.min(0.49d, strongestWeakEvidence);
    }

    private static AgenticAuthoringEvidenceBundle retrievedEvidence(AgenticAuthoringCandidate selectedCandidate) {
        if (selectedCandidate != null && selectedCandidate.evidenceBundle() != null) {
            return selectedCandidate.evidenceBundle();
        }
        return AgenticAuthoringEvidenceBundle.empty();
    }

    private static boolean strongGroundingEvidence(
            AgenticAuthoringEvidenceBundle retrievedEvidence,
            AgenticAuthoringCandidate selectedCandidate) {
        if (weakLexicalEvidence(retrievedEvidence, selectedCandidate)) {
            return false;
        }
        if (retrievedEvidence == null || retrievedEvidence.evidence().isEmpty()) {
            return selectedCandidate != null
                    && selectedCandidate.score() >= 0.85d
                    && !selectedCandidateHasEvidence(selectedCandidate, "domain-anchor")
                    && !selectedCandidateHasEvidence(selectedCandidate, "lexical-fallback")
                    && !selectedCandidateHasEvidence(selectedCandidate, "weak-evidence");
        }
        String retrievalSource = safe(retrievedEvidence.retrievalSource());
        if ("lexical_fallback".equals(retrievalSource)) {
            return false;
        }
        double strongestEvidence = retrievedEvidence.evidence().stream()
                .mapToDouble(AgenticAuthoringEvidenceBundle.Evidence::confidence)
                .max()
                .orElse(0d);
        boolean structuralEvidence = retrievedEvidence.evidence().stream()
                .map(AgenticAuthoringEvidenceBundle.Evidence::kind)
                .map(AgenticAuthoringSemanticDecision::safe)
                .anyMatch(kind -> kind.equals("schema_grounding")
                        || kind.equals("operation_grounding")
                        || kind.equals("resource_capability_hint")
                        || kind.equals("retrieved_candidate"));
        return strongestEvidence >= 0.62d && structuralEvidence;
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
