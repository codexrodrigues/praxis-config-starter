package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Le valores atuais de option sources depois que a IA resolveu o recurso e o conceito do filtro.
 *
 * <p>A fonte de verdade continua sendo o host Praxis. O servico resolve o endpoint exclusivamente
 * por {@code /schemas/filtered}, exige governanca explicita para IA, preserva identidade do
 * principal e enumera valores sem usar a expressao do usuario como filtro lexical inicial.</p>
 */
@Service
public class LiveOptionValueRetrievalService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final ObjectMapper objectMapper;
    private final SchemaRetrievalService schemaRetrievalService;
    private final GovernedPlatformRequestAuthorizationProvider authorizationProvider;

    @Value("${praxis.ai.option-values.timeout-ms:15000}")
    private long timeoutMs;

    @Autowired
    public LiveOptionValueRetrievalService(
            ObjectMapper objectMapper,
            SchemaRetrievalService schemaRetrievalService,
            ObjectProvider<GovernedPlatformRequestAuthorizationProvider> authorizationProviders) {
        this(
                objectMapper,
                schemaRetrievalService,
                authorizationProviders.getIfAvailable(GovernedPlatformRequestAuthorizationProvider::none));
    }

    public LiveOptionValueRetrievalService(
            ObjectMapper objectMapper,
            SchemaRetrievalService schemaRetrievalService,
            GovernedPlatformRequestAuthorizationProvider authorizationProvider) {
        this.objectMapper = objectMapper;
        this.schemaRetrievalService = schemaRetrievalService;
        this.authorizationProvider = authorizationProvider == null
                ? GovernedPlatformRequestAuthorizationProvider.none()
                : authorizationProvider;
    }

    public LiveOptionValueRetrievalResult retrieve(
            LiveOptionValueRetrievalRequest request,
            AiPrincipalContext principalContext,
            String requestBaseUrl) {
        if (request == null || !StringUtils.hasText(request.resourcePath())) {
            return failure("", "option-values-resource-required", "A canonical resourcePath is required.");
        }
        if (principalContext == null
                || !StringUtils.hasText(principalContext.tenantId())
                || !StringUtils.hasText(principalContext.environment())) {
            return failure(request.resourcePath(), "option-values-principal-scope-required",
                    "Live option values require authenticated tenant and environment scope.");
        }
        URI baseUri = safeBaseUri(requestBaseUrl);
        if (baseUri == null) {
            return failure(request.resourcePath(), "option-values-base-url-required",
                    "A governed request base URL is required.");
        }

        String resourcePath = normalizeResourcePath(request.resourcePath());
        String filterSchemaPath = resourcePath.endsWith("/filter")
                ? resourcePath
                : resourcePath + "/filter";
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(
                AiSchemaContext.builder()
                        .path(filterSchemaPath)
                        .operation("post")
                        .schemaType("request")
                        .build(),
                requestBaseUrl,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
        if (!schemaResult.isSuccess()) {
            return failure(resourcePath, "option-values-filter-schema-unavailable",
                    "The canonical filter schema could not be resolved.");
        }

        FieldResolution fieldResolution = resolveOptionSourceField(
                schemaResult.getSchema(), request.semanticField(), request.concept());
        if (!fieldResolution.valid()) {
            return failure(resourcePath, fieldResolution.errorCode(), fieldResolution.errorMessage());
        }
        JsonNode governance = fieldResolution.property().path("x-domain-governance");
        String visibility = governance.path("aiUsage").path("visibility").asText("");
        if (!"allow".equals(visibility)) {
            return failure(resourcePath, "option-source-ai-governance-required",
                    "The selected option source field is not explicitly visible to AI reasoning.");
        }

        JsonNode optionSource = fieldResolution.property().path("x-ui").path("optionSource");
        String filterEndpoint = optionSource.path("filterEndpoint").asText("");
        String byIdsEndpoint = optionSource.path("byIdsEndpoint").asText("");
        URI filterEndpointUri = governedEndpoint(baseUri, filterEndpoint);
        if (filterEndpointUri == null) {
            return failure(resourcePath, "option-source-filter-endpoint-invalid",
                    "The canonical option source filter endpoint is missing or outside the governed origin.");
        }
        URI byIdsEndpointUri = governedEndpoint(baseUri, byIdsEndpoint);
        if (byIdsEndpointUri == null) {
            return failure(resourcePath, "option-source-by-ids-endpoint-invalid",
                    "The canonical option source by-ids endpoint is missing or outside the governed origin.");
        }

        if (request.confirmSelection()) {
            if (request.requestedValue() == null || !request.requestedValue().isArray()) {
                return failure(resourcePath, "option-source-selection-array-required",
                        "Canonical selected-value reload requires an array of IDs.");
            }
            return reloadSelected(
                    request,
                    principalContext,
                    baseUri,
                    byIdsEndpointUri,
                    resourcePath,
                    filterSchemaPath,
                    fieldResolution,
                    optionSource,
                    filterEndpoint,
                    byIdsEndpoint);
        }

        return enumerate(
                request,
                principalContext,
                baseUri,
                filterEndpointUri,
                resourcePath,
                filterSchemaPath,
                fieldResolution,
                optionSource,
                filterEndpoint,
                byIdsEndpoint);
    }

    private LiveOptionValueRetrievalResult reloadSelected(
            LiveOptionValueRetrievalRequest request,
            AiPrincipalContext principalContext,
            URI baseUri,
            URI endpointUri,
            String resourcePath,
            String filterSchemaPath,
            FieldResolution fieldResolution,
            JsonNode optionSource,
            String filterEndpoint,
            String byIdsEndpoint) {
        if (request.requestedValue().isEmpty()) {
            return failure(resourcePath, "option-source-selection-required",
                    "At least one selected option ID is required for canonical reload.");
        }
        if (request.requestedValue().size() > MAX_LIMIT) {
            return failure(resourcePath, "option-source-selection-limit-exceeded",
                    "The selected option ID count exceeds the governed reload limit.");
        }
        for (JsonNode id : request.requestedValue()) {
            if (id == null || id.isNull() || !id.isValueNode() || id.isContainerNode()) {
                return failure(resourcePath, "option-source-selection-id-invalid",
                        "Selected option IDs must be scalar canonical values.");
            }
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .build();
            HttpRequest.Builder builder;
            if (byIdsEndpoint.contains("/option-sources/")) {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.set("filter", request.dependencyFilters() != null && request.dependencyFilters().isObject()
                        ? request.dependencyFilters().deepCopy()
                        : objectMapper.createObjectNode());
                payload.set("ids", request.requestedValue().deepCopy());
                builder = HttpRequest.newBuilder()
                        .uri(endpointUri)
                        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
            } else {
                if (request.dependencyFilters() != null
                        && request.dependencyFilters().isObject()
                        && !request.dependencyFilters().isEmpty()) {
                    return failure(resourcePath, "option-source-contextual-reload-unsupported",
                            "The published by-ids endpoint cannot carry the required dependency context.");
                }
                URI getUri = withIds(endpointUri, request.requestedValue());
                builder = HttpRequest.newBuilder()
                        .uri(getUri)
                        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .header("Accept", "application/json")
                        .GET();
                endpointUri = getUri;
            }
            addHeader(builder, "X-Tenant-ID", principalContext.tenantId());
            addHeader(builder, "X-User-ID", principalContext.userId());
            addHeader(builder, "X-Env", principalContext.environment());
            GovernedPlatformRequestAuthorization.apply(
                    builder,
                    authorizationProvider,
                    new GovernedPlatformRequest(
                            GovernedPlatformRequest.Surface.OPTION_SOURCE_VALUES,
                            baseUri,
                            endpointUri,
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment()));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return failure(resourcePath, "option-source-by-ids-http-" + response.statusCode(),
                        "The governed option source rejected the selected-value reload.");
            }
            JsonNode payload = objectMapper.readTree(response.body());
            if (!payload.isArray()) {
                return failure(resourcePath, "option-source-by-ids-invalid-response",
                        "The option source did not return the canonical selected-value list.");
            }
            List<LiveOptionValueCandidate> candidates = new ArrayList<>();
            for (JsonNode item : payload) {
                JsonNode id = item.get("id");
                String label = item.path("label").asText("");
                if (id == null || id.isNull() || !StringUtils.hasText(label)) {
                    continue;
                }
                candidates.add(new LiveOptionValueCandidate(
                        id.deepCopy(),
                        label,
                        item.has("extra") ? item.path("extra").deepCopy() : null));
            }
            return new LiveOptionValueRetrievalResult(
                    true,
                    "praxis-live-option-values.v1",
                    resourcePath,
                    filterSchemaPath,
                    fieldResolution.field(),
                    optionSource.path("key").asText(""),
                    filterEndpoint,
                    byIdsEndpoint,
                    response.headers().firstValue("X-Data-Version").orElse(""),
                    "selected_ids_reload",
                    "canonical_by_ids_confirmation",
                    request.requestedValue().deepCopy(),
                    candidates.size(),
                    true,
                    List.copyOf(candidates),
                    "",
                    "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(resourcePath, "option-source-by-ids-interrupted",
                    "The selected-value reload was interrupted.");
        } catch (Exception exception) {
            return failure(resourcePath, "option-source-by-ids-read-failed",
                    "The selected option values could not be reloaded from the governed host.");
        }
    }

    private LiveOptionValueRetrievalResult enumerate(
            LiveOptionValueRetrievalRequest request,
            AiPrincipalContext principalContext,
            URI baseUri,
            URI endpointUri,
            String resourcePath,
            String filterSchemaPath,
            FieldResolution fieldResolution,
            JsonNode optionSource,
            String filterEndpoint,
            String byIdsEndpoint) {
        int limit = request.limit() > 0 ? Math.min(request.limit(), MAX_LIMIT) : DEFAULT_LIMIT;
        ObjectNode filters = request.dependencyFilters() != null && request.dependencyFilters().isObject()
                ? request.dependencyFilters().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode requestEnvelope = objectMapper.createObjectNode();
        requestEnvelope.set("filter", filters);
        List<LiveOptionValueCandidate> candidates = new ArrayList<>();
        int totalElements = 0;
        int totalPages = 1;
        String datasetVersion = "";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .build();
            for (int page = 0; page < totalPages && page < MAX_PAGES && candidates.size() < limit; page++) {
                URI pageUri = withPage(endpointUri, page, Math.min(PAGE_SIZE, limit - candidates.size()));
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(pageUri)
                        .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestEnvelope.toString()));
                addHeader(builder, "X-Tenant-ID", principalContext.tenantId());
                addHeader(builder, "X-User-ID", principalContext.userId());
                addHeader(builder, "X-Env", principalContext.environment());
                GovernedPlatformRequestAuthorization.apply(
                        builder,
                        authorizationProvider,
                        new GovernedPlatformRequest(
                                GovernedPlatformRequest.Surface.OPTION_SOURCE_VALUES,
                                baseUri,
                                pageUri,
                                principalContext.tenantId(),
                                principalContext.userId(),
                                principalContext.environment()));
                HttpResponse<String> response = client.send(
                        builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    return failure(resourcePath, "option-source-values-http-" + response.statusCode(),
                            "The governed option source rejected the live-value read.");
                }
                if (datasetVersion.isBlank()) {
                    datasetVersion = response.headers().firstValue("X-Data-Version").orElse("");
                }
                JsonNode payload = objectMapper.readTree(response.body());
                JsonNode content = payload.path("content");
                if (!content.isArray()) {
                    return failure(resourcePath, "option-source-values-invalid-response",
                            "The option source did not return a pageable option payload.");
                }
                totalPages = Math.max(1, payload.path("totalPages").asInt(1));
                totalElements = Math.max(content.size(), payload.path("totalElements").asInt(content.size()));
                for (JsonNode item : content) {
                    if (candidates.size() >= limit) {
                        break;
                    }
                    JsonNode id = item.get("id");
                    String label = item.path("label").asText("");
                    if (id == null || id.isNull() || !StringUtils.hasText(label)) {
                        continue;
                    }
                    candidates.add(new LiveOptionValueCandidate(
                            id.deepCopy(),
                            label,
                            item.has("extra") ? item.path("extra").deepCopy() : null));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(resourcePath, "option-source-values-interrupted",
                    "The live option value read was interrupted.");
        } catch (Exception exception) {
            return failure(resourcePath, "option-source-values-read-failed",
                    "The live option values could not be read from the governed host.");
        }
        boolean exhaustive = totalElements <= candidates.size();
        return new LiveOptionValueRetrievalResult(
                true,
                "praxis-live-option-values.v1",
                resourcePath,
                filterSchemaPath,
                fieldResolution.field(),
                optionSource.path("key").asText(""),
                filterEndpoint,
                byIdsEndpoint,
                datasetVersion,
                exhaustive ? "complete_enumeration" : "bounded_enumeration",
                "post_semantic_schema_ranking",
                request.requestedValue() == null ? null : request.requestedValue().deepCopy(),
                totalElements,
                exhaustive,
                List.copyOf(candidates),
                "",
                "");
    }

    private FieldResolution resolveOptionSourceField(JsonNode schema, String semanticField, String concept) {
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties == null || !properties.isObject()) {
            return FieldResolution.failure(
                    "option-values-filter-schema-invalid", "The filter schema has no properties.");
        }
        List<ScoredField> candidates = new ArrayList<>();
        properties.fields().forEachRemaining(entry -> {
            JsonNode property = entry.getValue();
            if (!property.path("x-ui").path("optionSource").isObject()) {
                return;
            }
            int score = fieldScore(entry.getKey(), property, semanticField, concept);
            if (score > 0) {
                candidates.add(new ScoredField(entry.getKey(), property, score));
            }
        });
        candidates.sort(Comparator.comparingInt(ScoredField::score).reversed());
        if (candidates.isEmpty()) {
            return FieldResolution.failure(
                    "option-source-field-unresolved",
                    "No option-source filter field matched the semantically resolved constraint.");
        }
        if (candidates.size() > 1 && candidates.get(0).score() == candidates.get(1).score()) {
            return FieldResolution.failure(
                    "option-source-field-ambiguous",
                    "More than one canonical option-source field matched the semantic constraint.");
        }
        ScoredField selected = candidates.get(0);
        return FieldResolution.success(selected.field(), selected.property());
    }

    private int fieldScore(String field, JsonNode property, String semanticField, String concept) {
        String canonical = normalize(field);
        String canonicalStem = canonicalFilterStem(canonical);
        String semantic = normalize(semanticField);
        String semanticConcept = normalize(concept);
        String xUiName = normalize(property.path("x-ui").path("name").asText(""));
        String label = normalize(property.path("x-ui").path("label").asText(""));
        String description = normalize(property.path("description").asText(""));
        int score = 0;
        if (!semantic.isBlank() && canonical.equals(semantic)) {
            score = Math.max(score, 1000);
        }
        if (!semantic.isBlank() && canonicalStem.equals(semantic)) {
            score = Math.max(score, 950);
        }
        if (!semantic.isBlank() && xUiName.equals(semantic)) {
            score = Math.max(score, 925);
        }
        if (!semantic.isBlank() && (canonicalStem.contains(semantic) || semantic.contains(canonicalStem))) {
            score = Math.max(score, 800);
        }
        if (!semantic.isBlank() && label.equals(semantic)) {
            score = Math.max(score, 775);
        }
        if (!semanticConcept.isBlank() && (canonicalStem.equals(semanticConcept) || label.equals(semanticConcept))) {
            score = Math.max(score, 750);
        }
        if (!semantic.isBlank() && (label.contains(semantic) || description.contains(semantic))) {
            score = Math.max(score, 600);
        }
        if (!semanticConcept.isBlank()
                && (canonicalStem.contains(semanticConcept)
                        || label.contains(semanticConcept)
                        || description.contains(semanticConcept))) {
            score = Math.max(score, 500);
        }
        return score;
    }

    private String canonicalFilterStem(String value) {
        if (value.endsWith("idsin") && value.length() > 5) {
            return value.substring(0, value.length() - 5);
        }
        if (value.endsWith("idin") && value.length() > 4) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    private URI governedEndpoint(URI baseUri, String endpoint) {
        if (!StringUtils.hasText(endpoint) || !endpoint.startsWith("/") || endpoint.startsWith("//")) {
            return null;
        }
        try {
            URI target = baseUri.resolve(endpoint);
            GovernedPlatformRequest context = new GovernedPlatformRequest(
                    GovernedPlatformRequest.Surface.OPTION_SOURCE_VALUES,
                    baseUri,
                    target,
                    null,
                    null,
                    null);
            return context.isSameOrigin() ? target : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private URI withPage(URI endpoint, int page, int size) {
        String separator = StringUtils.hasText(endpoint.getQuery()) ? "&" : "?";
        return URI.create(endpoint + separator + "page=" + page + "&size=" + Math.max(1, size));
    }

    private URI withIds(URI endpoint, JsonNode ids) {
        StringBuilder target = new StringBuilder(endpoint.toString());
        String separator = StringUtils.hasText(endpoint.getQuery()) ? "&" : "?";
        for (JsonNode id : ids) {
            target.append(separator)
                    .append("ids=")
                    .append(URLEncoder.encode(id.asText(), StandardCharsets.UTF_8));
            separator = "&";
        }
        return URI.create(target.toString());
    }

    private URI safeBaseUri(String value) {
        URI baseUri = GovernedPlatformRequest.parseOptionalBaseUri(value);
        if (baseUri == null) {
            return null;
        }
        String text = baseUri.toString().replaceAll("/+$", "") + "/";
        try {
            return URI.create(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeResourcePath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.replaceAll("/+$", "");
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "");
    }

    private void addHeader(HttpRequest.Builder request, String name, String value) {
        if (StringUtils.hasText(value)) {
            request.header(name, value.trim());
        }
    }

    private LiveOptionValueRetrievalResult failure(String resourcePath, String code, String message) {
        return LiveOptionValueRetrievalResult.failure(resourcePath, code, message);
    }

    private record ScoredField(String field, JsonNode property, int score) {
    }

    private record FieldResolution(
            boolean valid,
            String field,
            JsonNode property,
            String errorCode,
            String errorMessage) {

        private static FieldResolution success(String field, JsonNode property) {
            return new FieldResolution(true, field, property, "", "");
        }

        private static FieldResolution failure(String errorCode, String errorMessage) {
            return new FieldResolution(false, "", null, errorCode, errorMessage);
        }
    }
}
