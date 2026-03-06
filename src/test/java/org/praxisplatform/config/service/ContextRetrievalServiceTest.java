package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class ContextRetrievalServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ApiMetadataRepository apiMetadataRepository;

    @Mock
    private AiRegistryRepository aiRegistryRepository;

    @Mock
    private org.praxisplatform.config.rag.RagVectorStoreService ragVectorStoreService;

    @InjectMocks
    private ContextRetrievalService contextRetrievalService;

    @Test
    void shouldReturnFullSchemaWithoutTruncation() {
        // Prepare a long schema string (> 500 chars)
        // We use a simple repeated string to avoid escaping issues in test generation
        // and because the service treats it as a plain string.
        String pattern = "{\"key\": \"value\", \"description\": \"some long description to fill space\"}, ";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for(int i=0; i<20; i++) {
            sb.append(pattern);
        }
        sb.append("]");
        String longSchema = sb.toString();
        
        assertTrue(longSchema.length() > 500, "Schema should be longer than 500 chars for valid test");

        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        // Mock embedding service
        when(embeddingService.embed(anyString(), any())).thenReturn(List.of(0.1f, 0.2f));

        // Mock repository to return a projection
        ApiMetadataProjection projectionMock = mock(ApiMetadataProjection.class);
        when(projectionMock.getId()).thenReturn(1L);
        when(projectionMock.getMethod()).thenReturn("GET");
        when(projectionMock.getPath()).thenReturn("/api/test");
        
        // The key verification: Service must use these getters
        when(projectionMock.getRequestSchema()).thenReturn(longSchema);
        when(projectionMock.getResponseSchema()).thenReturn(longSchema);
        when(projectionMock.getParameters()).thenReturn("[]");
        when(projectionMock.getSimilarityScore()).thenReturn(0.95);
        
        // We also mock snippet getters to return truncated versions (simulating SpEL behavior or just distinct values)
        String truncatedSchema = longSchema.substring(0, 497) + "...";
        when(projectionMock.getRequestSchemaSnippet()).thenReturn(truncatedSchema);
        when(projectionMock.getResponseSchemaSnippet()).thenReturn(truncatedSchema);

        when(apiMetadataRepository.findByVectorSimilarity(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(projectionMock));

        // Execute
        List<ApiSearchResult> results = contextRetrievalService.searchApiMetadata("query", "GET", null, 5);

        // Verify
        assertNotNull(results);
        assertEquals(1, results.size());
        ApiSearchResult result = results.get(0);

        // Ensure the DTO has the full schema in the new fields
        assertEquals(longSchema, result.getRequestSchema(), "Request schema should be full length");
        assertEquals(longSchema, result.getResponseSchema(), "Response schema should be full length");
        
        // Ensure the snippet fields are truncated (Semantic Integrity)
        assertEquals(truncatedSchema, result.getRequestSchemaSnippet(), "Snippet should be truncated");
        assertEquals(truncatedSchema, result.getResponseSchemaSnippet(), "Snippet should be truncated");

        assertEquals("[]", result.getParameters());
        
        // Ensure metadata is correct
        assertEquals("GET", result.getMethod());
        assertEquals("/api/test", result.getPath());
    }

    @Test
    void shouldApplyReleaseScopedFilterWhenSearchingApiMetadata() {
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(eq("query"), eq(5), any(Filter.Expression.class)))
                .thenReturn(List.of(buildApiDocument("/api/release")));

        List<ApiSearchResult> results = contextRetrievalService.searchApiMetadata(
                "query",
                "GET",
                null,
                5,
                null,
                "tenant-a",
                "prod",
                "release-2026-02");

        ArgumentCaptor<Filter.Expression> filterCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(ragVectorStoreService).search(eq("query"), eq(5), filterCaptor.capture());
        String filterExpression = String.valueOf(filterCaptor.getValue());

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("/api/release", results.get(0).getPath());
        assertTrue(filterExpression.contains("releaseId"));
        assertTrue(filterExpression.contains("version"));
        assertTrue(filterExpression.contains("release-2026-02"));
    }

    @Test
    void shouldFallbackToDefaultReleaseWhenEnabled() {
        ReflectionTestUtils.setField(contextRetrievalService, "ragDefaultRelease", "release-default");
        ReflectionTestUtils.setField(contextRetrievalService, "ragReleaseFallbackToDefaultEnabled", true);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(eq("query"), eq(5), any(Filter.Expression.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(buildApiDocument("/api/default-release")));

        List<ApiSearchResult> results = contextRetrievalService.searchApiMetadata(
                "query",
                "GET",
                null,
                5,
                null,
                "tenant-a",
                "prod",
                "release-custom");

        ArgumentCaptor<Filter.Expression> filterCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(ragVectorStoreService, times(2)).search(eq("query"), eq(5), filterCaptor.capture());

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("/api/default-release", results.get(0).getPath());
        assertTrue(String.valueOf(filterCaptor.getAllValues().get(0)).contains("release-custom"));
        assertTrue(String.valueOf(filterCaptor.getAllValues().get(1)).contains("release-default"));
    }

    @Test
    void shouldSkipLegacyRepositoryFallbackWhenRagAvailableAndNoReleaseMatch() {
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(eq("query"), eq(5), any(Filter.Expression.class)))
                .thenReturn(List.of());

        List<ApiSearchResult> results = contextRetrievalService.searchApiMetadata(
                "query",
                "GET",
                null,
                5,
                null,
                null,
                null,
                "release-missing");

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verifyNoInteractions(apiMetadataRepository);
    }

    private Document buildApiDocument(String path) {
        return Document.builder()
                .id("api-doc:" + path)
                .text("api metadata")
                .metadata(Map.of(
                        RagMetadataKeys.METHOD, "GET",
                        RagMetadataKeys.PATH, path,
                        RagMetadataKeys.SUMMARY, "summary"))
                .build();
    }
}
