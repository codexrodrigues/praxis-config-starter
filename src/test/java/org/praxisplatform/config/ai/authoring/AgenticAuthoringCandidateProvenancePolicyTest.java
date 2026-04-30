package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringCandidateProvenancePolicyTest {

    @Test
    void classifiesKnownRetrievalSourcesFromCandidateEvidence() {
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("semantic-retrieval"))))
                .isEqualTo("semantic_retrieval");
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("lexical-fallback"))))
                .isEqualTo("lexical_fallback");
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("broad-artifact-discovery"))))
                .isEqualTo("broad_artifact_discovery");
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("tool-search-api-resources"))))
                .isEqualTo("context_hint");
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("explicit-resource-path"))))
                .isEqualTo("deterministic_override");
    }

    @Test
    void preservesLegacyApiMetadataAsLexicalFallback() {
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("api-metadata"))))
                .isEqualTo("lexical_fallback");
    }

    @Test
    void reportsNoneOrUnknownWhenEvidenceIsMissing() {
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of()))
                .isEqualTo("none");
        assertThat(AgenticAuthoringCandidateProvenancePolicy.retrievalSource(List.of(candidate("custom-evidence"))))
                .isEqualTo("unknown");
    }

    private AgenticAuthoringCandidate candidate(String evidence) {
        return new AgenticAuthoringCandidate(
                "/api/example",
                "get",
                "/schemas/filtered?path=/api/example&operation=get&schemaType=response",
                "/api/example/all",
                "get",
                0.9,
                "test",
                List.of(evidence));
    }
}
