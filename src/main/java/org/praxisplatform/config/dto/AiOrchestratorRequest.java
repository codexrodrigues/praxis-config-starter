package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiOrchestratorRequest {
    @NotBlank
    private String componentId;
    @NotBlank
    private String componentType;
    @NotBlank
    private String userPrompt;

    private JsonNode currentState;
    private JsonNode dataProfile;
    private JsonNode schemaFields;
    private JsonNode runtimeState;
    private JsonNode suggestedPatch;
    private JsonNode contextHints;
    private String aiMode;
    private Boolean requireSchema;
    private String resourcePath;
    private AiSchemaContext schemaContext;
    private String variantId;

    private String apiMethod;
    private String apiTags;
    private Integer apiSearchLimit;
}
