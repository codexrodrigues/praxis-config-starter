package org.praxisplatform.config.projection;

import org.springframework.beans.factory.annotation.Value;

/**
 * Projeção para resultados de busca vetorial de definições de componentes.
 * Permite capturar campos da entidade ComponentDefinition junto com o score de similaridade do pgvector.
 */
public interface ComponentDefinitionProjection {
    String getId();
    String getDescription();
    String getJsonSchema(); // Schema completo do componente
    
    // Campo calculado via Native Query
    Double getSimilarityScore();

    // Método default para snippet do jsonSchema
    @Value("#{(target.jsonSchema != null && target.jsonSchema.length() > 500) ? target.jsonSchema.substring(0, 497) + '...' : target.jsonSchema}")
    String getJsonSchemaSnippet();
}
