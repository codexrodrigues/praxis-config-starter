package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Schema;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
public class GeminiAiService implements AiProvider {

    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.gemini.api-key:#{null}}")
    private String aiApiKey;

    @Value("${embedding.gemini.api-key:#{null}}")
    private String embeddingApiKey;

    @Value("${praxis.ai.gemini.model:gemini-2.0-flash}")
    private String model;

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
            log.warn("[GeminiAiService] JSON parse failed, returning null.", e);
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
        return "gemini";
    }

    public List<AiProviderModel> listModels(AiCallConfig config) {
        String apiKey = resolveApiKey(config);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key not configured (praxis.ai.gemini.api-key)");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Gemini API HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode modelsNode = root.path("models");
            if (!modelsNode.isArray()) {
                return List.of();
            }
            List<AiProviderModel> models = new ArrayList<>();
            for (JsonNode node : modelsNode) {
                String rawName = textOrNull(node, "name");
                String name = stripPrefix(rawName, "models/");
                List<String> methods = listStrings(node.get("supportedGenerationMethods"));
                models.add(AiProviderModel.builder()
                        .name(name)
                        .displayName(textOrNull(node, "displayName"))
                        .description(textOrNull(node, "description"))
                        .inputTokenLimit(toInt(node.get("inputTokenLimit")))
                        .outputTokenLimit(toInt(node.get("outputTokenLimit")))
                        .supportedGenerationMethods(methods.isEmpty() ? null : methods)
                        .version(textOrNull(node, "version"))
                        .build());
            }
            return models;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list Gemini models", e);
        }
    }

    private String generateContent(String prompt, boolean jsonMode, AiJsonSchema schema, AiCallConfig config) {
        String apiKey = resolveApiKey(config);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key not configured (praxis.ai.gemini.api-key)");
        }

        String resolvedModel = resolveModel(config);
        double resolvedTemperature = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        int attempts = Math.max(1, retryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Client client = buildClient(apiKey)) {
                GenerateContentConfig.Builder genConfig = GenerateContentConfig.builder()
                        .temperature((float) resolvedTemperature)
                        .maxOutputTokens(resolvedMaxTokens);

                if (jsonMode) {
                    genConfig.responseMimeType("application/json");
                }
                if (schema != null && schema.hasJsonSchema()) {
                    genConfig.responseSchema(Schema.fromJson(schema.jsonSchema()));
                }

                GenerateContentResponse response =
                        client.models.generateContent(resolvedModel, prompt, genConfig.build());
                log.info("[GeminiAiService] Raw response json: {}", response.toJson());
                String text = response.text();
                log.info("[GeminiAiService] Response text: {}", text);
                if (text == null || text.isBlank()) {
                    log.warn("[GeminiAiService] Empty response text.");
                    return null;
                }
                return text;
            } catch (Exception e) {
                if (!shouldRetry(e) || attempt >= attempts) {
                    throw new IllegalStateException("Failed to call Gemini", e);
                }
                long delay = computeBackoffDelayMs(attempt);
                log.warn("[GeminiAiService] Transient error, retrying in {} ms (attempt {}/{}).", delay, attempt, attempts);
                sleep(delay);
            }
        }
        return null;
    }

    private String resolveApiKey(AiCallConfig config) {
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey().trim();
        }
        if (aiApiKey != null && !aiApiKey.isBlank()) return aiApiKey;
        if (embeddingApiKey != null && !embeddingApiKey.isBlank()) return embeddingApiKey;
        return null;
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

    private Client buildClient(String apiKey) {
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(timeoutMs)
                .build();
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(httpOptions)
                .build();
    }

    private boolean shouldRetry(Exception exception) {
        ApiException apiException = findApiException(exception);
        if (apiException != null) {
            return isRetryableStatus(apiException.code());
        }
        return exception instanceof GenAiIOException
                || exception instanceof java.io.IOException;
    }

    private ApiException findApiException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException apiException) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
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

    private String stripPrefix(String value, String prefix) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (prefix != null && value.startsWith(prefix)) {
            return value.substring(prefix.length());
        }
        return value;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private Integer toInt(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private List<String> listStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
