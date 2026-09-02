package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.MultipartField;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.responses.ContainerAuto;
import com.openai.models.responses.FunctionShellTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.SkillReference;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Adaptador canônico do provider OpenAI para os contratos internos de {@link AiProvider}.
 *
 * <p>A geração usa o SDK Java oficial e a Responses API. O adapter preserva os contratos Praxis de
 * configuração por chamada, saída estruturada, streaming, cancelamento e telemetria, sem expor
 * tipos específicos do fornecedor para os demais runtimes.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpringAiOpenAiService implements AiProvider {

    private static final String PROVIDER = "openai";
    private static final String TRANSPORT = "openai-responses-sdk";
    private static final String STRUCTURED_OUTPUT_NAME = "praxis_response";
    private static final String DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe";
    private static final String DEFAULT_LIGHT_REASONING_MODELS = "gpt-5.6-luna,gpt-5.6-terra";
    private static final int MIN_OUTPUT_TOKENS = 16;

    private final ObjectMapper objectMapper;
    private final OpenAiHostedSkillProperties hostedSkillProperties;
    private final AtomicReference<DefaultClientHolder> defaultClient = new AtomicReference<>();

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

    @Value("${praxis.ai.openai.light-reasoning-models:" + DEFAULT_LIGHT_REASONING_MODELS + "}")
    private String lightReasoningModels = DEFAULT_LIGHT_REASONING_MODELS;

    @Value("${praxis.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${praxis.ai.usage-origin-class:${PRAXIS_AI_USAGE_ORIGIN_CLASS:unspecified}}")
    private String usageOriginClass = "unspecified";

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
        PreparedSchema preparedSchema = prepareSchema(schema);
        OpenAiResponseProjection response = callResponses(prompt, config, preparedSchema, true);
        String text = response.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (preparedSchema.converter() != null) {
            try {
                return objectMapper.valueToTree(preparedSchema.converter().convert(text));
            } catch (Exception exception) {
                log.warn("[SpringAiOpenAiService] Structured response conversion failed.", exception);
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
        return callResponses(prompt, config, PreparedSchema.none(), false).text();
    }

    @Override
    public boolean supportsAudioTranscription(AiCallConfig config) {
        return true;
    }

    @Override
    public String transcribeAudio(AiAudioTranscriptionRequest request, AiCallConfig config) {
        if (request == null || request.audio() == null || request.audio().length == 0) {
            throw new IllegalArgumentException("Audio payload is required.");
        }
        MultipartField<java.io.InputStream> audioFile = MultipartField.<java.io.InputStream>builder()
                .value(new ByteArrayInputStream(request.audio()))
                .filename(resolveAudioFileName(request.fileName()))
                .contentType(resolveAudioContentType(request.contentType()))
                .build();
        TranscriptionCreateParams.Builder params = TranscriptionCreateParams.builder()
                .file(audioFile)
                .model(AudioModel.of(resolveTranscriptionModel(config)));
        String language = normalizeTranscriptionLanguage(request.language());
        if (language != null) {
            params.language(language);
        }
        try (ClientLease lease = acquireClient(config)) {
            TranscriptionCreateResponse transcription = lease.client().audio().transcriptions().create(params.build());
            String text = transcription.asTranscription().text();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Audio transcription returned no text.");
            }
            return text.trim();
        } catch (AiProviderCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyCallFailure(exception);
        }
    }

    private String resolveTranscriptionModel(AiCallConfig config) {
        String configured = config != null ? config.getModel() : null;
        return configured != null && configured.toLowerCase(java.util.Locale.ROOT).contains("transcrib")
                ? configured.trim()
                : DEFAULT_TRANSCRIPTION_MODEL;
    }

    private String resolveAudioFileName(String fileName) {
        return fileName == null || fileName.isBlank() ? "audio.webm" : fileName.trim();
    }

    private String resolveAudioContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.trim();
    }

    private String normalizeTranscriptionLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim();
        int separator = normalized.indexOf('-');
        return (separator > 0 ? normalized.substring(0, separator) : normalized).toLowerCase(java.util.Locale.ROOT);
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
        return callResponsesStream(prompt, config, onChunk, cancellationRequested);
    }

    @Override
    public List<AiProviderModel> listModels(AiCallConfig config) {
        String resolvedKey = requireApiKey(config);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, resolveTimeoutSeconds(config))))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildModelsUrl(resolveBaseUrl(baseUrl))))
                .timeout(Duration.ofSeconds(Math.max(1, resolveTimeoutSeconds(config))))
                .header("Authorization", "Bearer " + resolvedKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw AiProviderCallException.fromHttpStatus(
                        PROVIDER, response.statusCode(), summarizeErrorBody(response.body()));
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<AiProviderModel> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = textOrNull(node, "id");
                if (id != null) {
                    models.add(AiProviderModel.builder()
                            .name(id)
                            .displayName(id)
                            .description(textOrNull(node, "owned_by"))
                            .supportedGenerationMethods(List.of("responses"))
                            .build());
                }
            }
            return models;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AiProviderCallException.transport(PROVIDER, exception);
        } catch (AiProviderCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyCallFailure(exception);
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER;
    }

    private OpenAiResponseProjection callResponses(
            String prompt,
            AiCallConfig config,
            PreparedSchema preparedSchema,
            boolean jsonMode) {
        int resolvedTimeoutSeconds = resolveTimeoutSeconds(config);
        try (ClientLease lease = acquireClient(config)) {
            ResponseCreateParams params = buildParams(prompt, config, preparedSchema, jsonMode);
            CompletableFuture<HttpResponseFor<Response>> future = lease.client()
                    .async()
                    .responses()
                    .withRawResponse()
                    .create(params);
            try {
                OpenAiResponseProjection response;
                try (HttpResponseFor<Response> rawResponse =
                        future.get(Math.max(1, resolvedTimeoutSeconds), TimeUnit.SECONDS)) {
                    response = projectResponse(rawResponse);
                }
                validateCompletedResponse(response);
                captureInvocationMetadata(config, response);
                return response;
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw AiProviderCallException.timeout(PROVIDER, exception);
            } catch (ExecutionException exception) {
                throw classifyCallFailure(unwrap(exception));
            }
        } catch (AiProviderCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw classifyCallFailure(exception);
        }
    }

    /**
     * Projects only the stable Responses fields consumed by Praxis from the SDK raw-response
     * surface. This keeps transport and request authoring in the official SDK while avoiding a
     * dependency on every variant of the provider's evolving output union.
     */
    private OpenAiResponseProjection projectResponse(HttpResponseFor<Response> rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse.body());
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("OpenAI returned a non-object response");
            }
            StringBuilder text = new StringBuilder();
            String refusal = null;
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    if (!"message".equals(item.path("type").asText()) || !item.path("content").isArray()) {
                        continue;
                    }
                    for (JsonNode content : item.path("content")) {
                        String contentType = content.path("type").asText();
                        if ("output_text".equals(contentType) && content.path("text").isTextual()) {
                            text.append(content.path("text").asText());
                        } else if (refusal == null
                                && "refusal".equals(contentType)
                                && content.path("refusal").isTextual()) {
                            refusal = content.path("refusal").asText();
                        }
                    }
                }
            }
            JsonNode usage = root.path("usage");
            return new OpenAiResponseProjection(
                    text.toString(),
                    refusal,
                    textOrNull(root, "status"),
                    root.path("error").isObject(),
                    root.path("incomplete_details").isObject(),
                    textOrNull(root, "id"),
                    textOrNull(root, "model"),
                    integerOrNull(usage, "input_tokens"),
                    integerOrNull(usage, "output_tokens"),
                    integerOrNull(usage.path("input_tokens_details"), "cached_tokens"),
                    integerOrNull(usage, "total_tokens"));
        } catch (IOException exception) {
            throw AiProviderCallException.unknown(
                    PROVIDER, new IllegalStateException("OpenAI response body could not be decoded", exception));
        }
    }

    private String callResponsesStream(
            String prompt,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        int resolvedTimeoutSeconds = resolveTimeoutSeconds(config);
        AtomicBoolean abortRequested = new AtomicBoolean(false);
        AtomicReference<AsyncStreamResponse<ResponseStreamEvent>> streamRef = new AtomicReference<>();
        ResponseAccumulator accumulator = ResponseAccumulator.create();
        StringBuilder text = new StringBuilder();
        AiStreamExecutionContextHolder.AbortRegistration registration =
                AiStreamExecutionContextHolder.registerAbortAction(() -> {
                    abortRequested.set(true);
                    closeQuietly(streamRef.get());
                });

        try (ClientLease lease = acquireClient(config)) {
            if (isCancelled(cancellationRequested)) {
                throw new CancellationException("OpenAI stream cancelled before start.");
            }
            ResponseCreateParams params = buildParams(prompt, config, PreparedSchema.none(), false);
            AsyncStreamResponse<ResponseStreamEvent> stream =
                    lease.client().async().responses().createStreaming(params);
            streamRef.set(stream);
            if (abortRequested.get() || isCancelled(cancellationRequested)) {
                stream.close();
                throw new CancellationException("OpenAI stream cancelled before response.");
            }
            stream.subscribe(event -> {
                if (abortRequested.get() || isCancelled(cancellationRequested)) {
                    throw new CancellationException("OpenAI stream cancelled.");
                }
                accumulator.accumulate(event);
                if (event.isOutputTextDelta()) {
                    String delta = event.asOutputTextDelta().delta();
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        if (onChunk != null) {
                            onChunk.accept(delta);
                        }
                    }
                }
            });
            try {
                stream.onCompleteFuture().get(Math.max(1, resolvedTimeoutSeconds), TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                stream.close();
                throw AiProviderStreamException.timeout(PROVIDER, exception);
            } catch (ExecutionException exception) {
                if (abortRequested.get() || isCancelled(cancellationRequested)) {
                    throw new CancellationException("OpenAI stream cancelled.");
                }
                throw classifyStreamFailure(unwrap(exception));
            }
            if (abortRequested.get() || isCancelled(cancellationRequested)) {
                throw new CancellationException("OpenAI stream cancelled.");
            }

            Response response;
            try {
                response = accumulator.response();
            } catch (IllegalStateException exception) {
                throw AiProviderStreamException.unknown(
                        PROVIDER,
                        new IllegalStateException("OpenAI stream ended without a terminal response.", exception));
            }
            validateCompletedStreamResponse(response);
            captureInvocationMetadata(config, response);
            String accumulated = text.toString();
            return accumulated.isBlank() ? extractResponseText(response) : accumulated;
        } catch (CancellationException exception) {
            throw exception;
        } catch (AiProviderStreamException exception) {
            throw exception;
        } catch (Exception exception) {
            if (abortRequested.get() || isCancelled(cancellationRequested)) {
                throw new CancellationException("OpenAI stream cancelled.");
            }
            throw classifyStreamFailure(exception);
        } finally {
            registration.close();
            closeQuietly(streamRef.getAndSet(null));
        }
    }

    private ResponseCreateParams buildParams(
            String prompt,
            AiCallConfig config,
            PreparedSchema preparedSchema,
            boolean jsonMode) {
        String resolvedModel = resolveModel(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        boolean explicitMaxTokens = config != null && config.getMaxTokens() != null;
        if (jsonMode && requiresReasoningTokenBudget(resolvedModel) && !explicitMaxTokens) {
            resolvedMaxTokens = Math.max(resolvedMaxTokens, Math.max(1, jsonMinCompletionTokens));
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(resolvedModel)
                .input(prompt)
                .maxOutputTokens(Math.max(MIN_OUTPUT_TOKENS, resolvedMaxTokens))
                .store(false);
        if (!usesFixedDefaultTemperature(resolvedModel)) {
            builder.temperature(resolveTemperature(config));
        }
        reasoningEffort(resolvedModel, resolvedMaxTokens)
                .ifPresent(effort -> builder.reasoning(Reasoning.builder().effort(effort).build()));
        if (jsonMode) {
            builder.text(buildTextConfig(preparedSchema));
        }
        addUsageMetadata(builder, config, jsonMode);
        addHostedSkills(builder, config);
        return builder.build();
    }

    /**
     * Attaches only bounded, non-content operational metadata to the provider request. Prompts,
     * responses, credentials, tenant identifiers and user identifiers are deliberately excluded.
     */
    private void addUsageMetadata(ResponseCreateParams.Builder response, AiCallConfig config, boolean jsonMode) {
        ResponseCreateParams.Metadata.Builder metadata = ResponseCreateParams.Metadata.builder()
                .putAdditionalProperty("praxis_origin_class", JsonValue.from(metadataValue(usageOriginClass, "unspecified")))
                .putAdditionalProperty("praxis_response_mode", JsonValue.from(jsonMode ? "structured-json" : "text"));
        if (config != null) {
            putMetadata(metadata, "praxis_environment", config.getEnvironment());
            if (config.getExecutionProfile() != null) {
                putMetadata(
                        metadata,
                        "praxis_execution_profile",
                        config.getExecutionProfile().name().toLowerCase(Locale.ROOT));
            }
            if (config.getInvocationTrace() != null) {
                AiProviderInvocationTelemetry trace = config.getInvocationTrace().snapshot();
                putMetadata(metadata, "praxis_call_phase", trace.phase());
                putMetadata(metadata, "praxis_call_attempt", Integer.toString(trace.attempt()));
            }
        }
        response.metadata(metadata.build());
    }

    private void putMetadata(ResponseCreateParams.Metadata.Builder metadata, String key, String value) {
        String normalized = metadataValue(value, null);
        if (normalized != null) {
            metadata.putAdditionalProperty(key, JsonValue.from(normalized));
        }
    }

    private String metadataValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private void addHostedSkills(ResponseCreateParams.Builder response, AiCallConfig config) {
        if (config == null || config.getExecutionProfile() == null) {
            return;
        }
        List<OpenAiHostedSkillProperties.Reference> references =
                hostedSkillProperties.referencesFor(config.getExecutionProfile());
        if (references.isEmpty()) {
            return;
        }
        ContainerAuto.Builder environment = ContainerAuto.builder();
        references.forEach(reference -> environment.addSkill(SkillReference.builder()
                .skillId(reference.getId().trim())
                .version(reference.resolvedVersion())
                .build()));
        response.addTool(FunctionShellTool.builder()
                .environment(environment.build())
                .build());
    }

    private ResponseTextConfig buildTextConfig(PreparedSchema preparedSchema) {
        if (preparedSchema.hasSchema()) {
            ResponseFormatTextJsonSchemaConfig.Schema schema = toSdkSchema(preparedSchema.jsonSchema());
            ResponseFormatTextJsonSchemaConfig format = ResponseFormatTextJsonSchemaConfig.builder()
                    .name(STRUCTURED_OUTPUT_NAME)
                    .schema(schema)
                    .strict(true)
                    .build();
            return ResponseTextConfig.builder().format(format).build();
        }
        return ResponseTextConfig.builder()
                .format(ResponseFormatJsonObject.builder().build())
                .build();
    }

    private ResponseFormatTextJsonSchemaConfig.Schema toSdkSchema(String jsonSchema) {
        try {
            JsonNode schemaNode = objectMapper.readTree(jsonSchema);
            if (schemaNode == null || !schemaNode.isObject()) {
                throw new IllegalArgumentException("Structured output schema must be a JSON object.");
            }
            validateStrictSchema(schemaNode, "$");
            Map<String, JsonValue> fields = new LinkedHashMap<>();
            schemaNode.fields().forEachRemaining(entry ->
                    fields.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
            return ResponseFormatTextJsonSchemaConfig.Schema.builder()
                    .additionalProperties(fields)
                    .build();
        } catch (Exception exception) {
            String detail = exception instanceof IllegalArgumentException
                    ? exception.getMessage()
                    : "schema JSON could not be parsed";
            log.warn("[SpringAiOpenAiService] Invalid strict structured output schema: {}", detail);
            throw AiProviderCallException.fromHttpStatus(
                    PROVIDER,
                    400,
                    "Invalid strict structured output schema: " + detail);
        }
    }

    /**
     * Validates the subset required by OpenAI strict Structured Outputs before the provider call.
     * Every object must be closed and every declared property must be required; optional values are
     * represented by nullable types in the schema itself.
     */
    private void validateStrictSchema(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        if (declaresObjectType(schema)) {
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                throw new IllegalArgumentException(path + " object must declare properties");
            }
            if (!schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean()) {
                throw new IllegalArgumentException(path + " object must set additionalProperties=false");
            }
            Set<String> propertyNames = new LinkedHashSet<>();
            properties.fieldNames().forEachRemaining(propertyNames::add);
            Set<String> requiredNames = new LinkedHashSet<>();
            JsonNode required = schema.path("required");
            AtomicBoolean invalidRequiredEntry = new AtomicBoolean(false);
            if (required.isArray()) {
                required.forEach(value -> {
                    if (value.isTextual()) {
                        requiredNames.add(value.asText());
                    } else {
                        invalidRequiredEntry.set(true);
                    }
                });
            }
            if (!required.isArray()
                    || invalidRequiredEntry.get()
                    || required.size() != propertyNames.size()
                    || !requiredNames.equals(propertyNames)) {
                throw new IllegalArgumentException(
                        path + " required must contain every declared property exactly once");
            }
        }
        schema.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String childPath = path + "." + entry.getKey();
            if (value.isObject()) {
                validateStrictSchema(value, childPath);
            } else if (value.isArray()) {
                for (int index = 0; index < value.size(); index++) {
                    validateStrictSchema(value.get(index), childPath + "[" + index + "]");
                }
            }
        });
    }

    private boolean declaresObjectType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return "object".equals(type.asText());
        }
        if (type.isArray()) {
            for (JsonNode value : type) {
                if (value.isTextual() && "object".equals(value.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private PreparedSchema prepareSchema(AiJsonSchema schema) {
        if (schema == null) {
            return PreparedSchema.none();
        }
        if (schema.hasTargetClass()) {
            BeanOutputConverter<?> converter = new BeanOutputConverter<>(schema.targetClass(), objectMapper);
            return new PreparedSchema(converter.getJsonSchema(), converter);
        }
        return schema.hasJsonSchema()
                ? new PreparedSchema(schema.jsonSchema(), null)
                : PreparedSchema.none();
    }

    private void validateCompletedResponse(OpenAiResponseProjection response) {
        String status = response.status() == null ? "unknown" : response.status();
        if (!"completed".equals(status)) {
            throw AiProviderCallException.unknown(
                    PROVIDER,
                    new IllegalStateException("OpenAI response status=" + status + response.failureSuffix()));
        }
        String refusal = response.refusal();
        if (refusal != null) {
            throw AiProviderCallException.unknown(
                    PROVIDER,
                    new IllegalStateException("OpenAI refused the response: " + summarizeErrorBody(refusal)));
        }
        if (response.text().isBlank()) {
            throw AiProviderCallException.unknown(
                    PROVIDER, new IllegalStateException("OpenAI returned empty response content"));
        }
    }

    private void validateCompletedStreamResponse(Response response) {
        String status = responseStatus(response);
        if (!"completed".equals(status)) {
            throw AiProviderStreamException.unknown(
                    PROVIDER,
                    new IllegalStateException("OpenAI stream status=" + status + responseFailureSuffix(response)));
        }
        String refusal = extractRefusal(response);
        if (refusal != null) {
            throw AiProviderStreamException.unknown(
                    PROVIDER,
                    new IllegalStateException("OpenAI refused the streamed response: " + summarizeErrorBody(refusal)));
        }
        if (extractResponseText(response).isBlank()) {
            throw AiProviderStreamException.unknown(
                    PROVIDER, new IllegalStateException("OpenAI returned empty streamed response content"));
        }
    }

    private String extractResponseText(Response response) {
        StringBuilder text = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) {
                continue;
            }
            ResponseOutputMessage message = item.asMessage();
            for (ResponseOutputMessage.Content content : message.content()) {
                if (content.isOutputText()) {
                    text.append(content.asOutputText().text());
                }
            }
        }
        return text.toString();
    }

    private String extractRefusal(Response response) {
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) {
                continue;
            }
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isRefusal()) {
                    return content.asRefusal().refusal();
                }
            }
        }
        return null;
    }

    private String responseFailureSuffix(Response response) {
        if (response.error().isPresent()) {
            return " error=" + summarizeErrorBody(response.error().get().toString());
        }
        if (response.incompleteDetails().isPresent()) {
            return " incomplete=" + summarizeErrorBody(response.incompleteDetails().get().toString());
        }
        return "";
    }

    private String responseStatus(Response response) {
        return response.status().map(status -> status.asString()).orElse("unknown");
    }

    private void captureInvocationMetadata(AiCallConfig config, Response response) {
        AiProviderInvocationTrace trace = config != null ? config.getInvocationTrace() : null;
        if (trace == null || response == null) {
            return;
        }
        ResponseUsage usage = response.usage().orElse(null);
        trace.providerResponse(
                TRANSPORT,
                response.id(),
                response.model().asString(),
                responseStatus(response),
                usage == null ? null : safeInt(usage.inputTokens()),
                usage == null ? null : safeInt(usage.outputTokens()),
                usage == null ? null : safeInt(usage.inputTokensDetails().cachedTokens()),
                null,
                usage == null ? null : safeInt(usage.totalTokens()));
    }

    private void captureInvocationMetadata(AiCallConfig config, OpenAiResponseProjection response) {
        AiProviderInvocationTrace trace = config != null ? config.getInvocationTrace() : null;
        if (trace == null || response == null) {
            return;
        }
        trace.providerResponse(
                TRANSPORT,
                response.id(),
                response.model(),
                response.status() == null ? "unknown" : response.status(),
                response.inputTokens(),
                response.outputTokens(),
                response.cachedInputTokens(),
                null,
                response.totalTokens());
    }

    private ClientLease acquireClient(AiCallConfig config) {
        String resolvedKey = requireApiKey(config);
        int resolvedTimeout = resolveTimeoutSeconds(config);
        boolean transientClient = config != null
                && (trimToNull(config.getApiKey()) != null
                        || (config.getTimeoutSeconds() != null && config.getTimeoutSeconds() > 0));
        if (transientClient) {
            return new ClientLease(createClient(resolvedKey, resolvedTimeout), true);
        }
        DefaultClientHolder holder = defaultClient.get();
        if (holder != null) {
            return new ClientLease(holder.client(), false);
        }
        synchronized (defaultClient) {
            holder = defaultClient.get();
            if (holder == null) {
                holder = new DefaultClientHolder(createClient(resolvedKey, resolvedTimeout));
                defaultClient.set(holder);
            }
            return new ClientLease(holder.client(), false);
        }
    }

    private OpenAIClient createClient(String resolvedKey, int resolvedTimeoutSeconds) {
        return OpenAIOkHttpClient.builder()
                .apiKey(resolvedKey)
                .baseUrl(resolveSdkBaseUrl(baseUrl))
                .timeout(Duration.ofSeconds(Math.max(1, resolvedTimeoutSeconds)))
                .maxRetries(0)
                .build();
    }

    @PreDestroy
    void closeDefaultClient() {
        DefaultClientHolder holder = defaultClient.getAndSet(null);
        if (holder != null) {
            holder.client().close();
        }
    }

    private Optional<ReasoningEffort> reasoningEffort(String modelName, int maxOutputTokens) {
        if (!supportsCompactReasoningEffort(modelName) || maxOutputTokens > 2048) {
            return Optional.empty();
        }
        if (usesLightReasoningProfile(modelName)) {
            return Optional.of(ReasoningEffort.LOW);
        }
        return Optional.of(supportsNoReasoningEffort(modelName) ? ReasoningEffort.NONE : ReasoningEffort.LOW);
    }

    private boolean usesLightReasoningProfile(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return List.of(lightReasoningModels.split(",")).stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(configured -> !configured.isBlank())
                .anyMatch(configured -> normalized.equals(configured) || normalized.startsWith(configured + "-"));
    }

    private boolean supportsNoReasoningEffort(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        String prefix = "gpt-5.";
        if (!normalized.startsWith(prefix)) {
            return false;
        }
        int end = prefix.length();
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == prefix.length()) {
            return false;
        }
        try {
            return Integer.parseInt(normalized.substring(prefix.length(), end)) >= 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean requiresReasoningTokenBudget(String modelName) {
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
        return requiresReasoningTokenBudget(modelName);
    }

    private boolean supportsCompactReasoningEffort(String modelName) {
        return modelName != null && modelName.trim().toLowerCase().startsWith("gpt-5");
    }

    private String resolveModel(AiCallConfig config) {
        String override = config != null ? trimToNull(config.getModel()) : null;
        return override != null ? override : model;
    }

    private double resolveTemperature(AiCallConfig config) {
        return config != null && config.getTemperature() != null ? config.getTemperature() : temperature;
    }

    private int resolveMaxTokens(AiCallConfig config) {
        return config != null && config.getMaxTokens() != null && config.getMaxTokens() > 0
                ? config.getMaxTokens()
                : maxTokens;
    }

    private int resolveTimeoutSeconds(AiCallConfig config) {
        return config != null && config.getTimeoutSeconds() != null && config.getTimeoutSeconds() > 0
                ? config.getTimeoutSeconds()
                : timeoutSeconds;
    }

    private String requireApiKey(AiCallConfig config) {
        String key = config != null ? trimToNull(config.getApiKey()) : null;
        if (key == null) {
            key = trimToNull(apiKey);
        }
        if (key == null) {
            throw AiProviderCallException.fromHttpStatus(PROVIDER, 401, "API key not configured");
        }
        return key;
    }

    private AiProviderCallException classifyCallFailure(Throwable failure) {
        Throwable root = unwrap(failure);
        if (root instanceof AiProviderCallException exception) {
            return exception;
        }
        if (root instanceof OpenAIServiceException exception) {
            return AiProviderCallException.fromHttpStatusSanitized(
                    PROVIDER,
                    exception.statusCode(),
                    summarizeErrorBody(exception.getMessage()),
                    null,
                    openAiRequestId(exception),
                    exception);
        }
        if (isTimeout(root)) {
            return AiProviderCallException.timeout(PROVIDER, root);
        }
        if (root instanceof OpenAIIoException || isTransport(root)) {
            return AiProviderCallException.transport(PROVIDER, root);
        }
        return AiProviderCallException.unknown(PROVIDER, root);
    }

    private String openAiRequestId(OpenAIServiceException failure) {
        if (failure.headers() == null) {
            return null;
        }
        return failure.headers().values("x-request-id").stream().findFirst().orElse(null);
    }

    private AiProviderStreamException classifyStreamFailure(Throwable failure) {
        Throwable root = unwrap(failure);
        if (root instanceof AiProviderStreamException exception) {
            return exception;
        }
        if (root instanceof AiProviderCallException exception) {
            if (exception.getStatusCode() != null) {
                return AiProviderStreamException.fromHttpStatus(
                        PROVIDER, exception.getStatusCode(), exception.getMessage());
            }
            return switch (exception.getKind()) {
                case TIMEOUT -> AiProviderStreamException.timeout(PROVIDER, exception);
                case TRANSPORT -> AiProviderStreamException.transport(PROVIDER, exception);
                default -> AiProviderStreamException.unknown(PROVIDER, exception);
            };
        }
        if (root instanceof OpenAIServiceException exception) {
            return AiProviderStreamException.fromHttpStatus(
                    PROVIDER, exception.statusCode(), summarizeErrorBody(exception.getMessage()));
        }
        if (isTimeout(root)) {
            return AiProviderStreamException.timeout(PROVIDER, root);
        }
        if (root instanceof OpenAIIoException || isTransport(root)) {
            return AiProviderStreamException.transport(PROVIDER, root);
        }
        return AiProviderStreamException.unknown(PROVIDER, root);
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (current instanceof InterruptedIOException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTransport(Throwable failure) {
        return failure instanceof IOException
                || failure instanceof InterruptedIOException
                || failure instanceof java.net.ConnectException
                || failure instanceof java.net.SocketException
                || failure instanceof java.net.UnknownHostException;
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

    private void closeQuietly(AsyncStreamResponse<?> stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // Best-effort SDK resource close.
            }
        }
    }

    private JsonNode parseJson(String text) {
        try {
            return objectMapper.readTree(sanitizeJsonText(text));
        } catch (Exception exception) {
            log.warn("[SpringAiOpenAiService] JSON parse failed.", exception);
            return null;
        }
    }

    private String sanitizeJsonText(String text) {
        String cleaned = text.replaceAll("```json\\n?|\\n?```", "").trim();
        int brace = cleaned.indexOf('{');
        int bracket = cleaned.indexOf('[');
        int firstOpen = brace == -1 ? bracket : bracket == -1 ? brace : Math.min(brace, bracket);
        return firstOpen > 0 ? cleaned.substring(firstOpen).trim() : cleaned;
    }

    private String buildModelsUrl(String base) {
        return base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
    }

    private String resolveSdkBaseUrl(String value) {
        String resolved = resolveBaseUrl(value);
        return resolved.endsWith("/v1") ? resolved : resolved + "/v1";
    }

    private String resolveBaseUrl(String value) {
        String resolved = trimToNull(value);
        if (resolved == null) {
            resolved = "https://api.openai.com";
        }
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return trimToNull(value.asText());
    }

    private Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isIntegralNumber() ? safeInt(value.asLong()) : null;
    }

    private String summarizeErrorBody(String body) {
        String trimmed = trimToNull(body);
        return trimmed == null ? null : trimmed.substring(0, Math.min(trimmed.length(), 180));
    }

    private Integer safeInt(long value) {
        return value < 0 ? null : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PreparedSchema(String jsonSchema, BeanOutputConverter<?> converter) {
        static PreparedSchema none() {
            return new PreparedSchema(null, null);
        }

        boolean hasSchema() {
            return jsonSchema != null && !jsonSchema.isBlank();
        }
    }

    private record OpenAiResponseProjection(
            String text,
            String refusal,
            String status,
            boolean errorPresent,
            boolean incompleteDetailsPresent,
            String id,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Integer cachedInputTokens,
            Integer totalTokens) {

        private OpenAiResponseProjection {
            text = text == null ? "" : text;
        }

        String failureSuffix() {
            if (errorPresent) {
                return " error=present";
            }
            if (incompleteDetailsPresent) {
                return " incomplete=present";
            }
            return "";
        }
    }

    private record DefaultClientHolder(OpenAIClient client) {}

    private record ClientLease(OpenAIClient client, boolean closeAfterUse) implements AutoCloseable {
        @Override
        public void close() {
            if (closeAfterUse) {
                client.close();
            }
        }
    }
}
