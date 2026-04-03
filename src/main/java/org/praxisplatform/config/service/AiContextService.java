package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.stereotype.Service;

/**
 * ServiÃ§o de montagem do contexto AI usado pela orquestraÃ§Ã£o metadata-driven.
 *
 * <p>
 * ResponsÃ¡vel por combinar template registrado, definiÃ§Ã£o de componente, estado atual da UI e
 * contexto de schema em um {@link AiContextDTO} coerente para o pipeline de AI. Quando dados
 * opcionais nÃ£o sÃ£o informados, o serviÃ§o aplica defaults determinÃ­sticos para manter o contrato
 * estÃ¡vel entre backend e consumidores.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AiContextService {

    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";

    private final AiRegistryTemplateService templateService;
    private final AiRegistryRepository aiRegistryRepository;
    private final ObjectMapper objectMapper;

    public AiContextDTO buildContext(
            String componentId,
            String componentType,
            String aiMode,
            Boolean requireSchema,
            JsonNode currentState,
            String resourcePath,
            AiSchemaContext schemaContext) {

        AiRegistryTemplateRecord templateRecord = resolveTemplateRecord(componentId);
        JsonNode componentDefinition = resolveComponentDefinition(componentId);

        String resolvedMode = normalizeMode(aiMode);
        boolean resolvedRequireSchema =
                requireSchema != null ? requireSchema : "create".equalsIgnoreCase(resolvedMode);
        JsonNode safeState = currentState != null ? currentState : objectMapper.createObjectNode();
        String resolvedResourcePath = resolveResourcePath(resourcePath, safeState);

        String description = extractDescription(componentDefinition, templateRecord);

        return AiContextDTO.builder()
                .componentId(componentId)
                .componentType(componentType)
                .aiMode(resolvedMode)
                .requireSchema(resolvedRequireSchema)
                .resourcePath(resolvedResourcePath)
                .description(description)
                .currentState(safeState)
                .componentDefinition(componentDefinition)
                .template(templateRecord)
                .schemaContext(schemaContext)
                .build();
    }

    public JsonNode parseJson(String raw) {
        if (raw == null) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private AiRegistryTemplateRecord resolveTemplateRecord(String componentId) {
        Optional<AiRegistry> systemTemplate = templateService.getTemplate(componentId);
        if (systemTemplate.isPresent()) {
            return mapTemplate(systemTemplate.get());
        }
        return null;
    }

    private JsonNode resolveComponentDefinition(String componentId) {
        Optional<AiRegistry> componentDefinitionEntry =
                aiRegistryRepository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        componentId,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SYSTEM_SCOPE_KEY);
        if (componentDefinitionEntry.isPresent()) {
            JsonNode payload = parseJson(componentDefinitionEntry.get().getPayload());
            JsonNode defNode = payload.get("componentDefinition");
            return defNode != null ? defNode : payload;
        }
        return null;
    }

    private String normalizeMode(String aiMode) {
        if (aiMode == null || aiMode.isBlank()) {
            return "edit";
        }
        return aiMode.trim().toLowerCase();
    }

    private String resolveResourcePath(String explicit, JsonNode currentState) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (currentState == null) {
            return null;
        }
        JsonNode resourceNode = currentState.at("/resource/path");
        if (resourceNode != null && !resourceNode.isNull() && !resourceNode.asText().isBlank()) {
            return resourceNode.asText();
        }
        JsonNode resourcePathNode = currentState.get("resourcePath");
        if (resourcePathNode != null && !resourcePathNode.isNull() && !resourcePathNode.asText().isBlank()) {
            return resourcePathNode.asText();
        }
        return null;
    }

    private AiRegistryTemplateRecord mapTemplate(AiRegistry config) {
        JsonNode payload = templateService.parsePayload(config);
        JsonNode configJson = payload != null ? payload.get("configJson") : null;
        JsonNode templateMeta = payload != null ? payload.get("templateMeta") : null;
        return AiRegistryTemplateRecord.builder()
                .componentId(config.getRegistryKey())
                .aiDescription(safeText(payload, "aiDescription"))
                .configJson(configJson)
                .templateMeta(templateMeta)
                .build();
    }

    private String safeText(JsonNode payload, String field) {
        if (payload == null) return null;
        JsonNode value = payload.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private String extractDescription(JsonNode componentDefinition, AiRegistryTemplateRecord templateRecord) {
        if (componentDefinition != null) {
            JsonNode descriptionNode = componentDefinition.get("description");
            if (descriptionNode != null && !descriptionNode.isNull()) {
                String description = descriptionNode.asText();
                if (description != null && !description.isBlank()) {
                    return description;
                }
            }
        }
        if (templateRecord != null && templateRecord.getAiDescription() != null
                && !templateRecord.getAiDescription().isBlank()) {
            return templateRecord.getAiDescription();
        }
        return "Dynamic Component";
    }
}

