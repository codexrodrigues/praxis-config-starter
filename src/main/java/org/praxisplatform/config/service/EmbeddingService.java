package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIServiceException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
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
    private static final Pattern SPRING_AI_HTTP_STATUS_PREFIX =
            Pattern.compile("^\\s*(?:HTTP\\s+)?(\\d{3})\\s+-", Pattern.CASE_INSENSITIVE);

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

    @Value("${praxis.ai.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${praxis.ai.retry.initial-delay-ms:500}")
    private long retryInitialDelayMs;

    @Value("${praxis.ai.retry.max-delay-ms:2000}")
    private long retryMaxDelayMs;

    @Value("${praxis.ai.rag.embedding.gemini-embedding-2.retrieval-instructions.enabled:true}")
    private boolean geminiEmbedding2RetrievalInstructionsEnabled;

    public List<Float> embed(String text) {
        return embed(text, null);
    }

    /** Embeds a document published to the derived RAG corpus. */
    public List<Float> embedRagDocument(String text) {
        return embed(formatRagInput(text, RagEmbeddingPurpose.DOCUMENT, null), null);
    }

    /** Embeds a user query executed against the derived RAG corpus. */
    public List<Float> embedRagQuery(String text) {
        return embedRagQuery(text, null);
    }

    public List<Float> embedRagQuery(String text, EmbeddingCallConfig override) {
        return embed(formatRagInput(text, RagEmbeddingPurpose.QUERY, override), override);
    }

    public List<List<Float>> embedAll(List<String> texts) {
        return embedAll(texts, null);
    }

    public List<List<Float>> embedRagDocuments(List<String> texts, EmbeddingCallConfig override) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return embedAll(texts.stream()
                .map(text -> formatRagInput(text, RagEmbeddingPurpose.DOCUMENT, override))
                .toList(), override);
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
                    vectors.addAll(callEmbeddingProvider(
                            PROVIDER_OPENAI,
                            () -> embedAllWithOpenAi(
                                    batch,
                                    override,
                                    effectiveApiKey,
                                    resolveModel(override, openaiModel),
                                    overrideDimensions != null
                                            ? overrideDimensions
                                            : resolveDefaultDimensions(PROVIDER_OPENAI))));
                }
                return vectors;
            } catch (Exception ex) {
                throw classifyEmbeddingFailure(PROVIDER_OPENAI, ex);
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
                    vectors.addAll(callEmbeddingProvider(
                            PROVIDER_GEMINI,
                            () -> embedAllWithGoogleGenAi(batch, override, effectiveApiKey)));
                }
                return vectors;
            } catch (Exception ex) {
                throw classifyEmbeddingFailure(PROVIDER_GEMINI, ex);
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

    private AiProviderCallException classifyEmbeddingFailure(String providerName, Throwable failure) {
        AiProviderCallException normalized = findCause(failure, AiProviderCallException.class);
        if (normalized != null) {
            return normalized;
        }
        ApiException geminiFailure = findCause(failure, ApiException.class);
        if (geminiFailure != null) {
            return AiProviderCallException.fromHttpStatusSanitized(
                    providerName,
                    geminiFailure.code(),
                    geminiFailure.message(),
                    null,
                    geminiFailure);
        }
        OpenAIServiceException openAiFailure = findCause(failure, OpenAIServiceException.class);
        if (openAiFailure != null) {
            return AiProviderCallException.fromHttpStatusSanitized(
                    providerName,
                    openAiFailure.statusCode(),
                    openAiFailure.code().orElseGet(() -> openAiFailure.type().orElse("unknown")),
                    openAiRetryAfter(openAiFailure, Instant.now()),
                    openAiFailure);
        }
        NonTransientAiException springAiNonTransientFailure =
                findCause(failure, NonTransientAiException.class);
        if (springAiNonTransientFailure != null) {
            AiProviderCallException classified = classifySpringAiHttpFailure(
                    providerName, springAiNonTransientFailure);
            if (classified != null) {
                return classified;
            }
        }
        TransientAiException springAiTransientFailure = findCause(failure, TransientAiException.class);
        if (springAiTransientFailure != null) {
            AiProviderCallException classified = classifySpringAiHttpFailure(
                    providerName, springAiTransientFailure);
            if (classified != null) {
                return classified;
            }
        }
        Throwable root = rootCause(failure);
        if (root instanceof HttpTimeoutException
                || root instanceof SocketTimeoutException
                || root instanceof java.util.concurrent.TimeoutException) {
            return AiProviderCallException.timeout(providerName, root);
        }
        if (root instanceof GenAiIOException
                || root instanceof ConnectException
                || root instanceof SocketException
                || root instanceof UnknownHostException
                || root instanceof java.io.IOException) {
            return AiProviderCallException.transport(providerName, root);
        }
        return AiProviderCallException.unknown(providerName, root);
    }

    /**
     * Adapts Spring AI retry exceptions, whose public exception types expose the HTTP status only
     * through the error-handler message prefix. The raw message is used exclusively as a
     * classification hint; the canonical exception message remains sanitized.
     */
    private AiProviderCallException classifySpringAiHttpFailure(
            String providerName, RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = SPRING_AI_HTTP_STATUS_PREFIX.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        return AiProviderCallException.fromHttpStatusSanitized(
                providerName,
                statusCode,
                message,
                null,
                failure);
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current != null ? current : failure;
    }

    private String formatRagInput(String text, RagEmbeddingPurpose purpose, EmbeddingCallConfig override) {
        String normalizedText = text != null ? text : "";
        String selectedProvider = normalizeProvider(override != null ? override.provider() : provider);
        if (!PROVIDER_GEMINI.equals(selectedProvider)
                || !geminiEmbedding2RetrievalInstructionsEnabled
                || !DEFAULT_GEMINI_EMBEDDING_MODEL.equals(resolveModel(override, geminiModel))) {
            return normalizedText;
        }
        if (purpose == RagEmbeddingPurpose.DOCUMENT) {
            return "title: Praxis governed corpus | text: " + normalizedText;
        }
        return "task: search result | query: " + normalizedText;
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
                return callEmbeddingProvider(
                        PROVIDER_GEMINI,
                        () -> embedWithGoogleGenAi(text, override, effectiveApiKey));
            } catch (Exception e) {
                throw classifyEmbeddingFailure(PROVIDER_GEMINI, e);
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
                return callEmbeddingProvider(
                        PROVIDER_OPENAI,
                        () -> embedWithOpenAi(
                                text,
                                override,
                                effectiveApiKey,
                                effectiveModel,
                                effectiveDimensions));
            } catch (Exception e) {
                throw classifyEmbeddingFailure(PROVIDER_OPENAI, e);
            }
        }
        throw new IllegalStateException(
                "Unsupported spring.ai.embedding.provider '" + provider + "'. Supported values: gemini, openai, mock.");
    }

    private <T> T callEmbeddingProvider(String providerName, EmbeddingProviderCall<T> call) {
        int maxAttempts = Math.max(1, retryMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.execute();
            } catch (Exception failure) {
                AiProviderCallException classified = classifyEmbeddingFailure(providerName, failure);
                if (attempt >= maxAttempts || !isRetryableEmbeddingFailure(classified)) {
                    throw classified;
                }
                long delayMs = retryDelayMs(attempt, classified);
                if (delayMs < 0L) {
                    throw classified;
                }
                log.warn(
                        "Retrying embedding provider call after transient failure: provider={}, kind={}, status={}, attempt={}/{}, delayMs={}",
                        providerName,
                        classified.getKind(),
                        classified.getStatusCode() == null ? "none" : classified.getStatusCode(),
                        attempt,
                        maxAttempts,
                        delayMs);
                if (!sleepBeforeEmbeddingRetry(delayMs)) {
                    throw classified;
                }
            }
        }
        throw new IllegalStateException("Embedding provider retry loop terminated unexpectedly.");
    }

    private boolean isRetryableEmbeddingFailure(AiProviderCallException failure) {
        return switch (failure.getKind()) {
            case TRANSPORT, TIMEOUT, RATE_LIMIT, CAPACITY, SERVER_ERROR -> true;
            case QUOTA_EXHAUSTED, AUTH, CLIENT_ERROR, UNKNOWN -> false;
        };
    }

    private long retryDelayMs(int completedAttempt, AiProviderCallException failure) {
        long initialDelayMs = Math.max(0L, retryInitialDelayMs);
        long maxDelayMs = Math.max(0L, retryMaxDelayMs);
        long exponent = Math.min(30L, Math.max(0L, completedAttempt - 1L));
        long backoffMs;
        try {
            backoffMs = Math.multiplyExact(initialDelayMs, 1L << exponent);
        } catch (ArithmeticException overflow) {
            backoffMs = Long.MAX_VALUE;
        }
        long boundedBackoffMs = Math.min(maxDelayMs, backoffMs);
        if (failure.getRetryAfter() == null) {
            return boundedBackoffMs;
        }
        long providerDelayMs = Math.max(0L, Duration.between(Instant.now(), failure.getRetryAfter()).toMillis());
        if (providerDelayMs > maxDelayMs) {
            log.warn(
                    "Embedding provider requested retry beyond the configured delay budget: provider={}, kind={}, status={}, retryAfterMs={}, maxDelayMs={}",
                    failure.getProvider(),
                    failure.getKind(),
                    failure.getStatusCode() == null ? "none" : failure.getStatusCode(),
                    providerDelayMs,
                    maxDelayMs);
            return -1L;
        }
        return Math.max(boundedBackoffMs, providerDelayMs);
    }

    private boolean sleepBeforeEmbeddingRetry(long delayMs) {
        if (delayMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    @FunctionalInterface
    private interface EmbeddingProviderCall<T> {
        T execute() throws Exception;
    }

    private enum RagEmbeddingPurpose {
        DOCUMENT,
        QUERY
    }

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
            throw classifyGoogleGenAiRestFailure(
                    response.statusCode(), response.body(), response.headers(), Instant.now());
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

    AiProviderCallException classifyGoogleGenAiRestFailure(
            int statusCode,
            String responseBody,
            java.net.http.HttpHeaders headers,
            Instant now) {
        AiProviderRetryAfter.GoogleFailureMetadata metadata =
                AiProviderRetryAfter.fromGoogleErrorBody(objectMapper, responseBody, now);
        Instant headerRetryAfter = headers != null
                ? AiProviderRetryAfter.fromHeaders(
                        headers.allValues("retry-after-ms"), headers.allValues("retry-after"), now)
                : null;
        return AiProviderCallException.fromHttpStatusSanitized(
                PROVIDER_GEMINI,
                statusCode,
                metadata.reason(),
                later(metadata.retryAfter(), headerRetryAfter),
                null);
    }

    private Instant openAiRetryAfter(OpenAIServiceException failure, Instant now) {
        if (failure.headers() == null) {
            return null;
        }
        return AiProviderRetryAfter.fromHeaders(
                failure.headers().values("retry-after-ms"),
                failure.headers().values("retry-after"),
                now);
    }

    private Instant later(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }
}
