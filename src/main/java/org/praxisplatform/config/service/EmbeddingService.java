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
    private static final String PROVIDER_MOCK = "mock";

    @Value("${embedding.provider:gemini}")
    private String provider;

    @Value("${embedding.dimensions:768}")
    private int dimensions;

    @Value("${embedding.gemini.api-key:#{null}}")
    private String geminiApiKey;

    private static final String GEMINI_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent";

    public List<Float> embed(String text) {
        logEmbeddingConfigIfNeeded();
        String selected = normalizeProvider(provider);
        if (selected == null) {
            selected = PROVIDER_GEMINI;
        }
        if (PROVIDER_MOCK.equals(selected)) {
            return mockEmbedding();
        }
        if (PROVIDER_GEMINI.equals(selected)) {
            if (geminiApiKey == null || geminiApiKey.isBlank()) {
                throw new IllegalStateException(
                        "embedding.gemini.api-key is required when embedding.provider=gemini.");
            }
            try {
                return embedWithGemini(text);
            } catch (Exception e) {
                throw new IllegalStateException("Gemini embedding failed.", e);
            }
        }
        throw new IllegalStateException(
                "Unsupported embedding.provider '" + provider + "'. Supported values: gemini, mock.");
    }

    private void logEmbeddingConfigIfNeeded() {
        if (!loggedConfig.compareAndSet(false, true)) {
            return;
        }
        boolean keyPresent = geminiApiKey != null && !geminiApiKey.isBlank();
        String selected = normalizeProvider(provider);
        log.info(
                "Embedding config: provider={}, dimensions={}, geminiKeyPresent={}",
                selected != null ? selected : provider,
                dimensions,
                keyPresent);
        if (PROVIDER_GEMINI.equals(selected) && !keyPresent) {
            log.error("Embedding provider=gemini but geminiApiKey missing; embeddings will fail.");
        } else if (selected == null) {
            log.error(
                    "Embedding provider is blank or invalid; supported values are gemini, mock.");
        }
    }

    private List<Float> embedWithGemini(String text) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String payload = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("model", "text-embedding-004")
                .set("content", objectMapper.createObjectNode()
                        .set("parts", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode().put("text", text)))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_EMBED_URL + "?key=" + geminiApiKey))
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

    private List<Float> mockEmbedding() {
        List<Float> dummy = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            dummy.add(0.001f * i);
        }
        return dummy;
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
