package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class AiProviderRouter implements AiProvider {

    private final GeminiAiService gemini;
    private final OpenAiService openai;
    private final XaiAiService xai;
    private final MockAiService mock;

    @Value("${praxis.ai.provider:gemini}")
    private String provider;

    @Override
    public JsonNode generateJson(String prompt) {
        return resolve().generateJson(prompt);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return resolve().generateJson(prompt, schema);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        return resolve(config).generateJson(prompt, schema, config);
    }

    @Override
    public String generateText(String prompt) {
        return resolve().generateText(prompt);
    }

    @Override
    public String generateText(String prompt, AiCallConfig config) {
        return resolve(config).generateText(prompt, config);
    }

    @Override
    public String getProviderName() {
        return resolve().getProviderName();
    }

    private AiProvider resolve() {
        return resolve(null);
    }

    private AiProvider resolve(AiCallConfig config) {
        String requested = config != null ? normalizeProvider(config.getProvider()) : null;
        String selected = requested != null ? requested : normalizeProvider(provider);
        if (selected == null) {
            selected = "gemini";
        }
        if ("openai".equals(selected) || "open-ai".equals(selected)) {
            return openai;
        }
        if ("xai".equals(selected) || "grok".equals(selected) || "grok-ai".equals(selected)) {
            return xai;
        }
        if ("mock".equals(selected)) {
            return mock;
        }
        if (!"gemini".equals(selected)) {
            log.warn("[AiProviderRouter] Unknown provider '{}', defaulting to gemini.", selected);
        }
        return gemini;
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
