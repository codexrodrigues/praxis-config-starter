package org.praxisplatform.config.ai.agent.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Non-PII operational telemetry summary for a single operational agent turn.
 * Deliberately excludes raw prompts, free-text narratives, record payloads, and redactor-sensitive keys.
 */
public record AgentTurnTelemetrySummary(
        String provider,
        String model,
        int iterations,
        int toolCallCount,
        int toolErrorCount,
        int blockedToolCalls,
        boolean tainted,
        boolean cacheHit,
        long inputTokens,
        long outputTokens,
        long thinkingTokens,
        long cacheReadInputTokens,
        long durationMs,
        String finishStatus
) {
    /**
     * Emits a compact map safe for SSE {@code result.telemetryMeta} without keys blocked by
     * {@code AiSensitiveDataRedactor} (avoids "token", "summary", "details", "reason", "content").
     */
    public Map<String, Object> toSafeEventMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (provider != null && !provider.isBlank()) {
            meta.put("provider", provider);
        }
        if (model != null && !model.isBlank()) {
            meta.put("model", model);
        }
        meta.put("iterations", iterations);
        meta.put("toolCallCount", toolCallCount);
        meta.put("toolErrorCount", toolErrorCount);
        meta.put("blockedToolCalls", blockedToolCalls);
        meta.put("tainted", tainted);
        meta.put("cacheHit", cacheHit);
        meta.put("durationMs", durationMs);
        meta.put("finishStatus", finishStatus == null ? "completed" : finishStatus);
        return Map.copyOf(meta);
    }
}
