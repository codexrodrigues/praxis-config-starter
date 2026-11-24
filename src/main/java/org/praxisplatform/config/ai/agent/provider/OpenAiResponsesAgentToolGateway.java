package org.praxisplatform.config.ai.agent.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import jakarta.annotation.PreDestroy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.praxisplatform.config.ai.agent.model.AgentConversationItem;
import org.praxisplatform.config.ai.agent.model.AgentConversationRole;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.model.AgentToolResult;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiThinkingProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/**
 * Gateway para OpenAI utilizando o SDK oficial {@code com.openai:openai-java} e a
 * {@code Responses API} com ciclo explicito de {@code FunctionTool} e {@code FunctionCallOutput}.
 */
public class OpenAiResponsesAgentToolGateway implements AiToolCallingProvider {

    public static final String PROVIDER_ID = "openai";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int MAX_CACHED_CLIENTS = 32;
    private static final long MIN_RETRY_AFTER_MS = 1_000L;
    private static final long MAX_RETRY_AFTER_MS = 600_000L;
    private static final Set<String> UNSUPPORTED_STRICT_KEYWORDS = Set.of(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern",
            "minItems", "maxItems", "uniqueItems",
            "minProperties", "maxProperties",
            "default", "propertyNames", "patternProperties",
            "unevaluatedProperties", "dependentRequired", "dependentSchemas",
            "if", "then", "else", "not");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private record ClientKey(String apiKey, String baseUrl) {}

    @FunctionalInterface
    public interface OpenAiExchange {
        Response create(String apiKey, String baseUrl, Integer timeoutSeconds, ResponseCreateParams params);
    }

    private final ObjectMapper objectMapper;
    private final String defaultApiKey;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final OpenAiExchange exchange;
    private final Map<ClientKey, OpenAIClient> clientCache = new ConcurrentHashMap<>();

