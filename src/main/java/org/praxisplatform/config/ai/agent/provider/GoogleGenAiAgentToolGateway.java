package org.praxisplatform.config.ai.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
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
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final Map<ClientKey, Client> CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final GeminiExchange DEFAULT_EXCHANGE = (apiKey, model, contents, config) ->
            invokeSdk(apiKey, null, null, model, contents, config);

    private record ClientKey(String apiKey, String baseUrl, Integer timeoutMs) {}

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

    @Autowired
    public GoogleGenAiAgentToolGateway(
            ObjectMapper objectMapper,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}") String defaultApiKey,
            @Value("${spring.ai.google.genai.chat.options.model:" + DEFAULT_MODEL + "}") String defaultModel) {
        this(objectMapper, defaultApiKey, defaultModel, DEFAULT_EXCHANGE);
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

        List<Content> contents = toGeminiContents(request.conversation());
        GenerateContentConfig config = toGeminiConfig(request, callConfig);

        GenerateContentResponse response = exchange == DEFAULT_EXCHANGE
                ? invokeSdk(
                        apiKey,
                        defaultBaseUrl,
                        callConfig != null ? callConfig.getTimeoutSeconds() : null,
                        model,
                        contents,
                        config)
                : exchange.generate(apiKey, model, contents, config);
        return fromGeminiResponse(model, response, listener);
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
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true).build());
        if (StringUtils.hasText(request.systemInstruction())) {
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
        if (!request.tools().isEmpty()) {
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
        return new AgentTokenUsage(input, output, total);
    }

    private static GenerateContentResponse invokeSdk(
            String apiKey,
            String model,
            List<Content> contents,
            GenerateContentConfig config) {
        return invokeSdk(apiKey, null, null, model, contents, config);
    }

    private static GenerateContentResponse invokeSdk(
            String apiKey,
            String baseUrl,
            Integer timeoutSeconds,
            String model,
            List<Content> contents,
            GenerateContentConfig config) {
        Integer timeoutMs = timeoutSeconds != null && timeoutSeconds > 0
                ? (int) Math.min(Integer.MAX_VALUE, timeoutSeconds * 1000L)
                : null;
        ClientKey key = new ClientKey(
                apiKey,
                StringUtils.hasText(baseUrl) ? baseUrl.trim() : "",
                timeoutMs);
        Client cached = CLIENT_CACHE.get(key);
        if (cached != null) {
            return cached.models.generateContent(model, contents, config);
        }
        if (CLIENT_CACHE.size() < MAX_CACHED_CLIENTS) {
            Client client = CLIENT_CACHE.computeIfAbsent(key, GoogleGenAiAgentToolGateway::buildClient);
            return client.models.generateContent(model, contents, config);
        }
        try (Client client = buildClient(key)) {
            return client.models.generateContent(model, contents, config);
        }
    }

    private static Client buildClient(ClientKey key) {
        Client.Builder builder = Client.builder().apiKey(key.apiKey());
        if (StringUtils.hasText(key.baseUrl()) || key.timeoutMs() != null) {
            HttpOptions.Builder httpBuilder = HttpOptions.builder();
            if (StringUtils.hasText(key.baseUrl())) {
                httpBuilder.baseUrl(key.baseUrl());
            }
            if (key.timeoutMs() != null) {
                httpBuilder.timeout(key.timeoutMs());
            }
            builder.httpOptions(httpBuilder.build());
        }
        return builder.build();
    }

    @PreDestroy
    void closeCachedClients() {
        List<Client> snapshot = new ArrayList<>(CLIENT_CACHE.values());
        CLIENT_CACHE.clear();
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
