package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiSchemaContext;

/**
 * Contexto AI consolidado para um componente.
 *
 * <p>
 * É o payload canônico devolvido por {@code /api/praxis/config/ai-context/**}, combinando estado
 * atual, definição do componente, template opcional e metadados de schema usados na orquestração.
 * </p>
 */
@Data
@Builder
public class AiContextDTO {
    private String componentId;
    private String componentType;
    private String aiMode;
    private boolean requireSchema;
    private String resourcePath;
    private String description;
    private JsonNode currentState;
    private JsonNode componentDefinition;
    private AiRegistryTemplateRecord template;
    private AiSchemaContext schemaContext;
    private JsonNode schema;
}
