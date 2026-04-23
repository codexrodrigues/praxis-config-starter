package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;

@Tag("unit")
class DomainFederationQueryServiceTest {

    @Test
    void buildsFederatedContextFromCatalogProjection() {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        DomainFederationQueryService service = new DomainFederationQueryService(catalogService);
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
        List<DomainCatalogItemResponse> relationships = List.of(new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "domain-catalog:operations:v1",
                "edge",
                "operations.mission.depends_on.assets.vehicle",
                "operations",
                null,
                null,
                "depends_on",
                null));
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
        assertThat(response.context()).isSameAs(catalogContext);
        assertThat(response.relationships()).containsExactlyElementsOf(relationships);
        assertThat(response.retrievalGuidance())
                .contains("Prefer explicit context keys.")
                .contains("Federated context is currently projected from domain catalog releases and edge rows.");
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
        DomainFederationQueryService service = new DomainFederationQueryService(catalogService);
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
