package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.praxisplatform.config.dto.AiProviderModel;

@Service
@Primary
@RequiredArgsConstructor
@Slf4j
public class AiProviderRouter implements AiProvider {

    private final ObjectProvider<SpringAiGeminiService> geminiProvider;
    private final ObjectProvider<SpringAiOpenAiService> openaiProvider;
    private final ObjectProvider<SpringAiXaiService> xaiProvider;
    private final ObjectProvider<MockAiService> mockProvider;

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
    public boolean supportsTextStreaming(AiCallConfig config) {
        return resolve(config).supportsTextStreaming(config);
    }

    @Override
    public boolean supportsTurnCancellation(AiCallConfig config) {
        return resolve(config).supportsTurnCancellation(config);
    }

    @Override
    public String generateTextStream(
            String prompt,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        return resolve(config).generateTextStream(prompt, config, onChunk, cancellationRequested);
    }

    @Override
    public void cancelTurn(UUID threadId, UUID turnId) {
        resolve().cancelTurn(threadId, turnId);
    }

    @Override
    public List<AiProviderModel> listModels(AiCallConfig config) {
        return resolve(config).listModels(config);
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
            return openaiProvider.getIfAvailable(() -> { throw new IllegalStateException("OpenAI provider not active"); });
        }
        if ("xai".equals(selected) || "grok".equals(selected) || "grok-ai".equals(selected)) {
            return xaiProvider.getIfAvailable(() -> { throw new IllegalStateException("xAI provider not active"); });
        }
        if ("mock".equals(selected)) {
            return mockProvider.getIfAvailable(() -> { throw new IllegalStateException("Mock provider not active"); });
        }
        if (!"gemini".equals(selected)) {
            log.warn("[AiProviderRouter] Unknown provider '{}', defaulting to gemini.", selected);
        }
        return geminiProvider.getIfAvailable(() -> { throw new IllegalStateException("Gemini provider not active (check configuration/api-key)"); });
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
