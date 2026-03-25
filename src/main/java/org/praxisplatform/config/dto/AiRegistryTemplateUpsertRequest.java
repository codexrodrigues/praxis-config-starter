package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload de upsert de template AI por componente.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiRegistryTemplateUpsertRequest {

    @NotNull
    private JsonNode configJson;

    private String aiDescription;

    private JsonNode templateMeta;
}
