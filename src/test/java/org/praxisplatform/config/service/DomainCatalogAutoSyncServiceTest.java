package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

@Tag("unit")
class DomainCatalogAutoSyncServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void synchronizesDocumentsFromRegisteredLocalSourceProviders() {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainCatalogLocalSourceProvider> provider = mock(ObjectProvider.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);

        JsonNode catalogDoc = objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis.domain-catalog/v0.2",
                "serviceKey", "ergon-host",
                "resourceKey", "administracao-pessoal.radar-ferias-prescricao"));
        DomainCatalogLocalSourceProvider localSource = () -> List.of(catalogDoc);
        when(provider.iterator()).thenAnswer(invocation -> Stream.of(localSource).iterator());

        DomainCatalogIngestionResponse expectedResponse = new DomainCatalogIngestionResponse(
                UUID.randomUUID(),
                "ergon-host:administracao-pessoal.radar-ferias-prescricao:hash",
                12);
        when(ingestionService.ingest(any(JsonNode.class), eq("default"), eq("dev")))
                .thenReturn(expectedResponse);

        DomainCatalogAutoSyncService service = new DomainCatalogAutoSyncService(
                ingestionService,
                provider,
                applicationContext,
                objectMapper,
                true,
                "default",
                "dev",
                List.of(),
                List.of());

        List<DomainCatalogIngestionResponse> synced = service.synchronizeNow("default", "dev");

        assertThat(synced).containsExactly(expectedResponse);
        verify(ingestionService).ingest(eq(catalogDoc), eq("default"), eq("dev"));
    }
}
