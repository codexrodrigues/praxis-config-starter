package org.praxisplatform.config.ai.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.ThinkingLevel;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import jakarta.annotation.PreDestroy;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Gateway para Google Gemini usando o SDK oficial {@code com.google.genai:google-genai}.
 *
 * <p>Desativa explicitamente o automatic function calling do SDK para que todas as chamadas de
 * ferramenta passem pela camada de governanca, mascara e autorizacao da aplicacao, preservando
 * {@code thoughtSignature} entre turnos.
 */
public class GoogleGenAiAgentToolGateway implements AiToolCallingProvider {

    public static final String PROVIDER_ID = "gemini";
    private static final String DEFAULT_MODEL = "gemini-flash-latest";
    private static final String SYNTHETIC_CALL_PREFIX = "gemini-auto-";
    private static final int MAX_CACHED_CLIENTS = 32;
    private static final long MIN_RETRY_AFTER_MS = 1_000L;
    private static final long MAX_RETRY_AFTER_MS = 600_000L;
    private static final Pattern GEMINI_RETRY_DELAY_PATTERN = Pattern.compile(
            "(?i)(?:retry\\s+(?:in|after)\\s+|retryDelay[\"':\\s]+)(\\d+(?:\\.\\d+)?)\\s*s");

    private record ClientKey(String apiKey, String baseUrl) {}

    @FunctionalInterface
    public interface GeminiExchange {
        GenerateContentResponse generate(
                String apiKey,
                String model,
                List<Content> contents,
                GenerateContentConfig config);
    }

    private final ObjectMapper objectMapper;
    private final String defaultApiKey;
    private final String defaultModel;
    private String defaultBaseUrl;
    private final GeminiExchange exchange;
    private final Map<ClientKey, Client> clientCache = new ConcurrentHashMap<>();

