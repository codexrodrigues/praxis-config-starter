package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

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
@Slf4j
public class AiProviderRouter implements AiProvider {

    private final ObjectProvider<SpringAiGeminiService> geminiProvider;
    private final ObjectProvider<SpringAiOpenAiService> openaiProvider;
    private final ObjectProvider<SpringAiXaiService> xaiProvider;
    private final ObjectProvider<MockAiService> mockProvider;
    private final AiProviderRateLimitStore rateLimitStore;

    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Value("${praxis.ai.provider:gemini}")
    private String provider;

    @Value("${praxis.ai.provider-fallback.enabled:true}")
    private boolean providerFallbackEnabled;

    @Value("${praxis.ai.provider-fallback.candidates:gemini,openai}")
    private String providerFallbackCandidates;

    @Value("${praxis.ai.provider-fallback.default-rate-limit-cooldown-seconds:5}")
    private long defaultRateLimitCooldownSeconds = 5L;

    public AiProviderRouter(
            ObjectProvider<SpringAiGeminiService> geminiProvider,
            ObjectProvider<SpringAiOpenAiService> openaiProvider,
            ObjectProvider<SpringAiXaiService> xaiProvider,
            ObjectProvider<MockAiService> mockProvider) {
        this(geminiProvider, openaiProvider, xaiProvider, mockProvider, new InMemoryAiProviderRateLimitStore());
    }

    @Autowired
    public AiProviderRouter(
            ObjectProvider<SpringAiGeminiService> geminiProvider,
            ObjectProvider<SpringAiOpenAiService> openaiProvider,
            ObjectProvider<SpringAiXaiService> xaiProvider,
            ObjectProvider<MockAiService> mockProvider,
            ObjectProvider<AiProviderRateLimitStore> rateLimitStoreProvider) {
        this(
                geminiProvider,
                openaiProvider,
                xaiProvider,
                mockProvider,
                rateLimitStoreProvider != null
                        ? rateLimitStoreProvider.getIfAvailable(InMemoryAiProviderRateLimitStore::new)
                        : new InMemoryAiProviderRateLimitStore());
    }

