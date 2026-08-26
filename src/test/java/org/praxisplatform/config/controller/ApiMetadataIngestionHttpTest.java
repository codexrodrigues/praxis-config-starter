package org.praxisplatform.config.controller;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.ApiMetadataIndexingCoordinator;
import org.praxisplatform.config.service.ApiMetadataIndexingScope;
import org.praxisplatform.config.service.ApiMetadataIndexingStateService;
import org.praxisplatform.config.service.ApiMetadataIngestionService;
import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class ApiMetadataIngestionHttpTest {

    @Test
    void shouldReturnAcceptedWithoutWaitingForABlockedEmbeddingProvider() {
        ApiMetadataRepository repository = mock(ApiMetadataRepository.class);
        EmbeddingService blockedEmbeddingProvider = mock(EmbeddingService.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        ApiMetadataIndexingStateService stateService = mock(ApiMetadataIndexingStateService.class);
        ApiMetadataIndexingCoordinator coordinator = mock(ApiMetadataIndexingCoordinator.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiMetadataIngestionService ingestionService = new ApiMetadataIngestionService(
                repository,
                objectMapper,
                blockedEmbeddingProvider,
                ragVectorStoreService,
                stateService,
                coordinator);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ApiMetadataController(ingestionService))
                .build();
        ApiMetadataIndexingScope scope =
                new ApiMetadataIndexingScope("tenant-a", "prod", "default", "release-1");
        doAnswer(invocation -> {
            Thread.sleep(5_000L);
            return java.util.List.of(0.1f);
        }).when(blockedEmbeddingProvider).embed(anyString());
        doAnswer(invocation -> {
            Thread.sleep(5_000L);
            return java.util.List.of(java.util.List.of(0.1f));
        }).when(blockedEmbeddingProvider).embedAll(any());
        when(stateService.request(scope)).thenReturn(1L);
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/users", "GET"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> mockMvc.perform(post(
                        "/api/praxis/config/api-catalog/ingest")
                        .header("X-Tenant-ID", "tenant-a")
                        .header("X-Env", "prod")
                        .contentType("application/json")
                        .content("""
                                {
                                  "releaseId": "release-1",
                                  "endpoints": [{
                                    "path": "/api/users",
                                    "method": "GET",
                                    "summary": "List users"
                                  }]
                                }
                                """))
                .andExpect(status().isAccepted()));

        verify(blockedEmbeddingProvider, never()).embed(anyString());
        verify(blockedEmbeddingProvider, never()).embedAll(any());
        verify(coordinator).scheduleAfterCommit(scope);
    }
}
