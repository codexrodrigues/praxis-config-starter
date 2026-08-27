package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.praxisplatform.config.dto.ApiCatalogRequest;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ApiMetadataIngestionServiceTest {

    @Mock private ApiMetadataRepository repository;
    @Mock private EmbeddingService embeddingService;
    @Mock private RagVectorStoreService ragVectorStoreService;
    @Mock private ApiMetadataIndexingStateService indexingStateService;
    @Mock private ApiMetadataIndexingCoordinator indexingCoordinator;

    private ApiMetadataIngestionService service;

    @BeforeEach
    void setUp() {
        service = new ApiMetadataIngestionService(
                repository,
                new ObjectMapper(),
                embeddingService,
                ragVectorStoreService,
                indexingStateService,
                indexingCoordinator);
    }

    @Test
    void shouldPersistCanonicalSnapshotAndScheduleIndexingWithoutCallingEmbeddingProvider() {
        ApiCatalogRequest request = request("release-1", "/api/users", "GET");
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(indexingStateService.request(scope)).thenReturn(7L);
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/users", "GET"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(1L);

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<ApiMetadata> metadata = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(metadata.capture());
        assertThat(metadata.getValue().getEmbedding()).isNull();
        verify(indexingStateService).updateExpectedCount(scope, 7L, 1L);
        verify(indexingCoordinator).scheduleAfterCommit(scope);
        verify(embeddingService, never()).embed(anyString());
        verify(embeddingService, never()).embedAll(any());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void shouldPreserveEmbeddingAndSkipDerivedIndexingForMateriallyIdenticalEndpoint() throws Exception {
        ApiCatalogRequest request = request("release-1", "/api/users", "GET");
        request.setGeneratedAt("2026-08-27T12:00:00Z");
        ApiMetadata existing = metadata(41L, "/api/users", "GET", List.of(0.1f, 0.2f));
        existing.setGeneratedAt("2026-08-26T12:00:00Z");
        existing.setRawJson(new ObjectMapper().writeValueAsString(request.getEndpoints().get(0)));
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/users", "GET"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(indexingStateService.snapshot(scope)).thenReturn(Optional.of(indexingState(ApiMetadataIndexingStatus.READY)));

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<ApiMetadata> saved = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmbedding()).containsExactly(0.1f, 0.2f);
        assertThat(saved.getValue().getGeneratedAt()).isEqualTo("2026-08-27T12:00:00Z");
        verify(indexingStateService, never()).request(any());
        verify(indexingStateService, never()).updateExpectedCount(
                any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(indexingCoordinator, never()).scheduleAfterCommit(any());
        verify(repository, never()).countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldScheduleRecoveryForIdenticalEndpointWhenDerivedLifecycleFailed() throws Exception {
        ApiCatalogRequest request = request("release-1", "/api/users", "GET");
        ApiMetadata existing = metadata(41L, "/api/users", "GET", List.of(0.1f, 0.2f));
        existing.setRawJson(new ObjectMapper().writeValueAsString(request.getEndpoints().get(0)));
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/users", "GET"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(indexingStateService.snapshot(scope)).thenReturn(Optional.of(indexingState(ApiMetadataIndexingStatus.FAILED)));
        when(indexingStateService.request(scope)).thenReturn(9L);
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        service.ingestCatalog(request, "tenant-a", "prod");

        assertThat(existing.getEmbedding()).containsExactly(0.1f, 0.2f);
        verify(indexingStateService).updateExpectedCount(scope, 9L, 1L);
        verify(indexingCoordinator).scheduleAfterCommit(scope);
    }

    @Test
    void shouldInvalidateEmbeddingAndScheduleIndexingForMaterialChange() throws Exception {
        ApiCatalogRequest request = request("release-1", "/api/users", "GET");
        ApiMetadata existing = metadata(41L, "/api/users", "GET", List.of(0.1f, 0.2f));
        existing.setSummary("Old summary");
        existing.setRawJson(new ObjectMapper().writeValueAsString(request.getEndpoints().get(0)));
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/users", "GET"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(indexingStateService.request(scope)).thenReturn(8L);
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        service.ingestCatalog(request, "tenant-a", "prod");

        assertThat(existing.getEmbedding()).isNull();
        verify(indexingStateService).updateExpectedCount(scope, 8L, 1L);
        verify(indexingCoordinator).scheduleAfterCommit(scope);
    }

    @Test
    void shouldMaterializeLegacyEmbeddingAndCanonicalRagOutsideIngestionTransaction() {
        ApiMetadata row = metadata(41L, "/api/users", "GET", null);
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(List.of(row));
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(indexingStateService.commitLegacyEmbeddings(any(), org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(true);

        service.processIndexingClaim(new ApiMetadataIndexingStateService.WorkClaim(scope, 7L, 1L));

        verify(indexingStateService).commitLegacyEmbeddings(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.eq(7L),
                any());
        verify(ragVectorStoreService).deleteDocumentsByRelease(
                "tenant-a", "prod", "release-1", RagResourceTypes.API_METADATA);
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documents.capture());
        assertThat(documents.getValue()).hasSize(1);
        verify(indexingStateService).complete(scope, 7L, 1L);
    }

    @Test
    void shouldPreserveReleaseScopedDocumentIdentityAndDropNullMetadataValues() {
        ApiMetadata row = metadata(41L, "/v1/users", "GET", List.of(0.1f, 0.2f));
        row.setReleaseId("release-2026-02");
        row.setReleaseVersion("2026.02");
        row.setSummary(null);
        row.setDescription(null);
        row.setOperationId(null);
        row.setTags(null);
        ObjectNode requestSchema = new ObjectMapper().createObjectNode().put("name", "DemoRequest");
        row.setRequestSchema(requestSchema.toString());
        row.setRawJson("{\"path\":\"/v1/users\",\"method\":\"GET\",\"requestSchema\":{\"name\":\"DemoRequest\"}}");
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-2026-02");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-2026-02")).thenReturn(List.of(row));
        when(indexingStateService.commitLegacyEmbeddings(scope, 7L, java.util.Map.of())).thenReturn(true);

        service.processIndexingClaim(new ApiMetadataIndexingStateService.WorkClaim(scope, 7L, 1L));

        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documents.capture());
        Document document = documents.getValue().get(0);
        assertThat(document.getId()).startsWith(
                "tenant-a/prod/get_v1_users/release-2026-02/api_metadata/");
        assertThat(document.getId()).endsWith("/0");
        assertThat(document.getMetadata().values()).doesNotContainNull();
        assertThat(document.getMetadata())
                .containsEntry(RagMetadataKeys.COMPONENT_ID, "GET:/v1/users")
                .containsEntry(RagMetadataKeys.RELEASE_ID, "release-2026-02")
                .containsEntry(RagMetadataKeys.VERSION, "2026.02");
    }

    @Test
    void shouldReconcileMovedEndpointOnlyThroughStableIdentityInsideTheSameScope() {
        ApiMetadata stale = metadata(41L, "/api/human-resources/missoes/filter", "POST", List.of(0.1f));
        stale.setOperationId("filterMissoes");
        ApiCatalogRequest request = ApiCatalogRequest.builder()
                .releaseId("release-1")
                .endpoints(List.of(ApiCatalogRequest.ApiEndpointEntry.builder()
                        .path("/api/operations/missoes/filter")
                        .method("POST")
                        .operationId("filterMissoes")
                        .build()))
                .build();
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(indexingStateService.request(scope)).thenReturn(8L);
        when(repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                "tenant-a", "prod", "default", "release-1", "/api/operations/missoes/filter", "POST"))
                .thenReturn(Optional.empty());
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndOperationIdAndMethod(
                "tenant-a", "prod", "default", "release-1", "filterMissoes", "POST"))
                .thenReturn(List.of(stale));
        when(repository.save(any(ApiMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);

        service.ingestCatalog(request, "tenant-a", "prod");

        ArgumentCaptor<ApiMetadata> saved = ArgumentCaptor.forClass(ApiMetadata.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stale);
        assertThat(saved.getValue().getPath()).isEqualTo("/api/operations/missoes/filter");
        assertThat(saved.getValue().getEmbedding()).isNull();
    }

    @Test
    void shouldNotPublishARevisionSupersededDuringEmbedding() {
        ApiMetadata row = metadata(41L, "/api/users", "GET", null);
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(List.of(row));
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(indexingStateService.commitLegacyEmbeddings(any(), org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(false);

        service.processIndexingClaim(new ApiMetadataIndexingStateService.WorkClaim(scope, 7L, 1L));

        verify(ragVectorStoreService, never()).deleteDocumentsByRelease(
                anyString(), anyString(), anyString(), anyString());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
        verify(indexingStateService, never()).complete(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldRecordSanitizedFailureWithoutDiscardingCanonicalRows() {
        ApiMetadata row = metadata(41L, "/api/users", "GET", null);
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1"))
                .thenReturn(List.of(row));
        when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("secret provider detail"));

        service.processIndexingClaim(new ApiMetadataIndexingStateService.WorkClaim(scope, 7L, 1L));

        verify(indexingStateService).fail(
                scope,
                7L,
                "EMBEDDING_FAILED",
                "API metadata derived indexing failed; canonical metadata remains persisted.");
        verify(repository, never()).delete(any());
    }

    @Test
    void shouldClassifyVectorPublicationFailureAfterLegacyIndexing() {
        ApiMetadata row = metadata(41L, "/api/users", "GET", List.of(0.1f, 0.2f));
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(List.of(row));
        when(indexingStateService.commitLegacyEmbeddings(scope, 7L, java.util.Map.of())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("vector provider secret"))
                .when(ragVectorStoreService).upsertDocuments(any());

        service.processIndexingClaim(new ApiMetadataIndexingStateService.WorkClaim(scope, 7L, 1L));

        verify(indexingStateService).fail(
                scope,
                7L,
                "RAG_PUBLICATION_FAILED",
                "API metadata derived indexing failed; canonical metadata remains persisted.");
    }

    @Test
    void shouldExposePersistedLifecycleTogetherWithVectorReadiness() {
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                "tenant-a", "prod", "default", "release-1");
        Instant now = Instant.parse("2026-08-26T20:00:00Z");
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);
        when(repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                "tenant-a", "prod", "default", "release-1")).thenReturn(1L);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.corpusReleaseStatus(
                "tenant-a", "prod", "release-1", RagResourceTypes.API_METADATA, 1L))
                .thenReturn(new RagVectorStoreService.RagCorpusReleaseStatus(
                        true, true, "tenant-a", "prod", "release-1",
                        1, 1, 1, java.util.Map.of(), java.util.Map.of(), List.of(), now.toString(), List.of()));
        when(indexingStateService.snapshot(scope)).thenReturn(Optional.of(
                new ApiMetadataIndexingStateService.StateSnapshot(
                        ApiMetadataIndexingStatus.READY,
                        7L,
                        1,
                        1L,
                        1L,
                        1L,
                        null,
                        null,
                        now,
                        now,
                        now,
                        now)));

        var status = service.ragStatus("tenant-a", "prod", "default", "release-1");

        assertThat(status.schemaVersion()).isEqualTo("praxis.api-metadata-rag-status/v0.2");
        assertThat(status.indexingStatus()).isEqualTo("READY");
        assertThat(status.reconciled()).isTrue();
        assertThat(status.legacyIndexedDocumentCount()).isEqualTo(1L);
    }

    private ApiCatalogRequest request(String release, String path, String method) {
        return ApiCatalogRequest.builder()
                .releaseId(release)
                .endpoints(List.of(ApiCatalogRequest.ApiEndpointEntry.builder()
                        .path(path)
                        .method(method)
                        .summary("List users")
                        .build()))
                .build();
    }

    private ApiMetadata metadata(Long id, String path, String method, List<Float> embedding) {
        ApiMetadata row = new ApiMetadata();
        ReflectionTestUtils.setField(row, "id", id);
        row.setTenantId("tenant-a");
        row.setEnvironment("prod");
        row.setServiceKey("default");
        row.setReleaseId("release-1");
        row.setPath(path);
        row.setMethod(method);
        row.setSummary("List users");
        row.setRawJson("{\"path\":\"/api/users\",\"method\":\"GET\",\"summary\":\"List users\"}");
        row.setEmbedding(embedding);
        return row;
    }

    private ApiMetadataIndexingStateService.StateSnapshot indexingState(ApiMetadataIndexingStatus status) {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        return new ApiMetadataIndexingStateService.StateSnapshot(
                status,
                7L,
                1,
                1L,
                1L,
                1L,
                status == ApiMetadataIndexingStatus.FAILED ? "EMBEDDING_FAILED" : null,
                status == ApiMetadataIndexingStatus.FAILED ? "Sanitized failure" : null,
                now,
                now,
                now,
                now);
    }
}
