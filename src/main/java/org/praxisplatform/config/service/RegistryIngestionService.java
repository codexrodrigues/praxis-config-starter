package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegistryIngestionService {

    private final AiRegistryRepository repository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagVectorStoreService ragVectorStoreService;
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";

    @Transactional
    public void ingestRegistry(RegistryIngestionRequest request, String tenantId, String environment) {
        if (request.getComponents() == null || request.getComponents().isEmpty()) {
            log.warn("No 'components' map found in registry request.");
            return;
        }

        String resolvedTenant = normalize(tenantId);
        String resolvedEnv = normalize(environment);
        String releaseId = RagDocumentIdentity.resolveReleaseId(null, request.getVersion(), request.getGeneratedAt());
        String requestVersion = normalize(request.getVersion());
        var definitions = request.getDefinitions();
        request.getComponents().forEach((componentId, entry) -> {
            try {
                AiRegistry def = toComponentDefinition(componentId, entry, definitions);
                upsertDefinition(def);
                Document ragDocument = toRagDocument(def, entry, resolvedTenant, resolvedEnv, releaseId, requestVersion);
                ragVectorStoreService.upsertDocuments(List.of(ragDocument));
                log.info("Ingested component: {}", componentId);
            } catch (Exception e) {
                log.error("Failed to process component: " + componentId, e);
                throw new ConfigurationIngestionException("Error processing component: " + componentId, e);
            }
        });
    }

    private AiRegistry toComponentDefinition(
            String componentId,
            RegistryIngestionRequest.ComponentEntry entry,
            com.fasterxml.jackson.databind.JsonNode definitions) {
        resolveConfigSchema(entry, definitions);
        String description = entry.getDescription();
        if (description == null || description.isEmpty()) {
            description = "Component " + componentId;
        }

        String summary = buildSummary(componentId, description, entry);
        List<Float> embedding = embeddingService.embed(summary);
        
        String payload = buildPayload(componentId, description, entry);

        return AiRegistry.builder()
                .registryType(REGISTRY_TYPE_COMPONENT_DEF)
                .registryKey(componentId)
                .componentType(COMPONENT_DEF_COMPONENT_TYPE)
                .scope(Scope.SYSTEM)
                .scopeKey("GLOBAL")
                .payload(payload)
                .embedding(embedding)
                .build();
    }

    private Document toRagDocument(
            AiRegistry definition,
            RegistryIngestionRequest.ComponentEntry entry,
            String tenantId,
            String environment,
            String releaseId,
            String requestVersion) {
        String description = entry.getDescription();
        if (description == null || description.isBlank()) {
            description = "Component " + definition.getRegistryKey();
        }
        String content = buildSummary(definition.getRegistryKey(), description, entry);
        String contentHash = RagDocumentIdentity.sha256(
                definition.getPayload() != null ? definition.getPayload() : content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.COMPONENT_DEFINITION);
        metadata.put(RagMetadataKeys.RESOURCE_ID, definition.getRegistryKey());
        metadata.put(RagMetadataKeys.COMPONENT_ID, definition.getRegistryKey());
        metadata.put(RagMetadataKeys.DOC_TYPE, RagResourceTypes.COMPONENT_DEFINITION);
        metadata.put(RagMetadataKeys.RELEASE_ID, releaseId);
        metadata.put(RagMetadataKeys.CONTENT_HASH, contentHash);
        metadata.put(RagMetadataKeys.CHUNK_INDEX, 0);
        metadata.put(RagMetadataKeys.DESCRIPTION, description);
        metadata.put(RagMetadataKeys.JSON_SCHEMA, toJson(entry));
        if (tenantId != null) {
            metadata.put(RagMetadataKeys.TENANT_ID, tenantId);
        }
        if (environment != null) {
            metadata.put(RagMetadataKeys.ENVIRONMENT, environment);
        }
        metadata.put(RagMetadataKeys.VERSION, hasText(requestVersion) ? requestVersion : definition.getVersion());
        metadata.entrySet().removeIf(entryMetadata -> Objects.isNull(entryMetadata.getValue()));
        return Document.builder()
                .id(RagDocumentIdentity.buildDocumentId(
                        tenantId,
                        environment,
                        definition.getRegistryKey(),
                        releaseId,
                        RagResourceTypes.COMPONENT_DEFINITION,
                        contentHash,
                        0))
                .text(content)
                .metadata(metadata)
                .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildSummary(String id, String description, RegistryIngestionRequest.ComponentEntry entry) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add("Component: " + id);
        joiner.add("Description: " + description);

        if (entry.getCategory() != null && !entry.getCategory().isBlank()) {
            joiner.add("Category: " + entry.getCategory());
        }

        if (hasText(entry.getConfigSchemaId())) {
            joiner.add("ConfigSchemaId: " + entry.getConfigSchemaId());
        }
        if (entry.getConfigSchema() != null && !entry.getConfigSchema().isNull()) {
            joiner.add("ConfigSchema: " + summarizeConfigSchema(entry.getConfigSchema()));
        }

        joiner.add("Inputs: " + summarizeIO(entry.getInputs()));
        joiner.add("Outputs: " + summarizeIO(entry.getOutputs()));
        joiner.add("Capabilities: " + summarizeCapabilities(entry.getCapabilities()));
        var extra = entry.getAdditionalProperties() != null
                ? entry.getAdditionalProperties()
                : java.util.Collections.<String, com.fasterxml.jackson.databind.JsonNode>emptyMap();
        joiner.add("ComponentCapabilities: " + summarizeAdditionalCapabilities(
                extra.get("componentCapabilities")));
        joiner.add("ComponentCapabilityNotes: " + summarizeNotes(
                extra.get("componentCapabilityNotes")));

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

    private String summarizeCapabilities(List<RegistryIngestionRequest.CapabilityEntry> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "none";
        }
        StringJoiner joiner = new StringJoiner("; ");
        int limit = 30;
        int count = 0;
        for (RegistryIngestionRequest.CapabilityEntry cap : capabilities) {
            if (cap == null || cap.getPath() == null || cap.getPath().isBlank()) {
                continue;
            }
            if (count >= limit) {
                joiner.add("... (+" + (capabilities.size() - limit) + " more)");
                break;
            }
            StringBuilder line = new StringBuilder(cap.getPath());
            if (cap.getDescription() != null && !cap.getDescription().isBlank()) {
                line.append(" - ").append(cap.getDescription());
            }
            joiner.add(line.toString());
            count++;
        }
        return joiner.toString();
    }

    private String summarizeAdditionalCapabilities(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray() || node.isNull()) {
            return "none";
        }
        try {
            List<RegistryIngestionRequest.CapabilityEntry> caps =
                    objectMapper.convertValue(node, new TypeReference<>() {});
            return summarizeCapabilities(caps);
        } catch (IllegalArgumentException ex) {
            return "unreadable";
        }
    }

    private String summarizeNotes(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return "none";
        }
        if (node.isArray()) {
            StringJoiner joiner = new StringJoiner("; ");
            int limit = 10;
            int count = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                if (count >= limit) {
                    joiner.add("... (+" + (node.size() - limit) + " more)");
                    break;
                }
                if (item != null && item.isTextual()) {
                    joiner.add(item.asText());
                    count++;
                }
            }
            return joiner.length() > 0 ? joiner.toString() : "none";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return "none";
    }

    private String summarizeConfigSchema(com.fasterxml.jackson.databind.JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) {
            return "none";
        }
        com.fasterxml.jackson.databind.JsonNode props = schemaNode.get("properties");
        if (props != null && props.isObject()) {
            return "properties=" + props.size();
        }
        if (schemaNode.isObject()) {
            return "keys=" + schemaNode.size();
        }
        return schemaNode.getNodeType().name().toLowerCase();
    }

    private String buildPayload(String componentId, String description, RegistryIngestionRequest.ComponentEntry entry) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode def = root.putObject("componentDefinition");
        def.put("id", componentId);
        def.put("description", description);
        var schemaNode = objectMapper.valueToTree(entry);
        var contextNode = entry.getComponentContext();
        if (schemaNode != null && schemaNode.isObject()) {
            ObjectNode schemaObject = (ObjectNode) schemaNode;
            if ((contextNode == null || contextNode.isNull()) && schemaObject.has("componentContext")) {
                contextNode = schemaObject.get("componentContext");
            }
            if (contextNode != null && !contextNode.isNull()) {
                def.set("componentContext", contextNode);
                schemaObject.remove("componentContext");
            }
            def.set("jsonSchema", schemaObject);
        } else {
            if (contextNode != null && !contextNode.isNull()) {
                def.set("componentContext", contextNode);
            }
            def.set("jsonSchema", schemaNode);
        }
        return toJson(root);
    }

    private void resolveConfigSchema(
            RegistryIngestionRequest.ComponentEntry entry,
            com.fasterxml.jackson.databind.JsonNode definitions) {
        if (entry == null) {
            return;
        }
        if (entry.getConfigSchema() != null && !entry.getConfigSchema().isNull()) {
            return;
        }
        if (!hasText(entry.getConfigSchemaId())) {
            return;
        }
        if (definitions == null || !definitions.isObject()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode schema = definitions.get(entry.getConfigSchemaId());
        if (schema != null && !schema.isNull()) {
            entry.setConfigSchema(schema);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new ConfigurationIngestionException("Failed to serialize component entry", e);
        }
    }

    private void upsertDefinition(AiRegistry config) {
        var existing = repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                config.getRegistryType(),
                config.getRegistryKey(),
                config.getComponentType(),
                config.getScope(),
                config.getScopeKey());

        if (existing.isPresent()) {
            AiRegistry db = existing.get();
            db.setPayload(config.getPayload());
            db.setEmbedding(config.getEmbedding());
            repository.save(db);
            return;
        }
        repository.save(config);
    }

}
