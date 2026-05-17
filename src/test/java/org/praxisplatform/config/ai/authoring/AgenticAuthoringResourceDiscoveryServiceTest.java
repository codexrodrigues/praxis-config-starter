package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

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
    void apiCatalogSearchUsesGovernedConsultativeProjection() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        "folha,pagamento",
                        "Listar folhas de pagamento",
                        "Lista folhas de pagamento",
                        "listFolhasPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/folhas-pagamento/filter",
                        "POST",
                        "folha,pagamento",
                        "Filtrar folhas de pagamento",
                        "Filtra folhas de pagamento",
                        "filterFolhasPagamento",
                        "{}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "POST",
                        "analytics,folha,pagamento",
                        "Agrupar analytics de folha de pagamento",
                        "Agrupa valores de folha de pagamento",
                        "groupByAnalyticsFolhaPagamento",
                        "{}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/funcionarios",
                        "GET",
                        "funcionarios",
                        "Listar funcionarios",
                        "Lista funcionarios",
                        "listFuncionarios",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        DomainCatalogIngestionService domainCatalog = Mockito.mock(DomainCatalogIngestionService.class);
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.folhas-pagamento"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.folhas-pagamento",
                "Folhas Pagamento",
                "Cadastro e acompanhamento operacional da folha de pagamento",
                List.of("Mes", "Ano", "Salario bruto")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.vw-analytics-folha-pagamento"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.vw-analytics-folha-pagamento",
                "Analytics Folha Pagamento",
                "Visao analitica para indicadores e graficos de folha de pagamento",
                List.of("Departamento", "Status", "Valor total")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.funcionarios"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.funcionarios",
                "Funcionarios",
                "Cadastro de pessoas da empresa",
                List.of("Nome", "Cargo")));

        AgenticAuthoringConsultativeApiCatalogProjectionService projectionService =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> domainCatalog,
                        repository,
                        "praxis-service");
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper,
                        "praxis-service",
                        null,
                        projectionService);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Quais APIs estao relacionadas a folha de pagamento?",
                        null,
                        "api_catalog",
                        8));

        assertThat(result.valid()).isTrue();
        assertThat(result.consultativeProjection()).isNotNull();
        assertThat(result.consultativeProjection().resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .contains("human-resources.folhas-pagamento", "human-resources.vw-analytics-folha-pagamento")
                .doesNotContain("human-resources.funcionarios");
        assertThat(result.assistantMessage())
                .contains("fontes de dados confirmadas")
                .contains("Folhas Pagamento")
                .contains("Analytics Folha Pagamento")
                .contains("Campos confirmados")
                .doesNotContain("schema");
        assertThat(result.warnings()).contains("domain-api-consultative-projection-used");
    }

    @Test
    void apiCatalogProjectionCanAnswerFromDomainCatalogWithoutInitialApiMetadataCandidate() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        DomainCatalogIngestionService domainCatalog = Mockito.mock(DomainCatalogIngestionService.class);
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq("node"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.anyString(),
                Mockito.eq(8))).thenReturn(domainContext(
                "human-resources.funcionarios",
                "Funcionarios",
                "Pessoas e colaboradores da empresa",
                List.of("Nome", "Cargo", "Departamento")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.funcionarios"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.funcionarios",
                "Funcionarios",
                "Pessoas e colaboradores da empresa",
                List.of("Nome", "Cargo", "Departamento")));

        AgenticAuthoringConsultativeApiCatalogProjectionService projectionService =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> domainCatalog,
                        repository,
                        "praxis-service");
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper,
                        "praxis-service",
                        null,
                        projectionService);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "Que dados existem sobre pessoas da empresa?",
                        null,
                        "api_catalog",
                        8));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.consultativeProjection()).isNotNull();
        assertThat(result.consultativeProjection().resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .containsExactly("human-resources.funcionarios");
        assertThat(result.assistantMessage())
                .contains("fonte de dados confirmada")
                .contains("Funcionarios")
                .contains("Campos confirmados");
        assertThat(result.warnings())
                .contains("domain-api-consultative-projection-used", "resource-candidates-empty");
    }

    @Test
    void apiCatalogProjectionFiltersWeakResourcesForFocusedCompoundDomainQuestion() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        DomainCatalogIngestionService domainCatalog = Mockito.mock(DomainCatalogIngestionService.class);
        String prompt = "Quais APIs e dados estao relacionados a folha de pagamento?";
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(prompt),
                Mockito.eq(8))).thenReturn(discoveryContext(List.of(
                        "human-resources.folhas-pagamento",
                        "human-resources.vw-analytics-folha-pagamento",
                        "human-resources.reputacoes",
                        "human-resources.ferias-afastamentos")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.folhas-pagamento"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.folhas-pagamento",
                "Folhas Pagamento",
                "Cadastro e acompanhamento operacional da folha de pagamento",
                List.of("Competencia", "Salario bruto")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.vw-analytics-folha-pagamento"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.vw-analytics-folha-pagamento",
                "Analytics Folha Pagamento",
                "Visao analitica para indicadores de folha de pagamento",
                List.of("Departamento", "Valor total")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.reputacoes"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.reputacoes",
                "Reputacoes",
                "Acompanhamento de reputacao corporativa",
                List.of("Pessoa", "Nota")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.eq("human-resources.ferias-afastamentos"),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq(80))).thenReturn(domainContext(
                "human-resources.ferias-afastamentos",
                "Ferias Afastamentos",
                "Controle de ausencias e afastamentos",
                List.of("Pessoa", "Periodo")));

        AgenticAuthoringConsultativeApiCatalogProjectionService projectionService =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> domainCatalog,
                        repository,
                        "praxis-service");

        AgenticAuthoringConsultativeApiCatalogProjection projection = projectionService.project(
                prompt,
                List.of(),
                "tenant-a",
                "dev");

        assertThat(projection).isNotNull();
        assertThat(projection.resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .containsExactlyInAnyOrder(
                        "human-resources.folhas-pagamento",
                        "human-resources.vw-analytics-folha-pagamento")
                .doesNotContain(
                        "human-resources.reputacoes",
                        "human-resources.ferias-afastamentos");
    }

    @Test
    void consultativeProjectionStopsFederatedCatalogDiscoveryAfterEnoughResources() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ApiMetadata(
                "/api/human-resources/folhas-pagamento",
                "GET",
                "folha,pagamento",
                "Listar folhas de pagamento",
                "Lista folhas de pagamento",
                "listFolhasPagamento",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null)));
        DomainCatalogIngestionService domainCatalog = Mockito.mock(DomainCatalogIngestionService.class);
        List<String> resourceKeys = List.of(
                "human-resources.folhas-pagamento",
                "human-resources.funcionarios",
                "human-resources.cargos",
                "human-resources.departamentos",
                "human-resources.vw-analytics-folha-pagamento",
                "human-resources.historicos-salariais");
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("Que dados existem sobre pessoas, cargos, departamentos e folha?"),
                Mockito.eq(8))).thenReturn(discoveryContext(resourceKeys));
        for (String resourceKey : resourceKeys) {
            when(domainCatalog.contextLatest(
                    Mockito.eq("praxis-service"),
                    Mockito.eq(resourceKey),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.eq(80))).thenReturn(domainContext(
                    resourceKey,
                    resourceKey.substring(resourceKey.lastIndexOf('.') + 1),
                    resourceKey.endsWith(".funcionarios") ? "Cadastro de pessoas e funcionarios da empresa" : "",
                    List.of("Nome")));
        }

        AgenticAuthoringConsultativeApiCatalogProjectionService projectionService =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> domainCatalog,
                        repository,
                        "praxis-service");

        AgenticAuthoringConsultativeApiCatalogProjection projection = projectionService.project(
                "Que dados existem sobre pessoas, cargos, departamentos e folha?",
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "list",
                        "/schemas/human-resources/folhas-pagamento",
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        0.95d,
                        "semantic match",
                        List.of("semantic-retrieval"))),
                "tenant-a",
                "dev");

        assertThat(projection).isNotNull();
        assertThat(projection.resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .contains(
                        "human-resources.folhas-pagamento",
                        "human-resources.funcionarios",
                        "human-resources.cargos",
                        "human-resources.departamentos",
                        "human-resources.vw-analytics-folha-pagamento");
        Mockito.verify(domainCatalog, Mockito.never()).contextLatest(
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.nullable(String.class),
                Mockito.eq(8));
        Mockito.verify(domainCatalog, Mockito.never()).contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.nullable(String.class),
                Mockito.nullable(String.class),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("pessoas"),
                Mockito.eq(8));
    }

    @Test
    void consultativeProjectionContinuesCatalogDiscoveryAfterPartialServiceScopedHits() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ApiMetadata(
                "/api/human-resources/folhas-pagamento",
                "GET",
                "folha,pagamento",
                "Listar folhas de pagamento",
                "Lista folhas de pagamento",
                "listFolhasPagamento",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null)));
        DomainCatalogIngestionService domainCatalog = Mockito.mock(DomainCatalogIngestionService.class);
        String prompt = "Que dados existem sobre pessoas, cargos, departamentos e folha?";
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(prompt),
                Mockito.eq(8))).thenReturn(discoveryContext(List.of(
                        "human-resources.folhas-pagamento",
                        "human-resources.departamentos")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("pessoas"),
                Mockito.eq(8))).thenReturn(discoveryContext(List.of("human-resources.funcionarios")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("cargos"),
                Mockito.eq(8))).thenReturn(discoveryContext(List.of("human-resources.cargos")));
        when(domainCatalog.contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("folha"),
                Mockito.eq(8))).thenReturn(discoveryContext(List.of("human-resources.vw-analytics-folha-pagamento")));
        List<String> resourceKeys = List.of(
                "human-resources.folhas-pagamento",
                "human-resources.departamentos",
                "human-resources.funcionarios",
                "human-resources.cargos",
                "human-resources.vw-analytics-folha-pagamento");
        for (String resourceKey : resourceKeys) {
            when(domainCatalog.contextLatest(
                    Mockito.eq("praxis-service"),
                    Mockito.eq(resourceKey),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.nullable(String.class),
                    Mockito.eq(80))).thenReturn(domainContext(
                    resourceKey,
                    resourceKey.substring(resourceKey.lastIndexOf('.') + 1),
                    resourceKey.endsWith(".funcionarios") ? "Cadastro de pessoas e funcionarios da empresa" : "",
                    List.of("Nome")));
        }

        AgenticAuthoringConsultativeApiCatalogProjectionService projectionService =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> domainCatalog,
                        repository,
                        "praxis-service");

        AgenticAuthoringConsultativeApiCatalogProjection projection = projectionService.project(
                prompt,
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "list",
                        "/schemas/human-resources/folhas-pagamento",
                        "/api/human-resources/folhas-pagamento",
                        "GET",
                        0.95d,
                        "semantic match",
                        List.of("semantic-retrieval"))),
                "tenant-a",
                "dev");

        assertThat(projection).isNotNull();
        assertThat(projection.resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .contains(
                        "human-resources.folhas-pagamento",
                        "human-resources.departamentos",
                        "human-resources.funcionarios",
                        "human-resources.cargos",
                        "human-resources.vw-analytics-folha-pagamento");
        Mockito.verify(domainCatalog).contextLatest(
                Mockito.eq("praxis-service"),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("pessoas"),
                Mockito.eq(8));
        Mockito.verify(domainCatalog, Mockito.never()).contextLatest(
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq("tenant-a"),
                Mockito.eq("dev"),
                Mockito.eq("node"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(prompt),
                Mockito.eq(8));
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
        verify(repository).findAll();
    }

    @Test
    void apiCatalogSemanticRetrievalIsSupplementedWithExplicitCatalogMatches() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "Que dados existem sobre pessoas, cargos, departamentos e folha?",
                null,
                null,
                8,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/human-resources/folhas-pagamento")
                        .method("POST")
                        .summary("Folhas de pagamento")
                        .similarityScore(0.91d)
                        .build()));
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/cargos",
                        "GET",
                        "human-resources,cargos",
                        "Cargos",
                        "Catalogo de cargos funcionais.",
                        "listCargos",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/departamentos",
                        "GET",
                        "human-resources,departamentos",
                        "Departamentos",
                        "Catalogo de departamentos organizacionais.",
                        "listDepartamentos",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates =
                catalog.discover("Que dados existem sobre pessoas, cargos, departamentos e folha?", "api_catalog");

        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains(
                        "/api/human-resources/folhas-pagamento",
                        "/api/human-resources/cargos",
                        "/api/human-resources/departamentos");
        assertThat(candidates)
                .anySatisfy(candidate -> {
                    assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/folhas-pagamento");
                    assertThat(candidate.evidence()).contains("semantic-retrieval");
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/cargos");
                    assertThat(candidate.evidence()).contains("lexical-fallback");
                });
    }

    @Test
    void weakSemanticRetrievalIsReconciledWithLexicalCatalogEvidence() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "Quais APIs estao relacionadas a folha de pagamento?",
                null,
                null,
                8,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/human-resources/vw-resumo-missoes")
                        .method("POST")
                        .summary("Resumo de missoes")
                        .similarityScore(0.49d)
                        .build()));
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-resumo-missoes",
                        "POST",
                        "missoes,resumo,human-resources",
                        "Resumo de missoes",
                        "Resumo de missoes por equipe.",
                        "filterResumoMissoes",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human-resources,folha,pagamento,analytics,dashboard",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento, competencia e colaborador.",
                        "filterAnalyticsFolhaPagamento",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates =
                catalog.discover("Quais APIs estao relacionadas a folha de pagamento?", "api_catalog");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(candidates.get(0).evidence()).contains("lexical-fallback");
        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/human-resources/vw-resumo-missoes");
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
    void dashboardDiscoveryKeepsAnalyticalCompanionWhenSemanticRetrievalSelectsTransactionalResource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        when(retrievalService.searchApiMetadata(
                "crie apenas um grafico de barras simples de incidentes por severidade",
                null,
                null,
                8,
                null,
                null,
                null,
                null))
                .thenReturn(List.of(ApiSearchResult.builder()
                        .path("/api/operations/incidentes")
                        .method("POST")
                        .summary("Incidentes operacionais")
                        .similarityScore(0.86d)
                        .build()));
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/operations/incidentes",
                        "POST",
                        "operations,incidentes",
                        "Incidentes operacionais",
                        "Cadastro operacional de incidentes com severidade.",
                        "createIncidente",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\",\"properties\":{\"severidade\":{\"type\":\"string\"}}}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "POST",
                        "risk-intelligence,incidentes,indicadores,analytics,dashboard",
                        "Indicadores de incidentes",
                        "Visao analitica de incidentes por severidade.",
                        "listVwIndicadoresIncidentes",
                        "{\"type\":\"object\"}",
                        "{\"type\":\"object\",\"properties\":{\"severidade\":{\"type\":\"string\"}}}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService),
                        objectMapper);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(
                        "crie apenas um grafico de barras simples de incidentes por severidade",
                        null,
                        "dashboard",
                        5));

        assertThat(result.valid()).isTrue();
        assertThat(result.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/operations/incidentes", "/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.candidates())
                .filteredOn(candidate -> "/api/risk-intelligence/vw-indicadores-incidentes"
                        .equals(candidate.resourcePath()))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.submitUrl())
                        .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by"));
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

    private DomainCatalogContextResponse domainContext(
            String resourceKey,
            String label,
            String description,
            List<String> fields) {
        List<DomainCatalogItemResponse> items = new java.util.ArrayList<>();
        ObjectNode concept = objectMapper.createObjectNode();
        concept.put("nodeKey", resourceKey + ".concept");
        concept.put("label", label);
        concept.put("description", description);
        items.add(new DomainCatalogItemResponse(
                UUID.randomUUID(),
                resourceKey + "@latest",
                "node",
                resourceKey + ".concept",
                resourceKey,
                "concept",
                null,
                null,
                concept));
        for (String field : fields) {
            ObjectNode fieldNode = objectMapper.createObjectNode();
            fieldNode.put("nodeKey", resourceKey + ".field." + field.toLowerCase().replace(" ", "-"));
            fieldNode.put("label", field);
            fieldNode.put("description", "Campo " + field);
            items.add(new DomainCatalogItemResponse(
                    UUID.randomUUID(),
                    resourceKey + "@latest",
                    "node",
                    resourceKey + ".field." + field.toLowerCase().replace(" ", "-"),
                    resourceKey,
                    "field",
                    null,
                    null,
                    fieldNode));
        }
        return new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                items);
    }

    private DomainCatalogContextResponse discoveryContext(List<String> resourceKeys) {
        List<DomainCatalogItemResponse> items = new java.util.ArrayList<>();
        for (String resourceKey : resourceKeys) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("resourceKey", resourceKey);
            node.put("nodeKey", resourceKey + ".concept");
            node.put("label", resourceKey.substring(resourceKey.lastIndexOf('.') + 1));
            items.add(new DomainCatalogItemResponse(
                    UUID.randomUUID(),
                    resourceKey + "@latest",
                    "node",
                    resourceKey + ".concept",
                    resourceKey,
                    "concept",
                    null,
                    null,
                    node));
        }
        return new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                items);
    }
}
