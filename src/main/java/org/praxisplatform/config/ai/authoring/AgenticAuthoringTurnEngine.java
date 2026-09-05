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
            AgenticAuthoringTurnRoute route = routeClassifier.classify(request, intentResolution, state);
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
                        "Estou pedindo para a IA revisar os recursos encontrados antes de continuar.",
                        Map.of(
                                "tool", resourceDiscoveryResult.tool(),
                                "candidateCount", resourceDiscovery.candidates().size())));
                AgenticAuthoringTurnStreamRequest refinedIntentRequest =
                        withResourceDiscoveryContext(request, resourceDiscovery);
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
                route = routeClassifier.classify(request, intentResolution, state);
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
                route = routeClassifier.classify(request, intentResolution, state);
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
                }
                if (!hasPreservedLiveOptionPredicate(
                                fieldRefinedResolution,
                                liveOptionFieldGrounding.projection())
                        && !requiresLiveOptionClarification(fieldRefinedResolution)) {
                    fieldRefinedResolution = blockUngroundedLiveOptionMaterialization(fieldRefinedResolution);
                }
                request = fieldGroundedRequest;
                intentResolution = fieldRefinedResolution;
                route = routeClassifier.classify(request, intentResolution, state);
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
                route = routeClassifier.classify(request, intentResolution, state);
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
                route = routeClassifier.classify(request, intentResolution, state);
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
        if (resolution != null && resolution.quickReplies() != null && !resolution.quickReplies().isEmpty()) {
            return resolution.quickReplies();
        }
        ObjectNode retryHints = objectMapper.createObjectNode();
        retryHints.put("source", "authoring-turn-terminal-clarification");
        retryHints.put("canonicalAction", "retry_semantic_resolution");
        return List.of(
                new AgenticAuthoringQuickReply(
                        "retry-semantic-resolution",
                        "retry",
                        "Tentar novamente",
                        "Tente resolver novamente a mesma solicitação com o contexto governado disponível.",
                        "Repete a resolução sem assumir uma intenção localmente.",
                        "refresh",
                        "primary",
                        retryHints),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Revisar solicitação",
                        "Quero revisar a solicitação antes de tentar novamente."));
    }

    private AgenticAuthoringTurnEventAppendResult appendTerminalResult(
            AgenticAuthoringTurnEventSink eventSink,
            Object payload) {
        if (payload instanceof Map<?, ?> payloadMap
                && payloadMap.get("intentResolution") instanceof AgenticAuthoringIntentResolutionResult resolution
                && requiresTerminalClarificationReplies(resolution)
                && (!(payloadMap.get("quickReplies") instanceof List<?> replies) || replies.isEmpty())) {
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
        if (answer == null
                || !"domain_knowledge".equals(answer.category())
                || intentResolution == null) {
            return intentResolution;
        }
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "governed-domain-discovery");
        JsonNode entries = answer.evidenceBundle() == null
                ? null
                : answer.evidenceBundle().path("entries");
        ArrayNode conceptKeys = constraints.putArray("conceptKeys");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String conceptKey = entry.path("conceptKey").asText("").trim();
                if (!conceptKey.isBlank()) {
                    conceptKeys.add(conceptKey);
                }
            }
        }
        List<String> warnings = new ArrayList<>(intentResolution.warnings() == null
                ? List.of()
                : intentResolution.warnings());
        warnings.add("governed-domain-discovery-cleared-ungrounded-resource-selection");
        AgenticAuthoringSemanticDecision semanticDecision = AgenticAuthoringSemanticDecision.from(
                        "explore",
                        "api_catalog",
                        "answer_api_catalog_question",
                        null,
                        List.of(),
                        null,
                        warnings,
                        intentResolution.llmDiagnostics(),
                        null,
                        request.activeSemanticDecision(),
                        request.sessionId(),
                        request.clientTurnId(),
                        request.userPrompt(),
                        request.userPrompt(),
                        "Governed domain discovery is grounded by the canonical domain catalog, not by an unrelated resource candidate.")
                .withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "explore",
                "api_catalog",
                "answer_api_catalog_question",
                "api-catalog-qa",
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                intentResolution.target(),
                null,
                List.of(),
                intentResolution.gate(),
                intentResolution.effectivePrompt(),
                intentResolution.assistantMessage(),
                intentResolution.assistantContent(),
                intentResolution.apiCatalogAnswer(),
                intentResolution.quickReplies(),
                intentResolution.pendingClarification(),
                intentResolution.clarificationQuestions(),
                warnings.stream().distinct().toList(),
                intentResolution.failureCodes(),
                intentResolution.currentPageSummary(),
                intentResolution.llmDiagnostics(),
                null,
                semanticDecision);
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
        boolean unconfirmedAiAuthoredResourceFocus = contains(
                intentResolution == null ? null : intentResolution.warnings(),
                "llm-resource-selection-unconfirmed-by-ai-authored-focus");
        if (onlyWeakCandidates && !providerFailure) {
            return null;
        }
        List<AgenticAuthoringQuickReply> quickReplies = onlyWeakCandidates || unconfirmedAiAuthoredResourceFocus
                ? List.of()
                : projection != null && projection.hasResources()
                ? consultativeQuickReplies(request, new AgenticAuthoringConsultativeAnswer(
                        "resource_discovery",
                        "clarify_resource_selection",
                        "",
                        projection,
                        List.of("resource-discovery-grounded-provider-failure-clarification")), intentResolution)
                : resourceDiscoveryCandidateQuickReplies(request, intentResolution, presentableCandidates);
        String assistantMessage = unconfirmedAiAuthoredResourceFocus
                ? unconfirmedAiAuthoredResourceFocusMessage()
                : groundedResourceDiscoveryClarificationMessage(request, presentableCandidates, projection);
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

    private String unconfirmedAiAuthoredResourceFocusMessage() {
        return "Os candidatos recuperados ainda não possuem vínculo semântico e operacional aprovado com o recurso e as ações solicitados. "
                + "Por segurança, não vou materializar a tela nem oferecer uma dessas fontes como confirmação. "
                + "Verifique a projeção e a aprovação do binding canônico no catálogo governado antes de continuar.";
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

    private List<AgenticAuthoringQuickReply> resourceDiscoveryCandidateQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode candidates) {
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
            String quickReplyId = resourceDiscoveryQuickReplyId(candidate);
            replies.add(new AgenticAuthoringQuickReply(
                    quickReplyId,
                    "resource",
                    label,
                    "Use " + label + " como fonte governada para a tela.",
                    "Confirma este candidato governado antes de materializar a configuração.",
                    "dataset",
                    "resource",
                    contextHints,
                    resourceDiscoverySemanticDecision(
                            request,
                            intentResolution,
                            quickReplyId,
                            label,
                            candidate),
                    value));
        }
        return replies;
    }

    private String resourceDiscoveryQuickReplyId(JsonNode candidate) {
        String identity = firstNonBlank(
                candidate.path("resourcePath").asText(""),
                candidate.path("resourceKey").asText(""),
                resourceDiscoveryCandidateLabel(candidate));
        String operation = candidate.path("operation").asText("");
        String slug = (identity + "-" + operation)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return "resource-discovery-confirm:" + (slug.isBlank() ? "candidate" : slug);
    }

    private JsonNode resourceDiscoverySemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            String quickReplyId,
            String label,
            JsonNode candidateNode) {
        AgenticAuthoringSemanticDecision activeDecision = intentResolution != null
                        && intentResolution.semanticDecision() != null
                ? intentResolution.semanticDecision()
                : request == null ? null : request.activeSemanticDecision();
        String operationKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.operationKind(),
                activeDecision == null ? "" : activeDecision.operationKind(),
                "create");
        String artifactKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.artifactKind(),
                activeDecision == null ? "" : activeDecision.artifactKind(),
                "page");
        String changeKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.changeKind(),
                activeDecision == null ? "" : activeDecision.changeKind(),
                "create_artifact");
        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNode = candidateNode.path("evidence");
        if (evidenceNode.isArray()) {
            evidenceNode.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    evidence.add(value);
                }
            });
        }
        if (evidence.isEmpty()) {
            evidence.add("semantic-retrieval");
        }
        AgenticAuthoringCandidate selectedCandidate = new AgenticAuthoringCandidate(
                candidateNode.path("resourcePath").asText(""),
                candidateNode.path("operation").asText(""),
                candidateNode.path("schemaUrl").asText(""),
                candidateNode.path("submitUrl").asText(""),
                candidateNode.path("submitMethod").asText(""),
                candidateNode.path("score").asDouble(0.0d),
                candidateNode.path("reason").asText("Governed resource discovery candidate."),
                List.copyOf(evidence));
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", quickReplyId);
        constraints.put("continuationOf", "resource_discovery");
        constraints.putArray("conceptKeys");
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        operationKind,
                        artifactKind,
                        changeKind,
                        selectedCandidate,
                        List.of(selectedCandidate),
                        activeDecision == null ? null : activeDecision.visualizationDecision(),
                        List.of(),
                        null,
                        null,
                        activeDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + quickReplyId,
                        "Use " + label + " como fonte governada para a tela.",
                        activeDecision == null
                                ? "Use " + label + " como fonte governada para a tela."
                                : activeDecision.activeObjective(),
                        "The user may select this governed resource discovered for the active authoring decision.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
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
        if (answer == null) {
            return List.of();
        }
        if (answer.quickReplies() != null && !answer.quickReplies().isEmpty()) {
            return answer.quickReplies();
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection = answer.apiCatalogProjection();
        if (projection == null || !projection.hasResources()) {
            return intentResolution != null && intentResolution.quickReplies() != null
                    ? intentResolution.quickReplies()
                    : List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        AgenticAuthoringConsultativeApiCatalogProjection.Resource primary = firstResource(projection.resources());
        if (primary != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-show-fields:" + safeResourceId(primary),
                    "Ver campos",
                    "Quais campos confirmados existem em " + resourceLabel(primary) + "?",
                    "Mostra os campos confirmados antes de escolher tabela, formulário ou gráfico.",
                    "view_list",
                    "resource",
                    "explore",
                    "api_catalog",
                    "answer_api_catalog_question",
                    primary,
                    quickReplyPresentation(
                            "Boa para entender se essa fonte cobre os dados que você precisa.",
                            "Retorna campos confirmados e pistas de uso para colunas, filtros, formulários ou gráficos.",
                            "Clique para explorar os campos dessa fonte.")));
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-table:" + safeResourceId(primary),
                    "Criar tabela",
                    "Crie uma tabela filtrável usando " + resourceLabel(primary) + ".",
                    "Transforma a fonte confirmada em uma primeira tabela governada para revisão.",
                    "table_chart",
                    "primary",
                    "create",
                    "table",
                    "create_artifact",
                    primary,
                    quickReplyPresentation(
                            "Boa para listas operacionais, auditoria e navegação por registros.",
                            "Retorna uma prévia de tabela com colunas, paginação, filtros e formatação governada.",
                            "Clique para pedir uma tabela inicial com essa fonte.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource analytical = firstAnalyticalResource(projection.resources());
        if (analytical != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-chart:" + safeResourceId(analytical),
                    "Criar gráfico",
                    "Crie uma visão com gráficos usando " + resourceLabel(analytical) + ".",
                    "Usa uma fonte analítica confirmada para propor indicadores e visualizações.",
                    "query_stats",
                    "analytics",
                    "create",
                    "chart",
                    "create_artifact",
                    analytical,
                    quickReplyPresentation(
                            "Boa para indicadores, tendências, comparação e visão executiva.",
                            "Retorna uma prévia com gráficos compatíveis com os campos confirmados.",
                            "Clique para pedir uma composição analítica inicial.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource writable = firstWritableResource(projection.resources());
        if (writable != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-form:" + safeResourceId(writable),
                    "Criar formulário",
                    "Crie um formulário governado usando " + resourceLabel(writable) + ".",
                    "Usa uma operação confirmada de escrita para capturar ou atualizar dados com governança.",
                    "dynamic_form",
                    "resource",
                    "create",
                    "form",
                    "create_artifact",
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
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision parentDecision,
            String id,
            String label,
            String prompt,
            String description,
            String icon,
            String tone,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource,
            ObjectNode presentation) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("schemaVersion", "praxis-agentic-authoring-consultative-resource-choice.v1");
        contextHints.put("source", "consultative-api-catalog-projection");
        putText(contextHints, "resourceKey", resource.resourceKey());
        putText(contextHints, "resourcePath", resource.resourcePath());
        putText(contextHints, "resourceLabel", resourceLabel(resource));
        putText(contextHints, "resourceRole", resource.role());
        putText(contextHints, "operationKind", operationKind);
        putText(contextHints, "artifactKind", artifactKind);
        putText(contextHints, "changeKind", changeKind);
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
                consultativeResourceSemanticDecision(
                        request,
                        parentDecision,
                        id,
                        operationKind,
                        artifactKind,
                        changeKind,
                        prompt,
                        resource),
                consultativeResourceChoiceValue(resource));
    }

    private JsonNode consultativeResourceSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision parentDecision,
            String id,
            String operationKind,
            String artifactKind,
            String changeKind,
            String prompt,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", id);
        constraints.put("continuationOf", "api_catalog_projection");
        putText(constraints, "resourcePath", resource.resourcePath());
        putText(constraints, "resourceKey", resource.resourceKey());
        constraints.putArray("conceptKeys");
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        operationKind,
                        artifactKind,
                        changeKind,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        parentDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + id,
                        prompt,
                        prompt,
                        "The user may select this governed resource action after catalog discovery.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
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
                ? new AgenticAuthoringComponentCapabilitiesService().listCapabilities()
                : componentCapabilitiesService.listCapabilities();
    }

    private AgenticAuthoringComponentCapabilitiesResult loadSnapshotComponentCapabilities(String reason) {
        AgenticAuthoringComponentCapabilitiesResult result = componentCapabilitiesService == null
                ? null
                : componentCapabilitiesService.listSnapshotFallback(reason);
        return result == null
                ? new AgenticAuthoringComponentCapabilitiesService().listSnapshotFallback(reason)
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
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        boolean english = responseLocale(request).toLowerCase(Locale.ROOT).startsWith("en");
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (DomainRuleCatalogResponse.Candidate candidate : candidates) {
            if (replies.size() >= 12
                    || candidate == null
                    || candidate.definitionId() == null
                    || !StringUtils.hasText(candidate.ruleKey())
                    || candidate.version() == null
                    || candidate.version() < 1) {
                continue;
            }
            ObjectNode contextHints = objectMapper.createObjectNode();
            ObjectNode selected = contextHints.putObject("selectedDomainDecisionRef");
            selected.put("schemaVersion", "praxis.ai.context-hints.domain-decision/v1");
            selected.put("definitionId", candidate.definitionId().toString());
            selected.put("ruleKey", candidate.ruleKey());
            selected.put("version", candidate.version());
            selected.put("source", "policy-studio-selection");
            String locale = responseLocale(request);
            if (StringUtils.hasText(locale)) {
                contextHints.put("responseLocale", locale);
            }
            String label = presentationText(candidate.ruleKey());
            String prompt = english
                    ? "Explain the governed decision %s at version %d using only attested evidence."
                            .formatted(candidate.ruleKey(), candidate.version())
                    : "Explique a decisão governada %s na versão %d usando somente evidência atestada."
                            .formatted(candidate.ruleKey(), candidate.version());
            String description = List.of(
                            safeText(candidate.status()),
                            safeText(candidate.ruleType()),
                            safeText(candidate.resourceKey()))
                    .stream()
                    .filter(StringUtils::hasText)
                    .map(this::presentationText)
                    .reduce((left, right) -> left + " · " + right)
                    .orElse(english ? "Governed decision" : "Decisão governada");
            replies.add(new AgenticAuthoringQuickReply(
                    "domain-decision:" + candidate.definitionId() + ":" + candidate.version(),
                    "domain-decision",
                    label,
                    prompt,
                    description,
                    "policy",
                    "default",
                    contextHints,
                    null,
                    domainRuleSearchCandidateNode(candidate)));
        }
        return List.copyOf(replies);
    }

    private ObjectNode domainRuleSearchNode(DomainRuleCatalogResponse search) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", search.schemaVersion());
        ArrayNode candidates = node.putArray("candidates");
        if (search.candidates() != null) {
            search.candidates().stream()
                    .filter(Objects::nonNull)
                    .map(this::domainRuleSearchCandidateNode)
                    .forEach(candidates::add);
        }
        node.put("page", search.page());
        node.put("limit", search.limit());
        node.put("hasMore", search.hasMore());
        return node;
    }

    private ObjectNode domainRuleSearchCandidateNode(DomainRuleCatalogResponse.Candidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        if (candidate.definitionId() != null) {
            node.put("definitionId", candidate.definitionId().toString());
        }
        putText(node, "ruleKey", candidate.ruleKey());
        if (candidate.version() != null) {
            node.put("version", candidate.version());
        }
        putText(node, "ruleType", candidate.ruleType());
        putText(node, "status", candidate.status());
        putText(node, "contextKey", candidate.contextKey());
        putText(node, "resourceKey", candidate.resourceKey());
        putText(node, "serviceKey", candidate.serviceKey());
        putText(node, "semanticOwner", candidate.semanticOwner());
        if (candidate.updatedAt() != null) {
            node.put("updatedAt", candidate.updatedAt().toString());
        }
        return node;
    }

    private String responseLocale(AgenticAuthoringTurnStreamRequest request) {
        return request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("").trim();
    }

    private boolean shouldPreservePreIntentSemanticOrientation(AgenticAuthoringPreIntentToolPlan plan) {
        if (plan == null) {
            return false;
        }
        String artifactKind = safeText(plan.artifactKind());
        return plan.requiresFullIntentResolution()
                || "governed_domain_discovery".equals(plan.semanticIntentClass())
                || (!artifactKind.isBlank() && !"unknown".equals(artifactKind));
    }

    private AgenticAuthoringTurnStreamRequest withProgressiveDomainKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        if (request == null || projections == null || projections.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringTurnStreamRequest withPreIntentSemanticOrientationContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreIntentToolPlan orientation) {
        if (request == null || orientation == null) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode context = contextHints.putObject("preIntentSemanticOrientation");
        context.put("schemaVersion", "praxis-agentic-authoring-pre-intent-orientation-context.v2");
        context.put("semanticIntentClass", safeText(orientation.semanticIntentClass()));
        context.put("artifactKind", safeText(orientation.artifactKind()));
        context.put("primaryComponent", safeText(orientation.primaryComponent()));
        context.put("layoutKind", safeText(orientation.layoutKind()));
        context.put("requiresFullIntentResolution", orientation.requiresFullIntentResolution());
        context.put("source", "llm_pre_intent_tool_plan");
        if (orientation.queryConstraints() != null && !orientation.queryConstraints().isNull()) {
            context.set("queryConstraints", orientation.queryConstraints().deepCopy());
        }
        AgenticAuthoringResourceSearchFocus resourceSearchFocus = resourceSearchFocus(orientation);
        if (resourceSearchFocus != null && !resourceSearchFocus.isEmpty()) {
            context.set("resourceSearchFocus", resourceSearchFocusNode(resourceSearchFocus));
        }
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringResourceSearchFocus resourceSearchFocus(
            AgenticAuthoringPreIntentToolPlan orientation) {
        if (orientation == null || orientation.toolCalls() == null) {
            return null;
        }
        for (AgenticAuthoringToolCall toolCall : orientation.toolCalls()) {
            if (toolCall == null) {
                continue;
            }
            if (toolCall.payload() instanceof AgenticAuthoringResourceCandidatesRequest candidatesRequest
                    && candidatesRequest.resourceSearchFocus() != null) {
                return candidatesRequest.resourceSearchFocus();
            }
            String resourceKey = switch (toolCall.payload()) {
                case DomainKnowledgeToolRequest domainRequest -> domainRequest.resourceKey();
                case DomainBindingToolRequest bindingRequest -> bindingRequest.resourceKey();
                case DomainOperationVerificationToolRequest verificationRequest -> verificationRequest.resourceKey();
                default -> "";
            };
            if (StringUtils.hasText(resourceKey)) {
                return new AgenticAuthoringResourceSearchFocus(
                        resourceKey,
                        List.of(),
                        safeText(orientation.artifactKind()),
                        "",
                        "LLM-resolved canonical resource scope from the pre-intent tool plan.");
            }
        }
        return null;
    }

    private AgenticAuthoringTurnStreamRequest withProgressiveDomainBindingContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringDomainBindingService.BindingProjection> bindings) {
        if (request == null || bindings == null || bindings.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("domainBindings");
        envelope.put("schemaVersion", "praxis-agentic-authoring-domain-bindings.v1");
        envelope.put("source", "domain_knowledge_binding");
        envelope.put("bindingCount", bindings.size());
        envelope.set("entries", objectMapper.valueToTree(bindings));
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringTurnStreamRequest withVerifiedOperationContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> operations) {
        if (request == null || operations == null || operations.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("verifiedDomainOperations");
        envelope.put("schemaVersion", "praxis-agentic-authoring-verified-domain-operations.v2");
        envelope.put("source", "schemas.filtered+resource.capabilities+schemas.actions");
        envelope.put("operationCount", operations.size());
        envelope.set("entries", objectMapper.valueToTree(operations));
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringTurnStreamRequest withVerifiedRelatedResourceSurfaceContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection> surfaces) {
        if (request == null || surfaces == null || surfaces.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("verifiedRelatedResourceSurfaces");
        envelope.put("schemaVersion", "praxis-agentic-authoring-verified-related-resource-surfaces.v1");
        envelope.put("source", "schemas.surfaces");
        envelope.put("surfaceCount", surfaces.size());
        envelope.set("entries", objectMapper.valueToTree(surfaces));
        return copyWithContextHints(request, contextHints);
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
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou verificando schemas, capabilities e actions do recurso selecionado para compor o workspace.",
                Map.of(
                        "tool", AgenticAuthoringToolRegistry.VERIFY_DOMAIN_OPERATION,
                        "resourceKey", resourceKey,
                        "layoutKind", "resource-master-detail")));
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
        return operations.isEmpty() ? request : withVerifiedOperationContext(request, operations);
    }

    private boolean hasVerifiedOperationContextForResource(
            AgenticAuthoringTurnStreamRequest request,
            String resourceKey,
            String resourcePath) {
        JsonNode envelope = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("verifiedDomainOperations");
        JsonNode entries = envelope == null ? null : envelope.path("entries");
        if (envelope == null || !envelope.isObject() || entries == null || !entries.isArray() || entries.isEmpty()) {
            return false;
        }
        String expectedPath = businessResourcePath(resourcePath);
        for (JsonNode entry : entries) {
            String entryResourceKey = safeText(entry.path("resourceKey").asText());
            String entryResourcePath = businessResourcePath(entry.path("resourcePath").asText());
            if (!resourceKey.equals(entryResourceKey)
                    || !StringUtils.hasText(expectedPath)
                    || !expectedPath.equals(entryResourcePath)) {
                return false;
            }
        }
        return true;
    }

    private boolean requiresResourceWorkspaceOperationalGrounding(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return false;
        }
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        AgenticAuthoringVisualizationDecision visualizationDecision = semanticDecision != null
                && semanticDecision.visualizationDecision() != null
                        ? semanticDecision.visualizationDecision()
                        : intentResolution.visualizationDecision();
        return visualizationDecision != null
                && ("resource-master-detail".equals(safeText(visualizationDecision.layoutKind()))
                || "parent-child-related-resource".equals(safeText(visualizationDecision.layoutKind())));
    }

    private ArtifactReconciliationOutcome reconcilePlannedArtifact(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution,
            boolean reconciliationAlreadyAttempted) {
        AgenticAuthoringIntentResolutionResult composedResolution =
                composePlannedDashboardWithChartDecision(plannedResourceDiscovery, intentResolution);
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
        if (isCompatibleWithPlannedArtifact(plannedResourceDiscovery, intentResolution)) {
            return new ArtifactReconciliationOutcome(intentResolution, reconciliationAlreadyAttempted);
        }
        if (reconciliationAlreadyAttempted) {
            return new ArtifactReconciliationOutcome(
                    blockPersistentArtifactConflict(
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

        AgenticAuthoringTurnStreamRequest reconciliationRequest = withSemanticReconciliationContext(
                request,
                plannedResourceDiscovery,
                intentResolution);
        AgenticAuthoringIntentResolutionResult reconciled = intentResolverService.resolve(
                toIntentRequest(reconciliationRequest),
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
        if (isCompatibleWithPlannedArtifact(plannedResourceDiscovery, reconciled)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve.reconciled",
                    "A revisão semântica confirmou uma estrutura compatível com o planejamento governado.",
                    "Artifact decision reconciled.",
                    Map.of(
                            "plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()),
                            "resolvedArtifactKind", safeText(reconciled.artifactKind()))));
            return new ArtifactReconciliationOutcome(
                    withIntentResolutionWarning(
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
                blockPersistentArtifactConflict(
                        plannedResourceDiscovery.artifactKind(),
                        reconciled),
                true);
    }

    private AgenticAuthoringIntentResolutionResult composePlannedDashboardWithChartDecision(
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult resolution) {
        if (plannedResourceDiscovery == null
                || !plannedResourceDiscovery.valid()
                || !"dashboard".equals(safeText(plannedResourceDiscovery.artifactKind()).toLowerCase(Locale.ROOT))
                || resolution == null
                || !resolution.valid()
                || !"chart".equals(safeText(resolution.artifactKind()).toLowerCase(Locale.ROOT))
                || !Set.of("create", "explore").contains(
                        safeText(resolution.operationKind()).toLowerCase(Locale.ROOT))
                || resolution.visualizationDecision() == null) {
            return resolution;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("llm-chart-projection-composed-into-pre-intent-dashboard-plan");
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                "dashboard",
                "create_dashboard",
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                null);
    }

    private boolean isCompatibleWithPlannedArtifact(
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (plannedResourceDiscovery == null
                || !plannedResourceDiscovery.valid()
                || plannedResourceDiscovery.resourceSearchFocus() == null
                || plannedResourceDiscovery.resourceSearchFocus().isEmpty()
                || plannedResourceDiscovery.candidates() == null
                || plannedResourceDiscovery.candidates().isEmpty()
                || intentResolution == null
                || !intentResolution.valid()) {
            return true;
        }
        String plannedArtifactKind = safeText(plannedResourceDiscovery.artifactKind()).toLowerCase(Locale.ROOT);
        if ("page".equals(plannedArtifactKind)
                || !Set.of("dashboard", "chart", "table", "form", "api_catalog")
                        .contains(plannedArtifactKind)) {
            return true;
        }
        if (!plannedArtifactKind.equals(safeText(intentResolution.artifactKind()).toLowerCase(Locale.ROOT))) {
            return false;
        }
        AgenticAuthoringVisualizationDecision decision = intentResolution.visualizationDecision();
        if (decision == null || !Set.of("dashboard", "chart").contains(plannedArtifactKind)) {
            return true;
        }
        String primaryComponent = safeText(decision.primaryComponent()).toLowerCase(Locale.ROOT);
        String layoutKind = safeText(decision.layoutKind()).toLowerCase(Locale.ROOT);
        return !Set.of("praxis-expansion", "praxis-tabs").contains(primaryComponent)
                && !Set.of("accordion_layout", "single_column_expansion_page", "tabs_layout")
                        .contains(layoutKind)
                && !decision.excludedComponentIds().contains("praxis-chart");
    }

    private AgenticAuthoringTurnStreamRequest withSemanticReconciliationContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode reconciliation = contextHints.putObject("semanticReconciliation");
        reconciliation.put("schemaVersion", "praxis-agentic-authoring-semantic-reconciliation.v1");
        reconciliation.put("source", "backend-pre-intent-tool-plan");
        reconciliation.put("forceFullIntentResolution", true);
        reconciliation.put("plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()));
        reconciliation.set(
                "plannedResourceSearchFocus",
                resourceSearchFocusNode(plannedResourceDiscovery.resourceSearchFocus()));
        reconciliation.put(
                "observedArtifactKind",
                safeText(intentResolution == null ? null : intentResolution.artifactKind()));
        if (intentResolution != null && intentResolution.visualizationDecision() != null) {
            reconciliation.set(
                    "observedVisualizationDecision",
                    objectMapper.valueToTree(intentResolution.visualizationDecision()));
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

    private AgenticAuthoringIntentResolutionResult withIntentResolutionWarning(
            AgenticAuthoringIntentResolutionResult resolution,
            String warning) {
        if (resolution == null) {
            return null;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add(warning);
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                resolution.semanticDecision());
    }

    private AgenticAuthoringIntentResolutionResult blockPersistentArtifactConflict(
            String plannedArtifactKind,
            AgenticAuthoringIntentResolutionResult resolution) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(resolution == null || resolution.warnings() == null
                ? List.of()
                : resolution.warnings());
        warnings.add("llm-intent-artifact-conflicts-with-pre-intent-plan");
        warnings.add("llm-intent-resolution-failed-closed");
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null
                        ? List.of()
                        : resolution.failureCodes());
        failureCodes.add("semantic-artifact-conflict");
        failureCodes.add("semantic-intent-confirmation-required");
        String resolvedArtifactKind = resolution == null ? "" : resolution.artifactKind();
        List<String> questions = List.of(persistentArtifactConflictQuestion(
                plannedArtifactKind,
                resolvedArtifactKind));
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "pre-intent-artifact-invariance@0.1.0",
                "clarification_required",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                "unknown",
                "unknown",
                "semantic_artifact_conflict",
                "semantic-reconciliation-required",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Encontrei duas interpretações incompatíveis para a estrutura da tela e bloqueei a prévia antes de materializar.",
                null,
                null,
                List.of(),
                null,
                questions,
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                null,
                null);
    }

    private String persistentArtifactConflictQuestion(
            String plannedArtifactKind,
            String resolvedArtifactKind) {
        return "Você confirma qual artefato deve governar a materialização: %s, definido pelo planejamento semântico, ou %s, definido pela resolução semântica?"
                .formatted(
                        artifactKindClarificationLabel(plannedArtifactKind),
                        artifactKindClarificationLabel(resolvedArtifactKind));
    }

    private String artifactKindClarificationLabel(String artifactKind) {
        return switch (safeText(artifactKind).toLowerCase(Locale.ROOT)) {
            case "dashboard" -> "dashboard";
            case "page" -> "página";
            case "table" -> "tabela";
            case "form" -> "formulário";
            case "chart" -> "gráfico";
            case "api_catalog" -> "catálogo de APIs";
            default -> "artefato não identificado";
        };
    }

    private AgenticAuthoringIntentResolutionResult blockUngroundedLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution == null || resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("live-option-value-selection-unresolved");
        warnings.add("live-option-value-materialization-failed-closed");
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null ? List.of() : resolution.failureCodes());
        failureCodes.add("live-option-value-confirmation-required");
        List<String> questions = resolution != null
                && resolution.clarificationQuestions() != null
                && !resolution.clarificationQuestions().isEmpty()
                ? resolution.clarificationQuestions()
                : List.of("Quais dos valores encontrados você deseja incluir nesse filtro?");
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "live-option-value-grounding@0.1.0",
                "clarification_required",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                resolution == null ? "unknown" : resolution.operationKind(),
                resolution == null ? "unknown" : resolution.artifactKind(),
                resolution == null ? "live_option_value_confirmation" : resolution.changeKind(),
                "live-option-value-confirmation-required",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Encontrei valores atuais relacionados ao pedido, mas preciso confirmar quais deles devem entrar no filtro.",
                null,
                null,
                resolution == null ? List.of() : resolution.quickReplies(),
                resolution == null ? null : resolution.pendingClarification(),
                questions,
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                resolution == null ? null : resolution.visualizationDecision(),
                resolution == null ? null : resolution.semanticDecision());
    }

    private AgenticAuthoringIntentResolutionResult blockUnavailableLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringToolResult toolResult) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution == null || resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("live-option-values-unavailable");
        warnings.add("live-option-value-materialization-failed-closed");
        if (toolResult != null && StringUtils.hasText(toolResult.errorCode())) {
            warnings.add(toolResult.errorCode());
        }
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null ? List.of() : resolution.failureCodes());
        failureCodes.add("live-option-values-unavailable");
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "live-option-value-grounding@0.1.0",
                "blocked",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                resolution == null ? "unknown" : resolution.operationKind(),
                resolution == null ? "unknown" : resolution.artifactKind(),
                resolution == null ? "live_option_value_grounding" : resolution.changeKind(),
                "live-option-values-unavailable",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Não consegui consultar com segurança os valores atuais desse campo. A tabela não foi alterada para evitar aplicar um filtro incorreto.",
                null,
                null,
                resolution == null ? List.of() : resolution.quickReplies(),
                resolution == null ? null : resolution.pendingClarification(),
                List.of("Tente novamente quando os dados do domínio estiverem disponíveis."),
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                resolution == null ? null : resolution.visualizationDecision(),
                resolution == null ? null : resolution.semanticDecision());
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
                        6,
                        preIntentResourceSearchFocus(request)));
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

    private LiveOptionGroundingExecution maybeRunLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && (request.contextHints().path("liveOptionValueGrounding").isObject()
                                || request.contextHints().path("staticEnumFilterGrounding").isObject())) {
            return LiveOptionGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return LiveOptionGroundingExecution.none();
        }
        JsonNode fieldGrounding = request.contextHints() == null
                ? null
                : request.contextHints().path("liveOptionFieldGrounding");
        if (!isCanonicalLiveOptionConstraint(filter, fieldGrounding)) {
            return LiveOptionGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return LiveOptionGroundingExecution.none();
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_OPTION_SOURCE_VALUES,
                route.routeClass(),
                new LiveOptionValueToolRequest(
                        resourcePath,
                        filter.path("field").asText(""),
                        filter.path("concept").asText(""),
                        filter.path("operator").asText(""),
                        filter.path("value").deepCopy(),
                        objectMapper.createObjectNode(),
                        100,
                        false));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando os valores atuais do campo governado antes de materializar o filtro.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "semanticField", filter.path("field").asText(""))));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                toolResult.valid() ? "tool.result" : "tool.error",
                toolResult.valid()
                        ? "Os valores atuais foram recuperados e serão reconciliados semanticamente."
                        : "Os valores atuais não puderam ser recuperados com segurança.",
                safeToolDiagnostics(toolResult)));
        return toolResult.valid() && toolResult.payload() instanceof LiveOptionValueRetrievalResult result
                ? new LiveOptionGroundingExecution(toolResult, result)
                : new LiveOptionGroundingExecution(toolResult, null);
    }

    private LiveOptionFieldGroundingExecution maybeRunLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && (request.contextHints().path("liveOptionFieldGrounding").isObject()
                                || request.contextHints().path("staticEnumFilterGrounding").isObject())) {
            return LiveOptionFieldGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return LiveOptionFieldGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return LiveOptionFieldGroundingExecution.none();
        }
        String filterPath = resourcePath.endsWith("/filter") ? resourcePath : resourcePath + "/filter";
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_SCHEMA_FIELDS,
                route.routeClass(),
                new SchemaFieldsToolRequest(
                        filterPath,
                        "post",
                        "request",
                        filter.path("concept").asText(filter.path("field").asText("")),
                        requestBaseUrl,
                        50));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando os campos governados do recurso antes de buscar valores atuais.",
                Map.of(
                        "tool", toolCall.name(),
                        "resourcePath", resourcePath)));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        ObjectNode projection = toolResult.valid()
                ? liveOptionFieldProjection(toolResult.payload(), resourcePath, filter)
                : null;
        eventSink.append("thought.step", safeToolProjection(
                projection != null ? "tool.result" : toolResult.valid() ? "tool.result" : "tool.error",
                projection != null
                        ? "Os campos de seleção governados foram recuperados para decisão semântica."
                        : toolResult.valid()
                                ? "O recurso não publicou campos de seleção governados aplicáveis."
                                : "Os campos governados não puderam ser consultados.",
                projection != null
                        ? Map.of(
                                "tool", toolCall.name(),
                                "candidateCount", projection.path("candidates").size())
                        : safeToolDiagnostics(toolResult)));
        return new LiveOptionFieldGroundingExecution(toolResult, projection);
    }

    private StaticEnumFilterGroundingExecution maybeRunStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && request.contextHints().path("staticEnumFilterGrounding").isObject()) {
            return StaticEnumFilterGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return StaticEnumFilterGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return StaticEnumFilterGroundingExecution.none();
        }
        String filterPath = resourcePath.endsWith("/filter") ? resourcePath : resourcePath + "/filter";
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_SCHEMA_FIELDS,
                route.routeClass(),
                new SchemaFieldsToolRequest(
                        filterPath,
                        "post",
                        "request",
                        filter.path("field").asText(""),
                        requestBaseUrl,
                        50));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        ObjectNode projection = toolResult.valid()
                ? staticEnumFilterGroundingProjection(toolResult.payload(), resourcePath, filter)
                : null;
        if (projection != null) {
            eventSink.append("thought.step", safeToolProjection(
                    "tool.result",
                    "Validei o valor solicitado no enum publicado pelo schema do filtro.",
                    Map.of(
                            "tool", toolCall.name(),
                            "canonicalFilterField", projection.path("canonicalFilterField").asText(""),
                            "source", "canonical-filter-schema-enum")));
        }
        return new StaticEnumFilterGroundingExecution(toolResult, projection);
    }

    private ObjectNode staticEnumFilterGroundingProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        JsonNode schema = payload instanceof JsonNode node ? node.path("schema") : null;
        if (schema == null || !schema.path("properties").isObject() || originalPredicate == null) {
            return null;
        }

        List<StaticEnumPropertyCandidate> candidates = new ArrayList<>();
        schema.path("properties").fields().forEachRemaining(entry -> {
            JsonNode property = entry.getValue();
            JsonNode enumValues = property.path("enum");
            if (!property.isObject() || !enumValues.isArray() || enumValues.isEmpty()) {
                return;
            }
            JsonNode canonicalValue = canonicalStaticEnumValue(property, originalPredicate.path("value"));
            if (canonicalValue == null) {
                return;
            }
            candidates.add(new StaticEnumPropertyCandidate(
                    entry.getKey(),
                    canonicalValue,
                    staticEnumFieldMatchScore(entry.getKey(), property, originalPredicate)));
        });
        if (candidates.isEmpty()) {
            return null;
        }
        int highestScore = candidates.stream()
                .mapToInt(StaticEnumPropertyCandidate::fieldMatchScore)
                .max()
                .orElse(0);
        List<StaticEnumPropertyCandidate> finalists = highestScore > 0
                ? candidates.stream().filter(candidate -> candidate.fieldMatchScore() == highestScore).toList()
                : candidates;
        if (finalists.size() != 1) {
            return null;
        }
        StaticEnumPropertyCandidate selected = finalists.get(0);
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("schemaVersion", "praxis-static-enum-filter-grounding.v1");
        projection.put("resourcePath", resourcePath);
        projection.put("canonicalFilterField", selected.field());
        projection.set("canonicalValue", selected.canonicalValue());
        projection.set("originalPredicate", originalPredicate.deepCopy());
        projection.put("source", "canonical-filter-schema-enum");
        return projection;
    }

    private int staticEnumFieldMatchScore(
            String canonicalField,
            JsonNode property,
            JsonNode originalPredicate) {
        String requestedField = normalizeGroundingText(originalPredicate.path("field").asText(""));
        String concept = normalizeGroundingText(originalPredicate.path("concept").asText(""));
        List<String> canonicalNames = List.of(
                normalizeGroundingText(canonicalField),
                normalizeGroundingText(property.path("x-ui").path("name").asText("")),
                normalizeGroundingText(property.path("x-ui").path("label").asText("")));
        int score = 0;
        for (int index = 0; index < canonicalNames.size(); index++) {
            String canonicalName = canonicalNames.get(index);
            if (!StringUtils.hasText(canonicalName)) {
                continue;
            }
            if (requestedField.equals(canonicalName)) {
                score = Math.max(score, 100 - (index * 5));
            } else if (StringUtils.hasText(requestedField)
                    && (containsGroundingPhrase(requestedField, canonicalName)
                            || containsGroundingPhrase(canonicalName, requestedField))) {
                score = Math.max(score, 75 - (index * 5));
            }
            if (containsGroundingPhrase(concept, canonicalName)) {
                score = Math.max(score, 55 - (index * 5));
            }
        }
        return score;
    }

    private boolean containsGroundingPhrase(String text, String phrase) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(phrase)
                && (" " + text + " ").contains(" " + phrase + " ");
    }

    private JsonNode canonicalStaticEnumValue(JsonNode property, JsonNode requestedValue) {
        if (requestedValue == null || requestedValue.isNull()) {
            return null;
        }
        if (requestedValue.isTextual()) {
            return canonicalStaticEnumScalar(property, requestedValue.asText(""));
        }
        if (!requestedValue.isArray() || requestedValue.isEmpty()) {
            return null;
        }
        ArrayNode canonical = objectMapper.createArrayNode();
        for (JsonNode item : requestedValue) {
            if (!item.isTextual()) {
                return null;
            }
            JsonNode resolved = canonicalStaticEnumScalar(property, item.asText(""));
            if (resolved == null) {
                return null;
            }
            canonical.add(resolved);
        }
        return canonical;
    }

    private JsonNode canonicalStaticEnumScalar(JsonNode property, String requestedValue) {
        String requested = normalizeGroundingText(requestedValue);
        if (!StringUtils.hasText(requested)) {
            return null;
        }
        JsonNode enumValues = property.path("enum");
        for (JsonNode enumValue : enumValues) {
            if (enumValue.isTextual()
                    && requested.equals(normalizeGroundingText(enumValue.asText("")))) {
                return enumValue.deepCopy();
            }
        }
        JsonNode options = property.path("x-ui").path("options");
        if (options.isArray()) {
            for (JsonNode option : options) {
                if (!requested.equals(normalizeGroundingText(option.path("label").asText("")))) {
                    continue;
                }
                JsonNode optionValue = option.get("value");
                if (optionValue == null || !optionValue.isTextual()) {
                    return null;
                }
                for (JsonNode enumValue : enumValues) {
                    if (enumValue.equals(optionValue)) {
                        return enumValue.deepCopy();
                    }
                }
            }
        }
        JsonNode nearMatch = null;
        for (JsonNode enumValue : enumValues) {
            if (!enumValue.isTextual()
                    || !isNearCanonicalGroundingText(requested, normalizeGroundingText(enumValue.asText("")))) {
                continue;
            }
            if (nearMatch != null && !nearMatch.equals(enumValue)) {
                return null;
            }
            nearMatch = enumValue.deepCopy();
        }
        if (options.isArray()) {
            for (JsonNode option : options) {
                JsonNode optionValue = option.get("value");
                if (optionValue == null
                        || !optionValue.isTextual()
                        || !isNearCanonicalGroundingText(
                                requested,
                                normalizeGroundingText(option.path("label").asText("")))) {
                    continue;
                }
                if (nearMatch != null && !nearMatch.equals(optionValue)) {
                    return null;
                }
                nearMatch = optionValue.deepCopy();
            }
        }
        return nearMatch;
    }

    private boolean isNearCanonicalGroundingText(String requested, String canonical) {
        if (!StringUtils.hasText(requested)
                || !StringUtils.hasText(canonical)
                || Math.abs(requested.length() - canonical.length()) > 1) {
            return false;
        }
        int previous = 0;
        int requestedIndex = 0;
        int canonicalIndex = 0;
        while (requestedIndex < requested.length() && canonicalIndex < canonical.length()) {
            if (requested.charAt(requestedIndex) == canonical.charAt(canonicalIndex)) {
                requestedIndex++;
                canonicalIndex++;
                continue;
            }
            if (++previous > 1) {
                return false;
            }
            if (requested.length() > canonical.length()) {
                requestedIndex++;
            } else if (canonical.length() > requested.length()) {
                canonicalIndex++;
            } else {
                requestedIndex++;
                canonicalIndex++;
            }
        }
        if (requestedIndex < requested.length() || canonicalIndex < canonical.length()) {
            previous++;
        }
        return previous == 1;
    }

    private AgenticAuthoringTurnStreamRequest withStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("staticEnumFilterGrounding", projection.deepCopy());
        return copyWithContextHints(request, contextHints);
    }

    private AgenticAuthoringIntentResolutionResult withCanonicalStaticEnumConstraint(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode grounding) {
        if (resolution == null
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || grounding == null
                || !StringUtils.hasText(grounding.path("canonicalFilterField").asText(""))
                || grounding.get("canonicalValue") == null) {
            return resolution;
        }
        String field = grounding.path("canonicalFilterField").asText("");
        JsonNode originalPredicate = grounding.path("originalPredicate");
        ObjectNode constraints = resolution.semanticDecision().constraints().deepCopy();
        boolean replaced = false;
        for (JsonNode filter : constraints.path("filters")) {
            boolean matchesOriginalPredicate = originalPredicate.isObject() && originalPredicate.equals(filter);
            boolean matchesOriginalField = originalPredicate.isObject()
                    && StringUtils.hasText(originalPredicate.path("field").asText(""))
                    && originalPredicate.path("field").asText("").equals(filter.path("field").asText(""));
            if (!replaced
                    && filter.isObject()
                    && (matchesOriginalPredicate || matchesOriginalField || field.equals(filter.path("field").asText("")))) {
                ((ObjectNode) filter).put("field", field);
                ((ObjectNode) filter).set("value", grounding.path("canonicalValue").deepCopy());
                replaced = true;
            }
        }
        if (!replaced) {
            return resolution;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("static-enum-filter-grounded-by-canonical-schema");
        AgenticAuthoringSemanticDecision groundedDecision = resolution.semanticDecision().withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                groundedDecision);
    }

    private ObjectNode liveOptionFieldProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        JsonNode schema = payload instanceof JsonNode node ? node.path("schema") : null;
        if (schema == null || !schema.path("properties").isObject()) {
            return null;
        }
        String authoredField = originalPredicate == null
                ? ""
                : originalPredicate.path("field").asText("");
        JsonNode authoredProperty = StringUtils.hasText(authoredField)
                ? schema.path("properties").path(authoredField)
                : null;
        if (authoredProperty != null
                && authoredProperty.isObject()
                && !authoredProperty.path("x-ui").path("optionSource").isObject()) {
            // The prior semantic pass already authored an exact field that exists in the
            // canonical filter schema. A record identity such as nome=Rodrigo must stay on
            // that governed non-option field; unrelated option-source candidates are not an
            // invitation to reinterpret the predicate as a master-data dimension.
            return null;
        }
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("schemaVersion", "praxis-live-option-field-grounding.v1");
        projection.put("resourcePath", resourcePath);
        projection.set("originalPredicate", originalPredicate.deepCopy());
        ArrayNode candidates = projection.putArray("candidates");
        schema.path("properties").fields().forEachRemaining(entry -> {
            JsonNode property = entry.getValue();
            JsonNode optionSource = property.path("x-ui").path("optionSource");
            JsonNode aiUsage = property.path("x-domain-governance").path("aiUsage");
            if (!optionSource.isObject()
                    || !"allow".equals(aiUsage.path("visibility").asText(""))
                    || !List.of("allow", "review_required").contains(aiUsage.path("reasoningUse").asText(""))) {
                return;
            }
            ObjectNode candidate = candidates.addObject();
            candidate.put("canonicalFilterField", entry.getKey());
            candidate.put("label", property.path("x-ui").path("label").asText(""));
            candidate.put("description", property.path("description").asText(""));
            candidate.put("optionSourceKey", optionSource.path("key").asText(""));
            candidate.put("optionResourcePath", optionSource.path("resourcePath").asText(""));
            candidate.put("multiple", property.path("x-ui").path("multiple").asBoolean(
                    property.path("type").asText("").equals("array")));
        });
        return candidates.isEmpty() ? null : projection;
    }

    private AgenticAuthoringTurnStreamRequest withLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("liveOptionFieldGrounding", projection.deepCopy());
        return copyWithContextHints(request, contextHints);
    }

    private JsonNode firstSemanticTextConstraint(JsonNode constraints) {
        if (constraints == null
                || !constraints.path("appliesToDataSelection").asBoolean(false)
                || !constraints.path("filters").isArray()) {
            return null;
        }
        for (JsonNode filter : constraints.path("filters")) {
            if (filter.isObject()
                    && (StringUtils.hasText(filter.path("field").asText(""))
                            || StringUtils.hasText(filter.path("concept").asText("")))
                    && isSemanticTextValue(filter.path("value"))) {
                return filter;
            }
        }
        return null;
    }

    private boolean isSemanticTextValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText(""));
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || !StringUtils.hasText(item.asText(""))) {
                return false;
            }
        }
        return true;
    }

    private LiveOptionGroundingExecution maybeConfirmLiveOptionSelection(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl,
            LiveOptionValueRetrievalResult grounding) {
        JsonNode selection = selectedLiveOptionConstraint(resolution, grounding);
        if (selection == null) {
            return LiveOptionGroundingExecution.none();
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_OPTION_SOURCE_VALUES,
                route.routeClass(),
                new LiveOptionValueToolRequest(
                        grounding.resourcePath(),
                        grounding.canonicalFilterField(),
                        selection.path("concept").asText(""),
                        "in",
                        selection.path("value").deepCopy(),
                        objectMapper.createObjectNode(),
                        Math.max(1, selection.path("value").size()),
                        true));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou confirmando os valores selecionados no escopo atual antes de montar o filtro.",
                Map.of(
                        "tool", toolCall.name(),
                        "canonicalFilterField", grounding.canonicalFilterField(),
                        "selectionCount", selection.path("value").size())));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                toolResult.valid() ? "tool.result" : "tool.error",
                toolResult.valid()
                        ? "A seleção foi recarregada pela superfície canônica de valores atuais."
                        : "A seleção não pôde ser confirmada no escopo atual.",
                safeToolDiagnostics(toolResult)));
        return toolResult.valid() && toolResult.payload() instanceof LiveOptionValueRetrievalResult result
                ? new LiveOptionGroundingExecution(toolResult, result)
                : new LiveOptionGroundingExecution(toolResult, null);
    }

    private AgenticAuthoringTurnStreamRequest withLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            LiveOptionValueRetrievalResult result) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        // Field discovery has already produced the canonical predicate at this point. Keeping both
        // grounding stages active makes the model reconsider a decision that is already closed and
        // can turn a clear multi-value selection into an unnecessary clarification.
        contextHints.remove("liveOptionFieldGrounding");
        contextHints.set("liveOptionValueGrounding", objectMapper.valueToTree(result));
        return copyWithContextHints(request, contextHints);
    }

    private boolean hasValidatedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || grounding == null
                || !grounding.valid()
                || !grounding.exhaustive()) {
            return false;
        }
        JsonNode filters = resolution.semanticDecision().constraints() == null
                ? null
                : resolution.semanticDecision().constraints().path("filters");
        if (filters == null || !filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            if (!grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    || !"in".equals(filter.path("operator").asText(""))
                    || !filter.path("value").isArray()
                    || filter.path("value").isEmpty()) {
                continue;
            }
            for (JsonNode selectedId : filter.path("value")) {
                boolean known = grounding.candidates().stream()
                        .map(LiveOptionValueCandidate::id)
                        .anyMatch(candidateId -> candidateId != null && candidateId.equals(selectedId));
                if (!known) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private AgenticAuthoringIntentResolutionResult collapseSemanticallyCoveredLiveOptionConstraints(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        // This is post-resolution grounding confirmation, not primary intent routing. The LLM has
        // already selected the canonical option-source field and exact live IDs. Text normalization
        // is used only to remove a duplicate projection whose value is covered by those selected,
        // backend-owned candidate labels; independent predicates remain for schema validation.
        if (resolution == null
                || grounding == null
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null) {
            return resolution;
        }
        JsonNode originalFilters = resolution.semanticDecision().constraints().path("filters");
        if (!originalFilters.isArray()) {
            return resolution;
        }
        ArrayNode canonicalSelection = objectMapper.createArrayNode();
        int canonicalConstraintCount = 0;
        for (JsonNode filter : originalFilters) {
            if (!grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    || !"in".equals(filter.path("operator").asText(""))
                    || !filter.path("value").isArray()
                    || filter.path("value").isEmpty()) {
                continue;
            }
            canonicalConstraintCount++;
            for (JsonNode selectedId : filter.path("value")) {
                boolean duplicate = false;
                for (JsonNode existingId : canonicalSelection) {
                    if (existingId.equals(selectedId)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    canonicalSelection.add(selectedId.deepCopy());
                }
            }
        }
        if (canonicalSelection.isEmpty()) {
            return resolution;
        }
        List<String> selectedCandidateLabels = selectedLiveOptionCandidateLabels(
                canonicalSelection,
                grounding);
        ObjectNode constraints = resolution.semanticDecision().constraints().deepCopy();
        JsonNode filters = constraints.path("filters");
        if (!filters.isArray() || filters.size() < 2) {
            return resolution;
        }
        ArrayNode reconciledFilters = objectMapper.createArrayNode();
        int collapsedTextConstraints = 0;
        int collapsedCanonicalConstraints = 0;
        boolean canonicalSelectionAdded = false;
        for (JsonNode filter : filters) {
            boolean canonicalConstraint = grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    && "in".equals(filter.path("operator").asText(""))
                    && filter.path("value").isArray()
                    && !filter.path("value").isEmpty();
            if (canonicalConstraint) {
                if (!canonicalSelectionAdded) {
                    ObjectNode consolidated = filter.deepCopy();
                    consolidated.set("value", canonicalSelection.deepCopy());
                    reconciledFilters.add(consolidated);
                    canonicalSelectionAdded = true;
                } else {
                    collapsedCanonicalConstraints++;
                }
                continue;
            }
            if (!selectedCandidateLabels.isEmpty()
                    && isSemanticTextValue(filter.path("value"))
                    && isTextConstraintCoveredBySelectedLiveOptionLabels(
                            filter.path("value"),
                            selectedCandidateLabels)) {
                collapsedTextConstraints++;
                continue;
            }
            reconciledFilters.add(filter.deepCopy());
        }
        if (collapsedTextConstraints == 0 && collapsedCanonicalConstraints == 0) {
            return resolution;
        }
        constraints.set("filters", reconciledFilters);
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        if (collapsedTextConstraints > 0) {
            warnings.add("live-option-redundant-semantic-constraint-collapsed");
        }
        if (canonicalConstraintCount > 1 && collapsedCanonicalConstraints > 0) {
            warnings.add("live-option-duplicate-canonical-constraint-unioned");
        }
        AgenticAuthoringSemanticDecision reconciledDecision =
                resolution.semanticDecision().withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                reconciledDecision);
    }

    private AgenticAuthoringIntentResolutionResult preserveLiveOptionRefinementLineage(
            AgenticAuthoringIntentResolutionResult established,
            AgenticAuthoringIntentResolutionResult refinement) {
        if (established == null
                || refinement == null
                || !refinement.valid()
                || established.semanticDecision() == null
                || refinement.semanticDecision() == null
                || refinement.semanticDecision().constraints() == null) {
            return refinement;
        }
        // The live-option pass is a constrained semantic classifier. It may choose current option
        // IDs, but it cannot reopen the already governed operation, artifact, resource or visual
        // decision. Backend reconciliation enforces this boundary instead of trusting prompt
        // compliance from any provider/model.
        AgenticAuthoringSemanticDecision reconciledDecision = established.semanticDecision()
                .withConstraints(refinement.semanticDecision().constraints().deepCopy());
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                established.warnings() == null ? List.of() : established.warnings());
        if (refinement.warnings() != null) {
            warnings.addAll(refinement.warnings());
        }
        warnings.add("live-option-refinement-scoped-to-constraints");
        return new AgenticAuthoringIntentResolutionResult(
                established.valid(),
                established.operationKind(),
                established.artifactKind(),
                established.changeKind(),
                established.authoringProfile(),
                established.targetApp(),
                established.targetComponentId(),
                established.target(),
                established.selectedCandidate(),
                established.candidates(),
                established.gate(),
                established.effectivePrompt(),
                established.assistantMessage(),
                established.assistantContent(),
                established.apiCatalogAnswer(),
                refinement.quickReplies(),
                refinement.pendingClarification(),
                refinement.clarificationQuestions(),
                List.copyOf(warnings),
                refinement.failureCodes(),
                established.currentPageSummary(),
                refinement.llmDiagnostics(),
                established.visualizationDecision(),
                reconciledDecision);
    }

    private List<String> selectedLiveOptionCandidateLabels(
            JsonNode selectedIds,
            LiveOptionValueRetrievalResult grounding) {
        if (selectedIds == null
                || !selectedIds.isArray()
                || selectedIds.isEmpty()
                || grounding == null
                || grounding.candidates() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode selectedId : selectedIds) {
            LiveOptionValueCandidate selectedCandidate = grounding.candidates().stream()
                    .filter(candidate -> candidate.id() != null && candidate.id().equals(selectedId))
                    .findFirst()
                    .orElse(null);
            if (selectedCandidate == null || !StringUtils.hasText(selectedCandidate.label())) {
                return List.of();
            }
            labels.add(normalizeGroundingText(selectedCandidate.label()));
        }
        return labels;
    }

    private boolean isTextConstraintCoveredBySelectedLiveOptionLabels(
            JsonNode value,
            List<String> selectedCandidateLabels) {
        List<String> semanticValues = new ArrayList<>();
        if (value.isTextual()) {
            semanticValues.add(value.asText(""));
        } else if (value.isArray()) {
            value.forEach(item -> semanticValues.add(item.asText("")));
        }
        return !semanticValues.isEmpty() && semanticValues.stream().allMatch(semanticValue -> {
            String normalizedValue = normalizeGroundingText(semanticValue);
            return StringUtils.hasText(normalizedValue)
                    && selectedCandidateLabels.stream().anyMatch(label ->
                            (" " + label + " ").contains(" " + normalizedValue + " "));
        });
    }

    private String normalizeGroundingText(String value) {
        return normalizeText(value)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean hasPreservedLiveOptionPredicate(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || fieldGrounding == null) {
            return false;
        }
        JsonNode original = fieldGrounding.path("originalPredicate");
        String originalField = original.path("field").asText("");
        Set<String> canonicalFields = new LinkedHashSet<>();
        fieldGrounding.path("candidates").forEach(candidate -> {
            String field = candidate.path("canonicalFilterField").asText("");
            if (StringUtils.hasText(field)) {
                canonicalFields.add(field);
            }
        });
        JsonNode filters = resolution.semanticDecision().constraints().path("filters");
        if (!filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            String field = filter.path("field").asText("");
            if ((field.equals(originalField) || canonicalFields.contains(field))
                    && isSemanticTextValue(filter.path("value"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSchemaConfirmedCanonicalLiveOptionField(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || fieldGrounding == null
                || !fieldGrounding.path("candidates").isArray()) {
            return false;
        }
        JsonNode predicate = firstSemanticTextConstraint(resolution.semanticDecision().constraints());
        if (predicate == null) {
            return false;
        }
        String authoredField = predicate.path("field").asText("");
        long exactCanonicalMatches = 0;
        for (JsonNode candidate : fieldGrounding.path("candidates")) {
            if (authoredField.equals(candidate.path("canonicalFilterField").asText(""))) {
                exactCanonicalMatches++;
            }
        }
        return exactCanonicalMatches == 1;
    }

    private boolean isCanonicalLiveOptionConstraint(JsonNode predicate, JsonNode fieldGrounding) {
        if (predicate == null
                || fieldGrounding == null
                || !fieldGrounding.path("candidates").isArray()) {
            return false;
        }
        String authoredField = predicate.path("field").asText("");
        if (!StringUtils.hasText(authoredField)) {
            return false;
        }
        for (JsonNode candidate : fieldGrounding.path("candidates")) {
            if (authoredField.equals(candidate.path("canonicalFilterField").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConfirmedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding,
            LiveOptionValueRetrievalResult confirmation) {
        JsonNode selection = selectedLiveOptionConstraint(resolution, grounding);
        if (selection == null
                || confirmation == null
                || !confirmation.valid()
                || !"selected_ids_reload".equals(confirmation.retrievalMode())
                || !grounding.canonicalFilterField().equals(confirmation.canonicalFilterField())
                || confirmation.candidates().size() != selection.path("value").size()) {
            return false;
        }
        if (StringUtils.hasText(grounding.datasetVersion())
                && !grounding.datasetVersion().equals(confirmation.datasetVersion())) {
            return false;
        }
        for (int index = 0; index < selection.path("value").size(); index++) {
            JsonNode selectedId = selection.path("value").get(index);
            JsonNode confirmedId = confirmation.candidates().get(index).id();
            if (selectedId == null || confirmedId == null || !selectedId.equals(confirmedId)) {
                return false;
            }
            for (int duplicateIndex = index + 1;
                    duplicateIndex < selection.path("value").size();
                    duplicateIndex++) {
                if (selectedId.equals(selection.path("value").get(duplicateIndex))) {
                    return false;
                }
            }
        }
        return true;
    }

    private JsonNode selectedLiveOptionConstraint(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        if (resolution == null
                || resolution.semanticDecision() == null
                || grounding == null
                || resolution.semanticDecision().constraints() == null) {
            return null;
        }
        JsonNode filters = resolution.semanticDecision().constraints().path("filters");
        if (!filters.isArray()) {
            return null;
        }
        for (JsonNode filter : filters) {
            if (grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    && "in".equals(filter.path("operator").asText(""))
                    && filter.path("value").isArray()
                    && !filter.path("value").isEmpty()) {
                return filter;
            }
        }
        return null;
    }

    private boolean requiresLiveOptionClarification(AgenticAuthoringIntentResolutionResult resolution) {
        return resolution != null
                && (resolution.pendingClarification() != null
                        || resolution.clarificationQuestions() != null
                                && !resolution.clarificationQuestions().isEmpty());
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
        AgenticAuthoringToolResult result = toolRegistry.execute(toolCall, principalContext, "retrieveEvidence");
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
            AgenticAuthoringPreviewResult preview,
            boolean canApply,
            Map<String, Object> decisionDiagnostics) {
        List<AgenticAuthoringQuickReply> replies = terminalQuickReplies(intentResolution, businessCatalogDiscovery);
        List<AgenticAuthoringQuickReply> contextual = contextualPreviewQuickReplies(request, intentResolution, preview);
        boolean requiresGovernedRepair = !canApply
                && intentResolution != null
                && List.of("create", "modify", "remove", "compose", "connect", "undo")
                        .contains(safeText(intentResolution.operationKind()))
                && !isAdvisoryCatalogIntent(intentResolution);
        if (contextual.isEmpty() && !requiresGovernedRepair) {
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
        if (requiresGovernedRepair && !hasGovernedReviewRepair(merged.values())) {
            AgenticAuthoringQuickReply repairReply = governedReviewRepairQuickReply(
                    intentResolution,
                    decisionDiagnostics);
            merged.putIfAbsent(repairReply.id(), repairReply);
        }
        return List.copyOf(merged.values());
    }

    private boolean hasGovernedReviewRepair(Iterable<AgenticAuthoringQuickReply> replies) {
        if (replies == null) {
            return false;
        }
        for (AgenticAuthoringQuickReply reply : replies) {
            JsonNode hints = reply == null ? null : reply.contextHints();
            if (hints != null
                    && hints.isObject()
                    && "governed-review-repair".equals(hints.path("kind").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private AgenticAuthoringQuickReply governedReviewRepairQuickReply(
            AgenticAuthoringIntentResolutionResult intentResolution,
            Map<String, Object> decisionDiagnostics) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("source", "governed-review-gate");
        contextHints.put("kind", "governed-review-repair");
        contextHints.put("requiresReview", true);
        if (intentResolution != null) {
            contextHints.put("operationKind", safeText(intentResolution.operationKind()));
            contextHints.put("artifactKind", safeText(intentResolution.artifactKind()));
            contextHints.put("changeKind", safeText(intentResolution.changeKind()));
            AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
            String resourcePath = selectedCandidate == null
                    ? ""
                    : safeText(selectedCandidate.resourcePath());
            if (resourcePath.isBlank() && intentResolution.target() != null) {
                resourcePath = safeText(intentResolution.target().resourcePath());
            }
            if (!resourcePath.isBlank()) {
                contextHints.put("resourcePath", resourcePath);
            }
        }
        String reviewReason = decisionDiagnostics == null
                ? ""
                : safeText((String) decisionDiagnostics.get("reviewReason"));
        if (!reviewReason.isBlank()) {
            contextHints.put("reviewReason", reviewReason);
        }
        JsonNode semanticDecision = governedReviewRepairSemanticDecision(intentResolution);
        return new AgenticAuthoringQuickReply(
                "governed-review-revise",
                "revise",
                "Revisar pontos pendentes",
                "Revise a previa bloqueada, explique os pontos que ainda precisam de decisao e proponha um ajuste seguro antes de salvar.",
                "Continua a revisao usando a decisao e as evidencias governadas deste turno.",
                "rate_review",
                "primary",
                contextHints,
                semanticDecision,
                null);
    }

    private JsonNode governedReviewRepairSemanticDecision(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.semanticDecision() == null) {
            return null;
        }
        AgenticAuthoringSemanticDecision currentDecision = intentResolution.semanticDecision();
        ObjectNode constraints = currentDecision.constraints() != null
                        && currentDecision.constraints().isObject()
                ? currentDecision.constraints().deepCopy()
                : objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", "governed-review-revise");
        constraints.put("continuationOf", "governed_review");
        if (!constraints.path("conceptKeys").isArray()) {
            constraints.putArray("conceptKeys");
        }
        return objectMapper.valueToTree(currentDecision.withConstraints(constraints));
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
        JsonNode previewPage = previewMaterializedPage(preview);
        String chartWidgetKey = previewComponentWidgetKey(preview, "praxis-chart");
        String tableWidgetKey = previewComponentWidgetKey(preview, "praxis-table");
        JsonNode chartWidgetSnapshot = previewComponentWidget(previewPage, "praxis-chart", chartWidgetKey);
        JsonNode tableWidgetSnapshot = previewComponentWidget(previewPage, "praxis-table", tableWidgetKey);
        boolean hasChart = chartWidgetSnapshot != null;
        boolean hasTable = tableWidgetSnapshot != null;
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
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
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
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
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
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
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
                    surfaceHints,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
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
                    tableWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
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
            JsonNode targetWidgetSnapshot,
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision) {
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
                null,
                request,
                activeDecision);
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
            JsonNode extraContextHints,
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision) {
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
                hints,
                contextualQuickReplySemanticDecision(
                        request,
                        activeDecision,
                        id,
                        prompt,
                        changeKind,
                        capabilityId,
                        componentId,
                        widgetKey,
                        selectedCandidate),
                null);
    }

    private JsonNode contextualQuickReplySemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision,
            String quickReplyId,
            String prompt,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode constraints = activeDecision != null
                        && activeDecision.constraints() != null
                        && activeDecision.constraints().isObject()
                ? activeDecision.constraints().deepCopy()
                : objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", quickReplyId);
        constraints.put("continuationOf", "contextual_preview_action");
        putText(constraints, "capabilityId", capabilityId);
        putText(constraints, "targetComponentId", componentId);
        putText(constraints, "targetWidgetKey", widgetKey);
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        "modify",
                        contextualArtifactKind(componentId),
                        changeKind,
                        selectedCandidate,
                        selectedCandidate == null ? List.of() : List.of(selectedCandidate),
                        activeDecision == null ? null : activeDecision.visualizationDecision(),
                        List.of(),
                        null,
                        null,
                        activeDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + quickReplyId,
                        prompt,
                        prompt,
                        "The user may select this governed component capability after preview materialization.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
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
        JsonNode plan = preview.uiCompositionPlan();
        if (plan != null && plan.isObject() && plan.path("widgets").isArray() && !plan.path("widgets").isEmpty()) {
            return plan;
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
        boolean hasExistingProjectKnowledge = request.contextHints() != null
                && request.contextHints().path("projectKnowledge").isObject();
        if (intentResolution == null && hasExistingProjectKnowledge) {
            return request;
        }
        AgenticAuthoringProjectKnowledgeQuery query = projectKnowledgeQuery(request, principalContext, intentResolution);
        if (hasExistingProjectKnowledge
                && !StringUtils.hasText(query.contextKey())
                && !StringUtils.hasText(query.resourceKey())
                && "context".equals(query.nodeType())) {
            return request;
        }
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
            if (!hasExistingProjectKnowledge
                    && !StringUtils.hasText(query.contextKey())
                    && !StringUtils.hasText(query.resourceKey())
                    && "context".equals(query.nodeType())) {
                ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                        ? request.contextHints().deepCopy()
                        : objectMapper.createObjectNode();
                contextHints.set("projectKnowledge", projectKnowledgeContext(List.of()));
                return copyWithContextHints(request, contextHints);
            }
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

    /**
     * Retrieves safe Project Knowledge after governed resource discovery, without treating any
     * candidate as selected. This closes the gap where a clarification turn could consult the API
     * catalog but terminate before the selected-candidate retrieval stage, leaving no audit of the
     * knowledge that was available for those governed resource scopes.
     */
    private AgenticAuthoringTurnStreamRequest withResourceCandidateProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            List<AgenticAuthoringCandidate> candidates) {
        if (projectKnowledgeService == null
                || eventSink.terminalReached()
                || candidates == null
                || candidates.isEmpty()) {
            return request;
        }
        LinkedHashMap<String, AgenticAuthoringProjectKnowledgeProjection> projectionsByKey =
                new LinkedHashMap<>();
        LinkedHashSet<String> resourceKeys = new LinkedHashSet<>();
        for (AgenticAuthoringCandidate candidate : candidates) {
            String resourceKey = resourceKeyFromPath(candidate == null ? null : candidate.resourcePath());
            if (!StringUtils.hasText(resourceKey) || resourceKeys.contains(resourceKey)) {
                continue;
            }
            if (resourceKeys.size() >= MAX_PROJECT_KNOWLEDGE_RESOURCE_SCOPES) {
                break;
            }
            resourceKeys.add(resourceKey);
        }
        if (resourceKeys.isEmpty()) {
            return request;
        }
        emitStatus(
                eventSink,
                "projectKnowledge.retrieve",
                "Estou buscando conhecimento governado do projeto para os recursos recuperados.");
        for (String resourceKey : resourceKeys) {
            AgenticAuthoringProjectKnowledgeQuery query = new AgenticAuthoringProjectKnowledgeQuery(
                    principalContext.tenantId(),
                    principalContext.environment(),
                    contextKeyFromResourceKey(resourceKey),
                    resourceKey,
                    scopedProjectKnowledgeKinds(),
                    null,
                    PROJECT_KNOWLEDGE_PER_RESOURCE_LIMIT,
                    null);
            for (AgenticAuthoringProjectKnowledgeProjection projection : projectKnowledgeService.retrieve(query)) {
                if (projection == null) {
                    continue;
                }
                String projectionKey = firstText(projection.knowledgeId(), projection.conceptKey());
                if (StringUtils.hasText(projectionKey)) {
                    projectionsByKey.putIfAbsent(projectionKey, projection);
                }
                if (projectionsByKey.size() >= MAX_PROJECT_KNOWLEDGE_INFLUENCES) {
                    break;
                }
            }
            if (projectionsByKey.size() >= MAX_PROJECT_KNOWLEDGE_INFLUENCES) {
                break;
            }
        }
        List<AgenticAuthoringProjectKnowledgeProjection> projections =
                List.copyOf(projectionsByKey.values());
        Map<String, Object> diagnostics = new LinkedHashMap<>(projectKnowledgeDiagnostics(projections));
        diagnostics.put("resourceScopeCount", resourceKeys.size());
        diagnostics.put("retrievalStage", "governed-resource-candidates");
        if (projections.isEmpty()) {
            diagnostics.put("result", "empty");
            eventSink.append("thought.step", safeToolProjection(
                    "projectKnowledge.result",
                    "Nao encontrei conhecimento governado adicional para os recursos recuperados.",
                    diagnostics));
            return request;
        }
        eventSink.append("thought.step", safeToolProjection(
                "projectKnowledge.retrieve",
                "Conhecimento governado do projeto recuperado para os recursos candidatos.",
                diagnostics));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
        return copyWithContextHints(request, contextHints);
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

    private AgenticAuthoringProjectKnowledgeQuery projectKnowledgeQuery(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String contextKey = firstText(
                domainCatalogHint(request, "contextKey"), contextKeyFromCandidate(intentResolution));
        String resourceKey = firstText(
                domainCatalogHint(request, "resourceKey"), resourceKeyFromCandidate(intentResolution));
        boolean macroPack = !StringUtils.hasText(contextKey) && !StringUtils.hasText(resourceKey);
        String semanticQuery = intentResolution == null ? safeText(request.userPrompt()) : null;
        boolean semanticPreIntent = macroPack && StringUtils.hasText(semanticQuery);
        return new AgenticAuthoringProjectKnowledgeQuery(
                principalContext.tenantId(),
                principalContext.environment(),
                contextKey,
                resourceKey,
                macroPack && !semanticPreIntent
                        ? List.of("context")
                        : scopedProjectKnowledgeKinds(),
                macroPack && !semanticPreIntent ? "context" : null,
                macroPack && !semanticPreIntent ? 4 : 8,
                semanticPreIntent ? semanticQuery : null);
    }

    private List<String> scopedProjectKnowledgeKinds() {
        return List.of(
                "context",
                "business_capability",
                "process",
                "business_event",
                "policy",
                "metric",
                "actor",
                "concept",
                "project_preference",
                "domain_decision_hint",
                "component_authoring_pattern",
                "resource_selection_rationale",
                "governance_constraint",
                "integration_note");
    }

    private boolean hasProjectKnowledgeScope(AgenticAuthoringProjectKnowledgeQuery query) {
        return query != null
                && (StringUtils.hasText(query.contextKey())
                        || StringUtils.hasText(query.resourceKey())
                        || "context".equals(query.nodeType())
                        || StringUtils.hasText(query.semanticQuery()));
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

    private ObjectNode nonMaterializedPreview(AgenticAuthoringTurnStreamRequest request) {
        ObjectNode preview = objectMapper.createObjectNode();
        JsonNode audit = AgenticAuthoringProjectKnowledgeAuditFactory.create(
                objectMapper,
                request == null ? null : request.contextHints(),
                null,
                null);
        if (audit != null) {
            preview.putObject("diagnostics").set("projectKnowledgeAudit", audit);
        }
        return preview;
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
        return decisionDiagnostics(intentResolution, preview, toolLoopResult, request, List.of());
    }

    private Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
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
            putProviderInvocationDiagnostics(diagnostics, request, providerInvocations);
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
                        && !Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"))
                        && !strongVerifiedSemanticDecisionEvidence(semanticDecision, selectedCandidate);
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
        putProviderInvocationDiagnostics(diagnostics, request, providerInvocations);
        diagnostics.put("requiresReview", requiresDecisionReview(diagnostics));
        String reviewReason = decisionReviewReason(diagnostics);
        if (!reviewReason.isBlank()) {
            diagnostics.put("reviewReason", reviewReason);
        }
        return diagnostics;
    }

    private boolean strongVerifiedSemanticDecisionEvidence(
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringCandidate selectedCandidate) {
        if (semanticDecision == null
                || semanticDecision.reviewRequired()
                || semanticDecision.selectedResource() == null
                || selectedCandidate == null
                || !safeText(semanticDecision.selectedResource().resourcePath())
                        .equals(safeText(selectedCandidate.resourcePath()))) {
            return false;
        }
        AgenticAuthoringSemanticDecision.RetrievalEvidence evidence = semanticDecision.retrievalEvidence();
        if (evidence == null
                || !AgenticAuthoringCandidateProvenancePolicy.SEMANTIC_RETRIEVAL.equals(evidence.retrievalSource())
                || evidence.evidence() == null) {
            return false;
        }
        return evidence.evidence().contains("llm-resource-focus")
                && evidence.evidence().contains("schema-available")
                && evidence.evidence().contains("stats-capabilities-verified");
    }

    private List<AiProviderInvocationTelemetry> providerInvocations(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        JsonNode items = intentResolution == null || intentResolution.llmDiagnostics() == null
                ? objectMapper.missingNode()
                : intentResolution.llmDiagnostics().path("resolutionTelemetry").path("providerInvocations");
        if (!items.isArray()) {
            return List.of();
        }
        List<AiProviderInvocationTelemetry> invocations = new ArrayList<>();
        for (JsonNode item : items) {
            try {
                invocations.add(objectMapper.treeToValue(item, AiProviderInvocationTelemetry.class));
            } catch (Exception ignored) {
                // Detailed diagnostics are best effort and must never affect authoring.
            }
        }
        return List.copyOf(invocations);
    }

    private void putProviderInvocationDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (!includeLlmDiagnostics(request)) {
            return;
        }
        List<AiProviderInvocationTelemetry> safeInvocations = providerInvocations == null
                ? List.of()
                : providerInvocations.stream().filter(Objects::nonNull).limit(12).toList();
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("schemaVersion", "praxis-agentic-authoring-provider-telemetry.v1");
        telemetry.put("providerInvocations", safeInvocations);
        telemetry.put("invocationCount", safeInvocations.size());
        telemetry.put("truncated", providerInvocations != null && providerInvocations.size() > safeInvocations.size());
        telemetry.put("successCount", safeInvocations.stream()
                .filter(invocation -> "success".equals(invocation.status()))
                .count());
        telemetry.put("failureCount", safeInvocations.stream()
                .filter(invocation -> "failure".equals(invocation.status()))
                .count());
        telemetry.put("latencyMs", safeInvocations.stream()
                .mapToLong(invocation -> Math.max(0L, invocation.latencyMs()))
                .sum());
        telemetry.put("inputTokens", tokenSum(safeInvocations, TokenKind.INPUT));
        telemetry.put("outputTokens", tokenSum(safeInvocations, TokenKind.OUTPUT));
        telemetry.put("cacheReadInputTokens", tokenSum(safeInvocations, TokenKind.CACHE_READ));
        telemetry.put("cacheWriteInputTokens", tokenSum(safeInvocations, TokenKind.CACHE_WRITE));
        telemetry.put("totalTokens", tokenSum(safeInvocations, TokenKind.TOTAL));
        telemetry.put("rawPromptCopied", false);
        telemetry.put("rawResponseCopied", false);
        telemetry.put("credentialsCopied", false);
        diagnostics.put("providerTelemetry", telemetry);
    }

    private boolean includeLlmDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("includeLlmDiagnostics").asBoolean(false);
    }

    private long tokenSum(List<AiProviderInvocationTelemetry> invocations, TokenKind kind) {
        return invocations.stream()
                .map(invocation -> switch (kind) {
                    case INPUT -> invocation.inputTokens();
                    case OUTPUT -> invocation.outputTokens();
                    case CACHE_READ -> invocation.cacheReadInputTokens();
                    case CACHE_WRITE -> invocation.cacheWriteInputTokens();
                    case TOTAL -> invocation.totalTokens();
                })
                .filter(Objects::nonNull)
                .filter(value -> value >= 0)
                .mapToLong(Integer::longValue)
                .sum();
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
        JsonNode contextHints = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints();
        JsonNode evidenceContext = contextHints.isMissingNode()
                ? objectMapper.missingNode()
                : contextHints.path("authoringEvidence");
        JsonNode evidence = evidenceContext.path("evidence");
        diagnostics.put("authoringEvidenceCount", evidence.isArray() ? evidence.size() : 0);
        diagnostics.put("authoringEvidenceSourceRefs", sourceRefs(evidence));
        diagnostics.put(
                "authoringEvidenceOperationCandidateIds",
                canonicalOperationIds(evidenceContext.path("operationCandidates")));
        diagnostics.put("authoringEvidenceComponentId", safeText(evidenceContext.path("componentId").asText("")));
        diagnostics.put("authoringEvidencePhase", safeText(evidenceContext.path("phase").asText("")));

        JsonNode resourceDiscovery = contextHints.path("resourceDiscovery");
        JsonNode resourceFocus = resourceDiscovery.path("resourceSearchFocus");
        diagnostics.put(
                "resourceSearchFocusPrimaryBusinessEntity",
                safeText(resourceFocus.path("primaryBusinessEntity").asText("")));
        diagnostics.put(
                "resourceSearchFocusDesiredSurface",
                safeText(resourceFocus.path("desiredSurface").asText("")));
        diagnostics.put(
                "resourceSearchFocusUncertaintyPresent",
                !safeText(resourceFocus.path("uncertainty").asText("")).isBlank());
        diagnostics.put(
                "resourceDiscoveryCandidateSummaries",
                resourceDiscoveryCandidateSummaries(resourceDiscovery.path("candidates")));
    }

    private List<String> canonicalOperationIds(JsonNode candidates) {
        if (candidates == null || !candidates.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode candidate : candidates) {
            String id = firstNonBlank(
                    candidate.path("operationId").asText(""),
                    candidate.path("id").asText(""));
            if (!id.isBlank()) {
                ids.add(id);
            }
            if (ids.size() >= 24) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    private List<Map<String, Object>> resourceDiscoveryCandidateSummaries(JsonNode candidates) {
        if (candidates == null || !candidates.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("resourcePath", safeText(candidate.path("resourcePath").asText("")));
            summary.put("score", candidate.path("score").asDouble(0d));
            List<String> evidence = new ArrayList<>();
            if (candidate.path("evidence").isArray()) {
                for (JsonNode item : candidate.path("evidence")) {
                    String value = safeText(item.asText(""));
                    if (!value.isBlank() && !evidence.contains(value)) {
                        evidence.add(value);
                    }
                    if (evidence.size() >= 16) {
                        break;
                    }
                }
            }
            summary.put("evidence", List.copyOf(evidence));
            summaries.add(Map.copyOf(summary));
            if (summaries.size() >= 12) {
                break;
            }
        }
        return List.copyOf(summaries);
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
            ObjectNode candidateNode = (ObjectNode) candidateContext(candidate);
            JsonNode fieldCatalog = analyticsFieldCatalog(discovery, candidate.resourcePath());
            if (fieldCatalog.isArray() && !fieldCatalog.isEmpty()) {
                candidateNode.set("analyticsFields", fieldCatalog);
            }
            candidates.add(candidateNode);
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

    private JsonNode analyticsFieldCatalog(
            AgenticAuthoringResourceCandidatesResult discovery,
            String resourcePath) {
        if (discovery == null || discovery.diagnostics() == null || !StringUtils.hasText(resourcePath)) {
            return objectMapper.createArrayNode();
        }
        Object catalogs = discovery.diagnostics().get("analyticsCapabilityFieldCatalogs");
        JsonNode node = objectMapper.valueToTree(catalogs);
        JsonNode catalog = node.path(resourcePath);
        return catalog.isArray() ? catalog : objectMapper.createArrayNode();
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
        return contextKeyFromResourceKey(resourceKey);
    }

    private String resourceKeyFromCandidate(AgenticAuthoringIntentResolutionResult intentResolution) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        return resourceKeyFromPath(candidate == null ? null : candidate.resourcePath());
    }

    private String contextKeyFromResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return null;
        }
        int firstDot = resourceKey.indexOf('.');
        return firstDot > 0 ? resourceKey.substring(0, firstDot) : null;
    }

    private String resourceKeyFromPath(String resourcePath) {
        if (!StringUtils.hasText(resourcePath)) {
            return null;
        }
        String path = resourcePath.trim();
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
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        if (eventSink == null || eventSink.terminalReached() || intentResolution == null) {
            return;
        }
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "praxis-agentic-authoring-intent-resolved-event.v1");
        payload.put("semanticDecisionRef", semanticDecision == null ? "" : safeText(semanticDecision.decisionId()));
        payload.put("routeClass", route == null ? "" : safeText(route.routeClass()));
        payload.put("resolved", intentResolution.valid());
        payload.put("userFacingUnderstanding", intentResolvedUserFacingUnderstanding(intentResolution, route, request));
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
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        String assistantMessage = safeText(intentResolution.assistantMessage());
        if (!assistantMessage.isBlank()) {
            return curatedResourceLabel(
                    publicAssistantMessage(assistantMessage, request),
                    intentResolution.selectedCandidate());
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
        return ensureReviewablePreviewMessage(value, request, canApply, "");
    }

    static String ensureReviewablePreviewMessage(
            String value,
            AgenticAuthoringTurnStreamRequest request,
            boolean canApply,
            String applyBlockReason) {
        String message = value == null ? "" : value.trim();
        if (!canApply) {
            if ("apply-target-missing".equals(applyBlockReason)) {
                String responseLocale = request == null || request.contextHints() == null
                        ? ""
                        : request.contextHints().path("responseLocale").asText("");
                String clarification = !responseLocale.isBlank()
                                && !responseLocale.toLowerCase(java.util.Locale.ROOT).startsWith("pt")
                        ? "The preview is ready for review, but it cannot be saved in this turn until the application target is identified."
                        : "A prévia está pronta para revisão, mas ainda não pode ser salva neste turno porque o destino de aplicação não foi identificado.";
                return message.isBlank() ? clarification : message + "\n\n" + clarification;
            }
            return message;
        }
        String normalized = java.text.Normalizer.normalize(message, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        boolean reviewMeaningPresent = normalized.contains("previa")
                || normalized.contains("pre visualizacao")
                || normalized.contains("preview")
                || normalized.contains("revis");
        if (reviewMeaningPresent) {
            return message;
        }
        String responseLocale = request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("");
        String guarantee = !responseLocale.isBlank()
                        && !responseLocale.toLowerCase(java.util.Locale.ROOT).startsWith("pt")
                ? "The preview is ready for review before saving."
                : "A prévia está pronta para revisão antes de salvar.";
        return message.isBlank() ? guarantee : message + "\n\n" + guarantee;
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

    private record LiveOptionGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            LiveOptionValueRetrievalResult result) {

        private static LiveOptionGroundingExecution none() {
            return new LiveOptionGroundingExecution(null, null);
        }
    }

    private record LiveOptionFieldGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            ObjectNode projection) {

        private static LiveOptionFieldGroundingExecution none() {
            return new LiveOptionFieldGroundingExecution(null, null);
        }
    }

    private record StaticEnumFilterGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            ObjectNode projection) {

        private static StaticEnumFilterGroundingExecution none() {
            return new StaticEnumFilterGroundingExecution(null, null);
        }
    }

    private record StaticEnumPropertyCandidate(
            String field,
            JsonNode canonicalValue,
            int fieldMatchScore) {
    }

    private enum TokenKind {
        INPUT,
        OUTPUT,
        CACHE_READ,
        CACHE_WRITE,
        TOTAL
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
