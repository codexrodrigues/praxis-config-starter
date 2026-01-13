package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO estruturado para ingestão de componentes de UI.
 * Espelha a estrutura esperada pelo RegistryIngestionService.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryIngestionRequest {

    @Valid
    private Map<String, ComponentEntry> components;

    private JsonNode definitions;

    private String version;

    private String generatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComponentEntry {
        
        private String description;
        
        private String category;
        
        private List<IoEntry> inputs;
        
        private List<IoEntry> outputs;

        private String configSchemaId;

        private JsonNode configSchema;

        private JsonNode componentContext;

        private List<CapabilityEntry> capabilities;
        
        // Campos adicionais livres que podem vir do JSON
        @JsonIgnore
        private Map<String, JsonNode> additionalProperties = new LinkedHashMap<>();

        @JsonAnySetter
        public void addAdditionalProperty(String key, JsonNode value) {
            additionalProperties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, JsonNode> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CapabilityEntry {
        private String path;
        private String category;
        private String valueKind;
        private List<Object> allowedValues;
        private String description;
        private Boolean critical;
        private List<String> intentExamples;
        private String dependsOn;
        private String example;
        private String safetyNotes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class IoEntry {
        private String name;
        private String type;
        private boolean required;
    }
}
