package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringResourcePresentationLabelTest {

    @Test
    void fallsBackToResourcePathWhenEvidenceSummaryIsInternalDiagnosticText() {
        AgenticAuthoringCandidate candidate = candidate(
                "/api/human-resources/funcionarios",
                "Llm authored governed resource focus: human resources");

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Funcionários");
    }

    @Test
    void fallsBackToResourcePathWhenEvidenceSummaryIsOnlyGenericOperationLabel() {
        AgenticAuthoringCandidate candidate = candidate(
                "/api/procurement/contracts",
                "Registros");

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Contracts");
    }

    @Test
    void fallsBackToResourcePathWhenEvidenceSummaryDescribesTechnicalSchemaEvidence() {
        AgenticAuthoringCandidate candidate = candidate(
                "/api/human-resources/funcionarios",
                "Canonical filtered schema for the selected operation");

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Funcionários");
    }

    @Test
    void fallsBackToResourcePathWhenEvidenceSummaryDescribesTechnicalCandidateEndpoint() {
        AgenticAuthoringCandidate candidate = candidate(
                "/api/procurement/contracts",
                "Candidate operation and materialization endpoint");

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Contracts");
    }

    @Test
    void ignoresSecondaryOperationalEvidenceWhenApiMetadataDoesNotExposePublicLabel() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                0.87d,
                "semantic candidate",
                List.of("api-metadata"),
                AgenticAuthoringEvidenceBundle.of(
                        "semantic_retrieval",
                        List.of(
                                new AgenticAuthoringEvidenceBundle.Evidence(
                                        "api_metadata",
                                        "retrieved_candidate",
                                        "/api/human-resources/funcionarios",
                                        "Canonical filtered schema for the selected operation",
                                        0.87d,
                                        List.of(),
                                        "tenant",
                                        "local",
                                        ""),
                                new AgenticAuthoringEvidenceBundle.Evidence(
                                        "capability_snapshot",
                                        "retrieved_candidate",
                                        "/api/human-resources/funcionarios/capabilities",
                                        "Resource capability snapshot candidate",
                                        0.70d,
                                        List.of(),
                                        "tenant",
                                        "local",
                                        ""))));

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Funcionários");
    }

    @Test
    void keepsGovernedProfileEvidenceSummaryWhenItContainsPublicResourceName() {
        AgenticAuthoringCandidate candidate = candidate(
                "/api/human-resources/vw-perfil-heroi",
                "Percorrer perfis 360 em listas extensas");

        assertThat(AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate))
                .isEqualTo("Perfis 360");
    }

    @Test
    void resourcePathFallbackUsesPublicTitleCaseAndPresentationRepairs() {
        assertThat(AgenticAuthoringResourcePresentationLabel.fromResourcePath(
                "/api/human-resources/vw-analytics-folha-pagamento"))
                .isEqualTo("Analytics folha pagamento");
    }

    private static AgenticAuthoringCandidate candidate(String resourcePath, String summary) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                "",
                resourcePath + "/filter/cursor",
                "POST",
                0.87d,
                "semantic candidate",
                List.of("api-metadata"),
                AgenticAuthoringEvidenceBundle.of(
                        "semantic_retrieval",
                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                "api_metadata",
                                "retrieved_candidate",
                                resourcePath,
                                summary,
                                0.87d,
                                List.of(),
                                "tenant",
                                "local",
                                ""))));
    }
}
