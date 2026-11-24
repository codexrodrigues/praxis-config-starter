package org.praxisplatform.config.ai.agent.telemetry;

import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.security.AgentSecurityFinding;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

/**
 * Pluggable SPI for recording GenAI semantic metrics and traces across turns, model rounds,
 * tool executions, and security guard evaluations without emitting PII.
 */
public interface AgentTurnTelemetryRecorder {

    AgentTurnTelemetryRecorder NOOP = new AgentTurnTelemetryRecorder() {
    };

    default TurnSpan startTurn(AgentExecutionContext context, String requestedProvider, String requestedModel) {
        return TurnSpan.NOOP;
    }

    interface TurnSpan extends AutoCloseable {
        TurnSpan NOOP = new TurnSpan() {
        };

        default void recordModelRound(
                int round,
                String provider,
                String model,
                AgentTokenUsage usage,
                long durationMs,
                int toolCallsCount,
                String finishReason) {
        }

        default void recordToolExecution(
                String toolName,
                String safetyClass,
                boolean error,
                boolean blockedBySecurity,
                String recipeId,
                long durationMs) {
        }

        default void recordSecurityFinding(AgentSecurityFinding finding) {
        }

        default AgentTurnTelemetrySummary complete(
                String finalProvider,
                String finalModel,
                int iterations,
                AgentTokenUsage totalUsage,
                boolean tainted,
                String finishStatus) {
            return new AgentTurnTelemetrySummary(
                    finalProvider,
                    finalModel,
                    iterations,
                    0,
                    0,
                    0,
                    tainted,
                    totalUsage != null && totalUsage.cacheHit(),
                    totalUsage != null ? totalUsage.inputTokens() : 0L,
                    totalUsage != null ? totalUsage.outputTokens() : 0L,
                    totalUsage != null ? totalUsage.thinkingTokens() : 0L,
                    totalUsage != null ? totalUsage.cacheReadInputTokens() : 0L,
                    0L,
                    finishStatus);
        }

        default ModelRoundSpan startModelRound(int round, String provider, String model) {
            long startNanos = System.nanoTime();
            return new ModelRoundSpan() {
                private boolean completed;

                @Override
                public void finish(
                        String finalProvider,
                        String finalModel,
                        AgentTokenUsage usage,
                        int toolCallsCount,
                        String finishReason) {
                    if (completed) {
                        return;
                    }
                    completed = true;
                    long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                    recordModelRound(
                            round,
                            finalProvider != null ? finalProvider : provider,
                            finalModel != null ? finalModel : model,
                            usage,
                            durationMs,
                            toolCallsCount,
                            finishReason);
                }
            };
        }

        default ToolExecutionSpan startToolExecution(String toolName, String safetyClass, String recipeId) {
            long startNanos = System.nanoTime();
            return new ToolExecutionSpan() {
                private boolean completed;

                @Override
                public void finish(boolean error, boolean blockedBySecurity) {
                    if (completed) {
                        return;
                    }
                    completed = true;
                    long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                    recordToolExecution(toolName, safetyClass, error, blockedBySecurity, recipeId, durationMs);
                }
            };
        }

        default void fail(Throwable error) {
        }

        @Override
        default void close() {
        }
    }

    interface ModelRoundSpan extends AutoCloseable {
        default void finish(
                String provider,
                String model,
                AgentTokenUsage usage,
                int toolCallsCount,
                String finishReason) {
        }

        default void fail(Throwable error) {
        }

        @Override
        default void close() {
        }
    }

    interface ToolExecutionSpan extends AutoCloseable {
        default void finish(boolean error, boolean blockedBySecurity) {
        }

        default void fail(Throwable error) {
        }

        @Override
        default void close() {
        }
    }
}
