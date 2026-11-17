package org.praxisplatform.config.ai.agent.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseUsage;
import java.time.Duration;
import java.util.ArrayList;
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
 * Gateway para OpenAI utilizando o SDK oficial {@code com.openai:openai-java} e a
 * {@code Responses API} com ciclo explicito de {@code FunctionTool} e {@code FunctionCallOutput}.
 */
public class OpenAiResponsesAgentToolGateway implements AiToolCallingProvider {

    public static final String PROVIDER_ID = "openai";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @FunctionalInterface
    public interface OpenAiExchange {
        Response create(String apiKey, String baseUrl, Integer timeoutSeconds, ResponseCreateParams params);
    }

    private final ObjectMapper objectMapper;
    private final String defaultApiKey;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final OpenAiExchange exchange;

    @Autowired
    public OpenAiResponsesAgentToolGateway(
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:}") String defaultApiKey,
            @Value("${spring.ai.openai.base-url:}") String defaultBaseUrl,
            @Value("${spring.ai.openai.chat.options.model:" + DEFAULT_MODEL + "}") String defaultModel) {
        this(objectMapper, defaultApiKey, defaultBaseUrl, defaultModel, OpenAiResponsesAgentToolGateway::invokeSdk);
    }

    public OpenAiResponsesAgentToolGateway(
            ObjectMapper objectMapper,
            String defaultApiKey,
            String defaultBaseUrl,
            String defaultModel,
            OpenAiExchange exchange) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.defaultApiKey = trimToNull(defaultApiKey);
        this.defaultBaseUrl = trimToNull(defaultBaseUrl);
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
            throw new IllegalStateException("OpenAI API key is not configured.");
        }
        String model = firstNonBlank(callConfig != null ? callConfig.getModel() : null, defaultModel);

        ResponseCreateParams params = toResponseParams(request, model, callConfig);
        Response response = exchange.create(
                apiKey,
                defaultBaseUrl,
                callConfig != null ? callConfig.getTimeoutSeconds() : null,
                params);
        return fromOpenAiResponse(model, response, listener);
    }

    ResponseCreateParams toResponseParams(
            AgentModelTurnRequest request,
            String model,
            AiCallConfig callConfig) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .inputOfResponse(toInputItems(request.conversation()));
        if (StringUtils.hasText(request.systemInstruction())) {
            builder.instructions(request.systemInstruction());
        }
        if (callConfig != null && callConfig.getTemperature() != null) {
            builder.temperature(callConfig.getTemperature());
        }
        if (callConfig != null && callConfig.getMaxTokens() != null) {
            builder.maxOutputTokens(callConfig.getMaxTokens().longValue());
        }
        for (AgentToolDeclaration tool : request.tools()) {
            builder.addTool(toFunctionTool(tool));
        }
        return builder.build();
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
        FunctionTool.Parameters.Builder paramBuilder = FunctionTool.Parameters.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            paramBuilder.putAdditionalProperty(
                    entry.getKey(),
                    JsonValue.fromJsonNode(objectMapper.valueToTree(entry.getValue())));
        }
        return FunctionTool.builder()
                .name(tool.name())
                .description(tool.description())
                .strict(false)
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
            }
        }

        AgentTokenUsage usage = response.usage()
                .map(this::toUsage)
                .orElse(AgentTokenUsage.ZERO);
        String finishReason = response.status().map(Object::toString).orElse("completed");
        return new AgentModelTurnResponse(
                PROVIDER_ID,
                model,
                textBuilder.toString(),
                calls,
                usage,
                finishReason,
                response.id(),
                Map.of());
    }

    private AgentTokenUsage toUsage(ResponseUsage usage) {
        return new AgentTokenUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of("_rawArguments", json);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Response invokeSdk(
            String apiKey,
            String baseUrl,
            Integer timeoutSeconds,
            ResponseCreateParams params) {
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        if (StringUtils.hasText(baseUrl)) {
            clientBuilder.baseUrl(baseUrl);
        }
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            clientBuilder.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        OpenAIClient client = clientBuilder.build();
        try {
            return client.responses().create(params);
        } finally {
            client.close();
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
