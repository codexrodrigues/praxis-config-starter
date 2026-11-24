package org.praxisplatform.config.ai.agent.telemetry;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.security.AgentSecurityFinding;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

/**
 * {@link AgentTurnTelemetryRecorder} backed by Micrometer {@link ObservationRegistry}, emitting
 * OpenTelemetry GenAI semantic attributes ({@code gen_ai.*}) with strict Zero-PII cardinality discipline.
 */
public final class MicrometerGenAiAgentTelemetryRecorder implements AgentTurnTelemetryRecorder {

    public static final String OBS_AGENT_TURN = "gen_ai.agent.turn";
    public static final String OBS_MODEL_CHAT = "gen_ai.chat";
    public static final String OBS_EXECUTE_TOOL = "gen_ai.execute_tool";
    public static final String OBS_SECURITY_EVENT = "praxis.agent.security.finding";

    private final ObservationRegistry observationRegistry;

    public MicrometerGenAiAgentTelemetryRecorder(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry != null
                ? observationRegistry
                : ObservationRegistry.NOOP;
    }

    @Override
    public TurnSpan startTurn(AgentExecutionContext context, String requestedProvider, String requestedModel) {
        long startNanos = System.nanoTime();
        Observation turnObs = Observation.createNotStarted(OBS_AGENT_TURN, observationRegistry)
                .lowCardinalityKeyValue("gen_ai.operation.name", "invoke_agent")
                .lowCardinalityKeyValue("gen_ai.system", safe(requestedProvider, "auto"))
                .lowCardinalityKeyValue("gen_ai.request.model", safe(requestedModel, "auto"))
                .lowCardinalityKeyValue("praxis.environment", safe(context != null ? context.environment() : null, "default"));
        turnObs.start();

        return new TurnSpan() {
            private final AtomicInteger toolCallCount = new AtomicInteger();
            private final AtomicInteger toolErrorCount = new AtomicInteger();
            private final AtomicInteger blockedToolCalls = new AtomicInteger();
            private final AtomicBoolean turnStopped = new AtomicBoolean();

            @Override
            public ModelRoundSpan startModelRound(int round, String requestedRoundProvider, String requestedRoundModel) {
                Observation roundObs = Observation.createNotStarted(OBS_MODEL_CHAT, observationRegistry)
                        .parentObservation(turnObs)
                        .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                        .lowCardinalityKeyValue("gen_ai.system", safe(requestedRoundProvider, "auto"))
                        .lowCardinalityKeyValue("gen_ai.request.model", safe(requestedRoundModel, "auto"))
                        .lowCardinalityKeyValue("praxis.agent.round", String.valueOf(round))
                        .start();
                AtomicBoolean roundStopped = new AtomicBoolean();
                return new ModelRoundSpan() {
                    @Override
                    public void finish(
                            String finalProvider,
                            String finalModel,
                            AgentTokenUsage usage,
                            int toolCallsCount,
                            String finishReason) {
                        if (!roundStopped.compareAndSet(false, true)) {
                            return;
                        }
                        roundObs.lowCardinalityKeyValue("gen_ai.system", safe(finalProvider, safe(requestedRoundProvider, "unknown")))
                                .lowCardinalityKeyValue("gen_ai.request.model", safe(finalModel, safe(requestedRoundModel, "unknown")))
                                .lowCardinalityKeyValue("gen_ai.response.finish_reason", safe(finishReason, "completed"));
                        if (usage != null) {
                            roundObs.highCardinalityKeyValue("gen_ai.usage.input_tokens", String.valueOf(usage.inputTokens()));
                            roundObs.highCardinalityKeyValue("gen_ai.usage.output_tokens", String.valueOf(usage.outputTokens()));
                            roundObs.highCardinalityKeyValue("gen_ai.usage.cache_read_input_tokens",
                                    String.valueOf(usage.cacheReadInputTokens()));
                            roundObs.highCardinalityKeyValue("gen_ai.usage.reasoning_tokens",
                                    String.valueOf(usage.thinkingTokens()));
                        }
                        roundObs.stop();
                    }

                    @Override
                    public void fail(Throwable error) {
                        if (!roundStopped.get() && error != null) {
                            roundObs.error(error);
                        }
                    }

                    @Override
                    public void close() {
                        if (roundStopped.compareAndSet(false, true)) {
                            roundObs.stop();
                        }
                    }
                };
            }

            @Override
            public void recordModelRound(
                    int round,
                    String provider,
                    String model,
                    AgentTokenUsage usage,
                    long durationMs,
                    int toolCallsCount,
                    String finishReason) {
                Observation roundObs = Observation.createNotStarted(OBS_MODEL_CHAT, observationRegistry)
                        .parentObservation(turnObs)
                        .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                        .lowCardinalityKeyValue("gen_ai.system", safe(provider, "unknown"))
                        .lowCardinalityKeyValue("gen_ai.request.model", safe(model, "unknown"))
                        .lowCardinalityKeyValue("gen_ai.response.finish_reason", safe(finishReason, "completed"))
                        .lowCardinalityKeyValue("praxis.agent.round", String.valueOf(round));
                if (usage != null) {
                    roundObs.highCardinalityKeyValue("gen_ai.usage.input_tokens", String.valueOf(usage.inputTokens()));
                    roundObs.highCardinalityKeyValue("gen_ai.usage.output_tokens", String.valueOf(usage.outputTokens()));
                    roundObs.highCardinalityKeyValue("gen_ai.usage.cache_read_input_tokens",
                            String.valueOf(usage.cacheReadInputTokens()));
                    roundObs.highCardinalityKeyValue("gen_ai.usage.reasoning_tokens",
                            String.valueOf(usage.thinkingTokens()));
                }
                roundObs.start().stop();
            }

            @Override
            public ToolExecutionSpan startToolExecution(String toolName, String safetyClass, String recipeId) {
                Observation toolObs = Observation.createNotStarted(OBS_EXECUTE_TOOL, observationRegistry)
                        .parentObservation(turnObs)
                        .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
                        .lowCardinalityKeyValue("gen_ai.tool.name", safe(toolName, "unknown"))
                        .lowCardinalityKeyValue("praxis.agent.tool.safety_class", safe(safetyClass, "READ_ONLY"));
                if (recipeId != null && !recipeId.isBlank()) {
                    toolObs.highCardinalityKeyValue("praxis.agent.recipe.id", recipeId.trim());
                }
                toolObs.start();
                AtomicBoolean toolStopped = new AtomicBoolean();
                AtomicBoolean recorded = new AtomicBoolean();
                return new ToolExecutionSpan() {
                    @Override
                    public void finish(boolean error, boolean blockedBySecurity) {
                        if (!toolStopped.compareAndSet(false, true)) {
                            return;
                        }
                        if (recorded.compareAndSet(false, true)) {
                            toolCallCount.incrementAndGet();
                            if (error) {
                                toolErrorCount.incrementAndGet();
                            }
                            if (blockedBySecurity) {
                                blockedToolCalls.incrementAndGet();
                            }
                        }
                        toolObs.lowCardinalityKeyValue("praxis.agent.tool.error", String.valueOf(error))
                                .lowCardinalityKeyValue("praxis.agent.tool.blocked", String.valueOf(blockedBySecurity));
                        toolObs.stop();
                    }

                    @Override
                    public void fail(Throwable error) {
                        if (!toolStopped.get() && error != null) {
                            toolObs.error(error);
                        }
                    }

                    @Override
                    public void close() {
                        if (toolStopped.compareAndSet(false, true)) {
                            if (recorded.compareAndSet(false, true)) {
                                toolCallCount.incrementAndGet();
                            }
                            toolObs.lowCardinalityKeyValue("praxis.agent.tool.error", "true")
                                    .lowCardinalityKeyValue("praxis.agent.tool.blocked", "false");
                            toolObs.stop();
                        }
                    }
                };
            }

            @Override
            public void recordToolExecution(
                    String toolName,
                    String safetyClass,
                    boolean error,
                    boolean blockedBySecurity,
                    String recipeId,
                    long durationMs) {
                toolCallCount.incrementAndGet();
                if (error) {
                    toolErrorCount.incrementAndGet();
                }
                if (blockedBySecurity) {
                    blockedToolCalls.incrementAndGet();
                }
                Observation toolObs = Observation.createNotStarted(OBS_EXECUTE_TOOL, observationRegistry)
                        .parentObservation(turnObs)
                        .lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
                        .lowCardinalityKeyValue("gen_ai.tool.name", safe(toolName, "unknown"))
                        .lowCardinalityKeyValue("praxis.agent.tool.safety_class", safe(safetyClass, "READ_ONLY"))
                        .lowCardinalityKeyValue("praxis.agent.tool.error", String.valueOf(error))
                        .lowCardinalityKeyValue("praxis.agent.tool.blocked", String.valueOf(blockedBySecurity));
                if (recipeId != null && !recipeId.isBlank()) {
                    toolObs.highCardinalityKeyValue("praxis.agent.recipe.id", recipeId.trim());
                }
                toolObs.start().stop();
            }

            @Override
            public void recordSecurityFinding(AgentSecurityFinding finding) {
                if (finding == null) {
                    return;
                }
                Observation secObs = Observation.createNotStarted(OBS_SECURITY_EVENT, observationRegistry)
                        .parentObservation(turnObs)
                        .lowCardinalityKeyValue("praxis.agent.security.stage", safe(finding.stage(), "unknown"))
                        .lowCardinalityKeyValue("praxis.agent.security.rule_id", safe(finding.ruleId(), "unknown"))
                        .lowCardinalityKeyValue("praxis.agent.security.decision", finding.decision().name());
                secObs.start().stop();
            }

            @Override
            public void fail(Throwable error) {
                if (!turnStopped.get() && error != null) {
                    turnObs.error(error);
                }
            }

            @Override
            public AgentTurnTelemetrySummary complete(
                    String finalProvider,
                    String finalModel,
                    int iterations,
                    AgentTokenUsage totalUsage,
                    boolean tainted,
                    String finishStatus) {
                long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                if (turnStopped.compareAndSet(false, true)) {
                    turnObs.lowCardinalityKeyValue("gen_ai.system", safe(finalProvider, "unknown"))
                            .lowCardinalityKeyValue("gen_ai.request.model", safe(finalModel, "unknown"))
                            .lowCardinalityKeyValue("praxis.agent.security.tainted", String.valueOf(tainted))
                            .lowCardinalityKeyValue("praxis.agent.finish_status", safe(finishStatus, "completed"));
                    if (totalUsage != null) {
                        turnObs.highCardinalityKeyValue("gen_ai.usage.input_tokens", String.valueOf(totalUsage.inputTokens()));
                        turnObs.highCardinalityKeyValue("gen_ai.usage.output_tokens", String.valueOf(totalUsage.outputTokens()));
                        turnObs.highCardinalityKeyValue("gen_ai.usage.cache_read_input_tokens",
                                String.valueOf(totalUsage.cacheReadInputTokens()));
                        turnObs.highCardinalityKeyValue("gen_ai.usage.reasoning_tokens",
                                String.valueOf(totalUsage.thinkingTokens()));
                    }
                    turnObs.stop();
                }
                return new AgentTurnTelemetrySummary(
                        finalProvider,
                        finalModel,
                        iterations,
                        toolCallCount.get(),
                        toolErrorCount.get(),
                        blockedToolCalls.get(),
                        tainted,
                        totalUsage != null && totalUsage.cacheHit(),
                        totalUsage != null ? totalUsage.inputTokens() : 0L,
                        totalUsage != null ? totalUsage.outputTokens() : 0L,
                        totalUsage != null ? totalUsage.thinkingTokens() : 0L,
                        totalUsage != null ? totalUsage.cacheReadInputTokens() : 0L,
                        durationMs,
                        finishStatus);
            }

            @Override
            public void close() {
                if (turnStopped.compareAndSet(false, true)) {
                    turnObs.stop();
                }
            }
        };
    }

    private static String safe(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
