package org.praxisplatform.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComponentEntry {
        
        private String description;
        
        private String category;
        
        private List<IoEntry> inputs;
        
        private List<IoEntry> outputs;
        
        // Campos adicionais livres que podem vir do JSON
        private Map<String, Object> additionalProperties;
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
