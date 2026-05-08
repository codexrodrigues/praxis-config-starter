package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.service.AiPrincipalContext;

public class AgenticAuthoringToolLoopExecutor {

    static final int DEFAULT_MAX_STEPS = 5;

    private static final List<String> PHASES = List.of(
            "loadActiveDecision",
            "retrieveEvidence",
            "proposeDecision",
            "materializePlan",
            "validatePreview",
            "repairOrAsk");
    private static final Set<String> LOCAL_PHASES = Set.of(
            "loadActiveDecision",
            "proposeDecision",
            "materializePlan",
            "validatePreview");

    private final AgenticAuthoringToolRegistry toolRegistry;
    private final AgenticAuthoringToolLoopPlanner planner;
    private final int maxSteps;

    public AgenticAuthoringToolLoopExecutor(
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringToolLoopPlanner planner) {
        this(toolRegistry, planner, DEFAULT_MAX_STEPS);
    }

    AgenticAuthoringToolLoopExecutor(
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringToolLoopPlanner planner,
            int maxSteps) {
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public AgenticAuthoringToolLoopResult run(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            String routeClass) {
        List<AgenticAuthoringToolLoopStep> trace = new ArrayList<>();
        int executedTools = 0;
        for (String phase : PHASES) {
            AgenticAuthoringToolLoopContext context = new AgenticAuthoringToolLoopContext(
                    phase,
                    trace.size(),
                    routeClass,
                    request,
                    principalContext,
                    intentResolution,
                    preview,
                    List.copyOf(trace));
            AgenticAuthoringToolCall call = planner.nextTool(context).orElse(null);
            if (call == null) {
                if (LOCAL_PHASES.contains(phase)) {
                    trace.add(localStep(trace.size(), phase, request, intentResolution, preview));
                    continue;
                }
                trace.add(new AgenticAuthoringToolLoopStep(
                        trace.size(),
                        phase,
                        "",
                        true,
                        "",
                        Map.of("toolSelected", false)));
                continue;
            }
            if (executedTools >= maxSteps) {
                return new AgenticAuthoringToolLoopResult(false, "max-steps-exceeded", List.copyOf(trace));
            }
            AgenticAuthoringToolResult result = toolRegistry.execute(call, principalContext, phase);
            trace.add(new AgenticAuthoringToolLoopStep(
                    trace.size(),
                    phase,
                    safe(call.name()),
                    result.valid(),
                    safe(result.errorCode()),
                    safeDiagnostics(result)));
            executedTools++;
            if (!result.valid()) {
                return new AgenticAuthoringToolLoopResult(false, result.errorCode(), List.copyOf(trace));
            }
        }
        return new AgenticAuthoringToolLoopResult(true, "completed", List.copyOf(trace));
    }

    private AgenticAuthoringToolLoopStep localStep(
            int stepIndex,
            String phase,
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("toolSelected", false);
        diagnostics.put("localPhase", true);
        if ("loadActiveDecision".equals(phase)) {
            diagnostics.put("hasActiveDecision", request != null && request.activeSemanticDecision() != null);
        } else if ("proposeDecision".equals(phase)) {
            diagnostics.put("hasSemanticDecision", intentResolution != null && intentResolution.semanticDecision() != null);
        } else if ("materializePlan".equals(phase)) {
            diagnostics.put("previewPresent", preview != null);
        } else if ("validatePreview".equals(phase)) {
            diagnostics.put("previewValid", preview != null && preview.valid());
            diagnostics.put("failureCodeCount", preview == null || preview.failureCodes() == null
                    ? 0
                    : preview.failureCodes().size());
        }
        return new AgenticAuthoringToolLoopStep(stepIndex, phase, "", true, "", diagnostics);
    }

    private Map<String, Object> safeDiagnostics(AgenticAuthoringToolResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("valid", result.valid());
        if (result.safeDiagnostics() != null) {
            for (String key : List.of("candidateCount", "artifactKind", "retrievalSource", "failureCodeCount")) {
                if (result.safeDiagnostics().containsKey(key)) {
                    diagnostics.put(key, result.safeDiagnostics().get(key));
                }
            }
        }
        return diagnostics;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
