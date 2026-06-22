package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
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
    private final AgenticAuthoringOrchestrator orchestrator;
    private final SchemaRetrievalService schemaRetrievalService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final AgenticAuthoringConsultativeAnswerService consultativeAnswerService;
    private final AgenticAuthoringPreIntentToolPlanningService preIntentToolPlanningService;
    private final AgenticAuthoringRuntimeComponentGroundingService runtimeComponentGroundingService;
    private final AgenticAuthoringTurnRouteClassifier routeClassifier = new AgenticAuthoringTurnRouteClassifier();

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                null,
                (AgenticAuthoringOrchestrator) null,
                null,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                (AgenticAuthoringOrchestrator) null,
                null,
                null,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringOrchestrator orchestrator) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                orchestrator,
                null,
                null,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringOrchestrator orchestrator,
            SchemaRetrievalService schemaRetrievalService) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                orchestrator,
                schemaRetrievalService,
                null,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringOrchestrator orchestrator,
            SchemaRetrievalService schemaRetrievalService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                orchestrator,
                schemaRetrievalService,
                componentCapabilitiesService,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringOrchestrator orchestrator,
            SchemaRetrievalService schemaRetrievalService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringConsultativeAnswerService consultativeAnswerService) {
        this(
                intentResolverService,
                previewService,
                objectMapper,
                currentPageAnalyzer,
                toolRegistry,
                projectKnowledgeService,
                orchestrator,
                schemaRetrievalService,
                componentCapabilitiesService,
                consultativeAnswerService,
                null);
    }

    public AgenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            ObjectMapper objectMapper,
            AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer,
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringOrchestrator orchestrator,
            SchemaRetrievalService schemaRetrievalService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringConsultativeAnswerService consultativeAnswerService,
            AgenticAuthoringPreIntentToolPlanningService preIntentToolPlanningService) {
        this.intentResolverService = intentResolverService;
        this.previewService = previewService;
        this.objectMapper = objectMapper;
        this.currentPageAnalyzer = currentPageAnalyzer;
        this.toolRegistry = toolRegistry;
        this.projectKnowledgeService = projectKnowledgeService;
        this.orchestrator = orchestrator;
        this.schemaRetrievalService = schemaRetrievalService;
        this.componentCapabilitiesService = componentCapabilitiesService;
        this.consultativeAnswerService = consultativeAnswerService;
        this.preIntentToolPlanningService = preIntentToolPlanningService;
        this.runtimeComponentGroundingService = new AgenticAuthoringRuntimeComponentGroundingService(objectMapper);
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
        request = withGroundedRuntimeComponentContext(request);
        AgenticAuthoringTurnState state = initialState(request);
        request = withActiveDecisionContext(request, state.activeSemanticDecision());
        PreloadedComponentCapabilities componentCapabilitiesFuture =
                preloadServerComponentCapabilities(request);
        try {
            eventSink.append("thought.step", thoughtStepPayload(
                    "context.bundle",
                    "Contexto do pedido recebido; vou organizar pagina, historico e evidencias disponiveis.",
                    "Authoring context received.",
                    safeDiagnostics(request)));
            emitRuntimeComponentGroundingStep(request, eventSink);
            emitStatus(
                    eventSink,
                    "intent.resolve",
                    "Estou organizando o pedido, o contexto da pagina e as restricoes informadas.");
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve",
                    "Estou identificando a intencao principal do pedido antes de escolher recursos ou componentes.",
                    "Preparing semantic intent resolution."));
            request = withProjectKnowledgeContext(request, principalContext, eventSink, null);
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery =
                    maybeRunPreIntentToolPlan(request, principalContext, eventSink);
            if (plannedResourceDiscovery != null
                    && plannedResourceDiscovery.candidates() != null
                    && !plannedResourceDiscovery.candidates().isEmpty()) {
                request = withResourceDiscoveryContext(request, plannedResourceDiscovery);
            }
            AgenticAuthoringResourceCandidatesResult earlyResourceDiscovery =
                    maybePreDiscoverResourcesForMaterialization(request, principalContext, eventSink);
            if (earlyResourceDiscovery != null
                    && earlyResourceDiscovery.candidates() != null
                    && !earlyResourceDiscovery.candidates().isEmpty()) {
                request = withResourceDiscoveryContext(request, earlyResourceDiscovery);
            }
            request = withServerComponentCapabilities(request, eventSink, componentCapabilitiesFuture);
            boolean resourceDiscoveryContextPresent = hasResourceDiscoveryContext(request);
            if (!resourceDiscoveryContextPresent) {
                emitStatus(
                        eventSink,
                        "intent.resolve.llm",
                        "A LLM esta confirmando a intencao com a evidencia governada antes de materializar a tela.");
            }
            String intentResolutionPhase = resourceDiscoveryContextPresent
                    ? "intent.resolve.evidence"
                    : "intent.resolve.llm";
            eventSink.append("thought.step", thoughtStepPayload(
                    intentResolutionPhase,
                    resourceDiscoveryContextPresent
                            ? "Estou avaliando os candidatos governados recuperados antes de materializar."
                            : "A LLM esta confirmando a intencao com o contexto governado.",
                    "Resolving the user request against governed context.",
                    Map.of(
                            "provider", safeText(request.provider()),
                            "model", safeText(request.model()),
                            "hasProjectKnowledge", request.contextHints() != null
                                    && request.contextHints().path("projectKnowledge").isObject())));
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
            emitIntentResolved(eventSink, intentResolution, route);
            AgenticAuthoringTurnOutcome postIntentConsultativeOutcome = maybeAnswerPostIntentConsultative(
                    request,
                    principalContext,
                    eventSink,
                    state,
                    intentResolution,
                    route);
            if (postIntentConsultativeOutcome != null) {
                return postIntentConsultativeOutcome;
            }
            boolean compactGovernedFastPath = resolvedByPreIntentGovernedEvidence(intentResolution);
            if (!compactGovernedFastPath) {
                emitStatus(
                        eventSink,
                        "intent.resolve.grounding",
                        "Estou conferindo a decisao com as evidencias governadas disponiveis.");
                eventSink.append("thought.step", thoughtStepPayload(
                        "intent.resolve.grounding",
                        "Estou conferindo a intencao resolvida com as evidencias governadas disponiveis.",
                        "Checking resolved intent against governed resource evidence.",
                        intentGroundingDiagnostics(intentResolution, route)));
                emitIntentResolutionProgress(eventSink, intentResolution);
            }
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
                    && !isAdvisoryCatalogIntent(intentResolution)
                    && !eventSink.terminalReached()) {
                emitStatus(
                        eventSink,
                        "intent.resolve.llm",
                        "Encontrei candidatos no backend e estou pedindo para a LLM revisar a escolha.");
                eventSink.append("thought.step", safeToolProjection(
                        "intent.resolve.llm",
                        "Asking the LLM to review backend resource candidates.",
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
                emitIntentResolved(eventSink, intentResolution, route);
                emitStatus(
                        eventSink,
                        "intent.resolve.grounding",
                        "Estou validando a escolha refinada com as evidencias do backend.");
                eventSink.append("thought.step", thoughtStepPayload(
                        "intent.resolve.grounding",
                        "Estou validando a escolha refinada com as evidencias do backend.",
                        "Checking refined intent against backend resource evidence.",
                        intentGroundingDiagnostics(intentResolution, route)));
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
            request = withAuthoringEvidenceContext(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route);
            AgenticAuthoringPreviewResult preview = null;
            AgenticAuthoringToolLoopResult toolLoopResult = null;
            if (route.allowsPreview() && intentResolution.valid()) {
                AgenticAuthoringTurnStreamRequest contextualPreviewRequest =
                        withImplicitChartDetailModalActionContext(request, intentResolution);
                AgenticAuthoringTurnStreamRequest previewRequest = resolvedByPreIntentGovernedEvidence(intentResolution)
                        ? contextualPreviewRequest
                        : withProjectKnowledgeContext(
                                contextualPreviewRequest,
                                principalContext,
                                eventSink,
                                intentResolution);
                if (!compactGovernedFastPath) {
                    emitStatus(
                            eventSink,
                            "preview.plan",
                            "Entendi a intencao e estou planejando a materializacao governada.");
                }
                eventSink.append("thought.step", thoughtStepPayload(
                        "preview.plan",
                        "Entendi a intencao e estou planejando a materializacao governada.",
                        "Planning governed page materialization.",
                        Map.of(
                                "routeClass", safeText(route.routeClass()),
                                "artifactKind", safeText(intentResolution.artifactKind()),
                                "operationKind", safeText(intentResolution.operationKind()))));
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
                if (!compactGovernedFastPath) {
                    emitStatus(
                            eventSink,
                            "preview.compile",
                            preview.valid()
                                    ? "Estou preparando a pre-visualizacao para revisao."
                                    : "A pre-visualizacao precisa de uma revisao de seguranca antes de continuar.");
                }
                eventSink.append("thought.step", thoughtStepPayload(
                        "preview.compile",
                        preview.valid()
                                ? "Estou preparando a pre-visualizacao para revisao."
                                : "A pre-visualizacao precisa de uma revisao de seguranca antes de continuar.",
                        preview.valid() ? "Compiled preview payload." : "Preview requires backend repair classification.",
                        safePreviewDiagnostics(intentResolution, preview, false)));
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
            String assistantMessage = previewAssistantMessage(
                    request.userPrompt(),
                    preview,
                    intentResolution,
                    resourceDiscovery,
                    businessCatalogDiscovery,
                    schemaBaseUrl);
            Map<String, Object> decisionDiagnostics = decisionDiagnostics(intentResolution, preview, toolLoopResult, request);
            if (Boolean.TRUE.equals(decisionDiagnostics.get("semanticDecisionReviewGroundedByPreview"))) {
                assistantMessage = groundedPreviewAssistantMessage(preview, intentResolution);
            }
            AgenticAuthoringIntentResolutionResult terminalIntentResolution =
                    terminalIntentResolution(intentResolution, decisionDiagnostics);
            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("intentResolution", terminalIntentResolution);
            resultPayload.put("preview", preview != null ? preview : objectMapper.createObjectNode());
            resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage));
            resultPayload.put("assistantContent", assistantContent(
                    terminalIntentResolution,
                    businessCatalogDiscovery,
                    resourceDiscovery));
            resultPayload.put(
                    "quickReplies",
                    terminalQuickReplies(request, intentResolution, businessCatalogDiscovery, preview));
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
                    "assistantMessage", "Tive um problema para concluir essa conversa agora. Tente de novo com um pedido um pouco mais direto ou confirme qual fonte de negocio devo usar.",
                    "code", "agentic-authoring-processing-failed",
                    "phase", "agentic-authoring"));
            return terminalResult.appendedType("error")
                    ? AgenticAuthoringTurnOutcome.expired(state)
                    : AgenticAuthoringTurnOutcome.noop(state);
        }
    }

    private void emitStatus(
            AgenticAuthoringTurnEventSink eventSink,
            String phase,
            String message) {
        if (eventSink == null || eventSink.terminalReached()) {
            return;
        }
        eventSink.append("status", Map.of(
                "state", "in_progress",
                "phase", safeText(phase),
                "message", presentationText(message),
                "summary", presentationText(message)));
    }

    private Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary) {
        return thoughtStepPayload(phase, message, summary, null);
    }

    private Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary,
            Object diagnostics) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", safeText(phase));
        payload.put("message", presentationText(message));
        payload.put("summary", presentationText(summary));
        if (diagnostics != null) {
            payload.put("diagnostics", diagnostics);
        }
        return payload;
    }

    private void emitRuntimeComponentGroundingStep(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink) {
        if (eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode context = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("groundedRuntimeComponentContext");
        if (context == null || !context.isObject()) {
            return;
        }
        eventSink.append("thought.step", thoughtStepPayload(
                "runtime.context.grounding",
                "Estou aterrando o contexto dos componentes atuais como evidencia nao autoritativa.",
                "Grounded runtime component observations as untrusted evidence.",
                safeRuntimeGroundingDiagnostics(context)));
    }

    private Map<String, Object> safeRuntimeGroundingDiagnostics(JsonNode context) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        JsonNode contextDiagnostics = context == null ? null : context.path("diagnostics");
        diagnostics.put("canonicalContext", context == null ? "" : safeText(context.path("canonicalContext").asText("")));
        diagnostics.put("trustLevel", context == null ? "" : safeText(context.path("trustLevel").asText("")));
        diagnostics.put("acceptedComponentCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("acceptedComponentCount").asInt(0));
        diagnostics.put("acceptedClaimCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("acceptedClaimCount").asInt(0));
        diagnostics.put("rejectedClaimCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("rejectedClaimCount").asInt(0));
        diagnostics.put("availableSurfaces", safeTextValues(context.path("availableSurfaces"), 12));
        diagnostics.put("allowedOperations", safeTextValues(context.path("allowedOperations"), 16));
        diagnostics.put("acceptedClaims", safeRuntimeClaims(context.path("acceptedClaims"), 24));
        diagnostics.put("rejectedClaims", safeRejectedRuntimeClaims(context.path("rejectedClaims"), 12));
        diagnostics.put("evidenceRefs", safeRuntimeEvidenceRefs(context.path("evidenceRefs"), 12));
        diagnostics.put("rawRuntimeValuesCopied", false);
        return diagnostics;
    }

    private List<String> safeTextValues(JsonNode source, int limit) {
        List<String> values = new ArrayList<>();
        if (source == null || !source.isArray()) {
            return values;
        }
        for (JsonNode item : source) {
            if (values.size() >= limit) {
                break;
            }
            String text = item.asText("");
            if (StringUtils.hasText(text)) {
                values.add(text);
            }
        }
        return values;
    }

    private List<Map<String, Object>> safeRuntimeClaims(JsonNode claims, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (claims == null || !claims.isArray()) {
            return values;
        }
        for (JsonNode claim : claims) {
            if (values.size() >= limit || !claim.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", safeText(claim.path("kind").asText("")));
            item.put("ref", safeText(claim.path("ref").asText("")));
            if (claim.path("observed").isBoolean()) {
                item.put("observed", claim.path("observed").asBoolean());
            }
            values.add(item);
        }
        return values;
    }

    private List<Map<String, Object>> safeRejectedRuntimeClaims(JsonNode claims, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (claims == null || !claims.isArray()) {
            return values;
        }
        for (JsonNode claim : claims) {
            if (values.size() >= limit || !claim.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reason", safeText(claim.path("reason").asText("")));
            item.put("componentId", safeText(claim.path("componentId").asText("")));
            item.put("schemaVersion", safeText(claim.path("schemaVersion").asText("")));
            values.add(item);
        }
        return values;
    }

    private List<Map<String, Object>> safeRuntimeEvidenceRefs(JsonNode refs, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (refs == null || !refs.isArray()) {
            return values;
        }
        for (JsonNode ref : refs) {
            if (values.size() >= limit || !ref.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", safeText(ref.path("source").asText("")));
            item.put("componentId", safeText(ref.path("componentId").asText("")));
            item.put("instanceId", safeText(ref.path("instanceId").asText("")));
            item.put("resourceKey", safeText(ref.path("resourceKey").asText("")));
            item.put("pageId", safeText(ref.path("pageId").asText("")));
            values.add(item);
        }
        return values;
    }

    private AgenticAuthoringTurnOutcome maybeAnswerPostIntentConsultative(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (eventSink.terminalReached()) {
            return null;
        }
        if (!isPostIntentConsultativeRoute(route)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "consultative.post-intent.skipped",
                    "A rota resolvida segue para materializacao governada; nao preciso de resposta consultiva agora.",
                    "Resolved route requires governed materialization or another executor.",
                    Map.of(
                            "serviceAvailable", true,
                            "routeClass", safeText(route == null ? "" : route.routeClass()))));
            return null;
        }
        AgenticAuthoringTurnOutcome groundedClarificationOutcome =
                maybeAnswerGroundedResourceDiscoveryClarification(request, eventSink, state, intentResolution, route);
        if (groundedClarificationOutcome != null) {
            return groundedClarificationOutcome;
        }
        groundedClarificationOutcome =
                maybeAnswerGroundedDomainDiscoveryClarification(request, eventSink, state, intentResolution, route);
        if (groundedClarificationOutcome != null) {
            return groundedClarificationOutcome;
        }
        if (consultativeAnswerService == null) {
            log.info("[AgenticAuthoring] Post-intent consultative answer unavailable; service bean was not injected.");
            eventSink.append("thought.step", thoughtStepPayload(
                    "consultative.post-intent.skipped",
                    "A resposta consultiva nao esta disponivel neste runtime; vou seguir pelo caminho governado restante.",
                    "Post-intent consultative answer service unavailable.",
                    Map.of("serviceAvailable", false)));
            return null;
        }
        emitStatus(
                eventSink,
                "consultative.intent",
                "A intencao foi entendida como pergunta consultiva; estou preparando a resposta com evidencias governadas.");
        AgenticAuthoringTurnStreamRequest consultativeRequest = withResolvedIntentContext(
                request,
                intentResolution,
                route);
        AgenticAuthoringConsultativeAnswer answer = consultativeAnswerService.answer(
                        consultativeRequest,
                        consultativeRequest.componentCapabilities(),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment())
                .orElse(null);
        log.info("[AgenticAuthoring] Post-intent consultative answer evaluated: routeClass={}, answerPresent={}",
                route.routeClass(),
                answer != null);
        eventSink.append("thought.step", thoughtStepPayload(
                "consultative.post-intent.probe",
                "Estou verificando se uma resposta consultiva governada resolve este turno.",
                "Post-intent consultative executor evaluated.",
                Map.of(
                        "serviceAvailable", true,
                        "routeClass", safeText(route.routeClass()),
                        "answerPresent", answer != null)));
        if (answer == null || eventSink.terminalReached()) {
            return null;
        }
        emitRuntimeRelatedSurfaceEvidenceSteps(answer, eventSink);
        eventSink.append("thought.step", streamEventPayload(
                "consultative.answer",
                "Answered consultative turn after semantic intent resolution.",
                Map.of(
                        "category", safeText(answer.category()),
                        "routeClass", safeText(route.routeClass()),
                        "hasApiCatalogProjection", answer.apiCatalogProjection() != null
                                && answer.apiCatalogProjection().hasResources(),
                        "hasRuntimeConsultableContext", answer.evidenceBundle() != null
                                && answer.evidenceBundle().path("runtimeConsultableContext").isObject()),
                "consultative.answer:" + safeText(answer.category())));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(intentResolution, null, null);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", objectMapper.createObjectNode());
        resultPayload.put("assistantMessage", publicAssistantMessage(answer.assistantMessage()));
        resultPayload.put("assistantContent",
                AgenticAuthoringAssistantContentFactory.fromConsultativeProjection(answer.apiCatalogProjection()));
        if (answer.evidenceBundle() != null && !answer.evidenceBundle().isNull()) {
            resultPayload.put("evidenceBundle", answer.evidenceBundle());
        }
        resultPayload.put("quickReplies", consultativeQuickReplies(answer));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:consultative_post_intent:" + safeText(answer.category()),
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("result", resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private AgenticAuthoringTurnOutcome maybeAnswerGroundedDomainDiscoveryClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (request == null
                || eventSink == null
                || eventSink.terminalReached()
                || route == null
                || !"needs_clarification".equals(route.routeClass())) {
            return null;
        }
        JsonNode contextHints = request.contextHints() == null ? objectMapper.missingNode() : request.contextHints();
        JsonNode resourceCandidates = contextHints.path("resourceDiscovery").path("candidates");
        if (resourceCandidates.isArray() && !resourceCandidates.isEmpty()) {
            return null;
        }
        JsonNode domainDiscovery = contextHints.path("domainDiscovery");
        if (!domainDiscovery.isArray() || domainDiscovery.isEmpty()) {
            return null;
        }
        List<JsonNode> resources = domainDiscoveryResources(domainDiscovery, 4);
        if (resources.isEmpty()) {
            return null;
        }
        List<String> labels = resources.stream()
                .map(this::domainDiscoveryLabel)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(4)
                .toList();
        if (labels.isEmpty()) {
            return null;
        }
        String assistantMessage = "Não consegui executar a confirmação semântica nem a busca governada agora. "
                + "O contexto governado disponível inclui: " + String.join(", ", labels) + ". "
                + "Não vou materializar a tela automaticamente; confirme qual fonte devo investigar ou peça para tentar novamente a busca governada.";
        eventSink.append("thought.step", streamEventPayload(
                "consultative.grounded-domain-clarification",
                "Answered clarification from governed domain discovery context after provider failure.",
                Map.of(
                        "routeClass", safeText(route.routeClass()),
                        "domainResourceCount", resources.size(),
                        "providerFailure", true),
                "consultative.grounded-domain-clarification:domain_discovery"));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(intentResolution, null, null, request);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        decisionDiagnostics.put("domainDiscoveryGroundedClarification", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", objectMapper.createObjectNode());
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage));
        resultPayload.put("assistantContent", objectMapper.createObjectNode());
        resultPayload.put("quickReplies", domainDiscoveryQuickReplies(resources));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:grounded_domain_discovery_clarification",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("result", resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private List<JsonNode> domainDiscoveryResources(JsonNode domainDiscovery, int limit) {
        List<JsonNode> resources = new ArrayList<>();
        if (domainDiscovery == null || !domainDiscovery.isArray()) {
            return resources;
        }
        for (JsonNode resource : domainDiscovery) {
            if (resources.size() >= limit || resource == null || !resource.isObject()) {
                break;
            }
            if (StringUtils.hasText(domainDiscoveryLabel(resource))) {
                resources.add(resource);
            }
        }
        return resources;
    }

    private List<AgenticAuthoringQuickReply> domainDiscoveryQuickReplies(List<JsonNode> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (JsonNode resource : resources) {
            if (replies.size() >= 4 || resource == null || !resource.isObject()) {
                break;
            }
            String label = domainDiscoveryLabel(resource);
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-agentic-authoring-domain-discovery-choice.v1");
            contextHints.put("source", "domainDiscovery");
            putText(contextHints, "resourceKey", resource.path("resourceKey").asText(""));
            putText(contextHints, "resourceLabel", label);
            if (resource.path("fields").isArray()) {
                contextHints.set("fields", resource.path("fields").deepCopy());
            }
            if (resource.path("surfaces").isArray()) {
                contextHints.set("surfaces", resource.path("surfaces").deepCopy());
            }
            ObjectNode value = objectMapper.createObjectNode();
            putText(value, "resourceKey", resource.path("resourceKey").asText(""));
            putText(value, "label", label);
            replies.add(new AgenticAuthoringQuickReply(
                    "domain-discovery-confirm:" + replies.size(),
                    "resource",
                    label,
                    "Investigue " + label + " como fonte governada para a tela.",
                    "Usa o contexto governado já disponível antes de materializar qualquer configuração.",
                    "dataset",
                    "resource",
                    contextHints,
                    null,
                    value));
        }
        return replies;
    }

    private String domainDiscoveryLabel(JsonNode resource) {
        if (resource == null || !resource.isObject()) {
            return "";
        }
        String label = firstNonBlank(
                resource.path("title").asText(""),
                resource.path("label").asText(""),
                resource.path("resourceLabel").asText(""),
                resource.path("resourceKey").asText(""));
        return StringUtils.hasText(label)
                ? AgenticAuthoringPresentationText.display(label)
                : "";
    }

    private AgenticAuthoringTurnOutcome maybeAnswerGroundedResourceDiscoveryClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (request == null
                || eventSink == null
                || eventSink.terminalReached()
                || route == null
                || !"needs_clarification".equals(route.routeClass())) {
            return null;
        }
        JsonNode resourceDiscovery = request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints().path("resourceDiscovery");
        JsonNode candidates = resourceDiscovery.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            candidates = intentResolutionCandidateNodes(intentResolution);
        }
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection =
                resourceDiscoveryProjection(resourceDiscovery);
        ArrayNode presentableCandidates = presentableResourceDiscoveryCandidates(candidates);
        boolean onlyWeakCandidates = presentableCandidates.isEmpty();
        boolean providerFailure = contains(
                intentResolution == null ? null : intentResolution.warnings(),
                "llm-provider-error");
        if (onlyWeakCandidates && !providerFailure) {
            return null;
        }
        List<AgenticAuthoringQuickReply> quickReplies = onlyWeakCandidates
                ? List.of()
                : projection != null && projection.hasResources()
                ? consultativeQuickReplies(new AgenticAuthoringConsultativeAnswer(
                        "resource_discovery",
                        "clarify_resource_selection",
                        "",
                        projection,
                        List.of("resource-discovery-grounded-provider-failure-clarification")))
                : resourceDiscoveryCandidateQuickReplies(presentableCandidates);
        String assistantMessage = groundedResourceDiscoveryClarificationMessage(request, presentableCandidates, projection);
        eventSink.append("thought.step", streamEventPayload(
                "consultative.grounded-clarification",
                "Answered clarification from governed resource discovery evidence.",
                Map.of(
                        "routeClass", safeText(route.routeClass()),
                        "candidateCount", candidates.size(),
                        "presentableCandidateCount", presentableCandidates.size(),
                        "hasApiCatalogProjection", projection != null && projection.hasResources(),
                        "onlyWeakCandidates", onlyWeakCandidates,
                        "providerFailure", providerFailure),
                "consultative.grounded-clarification:resource_discovery"));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(intentResolution, null, null, request);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        decisionDiagnostics.put("resourceDiscoveryGroundedClarification", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", objectMapper.createObjectNode());
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage));
        resultPayload.put("assistantContent", projection == null
                ? objectMapper.createObjectNode()
                : AgenticAuthoringAssistantContentFactory.fromConsultativeProjection(projection));
        resultPayload.put("quickReplies", quickReplies);
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:grounded_resource_discovery_clarification",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = eventSink.append("result", resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private ArrayNode intentResolutionCandidateNodes(AgenticAuthoringIntentResolutionResult intentResolution) {
        ArrayNode nodes = objectMapper.createArrayNode();
        if (intentResolution == null || intentResolution.candidates() == null || intentResolution.candidates().isEmpty()) {
            return nodes;
        }
        for (AgenticAuthoringCandidate candidate : intentResolution.candidates()) {
            if (candidate != null) {
                nodes.add(candidateContext(candidate));
            }
        }
        return nodes;
    }

    private AgenticAuthoringConsultativeApiCatalogProjection resourceDiscoveryProjection(JsonNode resourceDiscovery) {
        JsonNode projection = resourceDiscovery == null
                ? objectMapper.missingNode()
                : resourceDiscovery.path("consultativeProjection");
        if (!projection.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(projection, AgenticAuthoringConsultativeApiCatalogProjection.class);
        } catch (Exception ex) {
            log.warn("[AgenticAuthoring] Unable to read resource discovery consultative projection.", ex);
            return null;
        }
    }

    private String groundedResourceDiscoveryClarificationMessage(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode presentableCandidates,
            AgenticAuthoringConsultativeApiCatalogProjection projection) {
        if (presentableCandidates == null || !presentableCandidates.isArray() || presentableCandidates.isEmpty()) {
            String domainContext = domainDiscoveryContextSentence(request);
            return "A busca governada retornou candidatos preliminares, mas ainda sem evidência forte suficiente para eu destacar uma fonte. "
                    + domainContext
                    + "Como a confirmação semântica da intenção falhou nesta tentativa, não vou materializar a tela automaticamente. "
                    + "Confirme a fonte de dados desejada ou peça para tentar novamente a busca governada.";
        }
        List<String> labels = projection != null && projection.resources() != null
                ? projection.resources().stream()
                        .filter(Objects::nonNull)
                        .map(this::resourceLabel)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(3)
                        .toList()
                : resourceDiscoveryCandidateLabels(presentableCandidates, 3);
        String candidateText = labels.isEmpty()
                ? "fontes governadas compatíveis"
                : String.join(", ", labels);
        return "Encontrei candidatos governados para continuar: " + candidateText + "."
                + " Como a confirmação semântica da intenção falhou nesta tentativa, não vou materializar a tela automaticamente. "
                + "Confirme uma fonte ou peça os campos confirmados para eu seguir com segurança.";
    }

    private String domainDiscoveryContextSentence(AgenticAuthoringTurnStreamRequest request) {
        JsonNode contextHints = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints();
        JsonNode domainDiscovery = contextHints.path("domainDiscovery");
        List<String> labels = domainDiscoveryResources(domainDiscovery, 3).stream()
                .map(this::domainDiscoveryLabel)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return labels.isEmpty()
                ? ""
                : "O contexto governado disponível inclui: " + String.join(", ", labels) + ". ";
    }

    private boolean onlyWeakResourceDiscoveryCandidates(JsonNode candidates) {
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            if (!isWeakResourceDiscoveryCandidate(candidate)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWeakResourceDiscoveryCandidate(JsonNode candidate) {
        JsonNode evidence = candidate == null ? objectMapper.missingNode() : candidate.path("evidence");
        if (!evidence.isArray()) {
            return false;
        }
        boolean lexicalFallback = false;
        boolean weakEvidence = false;
        boolean broadArtifactDiscovery = false;
        for (JsonNode item : evidence) {
            String value = safeText(item.asText(""));
            lexicalFallback = lexicalFallback || "lexical-fallback".equals(value);
            weakEvidence = weakEvidence || "weak-evidence".equals(value);
            broadArtifactDiscovery = broadArtifactDiscovery || "broad-artifact-discovery".equals(value);
        }
        return lexicalFallback || weakEvidence || broadArtifactDiscovery;
    }

    private ArrayNode presentableResourceDiscoveryCandidates(JsonNode candidates) {
        ArrayNode presentable = objectMapper.createArrayNode();
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return presentable;
        }
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isObject() && !isWeakResourceDiscoveryCandidate(candidate)) {
                presentable.add(candidate);
            }
        }
        return presentable;
    }

    private List<String> resourceDiscoveryCandidateLabels(JsonNode candidates, int limit) {
        List<String> labels = new ArrayList<>();
        if (candidates == null || !candidates.isArray()) {
            return labels;
        }
        for (JsonNode candidate : candidates) {
            if (labels.size() >= limit || candidate == null || !candidate.isObject()) {
                break;
            }
            String label = resourceDiscoveryCandidateLabel(candidate);
            if (StringUtils.hasText(label)) {
                labels.add(AgenticAuthoringPresentationText.display(label));
            }
        }
        return labels;
    }

    private List<AgenticAuthoringQuickReply> resourceDiscoveryCandidateQuickReplies(JsonNode candidates) {
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (candidates == null || !candidates.isArray()) {
            return replies;
        }
        for (JsonNode candidate : candidates) {
            if (replies.size() >= 4 || candidate == null || !candidate.isObject()) {
                break;
            }
            String resourcePath = safeText(candidate.path("resourcePath").asText(""));
            if (!StringUtils.hasText(resourcePath)) {
                continue;
            }
            String label = AgenticAuthoringPresentationText.display(resourceDiscoveryCandidateLabel(candidate));
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-agentic-authoring-resource-discovery-choice.v1");
            contextHints.put("source", "resourceDiscovery");
            contextHints.put("resourcePath", resourcePath);
            putText(contextHints, "operation", candidate.path("operation").asText(""));
            putText(contextHints, "schemaUrl", candidate.path("schemaUrl").asText(""));
            putText(contextHints, "submitUrl", candidate.path("submitUrl").asText(""));
            putText(contextHints, "submitMethod", candidate.path("submitMethod").asText(""));
            ObjectNode value = objectMapper.createObjectNode();
            value.put("resourcePath", resourcePath);
            putText(value, "operation", candidate.path("operation").asText(""));
            putCandidateScore(candidate, value);
            replies.add(new AgenticAuthoringQuickReply(
                    "resource-discovery-confirm:" + replies.size(),
                    "resource",
                    label,
                    "Use " + label + " como fonte governada para a tela.",
                    "Confirma este candidato governado antes de materializar a configuração.",
                    "dataset",
                    "resource",
                    contextHints,
                    null,
                    value));
        }
        return replies;
    }

    private String resourceDiscoveryCandidateLabel(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return "";
        }
        String explicit = firstNonBlank(
                candidate.path("resourceLabel").asText(""),
                candidate.path("resourceKey").asText(""));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String resourcePath = safeText(candidate.path("resourcePath").asText(""));
        if (StringUtils.hasText(resourcePath)) {
            String lastSegment = resourcePath;
            int slash = lastSegment.lastIndexOf('/');
            if (slash >= 0 && slash < lastSegment.length() - 1) {
                lastSegment = lastSegment.substring(slash + 1);
            }
            return lastSegment.replace('-', ' ').replace('_', ' ');
        }
        return safeText(candidate.path("reason").asText(""));
    }

    private void putCandidateScore(JsonNode candidate, ObjectNode value) {
        if (candidate != null && candidate.path("score").isNumber()) {
            value.put("score", candidate.path("score").asDouble());
        }
    }

    private AgenticAuthoringTurnStreamRequest withResolvedIntentContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (request == null || intentResolution == null) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resolvedIntent = contextHints.putObject("resolvedIntent");
        resolvedIntent.put("schemaVersion", "praxis-agentic-authoring-resolved-intent-context.v1");
        resolvedIntent.put("source", "intent.resolved");
        resolvedIntent.put("routeClass", safeText(route == null ? "" : route.routeClass()));
        resolvedIntent.put("operationKind", safeText(intentResolution.operationKind()));
        resolvedIntent.put("artifactKind", safeText(intentResolution.artifactKind()));
        resolvedIntent.put("changeKind", safeText(intentResolution.changeKind()));
        resolvedIntent.put("valid", intentResolution.valid());
        if (intentResolution.selectedCandidate() != null) {
            putText(resolvedIntent, "selectedResourcePath", intentResolution.selectedCandidate().resourcePath());
        }
        return copyWithContextHints(request, contextHints);
    }

    private boolean isPostIntentConsultativeRoute(AgenticAuthoringTurnRoute route) {
        if (route == null) {
            return false;
        }
        String routeClass = safeText(route.routeClass());
        return "advisory_authoring".equals(routeClass)
                || "needs_clarification".equals(routeClass);
    }

    private List<AgenticAuthoringQuickReply> consultativeQuickReplies(AgenticAuthoringConsultativeAnswer answer) {
        if (answer == null) {
            return List.of();
        }
        if (answer.quickReplies() != null && !answer.quickReplies().isEmpty()) {
            return answer.quickReplies();
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection = answer.apiCatalogProjection();
        if (projection == null || !projection.hasResources()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        AgenticAuthoringConsultativeApiCatalogProjection.Resource primary = firstResource(projection.resources());
        if (primary != null) {
            replies.add(consultativeResourceQuickReply(
                    "consultative-show-fields:" + safeResourceId(primary),
                    "Ver campos",
                    "Quais campos confirmados existem em " + resourceLabel(primary) + "?",
                    "Mostra os campos confirmados antes de escolher tabela, formulário ou gráfico.",
                    "view_list",
                    "resource",
                    primary,
                    quickReplyPresentation(
                            "Boa para entender se essa fonte cobre os dados que você precisa.",
                            "Retorna campos confirmados e pistas de uso para colunas, filtros, formulários ou gráficos.",
                            "Clique para explorar os campos dessa fonte.")));
            replies.add(consultativeResourceQuickReply(
                    "consultative-create-table:" + safeResourceId(primary),
                    "Criar tabela",
                    "Crie uma tabela filtrável usando " + resourceLabel(primary) + ".",
                    "Transforma a fonte confirmada em uma primeira tabela governada para revisão.",
                    "table_chart",
                    "primary",
                    primary,
                    quickReplyPresentation(
                            "Boa para listas operacionais, auditoria e navegação por registros.",
                            "Retorna uma prévia de tabela com colunas, paginação, filtros e formatação governada.",
                            "Clique para pedir uma tabela inicial com essa fonte.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource analytical = firstAnalyticalResource(projection.resources());
        if (analytical != null) {
            replies.add(consultativeResourceQuickReply(
                    "consultative-create-chart:" + safeResourceId(analytical),
                    "Criar gráfico",
                    "Crie uma visão com gráficos usando " + resourceLabel(analytical) + ".",
                    "Usa uma fonte analítica confirmada para propor indicadores e visualizações.",
                    "query_stats",
                    "analytics",
                    analytical,
                    quickReplyPresentation(
                            "Boa para indicadores, tendências, comparação e visão executiva.",
                            "Retorna uma prévia com gráficos compatíveis com os campos confirmados.",
                            "Clique para pedir uma composição analítica inicial.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource writable = firstWritableResource(projection.resources());
        if (writable != null) {
            replies.add(consultativeResourceQuickReply(
                    "consultative-create-form:" + safeResourceId(writable),
                    "Criar formulário",
                    "Crie um formulário governado usando " + resourceLabel(writable) + ".",
                    "Usa uma operação confirmada de escrita para capturar ou atualizar dados com governança.",
                    "dynamic_form",
                    "resource",
                    writable,
                    quickReplyPresentation(
                            "Boa quando a fonte confirma uma ação de cadastro ou atualização.",
                            "Retorna uma prévia de formulário conectada à operação permitida pelo catálogo.",
                            "Clique para pedir um formulário inicial com essa fonte.")));
        }
        return replies.stream()
                .filter(Objects::nonNull)
                .limit(4)
                .toList();
    }

    private AgenticAuthoringQuickReply consultativeResourceQuickReply(
            String id,
            String label,
            String prompt,
            String description,
            String icon,
            String tone,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource,
            ObjectNode presentation) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("schemaVersion", "praxis-agentic-authoring-consultative-resource-choice.v1");
        contextHints.put("source", "consultative-api-catalog-projection");
        putText(contextHints, "resourceKey", resource.resourceKey());
        putText(contextHints, "resourcePath", resource.resourcePath());
        putText(contextHints, "resourceLabel", resourceLabel(resource));
        putText(contextHints, "resourceRole", resource.role());
        if (presentation != null) {
            contextHints.set("presentation", presentation);
        }
        return new AgenticAuthoringQuickReply(
                id,
                "resource",
                label,
                prompt,
                description,
                icon,
                tone,
                contextHints,
                consultativeResourceSemanticDecision(id, resource),
                consultativeResourceChoiceValue(resource));
    }

    private ObjectNode consultativeResourceSemanticDecision(
            String id,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        ObjectNode decision = objectMapper.createObjectNode();
        decision.put("schemaVersion", "praxis-agentic-authoring-semantic-decision.v1");
        decision.put("decisionId", id);
        decision.put("artifactKind", "api_catalog");
        decision.put("changeKind", "answer_api_catalog_question");
        decision.put("source", "consultative-api-catalog-projection");
        putText(decision, "selectedResourcePath", resource.resourcePath());
        putText(decision, "selectedResourceKey", resource.resourceKey());
        putText(decision, "selectedResourceLabel", resourceLabel(resource));
        return decision;
    }

    private ObjectNode consultativeResourceChoiceValue(
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        ObjectNode value = objectMapper.createObjectNode();
        putText(value, "resourcePath", resource.resourcePath());
        putText(value, "resourceKey", resource.resourceKey());
        putText(value, "label", resourceLabel(resource));
        putText(value, "role", resource.role());
        return value;
    }

    private ObjectNode quickReplyPresentation(String bestFor, String returns, String nextStep) {
        ObjectNode presentation = objectMapper.createObjectNode();
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return presentation;
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstAnalyticalResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> {
                    String role = safeText(resource.role()).toLowerCase(Locale.ROOT);
                    return role.contains("analytic") || role.contains("indicator") || role.contains("metric");
                })
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstWritableResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> hasWriteAffordance(resource.actions()) || hasWriteEndpoint(resource.endpoints()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasWriteAffordance(List<AgenticAuthoringConsultativeApiCatalogProjection.Action> actions) {
        if (actions == null) {
            return false;
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .map(action -> safeText(action.name()) + " " + safeText(action.label()))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("create")
                        || value.contains("update")
                        || value.contains("save")
                        || value.contains("cadastro")
                        || value.contains("atualizar"));
    }

    private boolean hasWriteEndpoint(List<AgenticAuthoringConsultativeApiCatalogProjection.Endpoint> endpoints) {
        if (endpoints == null) {
            return false;
        }
        return endpoints.stream()
                .filter(Objects::nonNull)
                .map(endpoint -> safeText(endpoint.method()).toUpperCase(Locale.ROOT))
                .anyMatch(method -> method.equals("POST") || method.equals("PUT") || method.equals("PATCH"));
    }

    private String resourceLabel(AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        return AgenticAuthoringPresentationText.display(
                firstNonBlank(resource.label(), resource.resourceKey(), resource.resourcePath(), "fonte confirmada"));
    }

    private String safeResourceId(AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        String value = firstNonBlank(resource.resourceKey(), resource.label(), resource.resourcePath(), "resource");
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? "resource" : normalized;
    }

    private void putText(ObjectNode node, String field, String value) {
        if (node != null && StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private void emitRuntimeRelatedSurfaceEvidenceSteps(
            AgenticAuthoringConsultativeAnswer answer,
            AgenticAuthoringTurnEventSink eventSink) {
        if (answer == null || answer.evidenceBundle() == null || eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode resolution = answer.evidenceBundle().path("runtimeRelatedSurfaceResolution");
        if (!resolution.isObject()) {
            return;
        }
        String semanticDecisionRef = safeText(resolution.path("semanticDecisionRef").asText(""));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.intent",
                "Runtime related surface read authorized by consultative semantic decision.",
                Map.of("semanticDecisionRef", semanticDecisionRef),
                "runtime.related-surface.intent:" + semanticDecisionRef));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.candidates",
                "Runtime related surface candidates ranked with accepted and rejected claims.",
                resolution,
                "runtime.related-surface.candidates:" + semanticDecisionRef));
        JsonNode reads = answer.evidenceBundle().path("runtimeRelatedSurfaceReads");
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.read",
                reads.isArray() && !reads.isEmpty()
                        ? "Runtime related surface read completed through read-only backend tool."
                        : "Runtime related surface read was blocked or returned no governed records.",
                Map.of(
                        "readCount", reads.isArray() ? reads.size() : 0,
                        "selectedCandidateRef", safeText(resolution.path("selectedCandidateRef").asText(""))),
                "runtime.related-surface.read:" + semanticDecisionRef + ":"
                        + (reads.isArray() ? reads.size() : 0)));
        emitRuntimeToolPlanEvidenceSteps(answer.evidenceBundle(), eventSink);
    }

    private void emitRuntimeToolPlanEvidenceSteps(JsonNode evidenceBundle, AgenticAuthoringTurnEventSink eventSink) {
        if (evidenceBundle == null || eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode plan = evidenceBundle.path("runtimeToolPlan");
        if (!plan.isObject()) {
            return;
        }
        String semanticDecisionRef = safeText(plan.path("semanticDecisionRef").asText(""));
        String intentKind = safeText(plan.path("intentKind").asText(""));
        String policyRef = safeText(plan.path("multiToolAuthorization").path("policyRef").asText(""));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.intent",
                "Runtime tool plan authorized by consultative semantic decision.",
                Map.of(
                        "semanticDecisionRef", safeText(plan.path("semanticDecisionRef").asText("")),
                        "intentKind", safeText(plan.path("intentKind").asText("")),
                        "readMode", safeText(plan.path("readMode").asText(""))),
                "runtime.tool-plan.intent:" + semanticDecisionRef));
        JsonNode resolution = evidenceBundle.path("runtimeRelatedSurfaceResolution");
        if (resolution.isObject()) {
            eventSink.append("thought.step", streamEventPayload(
                    "runtime.tool-plan.candidates",
                    "Runtime tool plan candidates derived from governed related surface resolution.",
                    resolution,
                    "runtime.tool-plan.candidates:" + semanticDecisionRef));
        }
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.created",
                "Runtime tool plan created with explicit budget and governed steps.",
                plan,
                "runtime.tool-plan.created:" + semanticDecisionRef + ":" + intentKind + ":" + policyRef));
        JsonNode steps = plan.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String stepRef = safeText(step.path("stepRef").asText(""));
                eventSink.append("thought.step", streamEventPayload(
                        "runtime.tool-plan.step",
                        "Runtime tool plan step status recorded.",
                        step,
                        "runtime.tool-plan.step:" + semanticDecisionRef + ":" + firstNonBlank(stepRef, "unknown")));
            }
        }
        JsonNode reads = evidenceBundle.path("runtimeRelatedSurfaceReads");
        JsonNode executionDiagnostics = plan.path("executionDiagnostics");
        Map<String, Object> aggregateDiagnostics = new LinkedHashMap<>();
        aggregateDiagnostics.put("policyRef", safeText(plan.path("multiToolAuthorization").path("policyRef").asText("")));
        aggregateDiagnostics.put("dryRun", executionDiagnostics.path("dryRun").asBoolean(false));
        aggregateDiagnostics.put("multiToolExecutionEnabled", executionDiagnostics.path("multiToolExecutionEnabled").asBoolean(
                plan.path("planner").path("multiToolExecutionEnabled").asBoolean(false)));
        aggregateDiagnostics.put("authorizedCandidateCount", executionDiagnostics.path("authorizedCandidateCount").asInt(
                plan.path("candidateSteps").isArray() ? plan.path("candidateSteps").size() : 0));
        aggregateDiagnostics.put("candidateStepCount", plan.path("candidateSteps").isArray() ? plan.path("candidateSteps").size() : 0);
        aggregateDiagnostics.put("blockedStepCount", plan.path("blockedSteps").isArray() ? plan.path("blockedSteps").size() : 0);
        aggregateDiagnostics.put("maxPlannedSteps", executionDiagnostics.path("maxPlannedSteps").asInt(0));
        aggregateDiagnostics.put("maxExecutableSteps", executionDiagnostics.path("maxExecutableSteps").asInt(
                plan.path("steps").isArray() ? plan.path("steps").size() : 0));
        aggregateDiagnostics.put("nonExecutionReason", safeText(executionDiagnostics.path("nonExecutionReason").asText("")));
        aggregateDiagnostics.put("backendReadsPerformed", reads.isArray() && !reads.isEmpty());
        aggregateDiagnostics.put("readCount", reads.isArray() ? reads.size() : 0);
        aggregateDiagnostics.put("usedToolCalls", plan.path("budget").path("usedToolCalls").asInt(0));
        aggregateDiagnostics.put("maxToolCalls", plan.path("budget").path("maxToolCalls").asInt(0));
        aggregateDiagnostics.put("readMode", safeText(plan.path("readMode").asText("")));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.aggregate",
                "Runtime tool plan aggregate recorded.",
                aggregateDiagnostics,
                "runtime.tool-plan.aggregate:" + semanticDecisionRef + ":" + intentKind + ":"
                        + safeText(executionDiagnostics.path("aggregateStatus").asText("")) + ":"
                        + aggregateDiagnostics.get("usedToolCalls") + ":"
                        + aggregateDiagnostics.get("readCount")));
    }

    private Map<String, Object> streamEventPayload(
            String phase,
            String summary,
            Object diagnostics,
            String dedupeKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("message", summary);
        payload.put("summary", summary);
        payload.put("diagnostics", diagnostics);
        payload.put("streamEventDiagnostics", streamEventDiagnostics(dedupeKey, false));
        return payload;
    }

    private Map<String, Object> streamEventDiagnostics(String dedupeKey, boolean technicalDuplicate) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1");
        diagnostics.put("dedupeKey", safeText(dedupeKey));
        diagnostics.put("eventUniquenessKey", safeText(dedupeKey));
        diagnostics.put("technicalDuplicate", technicalDuplicate);
        diagnostics.put("technicalDuplicateOf", "");
        diagnostics.put("replaySafe", true);
        diagnostics.put("duplicatesDoNotIndicateExecution", true);
        return diagnostics;
    }

    private boolean isContextualPreviewAction(JsonNode contextHints) {
        if (contextHints == null || contextHints.isNull()) {
            return false;
        }
        String source = contextHintText(contextHints, "source");
        String kind = contextHintText(contextHints, "kind");
        String changeKind = contextHintText(contextHints, "changeKind");
        String targetComponentId = firstNonBlank(
                contextHintText(contextHints, "targetComponentId"),
                contextHintText(contextHints, "selectedComponentId"));
        return "component-capability-catalog".equals(source)
                || "contextual-preview-action".equals(kind)
                || (!changeKind.isBlank() && !targetComponentId.isBlank());
    }

    private String contextHintText(JsonNode contextHints, String fieldName) {
        return contextHints == null || fieldName == null ? "" : safeText(contextHints.path(fieldName).asText(""));
    }

    private AgenticAuthoringTurnStreamRequest withServerComponentCapabilities(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            PreloadedComponentCapabilities preloadedCapabilities) {
        if (request == null
                || (request.componentCapabilities() != null
                && request.componentCapabilities().catalogs() != null
                && !request.componentCapabilities().catalogs().isEmpty())) {
            return request;
        }
        emitStatus(
                eventSink,
                "component.capabilities",
                "Estou carregando capacidades governadas dos componentes para escolher a materializacao correta.");
        ComponentCapabilitiesLoadResult componentCapabilitiesLoad =
                awaitServerComponentCapabilities(preloadedCapabilities);
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = componentCapabilitiesLoad.result();
        eventSink.append("thought.step", thoughtStepPayload(
                "component.capabilities",
                "Capacidades governadas dos componentes carregadas; vou usar isso na decisao de materializacao.",
                "Loaded governed component capabilities.",
                Map.of(
                        "catalogCount", componentCapabilities != null && componentCapabilities.catalogs() != null
                                ? componentCapabilities.catalogs().size()
                                : 0,
                        "preloaded", componentCapabilitiesLoad.preloaded(),
                        "preloadCompletedBeforeAwait", componentCapabilitiesLoad.completedBeforeAwait(),
                        "fallbackSynchronousLoad", componentCapabilitiesLoad.fallbackSynchronousLoad(),
                        "awaitElapsedMs", componentCapabilitiesLoad.awaitElapsedMs(),
                        "preloadAgeMs", componentCapabilitiesLoad.preloadAgeMs(),
                        "source", componentCapabilitiesService == null ? "built-in" : "service")));
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
                request.contextHints(),
                componentCapabilities,
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private PreloadedComponentCapabilities preloadServerComponentCapabilities(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null
                || request.componentCapabilities() != null
                && request.componentCapabilities().catalogs() != null
                && !request.componentCapabilities().catalogs().isEmpty()) {
            return null;
        }
        return new PreloadedComponentCapabilities(
                CompletableFuture.supplyAsync(this::loadServerComponentCapabilities),
                System.nanoTime());
    }

    private ComponentCapabilitiesLoadResult awaitServerComponentCapabilities(
            PreloadedComponentCapabilities preloadedCapabilities) {
        long awaitStartedAtNanos = System.nanoTime();
        if (preloadedCapabilities == null) {
            AgenticAuthoringComponentCapabilitiesResult result = loadServerComponentCapabilities();
            return new ComponentCapabilitiesLoadResult(
                    result,
                    false,
                    false,
                    false,
                    elapsedMs(awaitStartedAtNanos),
                    0L);
        }
        boolean completedBeforeAwait = preloadedCapabilities.future().isDone();
        try {
            AgenticAuthoringComponentCapabilitiesResult result = preloadedCapabilities.future().join();
            return new ComponentCapabilitiesLoadResult(
                    result,
                    true,
                    completedBeforeAwait,
                    false,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        } catch (CompletionException ex) {
            log.debug("Preloaded component capabilities failed; loading synchronously.", ex);
            AgenticAuthoringComponentCapabilitiesResult result = loadServerComponentCapabilities();
            return new ComponentCapabilitiesLoadResult(
                    result,
                    true,
                    completedBeforeAwait,
                    true,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private record PreloadedComponentCapabilities(
            CompletableFuture<AgenticAuthoringComponentCapabilitiesResult> future,
            long startedAtNanos) {
    }

    private record ComponentCapabilitiesLoadResult(
            AgenticAuthoringComponentCapabilitiesResult result,
            boolean preloaded,
            boolean completedBeforeAwait,
            boolean fallbackSynchronousLoad,
            long awaitElapsedMs,
            long preloadAgeMs) {
    }

    private AgenticAuthoringComponentCapabilitiesResult loadServerComponentCapabilities() {
        return componentCapabilitiesService == null
                ? new AgenticAuthoringComponentCapabilitiesService().listCapabilities()
                : componentCapabilitiesService.listCapabilities();
    }

    private AgenticAuthoringTurnStreamRequest withGroundedRuntimeComponentContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null
                || request.contextHints() != null
                && request.contextHints().path("groundedRuntimeComponentContext").isObject()) {
            return request;
        }
        ObjectNode groundedContext = runtimeComponentGroundingService.ground(
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
        if (groundedContext == null || groundedContext.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("groundedRuntimeComponentContext", groundedContext);
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringResourceCandidatesResult maybePreDiscoverResourcesForMaterialization(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink) {
        if (!shouldPreDiscoverResourcesForMaterialization(request) || eventSink.terminalReached()) {
            return null;
        }
        emitStatus(
                eventSink,
                "resource.discovery",
                "Estou buscando fontes governadas relacionadas ao pedido antes de chamar a LLM.");
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                "pre_intent_resource_discovery",
                new AgenticAuthoringResourceCandidatesRequest(
                        safeText(request.userPrompt()),
                        request.userPrompt(),
                        "page",
                        6));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando recursos governados antes de resolver a intencao.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", "pre_intent_resource_discovery",
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "Encontrei candidatos governados para fundamentar a decisao."
                        : "Nao consegui concluir a busca governada neste passo.",
                safeToolDiagnostics(result)));
        return resourceDiscoveryPayload(result);
    }

    private AgenticAuthoringResourceCandidatesResult maybeRunPreIntentToolPlan(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink) {
        if (eventSink.terminalReached()) {
            return null;
        }
        if (preIntentToolPlanningService == null) {
            emitPreIntentToolPlanSkipped(eventSink, "planner-bean-unavailable", "");
            return null;
        }
        if (request == null) {
            emitPreIntentToolPlanSkipped(eventSink, "request-unavailable", "");
            return null;
        }
        if (hasResourceDiscoveryContext(request)) {
            emitPreIntentToolPlanSkipped(eventSink, "resource-discovery-context-present", "");
            return null;
        }
        AgenticAuthoringPreIntentToolPlanningResult planningResult =
                preIntentToolPlanningService.plan(request, principalContext);
        if (planningResult == null || !planningResult.planned()) {
            emitPreIntentToolPlanSkipped(
                    eventSink,
                    planningResult == null ? "planner-result-empty" : planningResult.skipReason(),
                    planningResult == null ? "" : planningResult.errorCode());
            return null;
        }
        AgenticAuthoringPreIntentToolPlan plan = planningResult.plan();
        if (plan == null || plan.toolCalls().isEmpty()) {
            emitPreIntentToolPlanSkipped(eventSink, "planner-tool-calls-empty", "");
            return null;
        }
        eventSink.append("thought.step", safeToolProjection(
                "tool.plan",
                "A LLM decidiu consultar ferramentas de leitura antes de materializar a tela.",
                Map.of(
                        "schemaVersion", safeText(plan.schemaVersion()),
                        "toolCallCount", Math.min(plan.toolCalls().size(), MAX_TOOL_CALLS_PER_TURN),
                        "reason", safeText(plan.reason()))));
        AgenticAuthoringResourceCandidatesResult resourceDiscovery = null;
        int executed = 0;
        for (AgenticAuthoringToolCall toolCall : plan.toolCalls()) {
            if (toolCall == null || executed >= MAX_TOOL_CALLS_PER_TURN || eventSink.terminalReached()) {
                break;
            }
            eventSink.append("thought.step", safeToolProjection(
                    "tool.start",
                    "Estou executando a busca governada planejada pela LLM.",
                    Map.of(
                            "tool", safeText(toolCall.name()),
                            "routeClass", safeText(toolCall.routeClass()),
                            "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
            AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
            eventSink.append("thought.step", safeToolProjection(
                    result.valid() ? "tool.result" : "tool.error",
                    result.valid()
                            ? "Busca governada concluida; vou usar os candidatos como evidencia."
                            : "A busca governada planejada pela LLM falhou.",
                    safeToolDiagnostics(result)));
            executed++;
            AgenticAuthoringResourceCandidatesResult payload = resourceDiscoveryPayload(result);
            if (payload != null) {
                resourceDiscovery = payload;
            }
            if (!result.valid()) {
                break;
            }
        }
        return resourceDiscovery;
    }

    private void emitPreIntentToolPlanSkipped(
            AgenticAuthoringTurnEventSink eventSink,
            String reason,
            String errorCode) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("skipReason", safeText(reason));
        if (StringUtils.hasText(errorCode)) {
            diagnostics.put("errorCode", safeText(errorCode));
        }
        eventSink.append("thought.step", safeToolProjection(
                "tool.plan.skipped",
                "O planejamento de ferramenta pre-intent foi ignorado com motivo diagnosticado.",
                diagnostics));
    }

    private boolean shouldPreDiscoverResourcesForMaterialization(AgenticAuthoringTurnStreamRequest request) {
        if (request == null
                || request.pendingClarification() != null
                || request.activeSemanticDecision() != null
                || hasResourceDiscoveryContext(request)
                || request.userPrompt() == null
                || request.userPrompt().isBlank()) {
            return false;
        }
        String prompt = safeText(request.userPrompt()).toLowerCase(Locale.ROOT);
        boolean createOrChange = prompt.contains("crie")
                || prompt.contains("criar")
                || prompt.contains("monte")
                || prompt.contains("montar")
                || prompt.contains("adicione")
                || prompt.contains("adicionar")
                || prompt.contains("inclua")
                || prompt.contains("incluir");
        boolean materializedSurface = prompt.contains("pagina")
                || prompt.contains("página")
                || prompt.contains("dashboard")
                || prompt.contains("painel")
                || prompt.contains("grafico")
                || prompt.contains("gráfico")
                || prompt.contains("tabela")
                || prompt.contains("formulario")
                || prompt.contains("formulário")
                || prompt.contains("accordion")
                || prompt.contains("acordeon")
                || prompt.contains("abas")
                || prompt.contains("tabs")
                || prompt.contains("widget");
        return createOrChange && materializedSurface;
    }

    private boolean hasResourceDiscoveryContext(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("resourceDiscovery").isObject()
                && request.contextHints().path("resourceDiscovery").path("candidates").isArray()
                && !request.contextHints().path("resourceDiscovery").path("candidates").isEmpty();
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
                "Estou consultando recursos do backend para conferir a decisao.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "Consulta de recursos concluida; estou conferindo os candidatos encontrados."
                        : "Nao consegui concluir a consulta de recursos do backend.",
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
                || !"advisory_authoring".equals(safeText(route.routeClass()))
                || isPlatformGuidancePrompt(request == null ? "" : request.userPrompt())
                || (!isAdvisoryCatalogIntent(intentResolution)
                && !isUnresolvedAdvisoryIntent(intentResolution))) {
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
                        isUnresolvedAdvisoryIntent(intentResolution)
                                ? "api_catalog"
                                : safeText(intentResolution == null ? "" : intentResolution.artifactKind()),
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
                        ? "O ciclo de ferramentas governadas foi concluido."
                        : "O ciclo de ferramentas governadas parou antes da conclusao.",
                safeToolLoopDiagnostics(result)));
        return result;
    }

    private AgenticAuthoringTurnStreamRequest withAuthoringEvidenceContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (request == null
                || toolRegistry == null
                || eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || hasAuthoringEvidenceContext(request)) {
            return request;
        }
        String componentId = authoringEvidenceComponentId(request, intentResolution);
        if (!StringUtils.hasText(componentId)) {
            if (resolvedByPreIntentGovernedEvidence(intentResolution)) {
                return request;
            }
            eventSink.append("thought.step", safeToolProjection(
                    "authoringEvidence.skipped",
                    "Nao ha componente selecionado para buscar evidencia granular de autoria.",
                    Map.of(
                            "skipReason", "component-id-empty",
                            "routeClass", safeText(route.routeClass()),
                            "artifactKind", safeText(intentResolution.artifactKind()),
                            "operationKind", safeText(intentResolution.operationKind()))));
            return request;
        }
        String retrievalQuery = authoringEvidenceQuery(request, intentResolution);
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.GET_COMPONENT_AUTHORING_CONTEXT,
                route.routeClass(),
                new CorpusToolRequest(
                        retrievalQuery,
                        componentId,
                        null,
                        null,
                        principalContext == null ? null : principalContext.tenantId(),
                        principalContext == null ? null : principalContext.environment(),
                        contextHintText(request.contextHints(), "releaseId"),
                        6));
        eventSink.append("thought.step", safeToolProjection(
                "authoringEvidence.retrieve",
                "Retrieving granular component corpus evidence for preview planning.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "componentId", safeText(componentId),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "authoringEvidence.result" : "authoringEvidence.error",
                result.valid()
                        ? "Granular component corpus evidence retrieved."
                        : "Granular component corpus evidence retrieval failed.",
                safeToolDiagnostics(result)));
        if (!result.valid()) {
            return request;
        }
        List<ContextRetrievalService.ComponentCorpusEvidence> evidence = componentCorpusEvidence(result);
        if (evidence.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("authoringEvidence", authoringEvidenceContext(
                toolCall.name(),
                retrievalQuery,
                componentId,
                evidence));
        return copyWithContextHints(request, contextHints);
    }

    private boolean hasAuthoringEvidenceContext(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("authoringEvidence").isObject()
                && request.contextHints().path("authoringEvidence").path("evidence").isArray()
                && !request.contextHints().path("authoringEvidence").path("evidence").isEmpty();
    }

    private boolean resolvedByPreIntentGovernedEvidence(AgenticAuthoringIntentResolutionResult intentResolution) {
        return intentResolution != null
                && containsWarning(
                        intentResolution.warnings(),
                        "llm-intent-resolution-satisfied-by-pre-intent-governed-evidence")
                && containsWarning(
                        intentResolution.warnings(),
                        "llm-pre-intent-resource-discovery-used");
    }

    private boolean containsWarning(List<String> warnings, String expected) {
        return warnings != null && warnings.stream().anyMatch(expected::equals);
    }

    private String authoringEvidenceQuery(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return firstNonBlank(
                request == null ? null : request.userPrompt(),
                intentResolution == null ? null : intentResolution.effectivePrompt(),
                intentResolution == null ? null : intentResolution.changeKind(),
                intentResolution == null ? null : intentResolution.artifactKind(),
                "component authoring context");
    }

    private String authoringEvidenceComponentId(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String componentId = firstNonBlank(
                contextHintText(request == null ? null : request.contextHints(), "selectedComponentId"),
                contextHintText(request == null ? null : request.contextHints(), "targetComponentId"),
                contextHintText(request == null ? null : request.contextHints(), "surfaceWidgetId"),
                intentResolution == null || intentResolution.target() == null
                        ? null
                        : intentResolution.target().componentId(),
                intentResolution == null ? null : intentResolution.targetComponentId(),
                request == null ? null : request.targetComponentId());
        return isContainerAuthoringComponent(componentId) ? null : componentId;
    }

    private boolean isContainerAuthoringComponent(String componentId) {
        String normalized = safeText(componentId).toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || "praxis-dynamic-page-builder".equals(normalized)
                || "page-builder".equals(normalized);
    }

    @SuppressWarnings("unchecked")
    private List<ContextRetrievalService.ComponentCorpusEvidence> componentCorpusEvidence(
            AgenticAuthoringToolResult result) {
        if (result == null || !(result.payload() instanceof List<?> payload)) {
            return List.of();
        }
        return payload.stream()
                .filter(ContextRetrievalService.ComponentCorpusEvidence.class::isInstance)
                .map(item -> (ContextRetrievalService.ComponentCorpusEvidence) item)
                .limit(6)
                .toList();
    }

    private ObjectNode authoringEvidenceContext(
            String tool,
            String retrievalQuery,
            String componentId,
            List<ContextRetrievalService.ComponentCorpusEvidence> evidence) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-evidence.v1");
        context.put("source", "vector_store/component-corpus");
        context.put("tool", safeText(tool));
        context.put("retrievalQuery", safeText(retrievalQuery));
        context.put("componentId", safeText(componentId));
        ArrayNode entries = context.putArray("evidence");
        evidence.stream().limit(6).forEach(item -> {
            ObjectNode entry = entries.addObject();
            entry.put("documentId", safeText(item.documentId()));
            entry.put("sourceId", safeText(item.sourceId()));
            entry.put("sourceKind", safeText(item.sourceKind()));
            entry.put("chunkKind", safeText(item.chunkKind()));
            entry.put("sourceRef", safeText(item.sourcePointer()));
            entry.put("releaseId", safeText(item.releaseId()));
            entry.put("tenantId", safeText(item.tenantId()));
            entry.put("environment", safeText(item.environment()));
            entry.put("aiVisibility", safeText(item.aiVisibility()));
            entry.put("contentHash", safeText(item.contentHash()));
            entry.put("corpusVersion", safeText(item.corpusVersion()));
            entry.put("similarityScore", item.similarityScore());
            entry.put("content", safeText(toSnippet(item.content())));
        });
        return context;
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

    private Map<String, Object> intentGroundingDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("routeClass", route == null ? "" : safeText(route.routeClass()));
        diagnostics.put("operationKind", intentResolution == null ? "" : safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", intentResolution == null ? "" : safeText(intentResolution.artifactKind()));
        diagnostics.put("changeKind", intentResolution == null ? "" : safeText(intentResolution.changeKind()));
        diagnostics.put("candidateCount", intentResolution == null || intentResolution.candidates() == null
                ? 0
                : intentResolution.candidates().size());
        AgenticAuthoringCandidate selectedCandidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        diagnostics.put("selectedResourcePath", selectedCandidate == null ? "" : safeText(selectedCandidate.resourcePath()));
        AgenticAuthoringEvidenceBundle evidenceBundle = selectedCandidate == null ? null : selectedCandidate.evidenceBundle();
        diagnostics.put("retrievalSource", evidenceBundle == null ? "" : safeText(evidenceBundle.retrievalSource()));
        diagnostics.put("grounded", selectedCandidate != null
                && selectedCandidate.evidence() != null
                && selectedCandidate.evidence().stream()
                .anyMatch(evidence -> "domain-catalog-grounding".equals(evidence)
                        || "semantic-retrieval".equals(evidence)
                        || "schema-grounding".equals(evidence)
                        || "tool-search-api-resources".equals(evidence)));
        return diagnostics;
    }

    private List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        if (isAdvisoryCatalogIntent(intentResolution) || isUnresolvedAdvisoryIntent(intentResolution)) {
            return List.of();
        }
        if (businessCatalogDiscovery != null
                && businessCatalogDiscovery.quickReplies() != null
                && !businessCatalogDiscovery.quickReplies().isEmpty()) {
            return businessCatalogDiscovery.quickReplies();
        }
        return intentResolution != null && intentResolution.quickReplies() != null
                ? intentResolution.quickReplies()
                : List.of();
    }

    private List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringPreviewResult preview) {
        List<AgenticAuthoringQuickReply> replies = terminalQuickReplies(intentResolution, businessCatalogDiscovery);
        List<AgenticAuthoringQuickReply> contextual = contextualPreviewQuickReplies(request, intentResolution, preview);
        if (contextual.isEmpty()) {
            return replies;
        }
        Map<String, AgenticAuthoringQuickReply> merged = new LinkedHashMap<>();
        replies.forEach(reply -> {
            if (reply != null && StringUtils.hasText(reply.id())) {
                merged.put(reply.id(), reply);
            }
        });
        contextual.forEach(reply -> {
            if (reply != null && StringUtils.hasText(reply.id())) {
                merged.putIfAbsent(reply.id(), reply);
            }
        });
        return List.copyOf(merged.values());
    }

    private List<AgenticAuthoringQuickReply> contextualPreviewQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        if (preview == null || !preview.valid() || isAdvisoryCatalogIntent(intentResolution)) {
            return List.of();
        }
        AgenticAuthoringComponentCapabilitiesResult capabilities = componentCapabilities(request);
        if (capabilities == null || capabilities.catalogs() == null || capabilities.catalogs().isEmpty()) {
            return List.of();
        }
        boolean hasChart = previewContainsComponent(preview, "praxis-chart");
        boolean hasTable = previewContainsComponent(preview, "praxis-table");
        String chartWidgetKey = previewComponentWidgetKey(preview, "praxis-chart");
        String tableWidgetKey = previewComponentWidgetKey(preview, "praxis-table");
        JsonNode previewPage = previewMaterializedPage(preview);
        JsonNode chartWidgetSnapshot = previewComponentWidget(previewPage, "praxis-chart", chartWidgetKey);
        JsonNode tableWidgetSnapshot = previewComponentWidget(previewPage, "praxis-table", tableWidgetKey);
        AgenticAuthoringCandidate selectedCandidate = intentResolution == null
                ? null
                : intentResolution.selectedCandidate();
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (hasChart && supportsCapability(capabilities, "praxis-chart", "set_chart_type")) {
            replies.add(contextualQuickReply(
                    "chart-change-line",
                    "Trocar para linhas",
                    "Altere o gráfico selecionado para linhas, mantendo a fonte de dados, a dimensão e a métrica atuais se esse tipo fizer sentido para os dados exibidos.",
                    "show_chart",
                    "set_chart_type",
                    "praxis-chart.type.set@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot));
            replies.add(contextualQuickReply(
                    "chart-change-donut",
                    "Ver como donut",
                    "Mostre o gráfico selecionado como donut somente se a dimensão atual representar uma composição categórica; caso não faça sentido, explique a alternativa visual mais adequada.",
                    "donut_large",
                    "set_chart_type",
                    "praxis-chart.type.set@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot));
        }
        if (hasChart && supportsCapability(capabilities, "praxis-chart", "enable_chart_drilldown")) {
            replies.add(contextualQuickReply(
                    "chart-add-detail-table",
                    "Detalhes em tabela",
                    "Acrescente um filtro e uma tabela de detalhes abaixo do gráfico, conectados à seleção do gráfico.",
                    "table_view",
                    "enable_chart_drilldown",
                    "praxis-chart.drilldown.enable@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot));
            ObjectNode surfaceHints = objectMapper.createObjectNode();
            surfaceHints.put("surfacePresentation", "modal");
            surfaceHints.put("surfaceActionId", "surface.open");
            surfaceHints.put("surfaceWidgetId", "praxis-table");
            replies.add(contextualQuickReply(
                    "chart-add-detail-modal",
                    "Detalhes em modal",
                    "Abra os registros da categoria selecionada do gráfico em um modal de detalhes.",
                    "open_in_new",
                    "enable_chart_drilldown",
                    "praxis-chart.drilldown.enable@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot,
                    surfaceHints));
        }
        if (hasTable && supportsCapability(capabilities, "praxis-table", "configure_export")) {
            replies.add(contextualQuickReply(
                    "table-export-selected-rows",
                    "Exportar selecionadas",
                    "Habilite seleção na tabela e adicione uma ação para exportar apenas as linhas selecionadas.",
                    "download",
                    "configure_export",
                    "praxis-table.export.selected-rows@0.1.0",
                    "praxis-table",
                    tableWidgetKey,
                    selectedCandidate,
                    previewPage,
                    tableWidgetSnapshot));
        }
        return replies.size() <= 5 ? replies : List.copyOf(replies.subList(0, 5));
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities(AgenticAuthoringTurnStreamRequest request) {
        if (request != null
                && request.componentCapabilities() != null
                && request.componentCapabilities().catalogs() != null
                && !request.componentCapabilities().catalogs().isEmpty()) {
            return request.componentCapabilities();
        }
        return componentCapabilitiesService == null ? null : componentCapabilitiesService.listCapabilities();
    }

    private AgenticAuthoringQuickReply contextualQuickReply(
            String id,
            String label,
            String prompt,
            String icon,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate,
            JsonNode previewPage,
            JsonNode targetWidgetSnapshot) {
        return contextualQuickReply(
                id,
                label,
                prompt,
                icon,
                changeKind,
                capabilityId,
                componentId,
                widgetKey,
                selectedCandidate,
                previewPage,
                targetWidgetSnapshot,
                null);
    }

    private AgenticAuthoringQuickReply contextualQuickReply(
            String id,
            String label,
            String prompt,
            String icon,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate,
            JsonNode previewPage,
            JsonNode targetWidgetSnapshot,
            JsonNode extraContextHints) {
        ObjectNode hints = objectMapper.createObjectNode();
        hints.put("source", "component-capability-catalog");
        hints.put("kind", "contextual-preview-action");
        hints.put("operationKind", "modify");
        hints.put("artifactKind", contextualArtifactKind(componentId));
        hints.put("changeKind", changeKind);
        hints.put("capabilityId", capabilityId);
        if (StringUtils.hasText(componentId)) {
            hints.put("targetComponentId", componentId);
            hints.put("selectedComponentId", componentId);
        }
        if (StringUtils.hasText(widgetKey)) {
            hints.put("targetWidgetKey", widgetKey);
            hints.put("selectedWidgetKey", widgetKey);
        }
        if (selectedCandidate != null && StringUtils.hasText(selectedCandidate.resourcePath())) {
            hints.put("resourcePath", selectedCandidate.resourcePath());
            hints.put("submitUrl", firstNonBlank(selectedCandidate.submitUrl(), selectedCandidate.resourcePath()));
            hints.put("operation", firstNonBlank(selectedCandidate.operation(), selectedCandidate.submitMethod(), "get"));
            hints.put("submitMethod", firstNonBlank(selectedCandidate.submitMethod(), selectedCandidate.operation(), "get"));
            if (StringUtils.hasText(selectedCandidate.schemaUrl())) {
                hints.put("schemaUrl", selectedCandidate.schemaUrl());
            }
        }
        if (previewPage != null && previewPage.isObject()) {
            hints.set("previewPage", previewPage.deepCopy());
        }
        if (targetWidgetSnapshot != null && targetWidgetSnapshot.isObject()) {
            hints.set("targetWidgetSnapshot", targetWidgetSnapshot.deepCopy());
        }
        if (extraContextHints != null && extraContextHints.isObject()) {
            extraContextHints.fields().forEachRemaining(entry -> hints.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        return new AgenticAuthoringQuickReply(
                id,
                "suggestion",
                label,
                prompt,
                "Ação sugerida a partir das capacidades confirmadas do componente.",
                icon,
                "suggestion",
                hints);
    }

    private String contextualArtifactKind(String componentId) {
        if ("praxis-chart".equals(componentId)) {
            return "chart";
        }
        if ("praxis-table".equals(componentId)) {
            return "table";
        }
        if ("praxis-dynamic-form".equals(componentId)) {
            return "form";
        }
        return "page";
    }

    private JsonNode previewMaterializedPage(AgenticAuthoringPreviewResult preview) {
        if (preview == null) {
            return null;
        }
        JsonNode page = preview.compiledFormPatch().path("patch").path("page");
        if (page.isObject() && page.path("widgets").isArray() && !page.path("widgets").isEmpty()) {
            return page;
        }
        return null;
    }

    private JsonNode previewComponentWidget(JsonNode page, String componentId, String widgetKey) {
        if (page == null || !page.isObject() || !page.path("widgets").isArray() || !StringUtils.hasText(componentId)) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode widget : page.path("widgets")) {
            if (!widget.isObject()) {
                continue;
            }
            String widgetComponentId = firstNonBlank(
                    widget.path("componentId").asText(""),
                    widget.path("definition").path("id").asText(""),
                    widget.path("id").asText(""));
            if (!componentId.equals(widgetComponentId)) {
                continue;
            }
            if (!StringUtils.hasText(widgetKey)) {
                return widget;
            }
            String key = firstNonBlank(
                    widget.path("key").asText(""),
                    widget.path("widgetKey").asText(""),
                    widget.path("id").asText(""));
            if (widgetKey.equals(key)) {
                return widget;
            }
            if (fallback == null) {
                fallback = widget;
            }
        }
        return fallback;
    }

    private boolean hasCapabilityCatalog(
            AgenticAuthoringComponentCapabilitiesResult capabilities,
            String componentId) {
        return capabilities != null
                && capabilities.catalogs() != null
                && capabilities.catalogs().stream()
                .anyMatch(catalog -> componentId.equals(catalog.componentId()));
    }

    private boolean supportsCapability(
            AgenticAuthoringComponentCapabilitiesResult capabilities,
            String componentId,
            String changeKind) {
        return capabilities != null
                && capabilities.catalogs() != null
                && capabilities.catalogs().stream()
                .filter(catalog -> componentId.equals(catalog.componentId()))
                .flatMap(catalog -> catalog.capabilities() == null ? java.util.stream.Stream.empty() : catalog.capabilities().stream())
                .anyMatch(capability -> changeKind.equals(capability.changeKind()));
    }

    private boolean previewContainsComponent(AgenticAuthoringPreviewResult preview, String componentId) {
        return containsText(preview.uiCompositionPlan(), componentId)
                || containsText(preview.compiledFormPatch(), componentId);
    }

    private String previewComponentWidgetKey(AgenticAuthoringPreviewResult preview, String componentId) {
        String fromPlan = componentWidgetKey(preview == null ? null : preview.uiCompositionPlan(), componentId);
        if (StringUtils.hasText(fromPlan)) {
            return fromPlan;
        }
        return componentWidgetKey(preview == null ? null : preview.compiledFormPatch(), componentId);
    }

    private String componentWidgetKey(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(componentId)) {
            return "";
        }
        if (node.isObject()) {
            String nodeComponentId = firstNonBlank(
                    node.path("componentId").asText(""),
                    node.path("definition").path("id").asText(""),
                    node.path("id").asText(""));
            if (componentId.equals(nodeComponentId)) {
                return firstNonBlank(
                        node.path("key").asText(""),
                        node.path("widgetKey").asText(""),
                        node.path("id").asText(""));
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String key = componentWidgetKey(fields.next().getValue(), componentId);
                if (StringUtils.hasText(key)) {
                    return key;
                }
            }
            return "";
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String key = componentWidgetKey(child, componentId);
                if (StringUtils.hasText(key)) {
                    return key;
                }
            }
        }
        return "";
    }

    private boolean containsText(JsonNode node, String expected) {
        if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(expected)) {
            return false;
        }
        if (node.isTextual()) {
            return expected.equals(node.asText());
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (expected.equals(field.getKey()) || containsText(field.getValue(), expected)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsText(child, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AgenticAuthoringTurnStreamRequest withProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (projectKnowledgeService == null || eventSink.terminalReached()) {
            return request;
        }
        if (request.contextHints() != null && request.contextHints().path("projectKnowledge").isObject()) {
            return request;
        }
        AgenticAuthoringProjectKnowledgeQuery query = projectKnowledgeQuery(request, principalContext, intentResolution);
        if (!hasProjectKnowledgeScope(query)) {
            return request;
        }
        emitStatus(
                eventSink,
                "projectKnowledge.retrieve",
                "Estou buscando conhecimento governado do projeto para responder com mais contexto.");
        List<AgenticAuthoringProjectKnowledgeProjection> projections = projectKnowledgeService.retrieve(query);
        if (projections.isEmpty()) {
            Map<String, Object> diagnostics = new LinkedHashMap<>(projectKnowledgeDiagnostics(projections));
            diagnostics.put("result", "empty");
            eventSink.append("thought.step", safeToolProjection(
                    "projectKnowledge.result",
                    "Nao encontrei conhecimento governado adicional do projeto para este planejamento.",
                    diagnostics));
            return request;
        }
        eventSink.append("thought.step", safeToolProjection(
                "projectKnowledge.retrieve",
                "Conhecimento governado do projeto recuperado para apoiar o planejamento.",
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
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private AgenticAuthoringTurnStreamRequest withImplicitChartDetailModalActionContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (!isImplicitChartDetailModalAction(request, intentResolution)) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        if (isContextualPreviewAction(contextHints)) {
            return request;
        }
        AgenticAuthoringTarget target = null;
        if (StringUtils.hasText(request.selectedWidgetKey())) {
            target = currentPageAnalyzer.resolveTarget(request.currentPage(), request.selectedWidgetKey());
        }
        if (target == null || !"praxis-chart".equals(target.componentId())) {
            target = currentPageAnalyzer.resolveFirstComponentTarget(request.currentPage(), "praxis-chart");
        }
        if (target == null) {
            return request;
        }
        JsonNode targetWidgetSnapshot =
                previewComponentWidget(request.currentPage(), "praxis-chart", target.widgetKey());
        AgenticAuthoringCandidate selectedCandidate =
                intentResolution == null ? null : intentResolution.selectedCandidate();

        contextHints.put("source", "component-capability-catalog");
        contextHints.put("kind", "contextual-preview-action");
        contextHints.put("operationKind", "modify");
        contextHints.put("artifactKind", "chart");
        contextHints.put("changeKind", "enable_chart_drilldown");
        contextHints.put("capabilityId", "praxis-chart.drilldown.enable@0.1.0");
        contextHints.put("targetComponentId", "praxis-chart");
        contextHints.put("selectedComponentId", "praxis-chart");
        if (StringUtils.hasText(target.widgetKey())) {
            contextHints.put("targetWidgetKey", target.widgetKey());
            contextHints.put("selectedWidgetKey", target.widgetKey());
        }
        String resourcePath = firstNonBlank(
                selectedCandidate == null ? "" : selectedCandidate.resourcePath(),
                target.resourcePath());
        if (StringUtils.hasText(resourcePath)) {
            contextHints.put("resourcePath", resourcePath);
            contextHints.put("submitUrl", firstNonBlank(
                    selectedCandidate == null ? "" : selectedCandidate.submitUrl(),
                    target.submitUrl(),
                    resourcePath));
            contextHints.put("operation", firstNonBlank(
                    selectedCandidate == null ? "" : selectedCandidate.operation(),
                    target.submitMethod(),
                    "get"));
            contextHints.put("submitMethod", firstNonBlank(
                    selectedCandidate == null ? "" : selectedCandidate.submitMethod(),
                    target.submitMethod(),
                    "get"));
        }
        String schemaUrl = firstNonBlank(
                selectedCandidate == null ? "" : selectedCandidate.schemaUrl(),
                target.schemaUrl());
        if (StringUtils.hasText(schemaUrl)) {
            contextHints.put("schemaUrl", schemaUrl);
        }
        if (request.currentPage() != null && request.currentPage().isObject()) {
            contextHints.set("previewPage", request.currentPage().deepCopy());
        }
        if (targetWidgetSnapshot != null && targetWidgetSnapshot.isObject()) {
            contextHints.set("targetWidgetSnapshot", targetWidgetSnapshot.deepCopy());
        }
        contextHints.put("surfacePresentation", "modal");
        contextHints.put("surfaceActionId", "surface.open");
        contextHints.put("surfaceWidgetId", "praxis-table");
        return copyWithContextHints(request, contextHints);
    }

    private boolean isImplicitChartDetailModalAction(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (request == null
                || intentResolution == null
                || !"modify".equals(safeText(intentResolution.operationKind()))
                || !"enable_chart_drilldown".equals(safeText(intentResolution.changeKind()))) {
            return false;
        }
        String targetComponentId = firstNonBlank(
                intentResolution.target() == null ? "" : intentResolution.target().componentId(),
                intentResolution.targetComponentId());
        if (StringUtils.hasText(targetComponentId)
                && !"praxis-chart".equals(targetComponentId)
                && !"praxis-dynamic-page-builder".equals(targetComponentId)) {
            return false;
        }
        String prompt = normalizeText(firstNonBlank(request.userPrompt(), intentResolution.effectivePrompt()));
        return containsAny(prompt, "modal", "dialogo", "dialog", "janela", "popup")
                && containsAny(prompt, "detalhe", "detalhes", "registro", "registros", "categoria selecionada");
    }

    private AgenticAuthoringTurnStreamRequest copyWithContextHints(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode contextHints) {
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
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
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
        return decisionDiagnostics(intentResolution, preview, toolLoopResult, null);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-decision-diagnostics.v1");
        if (intentResolution == null) {
            diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.NONE);
            diagnostics.put("llmResolutionAttempted", false);
            diagnostics.put("llmResolved", false);
            diagnostics.put("keywordFallbackApplied", false);
            diagnostics.put("semanticPolicyApplied", false);
            diagnostics.put("selectedCandidateUsesLexicalFallback", false);
            diagnostics.put("selectedCandidateUsesBroadArtifactDiscovery", false);
            diagnostics.put("selectedCandidateUsesDomainAnchor", false);
            diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
            diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
            diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
            diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
            diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(preview, false));
            putToolLoopDiagnostics(diagnostics, toolLoopResult);
            putAuthoringEvidenceDiagnostics(diagnostics, request);
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
                        && !Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"));
        diagnostics.put("selectedCandidateUsesLexicalFallback", selectedCandidateUsesLexicalFallback);
        boolean selectedCandidateUsesBroadArtifactDiscovery =
                telemetry.path("selectedCandidateUsesBroadArtifactDiscovery").asBoolean(false)
                        && !previewResourceSchemaVerified(preview);
        diagnostics.put("selectedCandidateUsesBroadArtifactDiscovery", selectedCandidateUsesBroadArtifactDiscovery);
        diagnostics.put("selectedCandidateUsesDomainAnchor",
                telemetry.path("selectedCandidateUsesDomainAnchor").asBoolean(false));
        diagnostics.put("candidateSetContainsLexicalFallback",
                telemetry.path("candidateSetContainsLexicalFallback").asBoolean(false));
        diagnostics.put("candidateSetContainsBroadArtifactDiscovery",
                telemetry.path("candidateSetContainsBroadArtifactDiscovery").asBoolean(false));
        diagnostics.put("candidateSetContainsDomainAnchor",
                telemetry.path("candidateSetContainsDomainAnchor").asBoolean(false));
        diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
        diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
        diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
        diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
        boolean previewResourceSchemaVerified = previewResourceSchemaVerified(preview);
        diagnostics.put("previewResourceSchemaVerified", previewResourceSchemaVerified);
        diagnostics.put("selectedResourceSchemaGroundingMissing",
                selectedResourceRequiresSchemaGrounding(selectedCandidate, preview) && !previewResourceSchemaVerified);
        diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(
                preview,
                Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"))));
        putToolLoopDiagnostics(diagnostics, toolLoopResult);
        putAuthoringEvidenceDiagnostics(diagnostics, request);
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

    private void putAuthoringEvidenceDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringTurnStreamRequest request) {
        JsonNode evidenceContext = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints().path("authoringEvidence");
        JsonNode evidence = evidenceContext.path("evidence");
        diagnostics.put("authoringEvidenceCount", evidence.isArray() ? evidence.size() : 0);
        diagnostics.put("authoringEvidenceSourceRefs", sourceRefs(evidence));
    }

    private List<String> sourceRefs(JsonNode evidence) {
        if (evidence == null || !evidence.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode item : evidence) {
            String sourceRef = safeText(item.path("sourceRef").asText(""));
            if (!sourceRef.isBlank() && !refs.contains(sourceRef)) {
                refs.add(sourceRef);
            }
            if (refs.size() >= 6) {
                break;
            }
        }
        return refs;
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

    private boolean selectedResourceRequiresSchemaGrounding(
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringPreviewResult preview) {
        if (selectedCandidate == null
                || !StringUtils.hasText(selectedCandidate.resourcePath())
                || preview == null
                || preview.uiCompositionPlan() == null) {
            return false;
        }
        return hasEvidence(selectedCandidate, "semantic-retrieval")
                || hasEvidence(selectedCandidate, "tool-search-api-resources")
                || hasEvidence(selectedCandidate, "api-metadata")
                || hasEvidence(selectedCandidate, "schema-available");
    }

    private boolean semanticDecisionReviewGroundedByPreview(
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringPreviewResult preview) {
        if (semanticDecision == null || !semanticDecision.reviewRequired() || !previewResourceSchemaVerified(preview)) {
            return false;
        }
        String reason = safeText(semanticDecision.reviewReason());
        if ("prompt-alignment-selection".equals(reason)) {
            AgenticAuthoringSemanticDecision.RetrievalEvidence retrievalEvidence = semanticDecision.retrievalEvidence();
            return retrievalEvidence != null
                    && retrievalEvidence.evidence() != null
                    && (retrievalEvidence.evidence().contains("tool-search-api-resources")
                    || "semantic_retrieval".equals(safeText(retrievalEvidence.retrievalSource())));
        }
        if ("keyword-fallback-fail-safe".equals(reason)) {
            AgenticAuthoringSemanticDecision.RetrievalEvidence retrievalEvidence = semanticDecision.retrievalEvidence();
            boolean governedEvidence = retrievalEvidence != null
                    && retrievalEvidence.evidence() != null
                    && (retrievalEvidence.evidence().contains("tool-search-api-resources")
                    || "semantic_retrieval".equals(safeText(retrievalEvidence.retrievalSource())));
            return governedEvidence
                    || semanticDecision.refinement() != null
                    && semanticDecision.refinement().preservesResource()
                    && ("current-page-bound-resource".equals(safeText(semanticDecision.previousDecisionRef()))
                            || !safeText(semanticDecision.refinementOf()).isBlank()
                            || !safeText(semanticDecision.previousDecisionId()).isBlank());
        }
        return false;
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
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
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
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesBroadArtifactDiscovery"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))
                || Boolean.TRUE.equals(diagnostics.get("selectedResourceSchemaGroundingMissing"))
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
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesBroadArtifactDiscovery"))) {
            return "broad-artifact-discovery-requires-grounding";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))) {
            return "resource-selection-domain-anchor";
        }
        if (Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanUsesHardcodedAnchor"))) {
            return "ui-composition-hardcoded-reference-provider";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedResourceSchemaGroundingMissing"))) {
            return "resource-schema-grounding-required";
        }
        if (Boolean.FALSE.equals(diagnostics.get("decisionValid"))) {
            return "semantic-preview-materialization-mismatch";
        }
        if (Boolean.FALSE.equals(diagnostics.get("toolLoopCompleted"))) {
            String reason = safeText((String) diagnostics.get("toolLoopTerminalReason"));
            return reason.isBlank() ? "agentic-tool-loop-incomplete" : "agentic-tool-loop-" + reason;
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
        eventSink.append("thought.step", thoughtStepPayload(
                "preview.compile",
                repairedPreview.valid()
                        ? "O reparo do backend gerou uma pre-visualizacao valida para revisao."
                        : "A tentativa de reparo terminou, mas ainda nao gerou uma pre-visualizacao valida.",
                repairedPreview.valid()
                        ? "Compiled preview payload after backend repair."
                        : "Preview repair attempt completed without a valid payload.",
                safePreviewDiagnostics(intentResolution, repairedPreview, true)));
        return repairedPreview;
    }

    private boolean needsResourceDiscovery(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || hasToolDiscoveredCandidates(intentResolution)) {
            return false;
        }
        return contains(intentResolution.failureCodes(), "resource-candidate-required")
                || (intentResolution.gate() != null
                && contains(intentResolution.gate().messages(), "resource-candidate-required"));
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
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "evidenceCount");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "artifactKind");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "componentId");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "chunkKind");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "releaseId");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalQuery");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalSource");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "resourceDiscoveryDiagnostics");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "sourceRefs");
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
        if (discovery.resourceSearchFocus() != null && !discovery.resourceSearchFocus().isEmpty()) {
            resourceDiscovery.set("resourceSearchFocus", resourceSearchFocusNode(discovery.resourceSearchFocus()));
        }
        ArrayNode candidates = resourceDiscovery.putArray("candidates");
        for (AgenticAuthoringCandidate candidate : discovery.candidates()) {
            candidates.add(candidateContext(candidate));
        }
        if (discovery.consultativeProjection() != null && discovery.consultativeProjection().hasResources()) {
            resourceDiscovery.set(
                    "consultativeProjection",
                    objectMapper.valueToTree(discovery.consultativeProjection()));
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
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private ObjectNode resourceSearchFocusNode(AgenticAuthoringResourceSearchFocus focus) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("primaryBusinessEntity", safeText(focus.primaryBusinessEntity()));
        ArrayNode supportingConcepts = node.putArray("supportingConcepts");
        for (String concept : focus.supportingConcepts()) {
            if (StringUtils.hasText(concept)) {
                supportingConcepts.add(concept.trim());
            }
        }
        node.put("desiredSurface", safeText(focus.desiredSurface()));
        node.put("uncertainty", safeText(focus.uncertainty()));
        node.put("rationale", safeText(focus.rationale()));
        return node;
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
        if (candidate.evidenceBundle() != null) {
            node.set("evidenceBundle", objectMapper.valueToTree(candidate.evidenceBundle()));
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
                activeDecision,
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
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
            eventSink.append("thought.step", thoughtStepPayload(
                    "resource.discovery",
                    "Encontrei candidatos governados no catalogo do backend.",
                    "Resource candidates were retrieved from the backend catalog.",
                    resourceDiscoveryDiagnostics(intentResolution)));
        } else if (contains(intentResolution.failureCodes(), "resource-candidate-ambiguous")) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "resource.discovery",
                    "Encontrei mais de uma fonte governada possivel e vou manter a escolha para revisao.",
                    "Resource candidates returned for user selection.",
                    resourceDiscoveryDiagnostics(intentResolution)));
        }
        if (contains(intentResolution.warnings(), "llm-intent-resolution-second-pass-used")) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve.llm",
                    "A LLM revisou os candidatos recuperados pelo backend.",
                    "The LLM reviewed refined backend resource candidates.",
                    secondPassDiagnostics(intentResolution)));
        }
    }

    private void emitIntentResolved(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (eventSink == null || eventSink.terminalReached() || intentResolution == null) {
            return;
        }
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "praxis-agentic-authoring-intent-resolved-event.v1");
        payload.put("semanticDecisionRef", semanticDecision == null ? "" : safeText(semanticDecision.decisionId()));
        payload.put("routeClass", route == null ? "" : safeText(route.routeClass()));
        payload.put("resolved", intentResolution.valid());
        payload.put("userFacingUnderstanding", intentResolvedUserFacingUnderstanding(intentResolution, route));
        payload.put("requiresClarification", route != null && "needs_clarification".equals(route.routeClass()));
        payload.put("canMaterialize", route != null && route.allowsPreview() && intentResolution.valid());
        payload.put("fallbackKind", intentResolvedFallbackKind(intentResolution));
        payload.put("requiredTools", intentResolvedRequiredTools(intentResolution, route));
        payload.put("evidenceRefs", intentResolvedEvidenceRefs(intentResolution));
        payload.put("confidence", semanticDecision == null || semanticDecision.confidence() == null
                ? 0.0d
                : semanticDecision.confidence());
        payload.put("warnings", intentResolution.warnings() == null ? List.of() : intentResolution.warnings());
        eventSink.append("intent.resolved", payload);
    }

    private String intentResolvedUserFacingUnderstanding(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        String assistantMessage = safeText(intentResolution.assistantMessage());
        if (!assistantMessage.isBlank()) {
            return curatedResourceLabel(publicAssistantMessage(assistantMessage), intentResolution.selectedCandidate());
        }
        String routeClass = route == null ? "" : safeText(route.routeClass());
        String operation = safeText(intentResolution.operationKind());
        String artifact = safeText(intentResolution.artifactKind());
        String change = safeText(intentResolution.changeKind());
        if ("needs_clarification".equals(routeClass)) {
            return "Entendi que preciso confirmar alguns detalhes antes de criar ou alterar algo.";
        }
        if ("advisory_authoring".equals(routeClass)) {
            return "Entendi que voce quer uma resposta consultiva antes de materializar uma tela.";
        }
        if ("shared_rule_authoring".equals(routeClass) || "mixed".equals(routeClass)) {
            return "Entendi que o pedido envolve regra compartilhada e precisa seguir a governanca apropriada.";
        }
        return "Entendi a intencao como " + nonBlank(operation, "authoring")
                + " de " + nonBlank(artifact, "componente")
                + (change.isBlank() ? "." : " para " + change + ".");
    }

    private String curatedResourceLabel(String message, AgenticAuthoringCandidate candidate) {
        String value = safeText(message);
        if (value.isBlank() || candidate == null) {
            return value;
        }
        String fallbackLabel = AgenticAuthoringResourcePresentationLabel.fromResourcePath(candidate.resourcePath());
        String governedLabel = AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
        if (fallbackLabel.isBlank()
                || governedLabel.isBlank()
                || fallbackLabel.equals(governedLabel)
                || "o recurso selecionado".equals(fallbackLabel)) {
            return value;
        }
        return Pattern.compile(Pattern.quote(fallbackLabel), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(value)
                .replaceAll(Matcher.quoteReplacement(governedLabel));
    }

    private String intentResolvedFallbackKind(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return "";
        }
        JsonNode telemetry = intentResolution.llmDiagnostics() == null
                ? null
                : intentResolution.llmDiagnostics().path("resolutionTelemetry");
        String fallbackPolicy = telemetry == null ? "" : safeText(telemetry.path("fallbackPolicy").asText(""));
        if (!fallbackPolicy.isBlank()) {
            return fallbackPolicy;
        }
        if (contains(intentResolution.warnings(), "llm-provider-error")) {
            return "provider_error";
        }
        if (contains(intentResolution.warnings(), "keyword-fallback-applied")
                || contains(intentResolution.warnings(), "keyword-fallback-fail-safe-applied")) {
            return "deterministic_fallback";
        }
        return "";
    }

    private List<String> intentResolvedRequiredTools(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (route == null) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (needsResourceDiscovery(intentResolution)) {
            tools.add(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
        }
        if (route.allowsPreview()) {
            tools.add("preview.materialization");
        }
        if ("advisory_authoring".equals(route.routeClass())) {
            tools.add("consultative.answer");
        }
        return List.copyOf(new LinkedHashSet<>(tools));
    }

    private List<Map<String, Object>> intentResolvedEvidenceRefs(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        List<Map<String, Object>> refs = new ArrayList<>();
        AgenticAuthoringCandidate selectedCandidate =
                intentResolution == null ? null : intentResolution.selectedCandidate();
        if (selectedCandidate != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("kind", "selectedResource");
            ref.put("resourcePath", safeText(selectedCandidate.resourcePath()));
            ref.put("operation", safeText(selectedCandidate.operation()));
            ref.put("score", selectedCandidate.score());
            ref.put("evidence", selectedCandidate.evidence() == null ? List.of() : selectedCandidate.evidence());
            refs.add(ref);
        }
        AgenticAuthoringSemanticDecision semanticDecision =
                intentResolution == null ? null : intentResolution.semanticDecision();
        if (semanticDecision != null && semanticDecision.retrievedEvidence() != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("kind", "retrievedEvidence");
            ref.put("retrievalSource", safeText(semanticDecision.retrievedEvidence().retrievalSource()));
            ref.put("evidenceCount", semanticDecision.retrievedEvidence().evidence().size());
            refs.add(ref);
        }
        return refs;
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
            String userPrompt,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            String schemaBaseUrl) {
        String catalogMessage = consultativeCatalogAssistantMessage(
                userPrompt,
                intentResolution,
                resourceDiscovery,
                businessCatalogDiscovery,
                schemaBaseUrl);
        if (!catalogMessage.isBlank()) {
            return catalogMessage;
        }
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        return !message.isBlank() ? message : safeText(intentResolution.assistantMessage());
    }

    private String consultativeCatalogAssistantMessage(
            String userPrompt,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            String schemaBaseUrl) {
        if (isPlatformGuidancePrompt(intentResolution == null ? "" : intentResolution.effectivePrompt())) {
            return safeText(intentResolution == null ? "" : intentResolution.assistantMessage());
        }
        if (!isAdvisoryCatalogIntent(intentResolution)) {
            if (!isUnresolvedAdvisoryIntent(intentResolution)
                    || !hasDiscoveryCandidates(resourceDiscovery, businessCatalogDiscovery)) {
                return "";
            }
        }
        String projectionMessage = consultativeProjectionAssistantMessage(
                firstNonBlank(userPrompt, intentResolution == null ? "" : intentResolution.effectivePrompt()),
                businessCatalogDiscovery,
                resourceDiscovery);
        if (!projectionMessage.isBlank()) {
            return projectionMessage;
        }
        List<AgenticAuthoringCandidate> candidates = catalogCandidates(resourceDiscovery, businessCatalogDiscovery, intentResolution);
        if (candidates.isEmpty()) {
            return "Ainda nao encontrei fontes de dados governadas suficientes para responder com seguranca. "
                    + "Posso tentar de novo com um recorte de area, entidade ou processo mais especifico.";
        }
        List<CatalogSchemaSummary> schemaSummaries = candidates.stream()
                .limit(4)
                .map(candidate -> catalogSchemaSummary(candidate, schemaBaseUrl))
                .toList();
        List<CatalogSchemaSummary> confirmedSchemas = schemaSummaries.stream()
                .filter(summary -> summary.schemaConfirmed() && !summary.fields().isEmpty())
                .toList();
        String sources = humanJoin(candidates.stream()
                .limit(4)
                .map(this::businessResourceLabel)
                .filter(label -> !label.isBlank())
                .distinct()
                .toList());
        if (sources.isBlank()) {
            sources = "fontes governadas do catalogo";
        }
        if (confirmedSchemas.isEmpty()) {
            return "Encontrei dados em " + sources + ", mas ainda nao consegui confirmar os campos disponiveis. "
                    + "Por enquanto, consigo explicar as fontes disponiveis e recomendar telas em nivel de negocio; "
                    + "antes de materializar graficos, tabelas ou formularios, preciso validar os campos da fonte escolhida.";
        }
        StringBuilder message = new StringBuilder();
        message.append("Encontrei dados governados em ").append(sources).append(". ");
        message.append("Pelos campos confirmados, da para trabalhar com ");
        message.append(confirmedSchemas.stream()
                .map(summary -> schemaBusinessSummary(summary.label(), summary.fields()))
                .limit(3)
                .reduce((left, right) -> left + "; " + right)
                .orElse(""));
        message.append(". ");
        message.append("Eu recomendaria ").append(screenRecommendations(candidates, confirmedSchemas));
        message.append(". Quando voce pedir para criar, eu materializo usando apenas campos confirmados.");
        return message.toString();
    }

    private String consultativeProjectionAssistantMessage(
            String userPrompt,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery) {
        AgenticAuthoringConsultativeApiCatalogProjection projection =
                businessCatalogDiscovery == null ? null : businessCatalogDiscovery.consultativeProjection();
        if (projection == null || !projection.hasResources()) {
            projection = resourceDiscovery == null ? null : resourceDiscovery.consultativeProjection();
        }
        if (projection == null || !projection.hasResources()) {
            return "";
        }
        String unsupportedDomainMessage = AgenticAuthoringConsultativeGroundingAlignment.unsupportedDomainMessage(
                userPrompt,
                projection.resources());
        if (StringUtils.hasText(unsupportedDomainMessage)) {
            return unsupportedDomainMessage;
        }
        return safeText(projection.assistantMessage());
    }

    private JsonNode assistantContent(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery) {
        if (intentResolution != null && intentResolution.assistantContent() != null) {
            return intentResolution.assistantContent();
        }
        JsonNode content = businessCatalogDiscovery == null ? null : businessCatalogDiscovery.assistantContent();
        if (content != null) {
            return content;
        }
        return resourceDiscovery == null ? null : resourceDiscovery.assistantContent();
    }

    private List<AgenticAuthoringCandidate> catalogCandidates(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        addCandidates(candidates, businessCatalogDiscovery == null ? null : businessCatalogDiscovery.candidates());
        addCandidates(candidates, resourceDiscovery == null ? null : resourceDiscovery.candidates());
        if (intentResolution != null) {
            addCandidates(candidates, intentResolution.candidates());
            if (intentResolution.selectedCandidate() != null) {
                addCandidates(candidates, List.of(intentResolution.selectedCandidate()));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        return candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.resourcePath()))
                .filter(candidate -> seen.add(normalizeText(candidate.resourcePath())))
                .toList();
    }

    private void addCandidates(List<AgenticAuthoringCandidate> target, List<AgenticAuthoringCandidate> candidates) {
        if (target == null || candidates == null) {
            return;
        }
        candidates.stream()
                .filter(Objects::nonNull)
                .forEach(target::add);
    }

    private CatalogSchemaSummary catalogSchemaSummary(AgenticAuthoringCandidate candidate, String schemaBaseUrl) {
        String label = businessResourceLabel(candidate);
        if (schemaRetrievalService == null) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        AiSchemaContext context = schemaContext(candidate);
        if (context == null) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(context, schemaBaseUrl);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        return new CatalogSchemaSummary(label, true, schemaFieldLabels(schemaResult.getSchema()));
    }

    private AiSchemaContext schemaContext(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        Map<String, String> query = queryParameters(candidate.schemaUrl());
        String path = firstNonBlank(query.get("path"), candidate.submitUrl(), candidate.resourcePath());
        String operation = firstNonBlank(query.get("operation"), candidate.submitMethod(), candidate.operation(), "get");
        String schemaType = firstNonBlank(query.get("schemaType"), "response");
        String businessPath = businessResourcePath(firstNonBlank(path, candidate.submitUrl(), candidate.resourcePath()));
        if (isStatsPath(path) || isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            path = businessPath + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        if (!businessPath.isBlank() && normalizeText(path).equals(normalizeText(businessPath))
                && "get".equalsIgnoreCase(operation)) {
            path = businessPath + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        if (path.isBlank() || operation.isBlank() || schemaType.isBlank()) {
            return null;
        }
        return AiSchemaContext.builder()
                .path(path)
                .operation(operation)
                .schemaType(schemaType)
                .build();
    }

    private Map<String, String> queryParameters(String url) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String value = safeText(url);
        int queryIndex = value.indexOf('?');
        if (queryIndex < 0 || queryIndex == value.length() - 1) {
            return parameters;
        }
        for (String pair : value.substring(queryIndex + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String parameterValue = equals >= 0 ? pair.substring(equals + 1) : "";
            parameters.put(urlDecode(key), urlDecode(parameterValue));
        }
        return parameters;
    }

    private String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(safeText(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return safeText(value);
        }
    }

    private String businessResourcePath(String path) {
        String value = safeText(path).trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/filter/cursor")) {
            return value.substring(0, value.length() - "/filter/cursor".length());
        }
        if (value.endsWith("/stats")) {
            return value.substring(0, value.length() - "/stats".length());
        }
        return value;
    }

    private boolean isStatsPath(String path) {
        String normalized = safeText(path).toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("/stats");
    }

    private List<String> schemaFieldLabels(JsonNode schema) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        collectSchemaFieldLabels(schema, labels);
        return labels.stream().limit(6).toList();
    }

    private void collectSchemaFieldLabels(JsonNode node, Set<String> labels) {
        if (node == null || node.isMissingNode() || node.isNull() || labels.size() >= 8) {
            return;
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                if (labels.size() >= 8 || shouldHideField(entry.getKey(), entry.getValue())) {
                    return;
                }
                String label = firstNonBlank(
                        entry.getValue().path("x-ui").path("label").asText(""),
                        humanizeToken(entry.getKey()));
                if (!label.isBlank()) {
                    labels.add(label);
                }
            });
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSchemaFieldLabels(entry.getValue(), labels));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectSchemaFieldLabels(item, labels);
            }
        }
    }

    private boolean shouldHideField(String fieldName, JsonNode field) {
        JsonNode ui = field == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : field.path("x-ui");
        String label = normalizeText(firstNonBlank(
                ui.path("label").asText(""),
                field == null ? "" : field.path("title").asText("")));
        String normalizedFieldName = normalizeText(humanizeToken(fieldName));
        return ui.path("tableHidden").asBoolean(false)
                || ui.path("formHidden").asBoolean(false)
                || "password".equalsIgnoreCase(ui.path("controlType").asText(""))
                || isTechnicalIdentifier(normalizedFieldName)
                || Set.of(
                        "id",
                        "uuid",
                        "created at",
                        "updated at",
                        "created by",
                        "updated by",
                        "field",
                        "granularity",
                        "metric",
                        "metrics",
                        "points",
                        "operation",
                        "alias",
                        "start",
                        "end")
                .contains(label);
    }

    private boolean isTechnicalIdentifier(String value) {
        String normalized = safeText(value);
        return "id".equals(normalized)
                || "uuid".equals(normalized)
                || normalized.endsWith(" id")
                || normalized.endsWith(" uuid");
    }

    private String schemaBusinessSummary(String label, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return safeText(label);
        }
        return safeText(label) + " cobrindo " + humanJoin(fields);
    }

    private String screenRecommendations(
            List<AgenticAuthoringCandidate> candidates,
            List<CatalogSchemaSummary> confirmedSchemas) {
        List<String> recommendations = new ArrayList<>();
        recommendations.add("uma lista com filtros e detalhe para consulta operacional");
        if (hasCandidate(candidates, "analytics")
                || hasCandidate(candidates, "analit")
                || hasCandidate(candidates, "indicador")
                || hasCandidate(candidates, "folha")) {
            recommendations.add("um dashboard para acompanhar indicadores e distribuicoes confirmadas pelo schema");
        }
        if (hasCandidate(candidates, "historico")
                || hasCandidate(candidates, "eventos")
                || hasCandidate(candidates, "history")
                || hasField(confirmedSchemas, "data inicio")
                || hasField(confirmedSchemas, "data fim")) {
            recommendations.add("uma aba de historico ou linha do tempo para mudancas e eventos");
        }
        if (hasCandidate(candidates, "cargo")
                || hasCandidate(candidates, "departamento")
                || hasField(confirmedSchemas, "cargo")
                || hasField(confirmedSchemas, "departamento")) {
            recommendations.add("visoes de apoio para cargo, departamento e segmentacao");
        }
        return humanJoin(recommendations);
    }

    private boolean hasField(List<CatalogSchemaSummary> summaries, String token) {
        String normalizedToken = normalizeText(token);
        return summaries != null && summaries.stream()
                .filter(Objects::nonNull)
                .flatMap(summary -> summary.fields() == null ? java.util.stream.Stream.empty() : summary.fields().stream())
                .anyMatch(field -> normalizeText(field).contains(normalizedToken));
    }

    private String businessResourceLabel(AgenticAuthoringCandidate candidate) {
        String path = businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        if (path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return humanizeToken(last.replace("vw-", ""));
    }

    private String humanizeToken(String value) {
        String[] parts = safeText(value)
                .replace('_', '-')
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .split("-+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(part.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                    + part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return String.join(" ", words);
    }

    private String humanJoin(List<String> values) {
        List<String> clean = values == null
                ? List.of()
                : values.stream().filter(StringUtils::hasText).distinct().toList();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.size() == 1) {
            return clean.get(0);
        }
        if (clean.size() == 2) {
            return clean.get(0) + " e " + clean.get(1);
        }
        return String.join(", ", clean.subList(0, clean.size() - 1)) + " e " + clean.get(clean.size() - 1);
    }

    private boolean hasCandidate(List<AgenticAuthoringCandidate> candidates, String token) {
        String normalizedToken = normalizeText(token);
        return candidates != null && candidates.stream()
                .anyMatch(candidate -> normalizeText(candidate.resourcePath()).contains(normalizedToken));
    }

    private String groundedPreviewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        if (!message.isBlank()
                && !message.contains("revisao de governanca")
                && !isGenericPreviewReadyMessage(message)) {
            return message;
        }
        String artifactKind = intentResolution == null ? "" : safeText(intentResolution.artifactKind());
        if ("dashboard".equals(artifactKind)) {
            return "Montei uma primeira versao de dashboard com a fonte confirmada. "
                    + "Ela ja inclui grafico, filtros, KPIs e tabela de detalhe conectada; revise a pre-visualizacao "
                    + "e salve quando estiver de acordo.";
        }
        return "Montei uma primeira versao com a fonte confirmada. "
                + "Revise o resultado e salve quando estiver de acordo.";
    }

    private boolean isGenericPreviewReadyMessage(String message) {
        String normalized = normalizeText(message).replaceAll("[^a-z0-9]+", " ").trim();
        return "preview ready".equals(normalized)
                || "preview applied to the page".equals(normalized)
                || "pre visualizacao pronta".equals(normalized);
    }

    private boolean isAdvisoryCatalogIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        String operationKind = safeText(intentResolution.operationKind());
        String artifactKind = safeText(intentResolution.artifactKind());
        String changeKind = safeText(intentResolution.changeKind());
        return ("explore".equals(operationKind) || "explain".equals(operationKind))
                && "api_catalog".equals(artifactKind)
                && ("answer_api_catalog_question".equals(changeKind)
                || "answer_catalog_question".equals(changeKind)
                || "api_catalog_followup".equals(changeKind));
    }

    private boolean isPlatformGuidancePrompt(String prompt) {
        String normalized = normalizeText(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "o que posso", "o que consigo", "o que da para", "o que dá para",
                "o que voce pode", "o que você pode", "como faco", "como faço",
                "como criar", "como montar", "quais componentes", "que componentes",
                "quais widgets", "que widgets", "quais telas", "que telas",
                "quais paginas", "que paginas", "posso criar", "daria para criar",
                "da para criar", "dá para criar", "posso montar", "posso fazer");
        boolean platformSubject = containsAny(normalized,
                "aqui", "praxis", "page builder", "builder", "assistente",
                "componente", "componentes", "widget", "widgets",
                "tela", "telas", "pagina", "paginas", "dashboard", "painel",
                "formulario", "formulário", "tabela", "grafico", "gráfico",
                "aba", "abas", "tabs", "stepper", "administrativo", "admin",
                "livremente", "predefinido", "predefinidos", "pre definido", "pre definidos");
        boolean dataCatalogSubject = containsAny(normalized,
                "dados", "fonte", "fontes", "api", "apis", "schema", "schemas",
                "campo", "campos", "recurso", "recursos", "entidade", "entidades");
        boolean formPolicySubject = containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
        return question && platformSubject && (!dataCatalogSubject || formPolicySubject);
    }

    private boolean isUnresolvedAdvisoryIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        String operationKind = safeText(intentResolution.operationKind());
        String artifactKind = safeText(intentResolution.artifactKind());
        return !intentResolution.valid()
                || "unknown".equals(operationKind)
                || "unknown".equals(artifactKind);
    }

    private boolean hasDiscoveryCandidates(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        return (resourceDiscovery != null
                && resourceDiscovery.candidates() != null
                && !resourceDiscovery.candidates().isEmpty())
                || (businessCatalogDiscovery != null
                && businessCatalogDiscovery.candidates() != null
                && !businessCatalogDiscovery.candidates().isEmpty());
    }

    private String normalizeText(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> safeDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return Map.of(
                "targetApp", nonBlank(request.targetApp(), ""),
                "targetComponentId", nonBlank(request.targetComponentId(), ""),
                "selectedWidgetKey", nonBlank(request.selectedWidgetKey(), ""),
                "hasContextHints", request.contextHints() != null && !request.contextHints().isNull(),
                "runtimeComponentObservationCount", request.runtimeComponentObservations() == null
                        ? 0
                        : request.runtimeComponentObservations().size(),
                "hasGroundedRuntimeComponentContext", request.contextHints() != null
                        && request.contextHints().path("groundedRuntimeComponentContext").isObject(),
                "hasActiveSemanticDecision", request.activeSemanticDecision() != null,
                "componentCapabilityCatalogs", request.componentCapabilities() != null
                        && request.componentCapabilities().catalogs() != null
                        ? request.componentCapabilities().catalogs().size()
                        : 0);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    private String publicAssistantMessage(String value) {
        return AgenticAuthoringPresentationText.assistantReply(safeText(value));
    }

    private String presentationText(String value) {
        return AgenticAuthoringPresentationText.display(safeText(value));
    }

    private String toSnippet(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 497) + "...";
    }

    record CatalogSchemaSummary(String label, boolean schemaConfirmed, List<String> fields) {
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
