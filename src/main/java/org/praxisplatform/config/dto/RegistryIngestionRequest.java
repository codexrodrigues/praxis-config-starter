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
 * DTO estruturado para ingestÃ£o de componentes de UI.
 *
 * <p>
 * Espelha a estrutura esperada pelo {@code RegistryIngestionService} para persistir definiÃ§Ãµes
 * de componentes, capacidades e schemas auxiliares em lote.
 * </p>
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
    /**
     * DescriÃ§Ã£o serializada de um componente individual dentro do lote de ingestÃ£o.
     */
    public static class ComponentEntry {

        private String description;

        private String category;

        private List<IoEntry> inputs;

        private List<IoEntry> outputs;

        private String configSchemaId;

        private JsonNode configSchema;

        private JsonNode componentContext;

        private List<CapabilityEntry> capabilities;

        private List<ChunkEntry> chunks;

        // Campos adicionais livres que podem vir do JSON
        @JsonIgnore
        @Builder.Default
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
    /**
     * Capacidade declarativa publicada por um componente ingerido.
     */
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
    /**
     * Entrada simples de input/output declarada pelo componente.
     */
    public static class IoEntry {
        private String name;
        private String type;
        private boolean required;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    /**
     * Chunk granular de corpus gerado para o componente para uso em RAG.
     */
    public static class ChunkEntry {
        private int chunkIndex;
        private String chunkKind;
        private String content;
        private String sourcePointer;
        private String contentHash;
        private String sourceKind;
        private String sourceId;
        private String corpusVersion;
        private String aiVisibility;
        private String embeddingProfile;
    }
}
