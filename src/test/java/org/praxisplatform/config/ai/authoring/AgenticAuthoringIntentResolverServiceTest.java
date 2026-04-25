package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@Tag("unit")
class AgenticAuthoringIntentResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringIntentResolverService service =
            new AgenticAuthoringIntentResolverService(objectMapper);

    @Test
    void resolvesCreateMinimalFormForQuickstartFuncionarios() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario didatico para cadastrar funcionarios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("create_minimal_form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void prioritizesFuncionariosPostFormWhenEmployeeFormPromptHasCompetingSemanticCandidates() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringCandidate employeeSkillCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionario-habilidades",
                "get",
                "/schemas/filtered?path=/api/human-resources/funcionario-habilidades/all&operation=get&schemaType=response",
                "/api/human-resources/funcionario-habilidades/all",
                "get",
                1.0d,
                "api_metadata semantic retrieval",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringCandidate employeeCollectionCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "get",
                "/schemas/filtered?path=/api/human-resources/funcionarios/all&operation=get&schemaType=response",
                "/api/human-resources/funcionarios/all",
                "get",
                1.0d,
                "api_metadata collection retrieval",
                List.of("api-metadata", "semantic-retrieval"));
        Mockito.when(candidateCatalog.discover(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(employeeSkillCandidate, employeeCollectionCandidate));
        AgenticAuthoringIntentResolverService serviceWithCatalog =
                new AgenticAuthoringIntentResolverService(objectMapper, candidateCatalog);

        AgenticAuthoringIntentResolutionResult result = serviceWithCatalog.resolve(new AgenticAuthoringIntentResolutionRequest(
                "faz ela simples pra salva funcionario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("create_minimal_form");
        assertThat(result.candidates().get(0).resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.candidates().get(0).operation()).isEqualTo("post");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void resolvesCreateChartDrillDownForQuickstartPayrollAnalytics() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use um chart para criar drill down da folha por departamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.authoringProfile()).isEqualTo("generic-page-change");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void resolvesDirectPayrollDashboardWithIndicatorsAsCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard executivo de folha de pagamento por departamento com indicadores, detalhamento e resumo gerencial",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void preservesCanonicalPayrollAnalyticsSourceWhenDepartmentFollowUpTriesToSwitchDashboardDataSource() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringCandidate departmentCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/departamentos",
                "get",
                "/schemas/filtered?path=/api/human-resources/departamentos/all&operation=get&schemaType=response",
                "/api/human-resources/departamentos/all",
                "get",
                0.98d,
                "llm selected department collection for grouping",
                List.of("api-metadata", "semantic-retrieval"));
        Mockito.when(candidateCatalog.discover(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(departmentCandidate));
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "clarification_answer",
                        "unknown",
                        "clarify_resource",
                        "/api/human-resources/departamentos",
                        null,
                        "none",
                        "Use a API de departamentos como fonte.",
                        List.of(),
                        List.of(),
                        List.of("llm-selected-departments"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use Agrupar por departamento (/api/human-resources/departamentos) as the data source.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-6",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user",
                                "quero uma tela pra ve os pagamento dos funcionario, tipo um painel bonito", null),
                        new AgenticAuthoringConversationMessage("m2", "user",
                                "nao sei se e dashbord ou relatorio, mas queria ver por setor", null),
                        new AgenticAuthoringConversationMessage("m3", "user",
                                "coloca grafico e uma lista embaixo pra conferir", null),
                        new AgenticAuthoringConversationMessage("m4", "user",
                                "se precisar usa os dados de folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m5", "user",
                                "pode fazer agora", null),
                        new AgenticAuthoringConversationMessage("m6", "user",
                                "Quero o grafico de barras mostrando os pagamentos por setor.", null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.warnings()).contains("llm-selected-departments");
    }

    @Test
    void usesLlmIntentResolutionWhenAvailableAndPreservesRichQuickReplies() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        AgenticAuthoringQuickReply richReply = new AgenticAuthoringQuickReply(
                "payroll-dashboard",
                "resource",
                "Folha de pagamento",
                "Crie um dashboard de folha de pagamento por departamento.",
                "Indicadores, evolução e drill-down por departamento.",
                "payments",
                "analytics",
                contextHints);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Posso criar com o recurso de folha de pagamento.",
                        List.of(richReply),
                        List.of(),
                        List.of("llm-test-warning"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard executivo de folha de pagamento por departamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.assistantMessage()).isEqualTo("Posso criar com o recurso de folha de pagamento.");
        assertThat(result.quickReplies()).containsExactly(richReply);
        assertThat(result.quickReplies().get(0).icon()).isEqualTo("payments");
        assertThat(result.quickReplies().get(0).description()).contains("Indicadores");
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.warnings()).contains("llm-intent-resolution-used", "llm-test-warning");
    }

    @Test
    void usesResolvedLlmIntentForConsultativePromptInsteadOfKeywordFallback() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Faz mais sentido usar um dashboard para acompanhar folha de pagamento.",
                        List.of(),
                        List.of(),
                        List.of("llm-suggested-create"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Leia o contexto dos componentes disponiveis e me oriente: para salarios, descontos, departamento e historico de pagamentos, faz mais sentido dashboard, tabela operacional ou formulario?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.valid()).isTrue();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.warnings()).contains("llm-intent-resolution-used", "llm-suggested-create");
    }

    @Test
    void doesNotFillResolvedLlmBlankFieldsWithKeywordFallback() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "",
                        "",
                        null,
                        "",
                        null,
                        "Entendi que voce quer criar algo, mas preciso confirmar se e dashboard, relatorio, tabela ou formulario.",
                        List.of(),
                        List.of("Voce quer criar um dashboard, uma tabela ou um formulario?"),
                        List.of("llm-needs-artifact-kind"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero um painel pros pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.changeKind()).isEqualTo("unknown");
        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("intent-artifact-unknown");
        assertThat(result.clarificationQuestions())
                .contains("Voce quer criar um dashboard, uma tabela ou um formulario?");
        assertThat(result.assistantMessage())
                .contains("preciso confirmar");
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used", "llm-needs-artifact-kind")
                .doesNotContain("keyword-fallback-applied", "llm-intent-resolution-fallback-deterministic");
    }

    @Test
    void usesResolvedLlmClarificationQuestionsWhenGateStillNeedsBusinessChoice() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        String llmQuestion = "Posso montar o painel usando folha de pagamento. Voce prefere quebrar por departamento, competencia ou status?";
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Encontrei a fonte de folha, mas ainda falta escolher o recorte principal do painel.",
                        List.of(new AgenticAuthoringQuickReply(
                                "payroll-by-department",
                                "suggestion",
                                "Por departamento",
                                "Use departamento como recorte principal.")),
                        List.of(llmQuestion),
                        List.of("llm-clarification-question"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("analytics-breakdown-required");
        assertThat(result.assistantMessage())
                .isEqualTo("Encontrei a fonte de folha, mas ainda falta escolher o recorte principal do painel.");
        assertThat(result.clarificationQuestions()).containsExactly(llmQuestion);
        assertThat(result.pendingClarification()).isNotNull();
        assertThat(result.pendingClarification().questions()).containsExactly(llmQuestion);
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("payroll-by-department");
    }

    @Test
    void treatsConfirmedPayrollAnalyticsSourceWithDashboardBreakdownAsCreateEvenWhenLlmKeepsExploring() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "explore",
                        "dashboard",
                        "recommend_dashboard_visualization",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "clarification_answer",
                        "Vou usar a fonte confirmada e manter o painel por setor.",
                        List.of(),
                        List.of("Posso aplicar a base ao painel?"),
                        List.of("llm-kept-exploring"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        contextHints.put("submitUrl", "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use Fonte confirmada (/api/human-resources/vw-analytics-folha-pagamento) como data source. Mantenha o painel por setor com grafico e lista.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used", "deterministic-payroll-dashboard-confirmation-applied");
    }

    @Test
    void treatsConfirmedPayrollCollectionSourceWithDashboardBreakdownAsCreateEvenWhenLlmKeepsExploring() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "explore",
                        "dashboard",
                        "recommend_dashboard_visualization",
                        "/api/human-resources/folhas-pagamento",
                        null,
                        "clarification_answer",
                        "Vou usar a fonte confirmada para montar o painel por setor.",
                        List.of(),
                        List.of("Posso aplicar a base ao painel?"),
                        List.of("llm-kept-exploring"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/folhas-pagamento");
        contextHints.put("submitUrl", "/api/human-resources/folhas-pagamento/all");
        contextHints.put("operation", "get");

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use Grafico por setor + lista abaixo (/api/human-resources/folhas-pagamento) as the data source.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.quickReplies()).isEmpty();
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used", "deterministic-payroll-dashboard-confirmation-applied");
    }

    @Test
    void fallsBackToKeywordResolverOnlyWhenLlmIntentIsUnresolved() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        false,
                        "",
                        "",
                        "",
                        null,
                        null,
                        "unknown",
                        "Ainda preciso de mais contexto.",
                        List.of(),
                        List.of("Qual recurso deve alimentar esta tela?"),
                        List.of("llm-unresolved-test"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario didatico para cadastrar funcionarios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("create_minimal_form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.warnings()).contains(
                "llm-intent-resolution-used",
                "llm-intent-resolution-unresolved-fallback-deterministic",
                "llm-unresolved-test");
    }

    @Test
    void usesLlmResourceSearchQueryToRefineCandidateDiscovery() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringCandidate benefitsCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/beneficios",
                "post",
                "/schemas/filtered?path=/api/human-resources/beneficios&operation=post&schemaType=request",
                "/api/human-resources/beneficios",
                "post",
                0.96d,
                "api_metadata semantic retrieval",
                List.of("api-metadata", "semantic-retrieval"));
        Mockito.when(candidateCatalog.discover(Mockito.anyString(), Mockito.anyString())).thenReturn(List.of());
        Mockito.when(candidateCatalog.discover("cadastro de beneficios para funcionario", "form"))
                .thenReturn(List.of(benefitsCandidate));
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "form",
                        "create_minimal_form",
                        "/api/human-resources/beneficios",
                        "cadastro de beneficios para funcionario",
                        "none",
                        "Vou buscar a API de beneficios para montar o formulario.",
                        List.of(),
                        List.of(),
                        List.of("llm-resource-search-query"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "preciso monta uma ficha pra cadastra beneficio de funsionario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.selectedCandidate()).isEqualTo(benefitsCandidate);
        assertThat(result.warnings()).contains("llm-resource-search-query");
        Mockito.verify(candidateCatalog).discover("cadastro de beneficios para funcionario", "form");
    }

    @Test
    void rerunsLlmIntentResolutionAfterResourceSearchFindsBetterCandidates() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringCandidate benefitsCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/beneficios",
                "post",
                "/schemas/filtered?path=/api/human-resources/beneficios&operation=post&schemaType=request",
                "/api/human-resources/beneficios",
                "post",
                0.96d,
                "api_metadata semantic retrieval",
                List.of("api-metadata", "semantic-retrieval"));
        Mockito.when(candidateCatalog.discover(Mockito.anyString(), Mockito.anyString())).thenReturn(List.of());
        Mockito.when(candidateCatalog.discover("cadastro de beneficios para funcionario", "form"))
                .thenReturn(List.of(benefitsCandidate));
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(
                        Optional.of(new AgenticAuthoringLlmIntentResolution(
                                true,
                                "create",
                                "form",
                                "create_minimal_form",
                                null,
                                "cadastro de beneficios para funcionario",
                                "none",
                                "Vou buscar APIs de beneficios antes de escolher a fonte.",
                                List.of(),
                                List.of(),
                                List.of("llm-requested-resource-search"))),
                        Optional.of(new AgenticAuthoringLlmIntentResolution(
                                true,
                                "create",
                                "form",
                                "create_minimal_form",
                                "/api/human-resources/beneficios",
                                null,
                                "none",
                                "Encontrei beneficios e vou usar esse recurso para o formulario.",
                                List.of(),
                                List.of(),
                                List.of("llm-selected-refined-resource"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "preciso monta uma ficha pra cadastra beneficio de funsionario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate()).isEqualTo(benefitsCandidate);
        assertThat(result.assistantMessage())
                .isEqualTo("Encontrei beneficios e vou usar esse recurso para o formulario.");
        assertThat(result.warnings()).contains(
                "llm-selected-refined-resource",
                "llm-intent-resolution-second-pass-used");
        Mockito.verify(llmIntentResolver, Mockito.times(2)).resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
    }

    @Test
    void usesToolDiscoveredCandidatesFromContextHintsForLlmSelection() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringCandidate toolCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-ranking-reputacao",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                "post",
                0.97d,
                "resource discovered by backend tool",
                List.of("api-metadata", "tool-search-api-resources"));
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<AgenticAuthoringCandidate> candidates = invocation.getArgument(4, List.class);
                    assertThat(candidates)
                            .extracting(AgenticAuthoringCandidate::resourcePath)
                            .contains("/api/human-resources/vw-ranking-reputacao");
                    return Optional.of(new AgenticAuthoringLlmIntentResolution(
                            true,
                            "create",
                            "dashboard",
                            "create_artifact",
                            "/api/human-resources/vw-ranking-reputacao",
                            null,
                            "none",
                            "Vou usar ranking reputacao como fonte de dados.",
                            List.of(),
                            List.of(),
                            List.of("llm-tool-candidate")));
                });
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode resourceDiscovery = contextHints.putObject("resourceDiscovery");
        resourceDiscovery.put("tool", "searchApiResources");
        resourceDiscovery.put("retrievalQuery", "ranking reputacao");
        resourceDiscovery.set("candidates", objectMapper.valueToTree(List.of(toolCandidate)));

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um painel para reputacao",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao/filter/cursor");
        assertThat(result.selectedCandidate().evidence()).contains("tool-search-api-resources");
        assertThat(result.warnings()).contains("llm-tool-candidate");
    }

    @Test
    void usesLlmFollowUpKindToTreatPendingClarificationAsNewInstruction() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "table",
                        "create_artifact",
                        "/api/human-resources/folhas-pagamento",
                        null,
                        "new_instruction",
                        "Vou tratar este pedido como uma nova tabela operacional.",
                        List.of(),
                        List.of(),
                        List.of("llm-semantic-follow-up"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Com base nisso, crie uma tabela operacional de folhas de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual recurso de negocio deve alimentar esta tela?", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard",
                        List.of("Qual recurso de negocio deve alimentar esta tela?"),
                        "Qual recurso de negocio deve alimentar esta tela?",
                        "turn-1",
                        objectMapper.createObjectNode())));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.effectivePrompt())
                .isEqualTo("Com base nisso, crie uma tabela operacional de folhas de pagamento");
        assertThat(result.effectivePrompt()).doesNotContain("Confirmed:");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.assistantMessage()).isEqualTo("Vou tratar este pedido como uma nova tabela operacional.");
        assertThat(result.warnings()).contains(
                "llm-intent-resolution-used",
                "llm-semantic-follow-up",
                "llm-follow-up-kind-new-instruction");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llmIntentResolver).resolve(
                Mockito.any(),
                promptCaptor.capture(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
        assertThat(promptCaptor.getValue())
                .isEqualTo("Com base nisso, crie uma tabela operacional de folhas de pagamento");
        assertThat(promptCaptor.getValue()).doesNotContain("Confirmed:");
    }

    @Test
    void usesLlmFollowUpKindToCombinePendingClarificationOnlyAfterLlmClassifiesAnswer() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "clarification_answer",
                        "Vou usar departamento como recorte do dashboard.",
                        List.of(),
                        List.of(),
                        List.of("llm-semantic-follow-up"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "por departamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard de folha", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual recorte deve aparecer?", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard de folha",
                        List.of("Qual recorte deve aparecer?"),
                        "Qual recorte deve aparecer?",
                        "turn-1",
                        objectMapper.createObjectNode())));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard de folha\n\nConfirmed: por departamento");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.assistantMessage()).isEqualTo("Vou usar departamento como recorte do dashboard.");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llmIntentResolver).resolve(
                Mockito.any(),
                promptCaptor.capture(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
        assertThat(promptCaptor.getValue()).isEqualTo("por departamento");
    }

    @Test
    void keepsDeterministicFallbackExplicitWhenLlmIntentResolutionIsUnavailable() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("resource-candidate-required");
        assertThat(result.warnings()).contains("llm-intent-resolution-fallback-deterministic");
    }

    @Test
    void includesLlmDiagnosticsOnlyWhenRequestedByContextHints() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        ObjectNode diagnosticSnapshot = objectMapper.createObjectNode();
        diagnosticSnapshot.put("promptTemplateId", "ai-authoring/page-builder-system-prompt.v1.md");
        diagnosticSnapshot.put("prompt", "contextBundle: {}");
        diagnosticSnapshot.putObject("contextBundle")
                .put("schemaVersion", "praxis-agentic-authoring-context-bundle.v1");
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Vou usar folha de pagamento como base.",
                        List.of(),
                        List.of(),
                        List.of())));
        Mockito.when(llmIntentResolver.diagnosticSnapshot(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any()))
                .thenReturn(diagnosticSnapshot);
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("includeLlmDiagnostics", true);

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard executivo de folha de pagamento por departamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-1",
                List.of(),
                null,
                List.of(),
                contextHints));

        assertThat(result.llmDiagnostics()).isNotNull();
        assertThat(result.llmDiagnostics().path("enabled").asBoolean()).isTrue();
        assertThat(result.llmDiagnostics().path("request").path("promptTemplateId").asText())
                .isEqualTo("ai-authoring/page-builder-system-prompt.v1.md");
        assertThat(result.llmDiagnostics().path("request").path("contextBundle").path("schemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-context-bundle.v1");
    }

    @Test
    void asksForConfirmationWhenUserAsksBestWayToVisualizePayrollInformation() {
        List<String> prompts = List.of(
                "qual e melhor forma de visualizar informacoes sobre a folha de pagamento?",
                "Como visualizar informacoes da folha de pagamento por departamento?",
                "Quero analisar a folha de pagamento por departamento antes de criar a tela",
                "Me ajude a escolher um dashboard para folha de pagamento",
                "Como visualizar a folha?",
                "Quero visualizar a folha por departamento em um dashboard",
                "qual e melhor forma de ver pagamentos?",
                "Mostre uma visao de pagamento por departamento",
                "Quais outras opcoes para dashboards de folha voce indica antes de criar?",
                "Compare alternativas para acompanhar salarios, descontos e departamentos",
                "Estou montando uma pagina executiva para RH e ainda nao sei se devo usar chart, tabela, cards ou resumo. Com base no catalogo de componentes, quais opcoes voce recomenda para analisar folha de pagamento por departamento, descontos e salario liquido antes de criar qualquer coisa?",
                "Antes de criar, compare as alternativas do catalogo: grafico com drill down, tabela detalhada ou indicadores. Preciso entender qual recurso suporta selecao, cross filter e detalhamento da folha por competencia e departamento.",
                "Leia o contexto dos componentes disponiveis e me oriente: para salarios, descontos, departamento e historico de pagamentos, faz mais sentido dashboard, tabela operacional ou formulario?");

        for (String prompt : prompts) {
            AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                    prompt,
                    "praxis-ui-angular",
                    "praxis-dynamic-page-builder",
                    "/page-builder-ia",
                    objectMapper.createObjectNode(),
                    null,
                    null,
                    null,
                    null));

            assertThat(result.valid()).as(prompt).isFalse();
            assertThat(result.operationKind()).as(prompt).isEqualTo("explore");
            assertThat(result.artifactKind()).as(prompt).isEqualTo("dashboard");
            assertThat(result.changeKind()).as(prompt).isEqualTo("recommend_dashboard_visualization");
            assertThat(result.selectedCandidate().resourcePath())
                    .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
            assertThat(result.selectedCandidate().operation()).isEqualTo("post");
            assertThat(result.gate().status()).isEqualTo("clarification_required");
            assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
            assertThat(result.assistantMessage()).contains("melhores opcoes");
            assertThat(result.quickReplies())
                    .extracting(AgenticAuthoringQuickReply::id)
                    .containsExactly(
                            "payroll-executive-dashboard",
                            "payroll-department-drilldown",
                            "payroll-detail-table");
            assertThat(result.clarificationQuestions())
                    .containsExactly("Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?");
            assertThat(result.pendingClarification()).isNotNull();
            assertThat(result.pendingClarification().sourcePrompt()).isEqualTo(result.effectivePrompt());
            assertThat(result.pendingClarification().questions())
                    .containsExactly("Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?");
            assertThat(result.pendingClarification().assistantMessage()).isEqualTo(result.assistantMessage());
        }
    }

    @Test
    void resolvesConfirmedPayrollVisualizationAsDashboardCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sim, crie um dashboard para visualizar informacoes sobre a folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void keepsDashboardArtifactWhenPromptAlsoAsksForDetailsTable() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha de pagamento com KPIs, folha por departamento, evolucao mensal e tabela de detalhes",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }
    @Test
    void resolvesShortClarificationAnswerUsingPendingClarificationContext() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual tema do dashboard?", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard",
                        List.of("Qual tema do dashboard?"),
                        "Qual tema do dashboard?",
                        "turn-1",
                        objectMapper.createObjectNode())));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.clarificationQuestions())
                .containsExactly("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        assertThat(result.pendingClarification()).isNotNull();
        assertThat(result.pendingClarification().sourcePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(result.pendingClarification().questions())
                .containsExactly("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        assertThat(result.pendingClarification().clientTurnId()).isEqualTo("turn-2");
    }

    @Test
    void keepsConsultativeQuestionInExploreModeWhenHistoryHasSoftClarification() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Como visualizar a folha?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-5",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Me ajude a escolher um dashboard para folha de pagamento",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Voce pode seguir com essa visao e depois ajustar os graficos e metricas.",
                                null)),
                null));

        assertThat(result.effectivePrompt()).isEqualTo("Como visualizar a folha?");
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.valid()).isFalse();
        assertThat(result.quickReplies()).isNotEmpty();
    }
    @Test
    void resolvesNaturalConfirmationUsingSelectedResourceFromConversationHistory() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pode fazer agora",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-4",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei a melhor fonte de dados para o dashboard. Escolha a API principal.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m3",
                                "user",
                                "Usar Dashboard analitico (/api/human-resources/vw-analytics-folha-pagamento) como fonte de dados.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m4",
                                "assistant",
                                "Perfeito. Proximo passo: posso montar a previa usando essa fonte de dados.",
                                null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void resolvesNaturalPayrollDashboardConversationWithImperfectLanguageToExecutableCreate() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pode fazer agora",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-5",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "quero uma tela pra ve os pagamento dos funcionario, tipo um painel bonito",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Posso ajudar a escolher antes de criar. Para folha de pagamento, faz sentido um dashboard com grafico e detalhamento.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m3",
                                "user",
                                "nao sei se e dashbord ou relatorio, mas queria ver por setor",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m4",
                                "assistant",
                                "Entendi. Um dashboard por setor funciona melhor para esse caso.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m5",
                                "user",
                                "coloca grafico e uma lista embaixo pra conferir",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m6",
                                "assistant",
                                "Consigo montar essa combinacao. Se precisar, confirmamos a fonte de dados no proximo passo.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m7",
                                "user",
                                "se precisar usa os dados de folha de pagamento",
                                null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void asksForConcreteCustomBreakdownWhenPayrollDashboardAnswerIsOther() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "outro",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-3",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual recurso de negocio deve alimentar esta tela?", null),
                        new AgenticAuthoringConversationMessage("m3", "user", "folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m4", "assistant", "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard\n\nConfirmed: folha de pagamento",
                        List.of("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?"),
                        "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?",
                        "turn-2",
                        objectMapper.createObjectNode())));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("analytics-custom-breakdown-required");
        assertThat(result.clarificationQuestions())
                .containsExactly("Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "payroll-breakdown-role",
                        "payroll-breakdown-team",
                        "payroll-breakdown-base",
                        "payroll-breakdown-profile");
    }

    @Test
    void resolvesCustomPayrollBreakdownAfterOtherClarification() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "cargo",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-4",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual recurso de negocio deve alimentar esta tela?", null),
                        new AgenticAuthoringConversationMessage("m3", "user", "folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m4", "assistant", "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?", null),
                        new AgenticAuthoringConversationMessage("m5", "user", "outro", null),
                        new AgenticAuthoringConversationMessage("m6", "assistant", "Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro",
                        List.of("Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?"),
                        "Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?",
                        "turn-3",
                        objectMapper.createObjectNode())));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro\n\nConfirmed: cargo");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void carriesAttachmentSummariesInPendingClarificationDiagnostics() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard usando a imagem anexada",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-1",
                List.of(),
                null,
                List.of(new AgenticAuthoringAttachmentSummary(
                        "attachment-1",
                        "referencia.png",
                        "image",
                        "image/png",
                        12345L,
                        "paste",
                        true))));

        assertThat(result.valid()).isFalse();
        assertThat(result.pendingClarification()).isNotNull();
        JsonNode attachmentSummaries = result.pendingClarification().diagnostics().path("attachmentSummaries");
        assertThat(attachmentSummaries).hasSize(1);
        assertThat(attachmentSummaries.get(0).path("name").asText()).isEqualTo("referencia.png");
        assertThat(attachmentSummaries.get(0).path("mimeType").asText()).isEqualTo("image/png");
        assertThat(attachmentSummaries.toString()).doesNotContain("blob:");
    }

    @Test
    void resolvesShortClarificationAnswerUsingConversationHistoryWhenPendingStateIsMissing() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "por departamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-3",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual tema do dashboard?", null),
                        new AgenticAuthoringConversationMessage("m3", "user", "folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m4", "assistant", "Qual recorte do dashboard?", null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: por departamento");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void suggestsApproximatePayrollEndpointOptionsWhenPromptHasOnlyBareDomainTerm() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("unknown");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.changeKind()).isEqualTo("unknown");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/human-resources/folhas-pagamento");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::operation)
                .containsExactly("post", "get");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes())
                .containsExactly(
                        "intent-operation-unknown",
                        "intent-artifact-unknown",
                        "resource-candidate-ambiguous");
        assertThat(result.clarificationQuestions())
                .contains(
                        "O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?",
                        "Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?",
                        "Encontrei recursos proximos: /api/human-resources/vw-analytics-folha-pagamento (POST), /api/human-resources/folhas-pagamento (GET). Qual deles voce quer usar?");
    }

    @Test
    void resolvesPayrollTableFollowUpToOperationalPayrollCollectionBeforeConfirmation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero visualizar folhas de pagamento em uma tabela",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("recommend_table_visualization");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
        assertThat(result.assistantMessage()).contains("melhores opcoes");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "payroll-executive-dashboard",
                        "payroll-department-drilldown",
                        "payroll-detail-table");
        assertThat(result.clarificationQuestions())
                .containsExactly("Posso criar uma tabela operacional de folhas de pagamento usando /api/human-resources/folhas-pagamento?");
    }

    @Test
    void answersBarePayrollClarificationWithDashboardConfirmationQuestion() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero visualizar a folha por departamento em um dashboard",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "pagamento", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "O que voce quer fazer com esse tema?", null)),
                new AgenticAuthoringPendingClarification(
                        "pagamento",
                        List.of("O que voce quer fazer com esse tema?"),
                        "O que voce quer fazer com esse tema?",
                        "turn-1",
                        null)));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.assistantMessage()).isNull();
        assertThat(result.clarificationQuestions())
                .containsExactly("Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm", "revise", "cancel");
    }

    @Test
    void resolvesDirectPayrollTableCreationWhenPromptHasEnoughContext() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela operacional de folhas de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
    }

    @Test
    void resolvesSelectedTableTitleModificationAgainstExistingPage() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-table");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/folhas-pagamento");
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Altere o titulo da tabela para Folha operacional revisada",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-table",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("rename_or_relabel");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-table");
        assertThat(result.target().componentId()).isEqualTo("praxis-table");
        assertThat(result.target().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesSelectedTableCurrencyFormatModificationAgainstExistingPage() {
        ObjectNode page = payrollTablePage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Formate a coluna salario liquido da tabela como moeda em reais",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-table",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("set_column_format");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-table");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesSelectedTableVisibilityModificationAgainstExistingPage() {
        ObjectNode page = payrollTablePage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Oculte a coluna total descontos da tabela",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-table",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("set_column_visibility");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-table");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesSelectedTableOrderModificationAgainstExistingPage() {
        ObjectNode page = payrollTablePage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Mova a coluna salario liquido da tabela para o inicio",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-table",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.changeKind()).isEqualTo("set_column_order");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-table");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesSelectedChartTypeModificationAgainstExistingPage() {
        ObjectNode page = payrollChartPage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Troque o grafico para linha",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-chart",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("set_chart_type");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-chart");
        assertThat(result.target().componentId()).isEqualTo("praxis-chart");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesSelectedChartValueFormatBeforeGenericChartTypeModification() {
        ObjectNode page = payrollChartPage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "No grafico selecionado, formate o eixo y e os valores em moeda BRL",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "payroll-chart",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("set_chart_value_format");
        assertThat(result.target().widgetKey()).isEqualTo("payroll-chart");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void resolvesDashboardWidgetAdditionWithoutSelectedTargetWidget() {
        ObjectNode page = payrollChartPage();

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione um novo componente de resumo executivo com total de salarios, total de descontos e media salarial",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("add_dashboard_widget");
        assertThat(result.target()).isNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void asksForBreakdownWhenPayrollDashboardCreationLacksAnalyticsDimension() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("analytics-breakdown-required");
        assertThat(result.clarificationQuestions())
                .containsExactly("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "payroll-breakdown-department",
                        "payroll-breakdown-competence",
                        "payroll-breakdown-status");
    }

    @Test
    void metadataBackedPayrollDashboardIgnoresTechnicalSchemaEndpointsAndAsksForBreakdown() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento/schemas",
                        "GET",
                        "human-resources,folha,pagamento",
                        "Schema tecnico de folhas de pagamento",
                        "Endpoint auxiliar de schema",
                        "folhasPagamentoSchemas",
                        null,
                        null,
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        "human-resources,folha,pagamento",
                        "Lista folhas de pagamento",
                        "Consulta operacional de folhas de pagamento",
                        "listFolhasPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para dashboards de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard de folha de pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .doesNotContain("/api/human-resources/folhas-pagamento/schemas");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("analytics-breakdown-required");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "payroll-breakdown-department",
                        "payroll-breakdown-competence",
                        "payroll-breakdown-status");
    }

    @Test
    void metadataBackedConsultativePayrollDashboardPromptSelectsAnalyticsCandidate() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        "human-resources,folha,pagamento",
                        "Lista folhas de pagamento",
                        "Consulta operacional de folhas de pagamento",
                        "listFolhasPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para dashboards de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        for (String prompt : List.of(
                "Quais outras opcoes para dashboards de folha voce indica antes de criar?",
                "Compare alternativas para acompanhar salarios, descontos e departamentos",
                "Estou revisando o catalogo de componentes do Praxis UI e quero uma recomendacao antes de aplicar mudancas. Para folha de pagamento, devo usar grafico com drill down, tabela operacional, indicadores ou uma composicao com todos eles?",
                "Considere as capacidades documentadas dos componentes, incluindo selecao no chart, cross filter para tabela e resumo de detalhe. Quais opcoes de dashboard de folha voce recomenda para gestores de RH?")) {
            AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                    prompt,
                    "praxis-ui-angular",
                    "praxis-dynamic-page-builder",
                    "/page-builder-ia",
                    objectMapper.createObjectNode(),
                    null,
                    null,
                    null,
                    null));

            assertThat(result.valid()).isFalse();
            assertThat(result.operationKind()).isEqualTo("explore");
            assertThat(result.artifactKind()).isEqualTo("dashboard");
            assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
            assertThat(result.selectedCandidate().resourcePath())
                    .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
            assertThat(result.gate().status()).isEqualTo("clarification_required");
            assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
        }
    }

    @Test
    void metadataBackedApiCatalogQuestionListsEndpointsWithoutStartingPageGeneration() {
        AgenticAuthoringIntentResolverService metadataBackedService = metadataBackedPayrollService();

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quais APIs de folha de pagamento existem?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertApiCatalogAnswer(result);
        assertThat(result.assistantMessage()).contains("APIs candidatas encontradas");
        assertThat(result.assistantMessage()).contains("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.assistantMessage()).contains("/api/human-resources/folhas-pagamento");
        assertThat(result.quickReplies()).extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("api-create-dashboard", "api-show-schema", "api-show-actions");
    }

    @Test
    void metadataBackedApiCatalogQuestionAnswersSchemaActionsFiltersAndApiChoice() {
        AgenticAuthoringIntentResolverService metadataBackedService = metadataBackedPayrollService();

        AgenticAuthoringIntentResolutionResult schema = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Que campos existem no schema da API /api/human-resources/vw-analytics-folha-pagamento?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));
        assertApiCatalogAnswer(schema);
        assertThat(schema.assistantMessage()).contains("/schemas/filtered");
        assertThat(schema.assistantMessage()).contains("schemaType=response");
        assertThat(schema.apiCatalogAnswer().path("questionType").asText()).isEqualTo("schema");
        assertThat(schema.apiCatalogAnswer().path("schemaFields").size()).isGreaterThan(0);

        AgenticAuthoringIntentResolutionResult actions = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Essa API permite criar, editar ou excluir folha de pagamento?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));
        assertApiCatalogAnswer(actions);
        assertThat(actions.assistantMessage()).contains("/schemas/actions");
        assertThat(actions.apiCatalogAnswer().path("questionType").asText()).isEqualTo("actions");
        assertThat(actions.apiCatalogAnswer().path("actions").size()).isGreaterThan(0);

        AgenticAuthoringIntentResolutionResult filters = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "A API /api/human-resources/folhas-pagamento suporta filtros?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));
        assertApiCatalogAnswer(filters);
        assertThat(filters.assistantMessage().toLowerCase()).contains("filtros");
        assertThat(filters.assistantMessage()).contains("/schemas/surfaces");
        assertThat(filters.apiCatalogAnswer().path("questionType").asText()).isEqualTo("filters");
        assertThat(filters.apiCatalogAnswer().path("filterParameters").size()).isGreaterThan(0);

        AgenticAuthoringIntentResolutionResult choice = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Qual endpoint devo usar para um dashboard de folha de pagamento antes de gerar a pagina?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));
        assertApiCatalogAnswer(choice);
        assertThat(choice.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(choice.assistantMessage()).contains("Recomendacao");
        assertThat(choice.assistantMessage()).contains("antes de gerar a pagina");
        assertThat(choice.apiCatalogAnswer().path("questionType").asText()).isEqualTo("api_choice");
        assertThat(choice.apiCatalogAnswer().path("recommendations").size()).isGreaterThan(0);
    }

    @Test
    void metadataBackedApiCatalogQuestionAnswersRelatedApisWithCatalogEvidence() {
        AgenticAuthoringIntentResolverService metadataBackedService = metadataBackedPayrollService();

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quais APIs relacionadas eu devo combinar para fazer drill-down da folha por departamento e abrir o detalhe operacional?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertApiCatalogAnswer(result);
        assertThat(result.assistantMessage()).contains("APIs relacionadas");
        assertThat(result.assistantMessage()).contains("/api/human-resources/folhas-pagamento");
        assertThat(result.assistantMessage()).contains("campos em comum");
        assertThat(result.assistantMessage()).contains("API analitica");
        assertThat(result.assistantMessage()).contains("operacional");
        assertThat(result.apiCatalogAnswer().path("questionType").asText()).isEqualTo("related_apis");
        assertThat(result.apiCatalogAnswer().path("relatedApis").size()).isGreaterThan(0);
    }

    private void assertApiCatalogAnswer(AgenticAuthoringIntentResolutionResult result) {
        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_api_catalog_question");
        assertThat(result.authoringProfile()).isEqualTo("api-catalog-qa");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.assistantMessage()).isNotBlank();
        assertThat(result.apiCatalogAnswer()).isNotNull();
        assertThat(result.apiCatalogAnswer().path("candidateApis").size()).isGreaterThan(0);
    }

    private AgenticAuthoringIntentResolverService metadataBackedPayrollService() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        List<ApiMetadata> metadata = List.of(
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        "human-resources,folha,pagamento,operacional",
                        "Lista folhas de pagamento",
                        "Consulta operacional de folhas de pagamento com filtros por funcionario, departamento e competencia",
                        "listFolhasPagamento",
                        null,
                        "{\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"departmentId\",\"type\":\"string\"},{\"name\":\"employeeId\",\"type\":\"string\"},{\"name\":\"competencia\",\"type\":\"string\"},{\"name\":\"salario\",\"type\":\"number\"}]}",
                        "[{\"name\":\"departmentId\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"competencia\",\"in\":\"query\",\"type\":\"string\"}]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,operacional",
                        "Cria folha de pagamento",
                        "Operacao de escrita para criar folha de pagamento",
                        "createFolhaPagamento",
                        "{\"fields\":[{\"name\":\"departmentId\",\"type\":\"string\"},{\"name\":\"employeeId\",\"type\":\"string\"},{\"name\":\"competencia\",\"type\":\"string\"},{\"name\":\"salario\",\"type\":\"number\"}]}",
                        "{\"fields\":[{\"name\":\"id\",\"type\":\"string\"}]}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para dashboards de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"fields\":[{\"name\":\"departmentId\",\"type\":\"string\"},{\"name\":\"departamento\",\"type\":\"string\"},{\"name\":\"competencia\",\"type\":\"string\"},{\"name\":\"totalSalarios\",\"type\":\"number\"},{\"name\":\"quantidadeFuncionarios\",\"type\":\"integer\"}]}",
                        "[{\"name\":\"competencia\",\"in\":\"query\",\"type\":\"string\"}]",
                        "{}",
                        null));
        Mockito.when(repository.findAll()).thenReturn(metadata);
        return new AgenticAuthoringIntentResolverService(
                objectMapper,
                new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                new AgenticAuthoringApiCatalogConversationService(objectMapper, repository));
    }

    @Test
    void metadataBackedBarePayrollTermSuggestsOnlyCanonicalRenderableResources() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata("/api/human-resources/folhas-pagamento/{id}", "GET", "folha,pagamento", "Busca folha por id", null, "getFolha", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/all", "GET", "folha,pagamento", "Lista todas as folhas", null, "allFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/by-ids", "GET", "folha,pagamento", "Busca folhas por ids", null, "byIdsFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/options/filter", "GET", "folha,pagamento", "Opcoes de filtro", null, "filterOptionsFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/filter", "GET", "folha,pagamento", "Filtro de folhas", null, "filterFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/filter/cursor", "GET", "folha,pagamento", "Filtro cursor de folhas", null, "filterCursorFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/batch", "GET", "folha,pagamento", "Operacao batch de folhas", null, "batchFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento/locate", "GET", "folha,pagamento", "Localizacao de folha", null, "locateFolhas", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento", "POST", "folha,pagamento", "Cria folha de pagamento", "Endpoint de escrita de folhas de pagamento", "createFolhaPagamento", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/folhas-pagamento", "GET", "folha,pagamento", "Folhas de pagamento", "Recurso operacional de folhas de pagamento", "listFolhasPagamento", null, null, "[]", "{}", null),
                new ApiMetadata("/api/human-resources/vw-analytics-folha-pagamento", "GET", "analytics,folha,pagamento", "Analytics de folha de pagamento", "Visao analitica de folha de pagamento", "listVwAnalyticsFolhaPagamento", null, null, "[]", "{}", null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pagamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/human-resources/folhas-pagamento");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes())
                .containsExactly(
                        "intent-operation-unknown",
                        "intent-artifact-unknown",
                        "resource-candidate-ambiguous");
    }

    @Test
    void metadataBackedGenericDashboardPromptOffersResourceCandidatesAsRichQuickReplies() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para dashboard de folha de pagamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/operations/vw-analytics-incidentes",
                        "GET",
                        "operations,analytics,incidentes",
                        "Analytics de incidentes",
                        "Visao analitica para dashboard operacional de incidentes",
                        "listVwAnalyticsIncidentes",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/praxis/config/ai/providers",
                        "GET",
                        "config,ai",
                        "Providers de IA",
                        "Endpoint de configuracao interna que nao deve ser priorizado para dashboard de negocio",
                        "listAiProviders",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/operations/vw-analytics-incidentes");
        assertThat(result.candidates())
                .flatExtracting(AgenticAuthoringCandidate::evidence)
                .contains("broad-artifact-discovery");
        assertThat(result.failureCodes()).containsExactly("resource-candidate-ambiguous");
        assertThat(result.assistantMessage())
                .contains("Encontrei mais de uma fonte de dados possivel para este dashboard")
                .contains("Escolha a API que melhor representa o recorte de negocio")
                .contains("/api/human-resources/vw-analytics-folha-pagamento/all")
                .contains("/api/operations/vw-analytics-incidentes/all");
        assertThat(result.clarificationQuestions().get(0))
                .contains("/api/human-resources/vw-analytics-folha-pagamento")
                .contains("/api/operations/vw-analytics-incidentes");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "resource-api-human-resources-vw-analytics-folha-pagamento",
                        "resource-api-operations-vw-analytics-incidentes");
        AgenticAuthoringQuickReply firstReply = result.quickReplies().get(0);
        assertThat(firstReply.description())
                .isEqualTo("GET /api/human-resources/vw-analytics-folha-pagamento/all");
        assertThat(firstReply.icon()).isEqualTo("query_stats");
        assertThat(firstReply.tone()).isEqualTo("analytics");
        assertThat(firstReply.contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(firstReply.prompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: usar /api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void metadataBackedGenericDashboardSelectionUsesQuickReplyResourceContext() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringQuickReply repeatedResourceReply = new AgenticAuthoringQuickReply(
                "resource-api-human-resources-vw-ranking-reputacao",
                "suggestion",
                "Ranking reputacao",
                "Usar /api/human-resources/vw-ranking-reputacao",
                "POST /api/human-resources/vw-ranking-reputacao/filter/cursor",
                "query_stats",
                "analytics",
                objectMapper.createObjectNode().put("resourcePath", "/api/human-resources/vw-ranking-reputacao"));
        Mockito.when(llmIntentResolver.resolve(
                Mockito.any(),
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        null,
                        null,
                        "clarification_answer",
                        "Encontrei mais de uma fonte de dados possivel para este dashboard.",
                        List.of(repeatedResourceReply),
                        List.of(),
                        List.of())));
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                        "POST",
                        "human-resources,reputacao,ranking",
                        "Ranking de reputacao",
                        "Ranking de reputacao dos herois",
                        "rankingReputacaoCursor",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-perfil-heroi/filter",
                        "POST",
                        "human-resources,perfil,heroi",
                        "Perfil do heroi",
                        "Perfil consolidado dos herois",
                        "perfilHeroiFilter",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        null,
                        llmIntentResolver,
                        new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-ranking-reputacao");
        contextHints.put("submitUrl", "/api/human-resources/vw-ranking-reputacao/filter/cursor");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard\n\nConfirmed: usar /api/human-resources/vw-ranking-reputacao",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Encontrei mais de uma fonte de dados possivel para este dashboard.", null)),
                new AgenticAuthoringPendingClarification(
                        "Crie um dashboard",
                        List.of("Qual API deve alimentar este dashboard?"),
                        "Encontrei mais de uma fonte de dados possivel para este dashboard.",
                        "turn-1",
                        null),
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao/filter/cursor");
        assertThat(result.failureCodes()).doesNotContain("resource-candidate-ambiguous");
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void discoversCandidateFromApiMetadataWhenEndpointIsNotInKnownQuickstartFallback() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/beneficios/schemas",
                        "GET",
                        "human-resources,beneficios",
                        "Schema tecnico de beneficios",
                        "Endpoint auxiliar de schema que nao deve ser tratado como recurso renderizavel",
                        "beneficiosSchemas",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/beneficios",
                        "GET",
                        "human-resources,beneficios",
                        "Lista beneficios corporativos",
                        "Consulta beneficios disponiveis para colaboradores",
                        "listBeneficios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tabela de beneficios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/beneficios");
        assertThat(result.selectedCandidate().operation()).isEqualTo("get");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/beneficios/all&operation=get&schemaType=response");
        assertThat(result.selectedCandidate().evidence()).contains("api-metadata");
        assertThat(result.gate().status()).isEqualTo("eligible");
    }

    @Test
    void doesNotSelectLegacyHelpdeskEndpointWhenQuickstartMetadataDoesNotExposeIt() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario didatico so com os campos realmente necessarios para abrir chamados para notebooks com a tela quebrada",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("create_minimal_form");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .doesNotContain("/api/helpdesk/chamados");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("resource-candidate-required");
        assertThat(result.clarificationQuestions()).containsExactly("Qual recurso de negocio deve alimentar esta tela?");
        assertThat(result.assistantMessage())
                .contains("Consigo criar o formulario")
                .contains("Posso buscar APIs de criacao no catalogo");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("search-api-resources", "describe-business-domain", "cancel");
        assertThat(result.quickReplies().get(0).contextHints().path("tool").asText())
                .isEqualTo("searchApiResources");
    }

    @Test
    void metadataBackedHelpdeskLikePromptSuggestsQuickstartApproximateResourcesBeforePreview() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/operations/sinais-socorro",
                        "POST",
                        "operations,socorro,alertas",
                        "Cadastrar sinal de socorro",
                        "Cadastra novo alerta para triagem operacional de incidentes e ocorrencias",
                        "createSinaisSocorro",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/operations/incidentes",
                        "POST",
                        "operations,incidentes,triagem,operacional",
                        "Cadastrar incidente de missao",
                        "Cadastra ocorrencia operacional para triagem de alertas e sinais de socorro",
                        "createIncidente",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario didatico so com os campos realmente necessarios para abrir chamados para notebooks com a tela quebrada",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/operations/sinais-socorro",
                        "/api/operations/incidentes");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("resource-candidate-ambiguous");
        assertThat(result.clarificationQuestions())
                .containsExactly("Encontrei recursos proximos: /api/operations/sinais-socorro (POST), /api/operations/incidentes (POST). Qual deles voce quer usar?");
    }

    @Test
    void metadataBackedPromptFallsBackToBroadArtifactDiscoveryBeforeAskingForManualResource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/beneficios",
                        "POST",
                        "human-resources,beneficios",
                        "Cadastrar beneficio",
                        "Cadastra beneficios para colaboradores",
                        "createBeneficio",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios",
                        "Cadastrar funcionario",
                        "Cadastra funcionarios",
                        "createFuncionario",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um formulario para registrar manutencoes preventivas",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("resource-candidate-ambiguous");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly(
                        "/api/human-resources/beneficios",
                        "/api/human-resources/funcionarios");
        assertThat(result.candidates().get(0).evidence()).contains("broad-artifact-discovery");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "resource-api-human-resources-beneficios",
                        "resource-api-human-resources-funcionarios");
        assertThat(result.quickReplies().get(0).contextHints().path("tool").isMissingNode())
                .isTrue();
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/beneficios");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("schemaVersion").asText())
                .isEqualTo("praxis.ai.context-hints.domain-catalog/v0.2");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("serviceKey").asText())
                .isEqualTo("praxis-service");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("contextKey").asText())
                .isEqualTo("human-resources");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("resourceKey").asText())
                .isEqualTo("human-resources.beneficios");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("query").asText())
                .contains("manutencoes preventivas")
                .contains("beneficios");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("enabled").asBoolean())
                .isTrue();
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("federated").asBoolean())
                .isTrue();
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("query").asText())
                .contains("manutencoes preventivas")
                .contains("beneficios");
    }

    @Test
    void contextHintRecommendedSharedRuleAuthoringReturnsRouteRequiredInsteadOfPreviewEligible() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/funcionarios");
        ObjectNode domainCatalog = contextHints.putObject("domainCatalog");
        domainCatalog.put("schemaVersion", "praxis.ai.context-hints.domain-catalog/v0.2");
        domainCatalog.put("serviceKey", "praxis-service");
        domainCatalog.put("resourceKey", "human-resources.funcionarios");
        domainCatalog.put("type", "governance");
        domainCatalog.put("intent", "authoring");
        domainCatalog.put("query", "cpf lgpd funcionarios");
        domainCatalog.put("recommendedAuthoringFlow", "shared_rule_authoring");
        domainCatalog.put("limit", 12);

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma regra LGPD para CPF",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextHints));

        assertThat(result.valid()).isFalse();
        assertThat(result.gate().status()).isEqualTo("route_required");
        assertThat(result.failureCodes()).contains("shared-rule-authoring-required");
        assertThat(result.assistantMessage())
                .contains("/api/praxis/config/domain-rules")
                .contains("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void businessRulePromptRoutesToSharedRuleAuthoringWithoutExplicitDomainCatalogHint() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.gate().status()).isEqualTo("route_required");
        assertThat(result.failureCodes()).contains("shared-rule-authoring-required");
        assertThat(result.assistantMessage())
                .contains("/api/praxis/config/domain-rules")
                .contains("/api/procurement/suppliers");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/procurement/suppliers");
        assertThat(result.selectedCandidate().evidence()).contains("known-quickstart-procurement-resource");
    }

    @Test
    void metadataBackedResourceQuickReplyIdsRemainUniqueWhenResourcePathRepeats() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios",
                        "Cadastrar funcionario",
                        "Cadastra funcionarios",
                        "createFuncionario",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        "human-resources,funcionarios",
                        "Filtrar funcionarios",
                        "Consulta funcionarios para selecionar a ficha",
                        "filterFuncionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        "human-resources,funcionarios",
                        "Cursor funcionarios",
                        "Consulta paginada de funcionarios",
                        "cursorFuncionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "preciso monta uma ficha pra cadastra funsionario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .doesNotHaveDuplicates();
    }

    @Test
    void resolvesModifyAddFieldAgainstExistingDynamicForm() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione o campo salario no formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("add_field");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.target().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.currentPageSummary().path("formWidgets").size()).isEqualTo(1);
    }

    private ObjectNode payrollTablePage() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-table");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/folhas-pagamento");
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");
        return page;
    }

    private ObjectNode payrollChartPage() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-chart");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject()
                .put("id", "salario-liquido")
                .put("categoryField", "departamento")
                .putObject("metric")
                .put("field", "salarioLiquido");
        config.putObject("dataSource")
                .put("kind", "remote")
                .put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        return page;
    }

    @Test
    void summarizesExistingFormFieldsForAgenticEdits() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");
        ObjectNode config = inputs.putObject("config");
        var fieldMetadata = config.putArray("fieldMetadata");
        ObjectNode nome = fieldMetadata.addObject();
        nome.put("name", "nome");
        nome.put("label", "Nome completo do colaborador");
        nome.put("controlType", "text");
        ObjectNode observacaoInterna = fieldMetadata.addObject();
        observacaoInterna.put("name", "observacaoInterna");
        observacaoInterna.put("label", "Observacao interna");
        observacaoInterna.put("controlType", "textarea");
        observacaoInterna.put("source", "local");
        observacaoInterna.put("transient", true);
        observacaoInterna.put("submitPolicy", "omit");
        var sections = config.putArray("sections");
        ObjectNode section = sections.addObject();
        var rows = section.putArray("rows");
        ObjectNode row = rows.addObject();
        var columns = row.putArray("columns");
        ObjectNode column = columns.addObject();
        column.putArray("fields").add("observacaoInterna");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Remova o campo observacaoInterna do formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        var formSummary = result.currentPageSummary().path("formWidgets").get(0);
        assertThat(formSummary.path("fieldCount").asInt()).isEqualTo(2);
        assertThat(formSummary.path("localFieldCount").asInt()).isEqualTo(1);
        assertThat(formSummary.path("fieldNames")).extracting(JsonNode::asText)
                .containsExactly("nome", "observacaoInterna");
        assertThat(formSummary.path("localFieldNames")).extracting(JsonNode::asText)
                .containsExactly("observacaoInterna");
        assertThat(formSummary.path("serverBackedOverrideNames")).extracting(JsonNode::asText)
                .containsExactly("nome");
        assertThat(formSummary.path("layoutFieldNames")).extracting(JsonNode::asText)
                .containsExactly("observacaoInterna");
        assertThat(formSummary.path("fieldMetadata").get(0).path("label").asText())
                .isEqualTo("Nome completo do colaborador");
        assertThat(formSummary.path("fieldMetadata").get(1).path("source").asText()).isEqualTo("local");
        assertThat(formSummary.path("fieldMetadata").get(1).path("transient").asBoolean()).isTrue();
        assertThat(formSummary.path("fieldMetadata").get(1).path("submitPolicy").asText()).isEqualTo("omit");
    }

    @Test
    void resolvesModifyRelabelBeforeGenericFieldAddition() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Renomeie o campo nome para Nome completo do colaborador",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("rename_or_relabel");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void keepsExecutableLocalFieldPromptOutOfApiCatalogQuestionTrack() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione exatamente um campo local chamado observacaoInterna para triagem, que nao deve ser enviado para a API",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("add_field");
        assertThat(result.authoringProfile()).isEqualTo("create-minimal-form");
        assertThat(result.apiCatalogAnswer()).isNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void resolvesRemoveFieldAgainstExistingDynamicForm() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Remova o campo observacaoInterna do formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("remove");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.changeKind()).isEqualTo("remove_field");
        assertThat(result.authoringProfile()).isEqualTo("create-minimal-form");
        assertThat(result.target().widgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void asksClarificationWhenModifyHasNoTargetWidget() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Adicione prioridade no formulario",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("target-widget-required");
        assertThat(result.clarificationQuestions()).contains("Qual componente existente deve ser alterado?");
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> service.resolve(new AgenticAuthoringIntentResolutionRequest(
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userPrompt must not be blank.");
    }
}
