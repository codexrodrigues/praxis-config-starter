package org.praxisplatform.config.service;

import java.util.concurrent.TimeUnit;

/**
 * Coletor request-scoped para preservar metadados de uma unica chamada AI ate o consumidor.
 *
 * <p>A instancia nunca deve ser reutilizada entre chamadas. Os metodos sincronizados permitem que
 * adapters assincronos completem metadados sem depender de estado global ou {@code ThreadLocal}.</p>
 */
public final class AiProviderInvocationTrace {

    private final String phase;
    private final int attempt;
    private final long startedAtNanos = System.nanoTime();

    private String provider;
    private String model;
    private String transport;
    private String status;
    private String failureKind;
    private Long completedAtNanos;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cacheReadInputTokens;
    private Integer cacheWriteInputTokens;
    private Integer totalTokens;
    private String responseId;
    private String finishReason;

    public AiProviderInvocationTrace(String phase, int attempt, String provider, String model) {
        this.phase = safe(phase, "unknown");
        this.attempt = Math.max(1, attempt);
        this.provider = safe(provider, "unknown");
        this.model = safe(model, "unknown");
    }

    public synchronized void providerSelected(String provider, String model) {
        this.provider = safe(provider, this.provider);
        this.model = safe(model, this.model);
    }

    public synchronized void providerResponse(
            String transport,
            String responseId,
            String model,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheReadInputTokens,
            Integer cacheWriteInputTokens,
            Integer totalTokens) {
        this.transport = nullableSafe(transport);
        this.responseId = nullableSafe(responseId);
        this.model = safe(model, this.model);
        this.finishReason = nullableSafe(finishReason);
        this.inputTokens = nonNegative(inputTokens);
        this.outputTokens = nonNegative(outputTokens);
        this.cacheReadInputTokens = nonNegative(cacheReadInputTokens);
        this.cacheWriteInputTokens = nonNegative(cacheWriteInputTokens);
        this.totalTokens = nonNegative(totalTokens);
    }

    public synchronized void succeeded() {
        complete("success", null);
    }

    public synchronized void failed(String failureKind) {
        complete("failure", safe(failureKind, "unknown"));
    }

    public synchronized AiProviderInvocationTelemetry snapshot() {
        long endedAt = completedAtNanos != null ? completedAtNanos : System.nanoTime();
        return new AiProviderInvocationTelemetry(
                phase,
                attempt,
                provider,
                model,
                transport,
                status != null ? status : "unknown",
                failureKind,
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(endedAt - startedAtNanos)),
                inputTokens,
                outputTokens,
                cacheReadInputTokens,
                cacheWriteInputTokens,
                totalTokens,
                responseId,
                finishReason);
    }

    private void complete(String status, String failureKind) {
        if (completedAtNanos != null) {
            return;
        }
        this.status = status;
        this.failureKind = failureKind;
        this.completedAtNanos = System.nanoTime();
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private static String safe(String value, String fallback) {
        String normalized = nullableSafe(value);
        return normalized != null ? normalized : fallback;
    }

    private static String nullableSafe(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}
