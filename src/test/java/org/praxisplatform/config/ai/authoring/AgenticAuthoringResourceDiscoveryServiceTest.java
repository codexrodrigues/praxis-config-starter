package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;

@Tag("unit")
class AgenticAuthoringResourceDiscoveryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchReturnsCandidatesFromApiMetadataCatalog() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/praxis/config/ui-user-config",
                        "GET",
                        "config",
                        "Configuracoes de usuario",
                        "Endpoint interno de configuracao",
                        "listUiUserConfig",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Quais APIs analiticas podem alimentar graficos de folha de pagamento?",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates().get(0).evidence()).contains("lexical-fallback");
        AgenticAuthoringEvidenceBundle evidenceBundle = result.candidates().get(0).evidenceBundle();
        assertThat(evidenceBundle).isNotNull();
        assertThat(evidenceBundle.retrievalSource()).isEqualTo("lexical_fallback");
        assertThat(evidenceBundle.evidence())
                .extracting(AgenticAuthoringEvidenceBundle.Evidence::source)
                .contains("api_metadata", "/schemas/filtered", "capabilities", "actions", "domain_catalog");
        assertThat(evidenceBundle.evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.kind()).isEqualTo("weak_lexical_match");
                    assertThat(evidence.confidence()).isLessThan(0.5d);
                    assertThat(evidence.matchedTerms()).contains("folha", "pagamento");
                });
        assertThat(result.assistantMessage()).contains("Encontrei APIs");
        assertThat(result.quickReplies()).hasSize(1);
        assertThat(result.quickReplies().get(0).id())
                .isEqualTo("resource-api-human-resources-vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).label()).isEqualTo("analytics folha pagamento");
        assertThat(result.quickReplies().get(0).prompt())
                .isEqualTo("Usar analytics folha pagamento como fonte de dados do painel.");
        assertThat(result.quickReplies().get(0).description())
                .contains("Indicada para montar um painel")
                .contains("schema confirmar os recortes");
        assertThat(result.quickReplies().get(0).contextHints().path("presentation").path("bestFor").asText())
                .contains("schema confirmar os recortes");
        assertThat(result.quickReplies().get(0).contextHints().path("presentation").path("returns").asText())
                .contains("gráficos materializados por schema");
        assertThat(result.quickReplies().get(0).contextHints().path("presentation").path("nextStep").asText())
                .contains("Clique");
        assertThat(result.candidates().get(0).submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.candidates().get(0).submitMethod()).isEqualTo("post");
        assertThat(result.candidates().get(0).schemaUrl())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response");
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("technicalDetails").path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.quickReplies().get(0).contextHints().path("artifactKind").asText())
                .isEqualTo("dashboard");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("schemaVersion").asText())
                .isEqualTo("praxis.ai.context-hints.domain-catalog/v0.2");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("serviceKey").asText())
                .isEqualTo("praxis-service");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("contextKey").asText())
                .isEqualTo("human-resources");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("resourceKey").asText())
                .isEqualTo("human-resources.vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("query").asText())
                .contains("folha de pagamento")
                .contains("analytics folha pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("type").asText())
                .isEqualTo("node");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("enabled").asBoolean())
                .isTrue();
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("federated").asBoolean())
                .isTrue();
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("policyProfile").asText())
                .isEqualTo("authoring");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("relationships").path("query").asText())
                .contains("folha de pagamento")
                .contains("analytics folha pagamento");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void metadataCatalogDoesNotEmitDomainAnchorCandidatesInDefaultDiscoveryPath() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/procurement/suppliers",
                        "POST",
                        "procurement,fornecedores,suppliers,compras,dashboard,tabela",
                        "Fornecedores",
                        "Fonte de fornecedores para paineis, tabelas e analises de compras.",
                        "filterSuppliers",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository);

        List<AgenticAuthoringCandidate> candidates =
                catalog.discover("quero acompanhar fornecedores de compras", "dashboard");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.evidence()).doesNotContain("domain-anchor"));
        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::evidence)
                .anySatisfy(evidence -> assertThat(evidence).contains("lexical-fallback"));
    }

    @Test
    void metadataCatalogDoesNotExpandHostDomainSynonymsByDefault() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "funcionarios",
                        "Funcionarios",
                        "Cadastro de funcionarios.",
                        "filterFuncionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository);

        List<AgenticAuthoringCandidate> candidates =
                catalog.discover("quero monitorar colaboradores", "dashboard");

        assertThat(candidates).isEmpty();
    }

    @Test
    void enterprisePeoplePromptPrefersEmployeeResourceOverGenericDashboardIndicators() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "POST",
                        "risk,intelligence,incidentes,indicadores,analytics,dashboard",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes por gravidade e responsavel.",
                        "riskIncidentIndicators",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios,funcionario,colaboradores,departamento,cargo,status",
                        "Funcionarios",
                        "Cadastro de pessoas da empresa, colaboradores, cargos, departamentos e status.",
                        "filterFuncionarios",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/departamentos",
                        "POST",
                        "human-resources,departamentos,departamento",
                        "Departamentos",
                        "Cadastro de departamentos da empresa.",
                        "filterDepartamentos",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Quero um dashboard de pessoas da empresa com indicadores, grafico por departamento, filtros e uma tabela de detalhes conectada.",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/human-resources/funcionarios")
                .contains("/api/human-resources/departamentos");
        assertThat(result.candidates().get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.candidates().get(0).resourcePath())
                .isNotEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
    }

    @Test
    void semanticRetrievalIsPreferredOverLexicalFallback() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "Resumo analitico de folha por departamento",
                null,
                null,
                8,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/human-resources/vw-analytics-folha-pagamento")
                        .method("GET")
                        .summary("Analytics de folha de pagamento")
                        .similarityScore(0.93d)
                        .build()));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Resumo analitico de folha por departamento",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.candidates().get(0).submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.candidates().get(0).submitMethod()).isEqualTo("post");
        assertThat(result.candidates().get(0).evidence())
                .contains("semantic-retrieval")
                .doesNotContain("lexical-fallback");
        assertThat(result.candidates().get(0).evidenceBundle().retrievalSource())
                .isEqualTo("semantic_retrieval");
        assertThat(result.candidates().get(0).evidenceBundle().evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.source()).isEqualTo("api_metadata");
                    assertThat(evidence.kind()).isEqualTo("retrieved_candidate");
                    assertThat(evidence.confidence()).isGreaterThan(0.9d);
                });
        verify(repository, never()).findAll();
    }

    @Test
    void semanticRetrievalReceivesPrincipalScopeWhenProvided() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "Resumo analitico de folha por departamento",
                null,
                null,
                8,
                null,
                "desenv",
                "local",
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/human-resources/vw-analytics-folha-pagamento")
                        .method("GET")
                        .summary("Analytics de folha de pagamento")
                        .similarityScore(0.93d)
                        .build()));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Resumo analitico de folha por departamento",
                        null,
                        "dashboard",
                        5),
                new AiPrincipalContext("desenv", "demo", "local", false));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).evidenceBundle().evidence())
                .allSatisfy(evidence -> {
                    assertThat(evidence.tenantId()).isEqualTo("desenv");
                    assertThat(evidence.environment()).isEqualTo("local");
                });
        verify(retrievalService).searchApiMetadata(
                "Resumo analitico de folha por departamento",
                null,
                null,
                8,
                null,
                "desenv",
                "local",
                null);
    }

    @Test
    void searchPromotesToolCandidatesThroughDomainCatalogGrounding() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        AgenticAuthoringDomainCatalogCandidateEnhancer enhancer =
                Mockito.mock(AgenticAuthoringDomainCatalogCandidateEnhancer.class);
        AgenticAuthoringCandidate weakCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter",
                "post",
                0.48d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of("lexical_fallback", List.of()));
        AgenticAuthoringCandidate groundedCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter",
                "post",
                0.84d,
                "domain_catalog grounded resource selection",
                List.of(
                        "api-metadata",
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING,
                        "semantic-retrieval"),
                AgenticAuthoringEvidenceBundle.of("domain_catalog", List.of()));
        AgenticAuthoringCandidate ungroundedWeakCandidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                "post",
                0.62d,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                AgenticAuthoringEvidenceBundle.of("lexical_fallback", List.of()));
        when(candidateCatalog.discover(
                "funcionarios",
                "api_catalog",
                "tenant-a",
                "dev",
                null))
                .thenReturn(List.of(weakCandidate));
        when(enhancer.enhance(
                "funcionarios",
                List.of(weakCandidate),
                "tenant-a",
                "dev"))
                .thenReturn(List.of(groundedCandidate, ungroundedWeakCandidate));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        candidateCatalog,
                        objectMapper,
                        "praxis-service",
                        enhancer);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "funcionarios",
                        null,
                        "api_catalog",
                        5),
                new AiPrincipalContext("tenant-a", "user-a", "dev", false));

        assertThat(result.candidates()).containsExactly(groundedCandidate);
        assertThat(result.candidates().get(0).evidence())
                .contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                .doesNotContain("lexical-fallback", "weak-evidence");
    }

    @Test
    void evidenceBundleSupportsHostNeutralApiMetadataFixture() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "POST",
                        "risk,intelligence,incidentes,indicadores,analytics,dashboard",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes por gravidade e responsavel.",
                        "riskIncidentIndicators",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository);

        List<AgenticAuthoringCandidate> candidates =
                catalog.discover("monitorar incidentes por gravidade", "dashboard", "tenant-a", "staging", "release-2026.05");

        assertThat(candidates).hasSize(1);
        AgenticAuthoringCandidate selected = candidates.get(0);
        assertThat(selected.resourcePath()).isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(selected.evidenceBundle().evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.source()).isEqualTo("domain_catalog");
                    assertThat(evidence.ref()).isEqualTo("risk-intelligence.vw-indicadores-incidentes");
                    assertThat(evidence.tenantId()).isEqualTo("tenant-a");
                    assertThat(evidence.environment()).isEqualTo("staging");
                    assertThat(evidence.releaseId()).isEqualTo("release-2026.05");
                });
    }

    @Test
    void semanticRetrievalDoesNotInventAnalyticsProjectionForAnalyticalDashboardPrompts() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "quero ver quem recebe mais e comparar por area",
                null,
                null,
                8,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/human-resources/funcionarios")
                        .method("POST")
                        .summary("Cadastrar funcionario")
                        .similarityScore(0.62d)
                        .build()));
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "human-resources,funcionarios,funcionario,colaboradores",
                        "Cadastrar funcionario",
                        "Cadastro operacional de funcionarios.",
                        "createFuncionario",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "human-resources,analytics,folha,pagamento,salario,salarios,departamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica para rankings, comparacoes e dashboards por departamento.",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "quero ver quem recebe mais e comparar por area",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/funcionarios");
        assertThat(result.candidates().get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.candidates().get(0).submitUrl())
                .isEqualTo("/api/human-resources/funcionarios/stats/group-by");
    }

    @Test
    void analyticalComparisonPrefersGroupByAndExcludesExportEndpoints() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                        "POST",
                        "human-resources,analytics,folha,pagamento,salario,competencia",
                        "Time-series stats sobre analytics da folha",
                        "Serie temporal de folha de pagamento por competencia.",
                        "timeSeriesStats",
                        "{\"fields\":[{\"name\":\"field\",\"type\":\"string\"},{\"name\":\"metrics\",\"type\":\"array\"}]}",
                        "{\"fields\":[{\"name\":\"salarioLiquido\",\"type\":\"number\"},{\"name\":\"competencia\",\"type\":\"string\"}]}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "POST",
                        "human-resources,analytics,folha,pagamento,salario,departamento",
                        "Group-by stats sobre analytics da folha",
                        "Agrupa folha de pagamento para comparacoes e rankings por departamento ou area.",
                        "groupByStats",
                        "{\"fields\":[{\"name\":\"groupBy\",\"type\":\"string\"},{\"name\":\"metrics\",\"type\":\"array\"}]}",
                        "{\"fields\":[{\"name\":\"departamento\",\"type\":\"string\"},{\"name\":\"salarioLiquido\",\"type\":\"number\"}]}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/export",
                        "POST",
                        "human-resources,analytics,folha,pagamento,export",
                        "Exportar colecao",
                        "Exporta dados da colecao.",
                        "exportCollection",
                        "{\"fields\":[{\"name\":\"format\",\"type\":\"string\"}]}",
                        "{\"fields\":[{\"name\":\"downloadUrl\",\"type\":\"string\"}]}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "quero ver quem recebe mais e comparar por area",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates().get(0).submitUrl())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::submitUrl)
                .noneMatch(url -> url.contains("/export"));
    }

    @Test
    void quickRepliesAreCuratedAsOneCardPerResourceWithRelatedOperations() {
        AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog =
                Mockito.mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        when(candidateCatalog.discover(
                "analytics folha pagamento",
                "dashboard",
                null,
                null,
                null))
                .thenReturn(List.of(
                        new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "groupByStats",
                                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                                "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                                "post",
                                0.94d,
                                "Aggregated payroll analytics.",
                                List.of("semantic-retrieval")),
                        new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "timeSeriesStats",
                                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                                "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                                "post",
                                0.88d,
                                "Payroll trends over time.",
                                List.of("semantic-retrieval")),
                        new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "filterCollection",
                                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter",
                                "/api/human-resources/vw-analytics-folha-pagamento/filter",
                                "post",
                                0.81d,
                                "Payroll drill-down records.",
                                List.of("semantic-retrieval"))));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(candidateCatalog, objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "analytics folha pagamento",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.quickReplies()).hasSize(1);
        assertThat(result.quickReplies().get(0).id())
                .isEqualTo("resource-api-human-resources-vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(result.quickReplies().get(0).contextHints()
                .path("technicalDetails")
                .path("relatedOperations"))
                .hasSize(3);
        assertThat(result.quickReplies().get(0).contextHints()
                .path("technicalDetails")
                .path("relatedOperations")
                .findValuesAsText("submitUrl"))
                .contains(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter");
    }

    @Test
    void searchDoesNotExposeAllEndpointsAsGovernedAuthoringChoices() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/funcionarios/all",
                        "GET",
                        "funcionarios",
                        "Listar todos funcionarios",
                        "Endpoint legado sem paginacao para demos",
                        "listAllFuncionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        "funcionarios",
                        "Filtrar funcionarios com cursor",
                        "Consulta governada e paginada para telas corporativas de busca e detalhe.",
                        "filterFuncionariosCursor",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "quero procurar empregados e abrir detalhes",
                        null,
                        "page",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::submitUrl)
                .containsExactly("/api/human-resources/funcionarios/filter/cursor");
        assertThat(result.quickReplies())
                .extracting(AgenticAuthoringQuickReply::description)
                .noneMatch(description -> description.contains("/all"));
    }

    @Test
    void searchRequiresQueryBeforeCallingCatalog() {
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(null, "  ", null, null));

        assertThat(result.valid()).isFalse();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.assistantMessage()).contains("descricao do dado de negocio");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.quickReplies()).isEmpty();
        assertThat(result.warnings()).containsExactly("resource-discovery-query-required");
    }
}