    @Autowired
    public GoogleGenAiAgentToolGateway(
            ObjectMapper objectMapper,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}") String defaultApiKey,
            @Value("${spring.ai.google.genai.chat.options.model:" + DEFAULT_MODEL + "}") String defaultModel) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : DEFAULT_MODEL;
        this.defaultBaseUrl = null;
        this.exchange = null;
    }

    public GoogleGenAiAgentToolGateway(
            ObjectMapper objectMapper,
            String defaultApiKey,
            String defaultModel,
            GeminiExchange exchange) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : DEFAULT_MODEL;
        this.defaultBaseUrl = null;
        this.exchange = Objects.requireNonNull(exchange, "exchange is required");
    }

    @Autowired(required = false)
    void setDefaultBaseUrl(@Value("${spring.ai.google.genai.base-url:}") String defaultBaseUrl) {
        this.defaultBaseUrl = trimToNull(defaultBaseUrl);
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
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        String model = firstNonBlank(callConfig != null ? callConfig.getModel() : null, defaultModel);
        String effectiveBaseUrl = firstNonBlank(callConfig != null ? callConfig.getBaseUrl() : null, defaultBaseUrl);

        List<Content> contents = toGeminiContents(request.conversation());
        GenerateContentConfig config = toGeminiConfig(request, model, callConfig, effectiveBaseUrl);

        try {
            GenerateContentResponse response = exchange == null
                    ? invokeSdk(apiKey, effectiveBaseUrl, model, contents, config)
                    : exchange.generate(apiKey, model, contents, config);
            return fromGeminiResponse(model, response, listener);
        } catch (RuntimeException ex) {
            throw mapProviderException(ex);
        }
    }

    List<Content> toGeminiContents(List<AgentConversationItem> conversation) {
        List<Content> contents = new ArrayList<>();
        if (conversation == null) {
            return contents;
        }
        for (AgentConversationItem item : conversation) {
            if (item == null || item.role() == null) {
                continue;
            }
            if (item.role() == AgentConversationRole.USER) {
                if (StringUtils.hasText(item.text())) {
                    contents.add(Content.builder()
                            .role("user")
                            .parts(List.of(Part.fromText(item.text())))
                            .build());
                }
            } else if (item.role() == AgentConversationRole.ASSISTANT) {
                List<Part> rawOpaqueParts = extractOpaqueGeminiParts(item);
                if (!rawOpaqueParts.isEmpty()) {
                    contents.add(Content.builder().role("model").parts(rawOpaqueParts).build());
                } else {
                    List<Part> parts = new ArrayList<>();
                    if (StringUtils.hasText(item.text())) {
                        parts.add(Part.fromText(item.text()));
                    }
                    for (AgentToolCall call : item.toolCalls()) {
                        FunctionCall.Builder fcBuilder = FunctionCall.builder()
                                .name(call.name())
                                .args(call.arguments());
                        if (StringUtils.hasText(call.callId()) && !call.callId().startsWith(SYNTHETIC_CALL_PREFIX)) {
                            fcBuilder.id(call.callId());
                        }
                        Part.Builder partBuilder = Part.builder().functionCall(fcBuilder.build());
                        if (StringUtils.hasText(call.thoughtSignature())) {
                            partBuilder.thoughtSignature(Base64.getDecoder().decode(call.thoughtSignature()));
                        }
                        parts.add(partBuilder.build());
                    }
                    if (!parts.isEmpty()) {
                        contents.add(Content.builder().role("model").parts(parts).build());
                    }
                }
            } else if (item.role() == AgentConversationRole.TOOL_RESULT) {
                List<Part> parts = new ArrayList<>();
                for (AgentToolResult result : item.toolResults()) {
                    FunctionResponse.Builder frBuilder = FunctionResponse.builder()
                            .name(result.name())
                            .response(result.output());
                    if (StringUtils.hasText(result.callId()) && !result.callId().startsWith(SYNTHETIC_CALL_PREFIX)) {
                        frBuilder.id(result.callId());
                    }
                    parts.add(Part.builder().functionResponse(frBuilder.build()).build());
                }
                if (!parts.isEmpty()) {
                    contents.add(Content.builder().role("user").parts(parts).build());
                }
            }
        }
        return contents;
    }

    GenerateContentConfig toGeminiConfig(AgentModelTurnRequest request, AiCallConfig callConfig) {
        String model = firstNonBlank(callConfig != null ? callConfig.getModel() : null, defaultModel);
        return toGeminiConfig(request, model, callConfig);
    }

    GenerateContentConfig toGeminiConfig(
            AgentModelTurnRequest request,
            String resolvedModel,
            AiCallConfig callConfig) {
        String effectiveBaseUrl = firstNonBlank(callConfig != null ? callConfig.getBaseUrl() : null, defaultBaseUrl);
        return toGeminiConfig(request, resolvedModel, callConfig, effectiveBaseUrl);
    }

    private GenerateContentConfig toGeminiConfig(
            AgentModelTurnRequest request,
            String resolvedModel,
            AiCallConfig callConfig,
            String effectiveBaseUrl) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true).build());
        Integer timeoutSeconds = callConfig != null ? callConfig.getTimeoutSeconds() : null;
        if ((timeoutSeconds != null && timeoutSeconds > 0) || StringUtils.hasText(effectiveBaseUrl)) {
            HttpOptions.Builder httpOptionsBuilder = HttpOptions.builder();
            if (timeoutSeconds != null && timeoutSeconds > 0) {
                int timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeoutSeconds * 1000L);
                httpOptionsBuilder.timeout(timeoutMs);
            }
            if (StringUtils.hasText(effectiveBaseUrl)) {
                httpOptionsBuilder.baseUrl(effectiveBaseUrl);
            }
            builder.httpOptions(httpOptionsBuilder.build());
        }
        boolean hasCachedContent = !request.disableToolCalling()
                && callConfig != null
                && StringUtils.hasText(callConfig.getCachedContentName());
        if (hasCachedContent) {
            // Gemini rejects systemInstruction/tools/toolConfig alongside explicit cachedContent.
            builder.cachedContent(callConfig.getCachedContentName().trim());
        } else if (StringUtils.hasText(request.systemInstruction())) {
            builder.systemInstruction(Content.builder()
                    .parts(List.of(Part.fromText(request.systemInstruction())))
                    .build());
        }
        if (callConfig != null && callConfig.getTemperature() != null) {
            builder.temperature(callConfig.getTemperature().floatValue());
        }
        if (callConfig != null && callConfig.getMaxTokens() != null) {
            builder.maxOutputTokens(callConfig.getMaxTokens());
        }
        applyThinkingConfig(builder, resolvedModel, callConfig);
        if (request.responseJsonSchema() != null && !request.responseJsonSchema().isEmpty()) {
            builder.responseMimeType("application/json");
            builder.responseJsonSchema(request.responseJsonSchema());
        }
        if (!hasCachedContent && !request.tools().isEmpty()) {
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (AgentToolDeclaration tool : request.tools()) {
                FunctionDeclaration.Builder declBuilder = FunctionDeclaration.builder()
                        .name(tool.name())
                        .description(tool.description());
                Map<String, Object> jsonSchema = normalizeJsonSchemaMap(tool);
                if (!jsonSchema.isEmpty()) {
                    declBuilder.parametersJsonSchema(jsonSchema);
                }
                declarations.add(declBuilder.build());
            }
            builder.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
            if (request.disableToolCalling()) {
                builder.toolConfig(ToolConfig.builder()
                        .functionCallingConfig(FunctionCallingConfig.builder()
                                .mode(FunctionCallingConfigMode.Known.NONE)
                                .build())
                        .build());
            }
        }
        return builder.build();
    }

    private static void applyThinkingConfig(
            GenerateContentConfig.Builder builder,
            String model,
            AiCallConfig callConfig) {
        if (callConfig == null || (callConfig.getThinkingProfile() == null && callConfig.getThinkingBudgetTokens() == null)) {
            return;
        }
        String lower = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        AiThinkingProfile profile = callConfig.getThinkingProfile();
        if (lower.contains("gemini-3")) {
            ThinkingLevel.Known level = switch (profile != null ? profile : AiThinkingProfile.OPERATIONAL_AUDIT) {
                case OFF -> lower.contains("pro") ? ThinkingLevel.Known.LOW : ThinkingLevel.Known.MINIMAL;
                case FAST_LOOKUP -> ThinkingLevel.Known.LOW;
                case OPERATIONAL_AUDIT -> ThinkingLevel.Known.MEDIUM;
                case DEEP_ANALYSIS -> ThinkingLevel.Known.HIGH;
            };
            builder.thinkingConfig(ThinkingConfig.builder()
                    .thinkingLevel(level)
                    .build());
            return;
        }
        boolean supportsBudget = lower.contains("gemini-2.5")
                || lower.contains("flash-latest")
                || lower.contains("pro-latest")
                || callConfig.getThinkingBudgetTokens() != null;
        if (!supportsBudget) {
            return;
        }
        Integer explicitBudget = callConfig.getThinkingBudgetTokens();
        int budget;
        if (explicitBudget != null) {
            budget = Math.max(0, explicitBudget);
        } else {
            budget = switch (profile) {
                case OFF -> lower.contains("pro") ? 128 : 0;
                case FAST_LOOKUP -> 1024;
                case OPERATIONAL_AUDIT -> 4096;
                case DEEP_ANALYSIS -> 12288;
            };
        }
        builder.thinkingConfig(ThinkingConfig.builder()
                .thinkingBudget(budget)
                .build());
    }

    private static List<Part> extractOpaqueGeminiParts(AgentConversationItem item) {
        if (item == null || item.providerOpaqueState() == null) {
            return List.of();
        }
        Object raw = item.providerOpaqueState().get("geminiModelParts");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Part> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Part part)) {
                return List.of();
            }
            result.add(part);
        }
        return result;
    }

    private Map<String, Object> normalizeJsonSchemaMap(AgentToolDeclaration tool) {
        Map<String, Object> raw = tool.parametersJsonSchema() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(tool.parametersJsonSchema());
        raw.putIfAbsent("type", "object");
        if (!raw.containsKey("required") && tool.requiredParameters() != null && !tool.requiredParameters().isEmpty()) {
            raw.put("required", tool.requiredParameters());
        }
        return normalizeSchemaNode(raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSchemaNode(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of("type", "object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if ("properties".equals(key) && val instanceof Map<?, ?> propsMap) {
                Map<String, Object> normProps = new LinkedHashMap<>();
                for (Map.Entry<?, ?> propEntry : propsMap.entrySet()) {
                    if (propEntry.getKey() != null && propEntry.getValue() instanceof Map<?, ?> nested) {
                        normProps.put(propEntry.getKey().toString(), normalizeSchemaNode((Map<String, Object>) nested));
                    }
                }
                if (!normProps.isEmpty()) {
                    out.put("properties", normProps);
                }
            } else if ("items".equals(key) && val instanceof Map<?, ?> itemsMap) {
                out.put("items", normalizeSchemaNode((Map<String, Object>) itemsMap));
            } else if (val != null) {
                out.put(key, val);
            }
        }
        if (!out.containsKey("type") && !out.containsKey("enum") && !out.containsKey("$ref")) {
            out.put("type", "object");
        }
        return out;
    }

    private AgentModelTurnResponse fromGeminiResponse(
            String model,
            GenerateContentResponse response,
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
        List<Part> rawParts = List.of();
        String finishReason = null;

        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (!candidates.isEmpty()) {
            Candidate primary = candidates.get(0);
            finishReason = primary.finishReason().map(Object::toString).orElse(null);
            List<Part> parts = primary.content().flatMap(Content::parts).orElse(List.of());
            rawParts = parts;
            for (int index = 0; index < parts.size(); index++) {
                Part part = parts.get(index);
                boolean isThought = part.thought().orElse(false);
                if (part.text().isPresent()) {
                    String piece = part.text().get();
                    if (isThought) {
                        if (listener != null) {
                            listener.onThoughtDelta(piece);
                        }
                    } else if (StringUtils.hasText(piece)) {
                        textBuilder.append(piece);
                        if (listener != null) {
                            listener.onTextDelta(piece);
                        }
                    }
                }
                if (part.functionCall().isPresent()) {
                    FunctionCall fc = part.functionCall().get();
                    String name = fc.name().orElse("");
                    String callId = fc.id().filter(StringUtils::hasText)
                            .orElse(SYNTHETIC_CALL_PREFIX + index);
                    Map<String, Object> args = fc.args().orElse(Map.of());
                    String thoughtSignature = part.thoughtSignature()
                            .map(bytes -> Base64.getEncoder().encodeToString(bytes))
                            .orElse(null);
                    String rawArgs;
                    try {
                        rawArgs = objectMapper.writeValueAsString(args);
                    } catch (Exception ex) {
                        rawArgs = "{}";
                    }
                    calls.add(new AgentToolCall(callId, name, args, thoughtSignature, rawArgs));
                }
            }
        }

        AgentTokenUsage usage = response.usageMetadata()
                .map(this::toUsage)
                .orElse(AgentTokenUsage.ZERO);
        Map<String, Object> opaqueState = rawParts.isEmpty()
                ? Map.of()
                : Map.of("geminiModelParts", List.copyOf(rawParts));
        return new AgentModelTurnResponse(
                PROVIDER_ID,
                response.modelVersion().orElse(model),
                textBuilder.toString(),
                calls,
                usage,
                finishReason,
                response.responseId().orElse(null),
                opaqueState);
    }

    private AgentTokenUsage toUsage(GenerateContentResponseUsageMetadata metadata) {
        long input = metadata.promptTokenCount().map(Integer::longValue).orElse(0L);
        long output = metadata.candidatesTokenCount().map(Integer::longValue).orElse(0L);
        long total = metadata.totalTokenCount().map(Integer::longValue).orElse(input + output);
        long cacheRead = metadata.cachedContentTokenCount().map(Integer::longValue).orElse(0L);
        long thoughts = metadata.thoughtsTokenCount().map(Integer::longValue).orElse(0L);
        return new AgentTokenUsage(input, output, total, cacheRead, 0L, thoughts, cacheRead > 0L);
    }

    private static RuntimeException mapProviderException(RuntimeException ex) {
        if (ex instanceof AiProviderCallException) {
            return ex;
        }
        if (ex instanceof ApiException apiEx) {
            Instant retryAfter = parseRetryAfterHint(apiEx.message());
            return AiProviderCallException.fromHttpStatusSanitized(
                    PROVIDER_ID, apiEx.code(), apiEx.message(), retryAfter, apiEx);
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

    static Instant parseRetryAfterHint(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = GEMINI_RETRY_DELAY_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(matcher.group(1));
            long ms = Math.round(seconds * 1000.0d);
            long clampedMs = Math.max(MIN_RETRY_AFTER_MS, Math.min(MAX_RETRY_AFTER_MS, ms));
            return Instant.now().plusMillis(clampedMs);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private GenerateContentResponse invokeSdk(
            String apiKey,
            String baseUrl,
            String model,
            List<Content> contents,
            GenerateContentConfig config) {
        ClientKey key = new ClientKey(
                apiKey,
                StringUtils.hasText(baseUrl) ? baseUrl.trim() : "");
        Client cached = clientCache.get(key);
        if (cached != null) {
            return cached.models.generateContent(model, contents, config);
        }
        if (clientCache.size() < MAX_CACHED_CLIENTS) {
            Client client = clientCache.computeIfAbsent(key, GoogleGenAiAgentToolGateway::buildClient);
            return client.models.generateContent(model, contents, config);
        }
        try (Client client = buildClient(key)) {
            return client.models.generateContent(model, contents, config);
        }
    }

    private static Client buildClient(ClientKey key) {
        Client.Builder builder = Client.builder().apiKey(key.apiKey());
        if (StringUtils.hasText(key.baseUrl())) {
            builder.httpOptions(HttpOptions.builder().baseUrl(key.baseUrl()).build());
        }
        return builder.build();
    }

    @PreDestroy
    void closeCachedClients() {
        List<Client> snapshot = new ArrayList<>(clientCache.values());
        clientCache.clear();
        for (Client client : snapshot) {
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
