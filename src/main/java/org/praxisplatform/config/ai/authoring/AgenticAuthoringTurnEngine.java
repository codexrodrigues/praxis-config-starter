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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnAdvisorySupport.AgenticAuthoringTurnRoute;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnLiveOptionGroundingSupport.LiveOptionFieldGroundingExecution;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnLiveOptionGroundingSupport.LiveOptionGroundingExecution;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnLiveOptionGroundingSupport.StaticEnumFilterGroundingExecution;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.service.LiveOptionValueCandidate;
import org.praxisplatform.config.service.LiveOptionValueRetrievalResult;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.springframework.util.StringUtils;

@Slf4j
public class AgenticAuthoringTurnEngine {

    private static final int MAX_TOOL_CALLS_PER_TURN = 1;
    private static final int MAX_REPAIR_ATTEMPTS_PER_PHASE = 1;
    private static final int MAX_PROJECT_KNOWLEDGE_RESOURCE_SCOPES = 6;
    private static final int MAX_PROJECT_KNOWLEDGE_INFLUENCES = 8;
    private static final int PROJECT_KNOWLEDGE_PER_RESOURCE_LIMIT = 4;
    private static final long DEFAULT_COMPONENT_CAPABILITIES_PRELOAD_TIMEOUT_MS = 35_000L;
    private static final AgenticAuthoringComponentCapabilitiesService FALLBACK_COMPONENT_CAPABILITIES_SERVICE =
            createWarmFallbackComponentCapabilitiesService();

    private static AgenticAuthoringComponentCapabilitiesService createWarmFallbackComponentCapabilitiesService() {
        AgenticAuthoringComponentCapabilitiesService service = new AgenticAuthoringComponentCapabilitiesService();
        service.listCapabilities();
        return service;
    }

