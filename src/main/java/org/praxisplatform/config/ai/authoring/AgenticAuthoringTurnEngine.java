package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.springframework.util.StringUtils;

@Slf4j
public class AgenticAuthoringTurnEngine {

    private static final int MAX_TOOL_CALLS_PER_TURN = 1;
    private static final int MAX_REPAIR_ATTEMPTS_PER_PHASE = 1;

    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPreviewService previewService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringToolRegistry toolRegistry;
    private final AgenticAuthoringProjectKnowledgeService projectKnowledgeService;
    private final AgenticAuthoringApiCatalogConversationService apiCatalogConversationService;
    private final AgenticAuthoringOrchestrator orchestrator;
    private final AgenticAuthoringTurnRouteClassifier routeClassifier = new AgenticAuthoringTurnRouteClassifier();

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry) {
        this(intentResolverService, previewService, objectMapper, currentPageAnalyzer, toolRegistry, null, null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService) {
        this(intentResolverService, previewService, objectMapper, currentPageAnalyzer, toolRegistry, projectKnowledgeService, null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                apiCatalogConversationService,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService,
            AgenticAuthoringOrchestrator orchestrator) {
        this.intentResolverService = intentResolverService;
        this.previewService = previewService;
        this.objectMapper = objectMapper;
        this.currentPageAnalyzer = currentPageAnalyzer;
        this.toolRegistry = toolRegistry;
        this.projectKnowledgeService = projectKnowledgeService;
        this.apiCatalogConversationService = apiCatalogConversationService;
        this.orchestrator = orchestrator;
    }

    AgenticAuthoringTurnOutcome execute(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink) {
        return execute(request, principalContext, eventSink, null);
    }

    AgenticAuthoringTurnOutcome execute(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            String schemaBaseUrl) {
        AgenticAuthoringTurnState state = initialState(request);
        request = withActiveDecisionContext(request, state.activeSemanticDecision());
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
                    principalContext,
                    eventSink,
                    intentResolution,
                    route);
            AgenticAuthoringResourceCandidatesResult resourceDiscovery =
                    resourceDiscoveryPayload(resourceDiscoveryResult);
            if (resourceDiscoveryResult != null
                    && resourceDiscoveryResult.valid()
                    && resourceDiscovery != null
                    && resourceDiscovery.candidates() != null
                    && !resourceDiscovery.candidates().isEmpty()
                    && !eventSink.terminalReached()) {
                eventSink.append("thought.step", safeToolProjection(
                        "intent.resolve",
                        "Resolving authoring intent with backend resource candidates.",
                        Map.of(
                                "tool", resourceDiscoveryResult.tool(),
                                "candidateCount", resourceDiscovery.candidates().size())));
                intentResolution = intentResolverService.resolve(
                        toIntentRequest(withResourceDiscoveryContext(request, resourceDiscovery)),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                route = routeClassifier.classify(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolutionProgress(eventSink, intentResolution);
            }
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery =
                    maybeRunBusinessCatalogResourceDiscoveryTool(
                            request,
                            principalContext,
                            eventSink,
                            intentResolution,
                            route,
                            resourceDiscovery);
            AgenticAuthoringPreviewResult preview = null;
            AgenticAuthoringToolLoopResult toolLoopResult = null;
            if (route.allowsPreview() && intentResolution.valid()) {
                AgenticAuthoringTurnStreamRequest previewRequest = withProjectKnowledgeContext(
                        request,
                        principalContext,
                        eventSink,
                        intentResolution);
                eventSink.append("thought.step", Map.of(
                        "phase", "preview.plan",
                        "summary", "Generating page preview plan."));
                AgenticAuthoringPlanRequest planRequest = toPlanRequest(previewRequest, intentResolution);
                preview = StringUtils.hasText(schemaBaseUrl)
                        ? previewService.preview(
                                planRequest,
                                principalContext.tenantId(),
                                principalContext.userId(),
                                principalContext.environment(),
                                schemaBaseUrl)
                        : previewService.preview(
                                planRequest,
                                principalContext.tenantId(),
                                principalContext.userId(),
                                principalContext.environment());
                eventSink.append("thought.step", Map.of(
                        "phase", "preview.compile",
                        "summary", preview.valid() ? "Compiled preview payload." : "Preview requires backend repair classification.",
                        "diagnostics", safePreviewDiagnostics(intentResolution, preview, false)));
                preview = maybeRepairPreview(
                        previewRequest,
                        principalContext,
                        eventSink,
                        intentResolution,
                        preview,
                        schemaBaseUrl);
                toolLoopResult = runGovernedToolLoop(
                        previewRequest,
                        principalContext,
                        eventSink,
                        intentResolution,
                        preview,
                        route);
            }
            String assistantMessage = advisoryCatalogAssistantMessage(request, route, intentResolution);
            if (!StringUtils.hasText(assistantMessage)) {
                assistantMessage = previewAssistantMessage(preview, intentResolution);
            }
            Map<String, Object> decisionDiagnostics = decisionDiagnostics(intentResolution, preview, toolLoopResult);
            if (Boolean.TRUE.equals(decisionDiagnostics.get("semanticDecisionReviewGroundedByPreview"))) {
                assistantMessage = groundedPreviewAssistantMessage(preview, intentResolution);
            }
            AgenticAuthoringIntentResolutionResult terminalIntentResolution =
                    terminalIntentResolution(intentResolution, decisionDiagnostics);
            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("intentResolution", terminalIntentResolution);
            resultPayload.put("preview", preview != null ? preview : objectMapper.createObjectNode());
            resultPayload.put("assistantMessage", safeText(assistantMessage));
            resultPayload.put("quickReplies", terminalQuickReplies(intentResolution, businessCatalogDiscovery));
            resultPayload.put("canApply", preview != null
                    && preview.valid()
                    && !requiresDecisionReview(decisionDiagnostics)
                    && (toolLoopResult == null || toolLoopResult.completed()));
            resultPayload.put("decisionDiagnostics", decisionDiagnostics);
            if (toolLoopResult != null) {
                resultPayload.put("toolLoopTrace", safeToolLoopTrace(toolLoopResult));
            }
            AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("result", resultPayload);
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
            AiPrincipalContext principalContext,
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
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid() ? "Backend API resource search completed." : "Backend API resource search failed.",
                safeToolDiagnostics(result)));
        return result;
    }

    private AgenticAuthoringResourceCandidatesResult maybeRunBusinessCatalogResourceDiscoveryTool(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringResourceCandidatesResult existingDiscovery) {
        if (eventSink.terminalReached()
                || route == null
                || route.allowsPreview()
                || !isBusinessAnalyticsCatalogQuestion(request.userPrompt())) {
            return null;
        }
        if (existingDiscovery != null
                && existingDiscovery.quickReplies() != null
                && !existingDiscovery.quickReplies().isEmpty()) {
            return existingDiscovery;
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                route.routeClass(),
                new AgenticAuthoringResourceCandidatesRequest(
                        businessCatalogResourceDiscoveryQuery(request, intentResolution),
                        request.userPrompt(),
                        safeText(intentResolution == null ? "" : intentResolution.artifactKind()),
                        6));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Searching business data resources for advisory cards.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "Business data resource search completed."
                        : "Business data resource search failed.",
                safeToolDiagnostics(result)));
        return resourceDiscoveryPayload(result);
    }

    private String businessCatalogResourceDiscoveryQuery(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        StringBuilder query = new StringBuilder(safeText(request.userPrompt()));
        query.append(" analytics dashboard indicadores graficos kpis metricas tendencias agregacoes");
        if (intentResolution != null && intentResolution.candidates() != null) {
            intentResolution.candidates().stream()
                    .map(AgenticAuthoringCandidate::resourcePath)
                    .filter(path -> path != null && !path.isBlank())
                    .forEach(path -> query.append(' ').append(path.replace('/', ' ').replace('-', ' ')));
        }
        return query.toString().trim();
    }

    private AgenticAuthoringToolLoopResult runGovernedToolLoop(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringTurnRoute route) {
        if (orchestrator == null || eventSink.terminalReached()) {
            return null;
        }
        AgenticAuthoringToolLoopResult result = orchestrator.runToolLoop(
                request,
                principalContext,
                intentResolution,
                preview,
                route == null ? "" : route.routeClass());
        eventSink.append("thought.step", safeToolProjection(
                "tool.loop",
                result.completed()
                        ? "Governed authoring tool loop completed."
                        : "Governed authoring tool loop stopped before completion.",
                safeToolLoopDiagnostics(result)));
        return result;
    }

    private Map<String, Object> safeToolLoopDiagnostics(AgenticAuthoringToolLoopResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("completed", result != null && result.completed());
        diagnostics.put("terminalReason", result == null ? "" : safeText(result.terminalReason()));
        diagnostics.put("stepCount", result == null || result.trace() == null ? 0 : result.trace().size());
        diagnostics.put("toolCallCount", result == null || result.trace() == null
                ? 0
                : result.trace().stream().filter(step -> step != null && StringUtils.hasText(step.tool())).count());
        return diagnostics;
    }

    private List<Map<String, Object>> safeToolLoopTrace(AgenticAuthoringToolLoopResult result) {
        if (result == null || result.trace() == null) {
            return List.of();
        }
        return result.trace().stream()
                .map(step -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stepIndex", step.stepIndex());
                    item.put("phase", safeText(step.phase()));
                    item.put("tool", safeText(step.tool()));
                    item.put("valid", step.valid());
                    if (StringUtils.hasText(step.errorCode())) {
                        item.put("errorCode", step.errorCode());
                    }
                    item.put("diagnostics", step.safeDiagnostics() == null ? Map.of() : step.safeDiagnostics());
                    return item;
                })
                .toList();
    }

    private AgenticAuthoringResourceCandidatesResult resourceDiscoveryPayload(AgenticAuthoringToolResult result) {
        if (result == null || !result.valid()
                || !(result.payload() instanceof AgenticAuthoringResourceCandidatesResult discovery)) {
            return null;
        }
        return discovery;
    }

    private List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        if (businessCatalogDiscovery != null
                && businessCatalogDiscovery.quickReplies() != null
                && !businessCatalogDiscovery.quickReplies().isEmpty()) {
            return businessCatalogDiscovery.quickReplies();
        }
        return intentResolution != null && intentResolution.quickReplies() != null
                ? intentResolution.quickReplies()
                : List.of();
    }

    private AgenticAuthoringTurnStreamRequest withProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (projectKnowledgeService == null || eventSink.terminalReached()) {
            return request;
        }
        AgenticAuthoringProjectKnowledgeQuery query = projectKnowledgeQuery(request, principalContext, intentResolution);
        if (!hasProjectKnowledgeScope(query)) {
            return request;
        }
        List<AgenticAuthoringProjectKnowledgeProjection> projections = projectKnowledgeService.retrieve(query);
        if (projections.isEmpty()) {
            return request;
        }
        eventSink.append("thought.step", safeToolProjection(
                "projectKnowledge.retrieve",
                "Retrieved governed project knowledge for preview planning.",
                projectKnowledgeDiagnostics(projections)));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
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
                request.componentCapabilities(),
                request.activeSemanticDecision());
    }

    private AgenticAuthoringProjectKnowledgeQuery projectKnowledgeQuery(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return new AgenticAuthoringProjectKnowledgeQuery(
                principalContext.tenantId(),
                principalContext.environment(),
                firstText(domainCatalogHint(request, "contextKey"), contextKeyFromCandidate(intentResolution)),
                firstText(domainCatalogHint(request, "resourceKey"), resourceKeyFromCandidate(intentResolution)),
                List.of(
                        "project_preference",
                        "domain_decision_hint",
                        "component_authoring_pattern",
                        "resource_selection_rationale",
                        "governance_constraint",
                        "integration_note"),
                null,
                6);
    }

    private boolean hasProjectKnowledgeScope(AgenticAuthoringProjectKnowledgeQuery query) {
        return query != null
                && (StringUtils.hasText(query.contextKey()) || StringUtils.hasText(query.resourceKey()));
    }

    private ObjectNode projectKnowledgeContext(List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        context.put("source", "domain_knowledge_concept");
        context.put("influenceCount", projections.size());
        context.put("usageRule", "Use these safe projections as governed project context; never expose raw source data or treat them as executable rules.");
        ArrayNode entries = context.putArray("entries");
        for (AgenticAuthoringProjectKnowledgeProjection projection : projections) {
            ObjectNode entry = entries.addObject();
            entry.put("knowledgeId", safeText(projection.knowledgeId()));
            entry.put("conceptKey", safeText(projection.conceptKey()));
            entry.put("kind", safeText(projection.kind()));
            entry.set("scope", objectMapper.valueToTree(projection.scope()));
            entry.set("status", objectMapper.valueToTree(projection.status()));
            entry.put("visibility", safeText(projection.visibility()));
            entry.put("sourceSummary", safeText(projection.sourceSummary()));
            entry.put("influence", safeText(projection.influence()));
            entry.put("summary", safeText(projection.summary()));
            entry.set("evidence", objectMapper.valueToTree(projection.evidence() == null ? List.of() : projection.evidence()));
        }
        return context;
    }

    private Map<String, Object> projectKnowledgeDiagnostics(
            List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("source", "domain_knowledge_concept");
        diagnostics.put("influenceCount", projections.size());
        diagnostics.put("kinds", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::kind)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        diagnostics.put("visibilities", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::visibility)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        diagnostics.put("conceptKeys", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::conceptKey)
                .filter(StringUtils::hasText)
                .limit(6)
                .toList());
        diagnostics.put("sourceSummaries", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::sourceSummary)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .toList());
        diagnostics.put("influences", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::influence)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        return diagnostics;
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        return decisionDiagnostics(intentResolution, preview, null);
    }

    private AgenticAuthoringIntentResolutionResult terminalIntentResolution(
            AgenticAuthoringIntentResolutionResult intentResolution,
            Map<String, Object> decisionDiagnostics) {
        if (intentResolution == null || intentResolution.semanticDecision() == null) {
            return intentResolution;
        }
        if (!Boolean.TRUE.equals(decisionDiagnostics.get("semanticDecisionReviewGroundedByPreview"))) {
            return intentResolution;
        }
        AgenticAuthoringSemanticDecision groundedDecision =
                intentResolution.semanticDecision().withReviewResolvedByPreviewGrounding();
        return new AgenticAuthoringIntentResolutionResult(
                intentResolution.valid(),
                intentResolution.operationKind(),
                intentResolution.artifactKind(),
                intentResolution.changeKind(),
                intentResolution.authoringProfile(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                intentResolution.target(),
                intentResolution.selectedCandidate(),
                intentResolution.candidates(),
                intentResolution.gate(),
                intentResolution.effectivePrompt(),
                intentResolution.assistantMessage(),
                intentResolution.apiCatalogAnswer(),
                intentResolution.quickReplies(),
                intentResolution.pendingClarification(),
                intentResolution.clarificationQuestions(),
                intentResolution.warnings(),
                intentResolution.failureCodes(),
                intentResolution.currentPageSummary(),
                intentResolution.llmDiagnostics(),
                intentResolution.visualizationDecision(),
                groundedDecision);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-decision-diagnostics.v1");
        if (intentResolution == null) {
            diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.NONE);
            diagnostics.put("llmResolutionAttempted", false);
            diagnostics.put("llmResolved", false);
            diagnostics.put("keywordFallbackApplied", false);
            diagnostics.put("semanticPolicyApplied", false);
            diagnostics.put("selectedCandidateUsesLexicalFallback", false);
            diagnostics.put("selectedCandidateUsesDomainAnchor", false);
            diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
            diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
            diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
            diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
            diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(preview, false));
            putToolLoopDiagnostics(diagnostics, toolLoopResult);
            putSemanticAxisDiagnostics(diagnostics, preview);
            diagnostics.put("requiresReview", requiresDecisionReview(diagnostics));
            return diagnostics;
        }
        diagnostics.put("operationKind", safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", safeText(intentResolution.artifactKind()));
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        if (semanticDecision != null) {
            boolean semanticDecisionReviewGroundedByPreview =
                    semanticDecisionReviewGroundedByPreview(semanticDecision, preview);
            diagnostics.put("semanticDecisionSchemaVersion", safeText(semanticDecision.schemaVersion()));
            diagnostics.put("semanticDecisionId", safeText(semanticDecision.decisionId()));
            diagnostics.put("semanticDecisionReviewRequired",
                    semanticDecision.reviewRequired() && !semanticDecisionReviewGroundedByPreview);
            diagnostics.put("semanticDecisionReviewReason", safeText(semanticDecision.reviewReason()));
            diagnostics.put("semanticDecisionReviewGroundedByPreview", semanticDecisionReviewGroundedByPreview);
            diagnostics.put("semanticDecisionRefinementOf", safeText(semanticDecision.refinementOf()));
            diagnostics.put("semanticDecisionPreviousDecisionId", safeText(semanticDecision.previousDecisionId()));
            diagnostics.put("semanticDecisionVisualIntent", safeText(semanticDecision.visualIntent()));
        }
        diagnostics.put("valid", intentResolution.valid());
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                intentResolution.selectedCandidate(),
                intentResolution.candidates()));
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && StringUtils.hasText(selectedCandidate.resourcePath())) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        JsonNode telemetry = intentResolution.llmDiagnostics() == null
                ? objectMapper.missingNode()
                : intentResolution.llmDiagnostics().path("resolutionTelemetry");
        diagnostics.put("llmResolutionAttempted", telemetry.path("llmResolutionAttempted").asBoolean(false));
        diagnostics.put("llmResolved", telemetry.path("llmResolved").asBoolean(false));
        diagnostics.put("fallbackPolicy", safeText(telemetry.path("fallbackPolicy").asText("")));
        boolean keywordFallbackApplied = telemetry.path("keywordFallbackApplied").asBoolean(false)
                && !Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"));
        diagnostics.put("keywordFallbackApplied", keywordFallbackApplied);
        diagnostics.put("semanticPolicyApplied", telemetry.path("semanticPolicyApplied").asBoolean(false));
        boolean selectedCandidateUsesLexicalFallback =
                telemetry.path("selectedCandidateUsesLexicalFallback").asBoolean(false)
                        && !previewResourceSchemaVerified(preview);
        diagnostics.put("selectedCandidateUsesLexicalFallback", selectedCandidateUsesLexicalFallback);
        diagnostics.put("selectedCandidateUsesDomainAnchor",
                telemetry.path("selectedCandidateUsesDomainAnchor").asBoolean(false));
        diagnostics.put("candidateSetContainsLexicalFallback",
                telemetry.path("candidateSetContainsLexicalFallback").asBoolean(false));
        diagnostics.put("candidateSetContainsDomainAnchor",
                telemetry.path("candidateSetContainsDomainAnchor").asBoolean(false));
        diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
        diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
        diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
        diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
        diagnostics.put("previewResourceSchemaVerified", previewResourceSchemaVerified(preview));
        diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(
                preview,
                Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"))));
        putToolLoopDiagnostics(diagnostics, toolLoopResult);
        putSemanticAxisDiagnostics(diagnostics, preview);
        diagnostics.put("requiresReview", requiresDecisionReview(diagnostics));
        String reviewReason = decisionReviewReason(diagnostics);
        if (!reviewReason.isBlank()) {
            diagnostics.put("reviewReason", reviewReason);
        }
        return diagnostics;
    }

    private void putToolLoopDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringToolLoopResult toolLoopResult) {
        if (toolLoopResult == null) {
            diagnostics.put("toolLoopCompleted", true);
            diagnostics.put("toolLoopTerminalReason", "");
            return;
        }
        diagnostics.put("toolLoopCompleted", toolLoopResult.completed());
        diagnostics.put("toolLoopTerminalReason", safeText(toolLoopResult.terminalReason()));
        diagnostics.put("toolLoopStepCount", toolLoopResult.trace() == null ? 0 : toolLoopResult.trace().size());
    }

    private boolean uiCompositionPlanUsesReferenceProvider(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().stream()
                .anyMatch(warning -> warning != null
                        && (warning.startsWith("ui-composition-plan-provider:quickstart-")
                        || warning.startsWith("ui-composition-plan-provider:selected-resource-")
                        || warning.startsWith("ui-composition-plan-provider:local-editorial-")));
    }

    private boolean uiCompositionPlanUsesHardcodedAnchor(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().stream()
                .anyMatch(warning -> warning != null
                        && warning.startsWith("ui-composition-plan-provider:quickstart-"));
    }

    private boolean uiCompositionPlanHasUnverifiedSemanticAxes(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().contains("semantic-axis-schema-verification-pending");
    }

    private boolean previewResourceSchemaVerified(AgenticAuthoringPreviewResult preview) {
        JsonNode grounding = preview == null || preview.uiCompositionPlan() == null
                ? objectMapper.missingNode()
                : preview.uiCompositionPlan().path("diagnostics").path("resourceSchemaGrounding");
        return grounding.path("verified").asBoolean(false)
                && "schemas.filtered".equals(grounding.path("source").asText(""));
    }

    private boolean semanticDecisionReviewGroundedByPreview(
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringPreviewResult preview) {
        if (semanticDecision == null || !semanticDecision.reviewRequired() || !previewResourceSchemaVerified(preview)) {
            return false;
        }
        String reason = safeText(semanticDecision.reviewReason());
        if ("weak-lexical-evidence".equals(reason)) {
            return true;
        }
        return "keyword-fallback-fail-safe".equals(reason)
                && semanticDecision.refinement() != null
                && semanticDecision.refinement().preservesResource()
                && ("current-page-bound-resource".equals(safeText(semanticDecision.previousDecisionRef()))
                        || !safeText(semanticDecision.refinementOf()).isBlank()
                        || !safeText(semanticDecision.previousDecisionId()).isBlank());
    }

    private void putSemanticAxisDiagnostics(Map<String, Object> diagnostics, AgenticAuthoringPreviewResult preview) {
        JsonNode axes = preview == null || preview.uiCompositionPlan() == null
                ? objectMapper.missingNode()
                : preview.uiCompositionPlan().path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            diagnostics.put("semanticAxisCount", 0);
            diagnostics.put("semanticAxisVerifiedCount", 0);
            diagnostics.put("semanticAxisPendingCount", 0);
            diagnostics.put("semanticAxesSchemaVerified", false);
            return;
        }
        int total = 0;
        int verified = 0;
        List<Map<String, Object>> axisSummaries = new ArrayList<>();
        for (JsonNode axis : axes) {
            total++;
            boolean schemaVerified = axis.path("schemaVerified").asBoolean(false);
            if (schemaVerified) {
                verified++;
            }
            Map<String, Object> axisSummary = new LinkedHashMap<>();
            axisSummary.put("concept", safeText(axis.path("concept").asText("")));
            axisSummary.put("field", safeText(axis.path("field").asText("")));
            axisSummary.put("label", safeText(axis.path("label").asText("")));
            axisSummary.put("schemaVerified", schemaVerified);
            axisSummary.put("schemaProbeStatus", safeText(axis.path("schemaProbeStatus").asText("")));
            axisSummary.put("provenance", safeText(axis.path("provenance").asText("")));
            axisSummaries.add(axisSummary);
        }
        diagnostics.put("semanticAxisCount", total);
        diagnostics.put("semanticAxisVerifiedCount", verified);
        diagnostics.put("semanticAxisPendingCount", Math.max(0, total - verified));
        diagnostics.put("semanticAxesSchemaVerified", total > 0 && total == verified);
        diagnostics.put("semanticAxes", axisSummaries);
    }

    private boolean requiresDecisionReview(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostics.get("keywordFallbackApplied"))
                || Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewRequired"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesLexicalFallback"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))
                || Boolean.FALSE.equals(diagnostics.get("decisionValid"))
                || Boolean.FALSE.equals(diagnostics.get("toolLoopCompleted"))
                || Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanUsesHardcodedAnchor"))
                || Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanHasUnverifiedSemanticAxes"));
    }

    private String decisionReviewReason(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        if (Boolean.TRUE.equals(diagnostics.get("keywordFallbackApplied"))) {
            return "keyword-fallback-fail-safe";
        }
        if (Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewRequired"))) {
            String reason = safeText((String) diagnostics.get("semanticDecisionReviewReason"));
            return reason.isBlank() ? "semantic-decision-review-required" : reason;
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesLexicalFallback"))) {
            return "weak-lexical-evidence";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))) {
            return "resource-selection-domain-anchor";
        }
        if (Boolean.FALSE.equals(diagnostics.get("decisionValid"))) {
            return "semantic-preview-materialization-mismatch";
        }
        if (Boolean.FALSE.equals(diagnostics.get("toolLoopCompleted"))) {
            String reason = safeText((String) diagnostics.get("toolLoopTerminalReason"));
            return reason.isBlank() ? "agentic-tool-loop-incomplete" : "agentic-tool-loop-" + reason;
        }
        if (Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanUsesHardcodedAnchor"))) {
            return "ui-composition-hardcoded-reference-provider";
        }
        if (Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanHasUnverifiedSemanticAxes"))) {
            return "ui-composition-semantic-axis-schema-verification-pending";
        }
        return "";
    }

    private boolean previewHasSemanticMaterializationFailures(
            AgenticAuthoringPreviewResult preview,
            boolean semanticDecisionReviewGroundedByPreview) {
        return preview != null
                && preview.failureCodes() != null
                && preview.failureCodes().stream()
                .filter(Objects::nonNull)
                .anyMatch(code -> code.startsWith("semantic-preview-")
                        || (code.startsWith("semantic-decision-review-required")
                                && !semanticDecisionReviewGroundedByPreview));
    }

    private AgenticAuthoringPreviewResult maybeRepairPreview(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            String schemaBaseUrl) throws Exception {
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
        AgenticAuthoringPlanRequest repairRequest =
                toRepairPlanRequest(request, intentResolution, preview, repairClassification);
        AgenticAuthoringPreviewResult repairedPreview = StringUtils.hasText(schemaBaseUrl)
                ? previewService.preview(
                        repairRequest,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment(),
                        schemaBaseUrl)
                : previewService.preview(
                        repairRequest,
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
                request.componentCapabilities(),
                request.activeSemanticDecision());
    }

    private String domainCatalogHint(AgenticAuthoringTurnStreamRequest request, String fieldName) {
        JsonNode domainCatalog = request.contextHints() == null
                ? null
                : request.contextHints().path("domainCatalog");
        if (domainCatalog == null || domainCatalog.isMissingNode() || !domainCatalog.hasNonNull(fieldName)) {
            return null;
        }
        String value = domainCatalog.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String contextKeyFromCandidate(AgenticAuthoringIntentResolutionResult intentResolution) {
        String resourceKey = resourceKeyFromCandidate(intentResolution);
        if (!StringUtils.hasText(resourceKey)) {
            return null;
        }
        int firstDot = resourceKey.indexOf('.');
        return firstDot > 0 ? resourceKey.substring(0, firstDot) : null;
    }

    private String resourceKeyFromCandidate(AgenticAuthoringIntentResolutionResult intentResolution) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null || !StringUtils.hasText(candidate.resourcePath())) {
            return null;
        }
        String path = candidate.resourcePath().trim();
        if (path.startsWith("/api/")) {
            path = path.substring(5);
        } else if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.replace('/', '.');
        return StringUtils.hasText(path) ? path : null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
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
        return new AgenticAuthoringTurnState("component_authoring", target, request.activeSemanticDecision());
    }

    private AgenticAuthoringTurnStreamRequest withActiveDecisionContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision) {
        if (activeDecision == null) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("activeSemanticDecision", objectMapper.valueToTree(activeDecision));
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
                request.componentCapabilities(),
                activeDecision);
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
                request.contextHints(),
                request.activeSemanticDecision());
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

    private String groundedPreviewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String artifactKind = intentResolution == null ? "" : safeText(intentResolution.artifactKind());
        if ("dashboard".equals(artifactKind)) {
            return "Criei uma pre-visualizacao de dashboard analitico com recurso reancorado por /schemas/filtered.";
        }
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        return !message.isBlank() && !message.contains("revisao de governanca")
                ? message
                : "Criei uma pre-visualizacao com recurso reancorado por /schemas/filtered.";
    }

    private String advisoryCatalogAssistantMessage(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (apiCatalogConversationService == null
                || route == null
                || route.allowsPreview()
                || !isBusinessAnalyticsCatalogQuestion(request.userPrompt())) {
            return "";
        }
        AgenticAuthoringApiCatalogConversationService.ApiCatalogConversationAnswer answer =
                apiCatalogConversationService.answer(
                        request.userPrompt(),
                        intentResolution == null ? null : intentResolution.selectedCandidate(),
                        intentResolution == null || intentResolution.candidates() == null
                                ? List.of()
                                : intentResolution.candidates());
        return answer == null ? "" : safeText(answer.assistantMessage());
    }

    private boolean isBusinessAnalyticsCatalogQuestion(String prompt) {
        String normalized = normalizeText(prompt);
        boolean asksForDiscovery = containsAnyText(normalized,
                "catalogo", "catalog", "recursos", "recurso", "areas", "area", "dados", "disponiveis", "disponibilidade",
                "quais", "descobrir", "descoberta", "opcoes", "fonte", "fontes");
        boolean asksForAnalytics = containsAnyText(normalized,
                "grafico", "graficos", "chart", "dashboard", "painel", "indicador", "indicadores", "kpi", "metricas", "analitica", "analitico");
        boolean businessLanguage = containsAnyText(normalized,
                "negocio", "gestor", "pessoas", "folha", "operacoes", "ativos", "compras", "riscos", "sem endpoints", "linguagem simples");
        boolean postponesAuthoring = containsAnyText(normalized,
                "ainda nao quero criar", "nao quero criar", "antes de escolher", "antes de criar", "primeiro descobrir");
        return asksForDiscovery && asksForAnalytics && (businessLanguage || postponesAuthoring);
    }

    private boolean containsAnyText(String value, String... tokens) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
    }

    private Map<String, Object> safeDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return Map.of(
                "targetApp", nonBlank(request.targetApp(), ""),
                "targetComponentId", nonBlank(request.targetComponentId(), ""),
                "selectedWidgetKey", nonBlank(request.selectedWidgetKey(), ""),
                "hasContextHints", request.contextHints() != null && !request.contextHints().isNull(),
                "hasActiveSemanticDecision", request.activeSemanticDecision() != null,
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

    record AgenticAuthoringTurnState(
            String routeClass,
            AgenticAuthoringTarget structuralTarget,
            AgenticAuthoringSemanticDecision activeSemanticDecision) {

        AgenticAuthoringTurnState withRouteClass(String routeClass) {
            return new AgenticAuthoringTurnState(routeClass, structuralTarget, activeSemanticDecision);
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
            if (!allowsMaterializedPreview(intentResolution)) {
                return new AgenticAuthoringTurnRoute("advisory_authoring", false);
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

        private boolean allowsMaterializedPreview(AgenticAuthoringIntentResolutionResult intentResolution) {
            if (intentResolution == null) {
                return false;
            }
            String operationKind = safeLower(intentResolution.operationKind());
            return "create".equals(operationKind)
                    || "modify".equals(operationKind)
                    || "remove".equals(operationKind);
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
