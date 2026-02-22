package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderModel;
import org.praxisplatform.config.rag.RagFilterContext;
import org.praxisplatform.config.rag.RagFilters;
import org.praxisplatform.config.rag.RagChatAdvisorService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestClientResponseException;

import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "praxis.ai.provider", havingValue = "gemini", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SpringAiGeminiService implements AiProvider {

    private final ObjectProvider<GoogleGenAiChatModel> chatClientProvider;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";
    private static final long DEFAULT_BREAKER_WINDOW_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long DEFAULT_BREAKER_OPEN_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int DEFAULT_BREAKER_THRESHOLD = 3;

    @Autowired(required = false)
    private ObjectProvider<RagChatAdvisorService> ragChatAdvisorServiceProvider;

    @Value("${spring.ai.google.genai.api-key:#{null}}")
    private String apiKey;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.0-flash}")
    private String model;

    @Value("${spring.ai.google.genai.project-id:#{null}}")
    private String projectId;

    @Value("${spring.ai.google.genai.location:#{null}}")
    private String location;

    @Value("${praxis.ai.gemini.fallback-models:}")
    private String fallbackModels;

    @Value("${praxis.ai.gemini.preview-enabled:true}")
    private boolean previewEnabled;

    @Value("${praxis.ai.gemini.prefer-genai-api:true}")
    private boolean preferGenaiApi;

    @Value("${praxis.ai.gemini.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${praxis.ai.gemini.retry.base-delay-ms:300}")
    private long retryBaseDelayMs;

    @Value("${praxis.ai.gemini.retry.jitter-percent:30}")
    private int retryJitterPercent;

    @Value("${praxis.ai.gemini.breaker.threshold:3}")
    private int breakerThreshold;

    @Value("${praxis.ai.gemini.breaker.window-ms:30000}")
    private long breakerWindowMs;

    @Value("${praxis.ai.gemini.breaker.open-ms:30000}")
    private long breakerOpenMs;

    private final ConcurrentHashMap<String, ModelBreaker> breakers = new ConcurrentHashMap<>();

    @Value("${praxis.ai.temperature:0.1}")
    private double temperature;

    @Value("${praxis.ai.max-tokens:2048}")
    private int maxTokens;

    @Value("${praxis.ai.timeout-seconds:30}")
    private int timeoutSeconds;

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
        JsonSchemaResult schemaResult = prepareSchema(prompt, schema);
        String text = callWithOptions(schemaResult.prompt(), config, true);
        if (text == null || text.isBlank()) {
            return null;
        }
        if (schemaResult.converter() != null) {
            try {
                Object parsed = schemaResult.converter().convert(text);
                return objectMapper.valueToTree(parsed);
            } catch (Exception e) {
                log.warn("[SpringAiGeminiService] JSON parse failed, returning null.", e);
                return null;
            }
        }
        return parseJson(text);
    }

    @Override
    public String generateText(String prompt) {
        return generateText(prompt, null);
    }

    @Override
    public String generateText(String prompt, AiCallConfig config) {
        return callWithOptions(prompt, config, false);
    }

    @Override
    public boolean supportsTextStreaming(AiCallConfig config) {
        return true;
    }

    @Override
    public boolean supportsTurnCancellation(AiCallConfig config) {
        return true;
    }

    @Override
    public String generateTextStream(
            String prompt,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            return AiProvider.super.generateTextStream(prompt, config, onChunk, cancellationRequested);
        }
        return callWithApiKeyTextStream(prompt, resolvedKey, config, onChunk, cancellationRequested);
    }

    @Override
    public List<AiProviderModel> listModels(AiCallConfig config) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            return List.of(AiProviderModel.builder()
                    .name(resolveModel(config))
                    .displayName(resolveModel(config))
                    .description("Configured model (Vertex AI).")
                    .supportedGenerationMethods(List.of("generateContent"))
                    .build());
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                + URLEncoder.encode(resolvedKey, StandardCharsets.UTF_8);
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
            JsonNode data = root.path("models");
            if (!data.isArray()) {
                return List.of();
            }
            List<AiProviderModel> models = new ArrayList<>();
            for (JsonNode node : data) {
                String name = textOrNull(node, "name");
                if (name == null) {
                    continue;
                }
                models.add(AiProviderModel.builder()
                        .name(name)
                        .displayName(textOrNull(node, "displayName"))
                        .description(textOrNull(node, "description"))
                        .supportedGenerationMethods(listStrings(node.get("supportedGenerationMethods")))
                        .build());
            }
            return models;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list Gemini models", e);
        }
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    private String callWithOptions(String prompt, AiCallConfig config, boolean jsonMode) {
        String overrideKey = config != null ? trimToNull(config.getApiKey()) : null;
        List<String> models = resolveModelCandidates(config);
        if (preferGenaiApi) {
            String resolvedKey = resolveApiKey(config);
            if (resolvedKey != null && !resolvedKey.isBlank()) {
                return callWithApiKeyFallback(prompt, resolvedKey, config, jsonMode, models);
            }
        }
        if (overrideKey != null && !overrideKey.equals(apiKey)) {
            return callWithApiKeyFallback(prompt, overrideKey, config, jsonMode, models);
        }
        return callWithSdkFallback(prompt, config, jsonMode, models);
    }

    private GoogleGenAiChatOptions buildOptions(AiCallConfig config, boolean jsonMode) {
        return buildOptions(config, jsonMode, resolveModel(config));
    }

    private GoogleGenAiChatOptions buildOptions(AiCallConfig config, boolean jsonMode, String resolvedModel) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                .model(resolvedModel)
                .temperature(resolveTemperature(config))
                .maxOutputTokens(resolveMaxTokens(config));
        if (jsonMode) {
            builder.responseMimeType("application/json");
        }
        return builder.build();
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

    private JsonSchemaResult prepareSchema(String prompt, AiJsonSchema schema) {
        if (schema == null) {
            return new JsonSchemaResult(prompt, null);
        }
        if (schema.hasTargetClass()) {
            BeanOutputConverter<?> converter = new BeanOutputConverter<>(schema.targetClass(), objectMapper);
            String schemaPrompt = prompt + "\n\n" + converter.getFormat();
            return new JsonSchemaResult(schemaPrompt, converter);
        }
        if (schema.hasJsonSchema()) {
            String schemaPrompt = prompt
                    + "\n\nReturn a JSON object that matches this JSON schema:\n"
                    + schema.jsonSchema();
            return new JsonSchemaResult(schemaPrompt, null);
        }
        return new JsonSchemaResult(prompt, null);
    }

    private JsonNode parseJson(String text) {
        String cleaned = sanitizeJsonText(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[SpringAiGeminiService] JSON parse failed, returning null.", e);
            return null;
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
        if (firstOpen > 0) {
            cleaned = cleaned.substring(firstOpen).trim();
        }
        return cleaned;
    }

    private String extractContent(ChatResponse response) {
        if (response == null) {
            return null;
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return null;
        }
        String content = generation.getOutput().getText();
        return content != null && !content.isBlank() ? content : null;
    }

    private String resolveApiKey(AiCallConfig config) {
        String overrideKey = config != null ? trimToNull(config.getApiKey()) : null;
        return overrideKey != null ? overrideKey : trimToNull(apiKey);
    }

    private String callWithAdvisors(
            String prompt,
            GoogleGenAiChatOptions options,
            AiCallConfig config,
            List<Advisor> advisors) {
        GoogleGenAiChatModel chatClient = resolveChatClient();
        ChatClient client = ChatClient.create(chatClient);
        Filter.Expression filterExpression = RagFilters.buildTenantEnvironmentExpression(
                config != null ? config.getTenantId() : null,
                config != null ? config.getEnvironment() : null);
        if (filterExpression == null) {
            return client.prompt(prompt)
                    .options(options)
                    .advisors(advisors)
                    .call()
                    .content();
        }
        RagFilterContext.set(filterExpression);
        try {
            return client.prompt(prompt)
                    .options(options)
                    .advisors(spec -> spec.advisors(advisors)
                            .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filterExpression))
                    .call()
                    .content();
        } finally {
            RagFilterContext.clear();
        }
    }

    private List<Advisor> resolveRagAdvisors() {
        if (ragChatAdvisorServiceProvider == null) {
            return List.of();
        }
        RagChatAdvisorService service = ragChatAdvisorServiceProvider.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        return service.resolveAdvisors();
    }

    private String callWithApiKey(String prompt, String apiKey, AiCallConfig config, boolean jsonMode) {
        return callWithApiKey(prompt, apiKey, config, jsonMode, resolveModel(config));
    }

    private String callWithApiKey(
            String prompt,
            String apiKey,
            AiCallConfig config,
            boolean jsonMode,
            String resolvedModel) {
        double resolvedTemperature = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("contents").add(
                objectMapper.createObjectNode()
                        .putArray("parts")
                        .add(objectMapper.createObjectNode().put("text", prompt)));
        ObjectNode generationConfig = payload.putObject("generationConfig");
        generationConfig.put("temperature", resolvedTemperature);
        generationConfig.put("maxOutputTokens", resolvedMaxTokens);
        if (jsonMode) {
            generationConfig.put("responseMimeType", "application/json");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + resolvedModel
                + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        try {
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
                throw new IllegalStateException("Gemini API HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return extractContentFromGemini(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to call Gemini", e);
        }
    }

    private String callWithApiKeyTextStream(
            String prompt,
            String apiKey,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        String resolvedModel = resolveModel(config);
        double resolvedTemperature = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("contents").add(
                objectMapper.createObjectNode()
                        .putArray("parts")
                        .add(objectMapper.createObjectNode().put("text", prompt)));
        ObjectNode generationConfig = payload.putObject("generationConfig");
        generationConfig.put("temperature", resolvedTemperature);
        generationConfig.put("maxOutputTokens", resolvedMaxTokens);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + resolvedModel
                + ":streamGenerateContent?alt=sse&key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        CompletableFuture<HttpResponse<InputStream>> responseFuture = null;
        AtomicReference<InputStream> streamRef = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> responseFutureRef = new AtomicReference<>();
        AtomicBoolean abortRequested = new AtomicBoolean(false);
        AiStreamExecutionContextHolder.AbortRegistration abortRegistration = null;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            abortRegistration = AiStreamExecutionContextHolder.registerAbortAction(() -> {
                abortRequested.set(true);
                CompletableFuture<HttpResponse<InputStream>> inFlight = responseFutureRef.get();
                if (inFlight != null) {
                    inFlight.cancel(true);
                }
                closeQuietly(streamRef.get());
            });
            responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            responseFutureRef.set(responseFuture);
            if (abortRequested.get()) {
                responseFuture.cancel(true);
                throw new CancellationException("Gemini stream cancelled before response.");
            }
            HttpResponse<InputStream> response = responseFuture.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            streamRef.set(response.body());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw AiProviderStreamException.fromHttpStatus(
                        "gemini",
                        response.statusCode(),
                        summarizeErrorBody(errorBody));
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(streamRef.get(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled(cancellationRequested)) {
                        throw new CancellationException("Gemini stream cancelled.");
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                        continue;
                    }
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String chunk = extractContentFromGeminiEvent(data);
                    if (chunk == null || chunk.isBlank()) {
                        continue;
                    }
                    out.append(chunk);
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                }
            }
            return out.toString();
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Gemini stream interrupted.");
        } catch (TimeoutException timeoutException) {
            if (responseFuture != null) {
                responseFuture.cancel(true);
            }
            throw AiProviderStreamException.timeout("gemini", timeoutException);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause() != null
                    ? executionException.getCause()
                    : executionException;
            throw classifyStreamFailure("gemini", cause);
        } catch (Exception e) {
            if (isCancelled(cancellationRequested)) {
                throw new CancellationException("Gemini stream cancelled.");
            }
            if (e instanceof AiProviderStreamException streamException) {
                throw streamException;
            }
            throw classifyStreamFailure("gemini", e);
        } finally {
            if (abortRegistration != null) {
                abortRegistration.close();
            }
            closeQuietly(streamRef.getAndSet(null));
        }
    }

    private String callWithSdkFallback(
            String prompt,
            AiCallConfig config,
            boolean jsonMode,
            List<String> models) {
        GoogleGenAiChatModel chatClient = resolveChatClient();
        List<Advisor> advisors = resolveRagAdvisors();
        RuntimeException lastError = null;
        for (int i = 0; i < models.size(); i++) {
            String candidate = models.get(i);
            if (isBreakerOpen(candidate)) {
                log.warn(
                        "[SpringAiGeminiService] Gemini breaker open; provider=gemini api=google-genai-sdk model={} nextModel={} projectId={} location={}",
                        candidate,
                        i + 1 < models.size() ? models.get(i + 1) : "n/a",
                        trimToNull(projectId),
                        trimToNull(location));
                continue;
            }
            GoogleGenAiChatOptions options = buildOptions(config, jsonMode, candidate);
            try {
                return callWithRetries(() -> {
                    if (!advisors.isEmpty()) {
                        return callWithAdvisors(prompt, options, config, advisors);
                    }
                    ChatResponse response = chatClient.call(new Prompt(prompt, options));
                    return extractContent(response);
                }, candidate, "google-genai-sdk");
            } catch (RuntimeException ex) {
                if (isCapacityExhausted(ex)) {
                    recordCapacityFailure(candidate);
                    if (i == models.size() - 1) {
                        logGeminiError("google-genai-sdk", candidate, ex, i + 1, models.size());
                        throw ex;
                    }
                    logGeminiFallback("google-genai-sdk", candidate, models.get(i + 1), ex, i + 1, models.size());
                    lastError = ex;
                    continue;
                }
                if (isRetryable(ex)) {
                    if (i == models.size() - 1) {
                        logGeminiError("google-genai-sdk", candidate, ex, i + 1, models.size());
                        throw ex;
                    }
                    logGeminiFallback("google-genai-sdk", candidate, models.get(i + 1), ex, i + 1, models.size());
                    lastError = ex;
                    continue;
                }
                logGeminiError("google-genai-sdk", candidate, ex, i + 1, models.size());
                throw ex;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private GoogleGenAiChatModel resolveChatClient() {
        GoogleGenAiChatModel chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new IllegalStateException(
                    "Google GenAI chat client not configured. Enable spring-ai-starter-model-google-genai or keep PRAXIS_AI_GEMINI_PREFER_GENAI_API=true with an API key.");
        }
        return chatClient;
    }

    private String callWithApiKeyFallback(
            String prompt,
            String apiKey,
            AiCallConfig config,
            boolean jsonMode,
            List<String> models) {
        RuntimeException lastError = null;
        for (int i = 0; i < models.size(); i++) {
            String candidate = models.get(i);
            try {
                return callWithRetries(
                        () -> callWithApiKey(prompt, apiKey, config, jsonMode, candidate),
                        candidate,
                        "google-genai");
            } catch (RuntimeException ex) {
                if (isCapacityExhausted(ex)) {
                    recordCapacityFailure(candidate);
                    if (i == models.size() - 1) {
                        logGeminiError("google-genai", candidate, ex, i + 1, models.size());
                        throw ex;
                    }
                    logGeminiFallback("google-genai", candidate, models.get(i + 1), ex, i + 1, models.size());
                    lastError = ex;
                    continue;
                }
                if (isRetryable(ex)) {
                    if (i == models.size() - 1) {
                        logGeminiError("google-genai", candidate, ex, i + 1, models.size());
                        throw ex;
                    }
                    logGeminiFallback("google-genai", candidate, models.get(i + 1), ex, i + 1, models.size());
                    lastError = ex;
                    continue;
                }
                logGeminiError("google-genai", candidate, ex, i + 1, models.size());
                throw ex;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private String callWithRetries(Supplier<String> call, String model, String apiFlavor) {
        int attempts = Math.max(1, retryMaxAttempts);
        int capacityMaxAttempts = Math.min(attempts, 2);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException ex) {
                last = ex;
                if (isCapacityExhausted(ex)) {
                    if (attempt >= capacityMaxAttempts) {
                        throw ex;
                    }
                    logGeminiRetry("capacity", apiFlavor, model, ex, attempt, capacityMaxAttempts);
                    sleepWithJitter(retryBaseDelayMs);
                    continue;
                }
                if (isRetryable(ex)) {
                    if (attempt >= attempts) {
                        throw ex;
                    }
                    long backoff = computeBackoff(attempt);
                    logGeminiRetry("retryable", apiFlavor, model, ex, attempt, attempts);
                    sleepWithJitter(backoff);
                    continue;
                }
                throw ex;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private List<String> resolveModelCandidates(AiCallConfig config) {
        String primary = resolveModel(config);
        List<String> fallbacks = parseFallbackModels();
        Set<String> candidates = new LinkedHashSet<>();

        if (primary != null && !primary.isBlank()) {
            if (previewEnabled || !isPreviewModel(primary)) {
                candidates.add(primary);
            } else {
                log.warn("[SpringAiGeminiService] Preview model disabled; skipping primary model={}", primary);
            }
        }

        for (String fallback : fallbacks) {
            if (previewEnabled || !isPreviewModel(fallback)) {
                candidates.add(fallback);
            } else {
                log.info("[SpringAiGeminiService] Preview model disabled; skipping fallback model={}", fallback);
            }
        }

        if (candidates.isEmpty()) {
            String safeDefault = DEFAULT_GEMINI_MODEL;
            candidates.add(safeDefault);
        }
        return new ArrayList<>(candidates);
    }

    private List<String> parseFallbackModels() {
        if (fallbackModels == null || fallbackModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(fallbackModels.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private boolean isPreviewModel(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.toLowerCase();
        return normalized.contains("preview") || normalized.contains("exp") || normalized.contains("experimental");
    }

    private boolean isCapacityExhausted(Throwable ex) {
        Integer status = extractStatusCode(ex);
        if (status != null && (status == 429 || status == 503)) {
            return true;
        }
        String message = safeMessage(ex);
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("model_capacity_exhausted")
                || normalized.contains("resource_exhausted")
                || normalized.contains("no capacity available")
                || normalized.contains("rateLimitExceeded".toLowerCase());
    }

    private boolean isRetryable(Throwable ex) {
        Integer status = extractStatusCode(ex);
        if (status != null && (status == 429 || status == 503)) {
            return true;
        }
        String message = safeMessage(ex);
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("read timed out")
                || normalized.contains("connect timed out");
    }

    private void logGeminiFallback(
            String apiFlavor,
            String failedModel,
            String nextModel,
            Throwable ex,
            int attempt,
            int total) {
        Integer status = extractStatusCode(ex);
        String reason = extractReason(ex);
        log.warn(
                "[SpringAiGeminiService] Gemini capacity issue; provider=gemini api={} model={} nextModel={} attempt={}/{} status={} reason={} projectId={} location={}",
                apiFlavor,
                failedModel,
                nextModel,
                attempt,
                total,
                status,
                reason,
                trimToNull(projectId),
                trimToNull(location),
                ex);
    }

    private void logGeminiRetry(
            String reason,
            String apiFlavor,
            String model,
            Throwable ex,
            int attempt,
            int total) {
        Integer status = extractStatusCode(ex);
        String resolvedReason = extractReason(ex);
        log.warn(
                "[SpringAiGeminiService] Gemini retry; reason={} provider=gemini api={} model={} attempt={}/{} status={} reasonDetail={} projectId={} location={}",
                reason,
                apiFlavor,
                model,
                attempt,
                total,
                status,
                resolvedReason,
                trimToNull(projectId),
                trimToNull(location),
                ex);
    }

    private void logGeminiError(
            String apiFlavor,
            String model,
            Throwable ex,
            int attempt,
            int total) {
        Integer status = extractStatusCode(ex);
        String reason = extractReason(ex);
        log.warn(
                "[SpringAiGeminiService] Gemini call failed; provider=gemini api={} model={} attempt={}/{} status={} reason={} projectId={} location={}",
                apiFlavor,
                model,
                attempt,
                total,
                status,
                reason,
                trimToNull(projectId),
                trimToNull(location),
                ex);
    }

    private Integer extractStatusCode(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof RestClientResponseException restEx) {
                return restEx.getRawStatusCode();
            }
            String message = cursor.getMessage();
            Integer parsed = parseStatusFromMessage(message);
            if (parsed != null) {
                return parsed;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private String extractReason(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof RestClientResponseException restEx) {
                String body = restEx.getResponseBodyAsString();
                String reason = parseReasonFromMessage(body);
                if (reason != null) {
                    return reason;
                }
            }
            String message = cursor.getMessage();
            String reason = parseReasonFromMessage(message);
            if (reason != null) {
                return reason;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private Integer parseStatusFromMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("http 429")) {
            return 429;
        }
        if (normalized.contains("http 503")) {
            return 503;
        }
        if (normalized.contains("resource_exhausted")
                || normalized.contains("model_capacity_exhausted")
                || normalized.contains("ratelimitexceeded")) {
            return 429;
        }
        return null;
    }

    private String parseReasonFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String jsonReason = parseReasonFromJson(message);
        if (jsonReason != null) {
            return jsonReason;
        }
        if (message.contains("MODEL_CAPACITY_EXHAUSTED")) {
            return "MODEL_CAPACITY_EXHAUSTED";
        }
        if (message.contains("RESOURCE_EXHAUSTED")) {
            return "RESOURCE_EXHAUSTED";
        }
        if (message.contains("rateLimitExceeded")) {
            return "rateLimitExceeded";
        }
        if (message.toLowerCase().contains("no capacity available")) {
            return "MODEL_CAPACITY_EXHAUSTED";
        }
        return null;
    }

    private String safeMessage(Throwable ex) {
        return ex != null ? ex.getMessage() : null;
    }

    private AiProviderStreamException classifyStreamFailure(String provider, Throwable error) {
        if (error instanceof AiProviderStreamException streamException) {
            return streamException;
        }
        if (error instanceof java.net.http.HttpTimeoutException) {
            return AiProviderStreamException.timeout(provider, error);
        }
        if (error instanceof IOException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.SocketException
                || error instanceof java.net.UnknownHostException) {
            return AiProviderStreamException.transport(provider, error);
        }
        return AiProviderStreamException.unknown(provider, error);
    }

    private String summarizeErrorBody(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int max = Math.min(trimmed.length(), 180);
        return trimmed.substring(0, max);
    }

    private String parseReasonFromJson(String message) {
        String trimmed = message.trim();
        int brace = trimmed.indexOf('{');
        if (brace < 0) {
            return null;
        }
        String json = trimmed.substring(brace);
        try {
            JsonNode root = objectMapper.readTree(json);
            String reason = findReason(root);
            if (reason != null) {
                return reason;
            }
            JsonNode error = root.path("error");
            String status = textOrNull(error, "status");
            if (status != null) {
                return status;
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    private String findReason(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.has("reason")) {
            String reason = node.get("reason").asText(null);
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                String reason = findReason(child);
                if (reason != null) {
                    return reason;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String reason = findReason(child);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    private long computeBackoff(int attempt) {
        long base = Math.max(0, retryBaseDelayMs);
        long multiplier = 1L << Math.min(4, Math.max(0, attempt - 1));
        long value = base * multiplier;
        return Math.max(0, value);
    }

    private void sleepWithJitter(long delayMs) {
        long base = Math.max(0, delayMs);
        if (base <= 0) {
            return;
        }
        int jitter = Math.max(0, retryJitterPercent);
        double spread = jitter / 100.0;
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble(-spread, spread));
        long sleepMs = Math.max(1, (long) (base * factor));
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isBreakerOpen(String model) {
        ModelBreaker breaker = breakers.computeIfAbsent(model, ignored -> new ModelBreaker());
        long now = System.currentTimeMillis();
        synchronized (breaker) {
            if (breaker.openUntilMs > now) {
                return true;
            }
            if (breaker.openUntilMs > 0 && breaker.openUntilMs <= now) {
                breaker.reset(now);
            }
            return false;
        }
    }

    private void recordCapacityFailure(String model) {
        ModelBreaker breaker = breakers.computeIfAbsent(model, ignored -> new ModelBreaker());
        long now = System.currentTimeMillis();
        synchronized (breaker) {
            long window = breakerWindowMs > 0 ? breakerWindowMs : DEFAULT_BREAKER_WINDOW_MS;
            int threshold = breakerThreshold > 0 ? breakerThreshold : DEFAULT_BREAKER_THRESHOLD;
            long openMs = breakerOpenMs > 0 ? breakerOpenMs : DEFAULT_BREAKER_OPEN_MS;
            if (breaker.windowStartMs == 0 || now - breaker.windowStartMs > window) {
                breaker.windowStartMs = now;
                breaker.failures = 0;
            }
            breaker.failures++;
            if (breaker.failures >= threshold) {
                breaker.openUntilMs = now + openMs;
            }
        }
    }

    private static final class ModelBreaker {
        private int failures;
        private long windowStartMs;
        private long openUntilMs;

        private void reset(long now) {
            failures = 0;
            windowStartMs = now;
            openUntilMs = 0;
        }
    }

    private String extractContentFromGeminiEvent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            return extractContentFromGemini(root);
        } catch (Exception ex) {
            log.debug("[SpringAiGeminiService] Failed to parse Gemini stream chunk: {}", ex.getMessage());
            return null;
        }
    }

    private String extractContentFromGemini(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray() && parts.size() > 0) {
            StringBuilder merged = new StringBuilder();
            for (JsonNode part : parts) {
                String text = part.path("text").asText(null);
                if (text != null && !text.isBlank()) {
                    merged.append(text);
                }
            }
            String value = merged.toString();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isCancelled(Supplier<Boolean> cancellationRequested) {
        if (cancellationRequested == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(cancellationRequested.get());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best-effort close.
        }
    }

    private List<String> listStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record JsonSchemaResult(String prompt, BeanOutputConverter<?> converter) {}
}
