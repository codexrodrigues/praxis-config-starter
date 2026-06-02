package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.Domain360CatalogResponse;
import org.praxisplatform.config.dto.Domain360CatalogResponse.Domain360Coverage;
import org.praxisplatform.config.service.Domain360CatalogService;

@Tag("unit")
class Domain360CatalogControllerTest {

    @Test
    void delegatesCatalogQueryToServiceWithHeaders() {
        Domain360CatalogService service = mock(Domain360CatalogService.class);
        Domain360CatalogController controller = new Domain360CatalogController(service);
        Domain360CatalogResponse response = new Domain360CatalogResponse(
                "praxis.domain-360-catalog/v0.1",
                "tenant-a",
                "dev",
                "praxis-service",
                "human-resources.funcionarios",
                "funcionarios",
                "catalog_projection_fallback",
                null,
                List.of(),
                new Domain360Coverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(service.catalog(
                "praxis-service",
                "human-resources.funcionarios",
                "tenant-a",
                "dev",
                "human-resources",
                "funcionarios",
                75))
                .thenReturn(response);

        var entity = controller.catalog(
                "praxis-service",
                "human-resources.funcionarios",
                "human-resources",
                "funcionarios",
                75,
                "tenant-a",
                "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).catalog(
                "praxis-service",
                "human-resources.funcionarios",
                "tenant-a",
                "dev",
                "human-resources",
                "funcionarios",
                75);
    }
}
