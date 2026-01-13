package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiOrchestratorResponse {
    private String code;
    private String type;
    private JsonNode patch;
    private String explanation;
    private List<String> warnings;
    private String message;
    private List<String> options;
    private List<AiOption> optionPayloads;
    private String componentId;
    private String componentType;
    private String path;
    private JsonNode providedValue;
    private List<String> allowedValues;
}
