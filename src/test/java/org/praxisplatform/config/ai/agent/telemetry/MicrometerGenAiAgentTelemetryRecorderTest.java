package org.praxisplatform.config.ai.agent.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.security.AgentSecurityFinding;
import org.praxisplatform.config.ai.agent.security.AgentSecurityVerdict;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

@Tag("unit")
class MicrometerGenAiAgentTelemetryRecorderTest {

    @Test
    void recordsGenAiObservationsAndSafeTelemetrySummary() {
        List<Observation.Context> stoppedContexts = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stoppedContexts.add(context);
            }
        });

        MicrometerGenAiAgentTelemetryRecorder recorder = new MicrometerGenAiAgentTelemetryRecorder(registry);
        AgentExecutionContext ctx = new AgentExecutionContext(
                null, null, null, "tenant-x", "user-1", "prod", "joao.silva", Set.of(), null, null, null, null);

        AgentTurnTelemetryRecorder.TurnSpan span = recorder.startTurn(ctx, "gemini", "gemini-2.5-flash");
        span.recordModelRound(
                1,
                "gemini",
                "gemini-2.5-flash",
                new AgentTokenUsage(100, 25, 125, 80, 0, 10, true),
                45L,
                1,
                "tool_calls");
        span.recordToolExecution(
                "execute_verified_analytical_recipe",
                "READ_ONLY",
                false,
                false,
                "headcount-by-department",
                12L);
        span.recordSecurityFinding(new AgentSecurityFinding(
                "tool_output", "indirect-prompt-injection-in-tool-output",
                AgentSecurityVerdict.Decision.QUARANTINE, "notes", "Prompt injection isolated"));

        AgentTurnTelemetrySummary summary = span.complete(
                "gemini",
                "gemini-2.5-flash",
                1,
                new AgentTokenUsage(100, 25, 125, 80, 0, 10, true),
                true,
                "completed");

        assertThat(stoppedContexts)
                .extracting(Observation.Context::getName)
                .containsExactlyInAnyOrder(
                        MicrometerGenAiAgentTelemetryRecorder.OBS_MODEL_CHAT,
                        MicrometerGenAiAgentTelemetryRecorder.OBS_EXECUTE_TOOL,
                        MicrometerGenAiAgentTelemetryRecorder.OBS_SECURITY_EVENT,
                        MicrometerGenAiAgentTelemetryRecorder.OBS_AGENT_TURN);

        Map<String, Object> safeMeta = summary.toSafeEventMeta();
        assertThat(safeMeta).containsEntry("provider", "gemini")
                .containsEntry("iterations", 1)
                .containsEntry("toolCallCount", 1)
                .containsEntry("tainted", true)
                .containsEntry("cacheHit", true);
        // Must never emit PII or redactor-forbidden substrings in telemetryMeta keys
        assertThat(safeMeta.keySet()).noneMatch(k ->
                k.toLowerCase().contains("token")
                        || k.toLowerCase().contains("summary")
                        || k.toLowerCase().contains("details")
                        || k.toLowerCase().contains("content"));
    }

    @Test
    void recordsActiveSpanLifecyclesFailuresAndIdempotentClose() {
        List<Observation.Context> stoppedContexts = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stoppedContexts.add(context);
            }
        });

        MicrometerGenAiAgentTelemetryRecorder recorder = new MicrometerGenAiAgentTelemetryRecorder(registry);
        AgentExecutionContext ctx = new AgentExecutionContext(
                null, null, null, "tenant-x", "user-1", "prod", "joao.silva", Set.of(), null, null, null, null);

        IllegalStateException boom = new IllegalStateException("provider down");
        AgentTurnTelemetrySummary summary;
        try (AgentTurnTelemetryRecorder.TurnSpan turnSpan = recorder.startTurn(ctx, "openai", "gpt-5")) {
            try (AgentTurnTelemetryRecorder.ToolExecutionSpan toolSpan =
                         turnSpan.startToolExecution("query_resource_records", "READ_ONLY", null)) {
                toolSpan.finish(false, false);
                toolSpan.close(); // idempotent close after finish
            }
            try (AgentTurnTelemetryRecorder.ModelRoundSpan roundSpan =
                         turnSpan.startModelRound(1, "openai", "gpt-5")) {
                roundSpan.fail(boom);
                roundSpan.finish("openai", "gpt-5", new AgentTokenUsage(10, 0, 10), 0, "error");
            }
            turnSpan.fail(boom);
            summary = turnSpan.complete("openai", "gpt-5", 1, new AgentTokenUsage(10, 0, 10), false, "error");
            turnSpan.close(); // idempotent close after complete
        }

        assertThat(stoppedContexts).hasSize(3);
        assertThat(stoppedContexts.get(0).getName()).isEqualTo(MicrometerGenAiAgentTelemetryRecorder.OBS_EXECUTE_TOOL);
        assertThat(stoppedContexts.get(1).getName()).isEqualTo(MicrometerGenAiAgentTelemetryRecorder.OBS_MODEL_CHAT);
        assertThat(stoppedContexts.get(1).getError()).isSameAs(boom);
        assertThat(stoppedContexts.get(2).getName()).isEqualTo(MicrometerGenAiAgentTelemetryRecorder.OBS_AGENT_TURN);
        assertThat(stoppedContexts.get(2).getError()).isSameAs(boom);
        assertThat(summary.toolCallCount()).isEqualTo(1);
        assertThat(summary.finishStatus()).isEqualTo("error");
    }
}
