package org.praxisplatform.config.ai.agent.trajectory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.AgentSystemInstructionComposer;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.security.AgentSecurityMode;
import org.praxisplatform.config.ai.agent.security.DefaultDeterministicAgentSecurityGuard;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.telemetry.MicrometerGenAiAgentTelemetryRecorder;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.service.AiCallConfig;

/**
 * Deterministic multi-turn agent harness for evaluating tool selection order, security gates,
 * analytical provenance propagation, and instruction stability offline.
 */
public final class AgentTrajectoryTestHarness {

    private final Queue<AgentModelTurnRequest> capturedRequests = new ConcurrentLinkedQueue<>();

    public PraxisOperationalAgentLoop.AgentTurnOutcome runScriptedTrajectory(
            AgentExecutionContext context,
            String userPrompt,
            List<OperationalAgentTool> tools,
            AgentResourceExecutionBridge bridge,
            List<AgentModelTurnResponse> scriptedResponses) {
        return runScriptedTrajectory(context, userPrompt, List.of(), tools, bridge, scriptedResponses);
    }

    public PraxisOperationalAgentLoop.AgentTurnOutcome runScriptedTrajectory(
            AgentExecutionContext context,
            String userPrompt,
            List<PraxisOperationalAgentLoop.ConversationHistoryEntry> history,
            List<OperationalAgentTool> tools,
            AgentResourceExecutionBridge bridge,
            List<AgentModelTurnResponse> scriptedResponses) {
        Queue<AgentModelTurnResponse> script = new ConcurrentLinkedQueue<>(scriptedResponses);
        AiToolCallingProvider scriptedProvider = new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return "scripted";
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    AgentModelTurnRequest request,
                    AgentModelStreamListener listener) {
                capturedRequests.add(request);
                AgentModelTurnResponse next = script.poll();
                assertThat(next)
                        .as("Scripted LLM responses exhausted unexpectedly after %d requests", capturedRequests.size())
                        .isNotNull();
                return next;
            }
        };

        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                scriptedProvider,
                tools,
                new OperationalAssistantBlockComposer(new ObjectMapper()),
                PraxisOperationalAgentLoop.DEFAULT_MAX_ITERATIONS,
                bridge,
                AgentSystemInstructionComposer.defaultComposer(),
                new DefaultDeterministicAgentSecurityGuard(),
                AgentSecurityMode.ENFORCE,
                new MicrometerGenAiAgentTelemetryRecorder(ObservationRegistry.NOOP));

        return loop.execute(
                context,
                userPrompt,
                TurnScopedPiiVault.tokenize(userPrompt),
                history,
                AiCallConfig.builder().provider("scripted").model("scripted-v1").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);
    }

    public List<AgentModelTurnRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }
}
