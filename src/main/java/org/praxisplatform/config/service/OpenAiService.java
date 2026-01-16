package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService implements AiProvider {

    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.openai.api-key:#{null}}")
    private String apiKey;

    @Value("${praxis.ai.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${praxis.ai.temperature:0.1}")
    private double temperature;

    @Value("${praxis.ai.max-tokens:2048}")
    private int maxTokens;

    @Value("${praxis.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${praxis.ai.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Override
    public JsonNode generateJson(String prompt) {
        String text = generateContent(prompt, null);
        if (text == null || text.isBlank()) return null;
        String cleaned = sanitizeJsonText(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[OpenAiService] JSON parse failed, returning null.", e);
            return null;
        }
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return generateJson(prompt, schema, null);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        boolean skipTargetClass = false;
        if (schema != null && schema.hasJsonSchema()) {
            String resolvedModel = resolveModel(config);
            if (usesResponsesApi(resolvedModel)) {
                double resolvedTemperature = resolveTemperature(config);
                int resolvedMaxTokens = resolveMaxTokens(config);
                JsonNode json = generateJsonWithResponsesSchema(
                        prompt,
                        schema.jsonSchema(),
                        resolvedModel,
                        resolvedTemperature,
                        resolvedMaxTokens,
                        config);
                if (json != null) {
                    return json;
                }
                skipTargetClass = true;
            }
        }
        if (!skipTargetClass && schema != null && schema.hasTargetClass()) {
            return generateStructured(prompt, schema.targetClass(), config);
        }
        String text = generateContent(prompt, config);
        if (text == null || text.isBlank()) return null;
        String cleaned = sanitizeJsonText(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[OpenAiService] JSON parse failed, returning null.", e);
            return null;
        }
    }

    @Override
    public String generateText(String prompt) {
        return generateText(prompt, null);
    }

    @Override
    public String generateText(String prompt, AiCallConfig config) {
        return generateContent(prompt, config);
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    public List<AiProviderModel> listModels(AiCallConfig config) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key not configured (praxis.ai.openai.api-key)");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/models"))
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

    private OpenAIClient buildClient(AiCallConfig config) {
        String resolvedKey = resolveApiKey(config);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key not configured (praxis.ai.openai.api-key)");
        }
        return OpenAIOkHttpClient.builder()
                .apiKey(resolvedKey)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .maxRetries(resolveOpenAiRetries())
                .build();
    }

    private String generateContent(String prompt, AiCallConfig config) {
        OpenAIClient client = buildClient(config);
        List<ChatCompletionMessageParam> messages =
                List.of(ChatCompletionMessageParam.ofUser(buildUserMessage(prompt)));
        String resolvedModel = resolveModel(config);
        double resolvedTemperature = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        if (usesResponsesApi(resolvedModel)) {
            return generateContentWithResponses(client, prompt, resolvedModel, resolvedTemperature, resolvedMaxTokens);
        }
        var builder = ChatCompletionCreateParams.builder()
                .model(resolvedModel)
                .messages(messages)
                .temperature(resolvedTemperature);
        applyMaxTokens(builder, resolvedModel, resolvedMaxTokens);
        ChatCompletionCreateParams params = builder.build();
        ChatCompletion completion = client.chat().completions().create(params);
        if (completion.choices().isEmpty()) {
            return null;
        }
        return completion.choices().get(0).message().content().orElse(null);
    }

    private <T> JsonNode generateStructured(String prompt, Class<T> targetClass, AiCallConfig config) {
        OpenAIClient client = buildClient(config);
        List<ChatCompletionMessageParam> messages =
                List.of(ChatCompletionMessageParam.ofUser(buildUserMessage(prompt)));
        String resolvedModel = resolveModel(config);
        double resolvedTemperature = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        if (usesResponsesApi(resolvedModel)) {
            return generateStructuredWithResponses(
                    client,
                    prompt,
                    targetClass,
                    resolvedModel,
                    resolvedTemperature,
                    resolvedMaxTokens);
        }
        var builder = ChatCompletionCreateParams.builder()
                .model(resolvedModel)
                .messages(messages)
                .temperature(resolvedTemperature)
                .responseFormat(targetClass);
        applyMaxTokens(builder, resolvedModel, resolvedMaxTokens);
        StructuredChatCompletionCreateParams<T> params = builder.build();
        StructuredChatCompletion<T> completion = client.chat().completions().create(params);
        if (completion.choices().isEmpty()) {
            return null;
        }
        return completion.choices()
                .get(0)
                .message()
                .content()
                .map(value -> (JsonNode) objectMapper.valueToTree(value))
                .orElse(null);
    }

    private String resolveApiKey(AiCallConfig config) {
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey().trim();
        }
        return apiKey;
    }

    private ChatCompletionUserMessageParam buildUserMessage(String prompt) {
        return ChatCompletionUserMessageParam.builder()
                .content(prompt)
                .build();
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

    private int resolveOpenAiRetries() {
        int attempts = Math.max(1, retryMaxAttempts);
        return Math.max(0, attempts - 1);
    }

    private void applyMaxTokens(ChatCompletionCreateParams.Builder builder, String model, int maxTokens) {
        if (maxTokens <= 0) {
            return;
        }
        long tokens = maxTokens;
        if (requiresMaxCompletionTokens(model)) {
            builder.maxCompletionTokens(tokens);
        } else {
            builder.maxTokens(tokens);
        }
    }

    private <T> void applyMaxTokens(StructuredChatCompletionCreateParams.Builder<T> builder, String model, int maxTokens) {
        if (maxTokens <= 0) {
            return;
        }
        long tokens = maxTokens;
        if (requiresMaxCompletionTokens(model)) {
            builder.maxCompletionTokens(tokens);
        } else {
            builder.maxTokens(tokens);
        }
    }

    private String generateContentWithResponses(
            OpenAIClient client,
            String prompt,
            String model,
            double temperature,
            int maxTokens) {
        int outputTokens = normalizeResponseMaxOutputTokens(maxTokens);
        var builder = ResponseCreateParams.builder()
                .model(model)
                .input(prompt);
        if (supportsTemperature(model)) {
            builder.temperature(temperature);
        }
        if (outputTokens > 0) {
            builder.maxOutputTokens(outputTokens);
        }
        Response response = client.responses().create(builder.build());
        return extractResponseText(response);
    }

    private <T> JsonNode generateStructuredWithResponses(
            OpenAIClient client,
            String prompt,
            Class<T> targetClass,
            String model,
            double temperature,
            int maxTokens) {
        int outputTokens = normalizeResponseMaxOutputTokens(maxTokens);
        var builder = StructuredResponseCreateParams.<T>builder()
                .model(model)
                .input(prompt)
                .text(targetClass, com.openai.core.JsonSchemaLocalValidation.NO);
        if (supportsTemperature(model)) {
            builder.temperature(temperature);
        }
        if (outputTokens > 0) {
            builder.maxOutputTokens(outputTokens);
        }
        StructuredResponse<T> response = client.responses().create(builder.build());
        return extractStructuredResponsePayload(response);
    }

    private JsonNode generateJsonWithResponsesSchema(
            String prompt,
            String schemaJson,
            String model,
            double temperature,
            int maxTokens,
            AiCallConfig config) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        ResponseFormatTextJsonSchemaConfig schemaConfig = buildResponsesJsonSchema(schemaJson, "ai-action-plan");
        if (schemaConfig == null) {
            return null;
        }
        OpenAIClient client = buildClient(config);
        int outputTokens = normalizeResponseMaxOutputTokens(maxTokens);
        ResponseTextConfig textConfig = ResponseTextConfig.builder()
                .format(schemaConfig)
                .build();
        var builder = ResponseCreateParams.builder()
                .model(model)
                .input(prompt)
                .text(textConfig);
        if (supportsTemperature(model)) {
            builder.temperature(temperature);
        }
        if (outputTokens > 0) {
            builder.maxOutputTokens(outputTokens);
        }
        Response response;
        try {
            response = client.responses().create(builder.build());
        } catch (Exception e) {
            log.warn("[OpenAiService] Responses API json_schema call failed; falling back to text.", e);
            return null;
        }
        String text = extractResponseText(response);
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = sanitizeJsonText(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("[OpenAiService] JSON parse failed for schema response, returning null.", e);
            return null;
        }
    }

    private ResponseFormatTextJsonSchemaConfig buildResponsesJsonSchema(String schemaJson, String name) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            if (log.isDebugEnabled()) {
                String addl = schemaNode != null && schemaNode.has("additionalProperties")
                        ? schemaNode.get("additionalProperties").toString()
                        : "missing";
                log.debug("[OpenAiService] Responses json_schema name={} root.additionalProperties={} schema={}",
                        name, addl, schemaJson);
            }
            if (schemaNode == null || !schemaNode.isObject()) {
                log.warn("[OpenAiService] Response schema is not an object, skipping json_schema format.");
                return null;
            }
            JsonNode propertiesNode = schemaNode.get("properties");
            if (propertiesNode == null || !propertiesNode.isObject() || propertiesNode.isEmpty()) {
                log.warn("[OpenAiService] Response schema missing properties; skipping json_schema format.");
                return null;
            }
            Map<String, JsonValue> properties = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = schemaNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                properties.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()));
            }
            ResponseFormatTextJsonSchemaConfig.Schema schema =
                    ResponseFormatTextJsonSchemaConfig.Schema.builder()
                            .additionalProperties(properties)
                            .build();
            return ResponseFormatTextJsonSchemaConfig.builder()
                    .name(name)
                    .schema(schema)
                    .strict(true)
                    .build();
        } catch (Exception e) {
            log.warn("[OpenAiService] Failed to build json_schema response format.", e);
            return null;
        }
    }

    private String extractResponseText(Response response) {
        if (response == null) {
            return null;
        }
        logResponseDiagnostics(response);
        StringBuilder text = new StringBuilder();
        String refusal = null;
        for (var item : response.output()) {
            var message = item.message();
            if (message.isEmpty()) {
                continue;
            }
            for (var content : message.get().content()) {
                if (content.isOutputText()) {
                    String chunk = content.asOutputText().text();
                    if (chunk != null && !chunk.isBlank()) {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(chunk);
                    }
                } else if (content.isRefusal() && refusal == null) {
                    String value = content.asRefusal().refusal();
                    if (value != null && !value.isBlank()) {
                        refusal = value;
                    }
                }
            }
        }
        if (text.length() > 0) {
            return text.toString();
        }
        return refusal;
    }

    private <T> JsonNode extractStructuredResponsePayload(StructuredResponse<T> response) {
        if (response == null) {
            return null;
        }
        logResponseDiagnostics(response.rawResponse());
        String refusal = null;
        for (var item : response.output()) {
            var message = item.message();
            if (message.isEmpty()) {
                continue;
            }
            for (var content : message.get().content()) {
                if (content.isOutputText()) {
                    T payload = content.asOutputText();
                    return payload != null ? objectMapper.valueToTree(payload) : null;
                } else if (content.isRefusal() && refusal == null) {
                    String value = content.asRefusal().refusal();
                    if (value != null && !value.isBlank()) {
                        refusal = value;
                    }
                }
            }
        }
        if (refusal != null) {
            log.warn("[OpenAiService] OpenAI response refusal: {}", refusal);
            return objectMapper.createObjectNode().put("refusal", refusal);
        }
        return null;
    }

    private boolean usesResponsesApi(String model) {
        return requiresMaxCompletionTokens(model);
    }

    private int normalizeResponseMaxOutputTokens(int maxTokens) {
        if (maxTokens <= 0) {
            return 0;
        }
        return Math.max(16, maxTokens);
    }

    private void logResponseDiagnostics(Response response) {
        if (response == null) {
            return;
        }
        log.info(
                "[OpenAiService] OpenAI response meta: id={}, model={}, usage={}",
                response.id(),
                response.model(),
                response.usage().map(Object::toString).orElse("unknown"));
        response.error().ifPresent(error ->
                log.warn("[OpenAiService] OpenAI response error: {}", error));
        response.incompleteDetails().ifPresent(details -> {
            String reason = details.reason().map(Object::toString).orElse("unknown");
            log.warn("[OpenAiService] OpenAI response incomplete: {}", reason);
        });
        response.status().ifPresent(status -> {
            if (status != ResponseStatus.COMPLETED) {
                log.warn("[OpenAiService] OpenAI response status: {}", status.asString());
            }
        });
    }

    private boolean requiresMaxCompletionTokens(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toLowerCase();
        return normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3");
    }

    private boolean supportsTemperature(String model) {
        if (model == null) {
            return true;
        }
        String normalized = model.trim().toLowerCase();
        return !normalized.startsWith("o1") && !normalized.startsWith("o3") && !normalized.startsWith("gpt-5");
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
        int lastCloseBrace = cleaned.lastIndexOf('}');
        int lastCloseBracket = cleaned.lastIndexOf(']');
        int lastClose = Math.max(lastCloseBrace, lastCloseBracket);
        if (firstOpen >= 0 && lastClose > firstOpen) {
            cleaned = cleaned.substring(firstOpen, lastClose + 1);
        }
        return cleaned.trim();
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }
}
