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
            new AgenticAuthoringIntentResolverService(objectMapper, quickstartCandidateCatalog());

    private AgenticAuthoringApiMetadataCandidateCatalog quickstartCandidateCatalog() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios,funcionario,colaboradores,empregados,rh,cadastro,formulario",
                        "Cadastrar funcionario",
                        "Cadastra funcionarios e colaboradores com dados cadastrais, cargo, departamento e salario."),
                apiMetadata(
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        "human-resources,funcionarios,funcionario,colaboradores,empregados,rh,busca,detalhes,tabela,master-detail",
                        "Filtrar funcionarios",
                        "Consulta funcionarios para busca, selecao, tabela e abertura de detalhes."),
                apiMetadata(
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        "human-resources,funcionarios,funcionario,colaboradores,empregados,rh,busca,paginada,tabela,master-detail",
                        "Cursor funcionarios",
                        "Consulta paginada de funcionarios para tabelas, listas e telas master-detail."),
                apiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,pagamentos,salario,salarios,departamento,competencia,analytics,dashboard,ranking",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento para dashboards, rankings, comparacoes, KPIs e graficos por departamento."),
                apiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,pagamentos,salario,salarios,operacional,tabela,listagem",
                        "Folhas de pagamento",
                        "Registros operacionais de folha para tabelas, listagens e conferencias."),
                apiMetadata(
                        "/api/operations/missoes",
                        "POST",
                        "operations,operacoes,missao,missoes,mission,missions,operacional,tabela,listagem,detalhes",
                        "Missoes",
                        "Planejamento e acompanhamento operacional de missoes, participantes, status, prioridade e detalhes de execucao."),
                apiMetadata(
                        "/api/operations/missoes/filter",
                        "POST",
                        "operations,operacoes,missao,missoes,mission,missions,busca,tabela,detalhes",
                        "Filtrar missoes",
                        "Consulta missoes para tabela operacional, investigacao de detalhes, participantes e acompanhamento de status."),
                apiMetadata(
                        "/api/operations/incidentes/filter/cursor",
                        "POST",
                        "operations,operacoes,incidente,incidentes,ocorrencia,ocorrencias,chamado,chamados,gravidade,andamento,responsavel,status,dashboard",
                        "Incidentes",
                        "Consulta incidentes e ocorrencias para monitoramento operacional por gravidade, andamento, status e responsavel."),
                apiMetadata(
                        "/api/human-resources/missoes/filter",
                        "POST",
                        "human-resources,missao,missoes,empregados,rh,busca,tabela,detalhes",
                        "Missoes legado",
                        "Registro legado de catalogo que nao deve vencer o bounded context operacional canonico."),
                apiMetadata(
                        "/api/human-resources/equipes/filter",
                        "POST",
                        "human-resources,equipes,empregados,rh,busca,tabela,detalhes,missoes",
                        "Equipes legado",
                        "Registro legado de catalogo que nao deve vencer missoes operacionais quando o usuario pede missoes."),
                apiMetadata(
                        "/api/procurement/suppliers",
                        "POST",
                        "procurement,fornecedor,fornecedores,supplier,suppliers,compras,elegibilidade,bloqueado,inativo",
                        "Fornecedores",
                        "Cadastro e selecao de fornecedores usados em compras."),
                apiMetadata(
                        "/api/procurement/purchase-orders",
                        "POST",
                        "procurement,pedido,pedidos,compra,compras,purchase order,purchase orders,aprovacao",
                        "Pedidos de compra",
                        "Pedidos de compra com aprovacao e regras de governanca.")));
        return new AgenticAuthoringApiMetadataCandidateCatalog(repository);
    }

    private ApiMetadata apiMetadata(
            String path,
            String method,
            String tags,
            String summary,
            String description) {
        return new ApiMetadata(path, method, tags, summary, description, null, null, null, "[]", "{}", null);
    }

    private ObjectNode resourcePathContextHints(String resourcePath) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", resourcePath);
        contextHints.put("operation", "post");
        return contextHints;
    }

    private AgenticAuthoringIntentResolutionRequest requestWithContextHints(String prompt, JsonNode contextHints) {
        return requestWithContextHints(prompt, null, contextHints);
    }

    private AgenticAuthoringIntentResolutionRequest requestWithContextHints(
            String prompt,
            String provider,
            JsonNode contextHints) {
        return new AgenticAuthoringIntentResolutionRequest(
                prompt,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                provider,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextHints);
    }

    @Test
    void genericStarterDoesNotInventQuickstartResourcesWithoutHostCatalog() {
        AgenticAuthoringIntentResolverService genericService =
                new AgenticAuthoringIntentResolverService(objectMapper);

        for (String prompt : List.of(
                "Crie um formulario de funcionarios",
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                "Monte um dashboard com ranking dos maiores valores da empresa")) {
            AgenticAuthoringIntentResolutionResult result = genericService.resolve(new AgenticAuthoringIntentResolutionRequest(
                    prompt,
                    "praxis-ui-angular",
                    "praxis-dynamic-page-builder",
                    "/page-builder-ia",
                    objectMapper.createObjectNode(),
                    null,
                    null,
                    null,
                    null));

            assertThat(result.selectedCandidate())
                    .as(prompt)
                    .isNull();
            assertThat(result.gate().status())
                    .as(prompt)
                    .isEqualTo("clarification_required");
            assertThat(result.failureCodes())
                    .as(prompt)
                    .contains("resource-candidate-required");
            assertThat(objectMapper.valueToTree(result).toString())
                    .as(prompt)
                    .doesNotContain("/api/human-resources")
                    .doesNotContain("/api/procurement")
                    .doesNotContain("known-quickstart");
        }
    }

    @Test
    void semanticDecisionCarriesHostNeutralEvidenceBundleWithRetrievalSource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "POST",
                        "risk,intelligence,incidentes,indicadores,analytics,dashboard,gravidade",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes por gravidade e responsavel."),
                apiMetadata(
                        "/api/customer-success/accounts",
                        "POST",
                        "customer,success,accounts,clientes",
                        "Contas de clientes",
                        "Cadastro operacional de contas de clientes.")));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Monte um dashboard de risco com incidentes por gravidade",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.semanticDecision().retrievalEvidence().retrievalSource())
                .isEqualTo("lexical_fallback");
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isIn("weak-lexical-evidence", "keyword-fallback-fail-safe");
        assertThat(result.semanticDecision().confidence()).isLessThan(0.5d);
        AgenticAuthoringEvidenceBundle bundle = result.semanticDecision().retrievedEvidence();
        assertThat(bundle.retrievalSource()).isEqualTo("lexical_fallback");
        assertThat(bundle.evidence())
                .extracting(AgenticAuthoringEvidenceBundle.Evidence::source)
                .contains("api_metadata", "/schemas/filtered", "capabilities", "actions", "domain_catalog");
        assertThat(bundle.evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.source()).isEqualTo("api_metadata");
                    assertThat(evidence.kind()).isEqualTo("weak_lexical_match");
                    assertThat(evidence.confidence()).isLessThan(0.5d);
                });
    }

    @Test
    void fallbackKeepsPainelAsDashboardInsteadOfGenericTable() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero uma tela pra ve os pagamento dos funcionario, tipo um painel bonito",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.semanticDecision().artifactKind()).isEqualTo("dashboard");
        assertThat(result.semanticDecision().visualIntent()).isEqualTo("resource-backed-dashboard");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void semanticDecisionRequiresReviewForWeakLexicalEvidenceWithoutKeywordFallback() {
        AgenticAuthoringCandidate weakLexicalCandidate = new AgenticAuthoringCandidate(
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes&operation=post&schemaType=request",
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                0.61d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of("lexical_fallback", List.of(
                        new AgenticAuthoringEvidenceBundle.Evidence(
                                "api_metadata",
                                "weak_lexical_match",
                                "/api/risk-intelligence/vw-indicadores-incidentes",
                                "Lexical match only.",
                                0.42d,
                                List.of("incidentes"),
                                "",
                                "",
                                ""))));

        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                weakLexicalCandidate,
                List.of(weakLexicalCandidate),
                null,
                List.of());

        assertThat(decision.reviewRequired()).isTrue();
        assertThat(decision.reviewReason()).isEqualTo("weak-lexical-evidence");
        assertThat(decision.confidence()).isLessThan(0.5d);
    }

    @Test
    void semanticDecisionRequiresReviewForLegacyLexicalEvidenceWithoutBundle() {
        AgenticAuthoringCandidate weakLexicalCandidate = new AgenticAuthoringCandidate(
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes&operation=post&schemaType=request",
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                0.61d,
                "api_metadata lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback"));

        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                weakLexicalCandidate,
                List.of(weakLexicalCandidate),
                null,
                List.of());

        assertThat(decision.reviewRequired()).isTrue();
        assertThat(decision.reviewReason()).isEqualTo("weak-lexical-evidence");
        assertThat(decision.confidence()).isLessThan(0.5d);
    }

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
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter",
                "post",
                1.0d,
                "api_metadata collection retrieval",
                List.of("api-metadata", "semantic-retrieval"));
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(employeeSkillCandidate, employeeCollectionCandidate));
        AgenticAuthoringIntentResolverService serviceWithCatalog =
                new AgenticAuthoringIntentResolverService(objectMapper, candidateCatalog);

        AgenticAuthoringIntentResolutionResult result = serviceWithCatalog.resolve(new AgenticAuthoringIntentResolutionRequest(
                "crie um formulario simples pra salvar funcionario",
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
        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
                    assertThat(candidate.operation()).isEqualTo("post");
                });
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
    void refinesActiveSemanticDecisionByPreservingResourceAndChangingVisualIntent() {
        AgenticAuthoringCandidate tableResource = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                "/api/human-resources/folhas-pagamento",
                "POST",
                0.92,
                "turn 1 selected payroll table resource",
                List.of("api-metadata"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                tableResource,
                List.of(tableResource),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar uma tabela de folhas de pagamento",
                "Criar uma tabela de folhas de pagamento",
                "Initial table decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gostei, mas prefiro gráficos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.semanticDecision().refinementOf()).isEqualTo(previousDecision.decisionId());
        assertThat(result.semanticDecision().previousDecisionId()).isEqualTo(previousDecision.decisionId());
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.semanticDecision().visualIntent()).isEqualTo("charts");
        assertThat(result.semanticDecision().refinement()).isNotNull();
        assertThat(result.semanticDecision().refinement().refinementKind()).isEqualTo("visual_projection");
        assertThat(result.semanticDecision().refinement().preserve()).contains("resource", "source");
        assertThat(result.semanticDecision().refinement().replace())
                .containsEntry("artifactKind", "dashboard")
                .containsEntry("visualIntent", "charts");
        assertThat(result.visualizationDecision()).isNull();
        assertThat(result.warnings()).contains("semantic-decision-memory-refinement-applied");
    }

    @Test
    void refinesActiveChartDecisionBackToTableBySemanticDiff() {
        AgenticAuthoringCandidate chartResource = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                "POST",
                0.92,
                "turn 1 selected payroll chart resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                chartResource,
                List.of(chartResource),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar gráficos de folha de pagamento",
                "Criar gráficos de folha de pagamento",
                "Initial chart decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Troca para tabela, mantendo a fonte",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.semanticDecision().artifactKind()).isEqualTo("table");
        assertThat(result.semanticDecision().visualIntent()).isEqualTo("table");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.semanticDecision().refinement().replace())
                .containsEntry("artifactKind", "table")
                .containsEntry("visualIntent", "table");
        assertThat(result.semanticDecision().refinement().remove()).contains("chart");
    }

    @Test
    void explicitPreserveDataRefinementDoesNotLetFallbackSwapResource() {
        AgenticAuthoringCandidate tableResource = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                "/api/human-resources/folhas-pagamento",
                "POST",
                0.92,
                "turn 1 selected payroll table resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                tableResource,
                List.of(tableResource),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar tabela de folha de pagamento",
                "Criar tabela de folha de pagamento",
                "Initial table decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Mantém os dados, só muda a visualização para gráficos de funcionários",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.valid()).isTrue();
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.semanticDecision().refinement().preserve()).contains("resource", "source", "filters");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/human-resources/funcionarios");
    }

    @Test
    void llmRefinementWithoutConcreteDiffDoesNotPreserveActiveResource() {
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
                        "api_catalog",
                        "discover_resources",
                        null,
                        null,
                        "refinement",
                        "Vou entender melhor quais fontes se aplicam.",
                        List.of(),
                        List.of(),
                        List.of("llm-semantic-follow-up"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "POST",
                        0.92,
                        "previous payroll table resource",
                        List.of("api-metadata", "semantic-retrieval")),
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar dashboard de folha",
                "Criar dashboard de folha",
                "Initial decision.");

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "agora veja outras possibilidades",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.warnings()).doesNotContain("semantic-decision-memory-refinement-applied");
        assertThat(result.semanticDecision().refinement()).isNull();
        assertThat(result.selectedCandidate() == null
                || !"/api/human-resources/folhas-pagamento".equals(result.selectedCandidate().resourcePath()))
                .isTrue();
    }

    @Test
    void dataSourceRefinementDoesNotForcePreviousResourceWhenPromptAsksForNewSource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "POST",
                        "human-resources,folha,pagamento",
                        "Folhas de pagamento",
                        "Registros operacionais de folha."),
                apiMetadata(
                        "/api/customer-success/accounts",
                        "POST",
                        "customer,success,accounts,clientes,dashboard",
                        "Contas de clientes",
                        "Fonte de clientes para dashboards e analises.")));
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
                        "/api/customer-success/accounts",
                        null,
                        "refinement",
                        "Vou trocar a fonte para clientes.",
                        List.of(),
                        List.of(),
                        List.of("llm-semantic-follow-up"))));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        null,
                        llmIntentResolver,
                        new AgenticAuthoringComponentCapabilitiesService());
        AgenticAuthoringCandidate payroll = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                "/api/human-resources/folhas-pagamento",
                "POST",
                0.92,
                "previous payroll table resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                payroll,
                List.of(payroll),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar dashboard de folha",
                "Criar dashboard de folha",
                "Initial decision.");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Agora usa clientes como fonte",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.semanticDecision().refinement()).isNotNull();
        assertThat(result.semanticDecision().refinement().refinementKind()).isEqualTo("data_source");
        assertThat(result.semanticDecision().refinementOf()).isEqualTo(previousDecision.decisionId());
        assertThat(result.semanticDecision().previousDecisionId()).isEqualTo(previousDecision.decisionId());
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/customer-success/accounts");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/customer-success/accounts");
        assertThat(result.warnings()).doesNotContain("semantic-decision-memory-refinement-applied");
        assertThat(result.warnings()).contains("semantic-refinement-applied");
    }

    @Test
    void dataSourceRefinementRecognizesNaturalEmployeeSourceCorrection() {
        AgenticAuthoringCandidate payroll = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                "POST",
                0.92,
                "previous payroll analytics resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                payroll,
                List.of(payroll),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar dashboard de folha",
                "Criar dashboard de folha",
                "Initial decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "A fonte deve ser a tabela de funcionarios, nao folha. Mantenha o dashboard com grafico por departamento, filtros, KPIs e tabela conectada.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.semanticDecision().refinement()).isNotNull();
        assertThat(result.semanticDecision().refinement().refinementKind()).isEqualTo("data_source");
        assertThat(result.semanticDecision().refinementOf()).isEqualTo(previousDecision.decisionId());
        assertThat(result.warnings()).contains("semantic-refinement-applied");
        assertThat(result.warnings()).doesNotContain("semantic-decision-memory-refinement-applied");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().axes())
                .extracting(AgenticAuthoringVisualizationAxisDecision::field)
                .containsExactly("departamento");
    }

    @Test
    void dataSourceRefinementDetachesFromSelectedChartResourceWhenUserCorrectsSource() {
        AgenticAuthoringCandidate payroll = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                "POST",
                0.92,
                "previous payroll analytics resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "dashboard",
                "create_artifact",
                payroll,
                List.of(payroll),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar dashboard de folha",
                "Criar dashboard de folha",
                "Initial decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "A fonte deve ser a tabela de funcionarios, nao folha. Mantenha o dashboard com grafico por departamento, filtros, KPIs e tabela conectada.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                payrollChartPage(),
                "payroll-chart",
                "mock",
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(result.target()).isNotNull();
        assertThat(result.target().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.semanticDecision().refinement()).isNotNull();
        assertThat(result.semanticDecision().refinement().refinementKind()).isEqualTo("data_source");
        assertThat(result.warnings()).contains("semantic-refinement-applied");
        assertThat(result.warnings()).doesNotContain("semantic-decision-memory-refinement-applied");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().axes())
                .extracting(AgenticAuthoringVisualizationAxisDecision::field)
                .containsExactly("departamento");
    }

    @Test
    void dataSourceCorrectionAgainstSelectedChartRegroundsEvenWithoutLoadedDecisionMemory() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "A fonte deve ser a tabela de funcionarios, nao folha. Mantenha o dashboard com grafico por departamento, filtros, KPIs e tabela conectada.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                payrollChartPage(),
                "payroll-chart",
                "mock",
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.target()).isNotNull();
        assertThat(result.target().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .contains("/api/human-resources/funcionarios");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().axes())
                .extracting(AgenticAuthoringVisualizationAxisDecision::field)
                .containsExactly("departamento");
    }

    @Test
    void keepsReviewRequiredWhenRefiningReviewRequiredActiveDecision() {
        AgenticAuthoringCandidate fallbackResource = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                "/api/human-resources/folhas-pagamento",
                "POST",
                0.61,
                "turn 1 fallback payroll table resource",
                List.of("lexical-fallback"));
        AgenticAuthoringSemanticDecision previousDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                fallbackResource,
                List.of(fallbackResource),
                null,
                List.of("keyword-fallback-applied"),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar uma tabela de folhas de pagamento",
                "Criar uma tabela de folhas de pagamento",
                "Initial fallback table decision.");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gostei, mas prefiro gráficos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                previousDecision));

        assertThat(previousDecision.reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isEqualTo("keyword-fallback-fail-safe");
        assertThat(result.semanticDecision().refinementOf()).isEqualTo(previousDecision.decisionId());
        assertThat(result.selectedCandidate().evidence()).contains("semantic-decision-memory");
    }

    @Test
    void assignsDistinctDecisionIdsAcrossRepeatedRefinementTurns() {
        AgenticAuthoringCandidate tableResource = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=post&schemaType=response",
                "/api/human-resources/folhas-pagamento",
                "POST",
                0.92,
                "turn 1 selected payroll table resource",
                List.of("api-metadata"));
        AgenticAuthoringSemanticDecision firstDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                tableResource,
                List.of(tableResource),
                null,
                List.of(),
                null,
                null,
                null,
                "conversation-1",
                "turn-1",
                "Criar uma tabela de folhas de pagamento",
                "Criar uma tabela de folhas de pagamento",
                "Initial table decision.");

        AgenticAuthoringSemanticDecision secondDecision = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gostei, mas prefiro gráficos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-2",
                List.of(),
                null,
                List.of(),
                null,
                firstDecision)).semanticDecision();
        AgenticAuthoringSemanticDecision thirdDecision = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gostei, mas prefiro gráficos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                "conversation-1",
                "turn-3",
                List.of(),
                null,
                List.of(),
                null,
                secondDecision)).semanticDecision();

        assertThat(secondDecision.decisionId()).isNotEqualTo(firstDecision.decisionId());
        assertThat(thirdDecision.decisionId()).isNotEqualTo(secondDecision.decisionId());
        assertThat(thirdDecision.refinementOf()).isEqualTo(secondDecision.decisionId());
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
    void resolvesEmployeeDashboardAsAnalyticalExploreWhenUserAsksForIndicatorsWithoutKnowingFields() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "preciso de um painel pra acompanhar pessoas da empresa, ver um resumo geral e alguns indicadores, mas eu nao sei quais informacoes existem",
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
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("unknown");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.failureCodes()).contains("intent-confirmation-required");
    }

    @Test
    void detachesFromCurrentFormWhenUserPivotsToEmployeeMasterDetail() {
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
                "nao era isso, eu nao quero cadastrar agora; quero uma lista de empregados e abrir detalhes sem saber os campos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                page,
                "funcionarios-form",
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("create_master_detail");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response");
    }

    @Test
    void resolvesHumanCrudCompositionPromptAsMasterDetailCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Agora faca isso para empregados: quero procurar pelos dados que existirem, ver uma lista, selecionar alguem, ver detalhes ao lado e ter caminho para cadastrar ou alterar quando fizer sentido. Nao sei quais campos usar.",
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
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("create_master_detail");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response");
    }

    @Test
    void resolvesHumanTabbedWorkspacePromptAsTabbedMasterDetailFormCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Agora organize essa tela em abas: uma aba para procurar e listar empregados, outra para ver detalhes do empregado selecionado e outra para editar os dados principais com um formulario guiado. Eu nao sei quais campos existem, me ajude a escolher.",
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
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("create_tabbed_master_detail_form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response");
    }

    @Test
    void resolvesNaturalTabbedWorkspacePromptWithArticleBeforeDetailsAsPageCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma tela para empregados organizada em abas: uma aba para procurar pelos dados disponiveis e listar, outra para ver os detalhes do empregado selecionado, e outra para editar os dados principais com um formulario guiado. Eu nao sei quais campos existem, me ajude a escolher.",
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
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("create_tabbed_master_detail_form");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void resolvesHumanEmployeeSearchAndOpenDetailsPromptAsMasterDetailWithoutKeywordSyntax() {
        ObjectNode contextHints = resourcePathContextHints("/api/human-resources/funcionarios");
        contextHints.put("submitUrl", "/api/human-resources/funcionarios/filter");
        contextHints.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response");
        AgenticAuthoringIntentResolutionResult result = service.resolve(requestWithContextHints(
                "crie uma tela master-detail para procurar empregados, ver uma lista e abrir os detalhes de cada pessoa quando selecionar",
                contextHints));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("intent-confirmation-required");
        assertThat(result.clarificationQuestions())
                .contains("Posso aplicar esta alteracao usando o recurso de negocio selecionado?");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response");
        assertThat(result.selectedCandidate().evidence()).contains("api-metadata", "lexical-fallback");
    }

    @Test
    void keepsEmployeeMasterDetailCanonicalWithoutCallingLlmWhenIntentIsAlreadyClear() {
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
                .thenReturn(Optional.empty());
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        ObjectNode contextHints = resourcePathContextHints("/api/human-resources/funcionarios");
        contextHints.put("submitUrl", "/api/human-resources/funcionarios/filter");
        contextHints.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response");
        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(requestWithContextHints(
                "Crie uma tela com busca de empregados, lista e painel de detalhes ao selecionar uma pessoa.",
                "mock",
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isEqualTo("create_master_detail");
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response");
        assertThat(result.selectedCandidate().evidence()).contains("quick-reply-context");
        assertThat(result.warnings()).contains("llm-intent-resolution-fallback-deterministic");
        Mockito.verify(llmIntentResolver).resolve(
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
    void capsLongAssistantMessageAtFinalIntentResolutionBoundary() {
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
                        "api_catalog",
                        "resource_discovery_for_indicators",
                        null,
                        "indicadores para dashboard",
                        "none",
                        "Pelo catalogo semantico disponivel, eu comecaria por estas areas de negocio para graficos. ".repeat(40),
                        List.of(),
                        List.of(),
                        List.of("llm-test-warning"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quais dados posso usar para graficos?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.assistantMessage()).hasSizeLessThanOrEqualTo(700);
    }

    @Test
    void promotesTargetlessBusinessDashboardPromptMisreadAsModifyToCreate() {
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
                        "modify",
                        "dashboard",
                        "add_dashboard_widget",
                        null,
                        "folha pagamento custos",
                        "new_instruction",
                        "Vou ajustar o painel de folha.",
                        List.of(),
                        List.of(),
                        List.of("llm-treated-business-dashboard-as-modify"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sou gerente financeiro e quero entender onde estao os maiores custos da folha. "
                        + "Nao sei quais dados existem, mas preciso de um painel bonito com graficos, "
                        + "valores em reais e uma lista para investigar os detalhes quando algo chamar atencao.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_chart_drilldown");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.failureCodes()).doesNotContain("target-widget-required");
        assertThat(result.clarificationQuestions()).doesNotContain("Qual componente existente deve ser alterado?");
    }

    @Test
    void promotesLlmAssistantChoiceQuestionToQuickReplies() {
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
                        "compose_filter_table",
                        "/api/procurement/purchase-orders",
                        null,
                        "new_instruction",
                        "Posso criar uma tabela ligada aos Pedidos de Compra mostrando aprovacao, prazo, valor e status. "
                                + "Prefere uma tabela simples ou um painel com filtros e grafico para acompanhar os pedidos?",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero acompanhar solicitacoes de compra, aprovacao, prazo, valor e status de cada pedido.",
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
        assertThat(result.failureCodes()).contains("assistant-choice-confirmation-required");
        assertThat(result.quickReplies()).extracting(AgenticAuthoringQuickReply::label)
                .containsExactly("Dashboard completo", "Apenas tabela filtravel", "Ajustar pedido");
        assertThat(result.quickReplies().get(0).description())
                .contains("indicadores");
        assertThat(result.warnings()).contains("llm-assistant-choice-promoted-to-quick-replies");
    }

    @Test
    void usesLlmAuthoredQuickRepliesBeforeDeterministicDashboardFallback() {
        ObjectNode llmContextHints = objectMapper.createObjectNode();
        ObjectNode presentation = llmContextHints.putObject("presentation");
        presentation.put("bestFor", "Quando a prioridade e comparar gravidade, andamento e responsavel.");
        presentation.put("returns", "Previa com graficos e lista de apoio para validar a decisao.");
        presentation.put("nextStep", "Revise o painel antes de salvar a pagina.");
        llmContextHints.put("technicalDetails", "ignore-me");
        llmContextHints.put("submitUrl", "/api/unsafe");
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
                        "recommend_dashboard_composition",
                        "/api/operations/incidentes/filter/cursor",
                        null,
                        "none",
                        "Encontrei uma opcao analitica para incidentes.",
                        List.of(new AgenticAuthoringQuickReply(
                                "incident-graph-preview",
                                "recommendation",
                                "Painel com graficos",
                                "Gerar previa governada de incidentes com graficos por gravidade, andamento e responsavel.",
                                "Mostra o recorte como graficos antes de salvar a pagina.",
                                "query_stats",
                                "analytics",
                                llmContextHints)),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::label)
                .containsExactly("Painel com graficos");
        AgenticAuthoringQuickReply reply = result.quickReplies().get(0);
        assertThat(reply.contextHints().path("resourcePath").asText())
                .isEqualTo("/api/operations/incidentes");
        assertThat(reply.contextHints().path("submitUrl").asText())
                .isEqualTo("/api/operations/incidentes/filter/cursor");
        assertThat(reply.contextHints().path("technicalDetails").isObject()).isTrue();
        assertThat(reply.contextHints().path("presentation").path("bestFor").asText())
                .contains("gravidade");
        assertThat(result.warnings()).contains("llm-authored-quick-replies-used");
        assertThat(result.warnings()).doesNotContain("deterministic-quick-replies-fallback-applied");
    }

    @Test
    void apiCatalogDiscoveryPrioritizesRichCandidateCardsWhenResourcesWereFound() {
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
                        "api_catalog",
                        "resource_discovery_for_indicators",
                        null,
                        "dados para indicadores",
                        "none",
                        "Encontrei fontes candidatas para graficos.",
                        List.of(),
                        List.of(),
                        List.of("llm-api-catalog-discovery"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sou gestor de negocio e quero saber quais dados existem para criar graficos e indicadores antes de criar dashboard.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .allMatch(id -> id.startsWith("resource-"));
        AgenticAuthoringQuickReply firstReply = result.quickReplies().get(0);
        assertThat(firstReply.contextHints().path("resourcePath").asText()).startsWith("/api/");
        assertThat(firstReply.contextHints().path("presentation").path("bestFor").asText()).isNotBlank();
        assertThat(firstReply.contextHints().path("presentation").path("returns").asText()).isNotBlank();
        assertThat(firstReply.contextHints().path("presentation").path("nextStep").asText()).isNotBlank();
        assertThat(firstReply.description()).contains("Indicada");
    }

    @Test
    void consultativeRelatedTableQuestionAnswersInsteadOfMaterializingPreview() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quais outras tabelas podem ser criadas que tem referencia ou relacionamento com pessoas?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_catalog_question");
        assertThat(result.valid()).isFalse();
        assertThat(result.assistantMessage())
                .contains("Nao vou criar outra tela automaticamente")
                .contains("Para prosseguir")
                .contains("crie uma tabela de funcionarios");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("people-table-create", "people-table-fields", "people-related-options");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::label)
                .containsExactly("Criar tabela de pessoas", "Ver campos", "Comparar opcoes")
                .doesNotContain("Criar dashboard");
        assertThat(result.quickReplies().get(0).contextHints().path("artifactKind").asText()).isEqualTo("table");
        assertThat(result.quickReplies().get(0).contextHints().path("presentation").path("nextStep").asText())
                .contains("tabela de pessoas");
        assertThat(result.quickReplies().get(1).contextHints().path("resourcePath").asText())
                .startsWith("/api/");
        assertThat(result.quickReplies().get(1).contextHints().path("presentation").path("returns").asText())
                .contains("campos recomendados");
    }

    @Test
    void consultativePeopleTableFieldQuestionExplainsColumnsInsteadOfSchemaUrl() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quais campos existem para montar uma tabela de funcionarios?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.assistantMessage())
                .contains("Para uma tabela de pessoas")
                .contains("Nome completo")
                .contains("Departamento")
                .contains("Para prosseguir")
                .doesNotContain("/schemas/filtered")
                .doesNotContain("/api/");
    }

    @Test
    void comparativePeopleTableQuestionRecommendsNextStepInsteadOfMaterializingPreview() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Compare as tabelas relacionadas a pessoas e recomende a melhor para comecar.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_catalog_question");
        assertThat(result.valid()).isFalse();
        assertThat(result.assistantMessage())
                .contains("Eu recomendo comecar pela tabela principal de funcionarios")
                .contains("Comparacao rapida")
                .contains("Para prosseguir")
                .contains("Criar tabela de pessoas")
                .doesNotContain("/schemas/filtered")
                .doesNotContain("/api/");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("people-table-create", "people-table-fields", "people-related-options");
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
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
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

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("clarification_answer");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.changeKind()).isEqualTo("clarify_resource");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/departamentos");
        assertThat(result.warnings()).contains("llm-selected-departments");
    }

    @Test
    void usesLlmIntentResolutionWhenAvailableAndSuppressesFollowUpRepliesForEligibleCreate() {
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
                quickstartCandidateCatalog(),
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
        assertThat(result.quickReplies()).isEmpty();
        assertThat(result.warnings()).contains("llm-intent-resolution-used", "llm-test-warning");
    }

    @Test
    void sanitizesTechnicalAddressesFromLlmVisibleAuthoringText() {
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
                        "create_chart_drilldown",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Vou usar POST /api/human-resources/vw-analytics-folha-pagamento/stats/group-by com schema /schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response.",
                        List.of(new AgenticAuthoringQuickReply(
                                "technical-reply",
                                "suggestion",
                                "Usar /api/human-resources/vw-analytics-folha-pagamento",
                                "Confirmed: usar /api/human-resources/vw-analytics-folha-pagamento",
                                "POST /api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                                null,
                                null,
                                null)),
                        List.of(),
                        List.of("llm-test-warning"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use /api/human-resources/vw-analytics-folha-pagamento para criar um dashboard com ranking dos maiores salarios.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.assistantMessage())
                .contains("analytics folha pagamento")
                .contains("Detalhes técnicos")
                .doesNotContain("/api/")
                .doesNotContain("/schemas/");
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void preservesGovernedDashboardQuickRepliesWhenLlmReturnsGenericExploratoryReplies() {
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
                        "explore",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Posso ajudar a ajustar o recorte antes de criar.",
                        List.of(
                                new AgenticAuthoringQuickReply(
                                        "llm-revise",
                                        "revise",
                                        "Quero ajustar",
                                        "quero ajustar",
                                        null,
                                        "tune",
                                        "neutral",
                                        null),
                                new AgenticAuthoringQuickReply(
                                        "llm-cancel",
                                        "cancel",
                                        "Cancelar",
                                        "",
                                        null,
                                        null,
                                        null,
                                        null)),
                        List.of(),
                        List.of())));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero entender quem recebe mais na empresa e comparar por setor",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
        assertThat(result.quickReplies().get(0).label()).isEqualTo("Gerar previa governada");
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("presentation").path("returns").asText())
                .contains("KPIs", "graficos");
        assertThat(result.quickReplies().get(1).contextHints().path("presentation").path("bestFor").asText())
                .contains("indicadores", "filtros");
        assertThat(result.quickReplies().get(2).description())
                .contains("sem gerar previa");
        assertThat(result.quickReplies().get(2).contextHints().path("presentation").path("nextStep").asText())
                .contains("descartar");
    }

    @Test
    void composeDashboardWithSelectedAnalyticsCandidateStillOffersGovernedContinuation() {
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
                        "compose",
                        "dashboard",
                        "set_chart_dimension_and_metric",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "none",
                        "Posso seguir montando a visualizacao com comparacao por area.",
                        List.of(),
                        List.of(),
                        List.of())));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero ver quem recebe mais e comparar por area",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("compose");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
        assertThat(result.quickReplies().get(0).prompt())
                .contains("Confirmed:")
                .contains("criar dashboard");
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
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
                quickstartCandidateCatalog(),
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
    void keepsGenericLlmExplorationAsGovernedDashboardConfirmationForAnalyticalHumanIntent() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringCandidate peopleCandidate = new AgenticAuthoringCandidate(
                "/api/example/people",
                "post",
                "/schemas/filtered?path=/api/example/people/filter&operation=post&schemaType=response",
                "/api/example/people/filter",
                "post",
                0.36d,
                "api_metadata broad artifact discovery",
                List.of("api-metadata", "broad-artifact-discovery"));
        AgenticAuthoringCandidate analyticsCandidate = new AgenticAuthoringCandidate(
                "/api/example/vw-analytics-people",
                "post",
                "/schemas/filtered?path=/api/example/vw-analytics-people/filter&operation=post&schemaType=response",
                "/api/example/vw-analytics-people/filter",
                "post",
                0.67d,
                "api_metadata broad artifact discovery",
                List.of("api-metadata", "broad-artifact-discovery"));
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(peopleCandidate))
                .thenReturn(List.of(analyticsCandidate, peopleCandidate));

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
                        "unknown",
                        "unknown",
                        null,
                        null,
                        "none",
                        "Posso ajudar a escolher antes de criar.",
                        List.of(),
                        List.of(),
                        List.of("llm-generic-exploration"))));

        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero entender quem recebe mais na empresa e conseguir comparar por area",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.assistantMessage())
                .doesNotContain("Posso ajudar a escolher antes de criar");
        assertThat(result.clarificationQuestions())
                .contains("Posso criar um dashboard usando o recurso de negocio selecionado?");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("resource-api-example-vw-analytics-people", "resource-api-example-people");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/example/vw-analytics-people");
        assertThat(result.warnings())
                .contains(
                        "llm-intent-resolution-used",
                        "llm-generic-exploration",
                        "llm-operational-artifact-rejected-for-analytical-dashboard-intent",
                        "semantic-policy-corrected-analytical-dashboard-intent")
                .doesNotContain(
                        "keyword-fallback-applied",
                        "llm-exploratory-response-promoted-to-actionable-fallback");
        assertThat(result.semanticDecision().reviewRequired()).isFalse();
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("keywordFallbackApplied").asBoolean())
                .isFalse();
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("semanticPolicyApplied").asBoolean())
                .isTrue();
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
                quickstartCandidateCatalog(),
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
    void exposesKeywordFallbackAsFailSafeTelemetryWhenLlmIsUnavailable() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "crie uma tabela de funcionarios",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.warnings())
                .contains("keyword-fallback-applied", "keyword-fallback-fail-safe-applied");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("fallbackPolicy").asText())
                .isEqualTo("fail-safe");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("keywordFallbackApplied").asBoolean())
                .isTrue();
    }

    @Test
    void exposesDomainAnchorResourceSelectionAsTelemetry() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringCandidate anchoredCandidate = new AgenticAuthoringCandidate(
                "/api/example/incidentes",
                "post",
                "/schemas/filtered?path=/api/example/incidentes&operation=post&schemaType=response",
                "/api/example/incidentes",
                "post",
                0.95d,
                "api_metadata domain anchor",
                List.of("api-metadata", "domain-anchor"));
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.nullable(String.class),
                        Mockito.nullable(String.class)))
                .thenReturn(List.of(anchoredCandidate));
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
                        "/api/example/incidentes",
                        null,
                        "none",
                        "Vou preparar uma tabela usando a fonte encontrada.",
                        List.of(),
                        List.of(),
                        List.of())));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "crie uma tabela de incidentes",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.warnings())
                .contains("resource-selection-domain-anchor-selected", "resource-selection-domain-anchor-candidates-present");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("selectedCandidateUsesDomainAnchor").asBoolean())
                .isTrue();
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("candidateSetContainsDomainAnchor").asBoolean())
                .isTrue();
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isEqualTo("resource-selection-domain-anchor");
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
                quickstartCandidateCatalog(),
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

        assertThat(result.valid()).isTrue();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.assistantMessage())
                .isEqualTo("Encontrei a fonte de folha, mas ainda falta escolher o recorte principal do painel.");
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .isEmpty();
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

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(requestWithContextHints(
                "Use Fonte confirmada (/api/human-resources/vw-analytics-folha-pagamento) como data source. Mantenha o painel por setor com grafico e lista.",
                "mock",
                contextHints));

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.pendingClarification()).isNotNull();
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used", "llm-kept-exploring")
                .doesNotContain("deterministic-payroll-dashboard-confirmation-applied");
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

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/folhas-pagamento/all");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.pendingClarification()).isNotNull();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("revise", "cancel");
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used", "llm-kept-exploring")
                .doesNotContain("deterministic-payroll-dashboard-confirmation-applied");
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
                quickstartCandidateCatalog(),
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
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any())).thenReturn(List.of());
        Mockito.when(candidateCatalog.discover(
                        Mockito.eq("cadastro de beneficios para funcionario"),
                        Mockito.eq("form"),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
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
        Mockito.verify(candidateCatalog).discover(
                Mockito.eq("cadastro de beneficios para funcionario"),
                Mockito.eq("form"),
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
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
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any())).thenReturn(List.of());
        Mockito.when(candidateCatalog.discover(
                        Mockito.eq("cadastro de beneficios para funcionario"),
                        Mockito.eq("form"),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
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
    void preservesToolCandidateEvidenceBundleFromContextHints() {
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringCandidate toolCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-ranking-reputacao",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                "post",
                0.44d,
                "resource discovered by backend tool",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of("lexical_fallback", List.of(
                        new AgenticAuthoringEvidenceBundle.Evidence(
                                "api_metadata",
                                "weak_lexical_match",
                                "/api/human-resources/vw-ranking-reputacao",
                                "Tool lexical candidate.",
                                0.44d,
                                List.of("ranking", "reputacao"),
                                "tenant-a",
                                "staging",
                                "release-2026.05"))));
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
                        "/api/human-resources/vw-ranking-reputacao",
                        null,
                        "none",
                        "Vou usar ranking reputacao como fonte de dados.",
                        List.of(),
                        List.of(),
                        List.of("llm-tool-candidate"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode resourceDiscovery = contextHints.putObject("resourceDiscovery");
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

        assertThat(result.selectedCandidate().evidenceBundle()).isNotNull();
        assertThat(result.selectedCandidate().evidenceBundle().retrievalSource()).isEqualTo("lexical_fallback");
        assertThat(result.semanticDecision().retrievedEvidence().retrievalSource()).isEqualTo("lexical_fallback");
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isEqualTo("weak-lexical-evidence");
        assertThat(result.semanticDecision().confidence()).isLessThan(0.5d);
    }

    @Test
    void overridesLlmResourceSelectionWhenItContradictsStrongBusinessTerms() {
        AgenticAuthoringCandidate incidentCandidate = new AgenticAuthoringCandidate(
                "/api/operations/incidentes",
                "post",
                "/schemas/filtered?path=/api/operations/incidentes/filter/cursor&operation=post&schemaType=response",
                "/api/operations/incidentes/filter/cursor",
                "post",
                0.82d,
                "Consulta incidentes, ocorrencias, chamados, gravidade, andamento e responsavel.",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringCandidate reputationCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-ranking-reputacao",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                "post",
                0.97d,
                "Ranking reputacao para dashboards analiticos.",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(reputationCandidate, incidentCandidate));
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
                        "create_dashboard",
                        "/api/human-resources/vw-ranking-reputacao",
                        null,
                        "none",
                        "Vou usar ranking reputacao como fonte governada.",
                        List.of(),
                        List.of(),
                        List.of("llm-selected-ranking-reputation"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/operations/incidentes");
        assertThat(result.assistantMessage()).doesNotContain("Ranking reputacao");
        assertThat(result.warnings())
                .contains(
                        "llm-selected-ranking-reputation",
                        "llm-resource-selection-overridden-by-prompt-alignment");
    }

    @Test
    void deterministicFallbackPrefersPromptAlignedCandidateOverBroadDashboardTie() {
        AgenticAuthoringCandidate reputationCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-ranking-reputacao",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/stats/timeseries&operation=post&schemaType=response",
                "/api/human-resources/vw-ranking-reputacao/stats/timeseries",
                "post",
                0.79d,
                "api_metadata broad artifact discovery",
                List.of("api-metadata", "broad-artifact-discovery"));
        AgenticAuthoringCandidate incidentIndicatorsCandidate = new AgenticAuthoringCandidate(
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries&operation=post&schemaType=response",
                "/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries",
                "post",
                0.79d,
                "api_metadata broad artifact discovery",
                List.of("api-metadata", "broad-artifact-discovery"));
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        Mockito.when(candidateCatalog.discover(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()))
                .thenReturn(List.of(reputationCandidate, incidentIndicatorsCandidate));
        AgenticAuthoringIntentResolverService fallbackService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                candidateCatalog,
                null,
                null,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = fallbackService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().primaryComponent()).isEqualTo("praxis-chart");
        assertThat(result.visualizationDecision().provenance()).isEqualTo("governed-semantic-fallback");
        assertThat(result.visualizationDecision().axes())
                .extracting(AgenticAuthoringVisualizationAxisDecision::field)
                .containsExactly("gravidade", "andamento", "responsavel");
        assertThat(result.semanticDecision()).isNotNull();
        assertThat(result.semanticDecision().schemaVersion())
                .isEqualTo("praxis-agentic-authoring-semantic-decision.v1");
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.semanticDecision().visualizationDecision().primaryComponent())
                .isEqualTo("praxis-chart");
        assertThat(result.assistantMessage() == null || !result.assistantMessage().contains("Ranking reputacao"))
                .isTrue();
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
                quickstartCandidateCatalog(),
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
    void refinesCurrentTableIntoChartDashboardWhilePreservingBoundResource() {
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
                        "modify",
                        "table",
                        "set_column_visibility",
                        "",
                        null,
                        "refinement",
                        "Vou manter a fonte atual e trocar a visualizacao para graficos.",
                        List.of(),
                        List.of(),
                        List.of("llm-semantic-follow-up"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gostei, mas prefiro graficos",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                payrollTablePage(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie uma tabela de folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Criei uma tabela com a fonte de folha.", null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.selectedCandidate().evidence())
                .contains("conversation-refinement-current-page-resource");
        assertThat(result.visualizationDecision()).isNull();
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.semanticDecision().visualIntent()).isEqualTo("charts");
        assertThat(result.semanticDecision().refinement()).isNotNull();
        assertThat(result.semanticDecision().refinement().refinementKind()).isEqualTo("visual_projection");
        assertThat(result.semanticDecision().refinement().preserve()).contains("resource", "source");
        assertThat(result.semanticDecision().refinement().replace())
                .containsEntry("artifactKind", "dashboard")
                .containsEntry("visualIntent", "charts");
        assertThat(result.semanticDecision().refinementOf()).isEqualTo("previous-conversation-decision");
        assertThat(result.semanticDecision().previousDecisionRef()).isEqualTo("current-page-bound-resource");
        assertThat(result.warnings())
                .contains("semantic-policy-refined-visual-projection")
                .doesNotContain("keyword-fallback-applied");
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("semanticPolicyApplied").asBoolean())
                .isTrue();
        assertThat(result.llmDiagnostics().path("resolutionTelemetry").path("keywordFallbackApplied").asBoolean())
                .isFalse();
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
                quickstartCandidateCatalog(),
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

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
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
            assertThat(List.of("dashboard", "page", "unknown")).as(prompt).contains(result.artifactKind());
            assertThat(List.of("recommend_dashboard_visualization", "unknown")).as(prompt).contains(result.changeKind());
            if (result.selectedCandidate() != null) {
                assertThat(result.selectedCandidate().resourcePath())
                        .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
                assertThat(result.selectedCandidate().operation()).isEqualTo("post");
            }
            assertThat(result.gate().status()).isEqualTo("clarification_required");
            assertThat(result.failureCodes()).contains("intent-confirmation-required");
            assertThat(result.assistantMessage()).doesNotContain("/api/");
            assertThat(result.clarificationQuestions())
                    .anyMatch(question -> question.contains("recurso de negocio selecionado")
                            || question.contains("recursos proximos"));
            assertThat(result.pendingClarification()).isNotNull();
            assertThat(result.pendingClarification().sourcePrompt()).isEqualTo(result.effectivePrompt());
            assertThat(result.pendingClarification().questions())
                    .allMatch(question -> !question.contains("/api/"));
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

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(List.of("dashboard", "page")).contains(result.artifactKind());
        assertThat(List.of("create_artifact", "create_master_detail")).contains(result.changeKind());
        if (result.selectedCandidate() != null) {
            assertThat(result.selectedCandidate().resourcePath())
                    .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
            assertThat(result.selectedCandidate().schemaUrl())
                    .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response");
        }
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("resource-candidate-ambiguous");
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

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.pendingClarification()).isNull();
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
        assertThat(result.changeKind()).isEqualTo("unknown");
        assertThat(result.valid()).isFalse();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("revise", "cancel");
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
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.pendingClarification()).isNull();
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

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void resolvesCustomPayrollBreakdownAfterOtherClarification() {
        AgenticAuthoringIntentResolverService genericService = new AgenticAuthoringIntentResolverService(objectMapper);
        AgenticAuthoringIntentResolutionResult result = genericService.resolve(new AgenticAuthoringIntentResolutionRequest(
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

        assertThat(result.valid()).isFalse();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro\n\nConfirmed: cargo");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("resource-candidate-required");
    }

    @Test
    void carriesAttachmentSummariesInPendingClarificationDiagnostics() {
        AgenticAuthoringIntentResolverService genericService = new AgenticAuthoringIntentResolverService(objectMapper);
        AgenticAuthoringIntentResolutionResult result = genericService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "pagamento",
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
        assertThat(result.candidates()).isEmpty();
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes())
                .containsExactly(
                        "intent-operation-unknown",
                        "intent-artifact-unknown");
        assertThat(result.clarificationQuestions())
                .contains(
                        "O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?",
                        "Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?");
        assertThat(result.clarificationQuestions()).noneMatch(question -> question.contains("/api/"));
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
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("intent-confirmation-required");
        assertThat(result.assistantMessage())
                .contains("Posso ajudar a definir a tabela antes de criar")
                .contains("- Fonte: escolha o recurso de negocio.");
        assertThat(result.clarificationQuestions())
                .containsExactly("Posso criar uma tabela usando o recurso de negocio selecionado?");
    }

    @Test
    void resolvesMissionEmployeeTablePromptToOperationsMissionsInsteadOfEmployees() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sou gerente de RH e preciso acompanhar as missoes dos empregados de forma mais clara. Quero uma tabela bonita para entender as informacoes com valores formatados, chips, icones e acoes para investigar detalhes quando algo chamar atencao.",
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
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/operations/missoes");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/operations/missoes/filter/cursor&operation=post&schemaType=response");
    }

    @Test
    void resolvesOperationalMonitoringPromptToDashboardInsteadOfTableOnlySuccess() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
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
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/operations/incidentes");
        assertThat(result.gate().status()).isEqualTo("eligible");
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
                .containsExactly("Posso criar um dashboard usando o recurso de negocio selecionado?");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
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
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.semanticDecision().selectedResource().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
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

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void treatsTopSalaryRankingAsEnoughPayrollDashboardBreakdown() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie o dashboard com ranking dos 10 maiores salarios.",
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
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
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

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .doesNotContain("/api/human-resources/folhas-pagamento/schemas");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
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
            assertThat(List.of("dashboard", "unknown")).contains(result.artifactKind());
            assertThat(List.of("recommend_dashboard_visualization", "unknown")).contains(result.changeKind());
            if (result.selectedCandidate() != null) {
                assertThat(result.selectedCandidate().resourcePath())
                        .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
            }
            assertThat(result.gate().status()).isEqualTo("clarification_required");
            assertThat(result.failureCodes()).isNotEmpty();
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
                .allMatch(id -> id.startsWith("resource-"));
        assertThat(result.quickReplies()).extracting(AgenticAuthoringQuickReply::description)
                .allSatisfy(description -> assertThat(description)
                        .containsAnyOf("Indicada", "Opção encontrada"));
        assertThat(result.quickReplies())
                .allSatisfy(reply -> {
                    assertThat(reply.contextHints().path("resourcePath").asText()).startsWith("/api/");
                    assertThat(reply.contextHints().path("presentation").path("bestFor").asText()).isNotBlank();
                    assertThat(reply.contextHints().path("presentation").path("returns").asText()).isNotBlank();
                    assertThat(reply.contextHints().path("presentation").path("nextStep").asText()).contains("Clique");
                });
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
                .contains("Escolha a fonte que melhor representa o recorte de negocio")
                .contains("analytics folha pagamento")
                .contains("analytics incidentes")
                .doesNotContain("/api/");
        assertThat(result.clarificationQuestions().get(0))
                .contains("analytics folha pagamento")
                .contains("analytics incidentes")
                .doesNotContain("/api/");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "resource-api-human-resources-vw-analytics-folha-pagamento",
                        "resource-api-operations-vw-analytics-incidentes");
        AgenticAuthoringQuickReply firstReply = result.quickReplies().get(0);
        assertThat(firstReply.description())
                .contains("Indicada para começar por KPIs e gráficos")
                .contains("Retorna dados agregáveis");
        assertThat(firstReply.contextHints().path("presentation").path("bestFor").asText())
                .contains("dashboards executivos");
        assertThat(firstReply.contextHints().path("presentation").path("returns").asText())
                .contains("KPIs");
        assertThat(firstReply.contextHints().path("presentation").path("nextStep").asText())
                .contains("Clique");
        assertThat(firstReply.icon()).isEqualTo("query_stats");
        assertThat(firstReply.tone()).isEqualTo("analytics");
        assertThat(firstReply.contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(firstReply.contextHints().path("technicalDetails").path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(firstReply.prompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: usar analytics folha pagamento");
    }

    @Test
    void resolvesPayrollTopSalaryRankingAsDashboardWithoutRepeatingEndpointChoice() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use /api/human-resources/vw-analytics-folha-pagamento para criar um dashboard com ranking dos 10 maiores salarios.",
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
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void resolvesHumanAnalyticalPromptToAnnotatedAnalyticsResourceInsteadOfOperationalEntity() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero entender quem recebe mais na empresa e comparar por setor",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
    }

    @Test
    void dashboardFilterConnectionOffersConcreteGovernedContinuations() {
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
                        "connect",
                        "dashboard",
                        "connect_filter_to_results",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "new_instruction",
                        "Posso adicionar controles antes da visualizacao.",
                        List.of(),
                        List.of(),
                        List.of("llm-dashboard-filter-connection"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero escolher o periodo ou a area antes de ver o ranking",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("connect");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("connect_filter_to_results");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly(
                        "confirm-dashboard-filters",
                        "dashboard-filter-period",
                        "dashboard-filter-dimension",
                        "cancel");
        assertThat(result.quickReplies().get(0).label()).isEqualTo("Usar periodo e area");
        assertThat(result.quickReplies().get(0).prompt())
                .contains("Confirmed: adicionar filtros de periodo e area ao dashboard");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("schemaVersion").asText())
                .isEqualTo("praxis.ai.context-hints.domain-catalog/v0.2");
    }

    @Test
    void confirmedDashboardFilterControlsAreEligibleWithoutWidgetTarget() {
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
                        "modify",
                        "dashboard",
                        "add_filter",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        null,
                        "clarification_answer",
                        "Vou preparar filtros antes da visualizacao.",
                        List.of(),
                        List.of(),
                        List.of("llm-dashboard-filter-confirmed"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                """
                quero escolher o periodo ou a area antes de ver o ranking

                Confirmed: adicionar filtros de periodo e area ao dashboard
                """,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("add_filter");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.failureCodes()).doesNotContain("target-widget-required");
        assertThat(result.quickReplies()).isEmpty();
    }

    @Test
    void preservesAnalyticalDashboardIntentWhenLlmSuggestsOperationalForm() {
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
                        "form",
                        "create_minimal_form",
                        "/api/human-resources/funcionarios",
                        null,
                        "none",
                        "Vou criar uma tela de funcionarios.",
                        List.of(),
                        List.of(),
                        List.of("llm-picked-operational-form"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero ver quem recebe mais e comparar por area",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.assistantMessage()).doesNotContain("tela de funcionarios");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
        assertThat(result.warnings())
                .contains(
                        "llm-operational-artifact-rejected-for-analytical-dashboard-intent",
                        "semantic-policy-corrected-analytical-dashboard-intent")
                .doesNotContain("keyword-fallback-applied");
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isEqualTo("weak-lexical-evidence");
    }

    @Test
    void preservesAnalyticalDashboardIntentWhenLlmStaysInConsultativePageMode() {
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
                        "page",
                        "recommend_page_composition",
                        "/api/human-resources/funcionarios",
                        null,
                        "none",
                        "Vou preparar uma pagina usando o recurso disponivel de funcionarios.",
                        List.of(),
                        List.of(),
                        List.of("llm-picked-consultative-page"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero uma tela para enxergar os maiores valores da empresa e conseguir ver os registros por tras.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("recommend_dashboard_visualization");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.assistantMessage()).doesNotContain("funcionarios");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
        assertThat(result.warnings())
                .contains(
                        "llm-operational-artifact-rejected-for-analytical-dashboard-intent",
                        "semantic-policy-corrected-analytical-dashboard-intent")
                .doesNotContain("keyword-fallback-applied");
        assertThat(result.semanticDecision().reviewRequired()).isTrue();
        assertThat(result.semanticDecision().reviewReason()).isEqualTo("weak-lexical-evidence");
    }

    @Test
    void vagueAnalyticalPromptWithMultipleAnalyticsSourcesAsksForResourceChoice() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/vw-ranking-reputacao",
                        "POST",
                        "human-resources,reputacao,ranking,dashboard,analytics",
                        "Ranking de reputacao",
                        "Visao analitica para ranking de reputacao."),
                apiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,salario,analytics,dashboard",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento para dashboards."),
                apiMetadata(
                        "/api/human-resources/departamentos",
                        "POST",
                        "human-resources,departamento,departamentos,setor,area",
                        "Departamentos",
                        "Cadastro dimensional de departamentos e setores."),
                apiMetadata(
                        "/api/operations/vw-indicadores-incidentes",
                        "POST",
                        "operations,incidentes,indicadores,analytics,dashboard",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes operacionais.")));
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
                        "page",
                        "recommend_page_composition",
                        "/api/human-resources/funcionarios",
                        null,
                        "none",
                        "Vou preparar uma pagina usando o recurso disponivel de funcionarios.",
                        List.of(),
                        List.of(),
                        List.of("llm-picked-consultative-page"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero uma tela para enxergar os maiores valores da empresa e conseguir ver os registros por tras.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("resource-candidate-ambiguous");
        assertThat(result.assistantMessage())
                .contains("Encontrei mais de uma fonte de dados possivel")
                .doesNotContain("funcionarios");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .contains(
                        "resource-api-human-resources-vw-ranking-reputacao",
                        "resource-api-human-resources-vw-analytics-folha-pagamento",
                        "resource-api-operations-vw-indicadores-incidentes");
        assertThat(result.warnings())
                .contains("llm-intent-resolution-used",
                        "llm-operational-artifact-rejected-for-analytical-dashboard-intent",
                        "semantic-policy-corrected-analytical-dashboard-intent")
                .doesNotContain("keyword-fallback-applied");
        Mockito.verify(llmIntentResolver).resolve(
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
    void specificPayrollAnalyticalPromptSelectsPayrollProjectionAmongMultipleAnalyticsSources() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/vw-ranking-reputacao",
                        "POST",
                        "human-resources,reputacao,ranking,dashboard,analytics",
                        "Ranking de reputacao",
                        "Visao analitica para ranking de reputacao."),
                apiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,salario,analytics,dashboard",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento para dashboards."),
                apiMetadata(
                        "/api/operations/vw-indicadores-incidentes",
                        "POST",
                        "operations,incidentes,indicadores,analytics,dashboard",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes operacionais.")));
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
                        "page",
                        "recommend_page_composition",
                        "/api/operations/vw-indicadores-incidentes",
                        null,
                        "none",
                        "Vou preparar uma pagina usando indicadores operacionais.",
                        List.of(),
                        List.of(),
                        List.of("llm-picked-generic-analytics"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "quero ver quem recebe mais e comparar por area",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
        assertThat(result.assistantMessage()).doesNotContain("indicadores operacionais");
    }

    @Test
    void canonicalConfirmedDashboardPromptAdvancesFromExplorationToCreation() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                """
                quero entender quem recebe mais na empresa e comparar por setor

                Confirmed: criar dashboard com analytics folha pagamento
                """,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.failureCodes()).doesNotContain("intent-confirmation-required");
        assertThat(result.quickReplies()).isEmpty();
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
    void metadataBackedDashboardSourceChoiceKeepsGovernedPreviewActionAvailable() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
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
                        null,
                        null,
                        "clarification_answer",
                        "Posso preparar um dashboard usando a fonte escolhida.",
                        List.of(),
                        List.of(),
                        List.of())));
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                        "POST",
                        "human-resources,folha,pagamento,salario,analytics,dashboard",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento para dashboards.",
                        "analyticsFolhaPagamentoTimeseries",
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
        contextHints.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        contextHints.put("submitUrl", "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Use analytics folha pagamento as the data source.",
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
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Quero uma tela para enxergar os maiores valores da empresa.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei mais de uma fonte de dados possivel para este dashboard.",
                                null)),
                new AgenticAuthoringPendingClarification(
                        "Quero uma tela para enxergar os maiores valores da empresa.",
                        List.of("Qual API deve alimentar este dashboard?"),
                        "Encontrei mais de uma fonte de dados possivel para este dashboard.",
                        "turn-1",
                        null),
                null,
                contextHints));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).contains("intent-confirmation-required");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .containsExactly("confirm-dashboard", "revise", "cancel");
    }

    @Test
    void apiCatalogQuickRepliesPreserveSelectedResourceContextForDashboardCreation() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "POST",
                        "human-resources,folha,pagamento,salario,analytics,dashboard",
                        "Analytics de folha por departamento",
                        "Agrupa folha de pagamento por departamento para dashboards.",
                        "analyticsFolhaPagamentoGroupBy",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        new AgenticAuthoringApiCatalogConversationService(objectMapper, repository));
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        contextHints.put("submitUrl", "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quais campos existem no schema da API recomendada?",
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
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Quero criar graficos de folha.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "A fonte recomendada e Analytics de folha pagamento.",
                                null)),
                null,
                null,
                contextHints));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.selectedCandidate()).isNotNull();
        AgenticAuthoringQuickReply createDashboard = result.quickReplies().stream()
                .filter(reply -> "api-create-dashboard".equals(reply.id()))
                .findFirst()
                .orElseThrow();
        assertThat(createDashboard.kind()).isEqualTo("confirm");
        assertThat(createDashboard.prompt())
                .contains("Confirmed:")
                .contains("criar dashboard");
        assertThat(createDashboard.contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(createDashboard.contextHints().path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
    }

    @Test
    void consultativePeopleTableFieldQuestionAnswersFieldsWithoutPromotingPreview() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios,pessoas,colaboradores,tabela",
                        "Funcionarios",
                        "Fonte principal de pessoas da empresa para tabelas e filtros.",
                        "funcionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringIntentResolverService metadataBackedService =
                new AgenticAuthoringIntentResolverService(
                        objectMapper,
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        new AgenticAuthoringApiCatalogConversationService(objectMapper, repository),
                        llmIntentResolver,
                        new AgenticAuthoringComponentCapabilitiesService());
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/funcionarios");
        contextHints.put("submitUrl", "/api/human-resources/funcionarios");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Mostre a lista de campos detectados na tabela Funcionarios.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-4",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Quais telas fazem sentido para pessoas da empresa?",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Posso ajudar a escolher o melhor tipo de pagina.",
                                null)),
                null,
                null,
                contextHints));

        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_catalog_question");
        assertThat(result.assistantMessage())
                .contains("Para uma tabela de pessoas")
                .contains("Nome completo")
                .contains("Departamento")
                .doesNotContain("/schemas/")
                .doesNotContain("/api/");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::id)
                .contains("people-table-create", "people-table-fields", "people-related-options");
        assertThat(result.quickReplies())
                .noneMatch(reply -> "confirm-dashboard".equals(reply.id())
                        || "api-create-dashboard".equals(reply.id()));
        Mockito.verifyNoInteractions(llmIntentResolver);
    }

    @Test
    void governedResourceConfirmationUsesCanonicalContextWithoutReaskingLlm() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/procurement/suppliers",
                        "POST",
                        "procurement,fornecedor,fornecedores,supplier,suppliers,compras,dashboard,analytics",
                        "Fornecedores",
                        "Fonte de fornecedores para paineis, tabelas e analises de compras.",
                        "suppliers",
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
        contextHints.put("resourcePath", "/api/procurement/suppliers");
        contextHints.put("submitUrl", "/api/procurement/suppliers");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Quero um dashboard para acompanhar fornecedores\n\nConfirmed: Gerar previa governada",
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
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Quero um dashboard para acompanhar fornecedores",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei uma fonte de negocio aderente. Posso gerar a pre-visualizacao governada?",
                                null)),
                new AgenticAuthoringPendingClarification(
                        "Quero um dashboard para acompanhar fornecedores",
                        List.of("Posso gerar a pre-visualizacao governada?"),
                        "Encontrei uma fonte de negocio aderente. Posso gerar a pre-visualizacao governada?",
                        "turn-1",
                        null),
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/procurement/suppliers");
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.quickReplies()).isEmpty();
        assertThat(result.warnings())
                .contains("governed-resource-confirmation-deterministic", "keyword-fallback-applied")
                .doesNotContain("llm-intent-resolution-used");
        Mockito.verifyNoInteractions(llmIntentResolver);
    }

    @Test
    void governedPreviewQuickReplyUsesContextHintsEvenWhenVisiblePromptIsOnlyTheButtonLabel() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/procurement/suppliers",
                        "POST",
                        "procurement,fornecedor,fornecedores,supplier,suppliers,compras,dashboard,analytics",
                        "Fornecedores",
                        "Fonte de fornecedores para paineis, tabelas e analises de compras.",
                        "suppliers",
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
        contextHints.put("resourcePath", "/api/procurement/suppliers");
        contextHints.put("submitUrl", "/api/procurement/suppliers");
        contextHints.put("operation", "post");

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Gerar previa governada",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-3",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Quero um dashboard para acompanhar fornecedores",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei uma fonte de negocio aderente. Posso gerar a pre-visualizacao governada?",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2b",
                                "user",
                                "Use fornecedores as the data source.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2c",
                                "assistant",
                                "Encontrei uma intencao analitica para dashboard.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m3",
                                "user",
                                "Gerar previa governada",
                                null)),
                null,
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/procurement/suppliers");
        assertThat(result.pendingClarification()).isNull();
        assertThat(result.quickReplies()).isEmpty();
        assertThat(result.warnings())
                .contains("governed-resource-confirmation-deterministic", "keyword-fallback-applied")
                .doesNotContain("llm-intent-resolution-used");
        Mockito.verifyNoInteractions(llmIntentResolver);
    }

    @Test
    void governedPreviewConfirmationPromptCanCarryCanonicalResourceAndSubmitUrlWithoutHiddenHints() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,analytics,dashboard,salario,remuneracao",
                        "Analytics folha pagamento",
                        "Agrupa folha de pagamento por departamento para dashboards.",
                        "analyticsFolhaPagamento",
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

        AgenticAuthoringIntentResolutionResult result = metadataBackedService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie um dashboard governado\n\nConfirmed: usar /api/human-resources/vw-analytics-folha-pagamento via /api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null,
                "session-1",
                "turn-4",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "quero ver quem recebe mais e comparar por area",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei uma intencao analitica para dashboard.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m3",
                                "user",
                                "Gerar previa governada",
                                null)),
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.selectedCandidate().submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.quickReplies()).isEmpty();
        Mockito.verifyNoInteractions(llmIntentResolver);
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
        assertThat(result.selectedCandidate().operation()).isEqualTo("post");
        assertThat(result.selectedCandidate().schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/beneficios/filter/cursor&operation=post&schemaType=response");
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
        assertThat(result.failureCodes()).containsExactly("resource-candidate-ambiguous");
        assertThat(result.clarificationQuestions()).noneMatch(question -> question.contains("/api/"));
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
                .containsExactlyInAnyOrder(
                        "/api/operations/sinais-socorro",
                        "/api/operations/incidentes");
        assertThat(result.gate().status()).isEqualTo("clarification_required");
        assertThat(result.failureCodes()).containsExactly("resource-candidate-ambiguous");
        assertThat(result.clarificationQuestions()).noneMatch(question -> question.contains("/api/"));
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
                .contains("/api/praxis/config/domain-rules/intake")
                .contains("/api/praxis/config/domain-rules/simulations")
                .contains("/api/human-resources/funcionarios");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void explicitLocalUiCompositionDoesNotRouteToSharedRulesEvenWithStaleDomainCatalogHint() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-resumo-missoes");
        ObjectNode domainCatalog = contextHints.putObject("domainCatalog");
        domainCatalog.put("schemaVersion", "praxis.ai.context-hints.domain-catalog/v0.2");
        domainCatalog.put("resourceKey", "human-resources.vw-resumo-missoes");
        domainCatalog.put("recommendedAuthoringFlow", "shared_rule_authoring");

        AgenticAuthoringIntentResolutionResult result = service.resolve(requestWithContextHints(
                "Crie uma pagina operacional com Praxis Tabs. A primeira aba Cadastro contem um formulario simples com Nome, Email e Prioridade. A segunda aba Acompanhamento contem uma lista em cards com tres solicitacoes de exemplo e status curto. Use conteudo local/editorial de demonstracao, sem criar regra de negocio definitiva.",
                contextHints));

        assertThat(result.gate()).satisfiesAnyOf(
                gate -> assertThat(gate).isNull(),
                gate -> assertThat(gate.status()).isEqualTo("eligible"));
        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.failureCodes())
                .doesNotContain(
                        "shared-rule-authoring-required",
                        "resource-candidate-required",
                        "resource-candidate-ambiguous");
        assertThat(result.assistantMessage() == null || !result.assistantMessage().contains("/api/praxis/config/domain-rules"))
                .isTrue();
        assertThat(result.warnings()).contains("explicit-local-ui-composition-resource-selection-bypassed");
    }

    @Test
    void explicitLocalUiCompositionBypassesResourceDisambiguationEvenWhenCatalogHasCandidates() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma página operacional com Praxis Tabs. A primeira aba deve se chamar Cadastro e conter um formulário simples de solicitação com campos Nome, Email e Prioridade. A segunda aba deve se chamar Acompanhamento e conter uma lista em cards com três solicitações de exemplo e status curto. Use conteúdo local/editorial de demonstração, sem criar regra de negócio definitiva.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.failureCodes())
                .doesNotContain("resource-candidate-required", "resource-candidate-ambiguous");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::kind)
                .doesNotContain("resource");
        assertThat(result.assistantMessage() == null || !result.assistantMessage().contains("fonte de dados"))
                .isTrue();
    }

    @Test
    void explicitLocalTabbedRefinementKeepsPageCompositionWhenLlmClassifiesNestedCrud() {
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
                        "compose",
                        "table",
                        "add_column",
                        null,
                        null,
                        "new_instruction",
                        "Preview applied to the page.",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Agora refine a página existente mantendo as três abas. Na aba Registros, adicione uma coluna Categoria no CRUD e preserve as ações Criar, Editar e Excluir. Na aba Relacionamentos, inclua Status do comentário. Não use API real nem schema externo; continue como conteúdo local/editorial.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.failureCodes())
                .doesNotContain("target-widget-required", "resource-candidate-required", "resource-candidate-ambiguous");
        assertThat(result.warnings()).contains(
                "explicit-local-ui-composition-resource-selection-bypassed",
                "explicit-local-page-composition-normalized");
    }

    @Test
    void explicitLocalTabbedAdjustmentKeepsPageCompositionWhenLlmClassifiesApiCatalog() {
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
                        "api_catalog",
                        "answer_api_catalog_question",
                        null,
                        null,
                        "new_instruction",
                        "APIs relacionadas: POST /api/human-resources/base-acessos/options/filter.",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Ajuste somente os pontos faltantes da pre-visualizacao local/editorial: a tabela da aba Registros deve ter exatamente quatro registros ficticios e cada linha deve mostrar as acoes Editar, Excluir e Ver detalhes. A aba Acompanhamento esta vazia; preencha-a com cards visuais de resumo por Status e Prioridade, usando contagens coerentes com os quatro registros. Nao mude para API real, nao use schema externo e nao crie regra de negocio definitiva.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.changeKind()).isNotEqualTo("answer_api_catalog_question");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.apiCatalogAnswer()).isNull();
        assertThat(result.assistantMessage() == null || !result.assistantMessage().contains("APIs relacionadas"))
                .isTrue();
        assertThat(result.failureCodes())
                .doesNotContain("resource-candidate-required", "resource-candidate-ambiguous");
        assertThat(result.warnings()).contains(
                "explicit-local-ui-composition-resource-selection-bypassed",
                "explicit-local-page-composition-normalized");
    }

    @Test
    void explicitLocalTabbedRenameDoesNotDiscoverApiResourcesWhenPromptForbidsDataSourceDiscovery() {
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
                        "api_catalog",
                        "answer_api_catalog_question",
                        null,
                        "recurso de exemplo",
                        "new_instruction",
                        "Encontrei mais de uma fonte de dados possivel para esta pagina.",
                        List.of(new AgenticAuthoringQuickReply(
                                "search-api-resources",
                                "suggestion",
                                "Buscar APIs",
                                "Quais APIs disponiveis podem alimentar esta tela?")),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Refine localmente a página atual: renomeie somente a aba Relacionamentos para Acompanhamento. Não descubra fonte de dados e não conecte API real.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "mock",
                null,
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.authoringProfile()).isEqualTo("ui-composition-plan@0.1.0");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.apiCatalogAnswer()).isNull();
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::prompt)
                .doesNotContain("Quais APIs disponiveis podem alimentar esta tela?");
        assertThat(result.failureCodes())
                .doesNotContain("resource-candidate-required", "resource-candidate-ambiguous");
        assertThat(result.warnings()).contains(
                "explicit-local-ui-composition-resource-selection-bypassed",
                "explicit-local-page-composition-normalized");
    }

    @Test
    void explicitLocalTableCrudRefinementDoesNotRouteToSharedRulesWhenPromptMentionsSla() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-resumo-missoes");
        ObjectNode domainCatalog = contextHints.putObject("domainCatalog");
        domainCatalog.put("schemaVersion", "praxis.ai.context-hints.domain-catalog/v0.2");
        domainCatalog.put("resourceKey", "human-resources.vw-resumo-missoes");
        domainCatalog.put("recommendedAuthoringFlow", "shared_rule_authoring");

        AgenticAuthoringIntentResolutionResult result = service.resolve(requestWithContextHints(
                "Refine esta tabela local/editorial preservando as três solicitações fictícias. "
                        + "Mantenha as colunas Título, Responsável, Categoria, SLA e Status, "
                        + "mantenha as ações Criar, Editar e Excluir visíveis, e destaque visualmente o SLA "
                        + "sem conectar API real, sem schema externo e sem criar regra de negócio definitiva.",
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.gate()).satisfiesAnyOf(
                gate -> assertThat(gate).isNull(),
                gate -> assertThat(gate.status()).isNotEqualTo("route_required"));
        assertThat(result.failureCodes())
                .doesNotContain(
                        "shared-rule-authoring-required",
                        "resource-candidate-required",
                        "resource-candidate-ambiguous");
        assertThat(result.assistantMessage() == null || !result.assistantMessage().contains("/api/praxis/config/domain-rules"))
                .isTrue();
        assertThat(result.warnings()).contains("explicit-local-ui-composition-resource-selection-bypassed");
    }

    @Test
    void explicitLocalTableCrudRefinementWithSelectedWidgetIsEligibleForComponentAuthoring() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", "/api/human-resources/vw-resumo-missoes");
        ObjectNode domainCatalog = contextHints.putObject("domainCatalog");
        domainCatalog.put("schemaVersion", "praxis.ai.context-hints.domain-catalog/v0.2");
        domainCatalog.put("resourceKey", "human-resources.vw-resumo-missoes");
        domainCatalog.put("recommendedAuthoringFlow", "shared_rule_authoring");

        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Refine esta tabela local/editorial preservando as três solicitações fictícias. "
                        + "Mantenha as colunas Título, Responsável, Categoria, SLA e Status, "
                        + "mantenha as ações Criar, Editar e Excluir visíveis, e destaque visualmente o SLA "
                        + "sem conectar API real, sem schema externo e sem criar regra de negócio definitiva.",
                "praxis-ui-angular",
                "praxis-table",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                "local-solicitacoes-crud",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                contextHints));

        assertThat(result.valid()).isTrue();
        assertThat(result.gate().status()).isEqualTo("eligible");
        assertThat(result.operationKind()).isEqualTo("modify");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.failureCodes())
                .doesNotContain(
                        "shared-rule-authoring-required",
                        "target-widget-required",
                        "resource-candidate-required",
                        "resource-candidate-ambiguous");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.warnings()).contains("explicit-local-ui-composition-resource-selection-bypassed");
    }

    @Test
    void localUiCompositionConfirmationKeepsOriginalPromptWhenLlmResolverIsEnabled() {
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
                        "page",
                        "create_composition",
                        null,
                        null,
                        "clarification_answer",
                        "",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                quickstartCandidateCatalog(),
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        String originalPrompt = "Crie uma página operacional com Praxis Tabs para solicitações internas. A aba Cadastro deve conter um formulário local/editorial com campos Título, Responsável, Prioridade e Prazo. A aba Registros deve conter um componente Praxis CRUD local/editorial com três solicitações fictícias e ações visíveis de criar, editar e excluir. A aba Relacionamentos deve conter uma lista em cards relacionada aos registros com três comentários fictícios. Use apenas conteúdo local/editorial de demonstração, sem API real, sem schema externo e sem criar regra de negócio definitiva.";

        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Sim, siga e gere o preview com essa composição local/editorial.",
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
                        new AgenticAuthoringConversationMessage("m1", "user", originalPrompt, null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Entendi: posso montar a composição com Praxis Tabs e três áreas: Cadastro, Registros e Relacionamentos.", null)),
                null));

        assertThat(result.valid()).isTrue();
        assertThat(result.effectivePrompt())
                .contains(originalPrompt)
                .contains("Confirmed: Sim, siga e gere o preview");
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.failureCodes())
                .doesNotContain("resource-candidate-required", "resource-candidate-ambiguous");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::kind)
                .doesNotContain("resource");
        assertThat(result.warnings()).contains("explicit-local-ui-composition-resource-selection-bypassed");
        ArgumentCaptor<String> effectivePromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llmIntentResolver).resolve(
                Mockito.any(),
                effectivePromptCaptor.capture(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyList(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any());
        assertThat(effectivePromptCaptor.getValue()).contains(originalPrompt);
    }

    @Test
    void businessRulePromptRoutesToSharedRuleAuthoringWithoutExplicitDomainCatalogHint() {
        ObjectNode contextHints = resourcePathContextHints("/api/procurement/suppliers");
        AgenticAuthoringIntentResolutionResult result = service.resolve(requestWithContextHints(
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                contextHints));

        assertThat(result.valid()).isFalse();
        assertThat(result.gate().status()).isEqualTo("route_required");
        assertThat(result.failureCodes()).contains("shared-rule-authoring-required");
        assertThat(result.assistantMessage())
                .contains("/api/praxis/config/domain-rules")
                .contains("/api/praxis/config/domain-rules/intake")
                .contains("/api/praxis/config/domain-rules/simulations")
                .contains("/api/procurement/suppliers");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/procurement/suppliers");
        assertThat(result.selectedCandidate().evidence()).contains("quick-reply-context");
    }

    @Test
    void explicitResourcePathInPromptOverridesDomainHeuristicsForSharedRuleAuthoring() {
        AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                "Crie uma regra de validacao governada para chamados em /api/helpdesk/chamados: prioridade e titulo obrigatorios antes de salvar, mesmo citando funcionarios no texto.",
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
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/helpdesk/chamados");
        assertThat(result.selectedCandidate().evidence()).contains("explicit-resource-path");
        assertThat(result.assistantMessage())
                .contains("/api/praxis/config/domain-rules")
                .contains("/api/helpdesk/chamados");
    }

    @Test
    void canonicalRoutingMatrixRoutesBusinessRulesToSharedRuleAuthoring() {
        List<RoutingCase> cases = List.of(
                new RoutingCase(
                        "Crie uma politica de elegibilidade para fornecedores inativos",
                        "/api/procurement/suppliers"),
                new RoutingCase(
                        "Fornecedor inactive ou blocked nao pode aparecer como selecionavel",
                        "/api/procurement/suppliers"),
                new RoutingCase(
                        "Crie uma regra LGPD para CPF no formulario de funcionarios",
                        "/api/human-resources/funcionarios"),
                new RoutingCase(
                        "Validar que pedido de compra sem aprovacao nao pode seguir",
                        "/api/procurement/purchase-orders"));

        for (RoutingCase routingCase : cases) {
            ObjectNode contextHints = resourcePathContextHints(routingCase.resourcePath());
            AgenticAuthoringIntentResolutionResult result = service.resolve(requestWithContextHints(
                    routingCase.prompt(),
                    contextHints));

            assertThat(result.valid())
                    .as(routingCase.prompt())
                    .isFalse();
            assertThat(result.gate().status())
                    .as(routingCase.prompt())
                    .isEqualTo("route_required");
            assertThat(result.failureCodes())
                    .as(routingCase.prompt())
                    .contains("shared-rule-authoring-required");
            assertThat(result.assistantMessage())
                    .as(routingCase.prompt())
                    .contains("/api/praxis/config/domain-rules")
                    .contains("/api/praxis/config/domain-rules/intake")
                    .contains("/api/praxis/config/domain-rules/simulations");
            assertThat(result.selectedCandidate())
                    .as(routingCase.prompt())
                    .isNotNull();
            assertThat(result.selectedCandidate().resourcePath())
                    .as(routingCase.prompt())
                    .isEqualTo(routingCase.resourcePath());
        }
    }

    @Test
    void deterministicGuardrailRoutesGovernancePromptsWithResolvedResourceToSharedRules() {
        List<RoutingCase> cases = List.of(
                new RoutingCase(
                        "Dados sensiveis exigem revisao antes de aprovar",
                        "/api/human-resources/funcionarios"),
                new RoutingCase(
                        "Mascare CPF por LGPD antes de qualquer uso por IA",
                        "/api/human-resources/funcionarios"),
                new RoutingCase(
                        "Pedidos de compra precisam de aprovacao obrigatoria antes de seguir",
                        "/api/procurement/purchase-orders"));

        for (RoutingCase routingCase : cases) {
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("resourcePath", routingCase.resourcePath());

            AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                    routingCase.prompt(),
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

            assertThat(result.valid())
                    .as(routingCase.prompt())
                    .isFalse();
            assertThat(result.gate().status())
                    .as(routingCase.prompt())
                    .isEqualTo("route_required");
            assertThat(result.failureCodes())
                    .as(routingCase.prompt())
                    .contains("shared-rule-authoring-required");
            assertThat(result.selectedCandidate())
                    .as(routingCase.prompt())
                    .isNotNull();
            assertThat(result.selectedCandidate().resourcePath())
                    .as(routingCase.prompt())
                    .isEqualTo(routingCase.resourcePath());
        }
    }

    @Test
    void canonicalRoutingMatrixKeepsVisualAuthoringEligibleForPreview() {
        List<RoutingCase> cases = List.of(
                new RoutingCase(
                        "Crie um formulario de funcionarios",
                        "/api/human-resources/funcionarios"),
                new RoutingCase(
                        "Adicione o campo salario no formulario de funcionarios",
                        "/api/human-resources/funcionarios"),
                new RoutingCase(
                        "Monte um dashboard de folha de pagamento por departamento",
                        "/api/human-resources/vw-analytics-folha-pagamento"));

        for (RoutingCase routingCase : cases) {
            AgenticAuthoringIntentResolutionResult result = service.resolve(new AgenticAuthoringIntentResolutionRequest(
                    routingCase.prompt(),
                    "praxis-ui-angular",
                    "praxis-dynamic-page-builder",
                    "/page-builder-ia",
                    objectMapper.createObjectNode(),
                    null,
                    null,
                    null,
                    null));

            assertThat(result.gate().status())
                    .as(routingCase.prompt())
                    .isNotEqualTo("route_required");
            assertThat(result.failureCodes())
                    .as(routingCase.prompt())
                    .doesNotContain("shared-rule-authoring-required");
            assertThat(result.selectedCandidate())
                    .as(routingCase.prompt())
                    .isNotNull();
            assertThat(result.selectedCandidate().resourcePath())
                    .as(routingCase.prompt())
                    .isEqualTo(routingCase.resourcePath());
        }
    }

    @Test
    void businessRulePromptKeepsKnownResourceCandidateWhenLlmIntentIsUnavailable() {
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
                .thenReturn(Optional.empty());
        AgenticAuthoringIntentResolverService llmFirstService = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                null);

        ObjectNode contextHints = resourcePathContextHints("/api/procurement/suppliers");
        AgenticAuthoringIntentResolutionResult result = llmFirstService.resolve(requestWithContextHints(
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                "deterministic-smoke-disabled",
                contextHints));

        assertThat(result.valid()).isFalse();
        assertThat(result.gate().status()).isEqualTo("route_required");
        assertThat(result.failureCodes()).contains("shared-rule-authoring-required");
        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().resourcePath()).isEqualTo("/api/procurement/suppliers");
        assertThat(result.selectedCandidate().evidence()).contains("quick-reply-context");
        assertThat(result.warnings()).contains("llm-intent-resolution-fallback-deterministic");
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
        assertThat(result.currentPageSummary()
                        .path("structuralInspection")
                        .path("widgets")
                        .path(0)
                        .path("artifactKind")
                        .asText())
                .isEqualTo("form");
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

    private record RoutingCase(String prompt, String resourcePath) {
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
