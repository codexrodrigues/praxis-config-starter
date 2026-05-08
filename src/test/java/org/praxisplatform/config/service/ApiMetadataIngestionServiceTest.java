package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.ApiCatalogRequest;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ApiMetadataIngestionServiceTest {

    @Mock
    private ApiMetadataRepository repository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private RagVectorStoreService ragVectorStoreService;

    private ApiMetadataIngestionService service;

    @BeforeEach
    void setUp() {
        service = new ApiMetadataIngestionService(
                repository,
                new ObjectMapper(),
                embeddingService,
                ragVectorStoreService);
    }

    @Test
    void shouldDropNullMetadataEntriesBeforeVectorStoreUpsert() {
        ObjectNode requestSchema = new ObjectMapper().createObjectNode().put("name", "DemoRequest");
        ApiCatalogRequest.ApiEndpointEntry endpoint = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/demo")
                .method("GET")
                .summary(null)
                .description(null)
                .operationId(null)
                .tags(null)
                .requestSchema(requestSchema)
                .responseSchema(null)
                .parameters(null)
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .endpoints(List.of(endpoint))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByPathAndMethod("/api/demo", "GET")).thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, null, null);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(1);
        Document document = documents.get(0);
        assertThat(document.getMetadata()).isNotNull();
        assertThat(document.getMetadata().values())
                .allMatch(value -> !Objects.isNull(value));
    }

    @Test
    void shouldBuildReleaseScopedDeterministicDocumentIdentity() {
        ObjectNode requestSchema = new ObjectMapper().createObjectNode().put("name", "DemoRequest");
        ApiCatalogRequest.ApiEndpointEntry endpoint = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/v1/users")
                .method("GET")
                .summary("List users")
                .description("Returns users")
                .operationId("listUsers")
                .tags(List.of("users"))
                .requestSchema(requestSchema)
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .releaseId("release-2026-02")
                .version("2026.02")
                .generatedAt("2026-02-22T12:00:00Z")
                .endpoints(List.of(endpoint))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByPathAndMethod("/v1/users", "GET")).thenReturn(Optional.empty());
        when(repository.findAllByOperationIdAndMethod("listUsers", "GET")).thenReturn(List.of());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        Document document = captor.getValue().get(0);

        assertThat(document.getId()).startsWith("tenant-a/prod/get_v1_users/release-2026-02/api_metadata/");
        assertThat(document.getId()).endsWith("/0");

        Object contentHash = document.getMetadata().get(RagMetadataKeys.CONTENT_HASH);
        assertThat(contentHash).isInstanceOf(String.class);
        assertThat(((String) contentHash)).hasSize(64);
        assertThat(document.getId()).contains("/" + contentHash + "/0");

        assertThat(document.getMetadata().get(RagMetadataKeys.RELEASE_ID)).isEqualTo("release-2026-02");
        assertThat(document.getMetadata().get(RagMetadataKeys.COMPONENT_ID)).isEqualTo("GET:/v1/users");
        assertThat(document.getMetadata().get(RagMetadataKeys.DOC_TYPE)).isEqualTo(RagResourceTypes.API_METADATA);
        assertThat(document.getMetadata().get(RagMetadataKeys.CHUNK_INDEX)).isEqualTo(0);
        assertThat(document.getMetadata().get(RagMetadataKeys.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(document.getMetadata().get(RagMetadataKeys.ENVIRONMENT)).isEqualTo("prod");
        assertThat(document.getMetadata().get(RagMetadataKeys.VERSION)).isEqualTo("2026.02");
    }

    @Test
    void shouldReconcileMovedEndpointByStableOperationIdentity() {
        ApiMetadata stale = new ApiMetadata();
        stale.setPath("/api/human-resources/missoes/filter");
        stale.setMethod("POST");
        stale.setOperationId("filterMissoes");
        ApiCatalogRequest.ApiEndpointEntry endpoint = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/operations/missoes/filter")
                .method("POST")
                .summary("Filtrar missoes")
                .description("Consulta missoes operacionais")
                .operationId("filterMissoes")
                .tags(List.of("operations", "missoes"))
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .endpoints(List.of(endpoint))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByPathAndMethod("/api/operations/missoes/filter", "POST")).thenReturn(Optional.empty());
        when(repository.findAllByOperationIdAndMethod("filterMissoes", "POST")).thenReturn(List.of(stale));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, "demo", "dev");

        ArgumentCaptor<ApiMetadata> captor = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(captor.capture());
        ApiMetadata saved = captor.getValue();
        assertThat(saved).isSameAs(stale);
        assertThat(saved.getPath()).isEqualTo("/api/operations/missoes/filter");
        assertThat(saved.getMethod()).isEqualTo("POST");
        assertThat(saved.getOperationId()).isEqualTo("filterMissoes");
    }
}
