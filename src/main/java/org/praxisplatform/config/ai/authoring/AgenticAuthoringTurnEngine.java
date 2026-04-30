package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiPrincipalContext;

@RequiredArgsConstructor
@Slf4j
public class AgenticAuthoringTurnEngine {

    private static final int MAX_TOOL_CALLS_PER_TURN = 1;
    private static final int MAX_REPAIR_ATTEMPTS_PER_PHASE = 1;

    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPreviewService previewService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringToolRegistry toolRegistry;
    private final AgenticAuthoringTurnRouteClassifier routeClassifier = new AgenticAuthoringTurnRouteClassifier();

    AgenticAuthoringTurnOutcome execute(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink) {
        AgenticAuthoringTurnState state = initialState(request);
        try {
            eventSink.append("thought.step", Map.of(
                    "phase", "context.bundle",
                    "summary", "Authoring context received.",
                    "diagnostics", safeDiagnostics(request)));
            eventSink.append("thought.step", Map.of(
                    "phase", "intent.resolve",
                    "summary", "Resolving authoring intent."));
            AgenticAuthoringIntentResolutionResult intentResolution = intentResolverService.resolve(
                    toIntentRequest(request),
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (eventSink.terminalReached()) {
                return AgenticAuthoringTurnOutcome.noop(state);
            }
            AgenticAuthoringTurnRoute route = routeClassifier.classify(request, intentResolution, state);
            state = state.withRouteClass(route.routeClass());
            emitIntentResolutionProgress(eventSink, intentResolution);
            AgenticAuthoringToolResult resourceDiscoveryResult = maybeRunResourceDiscoveryTool(
                    request,
                    eventSink,
                    intentResolution,
                    route);
            if (resourceDiscoveryResult != null
                    && resourceDiscoveryResult.valid()
                    && resourceDiscoveryResult.payload() instanceof AgenticAuthoringResourceCandidatesResult discovery
                    && discovery.candidates() != null
                    && !discovery.candidates().isEmpty()
                    && !eventSink.terminalReached()) {
                eventSink.append("thought.step", safeToolProjection(
                        "intent.resolve",
                        "Resolving authoring intent with backend resource candidates.",
                        Map.of(
                                "tool", resourceDiscoveryResult.tool(),
                                "candidateCount", discovery.candidates().size())));
                intentResolution = intentResolverService.resolve(
                        toIntentRequest(withResourceDiscoveryContext(request, discovery)),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                route = routeClassifier.classify(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolutionProgress(eventSink, intentResolution);
            }
            AgenticAuthoringPreviewResult preview = null;
            if (route.allowsPreview() && intentResolution.valid()) {
                eventSink.append("thought.step", Map.of(
                        "phase", "preview.plan",
                        "summary", "Generating page preview plan."));
                preview = previewService.preview(
                        toPlanRequest(request, intentResolution),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                eventSink.append("thought.step", Map.of(
                        "phase", "preview.compile",
                        "summary", preview.valid() ? "Compiled preview payload." : "Preview requires backend repair classification.",
                        "diagnostics", safePreviewDiagnostics(intentResolution, preview, false)));
                preview = maybeRepairPreview(
                        request,
                        principalContext,
                        eventSink,
                        intentResolution,
                        preview);
            }
            AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("result", Map.of(
                    "intentResolution", intentResolution,
                    "preview", preview != null ? preview : objectMapper.createObjectNode(),
                    "assistantMessage", safeText(previewAssistantMessage(preview, intentResolution)),
                    "quickReplies", intentResolution.quickReplies() != null ? intentResolution.quickReplies() : List.of(),
                    "canApply", preview != null && preview.valid()));
            return terminalResult.appendedType("result")
                    ? AgenticAuthoringTurnOutcome.completed(state)
                    : AgenticAuthoringTurnOutcome.noop(state);
        } catch (Exception ex) {
            log.warn("[AgenticAuthoringTurnEngine] Stream processing failed: {}", ex.getMessage());
            AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("error", Map.of(
                    "message", ex.getMessage() != null ? ex.getMessage() : "Agentic authoring stream failed.",
                    "assistantMessage", "The assistant could not finish this authoring request. Try again or ask support to review the diagnostics.",
                    "code", "agentic-authoring-processing-failed",
                    "phase", "agentic-authoring"));
            return terminalResult.appendedType("error")
                    ? AgenticAuthoringTurnOutcome.expired(state)
                    : AgenticAuthoringTurnOutcome.noop(state);
        }
    }

    private AgenticAuthoringToolResult maybeRunResourceDiscoveryTool(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (!needsResourceDiscovery(intentResolution) || eventSink.terminalReached()) {
            return null;
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                route.routeClass(),
                new AgenticAuthoringResourceCandidatesRequest(
                        resourceDiscoveryQuery(intentResolution, request),
                        request.userPrompt(),
                        safeText(intentResolution.artifactKind()),
                        6));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Searching backend API resources.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall);
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid() ? "Backend API resource search completed." : "Backend API resource search failed.",
                safeToolDiagnostics(result)));
        return result;
    }

    private AgenticAuthoringPreviewResult maybeRepairPreview(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) throws Exception {
        String repairClassification = AgenticAuthoringRepairClassificationPolicy.classify(intentResolution, preview);
        if (!AgenticAuthoringRepairClassificationPolicy.RETRYABLE.equals(repairClassification)
                || eventSink.terminalReached()) {
            return preview;
        }
        eventSink.append("thought.step", safeToolProjection(
                "repair.attempt",
                "Retrying preview generation with backend repair context.",
                Map.of(
                        "phase", "preview",
                        "repairClassification", repairClassification,
                        "attempt", 1,
                        "maxAttempts", MAX_REPAIR_ATTEMPTS_PER_PHASE,
                        "failureCodeCount", preview.failureCodes() == null ? 0 : preview.failureCodes().size(),
                        "warningCount", preview.warnings() == null ? 0 : preview.warnings().size())));
        AgenticAuthoringPreviewResult repairedPreview = previewService.preview(
                toRepairPlanRequest(request, intentResolution, preview, repairClassification),
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
        eventSink.append("thought.step", Map.of(
                "phase", "preview.compile",
                "summary", repairedPreview.valid()
                        ? "Compiled preview payload after backend repair."
                        : "Preview repair attempt completed without a valid payload.",
                "diagnostics", safePreviewDiagnostics(intentResolution, repairedPreview, true)));
        return repairedPreview;
    }

    private boolean needsResourceDiscovery(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || hasToolDiscoveredCandidates(intentResolution)) {
            return false;
        }
        return contains(intentResolution.failureCodes(), "resource-candidate-required");
    }

    private String resourceDiscoveryQuery(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnStreamRequest request) {
        AgenticAuthoringQuickReply quickReply = firstSearchToolQuickReply(intentResolution);
        return quickReply != null
                ? safeText(quickReply.contextHints().path("retrievalQuery").asText(""))
                : safeText(request.userPrompt());
    }

    private AgenticAuthoringQuickReply firstSearchToolQuickReply(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.quickReplies() == null) {
            return null;
        }
        return intentResolution.quickReplies().stream()
                .filter(reply -> reply != null
                        && reply.contextHints() != null
                        && AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES.equals(reply.contextHints().path("tool").asText("")))
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringToolProgressProjection safeToolProjection(
            String phase,
            String label,
            Map<String, Object> diagnostics) {
        return new AgenticAuthoringToolProgressProjection(phase, label, diagnostics);
    }

    private Map<String, Object> safeToolDiagnostics(AgenticAuthoringToolResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("tool", result.tool());
        diagnostics.put("valid", result.valid());
        if (result.errorCode() != null && !result.errorCode().isBlank()) {
            diagnostics.put("errorCode", result.errorCode());
        }
        if (result.safeDiagnostics() != null) {
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "candidateCount");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "artifactKind");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalQuery");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalSource");
        }
        return diagnostics;
    }

    private void copySafeDiagnostic(
            Map<String, Object> source,
            Map<String, Object> target,
            String field) {
        if (source.containsKey(field)) {
            target.put(field, source.get(field));
        }
    }

    private AgenticAuthoringTurnStreamRequest withResourceDiscoveryContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceCandidatesResult discovery) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resourceDiscovery = contextHints.putObject("resourceDiscovery");
        resourceDiscovery.put("tool", AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
        resourceDiscovery.put("retrievalQuery", safeText(discovery.retrievalQuery()));
        resourceDiscovery.put("artifactKind", safeText(discovery.artifactKind()));
        ArrayNode candidates = resourceDiscovery.putArray("candidates");
        for (AgenticAuthoringCandidate candidate : discovery.candidates()) {
            candidates.add(candidateContext(candidate));
        }
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints,
                request.componentCapabilities());
    }

    private JsonNode candidateContext(AgenticAuthoringCandidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourcePath", safeText(candidate.resourcePath()));
        node.put("operation", safeText(candidate.operation()));
        node.put("schemaUrl", safeText(candidate.schemaUrl()));
        node.put("submitUrl", safeText(candidate.submitUrl()));
        node.put("submitMethod", safeText(candidate.submitMethod()));
        node.put("score", candidate.score());
        node.put("reason", safeText(candidate.reason()));
        ArrayNode evidence = node.putArray("evidence");
        if (candidate.evidence() != null) {
            candidate.evidence().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(evidence::add);
        }
        return node;
    }

    private AgenticAuthoringTurnState initialState(AgenticAuthoringTurnStreamRequest request) {
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(
                request.currentPage(),
                request.selectedWidgetKey());
        return new AgenticAuthoringTurnState("component_authoring", target);
    }

    private AgenticAuthoringIntentResolutionRequest toIntentRequest(AgenticAuthoringTurnStreamRequest request) {
        return new AgenticAuthoringIntentResolutionRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest toPlanRequest(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                intentResolution,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest toRepairPlanRequest(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            String repairClassification) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode repair = contextHints.putObject("repair");
        repair.put("phase", "preview");
        repair.put("classification", repairClassification);
        repair.put("attempt", 1);
        repair.put("maxAttempts", MAX_REPAIR_ATTEMPTS_PER_PHASE);
        repair.put("failureCodeCount", preview.failureCodes() == null ? 0 : preview.failureCodes().size());
        repair.put("warningCount", preview.warnings() == null ? 0 : preview.warnings().size());
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                intentResolution,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints);
    }

    private void emitIntentResolutionProgress(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return;
        }
        if (hasToolDiscoveredCandidates(intentResolution)) {
            eventSink.append("thought.step", Map.of(
                    "phase", "resource.discovery",
                    "summary", "Resource candidates were retrieved from the backend catalog.",
                    "diagnostics", resourceDiscoveryDiagnostics(intentResolution)));
        } else if (contains(intentResolution.failureCodes(), "resource-candidate-ambiguous")) {
            eventSink.append("thought.step", Map.of(
                    "phase", "resource.discovery",
                    "summary", "Resource candidates returned for user selection.",
                    "diagnostics", resourceDiscoveryDiagnostics(intentResolution)));
        }
        if (contains(intentResolution.warnings(), "llm-intent-resolution-second-pass-used")) {
            eventSink.append("thought.step", Map.of(
                    "phase", "intent.resolve",
                    "summary", "Refined resource candidates were reviewed by the LLM.",
                    "diagnostics", secondPassDiagnostics(intentResolution)));
        }
    }

    private Map<String, Object> resourceDiscoveryDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        diagnostics.put("operationKind", safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", safeText(intentResolution.artifactKind()));
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                selectedCandidate,
                intentResolution.candidates()));
        diagnostics.put("source", hasToolDiscoveredCandidates(intentResolution) ? "backend-resource-catalog" : "intent-resolution");
        return diagnostics;
    }

    private Map<String, Object> secondPassDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("secondPass", true);
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                selectedCandidate,
                intentResolution.candidates()));
        return diagnostics;
    }

    private Map<String, Object> safePreviewDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            boolean repairAttempted) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("valid", preview != null && preview.valid());
        diagnostics.put("repairClassification", AgenticAuthoringRepairClassificationPolicy.classify(
                intentResolution,
                preview));
        diagnostics.put("repairAttempted", repairAttempted);
        if (preview != null && preview.failureCodes() != null && !preview.failureCodes().isEmpty()) {
            diagnostics.put("failureCodeCount", preview.failureCodes().size());
        }
        if (preview != null && preview.warnings() != null && !preview.warnings().isEmpty()) {
            diagnostics.put("warningCount", preview.warnings().size());
        }
        return diagnostics;
    }

    private boolean hasToolDiscoveredCandidates(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        if (hasEvidence(intentResolution.selectedCandidate(), "tool-search-api-resources")) {
            return true;
        }
        return intentResolution.candidates() != null
                && intentResolution.candidates().stream()
                .anyMatch(candidate -> hasEvidence(candidate, "tool-search-api-resources"));
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null && contains(candidate.evidence(), evidence);
    }

    private boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private String previewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        return !message.isBlank() ? message : safeText(intentResolution.assistantMessage());
    }

    private Map<String, Object> safeDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return Map.of(
                "targetApp", nonBlank(request.targetApp(), ""),
                "targetComponentId", nonBlank(request.targetComponentId(), ""),
                "selectedWidgetKey", nonBlank(request.selectedWidgetKey(), ""),
                "hasContextHints", request.contextHints() != null && !request.contextHints().isNull(),
                "componentCapabilityCatalogs", request.componentCapabilities() != null
                        && request.componentCapabilities().catalogs() != null
                        ? request.componentCapabilities().catalogs().size()
                        : 0);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    record AgenticAuthoringTurnState(String routeClass, AgenticAuthoringTarget structuralTarget) {

        AgenticAuthoringTurnState withRouteClass(String routeClass) {
            return new AgenticAuthoringTurnState(routeClass, structuralTarget);
        }
    }

    record AgenticAuthoringTurnOutcome(Completion completion, AgenticAuthoringTurnState state) {

        static AgenticAuthoringTurnOutcome completed(AgenticAuthoringTurnState state) {
            return new AgenticAuthoringTurnOutcome(Completion.COMPLETE, state);
        }

        static AgenticAuthoringTurnOutcome expired(AgenticAuthoringTurnState state) {
            return new AgenticAuthoringTurnOutcome(Completion.EXPIRE, state);
        }

        static AgenticAuthoringTurnOutcome noop(AgenticAuthoringTurnState state) {
            return new AgenticAuthoringTurnOutcome(Completion.NONE, state);
        }
    }

    enum Completion {
        COMPLETE,
        EXPIRE,
        NONE
    }

    private record AgenticAuthoringTurnRoute(String routeClass, boolean allowsPreview) {
    }

    private static final class AgenticAuthoringTurnRouteClassifier {

        private AgenticAuthoringTurnRoute classify(
                AgenticAuthoringTurnStreamRequest request,
                AgenticAuthoringIntentResolutionResult intentResolution,
                AgenticAuthoringTurnState state) {
            if (requiresSharedRuleAuthoring(intentResolution)) {
                return new AgenticAuthoringTurnRoute(
                        hasComponentAuthoringSignal(request, intentResolution, state) ? "mixed" : "shared_rule_authoring",
                        false);
            }
            if (needsClarification(intentResolution)) {
                return new AgenticAuthoringTurnRoute("needs_clarification", false);
            }
            return new AgenticAuthoringTurnRoute("component_authoring", true);
        }

        private boolean requiresSharedRuleAuthoring(AgenticAuthoringIntentResolutionResult intentResolution) {
            if (intentResolution == null) {
                return false;
            }
            AgenticAuthoringGateResult gate = intentResolution.gate();
            return (gate != null
                    && "route_required".equals(gate.status())
                    && contains(gate.messages(), "shared-rule-authoring-required"))
                    || contains(intentResolution.failureCodes(), "shared-rule-authoring-required");
        }

        private boolean needsClarification(AgenticAuthoringIntentResolutionResult intentResolution) {
            if (intentResolution == null || intentResolution.gate() == null) {
                return false;
            }
            return "clarification_required".equals(intentResolution.gate().status());
        }

        private boolean hasComponentAuthoringSignal(
                AgenticAuthoringTurnStreamRequest request,
                AgenticAuthoringIntentResolutionResult intentResolution,
                AgenticAuthoringTurnState state) {
            if (state != null && state.structuralTarget() != null) {
                return true;
            }
            String artifactKind = safeLower(intentResolution.artifactKind());
            if ("form".equals(artifactKind)
                    || "table".equals(artifactKind)
                    || "dashboard".equals(artifactKind)
                    || "page".equals(artifactKind)
                    || "chart".equals(artifactKind)) {
                return true;
            }
            String prompt = safeLower(request.userPrompt());
            return prompt.contains("formulario")
                    || prompt.contains("formulário")
                    || prompt.contains("pagina")
                    || prompt.contains("página")
                    || prompt.contains("tabela")
                    || prompt.contains("dashboard")
                    || prompt.contains("painel")
                    || prompt.contains("campo")
                    || prompt.contains("widget");
        }

        private boolean contains(List<String> values, String expected) {
            return values != null && values.stream().anyMatch(expected::equals);
        }

        private String safeLower(String value) {
            return value == null ? "" : value.toLowerCase();
        }
    }
}
