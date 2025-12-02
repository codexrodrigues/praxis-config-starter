package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.ComponentDefinition;
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
    public void ingestRegistry(String registryJson) {
        try {
            JsonNode root = objectMapper.readTree(registryJson);
            JsonNode components = root.path("components");

            if (components.isMissingNode()) {
                log.warn("No 'components' node found in registry JSON.");
                return;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = components.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String componentId = entry.getKey();
                JsonNode data = entry.getValue();

                ComponentDefinition def = toComponentDefinition(componentId, data);

                repository.save(def);
                log.info("Ingested component: {}", componentId);
            }

        } catch (Exception e) {
            log.error("Error ingesting registry", e);
            throw new RuntimeException("Failed to ingest registry", e);
        }
    }

    private ComponentDefinition toComponentDefinition(String componentId, JsonNode data) {
        String description = data.path("description").asText(null);
        if (description == null || description.isEmpty()) {
            description = "Component " + componentId;
        }

        String summary = buildSummary(componentId, description, data);
        List<Float> embedding = embeddingService.embed(summary);
        String compactJson = compactJson(data);

        return ComponentDefinition.builder()
                .id(componentId)
                .description(description)
                .jsonSchema(compactJson)
                .embedding(embedding)
                .build();
    }

    private String buildSummary(String id, String description, JsonNode data) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add("Component: " + id);
        joiner.add("Description: " + description);

        String category = data.path("category").asText(null);
        if (category != null && !category.isBlank()) {
            joiner.add("Category: " + category);
        }

        joiner.add("Inputs: " + summarizeIO(data.path("inputs")));
        joiner.add("Outputs: " + summarizeIO(data.path("outputs")));

        return joiner.toString();
    }

    private String summarizeIO(JsonNode ioNode) {
        if (ioNode == null || ioNode.isMissingNode()) {
            return "none";
        }
        StringJoiner joiner = new StringJoiner("; ");
        if (ioNode.isArray()) {
            for (JsonNode item : ioNode) {
                joiner.add(buildIoEntry(item));
            }
        } else if (ioNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = ioNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                joiner.add(buildIoEntry(entry.getValue()));
            }
        }
        String result = joiner.toString();
        return result.isEmpty() ? "none" : result;
    }

    private String buildIoEntry(JsonNode item) {
        String name = item.path("name").asText("");
        String type = item.path("type").asText("");
        boolean required = item.path("required").asBoolean(false);
        return name + ":" + type + (required ? " (required)" : "");
    }

    private String compactJson(JsonNode data) {
        if (data instanceof ObjectNode obj) {
            obj.remove("sourceFile");
            obj.remove("schema");
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to compact component json, storing as string", e);
            return data.toString();
        }
    }
}