    public AiProviderRouter(
            ObjectProvider<SpringAiGeminiService> geminiProvider,
            ObjectProvider<SpringAiOpenAiService> openaiProvider,
            ObjectProvider<SpringAiXaiService> xaiProvider,
            ObjectProvider<MockAiService> mockProvider,
            AiProviderRateLimitStore rateLimitStore) {
        this.geminiProvider = geminiProvider;
        this.openaiProvider = openaiProvider;
        this.xaiProvider = xaiProvider;
        this.mockProvider = mockProvider;
        this.rateLimitStore = rateLimitStore != null ? rateLimitStore : new InMemoryAiProviderRateLimitStore();
    }

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
        ProviderCandidate primary = primaryCandidate(config);
        List<ProviderCandidate> candidates = resolveProviderCandidates(config);
        long deadlineNanos = operationDeadlineNanos(config);
        RuntimeException firstRecoverableFailure = null;
        RuntimeException lastRecoverableFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            ProviderCandidate candidate = candidates.get(i);
            boolean isPrimaryCandidate = candidate.equals(primary);
            AiProvider selectedProvider = resolveProvider(candidate.provider(), isPrimaryCandidate);
            if (selectedProvider == null) {
                continue;
            }
            Integer remainingTimeoutSeconds = remainingTimeoutSeconds(config, deadlineNanos);
            if (deadlineNanos > 0 && remainingTimeoutSeconds == null) {
                log.warn(
                        "[AiProviderRouter] Provider fallback budget exhausted; operation={} candidate={}/{}.",
                        operationName,
                        i + 1,
                        candidates.size());
                break;
            }
            AiCallConfig selectedConfig = configForCandidate(
                    config,
                    candidate,
                    isPrimaryCandidate,
                    remainingTimeoutSeconds);
            try {
                T result = operation.execute(selectedProvider, selectedConfig);
                if (!hasCustomApiKey(selectedConfig)) {
                    rateLimitStore.clearCooldown(candidate.provider(), candidate.model());
                }
                return result;
            } catch (RuntimeException ex) {
                if (!hasCustomApiKey(selectedConfig)) {
                    recordRateLimitCooldownIfApplicable(candidate, ex);
                }
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
                String reason = normalizedFailureReason(ex);
                counter(
                        "praxis_ai_provider_fallback_total",
                        "operation", safeMetricTag(operationName),
                        "from_provider", safeMetricTag(candidate.provider()),
                        "to_provider", safeMetricTag(next.provider()),
                        "reason", safeMetricTag(reason)).increment();
                log.warn(
                        "[AiProviderRouter] Provider fallback; operation={} provider={} model={} nextProvider={} nextModel={} candidate={}/{} reason={}",
                        operationName,
                        candidate.provider(),
                        candidate.model(),
                        next.provider(),
                        next.model(),
                        i + 1,
                        candidates.size(),
                        reason);
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

    private long operationDeadlineNanos(AiCallConfig config) {
        if (config == null || config.getTimeoutSeconds() == null || config.getTimeoutSeconds() <= 0) {
            return 0L;
        }
        long timeoutNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(config.getTimeoutSeconds());
        long now = System.nanoTime();
        return Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private Integer remainingTimeoutSeconds(AiCallConfig config, long deadlineNanos) {
        if (deadlineNanos <= 0) {
            return config == null ? null : config.getTimeoutSeconds();
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return null;
        }
        long remainingSeconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
        if (java.util.concurrent.TimeUnit.SECONDS.toNanos(remainingSeconds) < remainingNanos) {
            remainingSeconds++;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainingSeconds));
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
        if (!providerFallbackEnabled || candidates.size() <= 1 || rateLimitStore == null) {
            return candidates;
        }
        Instant now = Instant.now();
        boolean primaryUsesCustomKey = hasCustomApiKey(config);
        List<ProviderCandidate> healthy = new ArrayList<>(candidates.size());
        List<ProviderCandidate> cooledDown = new ArrayList<>();
        for (ProviderCandidate candidate : candidates) {
            boolean skipSharedCooldownForPrimary = primaryUsesCustomKey && candidate.equals(primary);
            if (!skipSharedCooldownForPrimary
                    && rateLimitStore.isInCooldown(candidate.provider(), candidate.model(), now)) {
                cooledDown.add(candidate);
            } else {
                healthy.add(candidate);
            }
        }
        if (healthy.isEmpty() || cooledDown.isEmpty()) {
            return candidates;
        }
        List<ProviderCandidate> prioritized = new ArrayList<>(candidates.size());
        prioritized.addAll(healthy);
        prioritized.addAll(cooledDown);
        return prioritized;
    }

    private void recordRateLimitCooldownIfApplicable(ProviderCandidate candidate, RuntimeException ex) {
        if (candidate == null || rateLimitStore == null || ex == null) {
            return;
        }
        Instant retryAfter = null;
        boolean shouldRecord = false;
        if (ex instanceof AiProviderCallException callException) {
            retryAfter = callException.getRetryAfter();
            shouldRecord = retryAfter != null
                    || callException.getKind() == AiProviderCallException.Kind.RATE_LIMIT
                    || callException.getKind() == AiProviderCallException.Kind.CAPACITY;
        } else if (ex instanceof AiProviderStreamException streamException) {
            shouldRecord = streamException.getKind() == AiProviderStreamException.Kind.RATE_LIMIT
                    || streamException.getKind() == AiProviderStreamException.Kind.CAPACITY;
        }
        if (!shouldRecord) {
            return;
        }
        Instant now = Instant.now();
        if (retryAfter == null || !retryAfter.isAfter(now)) {
            if (defaultRateLimitCooldownSeconds <= 0) {
                return;
            }
            retryAfter = now.plusSeconds(defaultRateLimitCooldownSeconds);
        }
        String reason = normalizedFailureReason(ex);
        rateLimitStore.recordCooldown(
                candidate.provider(),
                candidate.model(),
                retryAfter,
                reason);
        counter(
                "praxis_ai_provider_cooldown_recorded_total",
                "provider", safeMetricTag(candidate.provider()),
                "reason", safeMetricTag(reason)).increment();
    }

    private io.micrometer.core.instrument.Counter counter(String name, String... tags) {
        return meterRegistry != null
                ? meterRegistry.counter(name, tags)
                : io.micrometer.core.instrument.Metrics.counter(name, tags);
    }

    private String safeMetricTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
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

    private AiCallConfig configForCandidate(
            AiCallConfig config,
            ProviderCandidate candidate,
            boolean primary,
            Integer remainingTimeoutSeconds) {
        AiCallConfig base = config != null ? config : AiCallConfig.builder().build();
        AiCallConfig.AiCallConfigBuilder builder = base.toBuilder()
                .provider(candidate.provider())
                .model(candidate.model())
                .timeoutSeconds(remainingTimeoutSeconds);
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

    private boolean hasCustomApiKey(AiCallConfig config) {
        return config != null && config.getApiKey() != null && !config.getApiKey().isBlank();
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
