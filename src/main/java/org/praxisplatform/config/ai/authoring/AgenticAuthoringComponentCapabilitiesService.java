package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
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
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.projection.AiRegistryComponentCapabilityProjection;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.util.StringUtils;

public class AgenticAuthoringComponentCapabilitiesService {

    private static final Logger log = LoggerFactory.getLogger(AgenticAuthoringComponentCapabilitiesService.class);
    private static final String RESULT_VERSION = "0.1.0";
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";
    private static final int MAX_TRIGGER_TERMS = 18;
    private static final int MAX_OPERATION_CAPABILITIES = 128;
    private static final long DEFAULT_CACHE_TTL_MS = 60_000L;
    private static final long DEFAULT_REGISTRY_LOAD_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_DEGRADED_RETRY_MS = 5_000L;
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
    private final long degradedRetryMs;
    private final ThreadPoolExecutor registryLoadExecutor;
    private volatile CachedCapabilities cachedCapabilities;
    private volatile CachedCapabilities degradedCapabilities;
    private volatile AgenticAuthoringComponentCapabilitiesResult lastKnownGood;
    private volatile Instant lastSuccessfulRegistryLoadAt;

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
                DEFAULT_REGISTRY_LOAD_TIMEOUT_MS,
                DEFAULT_DEGRADED_RETRY_MS);
    }

    AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper,
            long cacheTtlMs,
            long registryLoadTimeoutMs) {
        this(
                aiRegistryRepository,
                objectMapper,
                cacheTtlMs,
                registryLoadTimeoutMs,
                DEFAULT_DEGRADED_RETRY_MS);
    }

    public AgenticAuthoringComponentCapabilitiesService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper,
            long cacheTtlMs,
            long registryLoadTimeoutMs,
            long degradedRetryMs) {
        this.aiRegistryRepository = aiRegistryRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.cacheTtlMs = Math.max(0L, cacheTtlMs);
        this.registryLoadTimeoutMs = Math.max(1L, registryLoadTimeoutMs);
        this.degradedRetryMs = Math.max(0L, degradedRetryMs);
        this.registryLoadExecutor = aiRegistryRepository == null ? null : createRegistryLoadExecutor();
    }

    public AgenticAuthoringComponentCapabilitiesResult listCapabilities() {
        long now = System.currentTimeMillis();
        CachedCapabilities cached = cacheTtlMs > 0L ? cachedCapabilities : null;
        if (isCurrent(cached, now)) {
            return cached.result();
        }
        CachedCapabilities degraded = cacheTtlMs > 0L ? degradedCapabilities : null;
        if (isCurrent(degraded, now)) {
            return degraded.result();
        }
        synchronized (this) {
            cached = cacheTtlMs > 0L ? cachedCapabilities : null;
            now = System.currentTimeMillis();
            if (isCurrent(cached, now)) {
                return cached.result();
            }
            degraded = cacheTtlMs > 0L ? degradedCapabilities : null;
            if (isCurrent(degraded, now)) {
                return degraded.result();
            }
            return refreshCapabilities();
        }
    }

    public synchronized void invalidateCapabilitiesCache() {
        cachedCapabilities = null;
        degradedCapabilities = null;
    }

    public synchronized AgenticAuthoringComponentCapabilitiesResult refreshCapabilitiesCache() {
        cachedCapabilities = null;
        degradedCapabilities = null;
        return refreshCapabilities();
    }

    AgenticAuthoringComponentCapabilitiesResult listBuiltInCapabilities() {
        return builtInCapabilities(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics(
                "built-in",
                false,
                null,
                Instant.now(),
                lastSuccessfulRegistryLoadAt));
    }

    AgenticAuthoringComponentCapabilitiesResult listBuiltInFallback(String reason) {
        return builtInCapabilities(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics(
                "built-in-fallback",
                true,
                reason,
                Instant.now(),
                lastSuccessfulRegistryLoadAt));
    }

    private AgenticAuthoringComponentCapabilitiesResult refreshCapabilities() {
        if (aiRegistryRepository == null || registryLoadExecutor == null) {
            AgenticAuthoringComponentCapabilitiesResult builtIn = listBuiltInCapabilities();
            cacheSuccessfulResult(builtIn, System.currentTimeMillis());
            return builtIn;
        }

        long loadStartedAtNanos = System.nanoTime();
        RegistryCatalogLoad load = registryCatalogs();
        long loadElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStartedAtNanos);
        if (load.successful()) {
            Instant loadedAt = Instant.now();
            lastSuccessfulRegistryLoadAt = loadedAt;
            AgenticAuthoringComponentCapabilitiesResult governed = mergeCapabilities(
                    load.catalogs(),
                    new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics(
                            "registry",
                            false,
                            null,
                            loadedAt,
                            loadedAt));
            lastKnownGood = governed;
            degradedCapabilities = null;
            cacheSuccessfulResult(governed, System.currentTimeMillis());
            log.info(
                    "Governed component capability catalog warmed (catalogs={}, loadElapsedMs={}, source=registry).",
                    governed.catalogs().size(),
                    loadElapsedMs);
            return governed;
        }

        cachedCapabilities = null;
        AgenticAuthoringComponentCapabilitiesResult degraded = degradedResult(load.failureReason());
        if (cacheTtlMs > 0L && degradedRetryMs > 0L) {
            degradedCapabilities = new CachedCapabilities(
                    degraded,
                    System.currentTimeMillis() + degradedRetryMs);
        } else {
            degradedCapabilities = null;
        }
        return degraded;
    }

    private void cacheSuccessfulResult(
            AgenticAuthoringComponentCapabilitiesResult result,
            long nowEpochMs) {
        cachedCapabilities = cacheTtlMs > 0L
                ? new CachedCapabilities(result, nowEpochMs + cacheTtlMs)
                : null;
    }

    private AgenticAuthoringComponentCapabilitiesResult degradedResult(String reason) {
        Instant resolvedAt = Instant.now();
        AgenticAuthoringComponentCapabilitiesResult governed = lastKnownGood;
        if (governed != null && isTransientFailure(reason)) {
            return new AgenticAuthoringComponentCapabilitiesResult(
                    RESULT_VERSION,
                    governed.catalogs(),
                    new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics(
                            "last-known-good",
                            true,
                            reason,
                            resolvedAt,
                            lastSuccessfulRegistryLoadAt));
        }
        if (!isTransientFailure(reason)) {
            lastKnownGood = null;
        }
        return builtInCapabilities(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics(
                "built-in-fallback",
                true,
                reason,
                resolvedAt,
                lastSuccessfulRegistryLoadAt));
    }

    private AgenticAuthoringComponentCapabilitiesResult builtInCapabilities(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics diagnostics) {
        return new AgenticAuthoringComponentCapabilitiesResult(
                RESULT_VERSION,
                List.of(
                        toCatalog(formCatalog.componentId(), formCatalog.version(), formCatalog.capabilities()),
                        toCatalog(tableCatalog.componentId(), tableCatalog.version(), tableCatalog.capabilities()),
                        toCatalog(chartCatalog.componentId(), chartCatalog.version(), chartCatalog.capabilities()),
                        toCatalog(filterCatalog.componentId(), filterCatalog.version(), filterCatalog.capabilities())),
                diagnostics);
    }

    private AgenticAuthoringComponentCapabilitiesResult mergeCapabilities(
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> registryCatalogs,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics diagnostics) {
        Map<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs =
                new LinkedHashMap<>();
        builtInCapabilities(diagnostics).catalogs().forEach(catalog -> putCatalog(catalogs, catalog));
        registryCatalogs.forEach(catalog -> putCatalog(catalogs, catalog));
        return new AgenticAuthoringComponentCapabilitiesResult(
                RESULT_VERSION,
                List.copyOf(catalogs.values()),
                diagnostics);
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

    private RegistryCatalogLoad registryCatalogs() {
        Future<List<AiRegistryComponentCapabilityProjection>> registryLoad;
        try {
            registryLoad = registryLoadExecutor.submit(() ->
                    aiRegistryRepository.findComponentCapabilityProjections(
                            REGISTRY_TYPE_COMPONENT_DEF,
                            COMPONENT_DEF_COMPONENT_TYPE,
                            Scope.SYSTEM.name(),
                            SYSTEM_SCOPE_KEY,
                            registryLoadTimeoutMs));
        } catch (RejectedExecutionException ex) {
            log.warn("Governed component capability loading is saturated; using built-in authoring catalogs only.");
            return RegistryCatalogLoad.failed("registry-load-saturated");
        }
        try {
            List<AiRegistryComponentCapabilityProjection> registries =
                    registryLoad.get(registryLoadTimeoutMs, TimeUnit.MILLISECONDS);
            if (registries == null || registries.isEmpty()) {
                log.warn("Governed component capability query returned no authoring manifests; using built-in catalogs.");
                return RegistryCatalogLoad.failed("registry-empty");
            }
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs = registries.stream()
                    .map(this::toRegistryCatalog)
                    .filter(Objects::nonNull)
                    .toList();
            if (catalogs.isEmpty()) {
                log.warn("Governed component capability query returned no valid catalogs; using built-in catalogs.");
                return RegistryCatalogLoad.failed("registry-catalog-empty");
            }
            return RegistryCatalogLoad.successful(catalogs);
        } catch (TimeoutException ex) {
            registryLoad.cancel(true);
            registryLoadExecutor.purge();
            log.warn(
                    "Governed component capability loading exceeded {} ms; using built-in authoring catalogs only.",
                    registryLoadTimeoutMs);
            return RegistryCatalogLoad.failed("registry-load-timeout");
        } catch (InterruptedException ex) {
            registryLoad.cancel(true);
            registryLoadExecutor.purge();
            Thread.currentThread().interrupt();
            log.warn("Governed component capability loading was interrupted; using built-in authoring catalogs only.");
            return RegistryCatalogLoad.failed("registry-load-interrupted");
        } catch (ExecutionException | RuntimeException ex) {
            log.warn(
                    "Failed to load governed component capabilities from ai_registry; using built-in authoring catalogs only.",
                    ex.getCause() == null ? ex : ex.getCause());
            return RegistryCatalogLoad.failed("registry-load-failed");
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

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog toRegistryCatalog(
            AiRegistryComponentCapabilityProjection registry) {
        JsonNode manifest = readJson(registry == null ? null : registry.authoringManifestJson());
        if (!manifest.isObject()) {
            return null;
        }
        String componentId = firstText(
                manifest,
                "componentId",
                registry == null ? null : registry.registryKey());
        if (!StringUtils.hasText(componentId)) {
            return null;
        }
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities = new ArrayList<>();
        capabilities.add(componentCapability(componentId, registry, manifest));
        int operationCount = 0;
        for (JsonNode operation : manifest.path("operations")) {
            if (!operation.isObject() || operationCount >= MAX_OPERATION_CAPABILITIES) {
                continue;
            }
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability =
                    operationCapability(componentId, operation, manifest.path("examples"));
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
            AiRegistryComponentCapabilityProjection registry,
            JsonNode manifest) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, componentId);
        addTerm(terms, registry == null ? null : registry.componentDescription());
        addTerm(terms, registry == null ? null : registry.friendlyName());
        addTerm(terms, registry == null ? null : registry.selector());
        addTerms(terms, readJson(registry == null ? null : registry.tagsJson()));
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
            JsonNode operation,
            JsonNode manifestExamples) {
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
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> semanticExamples =
                operationExamples(operationId, operation, manifestExamples);
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                operationId,
                operationId,
                limit(terms, MAX_TRIGGER_TERMS),
                List.of(),
                semanticExamples);
    }

    private List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> operationExamples(
            String operationId,
            JsonNode operation,
            JsonNode manifestExamples) {
        if (!manifestExamples.isArray()) {
            return List.of();
        }
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> examples = new ArrayList<>();
        for (JsonNode example : manifestExamples) {
            if (examples.size() >= 2
                    || !example.path("isPositive").asBoolean(false)
                    || !operationId.equals(text(example, "operationId"))) {
                continue;
            }
            String request = text(example, "request");
            if (!StringUtils.hasText(request)) {
                continue;
            }
            String semanticEffect = firstText(
                    operation,
                    "description",
                    firstText(operation, "title", operationId));
            List<String> constraints = new ArrayList<>();
            constraints.add("operationId=" + operationId);
            addConstraint(constraints, operation.path("affectedPaths"), "affectedPaths=");
            addConstraint(constraints, operation.path("validators"), "validators=");
            examples.add(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                    request,
                    semanticEffect,
                    List.copyOf(constraints)));
        }
        return List.copyOf(examples);
    }

    private void addConstraint(List<String> constraints, JsonNode values, String prefix) {
        if (!values.isArray() || values.isEmpty()) {
            return;
        }
        List<String> normalized = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                normalized.add(value.asText().trim());
            }
        });
        if (!normalized.isEmpty()) {
            constraints.add(prefix + String.join(",", normalized));
        }
    }

    private JsonNode readJson(String payload) {
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

    private boolean isCurrent(CachedCapabilities cached, long nowEpochMs) {
        return cached != null && cached.expiresAtEpochMs() >= nowEpochMs;
    }

    private boolean isTransientFailure(String reason) {
        return Set.of(
                        "registry-load-saturated",
                        "registry-load-timeout",
                        "registry-load-interrupted",
                        "registry-load-failed")
                .contains(reason);
    }

    private record RegistryCatalogLoad(
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs,
            String failureReason) {

        static RegistryCatalogLoad successful(
                List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs) {
            return new RegistryCatalogLoad(catalogs == null ? List.of() : List.copyOf(catalogs), null);
        }

        static RegistryCatalogLoad failed(String reason) {
            return new RegistryCatalogLoad(List.of(), reason);
        }

        boolean successful() {
            return failureReason == null;
        }
    }
}
