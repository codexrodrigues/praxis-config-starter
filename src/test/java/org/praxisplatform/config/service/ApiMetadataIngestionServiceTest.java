package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.praxisplatform.config.dto.ApiMetadataRagReconcileResponse;
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
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "GLOBAL", "default", "default", "v1", "/api/demo", "GET"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "GLOBAL", "default", "default", "v1"))
                .thenAnswer(invocation -> List.of(savedMetadataFromRepositorySave()));

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
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-2026-02", "/v1/users", "GET"))
                .thenReturn(Optional.empty());
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndOperationIdAndMethod(
                "tenant-a", "prod", "default", "release-2026-02", "listUsers", "GET"))
                .thenReturn(List.of());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-2026-02"))
                .thenAnswer(invocation -> List.of(savedMetadataFromRepositorySave()));

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<ApiMetadata> metadataCaptor = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(metadataCaptor.capture());
        ApiMetadata savedMetadata = metadataCaptor.getValue();
        assertThat(savedMetadata.getTenantId()).isEqualTo("tenant-a");
        assertThat(savedMetadata.getEnvironment()).isEqualTo("prod");
        assertThat(savedMetadata.getServiceKey()).isEqualTo("default");
        assertThat(savedMetadata.getReleaseId()).isEqualTo("release-2026-02");
        assertThat(savedMetadata.getReleaseVersion()).isEqualTo("2026.02");
        assertThat(savedMetadata.getGeneratedAt()).isEqualTo("2026-02-22T12:00:00Z");

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
        assertThat(document.getMetadata().get(RagMetadataKeys.TAGS)).isEqualTo("users");
        assertThat(document.getMetadata().get(RagMetadataKeys.PUBLISHED_AT)).isInstanceOf(String.class);
    }

    @Test
    void shouldReconcileMovedEndpointByStableOperationIdentity() {
        ApiMetadata stale = new ApiMetadata();
        stale.setTenantId("demo");
        stale.setEnvironment("dev");
        stale.setServiceKey("default");
        stale.setReleaseId("v1");
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
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "demo", "dev", "default", "v1", "/api/operations/missoes/filter", "POST"))
                .thenReturn(Optional.empty());
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndOperationIdAndMethod(
                "demo", "dev", "default", "v1", "filterMissoes", "POST"))
                .thenReturn(List.of(stale));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, "demo", "dev");

        ArgumentCaptor<ApiMetadata> captor = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(captor.capture());
        ApiMetadata saved = captor.getValue();
        assertThat(saved).isSameAs(stale);
        assertThat(saved.getPath()).isEqualTo("/api/operations/missoes/filter");
        assertThat(saved.getMethod()).isEqualTo("POST");
        assertThat(saved.getOperationId()).isEqualTo("filterMissoes");
        assertThat(saved.getTenantId()).isEqualTo("demo");
        assertThat(saved.getEnvironment()).isEqualTo("dev");
    }

    @Test
    void shouldNotReconcileOperationIdentityAcrossTenantOrReleaseScope() {
        ApiMetadata otherScope = new ApiMetadata();
        otherScope.setTenantId("tenant-b");
        otherScope.setEnvironment("prod");
        otherScope.setServiceKey("default");
        otherScope.setReleaseId("release-old");
        otherScope.setPath("/api/legacy/users/filter");
        otherScope.setMethod("POST");
        otherScope.setOperationId("filterUsers");
        ApiCatalogRequest.ApiEndpointEntry endpoint = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/current/users/filter")
                .method("POST")
                .summary("Filtrar usuarios")
                .operationId("filterUsers")
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .releaseId("release-new")
                .endpoints(List.of(endpoint))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.3f, 0.4f));
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-new", "/api/current/users/filter", "POST"))
                .thenReturn(Optional.empty());
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndOperationIdAndMethod(
                "tenant-a", "prod", "default", "release-new", "filterUsers", "POST"))
                .thenReturn(List.of());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<ApiMetadata> captor = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(captor.capture());
        ApiMetadata saved = captor.getValue();
        assertThat(saved).isNotSameAs(otherScope);
        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getReleaseId()).isEqualTo("release-new");
        assertThat(saved.getPath()).isEqualTo("/api/current/users/filter");
    }

    @Test
    void shouldNotPublishRagDocumentsWhenBatchFailsBeforeCommitBoundary() {
        ApiCatalogRequest.ApiEndpointEntry first = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/ok")
                .method("GET")
                .build();
        ApiCatalogRequest.ApiEndpointEntry second = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/fail")
                .method("GET")
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .endpoints(List.of(first, second))
                .build();

        when(embeddingService.embed(anyString()))
                .thenReturn(List.of(0.1f, 0.2f))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        assertThatThrownBy(() -> service.ingestCatalog(request, null, null))
                .isInstanceOf(org.praxisplatform.config.exception.ConfigurationIngestionException.class)
                .hasMessageContaining("Error ingesting endpoint: GET /api/fail");

        verify(ragVectorStoreService, never()).deleteDocumentsByRelease(
                anyString(),
                anyString(),
                anyString(),
                anyString());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void shouldEmbedMultiEndpointCatalogAsOneProviderBatch() {
        ApiCatalogRequest.ApiEndpointEntry first = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/users")
                .method("GET")
                .build();
        ApiCatalogRequest.ApiEndpointEntry second = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/teams")
                .method("GET")
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .endpoints(List.of(first, second))
                .build();

        when(embeddingService.embedAll(any()))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.3f, 0.4f)));
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestCatalog(request, null, null);

        verify(embeddingService).embedAll(any());
        verify(embeddingService, never()).embed(anyString());
        verify(repository, times(2)).save(any(ApiMetadata.class));
    }

    @Test
    void shouldKeepCanonicalIngestionWhenDerivedRagPublicationFailsAfterPersistence() {
        ApiCatalogRequest.ApiEndpointEntry endpoint = ApiCatalogRequest.ApiEndpointEntry.builder()
                .path("/api/demo")
                .method("GET")
                .build();
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .endpoints(List.of(endpoint))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "GLOBAL", "default", "default", "v1", "/api/demo", "GET"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "GLOBAL", "default", "default", "v1"))
                .thenAnswer(invocation -> List.of(savedMetadataFromRepositorySave()));
        org.mockito.Mockito.doThrow(new IllegalStateException("vector down"))
                .when(ragVectorStoreService)
                .upsertDocuments(any());

        service.ingestCatalog(request, null, null);

        verify(repository).save(any(ApiMetadata.class));
        verify(ragVectorStoreService).deleteDocumentsByRelease(
                "GLOBAL",
                "default",
                "v1",
                RagResourceTypes.API_METADATA);
        verify(ragVectorStoreService).upsertDocuments(any());
    }

    @Test
    void shouldReplayCanonicalApiMetadataIntoRagDuringManualReconciliation() {
        ApiMetadata metadata = new ApiMetadata();
        metadata.setTenantId("tenant-a");
        metadata.setEnvironment("prod");
        metadata.setServiceKey("default");
        metadata.setReleaseId("release-1");
        metadata.setReleaseVersion("2026.02");
        metadata.setPath("/api/users");
        metadata.setMethod("GET");
        metadata.setSummary("List users");
        metadata.setTags("users");
        metadata.setRawJson("{\"path\":\"/api/users\",\"method\":\"GET\",\"summary\":\"List users\",\"tags\":[\"users\"]}");

        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(List.of(metadata));
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(1L);
        when(ragVectorStoreService.corpusReleaseStatus(
                "tenant-a",
                "prod",
                "release-1",
                RagResourceTypes.API_METADATA,
                1L))
                .thenReturn(new RagVectorStoreService.RagCorpusReleaseStatus(
                        true,
                        true,
                        "tenant-a",
                        "prod",
                        "release-1",
                        1,
                        1,
                        1,
                        java.util.Map.of("summary", 1L),
                        java.util.Map.of("allow", 1L),
                        List.of(new RagVectorStoreService.SourceStatus(
                                "GET:/api/users",
                                RagResourceTypes.API_METADATA,
                                1,
                                List.of("summary"),
                                List.of("2026.02"),
                                "2026-07-11T01:00:00Z")),
                        "2026-07-11T01:00:00Z",
                        List.of()));

        ApiMetadataRagReconcileResponse response =
                service.reconcileRag("tenant-a", "prod", "default", "release-1");

        assertThat(response.publishedDocumentCount()).isEqualTo(1);
        assertThat(response.status().reconciled()).isTrue();
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).deleteDocumentsByRelease(
                "tenant-a",
                "prod",
                "release-1",
                RagResourceTypes.API_METADATA);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getId())
                .startsWith("tenant-a/prod/get_api_users/release-1/api_metadata/");
        assertThat(captor.getValue().get(0).getMetadata())
                .containsEntry(RagMetadataKeys.SOURCE_ID, "GET:/api/users")
                .containsEntry(RagMetadataKeys.CHUNK_KIND, "summary");
    }

    private ApiMetadata savedMetadataFromRepositorySave() {
        ArgumentCaptor<ApiMetadata> captor = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
