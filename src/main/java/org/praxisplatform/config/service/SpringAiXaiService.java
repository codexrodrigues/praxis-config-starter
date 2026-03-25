package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Adaptador canonico do runtime xAI/Grok para os contratos de {@link AiProvider}.
 *
 * <p>O servico reaproveita a infraestrutura compatível com OpenAI do Spring AI para oferecer
 * geracao estruturada, texto, listagem de modelos e streaming quando o provider configurado e
 * `xai`.
 */
@Service
@ConditionalOnProperty(name = "praxis.ai.provider", havingValue = "xai")
@RequiredArgsConstructor
@Slf4j
public class SpringAiXaiService implements AiProvider {

    private static final String DEFAULT_BASE_URL = "https://api.x.ai";

    private final OpenAiChatModel chatClient;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ObjectProvider<RagChatAdvisorService> ragChatAdvisorServiceProvider;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:grok-2-latest}")
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
                log.warn("[SpringAiXaiService] JSON parse failed, returning null.", e);
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
            throw new IllegalStateException("xAI API key not configured (spring.ai.openai.api-key)");
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
                throw new IllegalStateException("xAI API HTTP " + response.statusCode() + ": " + response.body());
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
            throw new IllegalStateException("Failed to list xAI models", e);
        }
    }

    @Override
    public String getProviderName() {
        return "xai";
    }

    private String callWithOptions(String prompt, AiCallConfig config, boolean jsonMode) {
        OpenAiChatOptions options = buildOptions(config);
        List<Advisor> advisors = resolveRagAdvisors();
        if (!advisors.isEmpty()) {
            return callWithAdvisors(prompt, options, config, advisors);
        }
        ChatResponse response = resolveClient(config).call(new Prompt(prompt, options));
        return extractContent(response);
    }

    private OpenAiChatOptions buildOptions(AiCallConfig config) {
        return OpenAiChatOptions.builder()
                .model(resolveModel(config))
                .temperature(resolveTemperature(config))
                .maxTokens(resolveMaxTokens(config))
                .build();
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
                    .defaultOptions(buildOptions(config))
                    .build();
        }
        return chatClient;
    }

    private String callWithAdvisors(
            String prompt,
            OpenAiChatOptions options,
            AiCallConfig config,
            List<Advisor> advisors) {
        ChatClient client = ChatClient.create(resolveClient(config));
        Filter.Expression filterExpression = RagFilters.buildScopedExpression(
                config != null ? config.getTenantId() : null,
                config != null ? config.getEnvironment() : null,
                config != null ? config.getRagReleaseId() : null,
                true);
        if (filterExpression == null) {
            return client.prompt(prompt)
                    .options(options)
                    .advisors(advisors)
                    .call()
                    .content();
        }
        RagFilterContext.set(filterExpression);
        try {
            return client.prompt(prompt)
                    .options(options)
                    .advisors(spec -> spec.advisors(advisors)
                            .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filterExpression))
                    .call()
                    .content();
        } finally {
            RagFilterContext.clear();
        }
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
            log.warn("[SpringAiXaiService] JSON parse failed, returning null.", e);
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
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized + "/models";
    }

    private String resolveBaseUrl(String value) {
        String resolved = trimToNull(value);
        if (resolved == null) {
            resolved = DEFAULT_BASE_URL;
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
