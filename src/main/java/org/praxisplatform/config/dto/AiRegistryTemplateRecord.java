package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representação serializada de um template AI persistido para um componente.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateRecord {
    private String componentId;
    private String aiDescription;
    private JsonNode configJson;
    private JsonNode templateMeta;
}
