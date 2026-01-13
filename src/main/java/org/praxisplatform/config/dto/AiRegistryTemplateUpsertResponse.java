package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateUpsertResponse {
    private String componentId;
    private String aiDescription;
    private JsonNode configJson;
    private JsonNode templateMeta;
    private String status;
}
