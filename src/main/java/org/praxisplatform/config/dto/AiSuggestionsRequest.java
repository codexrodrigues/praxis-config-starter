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
public class AiSuggestionsRequest {
    @NotBlank
    private String componentId;
    @NotBlank
    private String componentType;

    private JsonNode currentState;
    private JsonNode dataProfile;
    private String variantId;
    private Integer maxSuggestions;
    private Boolean forceRefresh;
    private String locale;
}
