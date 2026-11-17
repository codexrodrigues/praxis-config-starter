package org.praxisplatform.config.ai.agent.model;

/**
 * Contadores agregados de consumo de tokens retornados pelo provedor em uma ou mais rodadas.
 */
public record AgentTokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens
) {
    public static final AgentTokenUsage ZERO = new AgentTokenUsage(0L, 0L, 0L);

    public AgentTokenUsage plus(AgentTokenUsage other) {
        if (other == null) {
            return this;
        }
        return new AgentTokenUsage(
                Math.max(0L, inputTokens) + Math.max(0L, other.inputTokens()),
                Math.max(0L, outputTokens) + Math.max(0L, other.outputTokens()),
                Math.max(0L, totalTokens) + Math.max(0L, other.totalTokens()));
    }
}