    private final AgenticAuthoringIntentResolverService intentResolverService;
    private final AgenticAuthoringPreviewService previewService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringToolRegistry toolRegistry;
    private final AgenticAuthoringProjectKnowledgeService projectKnowledgeService;
    private final AgenticAuthoringOrchestrator orchestrator;
    private final SchemaRetrievalService schemaRetrievalService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final AgenticAuthoringComponentDiscoveryService componentDiscoveryService =
            new AgenticAuthoringComponentDiscoveryService();
    private final AgenticAuthoringConsultativeAnswerService consultativeAnswerService;
    private final AgenticAuthoringPreIntentToolPlanningService preIntentToolPlanningService;
    private final AgenticAuthoringPersistedUiCompositionSourceResolver persistedUiCompositionSourceResolver;
    private final long componentCapabilitiesPreloadTimeoutMs;
    private final AgenticAuthoringRuntimeComponentGroundingService runtimeComponentGroundingService;
    private final AgenticAuthoringTurnAdvisorySupport advisorySupport;
    private final AgenticAuthoringTurnDecisionProjectionSupport decisionProjectionSupport;
    private final AgenticAuthoringTurnLiveOptionGroundingSupport liveOptionGroundingSupport;
    private final AgenticAuthoringTurnConsultativeQuickReplySupport consultativeQuickReplySupport;
    private final AgenticAuthoringTurnPreIntentExecutionSupport preIntentExecutionSupport;
    private final AgenticAuthoringTurnEventDiagnosticsSupport eventDiagnosticsSupport =
            new AgenticAuthoringTurnEventDiagnosticsSupport();

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
                preIntentToolPlanningService,
                DEFAULT_COMPONENT_CAPABILITIES_PRELOAD_TIMEOUT_MS);
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
            AgenticAuthoringPreIntentToolPlanningService preIntentToolPlanningService,
            long componentCapabilitiesPreloadTimeoutMs) {
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
                preIntentToolPlanningService,
                componentCapabilitiesPreloadTimeoutMs,
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
            AgenticAuthoringPreIntentToolPlanningService preIntentToolPlanningService,
            long componentCapabilitiesPreloadTimeoutMs,
            AgenticAuthoringPersistedUiCompositionSourceResolver persistedUiCompositionSourceResolver) {
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
        this.persistedUiCompositionSourceResolver = persistedUiCompositionSourceResolver;
        this.componentCapabilitiesPreloadTimeoutMs = Math.max(1L, componentCapabilitiesPreloadTimeoutMs);
        this.runtimeComponentGroundingService = new AgenticAuthoringRuntimeComponentGroundingService(objectMapper);
        this.advisorySupport = new AgenticAuthoringTurnAdvisorySupport(schemaRetrievalService);
        this.decisionProjectionSupport =
                new AgenticAuthoringTurnDecisionProjectionSupport(objectMapper, this.advisorySupport);
        this.liveOptionGroundingSupport =
                new AgenticAuthoringTurnLiveOptionGroundingSupport(objectMapper, toolRegistry);
        this.consultativeQuickReplySupport =
                new AgenticAuthoringTurnConsultativeQuickReplySupport(objectMapper);
        this.preIntentExecutionSupport = new AgenticAuthoringTurnPreIntentExecutionSupport(
                objectMapper,
                projectKnowledgeService,
                this.consultativeQuickReplySupport,
                this.eventDiagnosticsSupport);
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
        AgenticAuthoringApplyTarget.Resolution terminalApplyTargetResolution =
                AgenticAuthoringApplyTarget.resolve(request, principalContext);
        if (persistedUiCompositionSourceResolver != null) {
            AgenticAuthoringApplyTarget.Resolution reconciledApplyTarget =
                    persistedUiCompositionSourceResolver.reconcileApplyTarget(
                            principalContext,
                            terminalApplyTargetResolution);
            if (reconciledApplyTarget != null) {
                terminalApplyTargetResolution = reconciledApplyTarget;
            }
        }
        AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution persistedSourceResolution =
                persistedUiCompositionSourceResolver == null
                        ? AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution.notRequired()
                        : persistedUiCompositionSourceResolver.resolve(
                                request,
                                principalContext,
                                terminalApplyTargetResolution);
        request = withoutAgenticApplyTargetContext(request);
        request = withoutClientAuthoringEvidenceContext(request);
        request = withoutClientVerifiedDomainOperations(request);
        request = withGroundedRuntimeComponentContext(request);
        AgenticAuthoringTurnState state = initialState(request);
        List<AiProviderInvocationTelemetry> turnProviderInvocations = new ArrayList<>();
        request = withActiveDecisionContext(request, state.activeSemanticDecision());
        boolean compactPlatformGuidanceOpportunity = hasPlatformGuidanceOpportunity(request);
        boolean serverIssuedQuickReplyContinuation = isServerIssuedQuickReplyContinuation(request);
        PreloadedComponentCapabilities componentCapabilitiesFuture = serverIssuedQuickReplyContinuation
                ? null
                : preloadServerComponentCapabilities(request);
        try {
            if (!compactPlatformGuidanceOpportunity) {
                eventSink.append("thought.step", thoughtStepPayload(
                        "context.bundle",
                        "Contexto do pedido recebido; vou organizar pagina, historico e evidencias disponiveis.",
                        "Authoring context received.",
                        safeDiagnostics(request)));
            }
            emitRuntimeComponentGroundingStep(request, eventSink);
            if (!compactPlatformGuidanceOpportunity) {
                emitStatus(
                        eventSink,
                        "intent.resolve",
                        "Estou organizando o pedido, o contexto da pagina e as restricoes informadas.");
                eventSink.append("thought.step", thoughtStepPayload(
                        "intent.resolve",
                        "Estou identificando a intencao principal do pedido antes de escolher recursos ou componentes.",
                        "Preparing semantic intent resolution."));
            }
            if (!serverIssuedQuickReplyContinuation) {
                request = withProjectKnowledgeContext(request, principalContext, eventSink, null);
                request = withServerComponentCapabilities(
                        request,
                        eventSink,
                        componentCapabilitiesFuture,
                        !compactPlatformGuidanceOpportunity);
            }
            request = withPreIntentAuthoringEvidenceContext(request, principalContext, state, eventSink);
            PreIntentToolPlanExecution preIntentExecution =
                    maybeRunPreIntentToolPlan(request, principalContext, eventSink, schemaBaseUrl);
            AgenticAuthoringTurnOutcome domainRuleSearchOutcome = completeDomainRuleSearch(
                    request,
                    preIntentExecution.domainRuleSearch(),
                    eventSink,
                    state);
            if (domainRuleSearchOutcome != null) {
                return domainRuleSearchOutcome;
            }
            if (preIntentExecution.semanticOrientation() != null) {
                request = withPreIntentSemanticOrientationContext(
                        request, preIntentExecution.semanticOrientation());
            }
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery =
                    preIntentExecution.resourceDiscovery();
            turnProviderInvocations.addAll(preIntentExecution.providerInvocations());
            request = withPreIntentTerminalProviderFailureContext(request, preIntentExecution.providerInvocations());
            if (!preIntentExecution.domainKnowledge().isEmpty()) {
                request = withProgressiveDomainKnowledgeContext(request, preIntentExecution.domainKnowledge());
            }
            if (!preIntentExecution.domainBindings().isEmpty()) {
                request = withProgressiveDomainBindingContext(request, preIntentExecution.domainBindings());
            }
            if (!preIntentExecution.verifiedOperations().isEmpty()) {
                request = withVerifiedOperationContext(request, preIntentExecution.verifiedOperations());
            }
            if (!preIntentExecution.verifiedRelatedResourceSurfaces().isEmpty()) {
                request = withVerifiedRelatedResourceSurfaceContext(
                        request,
                        preIntentExecution.verifiedRelatedResourceSurfaces());
            }
            if (plannedResourceDiscovery != null
                    && plannedResourceDiscovery.candidates() != null
                    && !plannedResourceDiscovery.candidates().isEmpty()) {
                request = withResourceDiscoveryContext(request, plannedResourceDiscovery);
                request = withResourceCandidateProjectKnowledgeContext(
                        request,
                        principalContext,
                        eventSink,
                        plannedResourceDiscovery.candidates());
            }
            boolean resourceDiscoveryContextPresent = hasResourceDiscoveryContext(request);
            boolean compactIntentProgress = compactPlatformGuidanceOpportunity
                    && !resourceDiscoveryContextPresent;
            if (!compactIntentProgress) {
                if (!resourceDiscoveryContextPresent && !serverIssuedQuickReplyContinuation) {
                    emitStatus(
                            eventSink,
                            "intent.resolve.llm",
                            "A LLM esta confirmando a intencao com a evidencia governada antes de materializar a tela.");
                }
                String intentResolutionPhase = resourceDiscoveryContextPresent || serverIssuedQuickReplyContinuation
                        ? "intent.resolve.evidence"
                        : "intent.resolve.llm";
                eventSink.append("thought.step", thoughtStepPayload(
                        intentResolutionPhase,
                        resourceDiscoveryContextPresent
                                ? "Estou avaliando os candidatos governados recuperados antes de materializar."
                                : serverIssuedQuickReplyContinuation
                                        ? "Estou aplicando a escolha governada deste histórico e consultando apenas os dados do domínio selecionado."
                                : "A LLM esta confirmando a intencao com o contexto governado.",
                        "Resolving the user request against governed context.",
                        Map.of(
                                "provider", safeText(request.provider()),
                                "model", safeText(request.model()),
                                "hasProjectKnowledge", request.contextHints() != null
                                        && request.contextHints().path("projectKnowledge").isObject())));
            }
            AgenticAuthoringIntentResolutionResult intentResolution = preIntentExecution.semanticOrientation() == null
                    ? intentResolverService.resolve(
                            toIntentRequest(request),
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment())
                    : intentResolverService.resolve(
                            toIntentRequest(request),
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment(),
                            preIntentExecution.semanticOrientation());
            turnProviderInvocations.addAll(providerInvocations(intentResolution));
            ArtifactReconciliationOutcome artifactReconciliation = reconcilePlannedArtifact(
                    request,
                    principalContext,
                    eventSink,
                    plannedResourceDiscovery,
                    intentResolution,
                    false);
            intentResolution = artifactReconciliation.intentResolution();
            boolean artifactReconciliationAttempted = artifactReconciliation.attempted();
            if (eventSink.terminalReached()) {
                return AgenticAuthoringTurnOutcome.noop(state);
            }
            AgenticAuthoringTurnRoute route = advisorySupport.classifyRoute(request, intentResolution, state);
            state = state.withRouteClass(route.routeClass());
            if (intentResolution != null
                    && "needs_clarification".equals(route.routeClass())
                    && intentResolution.candidates() != null
                    && !intentResolution.candidates().isEmpty()) {
                request = withResourceCandidateProjectKnowledgeContext(
                        request,
                        principalContext,
                        eventSink,
                        intentResolution.candidates());
            }
            emitIntentResolved(eventSink, intentResolution, route, request);
            AgenticAuthoringTurnOutcome clientActionOutcome = maybeCompleteDeclaredClientAction(
                    request,
                    eventSink,
                    state,
                    intentResolution,
                    route,
                    turnProviderInvocations);
            if (clientActionOutcome != null) {
                return clientActionOutcome;
            }
            AgenticAuthoringTurnOutcome postIntentConsultativeOutcome = maybeAnswerPostIntentConsultative(
                    request,
                    principalContext,
                    eventSink,
                    state,
                    intentResolution,
                    route,
                    compactPlatformGuidanceOpportunity,
                    turnProviderInvocations);
            if (postIntentConsultativeOutcome != null) {
                return postIntentConsultativeOutcome;
            }
            boolean compactGovernedFastPath = resolvedByPreIntentGovernedEvidence(intentResolution)
                    || resolvedByFastGovernedCurrentTarget(intentResolution);
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
                    route,
                    schemaBaseUrl);
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
                        "Estou pedindo para a IA revisar os recursos encontrados antes de continuar.",
                        Map.of(
                                "tool", resourceDiscoveryResult.tool(),
                                "candidateCount", resourceDiscovery.candidates().size())));
                AgenticAuthoringTurnStreamRequest refinedIntentRequest =
                        withResourceDiscoveryContext(request, resourceDiscovery);
                if (!resourceDiscovery.verifiedRelatedResourceSurfaces().isEmpty()) {
                    refinedIntentRequest = withVerifiedRelatedResourceSurfaceContext(
                            refinedIntentRequest,
                            resourceDiscovery.verifiedRelatedResourceSurfaces());
                }
                refinedIntentRequest = withResourceCandidateProjectKnowledgeContext(
                        refinedIntentRequest,
                        principalContext,
                        eventSink,
                        resourceDiscovery.candidates());
                intentResolution = intentResolverService.resolve(
                        toIntentRequest(refinedIntentRequest),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                turnProviderInvocations.addAll(providerInvocations(intentResolution));
                artifactReconciliation = reconcilePlannedArtifact(
                        refinedIntentRequest,
                        principalContext,
                        eventSink,
                        plannedResourceDiscovery,
                        intentResolution,
                        artifactReconciliationAttempted);
                intentResolution = artifactReconciliation.intentResolution();
                artifactReconciliationAttempted = artifactReconciliation.attempted();
                request = refinedIntentRequest;
                route = advisorySupport.classifyRoute(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolved(eventSink, intentResolution, route, request);
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
                AgenticAuthoringTurnOutcome groundedResourceClarification =
                        maybeAnswerGroundedResourceDiscoveryClarification(
                                request,
                                eventSink,
                                state,
                                intentResolution,
                                route,
                                turnProviderInvocations);
                if (groundedResourceClarification != null) {
                    return groundedResourceClarification;
                }
            }
            StaticEnumFilterGroundingExecution staticEnumFilterGrounding = maybeRunStaticEnumFilterGrounding(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route,
                    schemaBaseUrl);
            if (staticEnumFilterGrounding.projection() != null) {
                request = withStaticEnumFilterGrounding(request, staticEnumFilterGrounding.projection());
                intentResolution = withCanonicalStaticEnumConstraint(
                        intentResolution,
                        staticEnumFilterGrounding.projection());
                route = advisorySupport.classifyRoute(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolved(eventSink, intentResolution, route, request);
                emitIntentResolutionProgress(eventSink, intentResolution);
                AgenticAuthoringTurnOutcome staticFilterClarificationOutcome = maybeCompleteScopedSemanticClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        turnProviderInvocations);
                if (staticFilterClarificationOutcome != null) {
                    return staticFilterClarificationOutcome;
                }
            }
            LiveOptionFieldGroundingExecution liveOptionFieldGrounding = maybeRunLiveOptionFieldGrounding(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route,
                    schemaBaseUrl);
            if (liveOptionFieldGrounding.projection() != null) {
                AgenticAuthoringTurnStreamRequest fieldGroundedRequest = withLiveOptionFieldGrounding(
                        request,
                        liveOptionFieldGrounding.projection());
                fieldGroundedRequest = withActiveSemanticDecision(
                        fieldGroundedRequest,
                        intentResolution.semanticDecision());
                emitStatus(
                        eventSink,
                        "intent.resolve.live-field",
                        "Estou relacionando o conceito solicitado aos campos governados do domínio.");
                AgenticAuthoringIntentResolutionResult fieldRefinedResolution;
                if (hasSchemaConfirmedCanonicalLiveOptionField(
                        intentResolution,
                        liveOptionFieldGrounding.projection())) {
                    fieldRefinedResolution = withIntentResolutionWarning(
                            intentResolution,
                            "live-option-field-confirmed-by-canonical-schema");
                } else {
                    fieldRefinedResolution = intentResolverService.resolve(
                            toIntentRequest(fieldGroundedRequest),
                            principalContext.tenantId(),
                            principalContext.userId(),
                            principalContext.environment());
                    turnProviderInvocations.addAll(providerInvocations(fieldRefinedResolution));
                    fieldRefinedResolution = preserveLiveOptionRefinementLineage(
                            intentResolution,
                            fieldRefinedResolution);
                }
                if (!hasPreservedLiveOptionPredicate(
                                fieldRefinedResolution,
                                liveOptionFieldGrounding.projection())
                        && !requiresLiveOptionClarification(fieldRefinedResolution)) {
                    fieldRefinedResolution = blockUngroundedLiveOptionMaterialization(fieldRefinedResolution);
                }
                request = fieldGroundedRequest;
                intentResolution = fieldRefinedResolution;
                route = advisorySupport.classifyRoute(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolved(eventSink, intentResolution, route, request);
                emitIntentResolutionProgress(eventSink, intentResolution);
                AgenticAuthoringTurnOutcome fieldClarificationOutcome = maybeCompleteScopedSemanticClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        turnProviderInvocations);
                if (fieldClarificationOutcome != null) {
                    return fieldClarificationOutcome;
                }
            }
            LiveOptionGroundingExecution liveOptionGrounding = maybeRunLiveOptionValueGrounding(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route,
                    schemaBaseUrl);
            if (liveOptionGrounding.toolResult() != null
                    && (liveOptionGrounding.result() == null || !liveOptionGrounding.result().valid())) {
                intentResolution = blockUnavailableLiveOptionMaterialization(
                        intentResolution,
                        liveOptionGrounding.toolResult());
                route = advisorySupport.classifyRoute(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolved(eventSink, intentResolution, route, request);
                emitIntentResolutionProgress(eventSink, intentResolution);
            } else if (liveOptionGrounding.result() != null && liveOptionGrounding.result().valid()) {
                AgenticAuthoringTurnStreamRequest liveGroundedRequest = withLiveOptionValueGrounding(
                        request,
                        liveOptionGrounding.result());
                liveGroundedRequest = withActiveSemanticDecision(
                        liveGroundedRequest,
                        intentResolution.semanticDecision());
                emitStatus(
                        eventSink,
                        "intent.resolve.live-values",
                        "Estou relacionando a solicitação aos valores atuais do domínio antes de montar o filtro.");
                eventSink.append("thought.step", safeToolProjection(
                        "intent.resolve.live-values",
                        "Consultei os valores atuais do campo governado e estou validando a seleção semântica.",
                        Map.of(
                                "tool", AgenticAuthoringToolRegistry.SEARCH_OPTION_SOURCE_VALUES,
                                "canonicalFilterField", liveOptionGrounding.result().canonicalFilterField(),
                                "candidateCount", liveOptionGrounding.result().candidates().size(),
                                "exhaustive", liveOptionGrounding.result().exhaustive())));
                AgenticAuthoringIntentResolutionResult liveRefinedResolution = intentResolverService.resolve(
                        toIntentRequest(liveGroundedRequest),
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment());
                turnProviderInvocations.addAll(providerInvocations(liveRefinedResolution));
                liveRefinedResolution = preserveLiveOptionRefinementLineage(
                        intentResolution,
                        liveRefinedResolution);
                liveRefinedResolution = collapseSemanticallyCoveredLiveOptionConstraints(
                        liveRefinedResolution,
                        liveOptionGrounding.result());
                boolean locallyValidatedSelection = hasValidatedLiveOptionSelection(
                        liveRefinedResolution,
                        liveOptionGrounding.result());
                LiveOptionGroundingExecution selectionConfirmation = locallyValidatedSelection
                        ? maybeConfirmLiveOptionSelection(
                                liveGroundedRequest,
                                principalContext,
                                eventSink,
                                liveRefinedResolution,
                                route,
                                schemaBaseUrl,
                                liveOptionGrounding.result())
                        : LiveOptionGroundingExecution.none();
                if (locallyValidatedSelection
                        && !hasConfirmedLiveOptionSelection(
                                liveRefinedResolution,
                                liveOptionGrounding.result(),
                                selectionConfirmation.result())) {
                    liveRefinedResolution = blockUngroundedLiveOptionMaterialization(liveRefinedResolution);
                } else if (!locallyValidatedSelection && !requiresLiveOptionClarification(liveRefinedResolution)) {
                    liveRefinedResolution = blockUngroundedLiveOptionMaterialization(liveRefinedResolution);
                }
                request = liveGroundedRequest;
                intentResolution = liveRefinedResolution;
                route = advisorySupport.classifyRoute(request, intentResolution, state);
                state = state.withRouteClass(route.routeClass());
                emitIntentResolved(eventSink, intentResolution, route, request);
                emitIntentResolutionProgress(eventSink, intentResolution);
                AgenticAuthoringTurnOutcome valueClarificationOutcome = maybeCompleteScopedSemanticClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        turnProviderInvocations);
                if (valueClarificationOutcome != null) {
                    return valueClarificationOutcome;
                }
            }
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery =
                    maybeRunBusinessCatalogResourceDiscoveryTool(
                            request,
                            principalContext,
                            eventSink,
                            intentResolution,
                            route,
                            schemaBaseUrl,
                            resourceDiscovery);
            request = withAuthoringEvidenceContext(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route);
            request = withResourceWorkspaceOperationalGrounding(
                    request,
                    principalContext,
                    eventSink,
                    intentResolution,
                    route,
                    schemaBaseUrl);
            request = withComponentSelectionContext(request, intentResolution, route, eventSink);
            AgenticAuthoringPreviewResult preview = null;
            AgenticAuthoringToolLoopResult toolLoopResult = null;
            if (route.allowsPreview() && intentResolution.valid()) {
                AgenticAuthoringTurnStreamRequest contextualPreviewRequest =
                        withImplicitChartDetailModalActionContext(request, intentResolution);
                AgenticAuthoringTurnStreamRequest previewRequest = withProjectKnowledgeContext(
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
                preview = persistedSourceResolution.plan() != null
                        ? previewService.previewWithPersistedUiCompositionPlan(
                                planRequest,
                                principalContext.tenantId(),
                                principalContext.userId(),
                                principalContext.environment(),
                                schemaBaseUrl,
                                persistedSourceResolution.plan())
                        : StringUtils.hasText(schemaBaseUrl)
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
                turnProviderInvocations.addAll(preview.providerInvocations());
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
                AgenticAuthoringPreviewResult previewBeforeRepair = preview;
                preview = maybeRepairPreview(
                        previewRequest,
                        principalContext,
                        eventSink,
                        intentResolution,
                        preview,
                        schemaBaseUrl,
                        persistedSourceResolution.plan());
                if (preview != previewBeforeRepair && preview != null) {
                    turnProviderInvocations.addAll(preview.providerInvocations());
                }
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
            Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                    intentResolution,
                    preview,
                    toolLoopResult,
                    request,
                    turnProviderInvocations);
            String terminalPreviewApplyBlockReason = terminalPreviewApplyBlockReason(
                    request,
                    preview,
                    terminalApplyTargetResolution,
                    persistedSourceResolution);
            decisionDiagnostics.put("terminalPreviewApplyEligible", terminalPreviewApplyBlockReason.isBlank());
            decisionDiagnostics.put("terminalApplyTargetEligible", terminalApplyTargetResolution.valid());
            if (!terminalPreviewApplyBlockReason.isBlank()) {
                decisionDiagnostics.put("terminalPreviewApplyBlockReason", terminalPreviewApplyBlockReason);
            }
            if (Boolean.TRUE.equals(decisionDiagnostics.get("semanticDecisionReviewGroundedByPreview"))) {
                assistantMessage = groundedPreviewAssistantMessage(preview, intentResolution);
            }
            AgenticAuthoringIntentResolutionResult terminalIntentResolution =
                    terminalIntentResolution(intentResolution, decisionDiagnostics);
            boolean canApply = preview != null
                    && preview.valid()
                    && terminalPreviewApplyBlockReason.isBlank()
                    && !requiresDecisionReview(decisionDiagnostics)
                    && (toolLoopResult == null || toolLoopResult.completed());
            assistantMessage = ensureReviewablePreviewMessage(
                    assistantMessage,
                    request,
                    canApply,
                    terminalPreviewApplyBlockReason);
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
                    terminalQuickReplies(
                            request,
                            terminalIntentResolution,
                            businessCatalogDiscovery,
                            preview,
                            canApply,
                            decisionDiagnostics));
            resultPayload.put("canApply", canApply);
            JsonNode localComponentResponse = localComponentEditResponse(preview, assistantMessage);
            if (localComponentResponse != null) {
                resultPayload.put("response", localComponentResponse);
            }
            resultPayload.put("decisionDiagnostics", decisionDiagnostics);
            if (terminalApplyTargetResolution.valid()) {
                resultPayload.put("applyTarget", terminalApplyTargetResolution.target());
            }
            if (toolLoopResult != null) {
                resultPayload.put("toolLoopTrace", safeToolLoopTrace(toolLoopResult));
            }
            AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
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
        } finally {
            cancelPreloadedComponentCapabilities(componentCapabilitiesFuture);
        }
    }

    private String terminalPreviewApplyBlockReason(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringApplyTarget.Resolution applyTargetResolution,
            AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution persistedSourceResolution) {
        if (preview == null || !preview.valid()) {
            return "preview-invalid";
        }
        if (preview.warnings() != null
                && preview.warnings().contains("component-edit-plan-no-op")) {
            return "component-edit-no-op";
        }
        if (persistedSourceResolution != null && !persistedSourceResolution.valid()) {
            return persistedSourceResolution.failureCode();
        }
        if (isLocalComponentEditPreview(request, preview)) {
            return "";
        }
        String compiledPageBlockReason = AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(
                preview.compiledFormPatch());
        if (!compiledPageBlockReason.isBlank()) {
            return compiledPageBlockReason;
        }
        return applyTargetResolution == null || !applyTargetResolution.valid()
                ? applyTargetResolution == null ? "apply-target-missing" : applyTargetResolution.failureCode()
                : "";
    }

    private String terminalPreviewApplyBlockReason(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringApplyTarget.Resolution applyTargetResolution) {
        return terminalPreviewApplyBlockReason(
                request,
                preview,
                applyTargetResolution,
                AgenticAuthoringPersistedUiCompositionSourceResolver.Resolution.notRequired());
    }

    private boolean isLocalComponentEditPreview(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreviewResult preview) {
        JsonNode compiled = preview == null ? null : preview.compiledFormPatch();
        if (compiled == null
                || !"component-manifest-edit".equals(compiled.path("profileId").asText(""))) {
            return false;
        }
        String componentId = compiled.path("componentEdit").path("componentId").asText("").trim();
        return !componentId.isBlank()
                && request != null
                && componentId.equals(request.targetComponentId());
    }

    private JsonNode localComponentEditResponse(
            AgenticAuthoringPreviewResult preview,
            String assistantMessage) {
        if (preview == null || !preview.valid()) {
            return null;
        }
        if (preview.warnings() != null
                && preview.warnings().contains("component-edit-plan-no-op")) {
            return null;
        }
        JsonNode compiled = preview.compiledFormPatch();
        if (compiled == null
                || !"component-manifest-edit".equals(compiled.path("profileId").asText(""))
                || !compiled.path("componentEdit").path("plan").isObject()) {
            return null;
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "patch");
        response.set("componentEditPlan", compiled.path("componentEdit").path("plan").deepCopy());
        response.set("patch", compiled.path("patch").deepCopy());
        response.put("explanation", publicAssistantMessage(assistantMessage));
        response.set("warnings", objectMapper.valueToTree(
                preview.warnings() == null ? List.of() : preview.warnings()));
        return response;
    }

    private void emitStatus(
            AgenticAuthoringTurnEventSink eventSink,
            String phase,
            String message) {
        eventDiagnosticsSupport.emitStatus(eventSink, phase, message);
    }

    private Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary) {
        return eventDiagnosticsSupport.thoughtStepPayload(phase, message, summary);
    }

    private Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary,
            Object diagnostics) {
        return eventDiagnosticsSupport.thoughtStepPayload(phase, message, summary, diagnostics);
    }

    private void emitRuntimeComponentGroundingStep(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink) {
        eventDiagnosticsSupport.emitRuntimeComponentGroundingStep(request, eventSink);
    }

    private AgenticAuthoringTurnOutcome maybeAnswerPostIntentConsultative(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            boolean compactPlatformGuidanceOpportunity,
            List<AiProviderInvocationTelemetry> providerInvocations) {
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
        if (isServerIssuedExecutableQuickReplyContinuation(request, intentResolution)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "consultative.post-intent.skipped",
                    "A acao escolhida ja representa uma decisao executavel; vou preservar essa decisao e seguir para o grounding governado.",
                    "Server-issued executable semantic decision must not be downgraded to a terminal consultative answer.",
                    Map.of(
                            "serviceAvailable", consultativeAnswerService != null,
                            "routeClass", safeText(route.routeClass()),
                            "operationKind", safeText(intentResolution.operationKind()),
                            "serverIssuedExecutableDecision", true)));
            return null;
        }
        AgenticAuthoringTurnOutcome scopedSemanticClarification =
                maybeCompleteScopedSemanticClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        providerInvocations);
        if (scopedSemanticClarification != null) {
            return scopedSemanticClarification;
        }
        AgenticAuthoringTurnOutcome groundedClarificationOutcome =
                maybeAnswerGroundedResourceDiscoveryClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        providerInvocations);
        if (groundedClarificationOutcome != null) {
            return groundedClarificationOutcome;
        }
        groundedClarificationOutcome =
                maybeAnswerGroundedDomainDiscoveryClarification(
                        request,
                        eventSink,
                        state,
                        intentResolution,
                        route,
                        providerInvocations);
        if (groundedClarificationOutcome != null) {
            return groundedClarificationOutcome;
        }
        groundedClarificationOutcome = maybeCompleteProviderFailureClarification(
                request,
                eventSink,
                state,
                intentResolution,
                route,
                providerInvocations);
        if (groundedClarificationOutcome != null) {
            return groundedClarificationOutcome;
        }
        AgenticAuthoringTurnOutcome resolvedPlatformGuidance = maybeCompleteResolvedPlatformGuidance(
                eventSink,
                state,
                intentResolution,
                route,
                compactPlatformGuidanceOpportunity,
                request,
                providerInvocations);
        if (resolvedPlatformGuidance != null) {
            return resolvedPlatformGuidance;
        }
        if (requiresExecutableResourceGrounding(intentResolution, route, request)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "consultative.post-intent.skipped",
                    "A intencao de criacao ja foi entendida; vou buscar a fonte governada antes de responder.",
                    "Executable semantic intent requires governed resource grounding before a consultative terminal answer.",
                    Map.of(
                            "serviceAvailable", consultativeAnswerService != null,
                            "routeClass", safeText(route.routeClass()),
                            "operationKind", safeText(intentResolution.operationKind()),
                            "resourceGroundingRequired", true)));
            return null;
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
        AgenticAuthoringIntentResolutionResult consultativeIntentResolution =
                reconcileGovernedDomainConsultativeDecision(request, intentResolution, answer);
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
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                consultativeIntentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", consultativeIntentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(answer.assistantMessage()));
        resultPayload.put("assistantContent",
                AgenticAuthoringAssistantContentFactory.fromConsultativeProjection(answer.apiCatalogProjection()));
        if (answer.evidenceBundle() != null && !answer.evidenceBundle().isNull()) {
            resultPayload.put("evidenceBundle", answer.evidenceBundle());
        }
        resultPayload.put("quickReplies", consultativeQuickReplies(request, answer, consultativeIntentResolution));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:consultative_post_intent:" + safeText(answer.category()),
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private AgenticAuthoringTurnOutcome maybeCompleteScopedSemanticClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (eventSink == null
                || eventSink.terminalReached()
                || intentResolution == null
                || route == null
                || !"needs_clarification".equals(route.routeClass())
                || intentResolution.clarificationQuestions() == null
                || intentResolution.clarificationQuestions().isEmpty()
                || !hasGovernedClarificationScope(intentResolution)) {
            return null;
        }
        String assistantMessage = safeText(intentResolution.assistantMessage());
        for (String question : intentResolution.clarificationQuestions()) {
            String safeQuestion = safeText(question);
            if (!safeQuestion.isBlank() && !assistantMessage.contains(safeQuestion)) {
                assistantMessage = assistantMessage.isBlank()
                        ? safeQuestion
                        : assistantMessage + " " + safeQuestion;
            }
        }
        eventSink.append("thought.step", streamEventPayload(
                "consultative.scoped-semantic-clarification",
                "Completed the turn from the clarification resolved for the selected governed component.",
                Map.of(
                        "routeClass", safeText(route.routeClass()),
                        "questionCount", intentResolution.clarificationQuestions().size(),
                        "governedScopePreserved", true,
                        "secondInferenceSkipped", true),
                "consultative.scoped-semantic-clarification:selected_component"));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", false);
        decisionDiagnostics.put("scopedSemanticClarification", true);
        decisionDiagnostics.put("secondInferenceSkipped", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage, request));
        resultPayload.put("assistantContent", intentResolution.assistantContent());
        resultPayload.put("quickReplies", terminalClarificationQuickReplies(intentResolution));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:scoped_semantic_clarification",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private boolean hasGovernedClarificationScope(AgenticAuthoringIntentResolutionResult resolution) {
        if (resolution == null) {
            return false;
        }
        if (contains(resolution.warnings(), "llm-compact-targeted-component-clarification-used")) {
            return true;
        }
        if (resolution.target() != null || resolution.selectedCandidate() != null) {
            return true;
        }
        return resolution.semanticDecision() != null
                && resolution.semanticDecision().selectedResource() != null
                && StringUtils.hasText(resolution.semanticDecision().selectedResource().resourcePath());
    }

    private List<AgenticAuthoringQuickReply> terminalClarificationQuickReplies(
            AgenticAuthoringIntentResolutionResult resolution) {
        return consultativeQuickReplySupport.terminalClarificationQuickReplies(resolution);
    }

    private AgenticAuthoringTurnEventAppendResult appendTerminalResult(
            AgenticAuthoringTurnEventSink eventSink,
            Object payload) {
        if (payload instanceof Map<?, ?> payloadMap
                && payloadMap.get("intentResolution") instanceof AgenticAuthoringIntentResolutionResult resolution
                && requiresTerminalClarificationReplies(resolution)
                // An explicit list (including an empty one) is the terminal route's decision.
                // Never restore pre-gate suggestions after a route deliberately suppressed them.
                && !(payloadMap.get("quickReplies") instanceof List<?>)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutablePayload = (Map<String, Object>) payloadMap;
            mutablePayload.put("quickReplies", terminalClarificationQuickReplies(resolution));
        }
        return eventSink.append("result", payload);
    }

    private boolean requiresTerminalClarificationReplies(AgenticAuthoringIntentResolutionResult resolution) {
        return resolution != null
                && (resolution.pendingClarification() != null
                        || resolution.clarificationQuestions() != null
                                && !resolution.clarificationQuestions().isEmpty());
    }

    private boolean requiresExecutableResourceGrounding(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        if (intentResolution == null
                || route == null
                || !"needs_clarification".equals(route.routeClass())
                || intentResolution.selectedCandidate() != null) {
            return false;
        }
        String operationKind = safeText(intentResolution.operationKind());
        if (!("create".equals(operationKind)
                || "modify".equals(operationKind)
                || "edit".equals(operationKind)
                || "compose".equals(operationKind))) {
            return false;
        }
        JsonNode candidates = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints().path("resourceDiscovery").path("candidates");
        return !candidates.isArray() || candidates.isEmpty();
    }

    private AgenticAuthoringIntentResolutionResult reconcileGovernedDomainConsultativeDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringConsultativeAnswer answer) {
        return consultativeQuickReplySupport.reconcileGovernedDomainConsultativeDecision(
                request,
                intentResolution,
                answer);
    }

    private AgenticAuthoringTurnOutcome maybeCompleteProviderFailureClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (eventSink == null
                || eventSink.terminalReached()
                || intentResolution == null
                || route == null
                || !"needs_clarification".equals(route.routeClass())
                || !contains(intentResolution.warnings(), "llm-intent-resolution-failed")
                || !contains(intentResolution.warnings(), "llm-provider-error")) {
            return null;
        }
        eventSink.append("thought.step", streamEventPayload(
                "consultative.provider-failure-clarification",
                "Completed the turn from the structured semantic provider failure without starting another inference.",
                Map.of(
                        "routeClass", safeText(route.routeClass()),
                        "providerFailure", true,
                        "secondInferenceSkipped", true),
                "consultative.provider-failure-clarification:semantic_resolution"));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", false);
        decisionDiagnostics.put("providerFailureClarification", true);
        decisionDiagnostics.put("secondInferenceSkipped", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(intentResolution.assistantMessage(), request));
        resultPayload.put("assistantContent", intentResolution.assistantContent());
        resultPayload.put("quickReplies", terminalClarificationQuickReplies(intentResolution));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:provider_failure_clarification",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private AgenticAuthoringTurnOutcome maybeCompleteDeclaredClientAction(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (eventSink == null
                || eventSink.terminalReached()
                || intentResolution == null
                || !"undo".equals(safeText(intentResolution.operationKind()))
                || !"undo_last_local_change".equals(safeText(intentResolution.changeKind()))) {
            return null;
        }

        JsonNode declaredAction = declaredClientAction(request, "local-undo");
        boolean available = declaredAction != null && declaredAction.path("available").asBoolean(false);
        String assistantMessage = available
                ? "Vou desfazer somente a última alteração local, preservando as anteriores."
                : "Não há uma alteração local disponível para desfazer nesta página.";
        eventSink.append("thought.step", thoughtStepPayload(
                "client.action.resolve",
                available
                        ? "A intenção semântica corresponde a uma ação local declarada e disponível."
                        : "A intenção semântica foi resolvida, mas a ação local declarada não está disponível.",
                "Reconciling the semantic intent with declared local client actions.",
                Map.of(
                        "actionKind", "local-undo",
                        "declared", declaredAction != null,
                        "available", available)));

        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route == null ? null : route.routeClass()));
        decisionDiagnostics.put("clientActionRequested", true);
        decisionDiagnostics.put("clientActionKind", "local-undo");
        decisionDiagnostics.put("clientActionDeclared", declaredAction != null);
        decisionDiagnostics.put("clientActionAvailable", available);

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage, request));
        resultPayload.put("quickReplies", List.of());
        resultPayload.put("canApply", false);
        if (available) {
            resultPayload.put("clientAction", declaredAction.deepCopy());
        }
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                available ? "result:declared_client_action" : "result:client_action_unavailable",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private JsonNode declaredClientAction(
            AgenticAuthoringTurnStreamRequest request,
            String expectedKind) {
        if (request == null
                || request.contextHints() == null
                || !request.contextHints().isObject()
                || !StringUtils.hasText(expectedKind)) {
            return null;
        }
        JsonNode actions = request.contextHints().path("clientActions");
        if (!actions.isArray()) {
            return null;
        }
        for (JsonNode action : actions) {
            if (action == null
                    || !action.isObject()
                    || !"praxis-agentic-authoring-client-action.v1".equals(action.path("schemaVersion").asText())
                    || !expectedKind.equals(action.path("kind").asText())
                    || !StringUtils.hasText(action.path("id").asText())
                    || !StringUtils.hasText(action.path("capabilityRef").asText())) {
                continue;
            }
            String targetComponentId = action.path("targetComponentId").asText("");
            if (StringUtils.hasText(targetComponentId)
                    && !targetComponentId.equals(safeText(request.targetComponentId()))) {
                continue;
            }
            return action;
        }
        return null;
    }

    private AgenticAuthoringTurnOutcome maybeCompleteResolvedPlatformGuidance(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            boolean compactPlatformGuidanceOpportunity,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (eventSink == null
                || eventSink.terminalReached()
                || intentResolution == null
                || !intentResolution.valid()
                || !("explain".equals(intentResolution.operationKind())
                        || "explore".equals(intentResolution.operationKind()))
                || !"component".equals(intentResolution.artifactKind())
                || !("answer_component_catalog_question".equals(intentResolution.changeKind())
                        || "answer_component_capability_question".equals(intentResolution.changeKind()))
                || !StringUtils.hasText(intentResolution.assistantMessage())) {
            return null;
        }
        if (!compactPlatformGuidanceOpportunity) {
            emitStatus(
                    eventSink,
                    "consultative.intent",
                    "A orientação sobre a plataforma já está pronta com base nas capacidades governadas disponíveis.");
            eventSink.append("thought.step", streamEventPayload(
                    "consultative.answer",
                    "Completed platform guidance from the resolved semantic intent and governed component capabilities.",
                    Map.of(
                            "category", "platform_guidance",
                            "routeClass", safeText(route == null ? "" : route.routeClass()),
                            "resolvedIntentAnswerUsed", true),
                    "consultative.answer:platform_guidance:resolved_intent"));
        }
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route == null ? "" : route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", false);
        decisionDiagnostics.put("resolvedIntentAnswerUsed", true);
        String assistantMessage = intentResolution.assistantMessage();
        if (consultativeAnswerService != null) {
            String governedMessage = consultativeAnswerService.governedPlatformGuidanceMessage(
                    "platform_guidance",
                    assistantMessage,
                    componentCapabilities(request));
            if (StringUtils.hasText(governedMessage)) {
                assistantMessage = governedMessage;
            }
        }
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage, request));
        resultPayload.put("assistantContent", intentResolution.assistantContent());
        resultPayload.put("quickReplies", intentResolution.quickReplies() == null
                ? List.of()
                : intentResolution.quickReplies());
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:platform_guidance_from_resolved_intent",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(
                        route == null ? "advisory_authoring" : route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private AgenticAuthoringTurnOutcome maybeAnswerGroundedDomainDiscoveryClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            List<AiProviderInvocationTelemetry> providerInvocations) {
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
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        decisionDiagnostics.put("domainDiscoveryGroundedClarification", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage));
        resultPayload.put("assistantContent", objectMapper.createObjectNode());
        resultPayload.put("quickReplies", domainDiscoveryQuickReplies(resources));
        resultPayload.put("canApply", false);
        resultPayload.put("decisionDiagnostics", decisionDiagnostics);
        resultPayload.put("streamEventDiagnostics", streamEventDiagnostics(
                "result:grounded_domain_discovery_clarification",
                false));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
    }

    private List<JsonNode> domainDiscoveryResources(JsonNode domainDiscovery, int limit) {
        return consultativeQuickReplySupport.domainDiscoveryResources(domainDiscovery, limit);
    }

    private List<AgenticAuthoringQuickReply> domainDiscoveryQuickReplies(List<JsonNode> resources) {
        return consultativeQuickReplySupport.domainDiscoveryQuickReplies(resources);
    }

    private String domainDiscoveryLabel(JsonNode resource) {
        return consultativeQuickReplySupport.domainDiscoveryLabel(resource);
    }

    private AgenticAuthoringTurnOutcome maybeAnswerGroundedResourceDiscoveryClarification(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            List<AiProviderInvocationTelemetry> providerInvocations) {
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
            candidates = contains(
                            intentResolution == null ? null : intentResolution.warnings(),
                            "llm-intent-resolution-unresolved-clarification-required")
                    ? objectMapper.missingNode()
                    : consultativeQuickReplySupport.intentResolutionCandidateNodes(intentResolution);
        }
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection =
                consultativeQuickReplySupport.resourceDiscoveryProjection(resourceDiscovery);
        ArrayNode presentableCandidates =
                consultativeQuickReplySupport.presentableResourceDiscoveryCandidates(candidates);
        boolean onlyWeakCandidates = presentableCandidates.isEmpty();
        boolean providerFailure = contains(
                intentResolution == null ? null : intentResolution.warnings(),
                "llm-provider-error");
        boolean unconfirmedAiAuthoredResourceFocus = contains(
                intentResolution == null ? null : intentResolution.warnings(),
                "llm-resource-selection-unconfirmed-by-ai-authored-focus");
        boolean unresolvedLlmIntent = contains(
                intentResolution == null ? null : intentResolution.warnings(),
                "llm-intent-resolution-unresolved-clarification-required");
        if (onlyWeakCandidates && !providerFailure) {
            return null;
        }
        List<AgenticAuthoringQuickReply> quickReplies = onlyWeakCandidates || unconfirmedAiAuthoredResourceFocus
                ? List.of()
                // Discovery grounds the next semantic turn; it cannot manufacture a
                // server-issued create decision after the primary interpretation failed.
                : unresolvedLlmIntent
                ? (intentResolution.quickReplies() == null ? List.of() : intentResolution.quickReplies())
                : projection != null && projection.hasResources()
                ? consultativeQuickReplies(request, new AgenticAuthoringConsultativeAnswer(
                        "resource_discovery",
                        "clarify_resource_selection",
                        "",
                        projection,
                        List.of("resource-discovery-grounded-provider-failure-clarification")), intentResolution)
                : consultativeQuickReplySupport.resourceDiscoveryCandidateQuickReplies(
                        request,
                        intentResolution,
                        presentableCandidates);
        String assistantMessage = unconfirmedAiAuthoredResourceFocus
                ? consultativeQuickReplySupport.unconfirmedAiAuthoredResourceFocusMessage()
                : consultativeQuickReplySupport.groundedResourceDiscoveryClarificationMessage(
                        request,
                        presentableCandidates,
                        projection);
        eventSink.append("thought.step", streamEventPayload(
                "consultative.grounded-clarification",
                "Answered clarification from governed resource discovery evidence.",
                Map.of(
                        "routeClass", safeText(route.routeClass()),
                        "candidateCount", candidates.size(),
                        "presentableCandidateCount", presentableCandidates.size(),
                        "hasApiCatalogProjection", projection != null && projection.hasResources(),
                        "onlyWeakCandidates", onlyWeakCandidates,
                        "providerFailure", providerFailure,
                        "unconfirmedAiAuthoredResourceFocus", unconfirmedAiAuthoredResourceFocus),
                "consultative.grounded-clarification:resource_discovery"));
        Map<String, Object> decisionDiagnostics = decisionDiagnostics(
                intentResolution,
                null,
                null,
                request,
                providerInvocations);
        decisionDiagnostics.put("routeClass", safeText(route.routeClass()));
        decisionDiagnostics.put("consultativePostIntent", true);
        decisionDiagnostics.put("resourceDiscoveryGroundedClarification", true);
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("intentResolution", intentResolution);
        resultPayload.put("preview", nonMaterializedPreview(request));
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
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(state.withRouteClass(route.routeClass()))
                : AgenticAuthoringTurnOutcome.noop(state);
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
        JsonNode persistedCandidateApis = contextHints.path("resolvedIntent").path("candidateApis").deepCopy();
        ObjectNode resolvedIntent = contextHints.putObject("resolvedIntent");
        resolvedIntent.put("schemaVersion", "praxis-agentic-authoring-resolved-intent-context.v1");
        resolvedIntent.put("source", "intent.resolved");
        resolvedIntent.put("routeClass", safeText(route == null ? "" : route.routeClass()));
        resolvedIntent.put("operationKind", safeText(intentResolution.operationKind()));
        resolvedIntent.put("artifactKind", safeText(intentResolution.artifactKind()));
        resolvedIntent.put("changeKind", safeText(intentResolution.changeKind()));
        resolvedIntent.put("valid", intentResolution.valid());
        if (intentResolution.apiCatalogAnswer() != null
                && intentResolution.apiCatalogAnswer().path("candidateApis").isArray()) {
            resolvedIntent.set(
                    "candidateApis",
                    intentResolution.apiCatalogAnswer().path("candidateApis").deepCopy());
        } else if (request.activeSemanticDecision() != null
                && isServerIssuedQuickReplyContinuation(request)
                && persistedCandidateApis.isArray()
                && !persistedCandidateApis.isEmpty()) {
            resolvedIntent.set("candidateApis", persistedCandidateApis);
        }
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

    private List<AgenticAuthoringQuickReply> consultativeQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringConsultativeAnswer answer,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return consultativeQuickReplySupport.consultativeQuickReplies(request, answer, intentResolution);
    }

    private void putText(ObjectNode node, String field, String value) {
        consultativeQuickReplySupport.putText(node, field, value);
    }

    private void emitRuntimeRelatedSurfaceEvidenceSteps(
            AgenticAuthoringConsultativeAnswer answer,
            AgenticAuthoringTurnEventSink eventSink) {
        eventDiagnosticsSupport.emitRuntimeRelatedSurfaceEvidenceSteps(answer, eventSink);
    }

    private Map<String, Object> streamEventPayload(
            String phase,
            String summary,
            Object diagnostics,
            String dedupeKey) {
        return eventDiagnosticsSupport.streamEventPayload(phase, summary, diagnostics, dedupeKey);
    }

    private Map<String, Object> streamEventDiagnostics(String dedupeKey, boolean technicalDuplicate) {
        return eventDiagnosticsSupport.streamEventDiagnostics(dedupeKey, technicalDuplicate);
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

    private boolean hasPlatformGuidanceOpportunity(AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null) {
            return false;
        }
        JsonNode recommendedIntent = request.contextHints().path("recommendedIntent");
        return recommendedIntent.isObject()
                && "platform-capabilities".equals(recommendedIntent.path("semanticScope").asText(""));
    }

    private boolean isServerIssuedQuickReplyContinuation(AgenticAuthoringTurnStreamRequest request) {
        AgenticAuthoringSemanticDecision decision = request == null
                ? null
                : request.activeSemanticDecision();
        return decision != null
                && decision.constraints() != null
                && "server-issued-quick-reply".equals(
                        decision.constraints().path("source").asText(""))
                && !decision.constraints().path("quickReplyId").asText("").isBlank();
    }

    private boolean isServerIssuedExecutableQuickReplyContinuation(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (!isServerIssuedQuickReplyContinuation(request) || intentResolution == null) {
            return false;
        }
        return switch (safeText(intentResolution.operationKind())) {
            case "create", "modify", "edit", "compose" -> true;
            default -> false;
        };
    }

    private AgenticAuthoringTurnStreamRequest withServerComponentCapabilities(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink,
            PreloadedComponentCapabilities preloadedCapabilities,
            boolean emitProgress) {
        if (request == null
                || (request.componentCapabilities() != null
                && request.componentCapabilities().catalogs() != null
                && !request.componentCapabilities().catalogs().isEmpty())) {
            return request;
        }
        if (emitProgress) {
            emitStatus(
                    eventSink,
                    "component.capabilities",
                    "Estou carregando capacidades governadas dos componentes para escolher a materializacao correta.");
        }
        ComponentCapabilitiesLoadResult componentCapabilitiesLoad =
                awaitServerComponentCapabilities(preloadedCapabilities);
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = componentCapabilitiesLoad.result();
        if (emitProgress) {
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics catalogDiagnostics =
                    componentCapabilities == null ? null : componentCapabilities.diagnostics();
            boolean catalogDegraded = catalogDiagnostics != null && catalogDiagnostics.degraded();
            eventSink.append("thought.step", thoughtStepPayload(
                    "component.capabilities",
                    catalogDegraded
                            ? "O catálogo governado está temporariamente degradado; vou preservar essa limitação na decisão de materialização."
                            : "Capacidades governadas dos componentes carregadas; vou usar isso na decisão de materialização.",
                    catalogDegraded
                            ? "Component capability catalog is degraded and will remain visible in materialization diagnostics."
                            : "Loaded governed component capabilities.",
                    Map.ofEntries(
                            Map.entry(
                                    "catalogCount",
                                    componentCapabilities != null && componentCapabilities.catalogs() != null
                                            ? componentCapabilities.catalogs().size()
                                            : 0),
                            Map.entry("preloaded", componentCapabilitiesLoad.preloaded()),
                            Map.entry("preloadCompletedBeforeAwait", componentCapabilitiesLoad.completedBeforeAwait()),
                            Map.entry("fallbackSynchronousLoad", componentCapabilitiesLoad.fallbackSynchronousLoad()),
                            Map.entry("timedOut", componentCapabilitiesLoad.timedOut()),
                            Map.entry("fallbackSnapshot", componentCapabilitiesLoad.fallbackSnapshot()),
                            Map.entry("awaitElapsedMs", componentCapabilitiesLoad.awaitElapsedMs()),
                            Map.entry("preloadAgeMs", componentCapabilitiesLoad.preloadAgeMs()),
                            Map.entry(
                                    "source",
                                    catalogDiagnostics == null
                                            ? componentCapabilitiesService == null ? "snapshot" : "unknown"
                                            : catalogDiagnostics.source()),
                            Map.entry("degraded", catalogDegraded),
                            Map.entry(
                                    "degradationReason",
                                    catalogDiagnostics == null || catalogDiagnostics.degradationReason() == null
                                            ? ""
                                            : catalogDiagnostics.degradationReason()))));
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
            AgenticAuthoringComponentCapabilitiesResult result;
            boolean fallbackSnapshot = false;
            try {
                result = loadServerComponentCapabilities();
            } catch (RuntimeException ex) {
                log.warn("Component capabilities failed before preload; using derived registry snapshot.", ex);
                result = loadSnapshotComponentCapabilities("preload-failed");
                fallbackSnapshot = true;
            }
            if (result == null) {
                result = loadSnapshotComponentCapabilities("preload-empty");
                fallbackSnapshot = true;
            } else {
                fallbackSnapshot = isSnapshotFallback(result);
            }
            return new ComponentCapabilitiesLoadResult(
                    result,
                    false,
                    false,
                    false,
                    false,
                    fallbackSnapshot,
                    elapsedMs(awaitStartedAtNanos),
                    0L);
        }
        boolean completedBeforeAwait = preloadedCapabilities.future().isDone();
        try {
            long preloadAgeMs = elapsedMs(preloadedCapabilities.startedAtNanos());
            long remainingTimeoutMs = componentCapabilitiesPreloadTimeoutMs - preloadAgeMs;
            AgenticAuthoringComponentCapabilitiesResult result;
            if (completedBeforeAwait) {
                result = preloadedCapabilities.future().get();
            } else if (remainingTimeoutMs > 0L) {
                result = preloadedCapabilities.future().get(remainingTimeoutMs, TimeUnit.MILLISECONDS);
            } else {
                throw new TimeoutException("Component capability preload deadline elapsed.");
            }
            boolean fallbackSnapshot = result == null || isSnapshotFallback(result);
            if (result == null) {
                result = loadSnapshotComponentCapabilities("preload-empty");
            }
            return new ComponentCapabilitiesLoadResult(
                    result,
                    true,
                    completedBeforeAwait,
                    false,
                    false,
                    fallbackSnapshot,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        } catch (TimeoutException ex) {
            preloadedCapabilities.future().cancel(true);
            log.warn(
                    "Component capability preload exceeded {} ms; continuing with derived registry snapshot.",
                    componentCapabilitiesPreloadTimeoutMs);
            return new ComponentCapabilitiesLoadResult(
                    loadSnapshotComponentCapabilities("preload-timeout"),
                    true,
                    completedBeforeAwait,
                    false,
                    true,
                    true,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        } catch (InterruptedException ex) {
            preloadedCapabilities.future().cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Component capability preload was interrupted; continuing with derived registry snapshot.");
            return new ComponentCapabilitiesLoadResult(
                    loadSnapshotComponentCapabilities("preload-interrupted"),
                    true,
                    completedBeforeAwait,
                    false,
                    false,
                    true,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        } catch (ExecutionException | CancellationException ex) {
            log.warn("Component capability preload failed; continuing with derived registry snapshot.", ex);
            return new ComponentCapabilitiesLoadResult(
                    loadSnapshotComponentCapabilities("preload-failed"),
                    true,
                    completedBeforeAwait,
                    false,
                    false,
                    true,
                    elapsedMs(awaitStartedAtNanos),
                    elapsedMs(preloadedCapabilities.startedAtNanos()));
        }
    }

    private void cancelPreloadedComponentCapabilities(PreloadedComponentCapabilities preloadedCapabilities) {
        if (preloadedCapabilities != null && !preloadedCapabilities.future().isDone()) {
            preloadedCapabilities.future().cancel(true);
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
            boolean timedOut,
            boolean fallbackSnapshot,
            long awaitElapsedMs,
            long preloadAgeMs) {
    }

    private AgenticAuthoringComponentCapabilitiesResult loadServerComponentCapabilities() {
        return componentCapabilitiesService == null
                ? FALLBACK_COMPONENT_CAPABILITIES_SERVICE.listCapabilities()
                : componentCapabilitiesService.listCapabilities();
    }

    private AgenticAuthoringComponentCapabilitiesResult loadSnapshotComponentCapabilities(String reason) {
        AgenticAuthoringComponentCapabilitiesResult result = componentCapabilitiesService == null
                ? null
                : componentCapabilitiesService.listSnapshotFallback(reason);
        return result == null
                ? FALLBACK_COMPONENT_CAPABILITIES_SERVICE.listSnapshotFallback(reason)
                : result;
    }

    private boolean isSnapshotFallback(AgenticAuthoringComponentCapabilitiesResult result) {
        return result != null
                && result.diagnostics() != null
                && "snapshot-fallback".equals(result.diagnostics().source());
    }

    private AgenticAuthoringTurnStreamRequest withGroundedRuntimeComponentContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null) {
            return request;
        }
        ObjectNode groundedContext = runtimeComponentGroundingService.ground(
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
        ObjectNode contextHints = contextHintsWithoutClientGroundedRuntimeContext(request.contextHints());
        if (groundedContext != null && !groundedContext.isEmpty()) {
            contextHints.set("groundedRuntimeComponentContext", groundedContext);
        }
        if (contextHints.isEmpty() && request.contextHints() == null) {
            return request;
        }
        return copyWithContextHints(request, contextHints);
    }

    private ObjectNode contextHintsWithoutClientGroundedRuntimeContext(JsonNode contextHints) {
        ObjectNode sanitized = contextHints != null && contextHints.isObject()
                ? contextHints.deepCopy()
                : objectMapper.createObjectNode();
        sanitized.remove("groundedRuntimeComponentContext");
        return sanitized;
    }

    private PreIntentToolPlanExecution maybeRunPreIntentToolPlan(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            String schemaBaseUrl) {
        if (eventSink.terminalReached()) {
            return PreIntentToolPlanExecution.empty();
        }
        if (preIntentToolPlanningService == null) {
            emitPreIntentToolPlanSkipped(eventSink, "planner-bean-unavailable", "");
            return PreIntentToolPlanExecution.empty();
        }
        if (request == null) {
            emitPreIntentToolPlanSkipped(eventSink, "request-unavailable", "");
            return PreIntentToolPlanExecution.empty();
        }
        if (hasResourceDiscoveryContext(request)) {
            emitPreIntentToolPlanSkipped(eventSink, "resource-discovery-context-present", "");
            return PreIntentToolPlanExecution.empty();
        }
        emitStatus(
                eventSink,
                "intent.orientation",
                "Estou entendendo semanticamente o pedido para decidir qual contexto governado consultar.");
        AgenticAuthoringPreIntentToolPlanningResult planningResult =
                preIntentToolPlanningService.plan(request, principalContext);
        if (planningResult == null || !planningResult.planned()) {
            emitPreIntentToolPlanSkipped(
                    eventSink,
                    planningResult == null ? "planner-result-empty" : planningResult.skipReason(),
                    planningResult == null ? "" : planningResult.errorCode());
            return new PreIntentToolPlanExecution(
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    planningResult == null ? List.of() : planningResult.providerInvocations());
        }
        AgenticAuthoringPreIntentToolPlan plan = planningResult.plan();
        if (plan == null) {
            emitPreIntentToolPlanSkipped(eventSink, "planner-plan-empty", "");
            return new PreIntentToolPlanExecution(
                    null, null, List.of(), List.of(), List.of(), List.of(), null, planningResult.providerInvocations());
        }
        if (plan.resolvesPlatformGuidance()) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.orientation",
                    "Entendi a pergunta como orientação sobre o que o Praxis pode fazer neste contexto.",
                    "Resolved the first semantic orientation from platform, domain, surface and component context.",
                    Map.of(
                            "semanticIntentClass", plan.semanticIntentClass(),
                            "groundedByPlatform", true,
                            "groundedByDomain", true,
                            "groundedByComponentCapabilities", request.componentCapabilities() != null)));
            return new PreIntentToolPlanExecution(
                    null, plan, List.of(), List.of(), List.of(), List.of(), null, planningResult.providerInvocations());
        }
        if (plan.toolCalls().isEmpty()) {
            emitPreIntentToolPlanSkipped(eventSink, "planner-tool-calls-empty", "");
            return new PreIntentToolPlanExecution(
                    null, null, List.of(), List.of(), List.of(), List.of(), null, planningResult.providerInvocations());
        }
        eventSink.append("thought.step", safeToolProjection(
                "tool.plan",
                "A LLM decidiu consultar ferramentas de leitura antes de materializar a tela.",
                Map.of(
                        "schemaVersion", safeText(plan.schemaVersion()),
                        "toolCallCount", Math.min(plan.toolCalls().size(), MAX_TOOL_CALLS_PER_TURN),
                        "requiresFullIntentResolution", plan.requiresFullIntentResolution(),
                        "reason", safeText(plan.reason()))));
        AgenticAuthoringResourceCandidatesResult resourceDiscovery = null;
        List<AgenticAuthoringProjectKnowledgeProjection> domainKnowledge = new ArrayList<>();
        List<AgenticAuthoringDomainBindingService.BindingProjection> domainBindings = new ArrayList<>();
        List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> verifiedOperations = new ArrayList<>();
        List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection>
                verifiedRelatedResourceSurfaces = new ArrayList<>();
        DomainRuleCatalogResponse domainRuleSearch = null;
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
            AgenticAuthoringToolResult result = toolRegistry.execute(
                    toolCall, principalContext, "retrieveEvidence", schemaBaseUrl);
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
                if (result.valid()) {
                    verifiedOperations.addAll(payload.verifiedOperations());
                    verifiedRelatedResourceSurfaces.addAll(payload.verifiedRelatedResourceSurfaces());
                }
            }
            if (result.valid() && result.payload() instanceof DomainRuleCatalogResponse searchProjection) {
                domainRuleSearch = searchProjection;
            }
            if (result.payload() instanceof List<?> items) {
                items.stream()
                        .filter(AgenticAuthoringProjectKnowledgeProjection.class::isInstance)
                        .map(AgenticAuthoringProjectKnowledgeProjection.class::cast)
                        .forEach(domainKnowledge::add);
                items.stream()
                        .filter(AgenticAuthoringDomainBindingService.BindingProjection.class::isInstance)
                        .map(AgenticAuthoringDomainBindingService.BindingProjection.class::cast)
                        .forEach(domainBindings::add);
                items.stream()
                        .filter(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::isInstance)
                        .map(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::cast)
                        .forEach(verifiedOperations::add);
            }
            if (!result.valid()) {
                break;
            }
        }
        return new PreIntentToolPlanExecution(
                resourceDiscovery,
                shouldPreservePreIntentSemanticOrientation(plan) ? plan : null,
                List.copyOf(domainKnowledge),
                List.copyOf(domainBindings),
                List.copyOf(verifiedOperations),
                List.copyOf(verifiedRelatedResourceSurfaces),
                domainRuleSearch,
                planningResult.providerInvocations());
    }

    private AgenticAuthoringTurnOutcome completeDomainRuleSearch(
            AgenticAuthoringTurnStreamRequest request,
            DomainRuleCatalogResponse search,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringTurnState state) {
        if (search == null) {
            return null;
        }
        if (eventSink.terminalReached()) {
            return AgenticAuthoringTurnOutcome.noop(state);
        }
        List<DomainRuleCatalogResponse.Candidate> candidates = search.candidates() == null
                ? List.of()
                : search.candidates();
        boolean english = responseLocale(request).toLowerCase(Locale.ROOT).startsWith("en");
        String assistantMessage;
        if (candidates.isEmpty()) {
            assistantMessage = english
                    ? "I did not find governed decisions in the authorized scope for this request. Refine the business context or filters and try again."
                    : "Não encontrei decisões governadas no escopo autorizado para este pedido. Refine o contexto de negócio ou os filtros e tente novamente.";
        } else {
            assistantMessage = english
                    ? "I found %d governed decision candidate%s in the authorized scope. Select one to inspect and explain its attested version."
                            .formatted(candidates.size(), candidates.size() == 1 ? "" : "s")
                    : "Encontrei %d %s no escopo autorizado. Selecione uma para inspecionar e explicar a versão atestada."
                            .formatted(
                                    candidates.size(),
                                    candidates.size() == 1
                                            ? "decisão governada candidata"
                                            : "decisões governadas candidatas");
        }

        ObjectNode evidenceBundle = objectMapper.createObjectNode();
        evidenceBundle.put("source", AgenticAuthoringToolRegistry.SEARCH_DOMAIN_RULES);
        evidenceBundle.set("domainRuleSearch", domainRuleSearchNode(search));

        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", search.schemaVersion());
        diagnostics.put("candidateCount", candidates.size());
        diagnostics.put("page", search.page());
        diagnostics.put("hasMore", search.hasMore());
        diagnostics.put("selectionRequired", true);
        diagnostics.put("canApply", false);

        ObjectNode resultPayload = objectMapper.createObjectNode();
        resultPayload.put("routeClass", "advisory_authoring");
        resultPayload.put("operationKind", "explore");
        resultPayload.put("artifactKind", "domain_decision");
        resultPayload.put("changeKind", "discover_domain_decisions");
        resultPayload.put("assistantMessage", publicAssistantMessage(assistantMessage, request));
        resultPayload.set("assistantContent", objectMapper.createObjectNode());
        resultPayload.set("quickReplies", objectMapper.valueToTree(domainRuleSearchQuickReplies(request, candidates)));
        resultPayload.put("canApply", false);
        resultPayload.set("evidenceBundle", evidenceBundle);
        resultPayload.set("decisionDiagnostics", diagnostics);
        resultPayload.set("streamEventDiagnostics", objectMapper.valueToTree(streamEventDiagnostics(
                "result:domain_rule_search",
                false)));
        AgenticAuthoringTurnEventAppendResult terminalResult = appendTerminalResult(eventSink, resultPayload);
        AgenticAuthoringTurnState terminalState = state.withRouteClass("advisory_authoring");
        return terminalResult.appendedType("result")
                ? AgenticAuthoringTurnOutcome.completed(terminalState)
                : AgenticAuthoringTurnOutcome.noop(terminalState);
    }

    private List<AgenticAuthoringQuickReply> domainRuleSearchQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            List<DomainRuleCatalogResponse.Candidate> candidates) {
        return preIntentExecutionSupport.domainRuleSearchQuickReplies(request, candidates);
    }

    private ObjectNode domainRuleSearchNode(DomainRuleCatalogResponse search) {
        return preIntentExecutionSupport.domainRuleSearchNode(search);
    }

    private String responseLocale(AgenticAuthoringTurnStreamRequest request) {
        return preIntentExecutionSupport.responseLocale(request);
    }

    private boolean shouldPreservePreIntentSemanticOrientation(AgenticAuthoringPreIntentToolPlan plan) {
        return preIntentExecutionSupport.shouldPreservePreIntentSemanticOrientation(plan);
    }

    private AgenticAuthoringTurnStreamRequest withProgressiveDomainKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        return preIntentExecutionSupport.withProgressiveDomainKnowledgeContext(request, projections);
    }

    private AgenticAuthoringTurnStreamRequest withPreIntentTerminalProviderFailureContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> invocations) {
        return preIntentExecutionSupport.withPreIntentTerminalProviderFailureContext(request, invocations);
    }

    private AgenticAuthoringTurnStreamRequest withPreIntentSemanticOrientationContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreIntentToolPlan orientation) {
        return preIntentExecutionSupport.withPreIntentSemanticOrientationContext(request, orientation);
    }

    private AgenticAuthoringTurnStreamRequest withProgressiveDomainBindingContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringDomainBindingService.BindingProjection> bindings) {
        return preIntentExecutionSupport.withProgressiveDomainBindingContext(request, bindings);
    }

    private AgenticAuthoringTurnStreamRequest withVerifiedOperationContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> operations) {
        return preIntentExecutionSupport.withVerifiedOperationContext(request, operations);
    }

    private AgenticAuthoringTurnStreamRequest withVerifiedRelatedResourceSurfaceContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection> surfaces) {
        return preIntentExecutionSupport.withVerifiedRelatedResourceSurfaceContext(request, surfaces);
    }

    private AgenticAuthoringTurnStreamRequest withResourceWorkspaceOperationalGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String schemaBaseUrl) {
        if (!requiresResourceWorkspaceOperationalGrounding(intentResolution) || request == null) {
            return request;
        }
        AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
        String resourcePath = businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        String resourceKey = resourceKeyFromPath(resourcePath);
        if (!StringUtils.hasText(resourceKey)) {
            return request;
        }
        if (hasVerifiedOperationContextForResource(request, resourceKey, resourcePath)) {
            return request;
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.VERIFY_DOMAIN_OPERATION,
                route == null ? "component_authoring" : safeText(route.routeClass()),
                new DomainOperationVerificationToolRequest(resourceKey, schemaBaseUrl));
        String layoutKind = intentResolution.semanticDecision() != null
                && intentResolution.semanticDecision().visualizationDecision() != null
                        ? safeText(intentResolution.semanticDecision().visualizationDecision().layoutKind())
                        : intentResolution.visualizationDecision() != null
                                ? safeText(intentResolution.visualizationDecision().layoutKind())
                                : "resource-master-detail";
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou verificando schemas, capabilities e actions do recurso selecionado para compor o workspace.",
                Map.of(
                        "tool", AgenticAuthoringToolRegistry.VERIFY_DOMAIN_OPERATION,
                        "resourceKey", resourceKey,
                        "layoutKind", layoutKind.isBlank() ? "resource-master-detail" : layoutKind)));
        AgenticAuthoringToolResult result = toolRegistry.execute(
                toolCall, principalContext, "retrieveEvidence", schemaBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "Schemas, capabilities e actions do workspace foram verificados no backend."
                        : "Nao foi possivel verificar schemas, capabilities e actions do workspace.",
                safeToolDiagnostics(result)));
        if (!result.valid() || !(result.payload() instanceof List<?> items)) {
            return request;
        }
        List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> operations = items.stream()
                .filter(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::isInstance)
                .map(AgenticAuthoringOperationalBindingVerificationService.OperationProjection.class::cast)
                .toList();
        AgenticAuthoringTurnStreamRequest updatedRequest =
                operations.isEmpty() ? request : withVerifiedOperationContext(request, operations);
        Object rawSurfaces = result.safeDiagnostics() == null
                ? null
                : result.safeDiagnostics().get(
                        AgenticAuthoringResourceCandidatesResult.VERIFIED_RELATED_RESOURCE_SURFACES_CONTEXT_KEY);
        if (rawSurfaces instanceof List<?> surfaceItems) {
            List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection> surfaces =
                    surfaceItems.stream()
                            .filter(AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection.class::isInstance)
                            .map(AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection.class::cast)
                            .toList();
            if (!surfaces.isEmpty()) {
                updatedRequest = withVerifiedRelatedResourceSurfaceContext(updatedRequest, surfaces);
            }
        }
        return updatedRequest;
    }

    private boolean hasVerifiedOperationContextForResource(
            AgenticAuthoringTurnStreamRequest request,
            String resourceKey,
            String resourcePath) {
        return preIntentExecutionSupport.hasVerifiedOperationContextForResource(request, resourceKey, resourcePath);
    }

    private boolean requiresResourceWorkspaceOperationalGrounding(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return preIntentExecutionSupport.requiresResourceWorkspaceOperationalGrounding(intentResolution);
    }

    private ArtifactReconciliationOutcome reconcilePlannedArtifact(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution,
            boolean reconciliationAlreadyAttempted) {
        AgenticAuthoringIntentResolutionResult composedResolution =
                preIntentExecutionSupport.composePlannedDashboardWithChartDecision(
                        plannedResourceDiscovery,
                        intentResolution);
        if (composedResolution != intentResolution) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve.composed",
                    "O planejamento confirmou um dashboard e a decisão visual confirmou seu gráfico principal; vou compor as duas evidências.",
                    "Composed the governed dashboard plan with its chart projection.",
                    Map.of(
                            "plannedArtifactKind", "dashboard",
                            "resolvedProjectionKind", "chart")));
            return new ArtifactReconciliationOutcome(composedResolution, reconciliationAlreadyAttempted);
        }
        if (preIntentExecutionSupport.isCompatibleWithPlannedArtifact(plannedResourceDiscovery, intentResolution)) {
            return new ArtifactReconciliationOutcome(intentResolution, reconciliationAlreadyAttempted);
        }
        if (reconciliationAlreadyAttempted) {
            return new ArtifactReconciliationOutcome(
                    preIntentExecutionSupport.blockPersistentArtifactConflict(
                            plannedResourceDiscovery.artifactKind(),
                            intentResolution),
                    true);
        }

        emitStatus(
                eventSink,
                "intent.resolve.reconcile",
                "Estou reconciliando duas interpretações semânticas antes de materializar a tela.");
        eventSink.append("thought.step", thoughtStepPayload(
                "intent.resolve.reconcile",
                "O planejamento e a resolução escolheram artefatos incompatíveis; vou revisar a decisão uma vez.",
                "Reconciling conflicting AI-authored artifact decisions.",
                Map.of(
                        "plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()),
                        "observedArtifactKind", safeText(intentResolution.artifactKind()))));

        AgenticAuthoringTurnStreamRequest reconciliationRequest =
                preIntentExecutionSupport.withSemanticReconciliationContext(
                        request,
                        plannedResourceDiscovery,
                        intentResolution);
        AgenticAuthoringIntentResolutionResult reconciled = intentResolverService.resolve(
                toIntentRequest(reconciliationRequest),
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
        if (preIntentExecutionSupport.isCompatibleWithPlannedArtifact(plannedResourceDiscovery, reconciled)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve.reconciled",
                    "A revisão semântica confirmou uma estrutura compatível com o planejamento governado.",
                    "Artifact decision reconciled.",
                    Map.of(
                            "plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()),
                            "resolvedArtifactKind", safeText(reconciled.artifactKind()))));
            return new ArtifactReconciliationOutcome(
                    preIntentExecutionSupport.withIntentResolutionWarning(
                            reconciled,
                            "llm-pre-intent-artifact-conflict-reconciled"),
                    true);
        }
        eventSink.append("thought.step", thoughtStepPayload(
                "intent.resolve.reconciliation_required",
                "A revisão manteve decisões incompatíveis; a materialização foi bloqueada para confirmação.",
                "Persistent artifact conflict requires clarification.",
                Map.of(
                        "plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()),
                        "resolvedArtifactKind", safeText(reconciled == null ? null : reconciled.artifactKind()))));
        return new ArtifactReconciliationOutcome(
                preIntentExecutionSupport.blockPersistentArtifactConflict(
                        plannedResourceDiscovery.artifactKind(),
                        reconciled),
                true);
    }

    private AgenticAuthoringIntentResolutionResult withIntentResolutionWarning(
            AgenticAuthoringIntentResolutionResult resolution,
            String warning) {
        return preIntentExecutionSupport.withIntentResolutionWarning(resolution, warning);
    }

    private AgenticAuthoringIntentResolutionResult blockPersistentArtifactConflict(
            String plannedArtifactKind,
            AgenticAuthoringIntentResolutionResult resolution) {
        return preIntentExecutionSupport.blockPersistentArtifactConflict(plannedArtifactKind, resolution);
    }

    private AgenticAuthoringIntentResolutionResult blockUngroundedLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution) {
        return liveOptionGroundingSupport.blockUngroundedLiveOptionMaterialization(resolution);
    }

    private AgenticAuthoringIntentResolutionResult blockUnavailableLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringToolResult toolResult) {
        return liveOptionGroundingSupport.blockUnavailableLiveOptionMaterialization(resolution, toolResult);
    }

    private void emitPreIntentToolPlanSkipped(
            AgenticAuthoringTurnEventSink eventSink,
            String reason,
            String errorCode) {
        eventDiagnosticsSupport.emitPreIntentToolPlanSkipped(eventSink, reason, errorCode);
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
            AgenticAuthoringTurnRoute route,
            String schemaBaseUrl) {
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
                        6,
                        preIntentResourceSearchFocus(request)));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando recursos do backend para conferir a decisao.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence", schemaBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "Consulta de recursos concluida; estou conferindo os candidatos encontrados."
                        : "Nao consegui concluir a consulta de recursos do backend.",
                safeToolDiagnostics(result)));
        return result;
    }

    private LiveOptionGroundingExecution maybeRunLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        return liveOptionGroundingSupport.maybeRunLiveOptionValueGrounding(
                request, principalContext, eventSink, intentResolution, route, requestBaseUrl);
    }

    private LiveOptionFieldGroundingExecution maybeRunLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        return liveOptionGroundingSupport.maybeRunLiveOptionFieldGrounding(
                request, principalContext, eventSink, intentResolution, route, requestBaseUrl);
    }

    private StaticEnumFilterGroundingExecution maybeRunStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        return liveOptionGroundingSupport.maybeRunStaticEnumFilterGrounding(
                request, principalContext, eventSink, intentResolution, route, requestBaseUrl);
    }

    private ObjectNode staticEnumFilterGroundingProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        return liveOptionGroundingSupport.staticEnumFilterGroundingProjection(
                payload, resourcePath, originalPredicate);
    }

    private AgenticAuthoringTurnStreamRequest withStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        return liveOptionGroundingSupport.withStaticEnumFilterGrounding(request, projection);
    }

    private AgenticAuthoringIntentResolutionResult withCanonicalStaticEnumConstraint(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode grounding) {
        return liveOptionGroundingSupport.withCanonicalStaticEnumConstraint(resolution, grounding);
    }

    private ObjectNode liveOptionFieldProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        return liveOptionGroundingSupport.liveOptionFieldProjection(payload, resourcePath, originalPredicate);
    }

    private AgenticAuthoringTurnStreamRequest withLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        return liveOptionGroundingSupport.withLiveOptionFieldGrounding(request, projection);
    }

    private JsonNode firstSemanticTextConstraint(JsonNode constraints) {
        return liveOptionGroundingSupport.firstSemanticTextConstraint(constraints);
    }

    private LiveOptionGroundingExecution maybeConfirmLiveOptionSelection(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl,
            LiveOptionValueRetrievalResult grounding) {
        return liveOptionGroundingSupport.maybeConfirmLiveOptionSelection(
                request, principalContext, eventSink, resolution, route, requestBaseUrl, grounding);
    }

    private AgenticAuthoringTurnStreamRequest withLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            LiveOptionValueRetrievalResult result) {
        return liveOptionGroundingSupport.withLiveOptionValueGrounding(request, result);
    }

    private boolean hasValidatedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        return liveOptionGroundingSupport.hasValidatedLiveOptionSelection(resolution, grounding);
    }

    private AgenticAuthoringIntentResolutionResult collapseSemanticallyCoveredLiveOptionConstraints(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        return liveOptionGroundingSupport.collapseSemanticallyCoveredLiveOptionConstraints(resolution, grounding);
    }

    private AgenticAuthoringIntentResolutionResult preserveLiveOptionRefinementLineage(
            AgenticAuthoringIntentResolutionResult established,
            AgenticAuthoringIntentResolutionResult refinement) {
        return liveOptionGroundingSupport.preserveLiveOptionRefinementLineage(established, refinement);
    }

    private boolean hasPreservedLiveOptionPredicate(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        return liveOptionGroundingSupport.hasPreservedLiveOptionPredicate(resolution, fieldGrounding);
    }

    private boolean hasSchemaConfirmedCanonicalLiveOptionField(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        return liveOptionGroundingSupport.hasSchemaConfirmedCanonicalLiveOptionField(resolution, fieldGrounding);
    }

    private boolean isCanonicalLiveOptionConstraint(JsonNode predicate, JsonNode fieldGrounding) {
        return liveOptionGroundingSupport.isCanonicalLiveOptionConstraint(predicate, fieldGrounding);
    }

    private boolean hasConfirmedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding,
            LiveOptionValueRetrievalResult confirmation) {
        return liveOptionGroundingSupport.hasConfirmedLiveOptionSelection(resolution, grounding, confirmation);
    }

    private boolean requiresLiveOptionClarification(AgenticAuthoringIntentResolutionResult resolution) {
        return liveOptionGroundingSupport.requiresLiveOptionClarification(resolution);
    }

    private AgenticAuthoringResourceSearchFocus preIntentResourceSearchFocus(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null) {
            return null;
        }
        JsonNode focus = request.contextHints()
                .path("preIntentSemanticOrientation")
                .path("resourceSearchFocus");
        if (!focus.isObject()) {
            return null;
        }
        List<String> supportingConcepts = new ArrayList<>();
        focus.path("supportingConcepts").forEach(item -> {
            if (item.isTextual() && StringUtils.hasText(item.asText())) {
                supportingConcepts.add(item.asText().trim());
            }
        });
        AgenticAuthoringResourceSearchFocus resolved = new AgenticAuthoringResourceSearchFocus(
                focus.path("primaryBusinessEntity").asText(""),
                supportingConcepts,
                focus.path("desiredSurface").asText(""),
                focus.path("uncertainty").asText(""),
                focus.path("rationale").asText(""));
        return resolved.isEmpty() ? null : resolved;
    }

    private AgenticAuthoringResourceCandidatesResult maybeRunBusinessCatalogResourceDiscoveryTool(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String schemaBaseUrl,
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
                        "Estou buscando fontes de negócio governadas para preparar a resposta.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                schemaBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "tool.result" : "tool.error",
                result.valid()
                        ? "A busca de fontes de negócio foi concluída."
                        : "Não consegui concluir a busca de fontes de negócio.",
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
                || hasPostIntentAuthoringEvidenceContext(request)
                || hasAuthoringEvidenceContext(request)
                && !resolvedOperationIsDeclaredComponentCapability(request, intentResolution)) {
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
                "Estou consultando as capacidades do componente selecionado para planejar a alteração.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "componentId", safeText(componentId),
                        "maxCallsPerTurn", MAX_TOOL_CALLS_PER_TURN)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "authoringEvidence.result" : "authoringEvidence.error",
                result.valid()
                        ? "As capacidades do componente foram consultadas."
                        : "Não consegui consultar as capacidades do componente.",
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
        ObjectNode evidenceContext = authoringEvidenceContext(
                toolCall.name(),
                retrievalQuery,
                componentId,
                evidence);
        evidenceContext.put("phase", "post-intent");
        evidenceContext.put("semanticChangeKind", safeText(intentResolution.changeKind()));
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> candidates =
                AgenticAuthoringAuthoringEvidenceCapabilities.select(
                        objectMapper,
                        componentId,
                        evidence,
                        componentCapabilities(request),
                        6);
        evidenceContext.set("operationCandidates", objectMapper.valueToTree(candidates));
        contextHints.set("authoringEvidence", evidenceContext);
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringTurnStreamRequest withPreIntentAuthoringEvidenceContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnState state,
            AgenticAuthoringTurnEventSink eventSink) {
        if (request == null || toolRegistry == null || eventSink.terminalReached() || hasAuthoringEvidenceContext(request)) {
            return request;
        }
        String componentId = firstNonBlank(
                contextHintText(request.contextHints(), "selectedComponentId"),
                state == null || state.structuralTarget() == null ? null : state.structuralTarget().componentId(),
                request.targetComponentId());
        componentId = firstNonBlank(
                componentId,
                activeDecisionComponentId(state == null ? null : state.activeSemanticDecision()));
        if (isContainerAuthoringComponent(componentId)) {
            return request;
        }
        String retrievalQuery = authoringEvidenceQuery(request, null);
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.GET_COMPONENT_AUTHORING_CONTEXT,
                "component_authoring",
                new CorpusToolRequest(
                        retrievalQuery, componentId, "authoring_manifest", null,
                        principalContext == null ? null : principalContext.tenantId(),
                        principalContext == null ? null : principalContext.environment(),
                        contextHintText(request.contextHints(), "releaseId"), 12));
        eventSink.append("thought.step", safeToolProjection(
                "authoringEvidence.retrieve",
                "Estou recuperando operações governadas do componente selecionado antes de resolver a intenção.",
                Map.of("tool", toolCall.name(), "componentId", componentId, "phase", "retrieveEvidence", "limit", 12)));
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy() : objectMapper.createObjectNode();
        List<ContextRetrievalService.ComponentCorpusEvidence> evidence = result.valid()
                ? componentCorpusEvidence(result, 12) : List.of();
        ObjectNode evidenceContext = authoringEvidenceContext(toolCall.name(), retrievalQuery, componentId, evidence);
        evidenceContext.put("phase", "pre-intent");
        evidenceContext.put("attempted", true);
        evidenceContext.put("retrievalStatus", result.valid() ? "resolved" : "unavailable");
        if (!result.valid()) {
            evidenceContext.put("fallbackMode", "component-capability-textual-ranking");
            evidenceContext.put("diagnostic", safeText(result.errorCode()));
        } else {
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> candidates =
                    AgenticAuthoringAuthoringEvidenceCapabilities.select(
                            objectMapper, componentId, evidence, componentCapabilities(request), 12);
            evidenceContext.set("operationCandidates", objectMapper.valueToTree(candidates));
        }
        contextHints.set("authoringEvidence", evidenceContext);
        eventSink.append("thought.step", safeToolProjection(
                result.valid() ? "authoringEvidence.result" : "authoringEvidence.error",
                result.valid() ? "As operações governadas foram recuperadas antes da resolução." : "A recuperação semântica não está disponível; o ranking textual permanecerá apenas como contingência observável.",
                safeToolDiagnostics(result)));
        return copyWithContextHints(request, contextHints);
    }

    private String activeDecisionComponentId(AgenticAuthoringSemanticDecision decision) {
        if (decision == null || decision.constraints() == null || !decision.constraints().isObject()) {
            return null;
        }
        return firstNonBlank(
                contextHintText(decision.constraints(), "selectedComponentId"),
                contextHintText(decision.constraints(), "targetComponentId"));
    }

    private AgenticAuthoringTurnStreamRequest withComponentSelectionContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnEventSink eventSink) {
        if (request == null
                || intentResolution == null
                || intentResolution.semanticDecision() == null
                || route == null
                || !route.allowsPreview()
                || request.contextHints() != null
                && request.contextHints().path("componentSelection").isObject()) {
            return request;
        }
        AgenticAuthoringComponentDiscoveryService.ComponentDiscoveryResult selection =
                componentDiscoveryService.discover(
                        intentResolution.semanticDecision(),
                        componentCapabilities(request));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("componentSelection", objectMapper.valueToTree(selection));
        if (eventSink != null && !eventSink.terminalReached()) {
            eventSink.append("thought.step", safeToolProjection(
                    "component.discovery",
                    selection.acceptedCandidates().isEmpty()
                            ? "Nao encontrei um componente governado compativel com a decisao semantica."
                            : "Selecionei candidatos de componentes pelo catalogo governado de capacidades.",
                    Map.of(
                            "source", selection.source(),
                            "acceptedCandidateCount", selection.acceptedCandidates().size(),
                            "rejectedCandidateCount", selection.rejectedCandidates().size(),
                            "outcome", selection.outcome())));
        }
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(), request.targetApp(), request.targetComponentId(), request.currentRoute(),
                request.currentPage(), request.selectedWidgetKey(), request.provider(), request.model(),
                request.apiKey(), request.sessionId(), request.clientTurnId(), request.conversationMessages(),
                request.pendingClarification(), request.attachmentSummaries(), contextHints,
                request.componentCapabilities(), request.activeSemanticDecision(), request.diagnostics(),
                request.runtimeComponentObservations(), request.runtimeComponentObservationTrustBoundary());
    }

    private boolean hasAuthoringEvidenceContext(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("authoringEvidence").isObject()
                && (request.contextHints().path("authoringEvidence").path("attempted").asBoolean(false)
                || request.contextHints().path("authoringEvidence").path("evidence").isArray());
    }

    private boolean hasPostIntentAuthoringEvidenceContext(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && "post-intent".equals(
                        request.contextHints().path("authoringEvidence").path("phase").asText(""));
    }

    private boolean resolvedOperationIsDeclaredComponentCapability(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String componentId = authoringEvidenceComponentId(request, intentResolution);
        String changeKind = intentResolution == null ? "" : safeText(intentResolution.changeKind());
        AgenticAuthoringComponentCapabilitiesResult capabilities = componentCapabilities(request);
        if (!StringUtils.hasText(componentId)
                || !StringUtils.hasText(changeKind)
                || capabilities == null
                || capabilities.catalogs() == null) {
            return false;
        }
        return capabilities.catalogs().stream()
                .filter(catalog -> componentId.equals(catalog.componentId()))
                .filter(catalog -> catalog.capabilities() != null)
                .flatMap(catalog -> catalog.capabilities().stream())
                .anyMatch(capability -> changeKind.equals(capability.id()));
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

    private boolean resolvedByFastGovernedCurrentTarget(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null
                || !"modify".equals(intentResolution.operationKind())
                || !containsWarning(intentResolution.warnings(), "llm-fast-intent-resolution-used")
                || intentResolution.target() == null
                || intentResolution.selectedCandidate() == null
                || !StringUtils.hasText(intentResolution.target().componentId())
                || !hasEvidence(intentResolution.selectedCandidate(), "current-page-target-resource")) {
            return false;
        }
        return Objects.equals(
                safeText(intentResolution.target().resourcePath()),
                safeText(intentResolution.selectedCandidate().resourcePath()));
    }

    private boolean containsWarning(List<String> warnings, String expected) {
        return warnings != null && warnings.stream().anyMatch(expected::equals);
    }

    private String authoringEvidenceQuery(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution != null && StringUtils.hasText(intentResolution.changeKind())) {
            return "Resolved canonical change "
                    + safeText(intentResolution.changeKind())
                    + ". Current user delta: "
                    + safeText(request == null ? "" : request.userPrompt());
        }
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
        return componentCorpusEvidence(result, 6);
    }

    private List<ContextRetrievalService.ComponentCorpusEvidence> componentCorpusEvidence(
            AgenticAuthoringToolResult result, int limit) {
        if (result == null || !(result.payload() instanceof List<?> payload)) {
            return List.of();
        }
        return payload.stream()
                .filter(ContextRetrievalService.ComponentCorpusEvidence.class::isInstance)
                .map(item -> (ContextRetrievalService.ComponentCorpusEvidence) item)
                .limit(limit)
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
        evidence.stream().limit(12).forEach(item -> {
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
        return eventDiagnosticsSupport.safeToolLoopDiagnostics(result);
    }

    private List<Map<String, Object>> safeToolLoopTrace(AgenticAuthoringToolLoopResult result) {
        return eventDiagnosticsSupport.safeToolLoopTrace(result);
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
        return eventDiagnosticsSupport.intentGroundingDiagnostics(intentResolution, route);
    }

    private List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        return decisionProjectionSupport.terminalQuickReplies(intentResolution, businessCatalogDiscovery);
    }

    private List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringPreviewResult preview,
            boolean canApply,
            Map<String, Object> decisionDiagnostics) {
        return decisionProjectionSupport.terminalQuickReplies(
                request,
                intentResolution,
                businessCatalogDiscovery,
                preview,
                canApply,
                decisionDiagnostics,
                componentCapabilities(request));
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

    private JsonNode previewComponentWidget(JsonNode page, String componentId, String widgetKey) {
        return decisionProjectionSupport.previewComponentWidget(page, componentId, widgetKey);
    }

    private AgenticAuthoringTurnStreamRequest withProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return preIntentExecutionSupport.withProjectKnowledgeContext(
                request,
                principalContext,
                eventSink,
                intentResolution);
    }

    private AgenticAuthoringTurnStreamRequest withResourceCandidateProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            List<AgenticAuthoringCandidate> candidates) {
        return preIntentExecutionSupport.withResourceCandidateProjectKnowledgeContext(
                request,
                principalContext,
                eventSink,
                candidates);
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

    private AgenticAuthoringTurnStreamRequest withActiveSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeSemanticDecision) {
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
                request.componentCapabilities(),
                activeSemanticDecision,
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private AgenticAuthoringTurnStreamRequest withoutAgenticApplyTargetContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("agenticApplyTarget");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
    }

    private AgenticAuthoringTurnStreamRequest withoutClientAuthoringEvidenceContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("authoringEvidence");
        sanitized.remove("uiCompositionAuthoringSource");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
    }

    private AgenticAuthoringTurnStreamRequest withoutClientVerifiedDomainOperations(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        sanitized.remove("verifiedRelatedResourceSurfaces");
        return copyWithContextHints(request, sanitized.isEmpty() ? null : sanitized);
    }

    private ObjectNode nonMaterializedPreview(AgenticAuthoringTurnStreamRequest request) {
        return preIntentExecutionSupport.nonMaterializedPreview(request);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        return decisionProjectionSupport.decisionDiagnostics(intentResolution, preview);
    }

    private AgenticAuthoringIntentResolutionResult terminalIntentResolution(
            AgenticAuthoringIntentResolutionResult intentResolution,
            Map<String, Object> decisionDiagnostics) {
        return decisionProjectionSupport.terminalIntentResolution(intentResolution, decisionDiagnostics);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult) {
        return decisionProjectionSupport.decisionDiagnostics(intentResolution, preview, toolLoopResult);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request) {
        return decisionProjectionSupport.decisionDiagnostics(intentResolution, preview, toolLoopResult, request);
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        return decisionProjectionSupport.decisionDiagnostics(
                intentResolution,
                preview,
                toolLoopResult,
                request,
                providerInvocations);
    }

    private List<AiProviderInvocationTelemetry> providerInvocations(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return decisionProjectionSupport.providerInvocations(intentResolution);
    }

    private boolean requiresDecisionReview(Map<String, Object> diagnostics) {
        return decisionProjectionSupport.requiresDecisionReview(diagnostics);
    }

    private AgenticAuthoringPreviewResult maybeRepairPreview(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            String schemaBaseUrl,
            JsonNode persistedUiCompositionPlan) throws Exception {
        String repairClassification = AgenticAuthoringRepairClassificationPolicy.classify(intentResolution, preview);
        if (!AgenticAuthoringRepairClassificationPolicy.RETRYABLE.equals(repairClassification)
                || eventSink.terminalReached()) {
            return preview;
        }
        eventSink.append("thought.step", safeToolProjection(
                "repair.attempt",
                "Estou revisando a proposta com o contexto de segurança antes de tentar novamente.",
                Map.of(
                        "phase", "preview",
                        "repairClassification", repairClassification,
                        "attempt", 1,
                        "maxAttempts", MAX_REPAIR_ATTEMPTS_PER_PHASE,
                        "failureCodeCount", preview.failureCodes() == null ? 0 : preview.failureCodes().size(),
                        "warningCount", preview.warnings() == null ? 0 : preview.warnings().size())));
        AgenticAuthoringPlanRequest repairRequest =
                toRepairPlanRequest(request, intentResolution, preview, repairClassification);
        AgenticAuthoringPreviewResult repairedPreview = persistedUiCompositionPlan != null
                ? previewService.previewWithPersistedUiCompositionPlan(
                        repairRequest,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment(),
                        schemaBaseUrl,
                        persistedUiCompositionPlan)
                : StringUtils.hasText(schemaBaseUrl)
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
        return eventDiagnosticsSupport.needsResourceDiscovery(intentResolution);
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
        return eventDiagnosticsSupport.safeToolProjection(phase, label, diagnostics);
    }

    private Map<String, Object> safeToolDiagnostics(AgenticAuthoringToolResult result) {
        return eventDiagnosticsSupport.safeToolDiagnostics(result);
    }

    private AgenticAuthoringTurnStreamRequest withResourceDiscoveryContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceCandidatesResult discovery) {
        return preIntentExecutionSupport.withResourceDiscoveryContext(request, discovery);
    }

    private String resourceKeyFromPath(String resourcePath) {
        return preIntentExecutionSupport.resourceKeyFromPath(resourcePath);
    }

    private JsonNode candidateContext(AgenticAuthoringCandidate candidate) {
        return consultativeQuickReplySupport.candidateContext(candidate);
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
        eventDiagnosticsSupport.emitIntentResolutionProgress(eventSink, intentResolution);
    }

    private void emitIntentResolved(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        eventDiagnosticsSupport.emitIntentResolved(eventSink, intentResolution, route, request);
    }

    private Map<String, Object> safePreviewDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            boolean repairAttempted) {
        return eventDiagnosticsSupport.safePreviewDiagnostics(intentResolution, preview, repairAttempted);
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return eventDiagnosticsSupport.hasEvidence(candidate, evidence);
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
        return advisorySupport.previewAssistantMessage(
                userPrompt,
                preview,
                intentResolution,
                resourceDiscovery,
                businessCatalogDiscovery,
                schemaBaseUrl);
    }

    private String consultativeCatalogAssistantMessage(
            String userPrompt,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            String schemaBaseUrl) {
        return advisorySupport.consultativeCatalogAssistantMessage(
                userPrompt,
                intentResolution,
                resourceDiscovery,
                businessCatalogDiscovery,
                schemaBaseUrl);
    }

    private JsonNode assistantContent(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery) {
        return AgenticAuthoringTurnAdvisorySupport.assistantContent(
                intentResolution,
                businessCatalogDiscovery,
                resourceDiscovery);
    }

    private String businessResourcePath(String path) {
        return preIntentExecutionSupport.businessResourcePath(path);
    }

    private String groundedPreviewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        return AgenticAuthoringTurnAdvisorySupport.groundedPreviewAssistantMessage(preview, intentResolution);
    }

    private boolean isAdvisoryCatalogIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        return AgenticAuthoringTurnAdvisorySupport.isAdvisoryCatalogIntent(intentResolution);
    }

    private boolean isPlatformGuidancePrompt(String prompt) {
        return AgenticAuthoringTurnAdvisorySupport.isPlatformGuidancePrompt(prompt);
    }

    private boolean isUnresolvedAdvisoryIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        return AgenticAuthoringTurnAdvisorySupport.isUnresolvedAdvisoryIntent(intentResolution);
    }

    private boolean hasDiscoveryCandidates(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        return AgenticAuthoringTurnAdvisorySupport.hasDiscoveryCandidates(
                resourceDiscovery,
                businessCatalogDiscovery);
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
        return eventDiagnosticsSupport.safeDiagnostics(request);
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

    private String publicAssistantMessage(String value, AgenticAuthoringTurnStreamRequest request) {
        String responseLocale = request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("");
        return AgenticAuthoringPresentationText.assistantReply(safeText(value), responseLocale);
    }

    static String ensureReviewablePreviewMessage(
            String value,
            AgenticAuthoringTurnStreamRequest request,
            boolean canApply) {
        return AgenticAuthoringTurnAdvisorySupport.ensureReviewablePreviewMessage(value, request, canApply, "");
    }

    static String ensureReviewablePreviewMessage(
            String value,
            AgenticAuthoringTurnStreamRequest request,
            boolean canApply,
            String applyBlockReason) {
        return AgenticAuthoringTurnAdvisorySupport.ensureReviewablePreviewMessage(
                value,
                request,
                canApply,
                applyBlockReason);
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

    private record ArtifactReconciliationOutcome(
            AgenticAuthoringIntentResolutionResult intentResolution,
            boolean attempted) {
    }

    private record PreIntentToolPlanExecution(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            List<AgenticAuthoringProjectKnowledgeProjection> domainKnowledge,
            List<AgenticAuthoringDomainBindingService.BindingProjection> domainBindings,
            List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> verifiedOperations,
            List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection>
                    verifiedRelatedResourceSurfaces,
            DomainRuleCatalogResponse domainRuleSearch,
            List<AiProviderInvocationTelemetry> providerInvocations) {

        private PreIntentToolPlanExecution {
            domainKnowledge = domainKnowledge == null ? List.of() : List.copyOf(domainKnowledge);
            domainBindings = domainBindings == null ? List.of() : List.copyOf(domainBindings);
            verifiedOperations = verifiedOperations == null ? List.of() : List.copyOf(verifiedOperations);
            verifiedRelatedResourceSurfaces = verifiedRelatedResourceSurfaces == null
                    ? List.of()
                    : List.copyOf(verifiedRelatedResourceSurfaces);
            providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
        }

        private static PreIntentToolPlanExecution empty() {
            return new PreIntentToolPlanExecution(
                    null, null, List.of(), List.of(), List.of(), List.of(), null, List.of());
        }
    }
}
