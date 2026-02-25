package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class RegistryIngestionServiceIdentityTest {

    @Mock
    private AiRegistryRepository repository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private RagVectorStoreService ragVectorStoreService;

    private RegistryIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RegistryIngestionService(repository, new ObjectMapper(), embeddingService, ragVectorStoreService);
    }

    @Test
    void shouldBuildReleaseScopedDocumentIdAndMetadata() {
        RegistryIngestionRequest.IoEntry input = RegistryIngestionRequest.IoEntry.builder()
                .name("value")
                .type("string")
                .required(true)
                .build();
        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Table component")
                .inputs(List.of(input))
                .outputs(List.of())
                .build();
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("registry-v2")
                .generatedAt("2026-02-22T12:00:00Z")
                .components(Map.of("table", component))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestRegistry(request, "tenant-a", "prod");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        Document document = captor.getValue().get(0);

        assertThat(document.getId()).startsWith("tenant-a/prod/table/registry-v2/component_definition/");
        assertThat(document.getId()).endsWith("/0");

        Object contentHash = document.getMetadata().get(RagMetadataKeys.CONTENT_HASH);
        assertThat(contentHash).isInstanceOf(String.class);
        assertThat(((String) contentHash)).hasSize(64);
        assertThat(document.getId()).contains("/" + contentHash + "/0");

        assertThat(document.getMetadata().get(RagMetadataKeys.RELEASE_ID)).isEqualTo("registry-v2");
        assertThat(document.getMetadata().get(RagMetadataKeys.COMPONENT_ID)).isEqualTo("table");
        assertThat(document.getMetadata().get(RagMetadataKeys.DOC_TYPE)).isEqualTo("component_definition");
        assertThat(document.getMetadata().get(RagMetadataKeys.CHUNK_INDEX)).isEqualTo(0);
        assertThat(document.getMetadata().get(RagMetadataKeys.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(document.getMetadata().get(RagMetadataKeys.ENVIRONMENT)).isEqualTo("prod");
        assertThat(document.getMetadata().get(RagMetadataKeys.VERSION)).isEqualTo("registry-v2");
    }
}
