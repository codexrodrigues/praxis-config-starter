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
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

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
                "serviceKey", "workforce-host",
                "resourceKey", "administracao-pessoal.radar-ferias-prescricao"));
        DomainCatalogLocalSourceProvider localSource = () -> List.of(catalogDoc);
        when(provider.iterator()).thenAnswer(invocation -> Stream.of(localSource).iterator());

        DomainCatalogIngestionResponse expectedResponse = new DomainCatalogIngestionResponse(
                UUID.randomUUID(),
                "workforce-host:administracao-pessoal.radar-ferias-prescricao:hash",
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

    @Test
    void continuesSynchronizingRemainingDocumentsWhenOneFails() {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainCatalogLocalSourceProvider> provider = mock(ObjectProvider.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);

        JsonNode badDoc = objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis.domain-catalog/v0.2",
                "serviceKey", "workforce-host",
                "resourceKey", "workforce.broken"));
        JsonNode goodDoc = objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis.domain-catalog/v0.2",
                "serviceKey", "workforce-host",
                "resourceKey", "workforce.healthy"));
        DomainCatalogLocalSourceProvider localSource = () -> List.of(badDoc, goodDoc);
        when(provider.iterator()).thenAnswer(invocation -> Stream.of(localSource).iterator());

        DomainCatalogIngestionResponse healthyResponse = new DomainCatalogIngestionResponse(
                UUID.randomUUID(),
                "workforce-host:workforce.healthy:hash",
                5);
        when(ingestionService.ingest(eq(badDoc), eq("default"), eq("dev")))
                .thenThrow(new IllegalArgumentException("invalid document"));
        when(ingestionService.ingest(eq(goodDoc), eq("default"), eq("dev")))
                .thenReturn(healthyResponse);

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

        assertThat(synced).containsExactly(healthyResponse);
    }

    @Test
    void bindsIndexedYamlGroupPropertiesViaSpringBootBinderWhenDirectListIsEmpty() {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DomainCatalogLocalSourceProvider> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> Stream.empty().iterator());

        MockEnvironment environment = new MockEnvironment()
                .withProperty("praxis.domain-catalog.auto-sync.groups[0]", "workforce")
                .withProperty("praxis.domain-catalog.auto-sync.groups[1]", "payroll");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.setEnvironment(environment);
            context.refresh();

            DomainCatalogAutoSyncService service = new DomainCatalogAutoSyncService(
                    ingestionService,
                    provider,
                    context,
                    objectMapper,
                    true,
                    "rio",
                    "local",
                    List.of(),
                    List.of());

            @SuppressWarnings("unchecked")
            List<String> boundGroups = (List<String>) org.springframework.test.util.ReflectionTestUtils
                    .getField(service, "configuredGroups");
            assertThat(boundGroups).containsExactly("workforce", "payroll");
        }
    }
}
