package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload opcional para composição transitória de contexto AI.
 *
 * <p>
 * Usado pelo {@code POST /api/praxis/config/ai-context/{componentId}} quando o caller quer enviar
 * um estado em memória e um {@link AiSchemaContext} sem depender exclusivamente da configuração
 * persistida do host.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiContextRuntimeRequest {
    private JsonNode currentState;
    private String resourcePath;
    private AiSchemaContext schemaContext;
}
