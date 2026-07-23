package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Faixa canonica de geracao de embeddings usada pelos servicos de ingestao, busca semantica e
 * RAG do modulo.
 *
 * <p>O servico abstrai a selecao de provider, modelo, dimensoes e fallback de configuracao para
 * OpenAI, Gemini ou modo mock, devolvendo vetores compatíveis com os contratos persistidos no
 * banco e no vector store.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final ObjectProvider<OpenAiEmbeddingModel> openAiEmbeddingClientProvider;
    private final ObjectProvider<GoogleGenAiTextEmbeddingModel> googleGenAiEmbeddingClientProvider;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean loggedConfig = new AtomicBoolean(false);
    private static final String PROVIDER_GEMINI = "gemini";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_MOCK = "mock";
    private static final String DEFAULT_GEMINI_EMBEDDING_MODEL = "gemini-embedding-2";
    private static final int MAX_BATCH_UTF8_BYTES = 240_000;
    private static final int MAX_BATCH_INPUTS = 256;

    @Value("${spring.ai.embedding.provider:gemini}")
    private String provider;

    @Value("${spring.ai.openai.embedding.options.dimensions:768}")
    private int openaiDimensions;

    @Value("${spring.ai.google.genai.embedding.text.options.dimensions:768}")
    private int geminiDimensions;

    @Value("${spring.ai.google.genai.embedding.api-key:${spring.ai.google.genai.api-key:#{null}}}")
    private String geminiApiKey;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String openaiApiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-large}")
    private String openaiModel;

    @Value("${spring.ai.google.genai.embedding.text.options.model:gemini-embedding-2}")
    private String geminiModel;

    @Value("${praxis.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    public List<Float> embed(String text) {
        return embed(text, null);
    }

    public List<List<Float>> embedAll(List<String> texts) {
        return embedAll(texts, null);
    }

    public List<List<Float>> embedAll(List<String> texts, EmbeddingCallConfig override) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() == 1) {
            return List.of(embed(texts.get(0), override));
        }
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
            int dimensions = overrideDimensions != null ? overrideDimensions : resolveDefaultDimensions(selected);
            List<List<Float>> vectors = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                vectors.add(mockEmbedding(dimensions));
            }
            return vectors;
        }
        if (PROVIDER_OPENAI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, openaiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "spring.ai.openai.api-key is required when spring.ai.embedding.provider=openai.");
            }
            try {
                List<List<Float>> vectors = new ArrayList<>(texts.size());
                for (List<String> batch : partitionEmbeddingInputs(texts)) {
                    vectors.addAll(embedAllWithOpenAi(
                            batch,
                            override,
                            effectiveApiKey,
                            resolveModel(override, openaiModel),
                            overrideDimensions != null
                                    ? overrideDimensions
                                    : resolveDefaultDimensions(selected)));
                }
                return vectors;
            } catch (Exception ex) {
                throw new IllegalStateException("OpenAI embedding batch failed: " + rootCauseMessage(ex), ex);
            }
        }
        if (PROVIDER_GEMINI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, geminiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "spring.ai.google.genai.embedding.api-key is required when spring.ai.embedding.provider=gemini.");
            }
            try {
                List<List<Float>> vectors = new ArrayList<>(texts.size());
                for (List<String> batch : partitionEmbeddingInputs(texts)) {
                    vectors.addAll(embedAllWithGoogleGenAi(batch, override, effectiveApiKey));
                }
                return vectors;
            } catch (Exception ex) {
                throw new IllegalStateException("Gemini embedding batch failed.", ex);
            }
        }
        throw new IllegalStateException(
                "Unsupported spring.ai.embedding.provider '" + provider + "'. Supported values: gemini, openai, mock.");
    }

    private List<List<String>> partitionEmbeddingInputs(List<String> texts) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentBytes = 0;
        for (String text : texts) {
            String normalized = text != null ? text : "";
            int inputBytes = normalized.getBytes(StandardCharsets.UTF_8).length;
            if (!current.isEmpty()
                    && (current.size() >= MAX_BATCH_INPUTS
                            || currentBytes + inputBytes > MAX_BATCH_UTF8_BYTES)) {
                batches.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            current.add(normalized);
            currentBytes += inputBytes;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
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
            int mockDimensions = overrideDimensions != null ? overrideDimensions : resolveDefaultDimensions(selected);
            return mockEmbedding(mockDimensions);
        }
        if (PROVIDER_GEMINI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, geminiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "spring.ai.google.genai.embedding.api-key is required when spring.ai.embedding.provider=gemini.");
            }
            try {
                return embedWithGoogleGenAi(text, override, effectiveApiKey);
            } catch (Exception e) {
                throw new IllegalStateException("Gemini embedding failed.", e);
            }
        }
        if (PROVIDER_OPENAI.equals(selected)) {
            String effectiveApiKey = resolveApiKey(override, openaiApiKey);
            if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                throw new IllegalStateException(
                        "spring.ai.openai.api-key is required when spring.ai.embedding.provider=openai.");
            }
            try {
                String effectiveModel = resolveModel(override, openaiModel);
                Integer effectiveDimensions = overrideDimensions != null ? overrideDimensions : resolveDefaultDimensions(selected);
                return embedWithOpenAi(text, override, effectiveApiKey, effectiveModel, effectiveDimensions);
            } catch (Exception e) {
                throw new IllegalStateException("OpenAI embedding failed: " + rootCauseMessage(e), e);
            }
        }
        throw new IllegalStateException(
                "Unsupported spring.ai.embedding.provider '" + provider + "'. Supported values: gemini, openai, mock.");
    }

    private void logEmbeddingConfigIfNeeded() {
        if (!loggedConfig.compareAndSet(false, true)) {
            return;
        }
        boolean geminiKeyPresent = geminiApiKey != null && !geminiApiKey.isBlank();
        boolean openaiKeyPresent = openaiApiKey != null && !openaiApiKey.isBlank();
        String selected = normalizeProvider(provider);
        int defaultDimensions = resolveDefaultDimensions(selected);
        log.info(
                "Embedding config: provider={}, dimensions={}, geminiKeyPresent={}, openaiKeyPresent={}",
                selected != null ? selected : provider,
                defaultDimensions,
                geminiKeyPresent,
                openaiKeyPresent);
        if (PROVIDER_GEMINI.equals(selected) && !geminiKeyPresent) {
            log.error("Embedding provider=gemini but spring.ai.google.genai.embedding.api-key missing; embeddings will fail.");
        } else if (PROVIDER_OPENAI.equals(selected) && !openaiKeyPresent) {
            log.error("Embedding provider=openai but spring.ai.openai.api-key missing; embeddings will fail.");
        } else if (selected == null) {
            log.error(
                    "Embedding provider is blank or invalid; supported values are gemini, openai, mock.");
        }
    }

    private List<Float> embedWithOpenAi(
            String text,
            EmbeddingCallConfig override,
            String apiKey,
            String model,
            Integer dimensionsOverride) throws Exception {
        OpenAiEmbeddingModel client = resolveOpenAiClient(override, apiKey);
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(model);
        if (dimensionsOverride != null && dimensionsOverride > 0) {
            optionsBuilder.dimensions(dimensionsOverride);
        }
        OpenAiEmbeddingOptions options = optionsBuilder.build();
        EmbeddingRequest request = new EmbeddingRequest(List.of(text), options);
        EmbeddingResponse response = client.call(request);
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("OpenAI embedding returned empty response.");
        }
        List<Float> vector = toFloatList(response.getResult().getOutput());
        validateDimensions("openai", vector, dimensionsOverride);
        return vector;
    }

    private List<List<Float>> embedAllWithOpenAi(
            List<String> texts,
            EmbeddingCallConfig override,
            String apiKey,
            String model,
            Integer dimensionsOverride) {
        OpenAiEmbeddingModel client = resolveOpenAiClient(override, apiKey);
        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder().model(model);
        if (dimensionsOverride != null && dimensionsOverride > 0) {
            optionsBuilder.dimensions(dimensionsOverride);
        }
        EmbeddingResponse response = client.call(new EmbeddingRequest(texts, optionsBuilder.build()));
        return orderedVectors("openai", response, texts.size(), dimensionsOverride);
    }

    private List<Float> embedWithGoogleGenAi(
            String text,
            EmbeddingCallConfig override,
            String apiKey) throws Exception {
        Integer dimensions = override != null ? override.dimensions() : null;
        if (dimensions == null || dimensions <= 0) {
            dimensions = geminiDimensions > 0 ? geminiDimensions : null;
        }
        GoogleGenAiTextEmbeddingModel client = googleGenAiEmbeddingClientProvider.getIfAvailable();
        if (client != null) {
            GoogleGenAiTextEmbeddingOptions.Builder optionsBuilder = GoogleGenAiTextEmbeddingOptions.builder()
                    .model(resolveModel(override, geminiModel));
            if (dimensions != null && dimensions > 0) {
                optionsBuilder.dimensions(dimensions);
            }
            GoogleGenAiTextEmbeddingOptions options = optionsBuilder.build();
            EmbeddingRequest request = new EmbeddingRequest(List.of(text), options);
            EmbeddingResponse response = client.call(request);
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("Gemini embedding returned empty response.");
            }
            List<Float> vector = toFloatList(response.getResult().getOutput());
            validateDimensions("gemini", vector, dimensions);
            return vector;
        }
        List<Float> vector = embedWithGoogleGenAiRest(
                text,
                apiKey,
                resolveModel(override, geminiModel),
                dimensions);
        validateDimensions("gemini", vector, dimensions);
        return vector;
    }

    private List<List<Float>> embedAllWithGoogleGenAi(
            List<String> texts,
            EmbeddingCallConfig override,
            String apiKey) throws Exception {
        Integer dimensions = override != null ? override.dimensions() : null;
        if (dimensions == null || dimensions <= 0) {
            dimensions = geminiDimensions > 0 ? geminiDimensions : null;
        }
        GoogleGenAiTextEmbeddingModel client = googleGenAiEmbeddingClientProvider.getIfAvailable();
        if (client != null) {
            GoogleGenAiTextEmbeddingOptions.Builder optionsBuilder = GoogleGenAiTextEmbeddingOptions.builder()
                    .model(resolveModel(override, geminiModel));
            if (dimensions != null && dimensions > 0) {
                optionsBuilder.dimensions(dimensions);
            }
            EmbeddingResponse response = client.call(new EmbeddingRequest(texts, optionsBuilder.build()));
            return orderedVectors("gemini", response, texts.size(), dimensions);
        }
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            List<Float> vector = embedWithGoogleGenAiRest(
                    text,
                    apiKey,
                    resolveModel(override, geminiModel),
                    dimensions);
            validateDimensions("gemini", vector, dimensions);
            vectors.add(vector);
        }
        return vectors;
    }

    private List<List<Float>> orderedVectors(
            String providerName,
            EmbeddingResponse response,
            int expectedCount,
            Integer expectedDimensions) {
        if (response == null || response.getResults() == null
                || response.getResults().size() != expectedCount) {
            throw new IllegalStateException(
                    providerName + " embedding batch returned "
                            + (response == null || response.getResults() == null
                                    ? 0
                                    : response.getResults().size())
                            + " result(s), expected " + expectedCount + ".");
        }
        List<List<Float>> ordered = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
        for (org.springframework.ai.embedding.Embedding result : response.getResults()) {
            int index = result.getIndex() != null ? result.getIndex() : -1;
            if (index < 0 || index >= expectedCount) {
                throw new IllegalStateException(providerName + " embedding batch returned an invalid result index.");
            }
            List<Float> vector = toFloatList(result.getOutput());
            validateDimensions(providerName, vector, expectedDimensions);
            ordered.set(index, vector);
        }
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException(providerName + " embedding batch returned incomplete indexed results.");
        }
        return List.copyOf(ordered);
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

    private OpenAiEmbeddingModel resolveOpenAiClient(EmbeddingCallConfig override, String apiKey) {
        String overrideKey = override != null ? trimToNull(override.apiKey()) : null;
        if (overrideKey != null && !overrideKey.equals(apiKey)) {
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(overrideKey)
                    .baseUrl(resolveBaseUrl(openaiBaseUrl))
                    .build();
            return new OpenAiEmbeddingModel(api);
        }
        OpenAiEmbeddingModel client = openAiEmbeddingClientProvider.getIfAvailable();
        if (client != null) {
            return client;
        }
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(resolveBaseUrl(openaiBaseUrl))
                .build();
        return new OpenAiEmbeddingModel(api);
    }

    private List<Float> embedWithGoogleGenAiRest(
            String text,
            String apiKey,
            String model,
            Integer dimensions) throws Exception {
        String resolvedModel = trimToNull(model);
        if (resolvedModel == null) {
            resolvedModel = DEFAULT_GEMINI_EMBEDDING_MODEL;
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + resolvedModel
                + ":embedContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        JsonNode payload = googleGenAiEmbeddingPayload(text, dimensions);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Gemini embedding HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray()) {
            throw new IllegalStateException("Gemini embedding response missing 'embedding.values'.");
        }
        List<Float> vector = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            vector.add((float) value.asDouble());
        }
        return vector;
    }

    private ObjectNode googleGenAiEmbeddingPayload(String text, Integer dimensions) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode content = objectMapper.createObjectNode();
        content.putArray("parts")
                .add(objectMapper.createObjectNode().put("text", text));
        payload.set("content", content);
        if (dimensions != null && dimensions > 0) {
            payload.put("outputDimensionality", dimensions);
        }
        return payload;
    }

    private int resolveDefaultDimensions(String selected) {
        if (PROVIDER_GEMINI.equals(selected)) {
            return geminiDimensions;
        }
        return openaiDimensions;
    }

    private void validateDimensions(String provider, List<Float> vector, Integer expected) {
        if (expected == null || expected <= 0) {
            return;
        }
        if (vector == null) {
            return;
        }
        if (vector.size() != expected) {
            log.warn(
                    "Embedding size mismatch for {} (expected={}, actual={}).",
                    provider,
                    expected,
                    vector.size());
        }
    }

    private List<Float> toFloatList(float[] values) {
        if (values == null) {
            return List.of();
        }
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    private String resolveBaseUrl(String value) {
        String resolved = trimToNull(value);
        if (resolved == null) {
            resolved = "https://api.openai.com";
        }
        if (resolved.endsWith("/")) {
            return resolved.substring(0, resolved.length() - 1);
        }
        return resolved;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.replaceAll("sk-[A-Za-z0-9_-]+", "sk-***");
    }
}
