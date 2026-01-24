package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class SpringAiOpenAiService implements AiProvider {

    private final OpenAiChatModel chatClient;
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
            throw new RuntimeException("Failed to call OpenAI directly", e);
        }
    }

    private String callOpenAiDirectly(String prompt, AiCallConfig config, boolean jsonMode) {
        String resolvedKey = resolveApiKey(config);
        String resolvedModel = resolveModel(config);
        double resolvedTemp = resolveTemperature(config);
        int resolvedMaxTokens = resolveMaxTokens(config);
        String resolvedUrl = resolveBaseUrl(baseUrl) + "/v1/chat/completions";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", resolvedModel);
            root.put("temperature", resolvedTemp);
            root.put("max_tokens", resolvedMaxTokens);
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
                 throw new IllegalStateException("OpenAI Error " + response.statusCode() + ": " + response.body());
            }

            JsonNode resRoot = objectMapper.readTree(response.body());
            JsonNode contentNode = resRoot.path("choices").get(0).path("message").path("content");
            return contentNode.asText();

        } catch (Exception e) {
            throw new RuntimeException("Direct OpenAI call failed", e);
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

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record JsonSchemaResult(String prompt, BeanOutputConverter<?> converter) {}
}