    @Autowired
    public OpenAiResponsesAgentToolGateway(
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") String defaultApiKey,
            @Value("${spring.ai.openai.base-url:}") String defaultBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:" + DEFAULT_MODEL + "}") String defaultModel) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultBaseUrl = normalizeBaseUrl(defaultBaseUrl);
        this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : DEFAULT_MODEL;
        this.exchange = null;
    }

    public OpenAiResponsesAgentToolGateway(
            ObjectMapper objectMapper,
            String defaultApiKey,
            String defaultBaseUrl,
            String defaultModel,
            OpenAiExchange exchange) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultBaseUrl = normalizeBaseUrl(defaultBaseUrl);
        this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : DEFAULT_MODEL;
        this.exchange = Objects.requireNonNull(exchange, "exchange is required");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean hasConfiguredCredentials(AiCallConfig callConfig) {
        return StringUtils.hasText(firstNonBlank(callConfig != null ? callConfig.getApiKey() : null, defaultApiKey));
    }

    @Override
    public AgentModelTurnResponse generateTurn(
            AgentModelTurnRequest request,
            AgentModelStreamListener listener) {
        Objects.requireNonNull(request, "request is required");
        AiCallConfig callConfig = request.callConfig();
        String apiKey = firstNonBlank(callConfig != null ? callConfig.getApiKey() : null, defaultApiKey);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("OpenAI API key is not configured.");
        }
        String model = firstNonBlank(callConfig != null ? callConfig.getModel() : null, defaultModel);

        ResponseCreateParams params = toResponseParams(request, model, callConfig);
        Integer timeoutSeconds = callConfig != null ? callConfig.getTimeoutSeconds() : null;
        try {
            Response response = exchange != null
                    ? exchange.create(apiKey, defaultBaseUrl, timeoutSeconds, params)
                    : invokeSdk(apiKey, defaultBaseUrl, timeoutSeconds, params);
            return fromOpenAiResponse(model, response, listener);
        } catch (RuntimeException ex) {
            throw mapProviderException(ex);
        }
    }

    ResponseCreateParams toResponseParams(
            AgentModelTurnRequest request,
            String model,
            AiCallConfig callConfig) {
        List<AgentConversationItem> conversation = request.conversation() == null
                ? List.of()
                : request.conversation();
        List<ResponseInputItem> inputItems = toInputItems(conversation);

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .store(false)
                .inputOfResponse(inputItems);
        if (StringUtils.hasText(request.systemInstruction())) {
            builder.instructions(request.systemInstruction());
        }
        if (callConfig != null && callConfig.getTemperature() != null && !isReasoningModel(model)) {
            builder.temperature(callConfig.getTemperature());
        }
        if (callConfig != null && callConfig.getMaxTokens() != null) {
            builder.maxOutputTokens(callConfig.getMaxTokens().longValue());
        }
        if (isReasoningModel(model)) {
            builder.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
            if (callConfig != null && callConfig.getThinkingProfile() != null) {
                builder.reasoning(Reasoning.builder()
                        .effort(resolveReasoningEffort(model, callConfig.getThinkingProfile()))
                        .build());
            }
        }
        boolean nativeOpenAi = isNativeOpenAiEndpoint();
        if (nativeOpenAi && callConfig != null && StringUtils.hasText(callConfig.getPromptCacheKey())) {
            builder.promptCacheKey(callConfig.getPromptCacheKey().trim());
        }
        if (request.responseJsonSchema() != null && !request.responseJsonSchema().isEmpty()) {
            ResponseFormatTextJsonSchemaConfig.Schema.Builder schemaBuilder =
                    ResponseFormatTextJsonSchemaConfig.Schema.builder();
            for (Map.Entry<String, Object> entry : request.responseJsonSchema().entrySet()) {
                schemaBuilder.putAdditionalProperty(
                        entry.getKey(),
                        JsonValue.fromJsonNode(objectMapper.valueToTree(entry.getValue())));
            }
            boolean strictOutput = nativeOpenAi && isStrictSchemaCompatible(request.responseJsonSchema());
            builder.text(ResponseTextConfig.builder()
                    .format(ResponseFormatTextJsonSchemaConfig.builder()
                            .name("agent_structured_response")
                            .schema(schemaBuilder.build())
                            .strict(strictOutput)
                            .build())
                    .build());
        }
        if (!request.tools().isEmpty()) {
            for (AgentToolDeclaration tool : request.tools()) {
                builder.addTool(toFunctionTool(tool));
            }
            if (request.disableToolCalling()) {
                builder.toolChoice(ToolChoiceOptions.NONE);
            }
        }
        return builder.build();
    }

    static String normalizeBaseUrl(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        String withoutTrailingSlash = trimmed.replaceAll("/+$", "");
        try {
            URI uri = URI.create(withoutTrailingSlash);
            String host = uri.getHost();
            String path = uri.getPath();
            if ("api.openai.com".equalsIgnoreCase(host) && (path == null || path.isEmpty())) {
                return withoutTrailingSlash + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
            // Keep caller-supplied URI if non-standard
        }
        return withoutTrailingSlash;
    }

    private static boolean isReasoningModel(String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        String lower = model.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.startsWith("gpt-5");
    }

    private List<ResponseInputItem> toInputItems(List<AgentConversationItem> conversation) {
        List<ResponseInputItem> items = new ArrayList<>();
        if (conversation == null) {
            return items;
        }
        for (AgentConversationItem item : conversation) {
            if (item == null || item.role() == null) {
                continue;
            }
            if (item.role() == AgentConversationRole.USER) {
                if (StringUtils.hasText(item.text())) {
                    items.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content(item.text())
                            .build()));
                }
            } else if (item.role() == AgentConversationRole.ASSISTANT) {
                for (ResponseReasoningItem reasoningItem : extractOpaqueReasoningItems(item)) {
                    items.add(ResponseInputItem.ofReasoning(reasoningItem));
                }
                if (StringUtils.hasText(item.text())) {
                    items.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.ASSISTANT)
                            .content(item.text())
                            .build()));
                }
                for (AgentToolCall call : item.toolCalls()) {
                    String argsJson = StringUtils.hasText(call.rawArgumentsJson())
                            ? call.rawArgumentsJson()
                            : writeJson(call.arguments());
                    items.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                            .callId(call.callId())
                            .name(call.name())
                            .arguments(argsJson)
                            .build()));
                }
            } else if (item.role() == AgentConversationRole.TOOL_RESULT) {
                for (AgentToolResult result : item.toolResults()) {
                    items.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                            .callId(result.callId())
                            .output(writeJson(result.output()))
                            .build()));
                }
            }
        }
        return items;
    }

    private FunctionTool toFunctionTool(AgentToolDeclaration tool) {
        Map<String, Object> schema = new LinkedHashMap<>(tool.parametersJsonSchema());
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("additionalProperties", Boolean.FALSE);
        if (!schema.containsKey("required") && !tool.requiredParameters().isEmpty()) {
            schema.put("required", tool.requiredParameters());
        }
        boolean strictTool = isNativeOpenAiEndpoint() && isStrictSchemaCompatible(schema);
        FunctionTool.Parameters.Builder paramBuilder = FunctionTool.Parameters.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            paramBuilder.putAdditionalProperty(
                    entry.getKey(),
                    JsonValue.fromJsonNode(objectMapper.valueToTree(entry.getValue())));
        }
        return FunctionTool.builder()
                .name(tool.name())
                .description(tool.description())
                .strict(strictTool)
                .parameters(paramBuilder.build())
                .build();
    }

    private AgentModelTurnResponse fromOpenAiResponse(
            String model,
            Response response,
            AgentModelStreamListener listener) {
        if (response == null) {
            return new AgentModelTurnResponse(
                    PROVIDER_ID,
                    model,
                    "",
                    List.of(),
                    AgentTokenUsage.ZERO,
                    "empty",
                    null,
                    Map.of());
        }
        StringBuilder textBuilder = new StringBuilder();
        List<AgentToolCall> calls = new ArrayList<>();
        List<ResponseReasoningItem> reasoningItems = new ArrayList<>();

        for (ResponseOutputItem outputItem : response.output()) {
            if (outputItem.isMessage()) {
                ResponseOutputMessage message = outputItem.asMessage();
                for (ResponseOutputMessage.Content part : message.content()) {
                    if (part.isOutputText()) {
                        String text = part.asOutputText().text();
                        if (StringUtils.hasText(text)) {
                            textBuilder.append(text);
                            if (listener != null) {
                                listener.onTextDelta(text);
                            }
                        }
                    }
                }
            } else if (outputItem.isFunctionCall()) {
                ResponseFunctionToolCall fc = outputItem.asFunctionCall();
                Map<String, Object> parsedArgs = parseJsonMap(fc.arguments());
                calls.add(new AgentToolCall(fc.callId(), fc.name(), parsedArgs, null, fc.arguments()));
            } else if (outputItem.isReasoning()) {
                reasoningItems.add(outputItem.asReasoning());
            }
        }

        AgentTokenUsage usage = response.usage()
                .map(this::toUsage)
                .orElse(AgentTokenUsage.ZERO);
        String finishReason = response.status().map(Object::toString).orElse("completed");
        Map<String, Object> opaqueState = new LinkedHashMap<>();
        if (StringUtils.hasText(response.id())) {
            opaqueState.put("previousResponseId", response.id());
        }
        if (!reasoningItems.isEmpty()) {
            opaqueState.put("openaiReasoningItems", List.copyOf(reasoningItems));
        }
        return new AgentModelTurnResponse(
                PROVIDER_ID,
                model,
                textBuilder.toString(),
                calls,
                usage,
                finishReason,
                response.id(),
                opaqueState.isEmpty() ? Map.of() : Map.copyOf(opaqueState));
    }

    private AgentTokenUsage toUsage(ResponseUsage usage) {
        long cacheRead = usage._inputTokensDetails().asKnown()
                .flatMap(d -> d._cachedTokens().asKnown())
                .orElse(0L);
        long cacheWrite = usage._inputTokensDetails().asKnown()
                .flatMap(d -> d._cacheWriteTokens().asKnown())
                .orElse(0L);
        long reasoning = usage._outputTokensDetails().asKnown()
                .flatMap(d -> d._reasoningTokens().asKnown())
                .orElse(0L);
        return new AgentTokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                cacheRead,
                cacheWrite,
                reasoning,
                cacheRead > 0L);
    }

    private boolean isNativeOpenAiEndpoint() {
        if (!StringUtils.hasText(defaultBaseUrl)) {
            return true;
        }
        try {
            String host = URI.create(defaultBaseUrl).getHost();
            return host == null || "api.openai.com".equalsIgnoreCase(host);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean isStrictSchemaCompatible(Map<String, Object> schema) {
        return isStrictNodeCompatible(schema);
    }

    private static boolean isStrictNodeCompatible(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return true;
        }
        for (Object rawKey : map.keySet()) {
            if (rawKey != null && UNSUPPORTED_STRICT_KEYWORDS.contains(String.valueOf(rawKey))) {
                return false;
            }
        }
        Object rawType = map.get("type");
        boolean isObjectSchema = "object".equals(rawType) || map.containsKey("properties");
        if (isObjectSchema) {
            Object addlProps = map.get("additionalProperties");
            if (!Boolean.FALSE.equals(addlProps)) {
                return false;
            }
            Object rawProperties = map.get("properties");
            if (!(rawProperties instanceof Map<?, ?> propsMap) || propsMap.isEmpty()) {
                return false;
            }
            Set<String> declaredProps = new LinkedHashSet<>();
            propsMap.keySet().forEach(k -> declaredProps.add(String.valueOf(k)));
            Object rawRequired = map.get("required");
            if (!(rawRequired instanceof Collection<?> reqCol)) {
                return false;
            }
            Set<String> requiredSet = new LinkedHashSet<>();
            reqCol.forEach(r -> requiredSet.add(String.valueOf(r)));
            if (!requiredSet.containsAll(declaredProps)) {
                return false;
            }
            for (Object childSchema : propsMap.values()) {
                if (!isStrictNodeCompatible(childSchema)) {
                    return false;
                }
            }
        }
        for (String key : List.of("items")) {
            if (map.containsKey(key) && !isStrictNodeCompatible(map.get(key))) {
                return false;
            }
        }
        for (String arrayKey : List.of("anyOf", "oneOf", "allOf")) {
            if (map.get(arrayKey) instanceof Collection<?> variants) {
                for (Object variant : variants) {
                    if (!isStrictNodeCompatible(variant)) {
                        return false;
                    }
                }
            }
        }
        for (String defsKey : List.of("$defs", "definitions")) {
            if (map.get(defsKey) instanceof Map<?, ?> defs) {
                for (Object defSchema : defs.values()) {
                    if (!isStrictNodeCompatible(defSchema)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static ReasoningEffort resolveReasoningEffort(String model, AiThinkingProfile profile) {
        String lower = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return switch (profile) {
            case OFF -> {
                if (lower.matches("^gpt-5\\.[1-9].*")) {
                    yield ReasoningEffort.NONE;
                }
                if (lower.startsWith("gpt-5")) {
                    yield ReasoningEffort.MINIMAL;
                }
                yield ReasoningEffort.LOW;
            }
            case FAST_LOOKUP -> ReasoningEffort.LOW;
            case OPERATIONAL_AUDIT -> ReasoningEffort.MEDIUM;
            case DEEP_ANALYSIS -> ReasoningEffort.HIGH;
        };
    }

    private static List<ResponseReasoningItem> extractOpaqueReasoningItems(AgentConversationItem item) {
        if (item == null || item.providerOpaqueState() == null) {
            return List.of();
        }
        Object raw = item.providerOpaqueState().get("openaiReasoningItems");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<ResponseReasoningItem> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof ResponseReasoningItem reasoningItem
                    && reasoningItem.encryptedContent().filter(StringUtils::hasText).isPresent()) {
                result.add(reasoningItem);
            }
        }
        return result;
    }

    private static RuntimeException mapProviderException(RuntimeException ex) {
        if (ex instanceof AiProviderCallException) {
            return ex;
        }
        if (ex instanceof OpenAIServiceException svcEx) {
            Instant retryAfter = parseRetryAfter(svcEx.headers());
            return AiProviderCallException.fromHttpStatusSanitized(
                    PROVIDER_ID, svcEx.statusCode(), svcEx.getMessage(), retryAfter, svcEx);
        }
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof SocketTimeoutException || cursor instanceof HttpTimeoutException) {
                return AiProviderCallException.timeout(PROVIDER_ID, ex);
            }
            cursor = cursor.getCause();
        }
        return ex;
    }

    static Instant parseRetryAfter(Headers headers) {
        if (headers == null) {
            return null;
        }
        String retryAfterMs = firstHeaderIgnoreCase(headers, "retry-after-ms");
        if (StringUtils.hasText(retryAfterMs)) {
            try {
                long ms = Long.parseLong(retryAfterMs.trim());
                long clampedMs = Math.max(MIN_RETRY_AFTER_MS, Math.min(MAX_RETRY_AFTER_MS, ms));
                return Instant.now().plusMillis(clampedMs);
            } catch (NumberFormatException ignored) {
                // ignore malformed header
            }
        }
        String retryAfter = firstHeaderIgnoreCase(headers, "retry-after");
        if (!StringUtils.hasText(retryAfter)) {
            return null;
        }
        String trimmed = retryAfter.trim();
        try {
            double seconds = Double.parseDouble(trimmed);
            long ms = Math.round(seconds * 1000.0d);
            long clampedMs = Math.max(MIN_RETRY_AFTER_MS, Math.min(MAX_RETRY_AFTER_MS, ms));
            return Instant.now().plusMillis(clampedMs);
        } catch (NumberFormatException ignored) {
            // Try RFC-1123 HTTP date format
        }
        try {
            Instant target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long deltaMs = Duration.between(Instant.now(), target).toMillis();
            if (deltaMs <= 0L) {
                return null;
            }
            long clampedMs = Math.max(MIN_RETRY_AFTER_MS, Math.min(MAX_RETRY_AFTER_MS, deltaMs));
            return Instant.now().plusMillis(clampedMs);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String firstHeaderIgnoreCase(Headers headers, String targetName) {
        for (String name : headers.names()) {
            if (name != null && name.equalsIgnoreCase(targetName)) {
                List<String> values = headers.values(name);
                if (values != null && !values.isEmpty() && StringUtils.hasText(values.get(0))) {
                    return values.get(0);
                }
            }
        }
        return null;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of(
                    "_argumentParseError", "Invalid tool arguments JSON",
                    "_rawArguments", json);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Response invokeSdk(
            String apiKey,
            String baseUrl,
            Integer timeoutSeconds,
            ResponseCreateParams params) {
        ClientKey key = new ClientKey(
                apiKey,
                StringUtils.hasText(baseUrl) ? baseUrl.trim() : "");
        RequestOptions options = (timeoutSeconds != null && timeoutSeconds > 0)
                ? RequestOptions.builder().timeout(Duration.ofSeconds(timeoutSeconds)).build()
                : RequestOptions.none();
        OpenAIClient cached = clientCache.get(key);
        if (cached != null) {
            return cached.responses().create(params, options);
        }
        if (clientCache.size() < MAX_CACHED_CLIENTS) {
            OpenAIClient client = clientCache.computeIfAbsent(key, OpenAiResponsesAgentToolGateway::buildClient);
            return client.responses().create(params, options);
        }
        OpenAIClient transientClient = buildClient(key);
        try {
            return transientClient.responses().create(params, options);
        } finally {
            transientClient.close();
        }
    }

    private static OpenAIClient buildClient(ClientKey key) {
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder().apiKey(key.apiKey());
        if (StringUtils.hasText(key.baseUrl())) {
            clientBuilder.baseUrl(key.baseUrl());
        }
        return clientBuilder.build();
    }

    @PreDestroy
    void closeCachedClients() {
        List<OpenAIClient> snapshot = new ArrayList<>(clientCache.values());
        clientCache.clear();
        for (OpenAIClient client : snapshot) {
            try {
                client.close();
            } catch (Exception ignored) {
                // ignore shutdown cleanup noise
            }
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return StringUtils.hasText(second) ? second.trim() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
