package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.domain.ComponentDefinition;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.ComponentDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegistryIngestionService {

    private final ComponentDefinitionRepository repository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    @Transactional
    public void ingestRegistry(RegistryIngestionRequest request) {
        if (request.getComponents() == null || request.getComponents().isEmpty()) {
            log.warn("No 'components' map found in registry request.");
            return;
        }

        request.getComponents().forEach((componentId, entry) -> {
            try {
                ComponentDefinition def = toComponentDefinition(componentId, entry);
                repository.save(def);
                log.info("Ingested component: {}", componentId);
            } catch (Exception e) {
                log.error("Failed to process component: " + componentId, e);
                throw new ConfigurationIngestionException("Error processing component: " + componentId, e);
            }
        });
    }

    private ComponentDefinition toComponentDefinition(String componentId, RegistryIngestionRequest.ComponentEntry entry) {
        String description = entry.getDescription();
        if (description == null || description.isEmpty()) {
            description = "Component " + componentId;
        }

        String summary = buildSummary(componentId, description, entry);
        List<Float> embedding = embeddingService.embed(summary);
        
        // Serialize the entry back to JSON for storage as the 'jsonSchema' column requires it
        String compactJson = toJson(entry);

        return ComponentDefinition.builder()
                .id(componentId)
                .description(description)
                .jsonSchema(compactJson)
                .embedding(embedding)
                .build();
    }

    private String buildSummary(String id, String description, RegistryIngestionRequest.ComponentEntry entry) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add("Component: " + id);
        joiner.add("Description: " + description);

        if (entry.getCategory() != null && !entry.getCategory().isBlank()) {
            joiner.add("Category: " + entry.getCategory());
        }

        joiner.add("Inputs: " + summarizeIO(entry.getInputs()));
        joiner.add("Outputs: " + summarizeIO(entry.getOutputs()));

        return joiner.toString();
    }

    private String summarizeIO(List<RegistryIngestionRequest.IoEntry> ioEntries) {
        if (ioEntries == null || ioEntries.isEmpty()) {
            return "none";
        }
        StringJoiner joiner = new StringJoiner("; ");
        for (RegistryIngestionRequest.IoEntry item : ioEntries) {
            String name = item.getName() != null ? item.getName() : "";
            String type = item.getType() != null ? item.getType() : "";
            boolean required = item.isRequired();
            joiner.add(name + ":" + type + (required ? " (required)" : ""));
        }
        return joiner.toString();
    }

    private String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new ConfigurationIngestionException("Failed to serialize component entry", e);
        }
    }

    // Old methods removed as they are replaced by typed versions above

}
