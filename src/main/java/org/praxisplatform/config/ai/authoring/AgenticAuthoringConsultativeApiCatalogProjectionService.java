package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.DomainCatalogIngestionService;
import org.springframework.util.StringUtils;

public class AgenticAuthoringConsultativeApiCatalogProjectionService {

    private static final int MAX_PROJECTED_RESOURCES = 6;
    private static final int SUFFICIENT_PROJECTED_RESOURCES = 4;
    private static final int MAX_DISCOVERY_QUERIES = 5;
    private static final int MAX_DISCOVERY_ITEMS = 8;
    private static final int MAX_CONTEXT_ITEMS_PER_RESOURCE = 80;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "ao", "aos", "as", "com", "como", "da", "das", "de", "do", "dos", "e", "em", "o", "os",
            "para", "por", "que", "quais", "qual", "sobre", "um", "uma", "uns", "umas", "api", "apis",
            "dado", "dados", "existe", "existem", "relacionado", "relacionados", "tela", "telas", "posso",
            "fazer", "criar", "aqui", "explique", "explicar", "consultar", "consulta", "consultas",
            "recomenda", "recomendar", "recomendacao", "recomendacoes", "recomendações", "criada", "criadas",
            "esta", "estao", "estão");

    private final Supplier<DomainCatalogIngestionService> domainCatalogIngestionServiceSupplier;
    private final ApiMetadataRepository apiMetadataRepository;
    private final String domainCatalogServiceKey;

    public AgenticAuthoringConsultativeApiCatalogProjectionService(
            Supplier<DomainCatalogIngestionService> domainCatalogIngestionServiceSupplier,
            ApiMetadataRepository apiMetadataRepository,
            String domainCatalogServiceKey) {
        this.domainCatalogIngestionServiceSupplier = domainCatalogIngestionServiceSupplier;
        this.apiMetadataRepository = apiMetadataRepository;
        this.domainCatalogServiceKey = StringUtils.hasText(domainCatalogServiceKey)
                ? domainCatalogServiceKey
                : AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY;
    }

    public AgenticAuthoringConsultativeApiCatalogProjection project(
            String query,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        DomainCatalogIngestionService domainCatalogIngestionService = domainCatalogIngestionServiceSupplier == null
                ? null
                : domainCatalogIngestionServiceSupplier.get();
        if (domainCatalogIngestionService == null) {
            return null;
        }
        Set<String> queryTokens = significantTokens(query);
        List<ApiMetadata> apiMetadata = apiMetadataRepository == null ? List.of() : apiMetadataRepository.findAll();
        Map<String, AgenticAuthoringCandidate> candidatesByResourceKey =
                candidateResources(candidates == null ? List.of() : candidates);
        for (String resourceKey : domainCatalogResourceKeys(domainCatalogIngestionService, query, tenantId, environment)) {
            candidatesByResourceKey.putIfAbsent(resourceKey, null);
        }
        List<String> warnings = new ArrayList<>();
        List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources = new ArrayList<>();
        for (Map.Entry<String, AgenticAuthoringCandidate> entry : candidatesByResourceKey.entrySet()) {
            String resourceKey = entry.getKey();
            AgenticAuthoringCandidate candidate = entry.getValue();
            DomainCatalogContextResponse context = latestContext(
                    domainCatalogIngestionService,
                    resourceKey,
                    tenantId,
                    environment);
            if (context == null || context.items() == null || context.items().isEmpty()) {
                continue;
            }
            if (!isRelevant(queryTokens, resourceKey, candidate, context.items())) {
                warnings.add("domain-api-consultative-projection-skipped-weak-match:" + resourceKey);
                continue;
            }
            resources.add(resource(resourceKey, candidate, context.items(), apiMetadata));
            if (resources.size() >= MAX_PROJECTED_RESOURCES) {
                break;
            }
        }
        if (resources.isEmpty()) {
            return null;
        }
        resources = resources.stream()
                .sorted(Comparator
                        .comparing((AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) ->
                                "analytical".equals(resource.role()) ? 1 : 0)
                        .thenComparing(resource -> resource.label().toLowerCase(Locale.ROOT)))
                .toList();
        return new AgenticAuthoringConsultativeApiCatalogProjection(
                safe(query),
                assistantMessage(resources),
                List.copyOf(resources),
                List.copyOf(warnings));
    }

    public AgenticAuthoringConsultativeApiCatalogProjection projectCompact(
            String query,
            String tenantId,
            String environment) {
        DomainCatalogIngestionService domainCatalogIngestionService = domainCatalogIngestionServiceSupplier == null
                ? null
                : domainCatalogIngestionServiceSupplier.get();
        if (domainCatalogIngestionService == null) {
            return null;
        }
        List<String> queries = significantTokens(query).stream()
                .limit(MAX_DISCOVERY_QUERIES)
                .toList();
        if (queries.isEmpty()) {
            return null;
        }
        Map<String, AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources = new LinkedHashMap<>();
        collectCompactResources(domainCatalogIngestionService, domainCatalogServiceKey, queries, blankToNull(tenantId), blankToNull(environment), resources);
        if (resources.isEmpty()) {
            collectCompactResources(domainCatalogIngestionService, domainCatalogServiceKey, queries, null, null, resources);
        }
        if (resources.isEmpty()) {
            collectCompactResources(domainCatalogIngestionService, null, queries, blankToNull(tenantId), blankToNull(environment), resources);
        }
        if (resources.isEmpty()) {
            return null;
        }
        List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> projected = resources.values().stream()
                .limit(MAX_PROJECTED_RESOURCES)
                .toList();
        return new AgenticAuthoringConsultativeApiCatalogProjection(
                safe(query),
                assistantMessage(projected),
                projected,
                List.of("domain-api-consultative-compact-projection-used"));
    }

    private void collectCompactResources(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String serviceKey,
            List<String> queries,
            String tenantId,
            String environment,
            Map<String, AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources.size() >= MAX_PROJECTED_RESOURCES) {
            return;
        }
        for (String catalogQuery : queries) {
            try {
                DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatest(
                        serviceKey,
                        null,
                        tenantId,
                        environment,
                        "node",
                        null,
                        null,
                        catalogQuery,
                        MAX_DISCOVERY_ITEMS);
                if (context == null || context.items() == null) {
                    continue;
                }
                for (DomainCatalogItemResponse item : context.items()) {
                    AgenticAuthoringConsultativeApiCatalogProjection.Resource resource = compactResource(item);
                    if (resource == null || resources.containsKey(resource.resourceKey())) {
                        continue;
                    }
                    resources.put(resource.resourceKey(), resource);
                    if (resources.size() >= MAX_PROJECTED_RESOURCES) {
                        return;
                    }
                }
            } catch (RuntimeException ex) {
                // Compact consultative evidence should never block the turn.
            }
        }
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource compactResource(DomainCatalogItemResponse item) {
        String resourceKey = resourceKeyFromCatalogItem(item);
        if (resourceKey.isBlank()) {
            return null;
        }
        JsonNode payload = item == null ? null : item.payload();
        String label = firstNonBlank(
                firstText(payload, "resourceLabel", "label", "title", "name"),
                humanLabel(resourceKey));
        String description = firstNonBlank(
                firstText(payload, "description", "summary", "helpText"),
                firstText(payload, "businessDescription", "purpose"));
        String nodeType = safe(item == null ? "" : item.nodeType());
        List<String> evidence = List.of(firstNonBlank(
                description,
                nodeType.isBlank() ? "" : "Catalogo de dominio: " + nodeType,
                "Recurso encontrado no catalogo de dominio."));
        return new AgenticAuthoringConsultativeApiCatalogProjection.Resource(
                resourceKey,
                resourcePath(resourceKey, null),
                label,
                resourceKey.contains("vw-") || resourceKey.contains("analytics") ? "analytical" : "operational",
                description,
                List.of(),
                List.of(),
                List.of(),
                evidence);
    }

    private List<String> domainCatalogResourceKeys(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String query,
            String tenantId,
            String environment) {
        if (domainCatalogIngestionService == null || significantTokens(query).isEmpty()) {
            return List.of();
        }
        List<String> queries = catalogDiscoveryQueries(query);
        Set<String> resourceKeys = discoverDomainCatalogResourceKeys(
                domainCatalogIngestionService,
                domainCatalogServiceKey,
                queries,
                tenantId,
                environment,
                new LinkedHashSet<>());
        if (resourceKeys.isEmpty()) {
            resourceKeys = discoverDomainCatalogResourceKeys(
                    domainCatalogIngestionService,
                    null,
                    queries,
                    tenantId,
                    environment,
                    resourceKeys);
        }
        return resourceKeys.stream().limit(MAX_PROJECTED_RESOURCES).toList();
    }

    private Set<String> discoverDomainCatalogResourceKeys(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String serviceKey,
            List<String> catalogQueries,
            String tenantId,
            String environment,
            Set<String> initialResourceKeys) {
        Set<String> resourceKeys = new LinkedHashSet<>(initialResourceKeys);
        List<Scope> scopes = discoveryScopes(tenantId, environment);
        List<Scope> primaryScopes = scopes.stream()
                .filter(scope -> scope.tenantId() != null || scope.environment() != null)
                .toList();
        if (primaryScopes.isEmpty()) {
            primaryScopes = scopes;
        }
        for (String catalogQuery : catalogQueries) {
            for (Scope scope : primaryScopes) {
                collectDomainCatalogResourceKeys(
                        domainCatalogIngestionService,
                        serviceKey,
                        catalogQuery,
                        scope,
                        resourceKeys);
                if (resourceKeys.size() >= sufficientResourceTarget(catalogQueries)) {
                    return resourceKeys;
                }
            }
        }
        if (!resourceKeys.isEmpty() || primaryScopes.equals(scopes)) {
            return resourceKeys;
        }
        for (String catalogQuery : catalogQueries) {
            for (Scope scope : scopes) {
                if (primaryScopes.contains(scope)) {
                    continue;
                }
                collectDomainCatalogResourceKeys(
                        domainCatalogIngestionService,
                        serviceKey,
                        catalogQuery,
                        scope,
                        resourceKeys);
                if (resourceKeys.size() >= sufficientResourceTarget(catalogQueries)) {
                    return resourceKeys;
                }
            }
        }
        return resourceKeys;
    }

    private void collectDomainCatalogResourceKeys(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String serviceKey,
            String catalogQuery,
            Scope scope,
            Set<String> resourceKeys) {
        try {
            DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatest(
                    serviceKey,
                    null,
                    scope.tenantId(),
                    scope.environment(),
                    "node",
                    null,
                    null,
                    catalogQuery,
                    MAX_DISCOVERY_ITEMS);
            if (context == null || context.items() == null || context.items().isEmpty()) {
                return;
            }
            for (DomainCatalogItemResponse item : context.items()) {
                String resourceKey = resourceKeyFromCatalogItem(item);
                if (!resourceKey.isBlank()) {
                    resourceKeys.add(resourceKey);
                }
                if (resourceKeys.size() >= MAX_PROJECTED_RESOURCES) {
                    return;
                }
            }
        } catch (RuntimeException ex) {
            // Keep consultative discovery resilient: explicit candidate projection can still answer.
        }
    }

    private List<String> catalogDiscoveryQueries(String query) {
        Set<String> queries = new LinkedHashSet<>();
        String safeQuery = safe(query);
        if (!safeQuery.isBlank()) {
            queries.add(safeQuery);
        }
        queries.addAll(significantTokens(query));
        return queries.stream().limit(MAX_DISCOVERY_QUERIES).toList();
    }

    private int sufficientResourceTarget(List<String> catalogQueries) {
        int cheapCoverage = Math.max(1, catalogQueries == null ? 1 : catalogQueries.size());
        return Math.min(MAX_PROJECTED_RESOURCES, Math.max(SUFFICIENT_PROJECTED_RESOURCES, cheapCoverage));
    }

    private String resourceKeyFromCatalogItem(DomainCatalogItemResponse item) {
        if (item == null) {
            return "";
        }
        JsonNode payload = item.payload();
        String resourceKey = payload == null ? "" : firstText(payload, "resourceKey");
        if (StringUtils.hasText(resourceKey)) {
            return resourceKey;
        }
        String itemKey = safe(item.itemKey());
        if (itemKey.isBlank()) {
            return "";
        }
        String[] parts = itemKey.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        int end = parts.length;
        for (int i = 0; i < parts.length; i++) {
            if (Set.of("concept", "field", "surface", "action", "policy", "binding", "alias").contains(parts[i])) {
                end = i;
                break;
            }
        }
        if (end < 2) {
            return "";
        }
        return String.join(".", java.util.Arrays.copyOfRange(parts, 0, end));
    }

    private DomainCatalogContextResponse latestContext(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String resourceKey,
            String tenantId,
            String environment) {
        for (Scope scope : discoveryScopes(tenantId, environment)) {
            DomainCatalogContextResponse context = latestContextForScope(
                    domainCatalogIngestionService,
                    resourceKey,
                    scope.tenantId(),
                    scope.environment());
            if (context != null && context.items() != null && !context.items().isEmpty()) {
                return context;
            }
        }
        return null;
    }

    private DomainCatalogContextResponse latestContextForScope(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String resourceKey,
            String tenantId,
            String environment) {
        try {
            return domainCatalogIngestionService.contextLatest(
                    domainCatalogServiceKey,
                    resourceKey,
                    tenantId,
                    environment,
                    null,
                    null,
                    null,
                    null,
                    MAX_CONTEXT_ITEMS_PER_RESOURCE);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private record Scope(String tenantId, String environment) {
    }

    private List<Scope> discoveryScopes(String tenantId, String environment) {
        List<Scope> scopes = new ArrayList<>();
        addScope(scopes, new Scope(blankToNull(tenantId), blankToNull(environment)));
        addScope(scopes, new Scope(null, null));
        return scopes;
    }

    private void addScope(List<Scope> scopes, Scope scope) {
        if (scope == null || scopes.contains(scope)) {
            return;
        }
        scopes.add(scope);
    }

    private Map<String, AgenticAuthoringCandidate> candidateResources(List<AgenticAuthoringCandidate> candidates) {
        Map<String, AgenticAuthoringCandidate> resources = new LinkedHashMap<>();
        for (AgenticAuthoringCandidate candidate : candidates) {
            String resourceKey = resourceKey(candidate == null ? null : candidate.resourcePath());
            if (resourceKey.isBlank()) {
                continue;
            }
            AgenticAuthoringCandidate current = resources.get(resourceKey);
            if (current == null || candidate.score() > current.score()) {
                resources.put(resourceKey, candidate);
            }
        }
        return resources;
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource resource(
            String resourceKey,
            AgenticAuthoringCandidate candidate,
            List<DomainCatalogItemResponse> items,
            List<ApiMetadata> apiMetadata) {
        String resourcePath = resourcePath(resourceKey, candidate);
        String conceptLabel = "";
        String description = "";
        List<AgenticAuthoringConsultativeApiCatalogProjection.Field> fields = new ArrayList<>();
        List<AgenticAuthoringConsultativeApiCatalogProjection.Action> actions = new ArrayList<>();
        for (DomainCatalogItemResponse item : items) {
            if (item == null || item.payload() == null || !"node".equals(safe(item.itemType()))) {
                continue;
            }
            String nodeType = safe(item.nodeType());
            if ("concept".equals(nodeType) && conceptLabel.isBlank()) {
                conceptLabel = firstText(item.payload(), "label", "name", "title");
                description = firstText(item.payload(), "description", "summary", "helpText");
                continue;
            }
            if ("field".equals(nodeType) && fields.size() < 12) {
                fields.add(new AgenticAuthoringConsultativeApiCatalogProjection.Field(
                        leafName(firstText(item.payload(), "nodeKey", "key", "name", "fieldName", "id")),
                        firstNonBlank(firstText(item.payload(), "label", "name", "title"), leafName(item.itemKey())),
                        firstText(item.payload(), "description", "summary", "helpText")));
                continue;
            }
            if ("action".equals(nodeType) && actions.size() < 8) {
                actions.add(new AgenticAuthoringConsultativeApiCatalogProjection.Action(
                        leafName(firstText(item.payload(), "nodeKey", "key", "name", "action", "id")),
                        firstNonBlank(firstText(item.payload(), "label", "name", "title"), leafName(item.itemKey())),
                        firstText(item.payload(), "description", "summary", "helpText")));
            }
        }
        List<AgenticAuthoringConsultativeApiCatalogProjection.Endpoint> endpoints = endpoints(resourcePath, apiMetadata);
        String label = firstNonBlank(conceptLabel, humanLabel(resourceKey));
        String role = role(resourceKey, endpoints);
        List<String> evidence = new ArrayList<>();
        evidence.add("domain_catalog_context");
        if (!endpoints.isEmpty()) {
            evidence.add("api_metadata");
        }
        if (candidate != null && candidate.evidence() != null) {
            candidate.evidence().stream()
                    .filter(Objects::nonNull)
                    .limit(4)
                    .forEach(evidence::add);
        }
        return new AgenticAuthoringConsultativeApiCatalogProjection.Resource(
                resourceKey,
                resourcePath,
                label,
                role,
                description,
                List.copyOf(fields),
                List.copyOf(actions),
                endpoints,
                evidence.stream().distinct().toList());
    }

    private List<AgenticAuthoringConsultativeApiCatalogProjection.Endpoint> endpoints(
            String resourcePath,
            List<ApiMetadata> apiMetadata) {
        if (resourcePath.isBlank() || apiMetadata == null || apiMetadata.isEmpty()) {
            return List.of();
        }
        return apiMetadata.stream()
                .filter(metadata -> metadata != null && StringUtils.hasText(metadata.getPath()))
                .filter(metadata -> metadata.getPath().equals(resourcePath) || metadata.getPath().startsWith(resourcePath + "/"))
                .sorted(Comparator
                        .comparing((ApiMetadata metadata) -> safe(metadata.getPath()))
                        .thenComparing(metadata -> safe(metadata.getMethod())))
                .map(metadata -> new AgenticAuthoringConsultativeApiCatalogProjection.Endpoint(
                        endpointKind(metadata),
                        safe(metadata.getMethod()).toUpperCase(Locale.ROOT),
                        safe(metadata.getPath()),
                        firstNonBlank(metadata.getSummary(), endpointLabel(metadata))))
                .limit(12)
                .toList();
    }

    private String endpointKind(ApiMetadata metadata) {
        String path = safe(metadata.getPath()).toLowerCase(Locale.ROOT);
        String method = safe(metadata.getMethod()).toUpperCase(Locale.ROOT);
        if (path.endsWith("/stats/group-by")) {
            return "groupByStats";
        }
        if (path.endsWith("/stats/timeseries")) {
            return "timeSeriesStats";
        }
        if (path.endsWith("/filter/cursor")) {
            return "cursorFilter";
        }
        if (path.endsWith("/filter")) {
            return "filter";
        }
        if (path.endsWith("/all")) {
            return "listAll";
        }
        if ("POST".equals(method) && !path.contains("/{")) {
            return "create";
        }
        if ("GET".equals(method) && path.contains("/{")) {
            return "detail";
        }
        if ("GET".equals(method)) {
            return "list";
        }
        return method.toLowerCase(Locale.ROOT);
    }

    private String endpointLabel(ApiMetadata metadata) {
        return switch (endpointKind(metadata)) {
            case "groupByStats" -> "agrupar dados para graficos";
            case "timeSeriesStats" -> "serie historica para graficos";
            case "cursorFilter", "filter" -> "filtrar registros";
            case "listAll", "list" -> "listar registros";
            case "create" -> "criar registro";
            case "detail" -> "consultar detalhe";
            default -> "operacao disponivel";
        };
    }

    private boolean isRelevant(
            Set<String> queryTokens,
            String resourceKey,
            AgenticAuthoringCandidate candidate,
            List<DomainCatalogItemResponse> items) {
        if (queryTokens.isEmpty()) {
            return true;
        }
        String haystack = normalizedText(resourceKey + " " + catalogLabels(items));
        long matches = queryTokens.stream().filter(haystack::contains).count();
        if (queryTokens.size() <= 2 && matches < queryTokens.size()) {
            return false;
        }
        if (matches >= 2) {
            return true;
        }
        if (matches >= 1 && candidate == null) {
            return true;
        }
        if (matches >= 1 && candidate != null && candidate.score() >= 0.50d) {
            return true;
        }
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
    }

    private String catalogLabels(List<DomainCatalogItemResponse> items) {
        StringBuilder text = new StringBuilder();
        for (DomainCatalogItemResponse item : items) {
            if (item == null) {
                continue;
            }
            text.append(' ').append(item.itemKey());
            text.append(' ').append(item.contextKey());
            text.append(' ').append(item.nodeType());
            JsonNode payload = item.payload();
            if (payload != null) {
                appendPayloadText(text, payload, "label", "name", "title", "description", "summary", "helpText");
            }
        }
        return text.toString();
    }

    private void appendPayloadText(StringBuilder text, JsonNode payload, String... fields) {
        if (text == null || payload == null || fields == null) {
            return;
        }
        for (String field : fields) {
            String value = firstText(payload, field);
            if (StringUtils.hasText(value)) {
                text.append(' ').append(value);
            }
        }
    }

    private Set<String> significantTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalizedText(text).split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private String assistantMessage(List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> visible = resources.stream().limit(5).toList();
        StringBuilder message = new StringBuilder("Encontrei ");
        if (visible.size() == 1) {
            message.append("uma fonte de dados confirmada para esse recorte: ");
        } else {
            message.append(visible.size()).append(" fontes de dados confirmadas para esse recorte: ");
        }
        message.append(humanJoin(visible.stream().map(AgenticAuthoringConsultativeApiCatalogProjection.Resource::label).toList()));
        message.append(". ");
        List<String> summaries = visible.stream()
                .map(this::resourceSummary)
                .filter(summary -> !summary.isBlank())
                .toList();
        if (!summaries.isEmpty()) {
            message.append(String.join(" ", summaries)).append(" ");
        }
        message.append("Para uma tela administrativa, eu começaria por uma lista filtrável com campos confirmados");
        boolean hasAnalytics = visible.stream().anyMatch(resource -> "analytical".equals(resource.role()));
        if (hasAnalytics) {
            message.append(" e, quando o objetivo for acompanhamento, usaria a visão analítica para indicadores e gráficos");
        }
        message.append(". Quando você pedir para criar, eu materializo usando apenas o que estiver confirmado no catálogo.");
        return message.toString();
    }

    private String resourceSummary(AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        StringBuilder summary = new StringBuilder();
        summary.append(resource.label()).append(": ");
        if (StringUtils.hasText(resource.description())) {
            summary.append(resource.description());
        } else if ("analytical".equals(resource.role())) {
            summary.append("boa para análises, indicadores e gráficos");
        } else {
            summary.append("boa para consultar e operar registros");
        }
        List<String> fields = resource.fields().stream()
                .map(AgenticAuthoringConsultativeApiCatalogProjection.Field::label)
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();
        if (!fields.isEmpty()) {
            summary.append(". Campos confirmados: ").append(humanJoin(fields));
        }
        List<String> endpointLabels = resource.endpoints().stream()
                .map(AgenticAuthoringConsultativeApiCatalogProjection.Endpoint::label)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(3)
                .toList();
        if (!endpointLabels.isEmpty()) {
            summary.append(". Operações disponíveis: ").append(humanJoin(endpointLabels));
        }
        summary.append(".");
        return summary.toString();
    }

    private String humanJoin(List<String> values) {
        List<String> clean = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.size() == 1) {
            return clean.get(0);
        }
        return String.join(", ", clean.subList(0, clean.size() - 1)) + " e " + clean.get(clean.size() - 1);
    }

    private String role(
            String resourceKey,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Endpoint> endpoints) {
        String normalized = safe(resourceKey).toLowerCase(Locale.ROOT);
        if (normalized.contains("analytics") || normalized.contains("indicadores") || normalized.contains(".vw-")) {
            return "analytical";
        }
        boolean hasWriteEndpoint = endpoints.stream()
                .anyMatch(endpoint -> "POST".equals(endpoint.method()) || "PUT".equals(endpoint.method()) || "PATCH".equals(endpoint.method()));
        return hasWriteEndpoint ? "operational" : "reference";
    }

    private String resourceKey(String resourcePath) {
        String path = safe(resourcePath);
        if (!path.startsWith("/api/")) {
            return "";
        }
        String resource = path.substring("/api/".length()).replaceAll("/+$", "");
        if (resource.isBlank() || resource.contains("/{")) {
            return "";
        }
        return resource.replace('/', '.');
    }

    private String resourcePath(String resourceKey, AgenticAuthoringCandidate candidate) {
        if (candidate != null && StringUtils.hasText(candidate.resourcePath())) {
            return candidate.resourcePath();
        }
        return "/api/" + safe(resourceKey).replace('.', '/');
    }

    private String humanLabel(String resourceKey) {
        String key = safe(resourceKey);
        int dot = key.lastIndexOf('.');
        if (dot >= 0) {
            key = key.substring(dot + 1);
        }
        return key
                .replace("vw-", "")
                .replace("-", " ")
                .trim();
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String leafName(String value) {
        String text = safe(value);
        int dot = text.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < text.length()) {
            text = text.substring(dot + 1);
        }
        return text.replace("-", " ").trim();
    }

    private String normalizedText(String text) {
        String normalized = Normalizer.normalize(safe(text), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        String safe = safe(value);
        return safe.isBlank() ? null : safe;
    }
}
