package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

@Tag("unit")
class AgenticAuthoringConsultativeApiCatalogProjectionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsDataProductRoleGlossaryFactsAndExpandsKnowledgeGraphLinks() {
        DomainCatalogIngestionService ingestionService = Mockito.mock(DomainCatalogIngestionService.class);
        ApiMetadataRepository apiMetadataRepository = Mockito.mock(ApiMetadataRepository.class);

        when(apiMetadataRepository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/ergon/funcionarios/filter",
                        "POST",
                        "ergon",
                        "Filtrar servidores",
                        "Consulta filtrada de servidores",
                        "filterFuncionarios",
                        "{}",
                        "{}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/ergon/funcionarios",
                        "POST",
                        "ergon",
                        "Cadastrar servidor",
                        "Cria novo servidor",
                        "createFuncionario",
                        "{}",
                        "{}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/ergon/radar-remuneracao-movimentacao/filter",
                        "POST",
                        "ergon",
                        "Filtrar radar remuneratorio",
                        "Filtra projeção analítica",
                        "filterRadarRemuneracao",
                        "{}",
                        "{}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/ergon/radar-remuneracao-movimentacao/stats/group-by",
                        "POST",
                        "ergon",
                        "Agrupar radar remuneratorio",
                        "Agrupa indicadores por situação",
                        "statsGroupByRadarRemuneracao",
                        "{}",
                        "{}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/ergon/radar-remuneracao-movimentacao/stats/distribution",
                        "POST",
                        "ergon",
                        "Distribuição remuneratória",
                        "Calcula distribuição salarial",
                        "statsDistributionRadarRemuneracao",
                        "{}",
                        "{}",
                        "[]",
                        "{}",
                        null)));

        DomainCatalogReleaseResponse release = new DomainCatalogReleaseResponse(
                UUID.randomUUID(),
                "praxis-ergon-showcase:administracao-pessoal:v1",
                "praxis.domain-catalog/v0.2",
                "praxis-ergon-showcase",
                "Praxis Ergon Showcase",
                "1.0.0",
                Instant.parse("2026-11-03T12:00:00Z"),
                "sha256:test",
                "default",
                "dev",
                Instant.parse("2026-11-03T12:00:01Z"));

        ObjectNode funcionarioConcept = objectMapper.createObjectNode();
        funcionarioConcept.put("nodeKey", "administracao-pessoal.funcionarios");
        funcionarioConcept.put("nodeType", "concept");
        funcionarioConcept.put("label", "Funcionarios");
        funcionarioConcept.put("description", "Cadastro funcional de servidores ativos e inativos.");
        funcionarioConcept.putObject("businessGlossary")
                .put("preferredTerm", "Cadastro de Servidores")
                .put("description", "Registro mestre de servidores públicos.")
                .putArray("synonyms")
                .add("Quadro Funcional");
        ObjectNode funcionarioMetadata = funcionarioConcept.putObject("metadata");
        funcionarioMetadata.put("resourceKey", "administracao-pessoal.funcionarios");
        funcionarioMetadata.put("resourcePath", "/api/ergon/funcionarios");
        funcionarioMetadata.putArray("analyzedByDataProducts")
                .add("administracao-pessoal.radar-remuneracao-movimentacao");

        ObjectNode funcionarioFact = objectMapper.createObjectNode();
        funcionarioFact.put("nodeKey", "administracao-pessoal.funcionarios.fact.tempoServicoAnos");
        funcionarioFact.put("nodeType", "fact");
        funcionarioFact.put("label", "Tempo de Serviço (Anos)");
        funcionarioFact.put("description", "Indicador determinístico de anos efetivos de exercício.");

        ObjectNode radarConcept = objectMapper.createObjectNode();
        radarConcept.put("nodeKey", "administracao-pessoal.radar-remuneracao-movimentacao");
        radarConcept.put("nodeType", "concept");
        radarConcept.put("label", "RadarRemuneracaoMovimentacao");
        radarConcept.put("description", "Cruzamento analítico de folha remuneratória e movimentações.");
        radarConcept.putObject("businessGlossary")
                .put("preferredTerm", "Radar de Remuneração vs. Movimentação")
                .put("description", "Produto de dados executivo de variação remuneratória.");
        ObjectNode radarMetadata = radarConcept.putObject("metadata");
        radarMetadata.put("resourceKey", "administracao-pessoal.radar-remuneracao-movimentacao");
        radarMetadata.put("resourcePath", "/api/ergon/radar-remuneracao-movimentacao");
        radarMetadata.put("conceptRole", "analytical_data_product");
        radarMetadata.putArray("analyzesConcepts").add("administracao-pessoal.funcionarios");
        radarMetadata.putObject("dataProduct")
                .put("role", "analytical_data_product")
                .putArray("analyzesConcepts")
                .add("administracao-pessoal.funcionarios");

        DomainCatalogContextResponse funcionarioContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                release,
                null,
                null,
                "administracao-pessoal",
                null,
                List.of(),
                List.of(
                        new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-ergon-showcase:administracao-pessoal:v1",
                                "node",
                                "administracao-pessoal.funcionarios",
                                "administracao-pessoal",
                                "concept",
                                null,
                                null,
                                funcionarioConcept),
                        new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-ergon-showcase:administracao-pessoal:v1",
                                "node",
                                "administracao-pessoal.funcionarios.fact.tempoServicoAnos",
                                "administracao-pessoal",
                                "fact",
                                null,
                                null,
                                funcionarioFact)));

        DomainCatalogContextResponse radarContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                release,
                null,
                null,
                "administracao-pessoal",
                null,
                List.of(),
                List.of(
                        new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-ergon-showcase:administracao-pessoal:v1",
                                "node",
                                "administracao-pessoal.radar-remuneracao-movimentacao",
                                "administracao-pessoal",
                                "concept",
                                null,
                                null,
                                radarConcept)));

        when(ingestionService.contextLatest(
                Mockito.eq("praxis-ergon-showcase"),
                Mockito.eq("ergon.funcionarios"),
                Mockito.eq("default"),
                Mockito.eq("dev"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.anyInt())).thenReturn(funcionarioContext);
        when(ingestionService.contextLatest(
                Mockito.eq("praxis-ergon-showcase"),
                Mockito.eq("administracao-pessoal.radar-remuneracao-movimentacao"),
                Mockito.eq("default"),
                Mockito.eq("dev"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.anyInt())).thenReturn(radarContext);

        AgenticAuthoringConsultativeApiCatalogProjectionService service =
                new AgenticAuthoringConsultativeApiCatalogProjectionService(
                        () -> ingestionService,
                        apiMetadataRepository,
                        "praxis-ergon-showcase");

        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/ergon/funcionarios",
                "post",
                "/schemas/filtered?path=/api/ergon/funcionarios/filter&operation=post&schemaType=response",
                "/api/ergon/funcionarios/filter",
                "post",
                0.85,
                "matched funcionarios",
                List.of(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));

        AgenticAuthoringConsultativeApiCatalogProjection projection = service.project(
                "Quais gráficos e dados existem para quadro funcional de servidores?",
                List.of(candidate),
                "default",
                "dev");

        assertThat(projection).isNotNull();
        assertThat(projection.resources())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Resource::resourceKey)
                .containsExactly(
                        "administracao-pessoal.funcionarios",
                        "administracao-pessoal.radar-remuneracao-movimentacao");

        AgenticAuthoringConsultativeApiCatalogProjection.Resource operational = projection.resources().get(0);
        assertThat(operational.label()).isEqualTo("Cadastro de Servidores");
        assertThat(operational.role()).isEqualTo("operational");
        assertThat(operational.resourcePath()).isEqualTo("/api/ergon/funcionarios");
        assertThat(operational.fields())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Field::label)
                .contains("Tempo de Serviço (Anos)");
        assertThat(operational.evidence())
                .contains("analyzed_by:administracao-pessoal.radar-remuneracao-movimentacao");

        AgenticAuthoringConsultativeApiCatalogProjection.Resource dataProduct = projection.resources().get(1);
        assertThat(dataProduct.label()).isEqualTo("Radar de Remuneração vs. Movimentação");
        assertThat(dataProduct.role()).isEqualTo("analytical");
        assertThat(dataProduct.resourcePath()).isEqualTo("/api/ergon/radar-remuneracao-movimentacao");
        assertThat(dataProduct.evidence())
                .contains("analytical_data_product", "analyzes:administracao-pessoal.funcionarios");
        assertThat(dataProduct.endpoints())
                .extracting(AgenticAuthoringConsultativeApiCatalogProjection.Endpoint::kind)
                .contains("groupByStats", "distributionStats");
        assertThat(projection.assistantMessage())
                .contains("fontes analíticas confirmadas");
    }
}
