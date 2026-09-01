package org.praxisplatform.config.rag;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class RagVectorStoreServiceTest {

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;

    @Mock
    private RagEmbeddingProfile embeddingProfile;

    private RagVectorStoreService service;

    @BeforeEach
    void setUp() {
        lenient().when(embeddingProfile.id()).thenReturn("rag-v1__gemini__gemini-embedding-2__768");
        service = new RagVectorStoreService(vectorStoreProvider, jdbcTemplateProvider, embeddingProfile, "vector_store");
    }

    @Test
    void shouldReportUnavailableWhenCanonicalVectorStoreToggleIsDisabled() {
        RagVectorStoreService disabledService = new RagVectorStoreService(
                vectorStoreProvider,
                jdbcTemplateProvider,
                embeddingProfile,
                "vector_store",
                false);

        assertThat(disabledService.isAvailable()).isFalse();
    }

    @Test
    void shouldDeduplicateDocumentsUsingScopeAndContentHashMetadata() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        Map<String, Object> sharedMetadata = Map.of(
                RagMetadataKeys.TENANT_ID, "tenant-a",
                RagMetadataKeys.ENVIRONMENT, "prod",
                RagMetadataKeys.RELEASE_ID, "release-1",
                RagMetadataKeys.COMPONENT_ID, "get:/users",
                RagMetadataKeys.DOC_TYPE, "api_metadata",
                RagMetadataKeys.CONTENT_HASH, "hash-123",
                RagMetadataKeys.CHUNK_INDEX, 0);

        Document first = Document.builder()
                .id("tenant-a/prod/get_users/release-1/api_metadata/hash-123/0")
                .text("first")
                .metadata(sharedMetadata)
                .build();
        Document duplicate = Document.builder()
                .id("legacy-id-that-should-be-ignored")
                .text("second")
                .metadata(sharedMetadata)
                .build();

        service.upsertDocuments(List.of(first, duplicate));

        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(docsCaptor.capture());
        verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());

        assertThat(docsCaptor.getValue()).hasSize(1);
        assertThat(docsCaptor.getValue().get(0).getId()).isEqualTo(first.getId());
        assertThat(docsCaptor.getValue().get(0).getMetadata())
                .containsEntry(RagMetadataKeys.EMBEDDING_PROFILE, "rag-v1__gemini__gemini-embedding-2__768");
    }

    @Test
    void shouldFallbackToContentHashWhenMetadataHashIsMissing() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        Map<String, Object> metadata = Map.of(
                RagMetadataKeys.TENANT_ID, "tenant-a",
                RagMetadataKeys.ENVIRONMENT, "prod",
                RagMetadataKeys.RELEASE_ID, "release-1",
                RagMetadataKeys.COMPONENT_ID, "component-a",
                RagMetadataKeys.DOC_TYPE, "component_definition");

        Document first = Document.builder()
                .id("id-1")
                .text("same-content")
                .metadata(metadata)
                .build();
        Document duplicate = Document.builder()
                .id("id-2")
                .text("same-content")
                .metadata(metadata)
                .build();

        service.upsertDocuments(List.of(first, duplicate));

        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(docsCaptor.capture());
        assertThat(docsCaptor.getValue()).hasSize(1);
        assertThat(docsCaptor.getValue().get(0).getId()).isEqualTo(first.getId());
    }

    @Test
    void shouldBoundVectorStoreUpsertsToProtectProviderMemory() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        int documentCount = RagVectorStoreService.UPSERT_BATCH_SIZE * 2 + 5;
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < documentCount; index++) {
            documents.add(Document.builder()
                    .id("id-" + index)
                    .text("content-" + index)
                    .metadata(Map.of(
                            RagMetadataKeys.COMPONENT_ID, "component-" + index,
                            RagMetadataKeys.DOC_TYPE, "component_definition",
                            RagMetadataKeys.CONTENT_HASH, "hash-" + index))
                    .build());
        }

        service.upsertDocuments(documents);

        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(docsCaptor.capture());
        verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());

        assertThat(docsCaptor.getAllValues())
                .extracting(List::size)
                .containsExactly(
                        RagVectorStoreService.UPSERT_BATCH_SIZE,
                        RagVectorStoreService.UPSERT_BATCH_SIZE,
                        5);
        assertThat(docsCaptor.getAllValues().stream().flatMap(List::stream).map(Document::getId))
                .containsExactlyElementsOf(documents.stream().map(Document::getId).toList());
    }

    @Test
    void shouldPreserveExistingDocumentsWhenProviderUpsertFails() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        Document document = Document.builder()
                .id("stable-id")
                .text("content")
                .metadata(Map.of(
                        RagMetadataKeys.COMPONENT_ID, "component-a",
                        RagMetadataKeys.DOC_TYPE, "component_definition",
                        RagMetadataKeys.CONTENT_HASH, "hash-a"))
                .build();
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding unavailable"))
                .when(vectorStore)
                .add(org.mockito.ArgumentMatchers.anyList());

        assertThatThrownBy(() -> service.upsertDocuments(List.of(document)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding unavailable");

        verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldSkipUpsertWhenVectorStoreIsUnavailable() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);
        Document document = Document.builder().id("id-1").text("content").metadata(Map.of()).build();

        service.upsertDocuments(List.of(document));

        verify(vectorStore, never()).delete(org.mockito.ArgumentMatchers.anyList());
        verify(vectorStore, never()).add(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldExecuteDeleteQueryOnDeleteDocumentsByScope() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Map.class))).thenReturn(3);

        service.deleteDocumentsByScope("tenant-x", "prod", "v2", "comp-1", "comp-kind");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("DELETE FROM vector_store");
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("tenantId")).isEqualTo("tenant-x");
        assertThat(params.get("environment")).isEqualTo("prod");
        assertThat(params.get("releaseId")).isEqualTo("v2");
        assertThat(params.get("sourceId")).isEqualTo("comp-1");
        assertThat(params.get("sourceKind")).isEqualTo("comp-kind");
    }

    @Test
    void shouldUseConfiguredVectorStoreTableNameForDeleteDocumentsByScope() {
        service = new RagVectorStoreService(vectorStoreProvider, jdbcTemplateProvider, embeddingProfile, "custom_vector_store");
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Map.class))).thenReturn(1);

        service.deleteDocumentsByScope("tenant-x", "prod", "v2", "comp-1", "comp-kind");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(Map.class));
        assertThat(sqlCaptor.getValue()).contains("DELETE FROM custom_vector_store");
    }

    @Test
    void shouldDeleteCanonicalContentIdentityIndependentFromPhysicalDocumentId() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        Document document = Document.builder()
                .id("new-evidence-physical-id")
                .text("governed content")
                .metadata(Map.of(
                        RagMetadataKeys.TENANT_ID, "tenant-a",
                        RagMetadataKeys.ENVIRONMENT, "prod",
                        RagMetadataKeys.RELEASE_ID, "release-1",
                        RagMetadataKeys.COMPONENT_ID, "project.preference",
                        RagMetadataKeys.DOC_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE,
                        RagMetadataKeys.CONTENT_HASH, "content-hash",
                        RagMetadataKeys.CHUNK_INDEX, 0))
                .build();

        service.deleteDocumentByCanonicalContentIdentity(document);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("DELETE FROM vector_store")
                .contains("contentHash")
                .contains("chunkIndex");
        assertThat(paramsCaptor.getValue())
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("environment", "prod")
                .containsEntry("releaseId", "release-1")
                .containsEntry("componentId", "project.preference")
                .containsEntry("docType", RagResourceTypes.PROJECT_KNOWLEDGE)
                .containsEntry("contentHash", "content-hash")
                .containsEntry("chunkIndex", 0);
    }

    @Test
    void shouldExecuteDeleteQueryOnDeleteDocumentsByRelease() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Map.class))).thenReturn(4);

        service.deleteDocumentsByRelease("tenant-x", "prod", "v2", RagResourceTypes.API_METADATA);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("DELETE FROM vector_store");
        assertThat(sqlCaptor.getValue()).contains("resourceType");
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("tenantId")).isEqualTo("tenant-x");
        assertThat(params.get("environment")).isEqualTo("prod");
        assertThat(params.get("releaseId")).isEqualTo("v2");
        assertThat(params.get("resourceType")).isEqualTo(RagResourceTypes.API_METADATA);
    }

    @Test
    void shouldPurgeSupersededReleasesForResourceType() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);

        service.deleteDocumentsByResourceTypeExceptRelease(
                "tenant-x", "prod", "release-current", RagResourceTypes.COMPONENT_DEFINITION);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("DELETE FROM vector_store")
                .contains("<> :activeReleaseId");
        assertThat(paramsCaptor.getValue())
                .containsEntry("activeReleaseId", "release-current")
                .containsEntry("resourceType", RagResourceTypes.COMPONENT_DEFINITION);
    }

    @Test
    void shouldPlanSupersededReleasesWithoutDeletingDocuments() {
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), anyMap()))
                .thenReturn(List.of(
                        Map.of("release_id", "release-old-a", "document_count", 11L),
                        Map.of("release_id", "release-old-b", "document_count", 7L)));

        RagVectorStoreService.SupersededReleaseCleanupPlan plan =
                service.planDocumentsByResourceTypeExceptRelease(
                        "tenant-x",
                        "prod",
                        "release-current",
                        RagResourceTypes.COMPONENT_DEFINITION);

        assertThat(plan.documentCount()).isEqualTo(18L);
        assertThat(plan.releases())
                .extracting(RagVectorStoreService.SupersededReleaseDocuments::releaseId)
                .containsExactly("release-old-a", "release-old-b");
        verify(jdbcTemplate, never()).update(anyString(), anyMap());
    }

    @Test
    void shouldPurgeSupersededDomainCatalogReleasesWithinCanonicalScope() {
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);

        service.deleteDocumentsByCanonicalScopeExceptRelease(
                "tenant-x",
                "prod",
                "praxis-api-quickstart",
                null,
                "catalog-current",
                RagResourceTypes.DOMAIN_CATALOG);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("metadata ->> 'serviceKey'")
                .contains("metadata ->> 'resourceKey'")
                .contains("<> :activeReleaseId");
        assertThat(paramsCaptor.getValue())
                .containsEntry("serviceKey", "praxis-api-quickstart")
                .containsEntry("resourceKey", "")
                .containsEntry("activeReleaseId", "catalog-current");
    }

    @Test
    void shouldReturnReleaseStatusWithCountsBySourceChunkAndVisibility() {
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("praxis-table", "component_definition", "summary", "allow", "1.0.0", "2026-05-19T10:00:00Z", 1));
        rows.add(row("praxis-table", "component_definition", "recipe", "allow", "1.0.0", "2026-05-19T10:01:00Z", 2));
        rows.add(row("praxis-chart", "component_definition", "summary", "mask", "1.0.0", "2026-05-19T10:02:00Z", 1));
        when(jdbcTemplate.queryForList(anyString(), any(Map.class))).thenReturn(rows);

        RagVectorStoreService.RagCorpusReleaseStatus status =
                service.corpusReleaseStatus("tenant-a", "prod", "release-1", 4);

        assertThat(status.available()).isTrue();
        assertThat(status.reconciled()).isTrue();
        assertThat(status.documentCount()).isEqualTo(4);
        assertThat(status.sourceCount()).isEqualTo(2);
        assertThat(status.chunkKindCounts()).containsEntry("summary", 2L).containsEntry("recipe", 2L);
        assertThat(status.visibilityCounts()).containsEntry("allow", 3L).containsEntry("mask", 1L);
        assertThat(status.latestPublishedAt()).isEqualTo("2026-05-19T10:02:00Z");
        assertThat(status.sources())
                .extracting(RagVectorStoreService.SourceStatus::sourceId)
                .containsExactly("praxis-table", "praxis-chart");
        assertThat(status.warnings()).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("FROM vector_store");
        assertThat(paramsCaptor.getValue()).containsEntry("releaseId", "release-1");
        assertThat(paramsCaptor.getValue()).containsEntry("resourceType", RagResourceTypes.COMPONENT_DEFINITION);
        assertThat(paramsCaptor.getValue())
                .containsEntry("embeddingProfile", "rag-v1__gemini__gemini-embedding-2__768");
    }

    @Test
    void shouldReportMismatchWhenReleaseStatusDoesNotMatchExpectedChunkCount() {
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(row("praxis-table", "component_definition", "summary", "allow", "1.0.0", "", 1)));

        RagVectorStoreService.RagCorpusReleaseStatus status =
                service.corpusReleaseStatus("tenant-a", "prod", "release-1", 3);

        assertThat(status.available()).isTrue();
        assertThat(status.reconciled()).isFalse();
        assertThat(status.expectedChunkCount()).isEqualTo(3);
        assertThat(status.documentCount()).isEqualTo(1);
        assertThat(status.warnings()).contains("corpus-chunk-count-mismatch");
    }

    @Test
    void shouldAllowReleaseStatusForExplicitResourceType() {
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(row("human-resources.employee", "node", "summary", "allow", "v0.2", "", 1)));

        service.corpusReleaseStatus(
                "tenant-a",
                "prod",
                "release-1",
                RagResourceTypes.DOMAIN_CATALOG,
                1);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).queryForList(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("resourceType", RagResourceTypes.DOMAIN_CATALOG);
    }

    @Test
    void shouldReturnUnavailableReleaseStatusWhenJdbcIsMissing() {
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(null);

        RagVectorStoreService.RagCorpusReleaseStatus status =
                service.corpusReleaseStatus("tenant-a", "prod", "release-1", 2);

        assertThat(status.available()).isFalse();
        assertThat(status.reconciled()).isFalse();
        assertThat(status.warnings()).contains("configNamedParameterJdbcTemplate-unavailable");
    }

    private Map<String, Object> row(
            String sourceId,
            String sourceKind,
            String chunkKind,
            String visibility,
            String corpusVersion,
            String publishedAt,
            long count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", sourceId);
        row.put("source_kind", sourceKind);
        row.put("chunk_kind", chunkKind);
        row.put("ai_visibility", visibility);
        row.put("corpus_version", corpusVersion);
        row.put("latest_published_at", publishedAt);
        row.put("document_count", count);
        return row;
    }
}
