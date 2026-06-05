package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

public class AgenticAuthoringPresentationAffordanceCatalogService {

    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";

    private final ObjectMapper objectMapper;
    private final Map<String, AgenticAuthoringPresentationAffordanceCatalog> catalogsByComponentId;
    private final AiRegistryRepository aiRegistryRepository;

    public AgenticAuthoringPresentationAffordanceCatalogService(
            ObjectMapper objectMapper,
            List<AgenticAuthoringPresentationAffordanceCatalog> catalogs) {
        this(objectMapper, catalogs, null);
    }

    public AgenticAuthoringPresentationAffordanceCatalogService(
            ObjectMapper objectMapper,
            List<AgenticAuthoringPresentationAffordanceCatalog> catalogs,
            AiRegistryRepository aiRegistryRepository) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        Map<String, AgenticAuthoringPresentationAffordanceCatalog> mapped = new LinkedHashMap<>();
        for (AgenticAuthoringPresentationAffordanceCatalog catalog
                : catalogs == null ? List.<AgenticAuthoringPresentationAffordanceCatalog>of() : catalogs) {
            if (catalog != null && catalog.componentId() != null && !catalog.componentId().isBlank()) {
                mapped.put(catalog.componentId(), catalog);
            }
        }
        this.catalogsByComponentId = Map.copyOf(mapped);
        this.aiRegistryRepository = aiRegistryRepository;
    }

    public static AgenticAuthoringPresentationAffordanceCatalogService defaultService(ObjectMapper objectMapper) {
        return defaultService(objectMapper, null);
    }

    public static AgenticAuthoringPresentationAffordanceCatalogService defaultService(
            ObjectMapper objectMapper,
            AiRegistryRepository aiRegistryRepository) {
        return new AgenticAuthoringPresentationAffordanceCatalogService(
                objectMapper,
                List.of(
                        AgenticAuthoringPresentationAffordanceCatalog.load("ai-authoring/table-presentation-affordances.v0.json"),
                        AgenticAuthoringPresentationAffordanceCatalog.load("ai-authoring/form-presentation-affordances.v0.json"),
                        AgenticAuthoringPresentationAffordanceCatalog.load("ai-authoring/chart-presentation-affordances.v0.json"),
                        AgenticAuthoringPresentationAffordanceCatalog.load("ai-authoring/filter-presentation-affordances.v0.json")),
                aiRegistryRepository);
    }

    Optional<AgenticAuthoringPresentationAffordanceCatalog> catalog(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return Optional.empty();
        }
        return registryCatalog(componentId)
                .or(() -> Optional.ofNullable(catalogsByComponentId.get(componentId)));
    }

    public JsonNode getCatalogSlice(String componentId) {
        return catalog(componentId)
                .map(catalog -> catalog.toJson(objectMapper))
                .orElseThrow(() -> new IllegalArgumentException(
                        "presentationAffordanceCatalog not found for component: " + componentId));
    }

    private Optional<AgenticAuthoringPresentationAffordanceCatalog> registryCatalog(String componentId) {
        if (aiRegistryRepository == null || componentId == null || componentId.isBlank()) {
            return Optional.empty();
        }
        Optional<AiRegistry> registry;
        try {
            registry = aiRegistryRepository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                    REGISTRY_TYPE_COMPONENT_DEF,
                    componentId,
                    COMPONENT_DEF_COMPONENT_TYPE,
                    Scope.SYSTEM,
                    SYSTEM_SCOPE_KEY);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return registry.flatMap(this::catalogFromRegistry);
    }

    private Optional<AgenticAuthoringPresentationAffordanceCatalog> catalogFromRegistry(AiRegistry registry) {
        JsonNode payload = readPayload(registry == null ? null : registry.getPayload());
        JsonNode manifest = payload.path("componentDefinition").path("jsonSchema").path("authoringManifest");
        JsonNode catalog = manifest.path("presentationAffordances");
        if (!catalog.isObject()) {
            return Optional.empty();
        }
        String fallbackComponentId = manifest.path("componentId").asText(registry == null ? "" : registry.getRegistryKey());
        return Optional.of(AgenticAuthoringPresentationAffordanceCatalog.fromJson(catalog, fallbackComponentId));
    }

    private JsonNode readPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
