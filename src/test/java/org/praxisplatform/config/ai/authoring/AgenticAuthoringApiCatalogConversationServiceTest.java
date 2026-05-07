package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@Tag("unit")
class AgenticAuthoringApiCatalogConversationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void answersBusinessAnalyticsCatalogDiscoveryWithoutLeakingEndpointSyntax() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                metadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "analytics,human-resources,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha por departamento e competencia"),
                metadata(
                        "/api/operations/incidentes",
                        "operations,incidentes",
                        "Incidentes operacionais",
                        "Incidentes por status, base e severidade"),
                metadata(
                        "/api/assets/veiculos",
                        "assets,veiculos",
                        "Veiculos",
                        "Ativos de frota por disponibilidade"),
                metadata(
                        "/api/procurement/contracts",
                        "procurement,contracts",
                        "Contratos",
                        "Contratos e fornecedores por status"),
                metadata(
                        "/api/risk-intelligence/ameacas",
                        "risk,ameacas",
                        "Ameacas",
                        "Ameacas e riscos por severidade")));
        AgenticAuthoringApiCatalogConversationService service =
                new AgenticAuthoringApiCatalogConversationService(objectMapper, repository);

        AgenticAuthoringApiCatalogConversationService.ApiCatalogConversationAnswer answer = service.answer(
                "Sou gestor de negocio e quero descobrir o catalogo: quais recursos de dados existem para graficos e indicadores antes de criar dashboard?",
                null,
                List.of(candidate("/api/human-resources/vw-analytics-folha-pagamento")));

        assertThat(answer.assistantMessage())
                .contains("Pessoas e folha")
                .contains("Operacoes")
                .contains("Ativos")
                .contains("Compras e contratos")
                .contains("Riscos")
                .contains("Indicadores:")
                .contains("Graficos iniciais:")
                .doesNotContain("/api/");
        JsonNode structured = answer.apiCatalogAnswer();
        assertThat(structured.path("questionType").asText()).isEqualTo("business_analytics_catalog");
        assertThat(structured.path("businessAreas"))
                .extracting(area -> area.path("area").asText())
                .containsExactly(
                        "Pessoas e folha",
                        "Operacoes",
                        "Ativos",
                        "Compras e contratos",
                        "Riscos");
        assertThat(structured.path("businessAreas").get(0).path("canonicalResourcePaths"))
                .extracting(JsonNode::asText)
                .contains("/api/human-resources/vw-analytics-folha-pagamento");
    }

    private AgenticAuthoringCandidate candidate(String resourcePath) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "GET",
                resourcePath + "/schemas",
                resourcePath,
                "GET",
                0.92d,
                "semantic-match",
                List.of());
    }

    private ApiMetadata metadata(String path, String tags, String summary, String description) {
        return new ApiMetadata(
                path,
                "GET",
                tags,
                summary,
                description,
                "operation",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null);
    }
}
