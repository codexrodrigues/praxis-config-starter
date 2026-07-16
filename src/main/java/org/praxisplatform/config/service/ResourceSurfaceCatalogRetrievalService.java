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
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the canonical {@code /schemas/surfaces?resource=...} catalog after semantic target
 * resolution. The catalog is discovery evidence; endpoint authorization remains host-owned.
 */
@Slf4j
public class ResourceSurfaceCatalogRetrievalService {

    private static final Pattern RESOURCE_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private final ObjectMapper objectMapper;
    private final String configuredBaseUrl;
    private final long timeoutMs;
    private final GovernedPlatformRequestAuthorizationProvider authorizationProvider;

    public ResourceSurfaceCatalogRetrievalService(
            ObjectMapper objectMapper,
            String configuredBaseUrl,
            long timeoutMs) {
        this(objectMapper, configuredBaseUrl, timeoutMs, GovernedPlatformRequestAuthorizationProvider.none());
    }

    public ResourceSurfaceCatalogRetrievalService(
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

    public ResourceSurfaceCatalogFetchResult fetchCatalogResult(
            String resourceKey,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment) {
        String safeResourceKey = safeResourceKey(resourceKey);
        if (safeResourceKey == null) {
            return failure(
                    ResourceSurfaceCatalogFetchResult.Status.INVALID_RESOURCE,
                    null,
                    null,
                    "RESOURCE_SURFACE_CATALOG_INVALID_RESOURCE",
                    "Resource key must be a canonical Praxis resource identifier.");
        }
        String baseUrl = resolveBaseUrl(requestBaseUrl);
        if (baseUrl == null) {
            return failure(
                    ResourceSurfaceCatalogFetchResult.Status.BASE_URL_NOT_CONFIGURED,
                    null,
                    null,
                    "RESOURCE_SURFACE_CATALOG_BASE_URL_NOT_CONFIGURED",
                    "Base URL not configured for the surface catalog endpoint.");
        }

        String url = baseUrl + "/schemas/surfaces?resource="
                + URLEncoder.encode(safeResourceKey, StandardCharsets.UTF_8);
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
                            GovernedPlatformRequest.Surface.RESOURCE_SURFACE_CATALOG,
                            GovernedPlatformRequest.parseOptionalBaseUri(requestBaseUrl),
                            targetUri,
                            tenantId,
                            userId,
                            environment));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
                        ResourceSurfaceCatalogFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_SURFACE_CATALOG_INVALID_RESPONSE",
                        e.getOriginalMessage());
            }
            if (catalog == null
                    || !catalog.isObject()
                    || !safeResourceKey.equals(catalog.path("resourceKey").asText(""))
                    || safeResourcePath(catalog.path("resourcePath").asText("")) == null
                    || !catalog.path("surfaces").isArray()) {
                return failure(
                        ResourceSurfaceCatalogFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_SURFACE_CATALOG_INVALID_RESPONSE",
                        "Catalog must contain the requested resourceKey, resourcePath and surfaces array.");
            }
            Metrics.counter("ai_resource_surface_catalog_fetch_total", "status", "success").increment();
            return ResourceSurfaceCatalogFetchResult.success(catalog, url);
        } catch (java.io.IOException e) {
            return failure(
                    ResourceSurfaceCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_SURFACE_CATALOG_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    ResourceSurfaceCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_SURFACE_CATALOG_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (Exception e) {
            return failure(
                    ResourceSurfaceCatalogFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_SURFACE_CATALOG_TRANSPORT_ERROR",
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
        if (!normalized.startsWith("/api/")
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

    private ResourceSurfaceCatalogFetchResult.Status classifyStatus(int statusCode) {
        if (statusCode == 400) {
            return ResourceSurfaceCatalogFetchResult.Status.BAD_REQUEST;
        }
        if (statusCode == 401) {
            return ResourceSurfaceCatalogFetchResult.Status.UNAUTHORIZED;
        }
        if (statusCode == 403) {
            return ResourceSurfaceCatalogFetchResult.Status.FORBIDDEN;
        }
        if (statusCode == 404) {
            return ResourceSurfaceCatalogFetchResult.Status.NOT_FOUND;
        }
        if (statusCode == 429 || statusCode >= 500) {
            return ResourceSurfaceCatalogFetchResult.Status.UNAVAILABLE;
        }
        return ResourceSurfaceCatalogFetchResult.Status.CLIENT_ERROR;
    }

    private String codeForStatus(int statusCode) {
        if (statusCode == 400) {
            return "RESOURCE_SURFACE_CATALOG_REQUEST_REJECTED";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "RESOURCE_SURFACE_CATALOG_ACCESS_DENIED";
        }
        if (statusCode == 404) {
            return "RESOURCE_SURFACE_CATALOG_NOT_FOUND";
        }
        if (statusCode == 429 || statusCode >= 500) {
            return "RESOURCE_SURFACE_CATALOG_PLATFORM_UNAVAILABLE";
        }
        return "RESOURCE_SURFACE_CATALOG_CLIENT_ERROR";
    }

    private ResourceSurfaceCatalogFetchResult failure(
            ResourceSurfaceCatalogFetchResult.Status status,
            Integer httpStatus,
            String url,
            String code,
            String detail) {
        Metrics.counter(
                "ai_resource_surface_catalog_fetch_total",
                "status",
                status.name().toLowerCase(Locale.ROOT)).increment();
        log.warn(
                "[ResourceSurfaceCatalogRetrievalService] code={} status={} url={} detail={}",
                code,
                httpStatus,
                url,
                summarizeDetail(detail));
        return ResourceSurfaceCatalogFetchResult.failure(status, httpStatus, url, code, detail);
    }

    private String summarizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "n/a";
        }
        String normalized = detail.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
