package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiContextDTO {
    private String componentId;
    private String resourcePath;
    private String description;
    private JsonNode currentState;
}
