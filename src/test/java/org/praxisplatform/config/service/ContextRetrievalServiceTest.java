package org.praxisplatform.config.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.repository.AiRegistryRepository;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
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
}
