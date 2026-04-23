package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;

@Tag("unit")
class DomainFederationQueryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsFederatedContextFromCatalogProjection() throws Exception {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        DomainFederationQueryService service = new DomainFederationQueryService(
                catalogService,
                new DomainFederationRetrievalPolicyService());
        DomainCatalogContextResponse catalogContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                "veiculo",
                "node",
                "operations",
                null,
                List.of("Prefer explicit context keys."),
                List.of(new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "node",
                        "operations.mission",
                        "operations",
                        "aggregate",
                        null,
                        null,
                        null)));
        List<DomainCatalogItemResponse> relationships = List.of(
                new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "edge",
                        "operations.mission.depends_on.assets.vehicle",
                        "operations",
                        null,
                        null,
                        "depends_on",
                        objectMapper.readTree("{\"confidence\":0.64}")),
                new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "edge",
                        "operations.mission.depends_on.security.token",
                        "operations",
                        null,
                        null,
                        "depends_on",
                        objectMapper.readTree("{\"aiUsage\":{\"visibility\":\"deny\"}}")));
        when(catalogService.contextLatest(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "veiculo",
                20)).thenReturn(catalogContext);
        when(catalogService.relationshipsLatest(
                null,
                null,
                "tenant-a",
                "dev",
                null,
                null,
                "depends_on",
                "veiculo",
                20)).thenReturn(relationships);

        var response = service.context(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20);

        assertThat(response.federated()).isTrue();
        assertThat(response.context().items()).containsExactlyElementsOf(catalogContext.items());
        assertThat(response.relationships()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("operations.mission.depends_on.assets.vehicle");
        assertThat(response.policyReport().inputItemCount()).isEqualTo(3);
        assertThat(response.policyReport().returnedItemCount()).isEqualTo(2);
        assertThat(response.policyReport().deniedItemCount()).isEqualTo(1);
        assertThat(response.policyReport().lowConfidenceItemCount()).isEqualTo(1);
        assertThat(response.retrievalGuidance())
                .contains("Prefer explicit context keys.")
                .contains("Federated context is currently projected from domain catalog releases and edge rows.")
                .anySatisfy(guidance -> assertThat(guidance).contains("Excluded operations.mission.depends_on.security.token"))
                .anySatisfy(guidance -> assertThat(guidance).contains("Flagged operations.mission.depends_on.assets.vehicle"));
        verify(catalogService).contextLatest(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "veiculo",
                20);
        verify(catalogService).relationshipsLatest(
                null,
                null,
                "tenant-a",
                "dev",
                null,
                null,
                "depends_on",
                "veiculo",
                20);
    }

    @Test
    void clampsInvalidLimitAndPreservesScopedQuery() {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        DomainFederationQueryService service = new DomainFederationQueryService(
                catalogService,
                new DomainFederationRetrievalPolicyService());
        DomainCatalogContextResponse catalogContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                "employee",
                "node",
                "human-resources",
                "entity",
                List.of(),
                List.of());
        when(catalogService.contextLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                "employee",
                20)).thenReturn(catalogContext);
        when(catalogService.relationshipsLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                null,
                null,
                null,
                "employee",
                20)).thenReturn(List.of());

        var response = service.context(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                null,
                "employee",
                -1);

        assertThat(response.federated()).isFalse();
        assertThat(response.limit()).isEqualTo(20);
        verify(catalogService).contextLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                "employee",
                20);
    }
}
