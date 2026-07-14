package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class DomainCatalogControllerTest {

    @Mock
    private DomainCatalogIngestionService domainCatalogIngestionService;

    @InjectMocks
    private DomainCatalogController controller;

    @Test
    void releasesForwardsResourceScopeBeforePagination() {
        List<DomainCatalogReleaseResponse> releases = List.of();
        when(domainCatalogIngestionService.releases(
                "praxis-service",
                "operations.missoes",
                "tenant-a",
                "dev",
                5)).thenReturn(releases);

        var response = controller.releases(
                "praxis-service",
                "operations.missoes",
                "tenant-a",
                "dev",
                5);

        assertThat(response.getBody()).isSameAs(releases);
        verify(domainCatalogIngestionService).releases(
                "praxis-service",
                "operations.missoes",
                "tenant-a",
                "dev",
                5);
    }

    @Test
    void itemsForwardsTenantAndEnvironmentScope() {
        List<DomainCatalogItemResponse> items = List.of();
        when(domainCatalogIngestionService.search(
                "release-a",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "field",
                "salario",
                10)).thenReturn(items);

        var response = controller.items(
                "release-a",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "field",
                "salario",
                10);

        assertThat(response.getBody()).isSameAs(items);
        verify(domainCatalogIngestionService).search(
                "release-a",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "field",
                "salario",
                10);
    }
}
