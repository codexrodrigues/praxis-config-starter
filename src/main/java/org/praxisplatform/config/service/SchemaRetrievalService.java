package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Busca schemas canônicos publicados por {@code /schemas/filtered} para enriquecer o contexto AI.
 *
 * <p>O servico resolve a base URL efetiva, chama o endpoint remoto com timeout controlado e
 * devolve um {@link SchemaFetchResult} com status normalizado para tratamento uniforme no fluxo de
 * orquestracao.
 */
@Service
@Slf4j
public class SchemaRetrievalService {

    private static final long SUCCESS_CACHE_TTL_MS = 60_000L;
    private static final int SUCCESS_CACHE_MAX_ENTRIES = 256;

    private final ObjectMapper objectMapper;
    private final GovernedPlatformRequestAuthorizationProvider authorizationProvider;
    private final Map<SchemaCacheKey, SchemaCacheEntry> successCache = new ConcurrentHashMap<>();
    private volatile HttpClient httpClient;

    @Value("${praxis.ai.schemas.base-url:}")
    private String schemasBaseUrl;

    @Value("${praxis.ai.schemas.timeout-ms:15000}")
    private long timeoutMs;

    @Autowired
    public SchemaRetrievalService(
            ObjectMapper objectMapper,
            ObjectProvider<GovernedPlatformRequestAuthorizationProvider> authorizationProviders) {
        this(objectMapper, authorizationProviders.getIfAvailable(
                GovernedPlatformRequestAuthorizationProvider::none));
    }

    public SchemaRetrievalService(ObjectMapper objectMapper) {
        this(objectMapper, GovernedPlatformRequestAuthorizationProvider.none());
    }

    public SchemaRetrievalService(
            ObjectMapper objectMapper,
            GovernedPlatformRequestAuthorizationProvider authorizationProvider) {
        this.objectMapper = objectMapper;
        this.authorizationProvider = authorizationProvider == null
                ? GovernedPlatformRequestAuthorizationProvider.none()
                : authorizationProvider;
    }

    public JsonNode fetchSchema(AiSchemaContext context, String requestBaseUrl) {
        SchemaFetchResult result = fetchSchemaResult(context, requestBaseUrl);
        return result.isSuccess() ? result.getSchema() : null;
    }

    public SchemaFetchResult fetchSchemaResult(AiSchemaContext context, String requestBaseUrl) {
        return fetchSchemaResult(context, requestBaseUrl, null, null, null);
    }

