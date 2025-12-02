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

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final ObjectMapper objectMapper;

    @Value("${embedding.provider:mock}")
    private String provider;

    @Value("${embedding.dimensions:768}")
    private int dimensions;

    @Value("${embedding.gemini.api-key:#{null}}")
    private String geminiApiKey;

    private static final String GEMINI_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent";

    public List<Float> embed(String text) {
        if ("gemini".equalsIgnoreCase(provider) && geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return embedWithGemini(text);
            } catch (Exception e) {
                log.warn("Gemini embedding failed, falling back to mock: {}", e.getMessage());
            }
        }
        return mockEmbedding();
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
}
