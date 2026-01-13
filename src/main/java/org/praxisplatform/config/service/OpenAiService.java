package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        if (schema != null && schema.hasTargetClass()) {
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
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(resolvedModel)
                .messages(messages)
                .temperature(resolvedTemperature)
                .maxTokens(resolvedMaxTokens)
                .build();
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
        StructuredChatCompletionCreateParams<T> params = ChatCompletionCreateParams.builder()
                .model(resolvedModel)
                .messages(messages)
                .temperature(resolvedTemperature)
                .maxTokens(resolvedMaxTokens)
                .responseFormat(targetClass)
                .build();
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
