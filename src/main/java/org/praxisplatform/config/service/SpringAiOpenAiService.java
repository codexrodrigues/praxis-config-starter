package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.vectorstore.filter.Filter;

import org.springframework.stereotype.Service;

/**
 * Adaptador canonico do runtime OpenAI para os contratos de {@link AiProvider}.
 *
 * <p>O servico traduz chamadas de texto, JSON estruturado, listagem de modelos e streaming para a
 * stack Spring AI/OpenAI, incluindo opcionalmente advisors RAG e overrides por chamada.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpringAiOpenAiService implements AiProvider {

    private final ObjectProvider<OpenAiChatModel> chatClientProvider;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ObjectProvider<RagChatAdvisorService> ragChatAdvisorServiceProvider;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    @Value("${praxis.ai.temperature:0.1}")
    private double temperature;

    @Value("${praxis.ai.max-tokens:2048}")
    private int maxTokens;

    @Value("${praxis.ai.openai.json-min-completion-tokens:8192}")
    private int jsonMinCompletionTokens;

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
                log.warn("[SpringAiOpenAiService] JSON parse failed, returning null.", e);
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
        return callOpenAiTextStream(prompt, config, onChunk, cancellationRequested);
    }

    @Override
    public List<AiProviderModel> listModels(AiCallConfig config) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key not configured (spring.ai.openai.api-key)");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildModelsUrl(resolveBaseUrl(baseUrl))))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Authorization", "Bearer " + resolvedKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("OpenAI API HTTP " + response.statusCode() + ": " + response.body());
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
            throw new IllegalStateException("Failed to list OpenAI models", e);
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    private String callWithOptions(String prompt, AiCallConfig config, boolean jsonMode) {
        // BYPASS Spring AI Chat Client to avoid "extra_body" bug in 1.x versions with native OpenAI
        try {
            return callOpenAiDirectly(prompt, config, jsonMode);
        } catch (Exception e) {
            if (e instanceof AiProviderCallException callException) {
                throw callException;
            }
            throw new RuntimeException("Failed to call OpenAI directly", e);
        }
    }

    private String callOpenAiDirectly(String prompt, AiCallConfig config, boolean jsonMode) {
        String resolvedKey = resolveApiKey(config);
        String resolvedModel = resolveModel(config);
        double resolvedTemp = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        boolean explicitMaxTokens = config != null && config.getMaxTokens() != null;
        if (jsonMode && requiresMaxCompletionTokens(resolvedModel) && !explicitMaxTokens) {
            resolvedMaxTokens = Math.max(resolvedMaxTokens, Math.max(1, jsonMinCompletionTokens));
        }
        String resolvedUrl = resolveBaseUrl(baseUrl) + "/v1/chat/completions";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", resolvedModel);
            putTemperature(root, resolvedModel, resolvedTemp);
            putTokenLimit(root, resolvedModel, resolvedMaxTokens);
            putCompactReasoningEffort(root, resolvedModel, resolvedMaxTokens);
            if (jsonMode) {
                ObjectNode fmt = root.putObject("response_format");
                fmt.put("type", "json_object");
            }
            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            String requestBody = objectMapper.writeValueAsString(root);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Authorization", "Bearer " + resolvedKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw AiProviderCallException.fromHttpStatus(
                        "openai",
                        response.statusCode(),
                        summarizeErrorBody(response.body()));
            }

            JsonNode resRoot = objectMapper.readTree(response.body());
            JsonNode contentNode = resRoot.path("choices").get(0).path("message").path("content");
            String content = contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText();
            if (content == null || content.isBlank()) {
                String finishReason = resRoot.path("choices").get(0).path("finish_reason").asText("");
                throw AiProviderCallException.unknown(
                        "openai",
                        new IllegalStateException("OpenAI returned empty content"
                                + (finishReason.isBlank() ? "" : " (finish_reason=" + finishReason + ")")));
            }
            return content;

        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw AiProviderCallException.transport("openai", interruptedException);
        } catch (java.net.http.HttpTimeoutException timeoutException) {
            throw AiProviderCallException.timeout("openai", timeoutException);
        } catch (Exception e) {
            if (e instanceof AiProviderCallException callException) {
                throw callException;
            }
            if (e instanceof IOException
                    || e instanceof java.net.ConnectException
                    || e instanceof java.net.SocketException
                    || e instanceof java.net.UnknownHostException) {
                throw AiProviderCallException.transport("openai", e);
            }
            throw AiProviderCallException.unknown("openai", e);
        }
    }

    private String callOpenAiTextStream(
            String prompt,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        String resolvedKey = resolveApiKey(config);
        String resolvedModel = resolveModel(config);
        double resolvedTemp = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        String resolvedUrl = resolveBaseUrl(baseUrl) + "/v1/chat/completions";

        CompletableFuture<HttpResponse<InputStream>> responseFuture = null;
        AtomicReference<InputStream> streamRef = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> responseFutureRef = new AtomicReference<>();
        AtomicBoolean abortRequested = new AtomicBoolean(false);
        AiStreamExecutionContextHolder.AbortRegistration abortRegistration = null;
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", resolvedModel);
            putTemperature(root, resolvedModel, resolvedTemp);
            putTokenLimit(root, resolvedModel, resolvedMaxTokens);
            putCompactReasoningEffort(root, resolvedModel, resolvedMaxTokens);
            root.put("stream", true);
            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolvedUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Authorization", "Bearer " + resolvedKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root)))
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
                throw new CancellationException("OpenAI stream cancelled before response.");
            }
            HttpResponse<InputStream> response = responseFuture.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            streamRef.set(response.body());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw AiProviderStreamException.fromHttpStatus(
                        "openai",
                        response.statusCode(),
                        summarizeErrorBody(errorBody));
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(streamRef.get(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled(cancellationRequested)) {
                        throw new CancellationException("OpenAI stream cancelled.");
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
                    String chunk = extractOpenAiDelta(data);
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
            throw new CancellationException("OpenAI stream interrupted.");
        } catch (TimeoutException timeoutException) {
            if (responseFuture != null) {
                responseFuture.cancel(true);
            }
            throw AiProviderStreamException.timeout("openai", timeoutException);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause() != null
                    ? executionException.getCause()
                    : executionException;
            throw classifyStreamFailure("openai", cause);
        } catch (Exception ex) {
            if (isCancelled(cancellationRequested)) {
                throw new CancellationException("OpenAI stream cancelled.");
            }
            if (ex instanceof AiProviderStreamException streamException) {
                throw streamException;
            }
            throw classifyStreamFailure("openai", ex);
        } finally {
            if (abortRegistration != null) {
                abortRegistration.close();
            }
            closeQuietly(streamRef.getAndSet(null));
        }
    }

    private void putTokenLimit(ObjectNode payload, String modelName, int maxTokens) {
        if (requiresMaxCompletionTokens(modelName)) {
            payload.put("max_completion_tokens", maxTokens);
            return;
        }
        payload.put("max_tokens", maxTokens);
    }

    private void putTemperature(ObjectNode payload, String modelName, double resolvedTemp) {
        if (usesFixedDefaultTemperature(modelName)) {
            return;
        }
        payload.put("temperature", resolvedTemp);
    }

    private void putCompactReasoningEffort(ObjectNode payload, String modelName, int maxTokens) {
        if (!supportsCompactReasoningEffort(modelName) || maxTokens > 2048) {
            return;
        }
        payload.put("reasoning_effort", "low");
    }

    private boolean requiresMaxCompletionTokens(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4");
    }

    private boolean usesFixedDefaultTemperature(String modelName) {
        return requiresMaxCompletionTokens(modelName);
    }

    private boolean supportsCompactReasoningEffort(String modelName) {
        if (modelName == null) {
            return false;
        }
        return modelName.trim().toLowerCase().startsWith("gpt-5");
    }

    private String extractOpenAiDelta(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).path("delta");
            String content = delta.path("content").asText(null);
            if (content != null && !content.isBlank()) {
                return content;
            }
            JsonNode message = choices.get(0).path("message");
            String fallbackContent = message.path("content").asText(null);
            if (fallbackContent != null && !fallbackContent.isBlank()) {
                return fallbackContent;
            }
            return null;
        } catch (Exception ex) {
            log.debug("[SpringAiOpenAiService] Failed to parse OpenAI stream chunk: {}", ex.getMessage());
            return null;
        }
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

    private OpenAiChatOptions buildOptions(AiCallConfig config, boolean jsonMode) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(resolveModel(config))
                .temperature(resolveTemperature(config))
                .maxTokens(resolveMaxTokens(config));
        return builder.build();
    }

    private OpenAiChatModel resolveClient(AiCallConfig config) {
        String overrideKey = config != null ? trimToNull(config.getApiKey()) : null;
        if (overrideKey != null && !overrideKey.equals(apiKey)) {
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(overrideKey)
                    .baseUrl(resolveBaseUrl(baseUrl))
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(buildOptions(config, false))
                    .build();
        }
        OpenAiChatModel chatClient = chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            throw new IllegalStateException("OpenAI chat client not configured. Provide spring.ai.openai.api-key or use the direct API-key request path.");
        }
        return chatClient;
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
            log.warn("[SpringAiOpenAiService] JSON parse failed, returning null.", e);
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

    private String buildModelsUrl(String base) {
        String normalized = base;
        if (normalized.endsWith("/v1")) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
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

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record JsonSchemaResult(String prompt, BeanOutputConverter<?> converter) {}
}
