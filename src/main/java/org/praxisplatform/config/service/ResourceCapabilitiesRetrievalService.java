package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the canonical {@code GET /{resource}/capabilities} snapshot used to ground executable
 * resource operations after semantic intent resolution.
 */
@Slf4j
public class ResourceCapabilitiesRetrievalService {

    private final ObjectMapper objectMapper;
    private final String configuredBaseUrl;
    private final long timeoutMs;
    private final GovernedPlatformRequestAuthorizationProvider authorizationProvider;

    public ResourceCapabilitiesRetrievalService(
            ObjectMapper objectMapper,
            String configuredBaseUrl,
            long timeoutMs) {
        this(objectMapper, configuredBaseUrl, timeoutMs, GovernedPlatformRequestAuthorizationProvider.none());
    }

    public ResourceCapabilitiesRetrievalService(
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

    public ResourceCapabilitiesFetchResult fetchCapabilitiesResult(
            String resourcePath,
            String requestBaseUrl,
            String tenantId,
            String userId,
            String environment) {
        String safeResourcePath = safeResourcePath(resourcePath);
        if (safeResourcePath == null) {
            return failure(
                    ResourceCapabilitiesFetchResult.Status.INVALID_RESOURCE,
                    null,
                    null,
                    "RESOURCE_CAPABILITIES_INVALID_RESOURCE",
                    "Resource path must be a relative governed Praxis API path.");
        }
        String baseUrl = resolveBaseUrl(requestBaseUrl);
        if (baseUrl == null) {
            return failure(
                    ResourceCapabilitiesFetchResult.Status.BASE_URL_NOT_CONFIGURED,
                    null,
                    null,
                    "RESOURCE_CAPABILITIES_BASE_URL_NOT_CONFIGURED",
                    "Base URL not configured for the resource capabilities endpoint.");
        }

        String url = baseUrl + safeResourcePath + "/capabilities";
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
                            GovernedPlatformRequest.Surface.RESOURCE_CAPABILITIES,
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
            JsonNode capabilities;
            try {
                capabilities = objectMapper.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return failure(
                        ResourceCapabilitiesFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_CAPABILITIES_INVALID_RESPONSE",
                        e.getOriginalMessage());
            }
            if (capabilities == null || capabilities.isMissingNode() || capabilities.isNull()) {
                return failure(
                        ResourceCapabilitiesFetchResult.Status.INVALID_RESPONSE,
                        response.statusCode(),
                        url,
                        "RESOURCE_CAPABILITIES_INVALID_RESPONSE",
                        "Resolved payload was null or missing.");
            }
            Metrics.counter("ai_resource_capabilities_fetch_total", "status", "success").increment();
            return ResourceCapabilitiesFetchResult.success(capabilities, url);
        } catch (java.io.IOException e) {
            return failure(
                    ResourceCapabilitiesFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_CAPABILITIES_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(
                    ResourceCapabilitiesFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_CAPABILITIES_TRANSPORT_ERROR",
                    e.getMessage());
        } catch (Exception e) {
            return failure(
                    ResourceCapabilitiesFetchResult.Status.TRANSPORT_ERROR,
                    null,
                    url,
                    "RESOURCE_CAPABILITIES_TRANSPORT_ERROR",
                    e.getMessage());
        }
    }

    private void addHeader(HttpRequest.Builder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.header(name, value.trim());
        }
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
                || normalized.contains("://")
                || normalized.endsWith("/capabilities")) {
            return null;
        }
        return normalized;
    }

    private String resolveBaseUrl(String requestBaseUrl) {
        String candidate = configuredBaseUrl != null && !configuredBaseUrl.isBlank()
                ? configuredBaseUrl
                : requestBaseUrl;
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return candidate.trim().replaceAll("/+$", "");
    }

    private ResourceCapabilitiesFetchResult.Status classifyStatus(int statusCode) {
        if (statusCode == 400) {
            return ResourceCapabilitiesFetchResult.Status.BAD_REQUEST;
        }
        if (statusCode == 401) {
            return ResourceCapabilitiesFetchResult.Status.UNAUTHORIZED;
        }
        if (statusCode == 403) {
            return ResourceCapabilitiesFetchResult.Status.FORBIDDEN;
        }
        if (statusCode == 404) {
            return ResourceCapabilitiesFetchResult.Status.NOT_FOUND;
        }
        if (statusCode == 429 || statusCode >= 500) {
            return ResourceCapabilitiesFetchResult.Status.UNAVAILABLE;
        }
        return ResourceCapabilitiesFetchResult.Status.CLIENT_ERROR;
    }

    private String codeForStatus(int statusCode) {
        if (statusCode == 400) {
            return "RESOURCE_CAPABILITIES_REQUEST_REJECTED";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "RESOURCE_CAPABILITIES_ACCESS_DENIED";
        }
        if (statusCode == 404) {
            return "RESOURCE_CAPABILITIES_NOT_FOUND";
        }
        if (statusCode == 429 || statusCode >= 500) {
            return "RESOURCE_CAPABILITIES_PLATFORM_UNAVAILABLE";
        }
        return "RESOURCE_CAPABILITIES_CLIENT_ERROR";
    }

    private ResourceCapabilitiesFetchResult failure(
            ResourceCapabilitiesFetchResult.Status status,
            Integer httpStatus,
            String url,
            String code,
            String detail) {
        Metrics.counter(
                "ai_resource_capabilities_fetch_total",
                "status",
                status.name().toLowerCase(java.util.Locale.ROOT)).increment();
        log.warn(
                "[ResourceCapabilitiesRetrievalService] code={} status={} url={} detail={}",
                code,
                httpStatus,
                url,
                summarizeDetail(detail));
        return ResourceCapabilitiesFetchResult.failure(status, httpStatus, url, code, detail);
    }

    private String summarizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "n/a";
        }
        String normalized = detail.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
