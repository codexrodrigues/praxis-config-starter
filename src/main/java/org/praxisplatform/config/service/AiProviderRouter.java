package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

/**
 * Roteador primário de provedores AI.
 *
 * <p>
 * Seleciona a implementação concreta a partir do provider pedido em runtime ou do provider padrão
 * configurado, preservando a interface {@link AiProvider} como boundary estável para o restante do
 * módulo.
 * </p>
 */
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

    @Value("${praxis.ai.provider-fallback.enabled:true}")
    private boolean providerFallbackEnabled;

    @Value("${praxis.ai.provider-fallback.candidates:gemini,openai}")
    private String providerFallbackCandidates;

    @Override
    public JsonNode generateJson(String prompt) {
        return executeWithFallback(null, "generateJson", (selectedProvider, selectedConfig) ->
                selectedProvider.generateJson(prompt, null, selectedConfig));
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return executeWithFallback(null, "generateJson(schema)", (selectedProvider, selectedConfig) ->
                selectedProvider.generateJson(prompt, schema, selectedConfig));
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        return executeWithFallback(config, "generateJson(schema, config)", (selectedProvider, selectedConfig) ->
                selectedProvider.generateJson(prompt, schema, selectedConfig));
    }

    @Override
    public String generateText(String prompt) {
        return executeWithFallback(null, "generateText", (selectedProvider, selectedConfig) ->
                selectedProvider.generateText(prompt, selectedConfig));
    }

    @Override
    public String generateText(String prompt, AiCallConfig config) {
        return executeWithFallback(config, "generateText(config)", (selectedProvider, selectedConfig) ->
                selectedProvider.generateText(prompt, selectedConfig));
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
        java.util.concurrent.atomic.AtomicBoolean emittedChunk = new java.util.concurrent.atomic.AtomicBoolean(false);
        Consumer<String> guardedChunkConsumer = chunk -> {
            emittedChunk.set(true);
            if (onChunk != null) {
                onChunk.accept(chunk);
            }
        };
        return executeWithFallback(
                config,
                "generateTextStream(config)",
                () -> !emittedChunk.get(),
                (selectedProvider, selectedConfig) -> selectedProvider.generateTextStream(
                        prompt,
                        selectedConfig,
                        guardedChunkConsumer,
                        cancellationRequested));
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
        AiProvider selectedProvider = resolveProvider(selected, true);
        if (selectedProvider != null) {
            return selectedProvider;
        }
        return geminiProvider.getIfAvailable(() -> { throw new IllegalStateException("Gemini provider not active (check configuration/api-key)"); });
    }

    private AiProvider resolveProvider(String selected, boolean allowUnknownGeminiDefault) {
        if (!"gemini".equals(selected)) {
            if ("openai".equals(selected) || "open-ai".equals(selected)) {
                return openaiProvider.getIfAvailable(() -> { throw new IllegalStateException("OpenAI provider not active"); });
            }
            if ("xai".equals(selected) || "grok".equals(selected) || "grok-ai".equals(selected)) {
                return xaiProvider.getIfAvailable(() -> { throw new IllegalStateException("xAI provider not active"); });
            }
            if ("mock".equals(selected)) {
                return mockProvider.getIfAvailable(() -> { throw new IllegalStateException("Mock provider not active"); });
            }
            if (!allowUnknownGeminiDefault) {
                log.warn("[AiProviderRouter] Unknown fallback provider '{}'; skipping candidate.", selected);
                return null;
            }
            log.warn("[AiProviderRouter] Unknown provider '{}', defaulting to gemini.", selected);
        }
        return geminiProvider.getIfAvailable(() -> { throw new IllegalStateException("Gemini provider not active (check configuration/api-key)"); });
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> T executeWithFallback(
            AiCallConfig config,
            String operationName,
            ProviderOperation<T> operation) {
        return executeWithFallback(config, operationName, () -> true, operation);
    }

    private <T> T executeWithFallback(
            AiCallConfig config,
            String operationName,
            Supplier<Boolean> fallbackAllowed,
            ProviderOperation<T> operation) {
        List<ProviderCandidate> candidates = resolveProviderCandidates(config);
        RuntimeException firstRecoverableFailure = null;
        RuntimeException lastRecoverableFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            ProviderCandidate candidate = candidates.get(i);
            AiProvider selectedProvider = resolveProvider(candidate.provider(), i == 0);
            if (selectedProvider == null) {
                continue;
            }
            AiCallConfig selectedConfig = configForCandidate(config, candidate, i == 0);
            try {
                return operation.execute(selectedProvider, selectedConfig);
            } catch (RuntimeException ex) {
                boolean canTryFallback = providerFallbackEnabled
                        && i + 1 < candidates.size()
                        && Boolean.TRUE.equals(fallbackAllowed.get())
                        && isRecoverableForFallback(ex);
                if (!canTryFallback) {
                    if (i > 0 && firstRecoverableFailure != null && !isRecoverableForFallback(ex)) {
                        log.warn(
                                "[AiProviderRouter] Provider fallback candidate failed with non-recoverable error; operation={} provider={} model={} candidate={}/{}. Keeping primary recoverable failure.",
                                operationName,
                                candidate.provider(),
                                candidate.model(),
                                i + 1,
                                candidates.size());
                        continue;
                    }
                    throw ex;
                }
                if (firstRecoverableFailure == null) {
                    firstRecoverableFailure = ex;
                }
                lastRecoverableFailure = ex;
                ProviderCandidate next = candidates.get(i + 1);
                log.warn(
                        "[AiProviderRouter] Provider fallback; operation={} provider={} model={} nextProvider={} nextModel={} candidate={}/{} reason={}",
                        operationName,
                        candidate.provider(),
                        candidate.model(),
                        next.provider(),
                        next.model(),
                        i + 1,
                        candidates.size(),
                        normalizedFailureReason(ex));
            }
        }
        if (firstRecoverableFailure != null) {
            throw firstRecoverableFailure;
        }
        if (lastRecoverableFailure != null) {
            throw lastRecoverableFailure;
        }
        throw new IllegalStateException("No AI provider candidate is active.");
    }

    private List<ProviderCandidate> resolveProviderCandidates(AiCallConfig config) {
        ProviderCandidate primary = primaryCandidate(config);
        Set<String> seen = new LinkedHashSet<>();
        List<ProviderCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, seen, primary);
        if (providerFallbackEnabled) {
            for (String token : parseFallbackCandidateTokens()) {
                addCandidate(candidates, seen, parseCandidate(token));
            }
        }
        return candidates;
    }

    private ProviderCandidate primaryCandidate(AiCallConfig config) {
        String requested = config != null ? normalizeProvider(config.getProvider()) : null;
        String selected = requested != null ? requested : normalizeProvider(provider);
        if (selected == null) {
            selected = "gemini";
        }
        String selectedModel = config != null && config.getModel() != null && !config.getModel().isBlank()
                ? config.getModel().trim()
                : null;
        return new ProviderCandidate(selected, selectedModel);
    }

    private List<String> parseFallbackCandidateTokens() {
        if (providerFallbackCandidates == null || providerFallbackCandidates.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(providerFallbackCandidates.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private ProviderCandidate parseCandidate(String token) {
        int separator = token.indexOf(':');
        String candidateProvider = separator >= 0 ? token.substring(0, separator) : token;
        String candidateModel = separator >= 0 ? token.substring(separator + 1) : null;
        return new ProviderCandidate(
                normalizeProvider(candidateProvider),
                candidateModel != null && !candidateModel.isBlank() ? candidateModel.trim() : null);
    }

    private void addCandidate(
            List<ProviderCandidate> candidates,
            Set<String> seen,
            ProviderCandidate candidate) {
        if (candidate == null || candidate.provider() == null) {
            return;
        }
        String key = candidate.provider() + "|" + (candidate.model() == null ? "" : candidate.model());
        if (seen.add(key)) {
            candidates.add(candidate);
        }
    }

    private AiCallConfig configForCandidate(AiCallConfig config, ProviderCandidate candidate, boolean primary) {
        AiCallConfig base = config != null ? config : AiCallConfig.builder().build();
        AiCallConfig.AiCallConfigBuilder builder = base.toBuilder()
                .provider(candidate.provider())
                .model(candidate.model());
        String primaryProvider = normalizeProvider(base.getProvider());
        if (primaryProvider == null) {
            primaryProvider = normalizeProvider(provider);
        }
        if (primaryProvider == null) {
            primaryProvider = "gemini";
        }
        if (!primary && !candidate.provider().equals(primaryProvider)) {
            builder.apiKey(null);
        }
        return builder.build();
    }

    private boolean isRecoverableForFallback(RuntimeException ex) {
        if (ex instanceof AiProviderCallException callException) {
            return switch (callException.getKind()) {
                case RATE_LIMIT, CAPACITY, TIMEOUT, TRANSPORT, SERVER_ERROR -> true;
                default -> false;
            };
        }
        if (ex instanceof AiProviderStreamException streamException) {
            return switch (streamException.getKind()) {
                case RATE_LIMIT, CAPACITY, TIMEOUT, TRANSPORT, SERVER_ERROR -> true;
                default -> false;
            };
        }
        return false;
    }

    private String normalizedFailureReason(RuntimeException ex) {
        if (ex instanceof AiProviderCallException callException) {
            return callException.getKind().name().toLowerCase(Locale.ROOT);
        }
        if (ex instanceof AiProviderStreamException streamException) {
            return streamException.getKind().name().toLowerCase(Locale.ROOT);
        }
        return ex.getClass().getSimpleName();
    }

    @FunctionalInterface
    private interface ProviderOperation<T> {
        T execute(AiProvider provider, AiCallConfig config);
    }

    private record ProviderCandidate(String provider, String model) {
    }
}
