package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Objects;

final class AgenticAuthoringCandidateProvenancePolicy {

    static final String SEMANTIC_RETRIEVAL = "semantic_retrieval";
    static final String LEXICAL_FALLBACK = "lexical_fallback";
    static final String BROAD_ARTIFACT_DISCOVERY = "broad_artifact_discovery";
    static final String CONTEXT_HINT = "context_hint";
    static final String DETERMINISTIC_OVERRIDE = "deterministic_override";
    static final String NONE = "none";
    static final String UNKNOWN = "unknown";

    private AgenticAuthoringCandidateProvenancePolicy() {
    }

    static String retrievalSource(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return NONE;
        }
        if (hasEvidence(candidates, "semantic-retrieval")) {
            return SEMANTIC_RETRIEVAL;
        }
        if (hasEvidence(candidates, "lexical-fallback")) {
            return LEXICAL_FALLBACK;
        }
        if (hasEvidence(candidates, "broad-artifact-discovery")) {
            return BROAD_ARTIFACT_DISCOVERY;
        }
        if (hasEvidence(candidates, "tool-search-api-resources")
                || hasEvidence(candidates, "quick-reply-context")) {
            return CONTEXT_HINT;
        }
        if (hasEvidence(candidates, "explicit-resource-path")) {
            return DETERMINISTIC_OVERRIDE;
        }
        if (hasEvidence(candidates, "api-metadata")) {
            return LEXICAL_FALLBACK;
        }
        return UNKNOWN;
    }

    static String retrievalSource(
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (hasEvidence(selectedCandidate)) {
            return retrievalSource(List.of(selectedCandidate));
        }
        return retrievalSource(candidates);
    }

    private static boolean hasEvidence(AgenticAuthoringCandidate candidate) {
        return candidate != null && candidate.evidence() != null && !candidate.evidence().isEmpty();
    }

    private static boolean hasEvidence(List<AgenticAuthoringCandidate> candidates, String evidence) {
        return candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> candidate.evidence() != null && candidate.evidence().contains(evidence));
    }
}
