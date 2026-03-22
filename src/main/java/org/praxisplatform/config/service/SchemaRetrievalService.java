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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaRetrievalService {

    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.schemas.base-url:}")
    private String schemasBaseUrl;

    @Value("${praxis.ai.schemas.timeout-ms:15000}")
    private long timeoutMs;

    public JsonNode fetchSchema(AiSchemaContext context, String requestBaseUrl) {
        SchemaFetchResult result = fetchSchemaResult(context, requestBaseUrl);
        return result.isSuccess() ? result.getSchema() : null;
    }

    public SchemaFetchResult fetchSchemaResult(AiSchemaContext context, String requestBaseUrl) {
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
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
            return SchemaFetchResult.success(schema, url);
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
}
