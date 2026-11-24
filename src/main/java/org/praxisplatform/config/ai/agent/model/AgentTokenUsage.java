package org.praxisplatform.config.ai.agent.model;

/**
 * Contadores agregados de consumo de tokens retornados pelo provedor em uma ou mais rodadas,
 * incluindo leitura/escrita em prompt cache e tokens de raciocinio (thinking/reasoning).
 */
public record AgentTokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens,
        long thinkingTokens,
        boolean cacheHit
) {
    public static final AgentTokenUsage ZERO = new AgentTokenUsage(0L, 0L, 0L, 0L, 0L, 0L, false);

    public AgentTokenUsage(long inputTokens, long outputTokens, long totalTokens) {
        this(inputTokens, outputTokens, totalTokens, 0L, 0L, 0L, false);
    }

    public AgentTokenUsage plus(AgentTokenUsage other) {
        if (other == null) {
            return this;
        }
        return new AgentTokenUsage(
                Math.max(0L, inputTokens) + Math.max(0L, other.inputTokens()),
                Math.max(0L, outputTokens) + Math.max(0L, other.outputTokens()),
                Math.max(0L, totalTokens) + Math.max(0L, other.totalTokens()),
                Math.max(0L, cacheReadInputTokens) + Math.max(0L, other.cacheReadInputTokens()),
                Math.max(0L, cacheWriteInputTokens) + Math.max(0L, other.cacheWriteInputTokens()),
                Math.max(0L, thinkingTokens) + Math.max(0L, other.thinkingTokens()),
                this.cacheHit || other.cacheHit());
    }
}
