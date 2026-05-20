package org.praxisplatform.config.rag;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    private RagVectorStoreService service;

    @BeforeEach
    void setUp() {
        service = new RagVectorStoreService(vectorStoreProvider, jdbcTemplateProvider, "vector_store");
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

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(idsCaptor.capture());
        verify(vectorStore).add(docsCaptor.capture());

        assertThat(idsCaptor.getValue()).containsExactly(first.getId());
        assertThat(docsCaptor.getValue()).hasSize(1);
        assertThat(docsCaptor.getValue().get(0).getId()).isEqualTo(first.getId());
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
        service = new RagVectorStoreService(vectorStoreProvider, jdbcTemplateProvider, "custom_vector_store");
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(jdbcTemplateProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), any(Map.class))).thenReturn(1);

        service.deleteDocumentsByScope("tenant-x", "prod", "v2", "comp-1", "comp-kind");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(Map.class));
        assertThat(sqlCaptor.getValue()).contains("DELETE FROM custom_vector_store");
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
