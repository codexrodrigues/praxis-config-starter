package org.praxisplatform.config.projection;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;

/**
 * Projeção para resultados de busca vetorial de metadados de API.
 * Permite capturar campos da entidade ApiMetadata junto com o score de similaridade do pgvector.
 */
public interface ApiMetadataProjection {
    Long getId();
    String getPath();
    String getMethod();
    String getSummary();
    String getTags();
    String getDescription();
    String getOperationId();
    String getRequestSchema(); // Retorna como String, depois pode ser convertido para JsonNode
    String getResponseSchema(); // Retorna como String
    String getParameters(); // Retorna como String
    
    // Campo calculado via Native Query
    Double getSimilarityScore();

    // Métodos default para snippets (preview para UI)
    @Value("#{(target.requestSchema != null && target.requestSchema.length() > 500) ? target.requestSchema.substring(0, 497) + '...' : target.requestSchema}")
    String getRequestSchemaSnippet();

    @Value("#{(target.responseSchema != null && target.responseSchema.length() > 500) ? target.responseSchema.substring(0, 497) + '...' : target.responseSchema}")
    String getResponseSchemaSnippet();
}
