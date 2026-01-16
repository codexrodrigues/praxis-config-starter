package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final ObjectMapper objectMapper;

    private final AtomicBoolean loggedConfig = new AtomicBoolean(false);
    private static final String PROVIDER_GEMINI = "gemini";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_MOCK = "mock";

    @Value("${embedding.provider:gemini}")
    private String provider;

    @Value("${embedding.dimensions:768}")
    private int dimensions;

    @Value("${embedding.gemini.api-key:#{null}}")
    private String geminiApiKey;

    @Value("${embedding.openai.api-key:#{null}}")
    private String openaiApiKey;

    @Value("${embedding.openai.model:text-embedding-3-small}")
    private String openaiModel;

    private static final String GEMINI_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent";
    private static final String OPENAI_EMBED_URL = "https://api.openai.com/v1/embeddings";

    public List<Float> embed(String text) {
        return embed(text, null);
    }

    public List<Float> embed(String text, EmbeddingCallConfig override) {
        logEmbeddingConfigIfNeeded();
        String selected = normalizeProvider(override != null ? override.provider() : provider);
        if (selected == null) {
            selected = normalizeProvider(provider);
        }
        if (selected == null) {
            selected = PROVIDER_GEMINI;
        }
        Integer overrideDimensions = override != null ? override.dimensions() : null;
        if (PROVIDER_MOCK.equals(selected)) {
            int mockDimensions = overrideDimensions != null ? overrideDimensions : dimensions;
            return mockEmbedding(mockDimensions);
        }
        if (PROVIDER_GEMINI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, geminiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "embedding.gemini.api-key is required when embedding.provider=gemini.");
            }
            try {
                return embedWithGemini(text, effectiveApiKey);
            } catch (Exception e) {
                throw new IllegalStateException("Gemini embedding failed.", e);
            }
        }
        if (PROVIDER_OPENAI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, openaiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "embedding.openai.api-key is required when embedding.provider=openai.");
            }
            try {
                String effectiveModel = resolveModel(override, openaiModel);
                Integer effectiveDimensions = overrideDimensions != null ? overrideDimensions : dimensions;
                return embedWithOpenAi(text, effectiveApiKey, effectiveModel, effectiveDimensions);
            } catch (Exception e) {
                throw new IllegalStateException("OpenAI embedding failed.", e);
            }
        }
        throw new IllegalStateException(
                "Unsupported embedding.provider '" + provider + "'. Supported values: gemini, openai, mock.");
    }

    private void logEmbeddingConfigIfNeeded() {
        if (!loggedConfig.compareAndSet(false, true)) {
            return;
        }
        boolean geminiKeyPresent = geminiApiKey != null && !geminiApiKey.isBlank();
        boolean openaiKeyPresent = openaiApiKey != null && !openaiApiKey.isBlank();
        String selected = normalizeProvider(provider);
        log.info(
                "Embedding config: provider={}, dimensions={}, geminiKeyPresent={}, openaiKeyPresent={}",
                selected != null ? selected : provider,
                dimensions,
                geminiKeyPresent,
                openaiKeyPresent);
        if (PROVIDER_GEMINI.equals(selected) && !geminiKeyPresent) {
            log.error("Embedding provider=gemini but geminiApiKey missing; embeddings will fail.");
        } else if (PROVIDER_OPENAI.equals(selected) && !openaiKeyPresent) {
            log.error("Embedding provider=openai but openaiApiKey missing; embeddings will fail.");
        } else if (selected == null) {
            log.error(
                    "Embedding provider is blank or invalid; supported values are gemini, openai, mock.");
        }
    }

    private List<Float> embedWithGemini(String text, String apiKey) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String payload = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("model", "text-embedding-004")
                .set("content", objectMapper.createObjectNode()
                        .set("parts", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode().put("text", text)))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_EMBED_URL + "?key=" + apiKey))
                .timeout(Duration.ofSeconds(15))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Gemini embedding HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray()) {
            throw new IllegalStateException("Unexpected Gemini embedding response: " + response.body());
        }

        List<Float> vector = new ArrayList<>();
        values.forEach(v -> vector.add(v.floatValue()));
        return vector;
    }

    private List<Float> embedWithOpenAi(
            String text,
            String apiKey,
            String model,
            Integer dimensionsOverride) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        var payloadNode = objectMapper.createObjectNode()
                .put("model", model)
                .put("input", text);
        if (dimensionsOverride != null && dimensionsOverride > 0) {
            payloadNode.put("dimensions", dimensionsOverride);
        }
        String payload = objectMapper.writeValueAsString(payloadNode);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_EMBED_URL))
                .timeout(Duration.ofSeconds(15))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("OpenAI embedding HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Unexpected OpenAI embedding response: " + response.body());
        }
        JsonNode values = data.get(0).path("embedding");
        if (!values.isArray()) {
            throw new IllegalStateException("Unexpected OpenAI embedding response: " + response.body());
        }

        List<Float> vector = new ArrayList<>();
        values.forEach(v -> vector.add(v.floatValue()));
        return vector;
    }

    private List<Float> mockEmbedding(int effectiveDimensions) {
        List<Float> dummy = new ArrayList<>(effectiveDimensions);
        for (int i = 0; i < effectiveDimensions; i++) {
            dummy.add(0.001f * i);
        }
        return dummy;
    }

    public record EmbeddingCallConfig(
            String provider,
            String apiKey,
            String model,
            Integer dimensions) {}

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }

    private String resolveApiKey(EmbeddingCallConfig override, String fallback) {
        if (override != null && override.apiKey() != null && !override.apiKey().isBlank()) {
            return override.apiKey();
        }
        return fallback;
    }

    private String resolveModel(EmbeddingCallConfig override, String fallback) {
        if (override != null && override.model() != null && !override.model().isBlank()) {
            return override.model();
        }
        return fallback;
    }
}
