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
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the canonical {@code /schemas/actions?resource=...} catalog after semantic target
 * resolution. It proves a command's declared contract; item availability remains runtime-owned.
 */
@Slf4j
public class ResourceActionCatalogRetrievalService {

    private static final Pattern RESOURCE_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private static final long SUCCESS_CACHE_TTL_MS = 60_000L;
    private static final int SUCCESS_CACHE_MAX_ENTRIES = 256;

    private final ObjectMapper objectMapper;
    private final Map<ActionCatalogCacheKey, ActionCatalogCacheEntry> successCache = new ConcurrentHashMap<>();
    private volatile HttpClient httpClient;
    private final String configuredBaseUrl;
    private final long timeoutMs;
    private final GovernedPlatformRequestAuthorizationProvider authorizationProvider;

    public ResourceActionCatalogRetrievalService(
            ObjectMapper objectMapper,
            String configuredBaseUrl,
            long timeoutMs) {
        this(objectMapper, configuredBaseUrl, timeoutMs, GovernedPlatformRequestAuthorizationProvider.none());
    }

    public ResourceActionCatalogRetrievalService(
            ObjectMapper objectMapper,
            String configuredBaseUrl,
            long timeoutMs,
            GovernedPlatformRequestAuthorizationProvider authorizationProvider) {
        this.objectMapper = objectMapper;
        this.configuredBaseUrl = configuredBaseUrl;
        this.timeoutMs = timeoutMs;
        this.authorizationProvider = authorizationProvider == null
                ? GovernedPlatformRequestAuthorizationProvider.none()
                : authorizationProvider;
    }

    public ResourceActionCatalogFetchResult fetchCatalogResult(
            String resourceKey,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment) {
        String safeResourceKey = safeResourceKey(resourceKey);
        if (safeResourceKey == null) {
            return failure(
                    ResourceActionCatalogFetchResult.Status.INVALID_RESOURCE,
                    null,
                    null,
                    "RESOURCE_ACTION_CATALOG_INVALID_RESOURCE",
                    "Resource key must be a canonical Praxis resource identifier.");
        }
        String baseUrl = resolveBaseUrl(requestBaseUrl);
        if (baseUrl == null) {
            return failure(
                    ResourceActionCatalogFetchResult.Status.BASE_URL_NOT_CONFIGURED,
                    null,
                    null,
                    "RESOURCE_ACTION_CATALOG_BASE_URL_NOT_CONFIGURED",
                    "Base URL not configured for the action catalog endpoint.");
        }

        String url = baseUrl + "/schemas/actions?resource="
                + URLEncoder.encode(safeResourceKey, StandardCharsets.UTF_8);
        ActionCatalogCacheKey cacheKey = new ActionCatalogCacheKey(
                url,
                normalizeScope(tenantId),
                normalizeScope(userId),
                normalizeScope(environment));
        ResourceActionCatalogFetchResult cached = cached(cacheKey);
        if (cached != null) {
            Metrics.counter("ai_resource_action_catalog_fetch_cache_total", "status", "hit").increment();
            return cached;
        }
        Metrics.counter("ai_resource_action_catalog_fetch_cache_total", "status", "miss").increment();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .build();
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
                            GovernedPlatformRequest.Surface.RESOURCE_ACTION_CATALOG,
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
            JsonNode catalog;
            try {
                catalog = objectMapper.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return failure(
                        ResourceActionCatalogFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_ACTION_CATALOG_INVALID_RESPONSE",
                        e.getOriginalMessage());
            }
            if (catalog == null
                    || !catalog.isObject()
                    || !safeResourceKey.equals(catalog.path("resourceKey").asText(""))
                    || safeResourcePath(catalog.path("resourcePath").asText("")) == null
                    || !catalog.path("actions").isArray()) {
                return failure(
                        ResourceActionCatalogFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_ACTION_CATALOG_INVALID_RESPONSE",
                        "Catalog must contain the requested resourceKey, resourcePath and actions array.");
            }
            Metrics.counter("ai_resource_action_catalog_fetch_total", "status", "success").increment();
            ResourceActionCatalogFetchResult result = ResourceActionCatalogFetchResult.success(catalog, url);
            cache(cacheKey, result);
            return result;
        } catch (java.io.IOException e) {
            return failure(
                    ResourceActionCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_ACTION_CATALOG_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    ResourceActionCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_ACTION_CATALOG_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (Exception e) {
            return failure(
                    ResourceActionCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_ACTION_CATALOG_TRANSPORT_ERROR",
                    e.getMessage());
        }
    }

    private void addHeader(HttpRequest.Builder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.header(name, value.trim());
        }
    }

