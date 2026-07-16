package org.praxisplatform.config.service;

import io.micrometer.core.instrument.Metrics;
import java.util.Locale;

/** Publishes bounded, prompt-free operational metrics for traced AI provider invocations. */
public final class AiProviderInvocationMetrics {

    static final String INVOCATIONS_TOTAL = "ai_provider_invocations_total";
    static final String DURATION_MS = "ai_provider_invocation_duration_ms";
    static final String TOKENS = "ai_provider_tokens";

    private AiProviderInvocationMetrics() {
    }

    public static void record(AiProviderInvocationTelemetry invocation) {
        if (invocation == null) {
            return;
        }
        String phase = tag(invocation.phase());
        String provider = tag(invocation.provider());
        String status = tag(invocation.status());
        Metrics.counter(
                        INVOCATIONS_TOTAL,
                        "phase", phase,
                        "provider", provider,
                        "status", status)
                .increment();
        Metrics.summary(
                        DURATION_MS,
                        "phase", phase,
                        "provider", provider,
                        "status", status)
                .record(Math.max(0L, invocation.latencyMs()));
        recordTokens(phase, provider, "input", invocation.inputTokens());
        recordTokens(phase, provider, "output", invocation.outputTokens());
        recordTokens(phase, provider, "cache_read_input", invocation.cacheReadInputTokens());
        recordTokens(phase, provider, "cache_write_input", invocation.cacheWriteInputTokens());
        recordTokens(phase, provider, "total", invocation.totalTokens());
    }

    private static void recordTokens(String phase, String provider, String kind, Integer value) {
        if (value == null || value < 0) {
            return;
        }
        Metrics.summary(TOKENS, "phase", phase, "provider", provider, "kind", kind)
                .record(value);
    }

    private static String tag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
