package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@Tag("unit")
class AgenticAuthoringResourceDiscoveryServiceTest {

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
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository));

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
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void searchRequiresQueryBeforeCallingCatalog() {
        AgenticAuthoringResourceDiscoveryService service =
                new AgenticAuthoringResourceDiscoveryService(null);

        AgenticAuthoringResourceCandidatesResult result = service.search(
                new AgenticAuthoringResourceCandidatesRequest(null, "  ", null, null));

        assertThat(result.valid()).isFalse();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.warnings()).containsExactly("resource-discovery-query-required");
    }
}
