package org.praxisplatform.config.service;

/**
 * Snapshot sanitizado de uma invocacao de provider AI.
 *
 * <p>O contrato transporta apenas identidade operacional, latencia e contadores de uso. Prompt,
 * resposta, credenciais, headers e payloads nativos do provider ficam deliberadamente fora desta
 * superficie.</p>
 */
public record AiProviderInvocationTelemetry(
        String phase,
        int attempt,
        String provider,
        String model,
        String transport,
        String status,
        String failureKind,
        long latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheReadInputTokens,
        Integer cacheWriteInputTokens,
        Integer totalTokens,
        String responseId,
        String finishReason) {
}
