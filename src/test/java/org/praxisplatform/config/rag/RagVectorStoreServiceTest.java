package org.praxisplatform.config.rag;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class RagVectorStoreServiceTest {

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private VectorStore vectorStore;

    private RagVectorStoreService service;

    @BeforeEach
    void setUp() {
        service = new RagVectorStoreService(vectorStoreProvider);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
    }

    @Test
    void shouldDeduplicateDocumentsUsingScopeAndContentHashMetadata() {
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
}
