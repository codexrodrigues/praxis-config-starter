package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringSemanticDecisionPolicyTest {

    private final AgenticAuthoringSemanticDecisionPolicy policy = new AgenticAuthoringSemanticDecisionPolicy();

    @Test
    void preservesCandidateWhenLlmResolutionDoesNotAuthorDomainSpecificOverride() {
        AgenticAuthoringCandidate payroll = candidate("/api/human-resources/vw-analytics-folha-pagamento");
        AgenticAuthoringCandidate reputation = candidate("/api/human-resources/vw-ranking-reputacao");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "ranking por departamento",
                "explore",
                "unknown",
                "unknown",
                payroll,
                List.of(payroll, reputation),
                null,
                llm("explore", "unknown"),
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.selectedCandidate()).isSameAs(payroll);
        assertThat(decision.operationKind()).isEqualTo("explore");
        assertThat(decision.artifactKind()).isEqualTo("unknown");
    }

    @Test
    void clearsSelectedCandidateForBareDomainPrompt() {
        AgenticAuthoringCandidate payroll = candidate("/api/human-resources/folhas-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "folha pagamento",
                "explore",
                "api_catalog",
                "unknown",
                payroll,
                List.of(payroll),
                null,
                null,
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.selectedCandidate()).isNull();
    }

    @Test
    void broadApiCatalogDiscoveryRequiresResourceChoiceWithoutContextHint() {
        AgenticAuthoringCandidate payroll = candidate("/api/human-resources/folhas-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "quais apis existem para filtrar folha",
                "explore",
                "api_catalog",
                "unknown",
                payroll,
                List.of(payroll),
                null,
                null,
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.selectedCandidate()).isNull();
    }

    @Test
    void broadApiCatalogDiscoveryPreservesContextHintCandidate() {
        AgenticAuthoringCandidate payroll = candidate("/api/human-resources/folhas-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "quais apis existem para filtrar folha",
                "explore",
                "api_catalog",
                "unknown",
                payroll,
                List.of(payroll),
                payroll,
                null,
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.selectedCandidate()).isSameAs(payroll);
    }

    @Test
    void resourceChoiceClarificationForDashboardBecomesGovernedVisualizationRecommendation() {
        AgenticAuthoringCandidate analytics = candidate("/api/human-resources/vw-analytics-folha-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "usar analytics folha pagamento como fonte de dados",
                "create",
                "dashboard",
                "create_artifact",
                analytics,
                List.of(analytics),
                analytics,
                null,
                false,
                true,
                false,
                null,
                null,
                null));

        assertThat(decision.operationKind()).isEqualTo("explore");
        assertThat(decision.artifactKind()).isEqualTo("dashboard");
        assertThat(decision.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(decision.selectedCandidate()).isSameAs(analytics);
    }

    @Test
    void governedConfirmationUsesContextHintDecision() {
        AgenticAuthoringCandidate incidents = candidate("/api/operations/incidentes");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "gerar previa governada",
                "explore",
                "api_catalog",
                "unknown",
                null,
                List.of(incidents),
                incidents,
                null,
                false,
                false,
                true,
                "create",
                "dashboard",
                "create_artifact"));

        assertThat(decision.operationKind()).isEqualTo("create");
        assertThat(decision.artifactKind()).isEqualTo("dashboard");
        assertThat(decision.changeKind()).isEqualTo("create_artifact");
        assertThat(decision.selectedCandidate()).isSameAs(incidents);
    }

    @Test
    void rawOptionalDataSourceHintPreventsPrematureResourceBinding() {
        AgenticAuthoringCandidate analytics = candidate("/api/human-resources/vw-analytics-folha-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "conversa acumulada sobre dashboard por setor",
                "se precisar usa os dados de folha de pagamento",
                "create",
                "dashboard",
                "create_artifact",
                analytics,
                List.of(analytics),
                null,
                null,
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.selectedCandidate()).isNull();
        assertThat(decision.operationKind()).isEqualTo("create");
    }

    @Test
    void rawDataSourceAnswerKeepsDashboardInGovernedExploration() {
        AgenticAuthoringCandidate analytics = candidate("/api/human-resources/vw-analytics-folha-pagamento");

        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision decision = policy.apply(input(
                "Qual API deve alimentar este dashboard?",
                "Use analytics folha pagamento as the data source.",
                "create",
                "dashboard",
                "create_artifact",
                analytics,
                List.of(analytics),
                analytics,
                null,
                false,
                false,
                false,
                null,
                null,
                null));

        assertThat(decision.operationKind()).isEqualTo("explore");
        assertThat(decision.artifactKind()).isEqualTo("dashboard");
        assertThat(decision.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(decision.selectedCandidate()).isSameAs(analytics);
    }

    private AgenticAuthoringSemanticDecisionPolicy.Input input(
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
        return input(
                prompt,
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                contextHintCandidate,
                llmIntent,
                optionalDataSourceHint,
                resourceChoiceClarificationAnswer,
                governedResourceConfirmation,
                contextHintOperationKind,
                contextHintArtifactKind,
                contextHintChangeKind);
    }

    private AgenticAuthoringSemanticDecisionPolicy.Input input(
            String prompt,
            String rawPrompt,
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
        return new AgenticAuthoringSemanticDecisionPolicy.Input(
                prompt,
                prompt,
                rawPrompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                contextHintCandidate,
                llmIntent,
                optionalDataSourceHint,
                resourceChoiceClarificationAnswer,
                governedResourceConfirmation,
                contextHintOperationKind,
                contextHintArtifactKind,
                contextHintChangeKind);
    }

    private AgenticAuthoringLlmIntentResolution llm(String operationKind, String artifactKind) {
        return new AgenticAuthoringLlmIntentResolution(
                true,
                operationKind,
                artifactKind,
                "unknown",
                null,
                null,
                "none",
                "",
                List.of(),
                List.of(),
                List.of());
    }

    private AgenticAuthoringCandidate candidate(String resourcePath) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                "/schemas/filtered?path=" + resourcePath + "/filter&operation=post&schemaType=response",
                resourcePath + "/filter",
                "post",
                0.8,
                "test",
                List.of("semantic-retrieval"));
    }
}
