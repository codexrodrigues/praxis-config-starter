package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiPrincipalContext;

@RequiredArgsConstructor
@Slf4j
public class AgenticAuthoringTurnEngine {

    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPreviewService previewService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;

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
            emitIntentResolutionProgress(eventSink, intentResolution);
            AgenticAuthoringPreviewResult preview = null;
            if (intentResolution.valid()) {
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
                        "summary", "Compiled preview payload."));
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
}