    private String safeResourceKey(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        String normalized = resourceKey.trim();
        return RESOURCE_KEY_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private String safeResourcePath(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String normalized = resourcePath.trim().replaceAll("/+$", "");
        if (!normalized.startsWith("/")
                || normalized.contains("..")
                || normalized.contains("?")
                || normalized.contains("#")
                || normalized.contains("://")) {
            return null;
        }
        return normalized;
    }

    private String resolveBaseUrl(String requestBaseUrl) {
        String candidate = configuredBaseUrl != null && !configuredBaseUrl.isBlank()
                ? configuredBaseUrl
                : requestBaseUrl;
        return candidate == null || candidate.isBlank()
                ? null
                : candidate.trim().replaceAll("/+$", "");
    }

    private ResourceActionCatalogFetchResult.Status classifyStatus(int statusCode) {
        if (statusCode == 400) {
            return ResourceActionCatalogFetchResult.Status.BAD_REQUEST;
        }
        if (statusCode == 401) {
            return ResourceActionCatalogFetchResult.Status.UNAUTHORIZED;
        }
        if (statusCode == 403) {
            return ResourceActionCatalogFetchResult.Status.FORBIDDEN;
        }
        if (statusCode == 404) {
            return ResourceActionCatalogFetchResult.Status.NOT_FOUND;
        }
        if (statusCode == 429 || statusCode >= 500) {
            return ResourceActionCatalogFetchResult.Status.UNAVAILABLE;
        }
        return ResourceActionCatalogFetchResult.Status.CLIENT_ERROR;
    }

    private String codeForStatus(int statusCode) {
        if (statusCode == 400) {
            return "RESOURCE_ACTION_CATALOG_REQUEST_REJECTED";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "RESOURCE_ACTION_CATALOG_ACCESS_DENIED";
        }
        if (statusCode == 404) {
            return "RESOURCE_ACTION_CATALOG_NOT_FOUND";
        }
        if (statusCode == 429 || statusCode >= 500) {
            return "RESOURCE_ACTION_CATALOG_PLATFORM_UNAVAILABLE";
        }
        return "RESOURCE_ACTION_CATALOG_CLIENT_ERROR";
    }

    private ResourceActionCatalogFetchResult failure(
            ResourceActionCatalogFetchResult.Status status,
            Integer httpStatus,
            String url,
            String code,
            String detail) {
        Metrics.counter(
                "ai_resource_action_catalog_fetch_total",
                "status",
                status.name().toLowerCase(Locale.ROOT)).increment();
        log.warn(
                "[ResourceActionCatalogRetrievalService] code={} status={} url={} detail={}",
                code,
                httpStatus,
                url,
                summarizeDetail(detail));
        return ResourceActionCatalogFetchResult.failure(status, httpStatus, url, code, detail);
    }

    private String summarizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "n/a";
        }
        String normalized = detail.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
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

    private ResourceActionCatalogFetchResult cached(ActionCatalogCacheKey key) {
        ActionCatalogCacheEntry entry = successCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            successCache.remove(key, entry);
            return null;
        }
        return defensiveCopy(entry.result());
    }

    private void cache(ActionCatalogCacheKey key, ResourceActionCatalogFetchResult result) {
        if (key == null || result == null || !result.isSuccess()) {
            return;
        }
        evictExpiredOrOldestIfRequired();
        successCache.put(
                key,
                new ActionCatalogCacheEntry(
                        defensiveCopy(result),
                        System.currentTimeMillis() + SUCCESS_CACHE_TTL_MS));
    }

    private void evictExpiredOrOldestIfRequired() {
        long now = System.currentTimeMillis();
        successCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (successCache.size() < SUCCESS_CACHE_MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<ActionCatalogCacheKey, ActionCatalogCacheEntry>> iterator = successCache.entrySet().iterator();
        if (iterator.hasNext()) {
            successCache.remove(iterator.next().getKey());
        }
    }

    private ResourceActionCatalogFetchResult defensiveCopy(ResourceActionCatalogFetchResult result) {
        if (result == null || !result.isSuccess()) {
            return result;
        }
        return ResourceActionCatalogFetchResult.success(result.getCatalog().deepCopy(), result.getEndpointUrl());
    }

    private String normalizeScope(String value) {
        return value == null ? "" : value.trim();
    }

    private record ActionCatalogCacheKey(String url, String tenantId, String userId, String environment) {}

    private record ActionCatalogCacheEntry(ResourceActionCatalogFetchResult result, long expiresAtEpochMs) {
        private boolean isExpired(long nowEpochMs) { return expiresAtEpochMs <= nowEpochMs; }
    }
}
