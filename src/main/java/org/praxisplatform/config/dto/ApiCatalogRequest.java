package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * DTO estruturado para ingestão de catálogo de APIs.
 * Espelha a estrutura esperada pelo ApiMetadataIngestionService.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiCatalogRequest {

    @Valid
    @NotNull
    private List<ApiEndpointEntry> endpoints;

    private String releaseId;
    private String version;
    private String generatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApiEndpointEntry {

        @NotBlank(message = "Path is required")
        private String path;

        @NotBlank(message = "Method is required")
        private String method;

        private List<String> tags;
        private String summary;
        private String description;
        private String operationId;

        // Schemas complexos mantidos como JsonNode para flexibilidade,
        // mas agora tipados dentro do DTO em vez de strings soltas.
        private JsonNode requestSchema;
        private JsonNode responseSchema;
        private JsonNode parameters;
    }
}
