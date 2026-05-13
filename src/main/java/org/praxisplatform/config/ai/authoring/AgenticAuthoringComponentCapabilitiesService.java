package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.util.StringUtils;

public class AgenticAuthoringComponentCapabilitiesService {

    private static final Logger log = LoggerFactory.getLogger(AgenticAuthoringComponentCapabilitiesService.class);
    private static final String RESULT_VERSION = "0.1.0";
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";
    private static final int MAX_TRIGGER_TERMS = 18;
    private static final int MAX_OPERATION_CAPABILITIES = 16;

    private final AgenticAuthoringFormCapabilityCatalog formCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringTableCapabilityCatalog tableCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringFilterCapabilityCatalog filterCatalog = AgenticAuthoringFilterCapabilityCatalog.INSTANCE;
    private final AiRegistryRepository aiRegistryRepository;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringComponentCapabilitiesService() {
        this(null, new ObjectMapper());
    }

    public AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper) {
        this.aiRegistryRepository = aiRegistryRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public AgenticAuthoringComponentCapabilitiesResult listCapabilities() {
        Map<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs =
                new LinkedHashMap<>();
        putCatalog(catalogs, toCatalog(formCatalog.componentId(), formCatalog.version(), formCatalog.capabilities()));
        putCatalog(catalogs, toCatalog(tableCatalog.componentId(), tableCatalog.version(), tableCatalog.capabilities()));
        putCatalog(catalogs, toCatalog(chartCatalog.componentId(), chartCatalog.version(), chartCatalog.capabilities()));
        putCatalog(catalogs, toCatalog(filterCatalog.componentId(), filterCatalog.version(), filterCatalog.capabilities()));
        registryCatalogs().forEach(catalog -> putCatalog(catalogs, catalog));
        return new AgenticAuthoringComponentCapabilitiesResult(
                RESULT_VERSION,
                List.copyOf(catalogs.values()));
    }

    private void putCatalog(
            Map<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
            return;
        }
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog existing =
                catalogs.get(catalog.componentId());
        if (existing == null) {
            catalogs.put(catalog.componentId(), catalog);
            return;
        }
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities = new ArrayList<>();
        capabilities.addAll(existing.capabilities() == null ? List.of() : existing.capabilities());
        Set<String> existingIds = new LinkedHashSet<>();
        capabilities.stream()
                .map(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::id)
                .filter(StringUtils::hasText)
                .forEach(existingIds::add);
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : nullToEmpty(catalog.capabilities())) {
            if (capability != null && existingIds.add(capability.id())) {
                capabilities.add(capability);
            }
        }
        catalogs.put(catalog.componentId(), new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                existing.componentId(),
                StringUtils.hasText(catalog.version()) ? catalog.version() : existing.version(),
                List.copyOf(capabilities)));
    }

    private List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> registryCatalogs() {
        if (aiRegistryRepository == null) {
            return List.of();
        }
        try {
            return aiRegistryRepository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                            REGISTRY_TYPE_COMPONENT_DEF,
                            COMPONENT_DEF_COMPONENT_TYPE,
                            Scope.SYSTEM,
                            SYSTEM_SCOPE_KEY)
                    .stream()
                    .map(this::toRegistryCatalog)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Failed to load governed component capabilities from ai_registry; using built-in authoring catalogs only.", ex);
            return List.of();
        }
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog toRegistryCatalog(AiRegistry registry) {
        JsonNode payload = readPayload(registry == null ? null : registry.getPayload());
        JsonNode definition = payload.path("componentDefinition");
        JsonNode schema = definition.path("jsonSchema");
        JsonNode manifest = schema.path("authoringManifest");
        if (!manifest.isObject()) {
            return null;
        }
        String componentId = firstText(manifest, "componentId", registry == null ? null : registry.getRegistryKey());
        if (!StringUtils.hasText(componentId)) {
            return null;
        }
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities = new ArrayList<>();
        capabilities.add(componentCapability(componentId, definition, schema, manifest));
        int operationCount = 0;
        for (JsonNode operation : manifest.path("operations")) {
            if (!operation.isObject() || operationCount >= MAX_OPERATION_CAPABILITIES) {
                continue;
            }
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability =
                    operationCapability(componentId, operation);
            if (capability != null) {
                capabilities.add(capability);
                operationCount++;
            }
        }
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                componentId,
                firstText(manifest, "manifestVersion", firstText(manifest, "schemaVersion", "registry")),
                List.copyOf(capabilities));
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapability componentCapability(
            String componentId,
            JsonNode definition,
            JsonNode schema,
            JsonNode manifest) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, componentId);
        addTerm(terms, text(definition, "description"));
        addTerm(terms, text(schema, "friendlyName"));
        addTerm(terms, text(schema, "selector"));
        addTerms(terms, schema.path("tags"));
        addTerms(terms, manifest.path("editableTargets"), "kind");
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                "component.author",
                "author_component",
                limit(terms, MAX_TRIGGER_TERMS),
                List.of(),
                List.of());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapability operationCapability(
            String componentId,
            JsonNode operation) {
        String operationId = text(operation, "operationId");
        if (!StringUtils.hasText(operationId)) {
            return null;
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, componentId);
        addTerm(terms, operationId);
        addTerm(terms, text(operation, "title"));
        addTerm(terms, text(operation, "label"));
        addTerm(terms, text(operation, "description"));
        addTerm(terms, text(operation.path("target"), "kind"));
        addTerms(terms, operation.path("effects"), "kind");
        addTerms(terms, operation.path("effects"), "handler");
        operation.path("inputSchema").path("properties").fieldNames().forEachRemaining(terms::add);
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                operationId,
                operationId,
                limit(terms, MAX_TRIGGER_TERMS),
                List.of(),
                List.of());
    }

    private JsonNode readPayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog toCatalog(
            String componentId,
            String version,
            List<AgenticAuthoringComponentCapabilityCatalog.ComponentCapability> capabilities) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                componentId,
                version,
                capabilities.stream().map(this::toCapability).toList());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapability toCapability(
            AgenticAuthoringComponentCapabilityCatalog.ComponentCapability capability) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                capability.id(),
                capability.changeKind(),
                capability.triggerTerms(),
                capability.fieldAliases().stream().map(this::toFieldAlias).toList(),
                capability.examples().stream().map(this::toExample).toList());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample toExample(
            AgenticAuthoringComponentCapabilityCatalog.ComponentCapabilityExample example) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                example.prompt(),
                example.intent(),
                example.configHints());
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias toFieldAlias(
            AgenticAuthoringComponentCapabilityCatalog.ComponentFieldAlias alias) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias(
                alias.field(),
                alias.aliases());
    }

    private List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> nullToEmpty(
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities) {
        return capabilities == null ? List.of() : capabilities;
    }

    private String firstText(JsonNode node, String fieldName, String fallback) {
        String value = text(node, fieldName);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private void addTerm(Set<String> terms, String value) {
        if (StringUtils.hasText(value)) {
            terms.add(value.trim());
        }
    }

    private void addTerms(Set<String> terms, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                addTerm(terms, item.asText());
            }
        }
    }

    private void addTerms(Set<String> terms, JsonNode node, String fieldName) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            addTerm(terms, text(item, fieldName));
        }
    }

    private List<String> limit(LinkedHashSet<String> values, int limit) {
        return values.stream()
                .filter(StringUtils::hasText)
                .limit(limit)
                .toList();
    }
}
