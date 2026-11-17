package org.praxisplatform.config.ai.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final GeminiExchange exchange;

    @Autowired
    public GoogleGenAiAgentToolGateway(
            ObjectMapper objectMapper,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}") String defaultApiKey,
            @Value("${spring.ai.google.genai.chat.options.model:" + DEFAULT_MODEL + "}") String defaultModel) {
        this(objectMapper, defaultApiKey, defaultModel, GoogleGenAiAgentToolGateway::invokeSdk);
    }

    public GoogleGenAiAgentToolGateway(
            ObjectMapper objectMapper,
            String defaultApiKey,
            String defaultModel,
            GeminiExchange exchange) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : DEFAULT_MODEL;
        this.exchange = Objects.requireNonNull(exchange, "exchange is required");
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

        GenerateContentResponse response = exchange.generate(apiKey, model, contents, config);
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
                    .role("user")
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
                Schema schema = toGeminiSchema(tool);
                if (schema != null) {
                    declBuilder.parameters(schema);
                }
                declarations.add(declBuilder.build());
            }
            builder.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
        }
        return builder.build();
    }

    private Schema toGeminiSchema(AgentToolDeclaration tool) {
        Map<String, Object> raw = new LinkedHashMap<>(tool.parametersJsonSchema());
        if (!raw.containsKey("type")) {
            raw.put("type", "object");
        }
        if (!raw.containsKey("required") && !tool.requiredParameters().isEmpty()) {
            raw.put("required", tool.requiredParameters());
        }
        return toGeminiSchemaNode(raw);
    }

    @SuppressWarnings("unchecked")
    private Schema toGeminiSchemaNode(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Schema.builder().type(Type.Known.OBJECT).build();
        }
        Schema.Builder builder = Schema.builder();
        Object typeValue = raw.get("type");
        if (typeValue instanceof String typeStr) {
            builder.type(mapType(typeStr));
        }
        if (raw.get("description") instanceof String desc && StringUtils.hasText(desc)) {
            builder.description(desc);
        }
        if (raw.get("nullable") instanceof Boolean nullable) {
            builder.nullable(nullable);
        }
        if (raw.get("enum") instanceof List<?> enumValues && !enumValues.isEmpty()) {
            List<String> normalizedEnums = enumValues.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            builder.enum_(normalizedEnums);
        }
        if (raw.get("required") instanceof List<?> requiredList && !requiredList.isEmpty()) {
            List<String> required = requiredList.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            builder.required(required);
        }
        if (raw.get("properties") instanceof Map<?, ?> propsMap && !propsMap.isEmpty()) {
            Map<String, Schema> convertedProps = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> nestedMap) {
                    convertedProps.put(entry.getKey().toString(), toGeminiSchemaNode((Map<String, Object>) nestedMap));
                }
            }
            builder.properties(convertedProps);
        }
        if (raw.get("items") instanceof Map<?, ?> itemsMap) {
            builder.items(toGeminiSchemaNode((Map<String, Object>) itemsMap));
        }
        return builder.build();
    }

    private Type.Known mapType(String jsonSchemaType) {
        if (jsonSchemaType == null) {
            return Type.Known.TYPE_UNSPECIFIED;
        }
        return switch (jsonSchemaType.trim().toLowerCase()) {
            case "string" -> Type.Known.STRING;
            case "integer" -> Type.Known.INTEGER;
            case "number" -> Type.Known.NUMBER;
            case "boolean" -> Type.Known.BOOLEAN;
            case "array" -> Type.Known.ARRAY;
            case "object" -> Type.Known.OBJECT;
            default -> Type.Known.STRING;
        };
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
        String finishReason = null;

        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (!candidates.isEmpty()) {
            Candidate primary = candidates.get(0);
            finishReason = primary.finishReason().map(Object::toString).orElse(null);
            List<Part> parts = primary.content().flatMap(Content::parts).orElse(List.of());
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
        return new AgentModelTurnResponse(
                PROVIDER_ID,
                response.modelVersion().orElse(model),
                textBuilder.toString(),
                calls,
                usage,
                finishReason,
                response.responseId().orElse(null),
                Map.of());
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
        try (Client client = Client.builder().apiKey(apiKey).build()) {
            return client.models.generateContent(model, contents, config);
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
