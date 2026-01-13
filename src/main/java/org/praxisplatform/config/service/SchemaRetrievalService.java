package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        if (context == null || context.getPath() == null
                || context.getOperation() == null || context.getSchemaType() == null) {
            return null;
        }
        String baseUrl = resolveBaseUrl(requestBaseUrl);
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("[SchemaRetrievalService] Base URL not configured for /schemas/filtered");
            return null;
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
                log.warn("[SchemaRetrievalService] /schemas/filtered HTTP {}: {}",
                        response.statusCode(), response.body());
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.warn("[SchemaRetrievalService] Failed to load schema from {}", url, e);
            return null;
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
}
