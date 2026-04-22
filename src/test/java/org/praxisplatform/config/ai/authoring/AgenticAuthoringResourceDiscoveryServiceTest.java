package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

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
        assertThat(result.assistantMessage()).contains("Encontrei APIs");
        assertThat(result.quickReplies()).hasSize(1);
        assertThat(result.quickReplies().get(0).id())
                .isEqualTo("resource-api-human-resources-vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).label()).isEqualTo("analytics folha pagamento");
        assertThat(result.quickReplies().get(0).prompt())
                .isEqualTo("Usar /api/human-resources/vw-analytics-folha-pagamento como fonte de dados.");
        assertThat(result.quickReplies().get(0).description())
                .isEqualTo("GET /api/human-resources/vw-analytics-folha-pagamento/all");
        assertThat(result.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("artifactKind").asText())
                .isEqualTo("dashboard");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("schemaVersion").asText())
                .isEqualTo("praxis.ai.context-hints.domain-catalog/v0.1");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("serviceKey").asText())
                .isEqualTo("praxis-service");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("contextKey").asText())
                .isEqualTo("human-resources");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("query").asText())
                .contains("folha de pagamento")
                .contains("analytics folha pagamento");
        assertThat(result.quickReplies().get(0).contextHints().path("domainCatalog").path("type").asText())
                .isEqualTo("node");
        assertThat(result.warnings()).isEmpty();
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
