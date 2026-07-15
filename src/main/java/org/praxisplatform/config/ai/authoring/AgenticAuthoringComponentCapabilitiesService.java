package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long DEFAULT_CACHE_TTL_MS = 300_000L;
    private static final long DEFAULT_REGISTRY_LOAD_TIMEOUT_MS = 5_000L;
    private static final int REGISTRY_LOAD_QUEUE_CAPACITY = 1;
    private static final AtomicInteger REGISTRY_LOADER_SEQUENCE = new AtomicInteger();

    private final AgenticAuthoringFormCapabilityCatalog formCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringTableCapabilityCatalog tableCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringFilterCapabilityCatalog filterCatalog = AgenticAuthoringFilterCapabilityCatalog.INSTANCE;
    private final AiRegistryRepository aiRegistryRepository;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMs;
    private final long registryLoadTimeoutMs;
    private final ThreadPoolExecutor registryLoadExecutor;
    private volatile CachedCapabilities cachedCapabilities;

    public AgenticAuthoringComponentCapabilitiesService() {
        this(null, new ObjectMapper());
    }

    public AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper) {
        this(aiRegistryRepository, objectMapper, DEFAULT_CACHE_TTL_MS);
    }

    public AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper,
            long cacheTtlMs) {
        this(
                aiRegistryRepository,
                objectMapper,
                cacheTtlMs,
                DEFAULT_REGISTRY_LOAD_TIMEOUT_MS);
    }

    AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper,
            long cacheTtlMs,
            long registryLoadTimeoutMs) {
        this.aiRegistryRepository = aiRegistryRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
        this.registryLoadTimeoutMs = Math.max(1L, registryLoadTimeoutMs);
        this.registryLoadExecutor = aiRegistryRepository == null ? null : createRegistryLoadExecutor();
    }

    public AgenticAuthoringComponentCapabilitiesResult listCapabilities() {
        if (cacheTtlMs <= 0L) {
            return buildCapabilities();
        }
        CachedCapabilities cached = cachedCapabilities;
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtEpochMs() >= now) {
            return cached.result();
        }
        synchronized (this) {
            cached = cachedCapabilities;
            now = System.currentTimeMillis();
            if (cached == null || cached.expiresAtEpochMs() < now) {
                cached = new CachedCapabilities(
                        buildCapabilities(),
                        now + cacheTtlMs);
                cachedCapabilities = cached;
            }
            return cached.result();
        }
    }

    public void invalidateCapabilitiesCache() {
        cachedCapabilities = null;
    }

    AgenticAuthoringComponentCapabilitiesResult listBuiltInCapabilities() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                RESULT_VERSION,
                List.of(
                        toCatalog(formCatalog.componentId(), formCatalog.version(), formCatalog.capabilities()),
                        toCatalog(tableCatalog.componentId(), tableCatalog.version(), tableCatalog.capabilities()),
                        toCatalog(chartCatalog.componentId(), chartCatalog.version(), chartCatalog.capabilities()),
                        toCatalog(filterCatalog.componentId(), filterCatalog.version(), filterCatalog.capabilities())));
    }

    private AgenticAuthoringComponentCapabilitiesResult buildCapabilities() {
        Map<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs =
                new LinkedHashMap<>();
        listBuiltInCapabilities().catalogs().forEach(catalog -> putCatalog(catalogs, catalog));
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
        if (aiRegistryRepository == null || registryLoadExecutor == null) {
            return List.of();
        }
        Future<List<AiRegistry>> registryLoad;
        try {
            registryLoad = registryLoadExecutor.submit(() ->
                    aiRegistryRepository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                            REGISTRY_TYPE_COMPONENT_DEF,
                            COMPONENT_DEF_COMPONENT_TYPE,
                            Scope.SYSTEM,
                            SYSTEM_SCOPE_KEY));
        } catch (RejectedExecutionException ex) {
            log.warn("Governed component capability loading is saturated; using built-in authoring catalogs only.");
            return List.of();
        }
        try {
            List<AiRegistry> registries = registryLoad.get(registryLoadTimeoutMs, TimeUnit.MILLISECONDS);
            return (registries == null ? List.<AiRegistry>of() : registries).stream()
                    .map(this::toRegistryCatalog)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (TimeoutException ex) {
            registryLoad.cancel(true);
            registryLoadExecutor.purge();
            log.warn(
                    "Governed component capability loading exceeded {} ms; using built-in authoring catalogs only.",
                    registryLoadTimeoutMs);
            return List.of();
        } catch (InterruptedException ex) {
            registryLoad.cancel(true);
            registryLoadExecutor.purge();
            Thread.currentThread().interrupt();
            log.warn("Governed component capability loading was interrupted; using built-in authoring catalogs only.");
            return List.of();
        } catch (ExecutionException | RuntimeException ex) {
            log.warn(
                    "Failed to load governed component capabilities from ai_registry; using built-in authoring catalogs only.",
                    ex.getCause() == null ? ex : ex.getCause());
            return List.of();
        }
    }

    @PreDestroy
    void shutdown() {
        if (registryLoadExecutor != null) {
            registryLoadExecutor.shutdownNow();
        }
    }

    private ThreadPoolExecutor createRegistryLoadExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "agentic-component-capabilities-registry-" + REGISTRY_LOADER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(REGISTRY_LOAD_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
        String term = AgenticAuthoringPresentationText.display(value);
        if (StringUtils.hasText(term)) {
            terms.add(term.trim());
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

    private record CachedCapabilities(
            AgenticAuthoringComponentCapabilitiesResult result,
            long expiresAtEpochMs) {
    }
}