    public SchemaFetchResult fetchSchemaResult(
            AiSchemaContext context,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment) {
        if (context == null || context.getPath() == null
                || context.getOperation() == null || context.getSchemaType() == null) {
            return failure(
                    SchemaFetchResult.Status.INVALID_CONTEXT,
                    null,
                    null,
                    "SCHEMA_INVALID_CONTEXT",
                    "Missing path, operation or schemaType.");
        }
        String baseUrl = resolveBaseUrl(requestBaseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            return failure(
                    SchemaFetchResult.Status.BASE_URL_NOT_CONFIGURED,
                    null,
                    null,
                    "SCHEMA_BASE_URL_NOT_CONFIGURED",
                    "Base URL not configured for /schemas/filtered.");
        }

        String url = buildUrl(baseUrl, context);
        SchemaCacheKey cacheKey = new SchemaCacheKey(
                url,
                normalizeScope(tenantId),
                normalizeScope(userId),
                normalizeScope(environment));
        SchemaFetchResult cached = cached(cacheKey);
        if (cached != null) {
            Metrics.counter("ai_schema_fetch_cache_total", "status", "hit").increment();
            return cached;
        }
        Metrics.counter("ai_schema_fetch_cache_total", "status", "miss").increment();
        try {
            URI targetUri = URI.create(url);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .GET();
            addHeader(request, "X-Tenant-ID", tenantId);
            addHeader(request, "X-User-ID", userId);
            addHeader(request, "X-Env", environment);
            GovernedPlatformRequestAuthorization.apply(
                    request,
                    authorizationProvider,
                    new GovernedPlatformRequest(
                            GovernedPlatformRequest.Surface.SCHEMA_FILTERED,
                            GovernedPlatformRequest.parseOptionalBaseUri(requestBaseUrl),
                            targetUri,
                            tenantId,
                            userId,
                            environment));
            HttpResponse<String> response = httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return failure(
                        classifyStatus(response.statusCode()),
                        response.statusCode(),
                        url,
                        codeForStatus(response.statusCode()),
                        response.body());
            }
            JsonNode schema;
            try {
                schema = objectMapper.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return failure(
                        SchemaFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "SCHEMA_INVALID_RESPONSE",
                        e.getOriginalMessage());
            }
            if (schema == null || schema.isMissingNode() || schema.isNull()) {
                return failure(
                        SchemaFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "SCHEMA_INVALID_RESPONSE",
                        "Resolved payload was null or missing.");
            }
            Metrics.counter("ai_schema_fetch_total", "status", "success").increment();
            SchemaFetchResult result = SchemaFetchResult.success(schema, url);
            cache(cacheKey, result);
            return result;
        } catch (java.io.IOException e) {
            return failure(
                    SchemaFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "SCHEMA_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    SchemaFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "SCHEMA_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (Exception e) {
            return failure(
                    SchemaFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "SCHEMA_TRANSPORT_ERROR",
                    e.getMessage());
        }
    }

    private HttpClient httpClient() {
        HttpClient resolved = httpClient;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .build();
            }
            return httpClient;
        }
    }

    private SchemaFetchResult cached(SchemaCacheKey key) {
        SchemaCacheEntry entry = successCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtEpochMs() <= System.currentTimeMillis()) {
            successCache.remove(key, entry);
            return null;
        }
        return defensiveCopy(entry.result());
    }

    private void cache(SchemaCacheKey key, SchemaFetchResult result) {
        if (key == null || result == null || !result.isSuccess()) {
            return;
        }
        evictExpiredOrOldestIfRequired();
        successCache.put(
                key,
                new SchemaCacheEntry(
                        defensiveCopy(result),
                        System.currentTimeMillis() + SUCCESS_CACHE_TTL_MS));
    }

    private void evictExpiredOrOldestIfRequired() {
        long now = System.currentTimeMillis();
        successCache.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMs() <= now);
        if (successCache.size() < SUCCESS_CACHE_MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<SchemaCacheKey, SchemaCacheEntry>> iterator = successCache.entrySet().iterator();
        if (iterator.hasNext()) {
            successCache.remove(iterator.next().getKey());
        }
    }

    private SchemaFetchResult defensiveCopy(SchemaFetchResult result) {
        if (result == null || !result.isSuccess()) {
            return result;
        }
        return SchemaFetchResult.success(result.getSchema().deepCopy(), result.getEndpointUrl());
    }

    private String normalizeScope(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveBaseUrl(String requestBaseUrl) {
        if (schemasBaseUrl != null && !schemasBaseUrl.isBlank()) {
            return schemasBaseUrl.replaceAll("/+$", "");
        }
        if (requestBaseUrl != null && !requestBaseUrl.isBlank()) {
            return requestBaseUrl.replaceAll("/+$", "");
        }
        return null;
    }

    private String buildUrl(String baseUrl, AiSchemaContext context) {
        String path = encode(context.getPath());
        String op = encode(context.getOperation());
        String type = encode(context.getSchemaType());
        return baseUrl + "/schemas/filtered?path=" + path + "&operation=" + op + "&schemaType=" + type;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void addHeader(HttpRequest.Builder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.header(name, value.trim());
        }
    }

    private SchemaFetchResult.Status classifyStatus(int statusCode) {
        if (statusCode == 400) {
            return SchemaFetchResult.Status.BAD_REQUEST;
        }
        if (statusCode == 401) {
            return SchemaFetchResult.Status.UNAUTHORIZED;
        }
        if (statusCode == 403) {
            return SchemaFetchResult.Status.FORBIDDEN;
        }
        if (statusCode == 404) {
            return SchemaFetchResult.Status.NOT_FOUND;
        }
        if (statusCode == 429 || statusCode >= 500) {
            return SchemaFetchResult.Status.UNAVAILABLE;
        }
        return SchemaFetchResult.Status.CLIENT_ERROR;
    }

    private String codeForStatus(int statusCode) {
        if (statusCode == 400) {
            return "SCHEMA_REQUEST_REJECTED";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "SCHEMA_ACCESS_DENIED";
        }
        if (statusCode == 404) {
            return "SCHEMA_NOT_FOUND";
        }
        if (statusCode == 429 || statusCode >= 500) {
            return "SCHEMA_PLATFORM_UNAVAILABLE";
        }
        return "SCHEMA_CLIENT_ERROR";
    }

    private SchemaFetchResult failure(
            SchemaFetchResult.Status status,
            Integer httpStatus,
            String url,
            String code,
            String detail) {
        Metrics.counter("ai_schema_fetch_total", "status", status.name().toLowerCase()).increment();
        log.warn(
                "[SchemaRetrievalService] code={} status={} url={} detail={}",
                code,
                httpStatus,
                url,
                summarizeDetail(detail));
        return SchemaFetchResult.failure(status, httpStatus, url, code, detail);
    }

    private String summarizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "n/a";
        }
        String normalized = detail.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private record SchemaCacheKey(String url, String tenantId, String userId, String environment) {}

    private record SchemaCacheEntry(SchemaFetchResult result, long expiresAtEpochMs) {}
}
