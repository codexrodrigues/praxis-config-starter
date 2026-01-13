package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class XaiAiService implements AiProvider {

    private static final String DEFAULT_BASE_URL = "https://api.x.ai/v1";

    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.xai.api-key:#{null}}")
    private String apiKey;

    @Value("${praxis.ai.xai.model:grok-2-latest}")
    private String model;

    @Value("${praxis.ai.xai.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${praxis.ai.temperature:0.1}")
    private double temperature;

    @Value("${praxis.ai.max-tokens:2048}")
    private int maxTokens;

    @Value("${praxis.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${praxis.ai.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${praxis.ai.retry.initial-delay-ms:500}")
    private long retryInitialDelayMs;

    @Value("${praxis.ai.retry.max-delay-ms:2000}")
    private long retryMaxDelayMs;

    @Override
    public JsonNode generateJson(String prompt) {
        return generateJson(prompt, null, null);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return generateJson(prompt, schema, null);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        String text = generateContent(prompt, true, schema, config);
        if (text == null || text.isBlank()) return null;
        String cleaned = sanitizeJsonText(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[XaiAiService] JSON parse failed, returning null.", e);
            return null;
        }
    }

    @Override
    public String generateText(String prompt) {
        return generateText(prompt, null);
    }

    @Override
    public String generateText(String prompt, AiCallConfig config) {
        return generateContent(prompt, false, null, config);
    }

    @Override
    public String getProviderName() {
        return "xai";
    }

    public List<AiProviderModel> listModels(AiCallConfig config) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new IllegalStateException("xAI API key not configured (praxis.ai.xai.api-key)");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl("/models")))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Authorization", "Bearer " + resolvedKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("xAI API HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<AiProviderModel> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = textOrNull(node, "id");
                if (id == null) {
                    continue;
                }
                models.add(AiProviderModel.builder()
                        .name(id)
                        .displayName(id)
                        .description(textOrNull(node, "owned_by"))
                        .supportedGenerationMethods(List.of("chat.completions"))
                        .build());
            }
            return models;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list xAI models", e);
        }
    }

    private String generateContent(String prompt, boolean jsonMode, AiJsonSchema schema, AiCallConfig config) {
        String apiKey = resolveApiKey(config);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("xAI API key not configured (praxis.ai.xai.api-key)");
        }

        String payload = buildPayload(prompt, jsonMode, schema, config);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        int attempts = Math.max(1, retryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl("/chat/completions")))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    if (isRetryableStatus(response.statusCode()) && attempt < attempts) {
                        long delay = computeBackoffDelayMs(attempt);
                        log.warn("[XaiAiService] HTTP {}, retrying in {} ms (attempt {}/{}).",
                                response.statusCode(), delay, attempt, attempts);
                        sleep(delay);
                        continue;
                    }
                    throw new IllegalStateException("xAI API HTTP " + response.statusCode() + ": " + response.body());
                }
                JsonNode root = objectMapper.readTree(response.body());
                String text = extractContent(root);
                return (text == null || text.isBlank()) ? null : text;
            } catch (Exception e) {
                if (!shouldRetry(e) || attempt >= attempts) {
                    throw new IllegalStateException("Failed to call xAI", e);
                }
                long delay = computeBackoffDelayMs(attempt);
                log.warn("[XaiAiService] Transient error, retrying in {} ms (attempt {}/{}).", delay, attempt, attempts);
                sleep(delay);
            }
        }
        return null;
    }

    private String buildPayload(String prompt, boolean jsonMode, AiJsonSchema schema, AiCallConfig config) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", resolveModel(config));
        payload.put("temperature", resolveTemperature(config));
        payload.put("max_tokens", resolveMaxTokens(config));

        ArrayNode messages = payload.putArray("messages");
        String finalPrompt = jsonMode ? buildJsonPrompt(prompt, schema) : prompt;
        messages.add(objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", finalPrompt));
        return payload.toString();
    }

    private String buildJsonPrompt(String prompt, AiJsonSchema schema) {
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\nReturn only valid JSON.");
        if (schema != null && schema.hasJsonSchema()) {
            sb.append("\nUse this JSON Schema:\n");
            sb.append(schema.jsonSchema());
        }
        return sb.toString();
    }

    private String resolveApiKey(AiCallConfig config) {
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey().trim();
        }
        return apiKey;
    }

    private String resolveModel(AiCallConfig config) {
        if (config != null && config.getModel() != null && !config.getModel().isBlank()) {
            return config.getModel().trim();
        }
        return model;
    }

    private double resolveTemperature(AiCallConfig config) {
        if (config != null && config.getTemperature() != null) {
            return config.getTemperature();
        }
        return temperature;
    }

    private int resolveMaxTokens(AiCallConfig config) {
        if (config != null && config.getMaxTokens() != null && config.getMaxTokens() > 0) {
            return config.getMaxTokens();
        }
        return maxTokens;
    }

    private String buildUrl(String path) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private String extractContent(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        String content = choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            content = choice.path("text").asText(null);
        }
        return content;
    }

    private boolean shouldRetry(Exception exception) {
        if (exception instanceof java.net.http.HttpTimeoutException) {
            return true;
        }
        return exception instanceof java.io.IOException;
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504;
    }

    private long computeBackoffDelayMs(int attempt) {
        long base = Math.max(0, retryInitialDelayMs);
        long max = Math.max(base, retryMaxDelayMs);
        long delay = base > 0 ? base * (1L << Math.max(0, attempt - 1)) : 0L;
        delay = Math.min(delay, max);
        if (delay <= 0) {
            return 0L;
        }
        double jitter = 0.2d * ThreadLocalRandom.current().nextDouble();
        return (long) (delay * (1 + jitter));
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String sanitizeJsonText(String text) {
        String cleaned = text.replaceAll("```json\\n?|\\n?```", "").trim();
        int brace = cleaned.indexOf('{');
        int bracket = cleaned.indexOf('[');
        int firstOpen;
        if (brace == -1) {
            firstOpen = bracket;
        } else if (bracket == -1) {
            firstOpen = brace;
        } else {
            firstOpen = Math.min(brace, bracket);
        }
        int lastCloseBrace = cleaned.lastIndexOf('}');
        int lastCloseBracket = cleaned.lastIndexOf(']');
        int lastClose = Math.max(lastCloseBrace, lastCloseBracket);
        if (firstOpen >= 0 && lastClose > firstOpen) {
            cleaned = cleaned.substring(firstOpen, lastClose + 1);
        }
        return cleaned.trim();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }
}
