package org.praxisplatform.config.ai.authoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.config.service.AiPrincipalContext;

public class AgenticAuthoringToolRegistry {

    static final String SEARCH_API_RESOURCES = "searchApiResources";

    private final Map<String, AgenticAuthoringToolExecutor> executors;

    public AgenticAuthoringToolRegistry(AgenticAuthoringResourceDiscoveryService resourceDiscoveryService) {
        Map<String, AgenticAuthoringToolExecutor> registered = new LinkedHashMap<>();
        register(registered, new SearchApiResourcesToolExecutor(resourceDiscoveryService));
        this.executors = Map.copyOf(registered);
    }

    List<AgenticAuthoringToolDefinition> definitions() {
        return executors.values().stream()
                .map(AgenticAuthoringToolExecutor::definition)
                .toList();
    }

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
        return execute(call, null);
    }

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
        String toolName = call == null ? "" : call.name();
        return AgenticAuthoringToolResult.failure(
                toolName,
                "tool-phase-required",
                "Tool execution requires an explicit authoring phase.");
    }

    AgenticAuthoringToolResult execute(
            AgenticAuthoringToolCall call,
            AiPrincipalContext principalContext,
            String phase) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return AgenticAuthoringToolResult.failure("", "tool-name-required", "Tool name is required.");
        }
        if (phase == null || phase.isBlank()) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-phase-required",
                    "Tool execution requires an explicit authoring phase.");
        }
        AgenticAuthoringToolExecutor executor = executors.get(call.name());
        if (executor == null) {
            return AgenticAuthoringToolResult.failure(call.name(), "tool-not-found", "Tool is not registered.");
        }
        AgenticAuthoringToolDefinition definition = executor.definition();
        if (!definition.allowedRoutes().contains(call.routeClass())) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-route-not-allowed",
                    "Tool is not allowed for route " + safeRoute(call.routeClass()) + ".");
        }
        if (definition.allowedPhases() != null
                && !definition.allowedPhases().isEmpty()
                && !definition.allowedPhases().contains(phase)) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-phase-not-allowed",
                    "Tool is not allowed for phase " + phase + ".");
        }
        try {
            return executor.execute(call, principalContext);
        } catch (Exception ex) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-execution-failed",
                    ex.getMessage() != null ? ex.getMessage() : "Tool execution failed.");
        }
    }

    private void register(
            Map<String, AgenticAuthoringToolExecutor> target,
            AgenticAuthoringToolExecutor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        target.put(executor.definition().name(), executor);
    }

    private static String safeRoute(String routeClass) {
        return routeClass == null || routeClass.isBlank() ? "unknown" : routeClass;
    }

    private static final class SearchApiResourcesToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                SEARCH_API_RESOURCES,
                Set.of("component_authoring", "shared_rule_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                "praxis-config-starter:/api/praxis/config/ai/authoring/resource-candidates",
                "read_only",
                "safe_grounding",
                "safe_event_projection_only");

        private final AgenticAuthoringResourceDiscoveryService resourceDiscoveryService;

        private SearchApiResourcesToolExecutor(AgenticAuthoringResourceDiscoveryService resourceDiscoveryService) {
            this.resourceDiscoveryService = Objects.requireNonNull(
                    resourceDiscoveryService,
                    "resourceDiscoveryService must not be null");
        }

        @Override
        public AgenticAuthoringToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
            return execute(call, null);
        }

        @Override
        public AgenticAuthoringToolResult execute(
                AgenticAuthoringToolCall call,
                AiPrincipalContext principalContext) {
            if (!(call.payload() instanceof AgenticAuthoringResourceCandidatesRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "searchApiResources requires AgenticAuthoringResourceCandidatesRequest payload.");
            }
            AgenticAuthoringResourceCandidatesResult result = resourceDiscoveryService.search(request, principalContext);
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    result,
                    Map.of(
                            "candidateCount", result.candidates() != null ? result.candidates().size() : 0,
                            "artifactKind", result.artifactKind() != null ? result.artifactKind() : "",
                            "retrievalQuery", result.retrievalQuery() != null ? result.retrievalQuery() : "",
                            "retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(result.candidates())));
        }
    }
}
