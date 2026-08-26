package org.praxisplatform.config.controller;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.ApiMetadataRagReconcileResponse;
import org.praxisplatform.config.dto.ApiMetadataRagStatusResponse;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.service.ApiMetadataIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Tag("unit")
class ApiMetadataControllerTest {

    private final ApiMetadataIngestionService ingestionService = mock(ApiMetadataIngestionService.class);
    private final ApiMetadataController controller = new ApiMetadataController(ingestionService);

    @Test
    void shouldExposeApiMetadataRagStatus() {
        ApiMetadataRagStatusResponse status = statusResponse(true);
        when(ingestionService.ragStatus("tenant-a", "prod", "default", "release-1"))
                .thenReturn(status);

        ResponseEntity<ApiMetadataRagStatusResponse> response =
                controller.ragStatus("default", "release-1", "tenant-a", "prod");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(status);
        verify(ingestionService).ragStatus("tenant-a", "prod", "default", "release-1");
    }

    @Test
    void shouldAcceptApiMetadataRagReconciliation() {
        ApiMetadataRagStatusResponse status = statusResponse(true);
        ApiMetadataRagReconcileResponse reconcile = new ApiMetadataRagReconcileResponse(
                "praxis.api-metadata-rag-reconcile/v0.2",
                "tenant-a",
                "prod",
                "default",
                "release-1",
                true,
                true,
                1,
                1,
                status);
        when(ingestionService.reconcileRag("tenant-a", "prod", "default", "release-1"))
                .thenReturn(reconcile);

        ResponseEntity<ApiMetadataRagReconcileResponse> response =
                controller.reconcileRag("default", "release-1", "tenant-a", "prod");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(reconcile);
        verify(ingestionService).reconcileRag("tenant-a", "prod", "default", "release-1");
    }

    private ApiMetadataRagStatusResponse statusResponse(boolean reconciled) {
        return new ApiMetadataRagStatusResponse(
                "praxis.api-metadata-rag-status/v0.2",
                "tenant-a",
                "prod",
                "default",
                "release-1",
                RagResourceTypes.API_METADATA,
                true,
                true,
                true,
                reconciled,
                reconciled ? "READY" : "PENDING",
                1L,
                1,
                1L,
                1L,
                1L,
                1L,
                1,
                Map.of("summary", 1L),
                Map.of("allow", 1L),
                List.of(),
                "2026-07-11T01:00:00Z",
                null,
                null,
                "2026-07-11T00:00:00Z",
                "2026-07-11T00:00:01Z",
                "2026-07-11T01:00:00Z",
                "2026-07-11T01:00:00Z",
                List.of());
    }
}
