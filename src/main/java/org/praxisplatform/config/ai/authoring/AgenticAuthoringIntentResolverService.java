package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentCandidateScoringSupport.CandidatePromptAlignment;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentCandidateScoringSupport.SemanticResourceNeed;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final int MAX_ASSISTANT_MESSAGE_CHARS = 700;
    private static final double DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE = 0.02d;
    private static final int PRIMARY_ENTITY_PROJECTION_ALIGNMENT_TOLERANCE = 6;
    private static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE = "semantic-role:operational-resource";
    private static final String SEMANTIC_ROLE_ANALYTICS_PROJECTION = "semantic-role:analytics-projection";
    private static final String SEMANTIC_ROLE_PROFILE_PROJECTION = "semantic-role:profile-projection";
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringLlmIntentResolverService llmIntentResolverService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer;
    private final ObjectMapper objectMapper;
    private final String domainCatalogServiceKey;
    private final AgenticAuthoringFormCapabilityCatalog formCapabilityCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringKeywordFallbackResolver keywordFallbackResolver =
            new AgenticAuthoringKeywordFallbackResolver(
                    formCapabilityCatalog,
                    chartCapabilityCatalog);
    private final boolean legacyKeywordFallbackEnabled;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator =
            new AgenticAuthoringConversationTurnOrchestrator();
    private final AgenticAuthoringSemanticDecisionPolicy semanticDecisionPolicy =
            new AgenticAuthoringSemanticDecisionPolicy();
    private final AgenticAuthoringSemanticOrientationReconciler semanticOrientationReconciler =
            AgenticAuthoringSemanticOrientationReconciler.INSTANCE;
    private final AgenticAuthoringPresentationAffordanceDiscoveryService presentationAffordanceDiscoveryService;
    private final AgenticAuthoringIntentQuickReplySupport quickReplySupport;
    private final AgenticAuthoringApiCatalogGuidanceSupport apiCatalogGuidanceSupport;
    private final AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport;
    private final AgenticAuthoringProjectionRoleResolutionSupport projectionRoleResolutionSupport;
    private final AgenticAuthoringCandidateDiscoveryTelemetrySupport candidateDiscoveryTelemetrySupport;
    private final AgenticAuthoringIntentContextCandidateSupport contextCandidateSupport;

    public AgenticAuthoringIntentResolverService(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog) {
        this(objectMapper, apiMetadataCandidateCatalog, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        this(objectMapper,
                apiMetadataCandidateCatalog,
                llmIntentResolverService,
                componentCapabilitiesService,
                AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            String domainCatalogServiceKey) {
        this(
                objectMapper,
                apiMetadataCandidateCatalog,
                llmIntentResolverService,
                componentCapabilitiesService,
                domainCatalogServiceKey,
                null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            String domainCatalogServiceKey,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer) {
        this(
                objectMapper,
                apiMetadataCandidateCatalog,
                llmIntentResolverService,
                componentCapabilitiesService,
                domainCatalogServiceKey,
                domainCatalogCandidateEnhancer,
                true);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            String domainCatalogServiceKey,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer,
            boolean legacyKeywordFallbackEnabled) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.llmIntentResolverService = llmIntentResolverService;
        this.componentCapabilitiesService = componentCapabilitiesService;
        this.domainCatalogServiceKey = domainCatalogServiceKey;
        this.domainCatalogCandidateEnhancer = domainCatalogCandidateEnhancer;
        this.legacyKeywordFallbackEnabled = legacyKeywordFallbackEnabled;
        this.presentationAffordanceDiscoveryService =
                AgenticAuthoringPresentationAffordanceDiscoveryService.defaultService(objectMapper);
        this.quickReplySupport = new AgenticAuthoringIntentQuickReplySupport(
                this.objectMapper,
                this.domainCatalogServiceKey,
                this.presentationAffordanceDiscoveryService);
        this.apiCatalogGuidanceSupport = new AgenticAuthoringApiCatalogGuidanceSupport(
                this.objectMapper,
                this.quickReplySupport);
        this.candidateScoringSupport =
                new AgenticAuthoringIntentCandidateScoringSupport(this.quickReplySupport);
        this.projectionRoleResolutionSupport =
                new AgenticAuthoringProjectionRoleResolutionSupport(this.candidateScoringSupport);
        this.candidateDiscoveryTelemetrySupport =
                new AgenticAuthoringCandidateDiscoveryTelemetrySupport(
                        this.objectMapper,
                        this.apiMetadataCandidateCatalog,
                        this.domainCatalogCandidateEnhancer,
                        this.candidateScoringSupport,
                        this.quickReplySupport);
        this.contextCandidateSupport =
                new AgenticAuthoringIntentContextCandidateSupport(
                        this.objectMapper,
                        this.apiMetadataCandidateCatalog,
                        this.domainCatalogCandidateEnhancer,
                        this.candidateScoringSupport,
                        this.projectionRoleResolutionSupport,
                        this.candidateDiscoveryTelemetrySupport);
    }

    public AgenticAuthoringIntentResolutionResult resolve(AgenticAuthoringIntentResolutionRequest request) {
        return resolve(request, null, null, null);
    }

    public AgenticAuthoringIntentResolutionResult resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String tenantId,
            String userId,
            String environment) {
        return resolve(request, tenantId, userId, environment, null);
    }

    AgenticAuthoringIntentResolutionResult resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String tenantId,
            String userId,
            String environment,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank.");
        }
        AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                request.userPrompt(),
                request.conversationMessages(),
                request.pendingClarification());
        String rawPrompt = request.userPrompt().trim();
        boolean hasLlmIntentResolver = llmIntentResolverService != null;
        boolean governedResourceConfirmation = isGovernedResourceConfirmation(request, turn);
        boolean barePendingConfirmation = turn.answeredPendingClarification()
                && isBareConfirmationPrompt(rawPrompt);
        boolean consultativeDomainQuestion = isConsultativeDomainQuestion(rawPrompt);
        boolean consultativePlatformCapabilityQuestion = isConsultativePlatformCapabilityQuestion(rawPrompt);
        String effectivePrompt = (consultativeDomainQuestion
                || (hasLlmIntentResolver && !governedResourceConfirmation && !barePendingConfirmation))
                ? rawPrompt
                : turn.effectivePrompt();
        String prompt = normalize(effectivePrompt);
        String discoveryPrompt = normalize(turn.answeredPendingClarification() ? turn.effectivePrompt() : effectivePrompt);
        AgenticAuthoringSemanticDecision activeDecision = request.activeSemanticDecision();
        boolean serverIssuedQuickReplyContinuation = isServerIssuedQuickReplyContinuation(activeDecision);
        if (serverIssuedQuickReplyContinuation) {
            discoveryPrompt = governedContinuationDiscoveryPrompt(discoveryPrompt, activeDecision);
        }
        AgenticAuthoringSemanticRefinement semanticRefinement = semanticRefinement(prompt, activeDecision, null);
        boolean decisionMemoryRefinement = semanticRefinement.active();
        String effectiveSelectedWidgetKey = effectiveSelectedWidgetKey(request);
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage(), effectiveSelectedWidgetKey);
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), effectiveSelectedWidgetKey);
        AgenticAuthoringTarget llmGroundingTarget = target == null
                ? currentPageAnalyzer.resolveSoleComponentTarget(request.currentPage())
                : target;
        AgenticAuthoringKeywordFallbackResolution fallbackResolution = legacyKeywordFallback(prompt, currentPageSummary, target);
        String operationKind = fallbackResolution.operationKind();
        String artifactKind = fallbackResolution.artifactKind();
        String changeKind = fallbackResolution.changeKind();
        if (serverIssuedQuickReplyContinuation) {
            operationKind = valueOrUnknown(activeDecision.operationKind());
            artifactKind = valueOrUnknown(activeDecision.artifactKind());
            changeKind = valueOrUnknown(activeDecision.changeKind());
        }
        boolean contextualPreviewAction = isContextualPreviewAction(request);
        if (contextualPreviewAction) {
            operationKind = contextualPreviewOperationKind(request);
            artifactKind = contextualPreviewArtifactKind(request, artifactKind);
            changeKind = valueOrDefault(jsonText(request.contextHints(), "changeKind"), changeKind);
        }
        if (!hasLlmIntentResolver && legacyKeywordFallbackEnabled && consultativePlatformCapabilityQuestion) {
            operationKind = "explain";
            artifactKind = "component";
            changeKind = platformCapabilityChangeKind(prompt);
        }
        if (!hasLlmIntentResolver && legacyKeywordFallbackEnabled && consultativeDomainQuestion) {
            operationKind = "explore";
            artifactKind = "api_catalog";
            changeKind = "answer_api_catalog_question";
        }
        boolean shouldResolveLlmIntent = hasLlmIntentResolver
                && !governedResourceConfirmation
                && !contextualPreviewAction
                && !serverIssuedQuickReplyContinuation;
        boolean deterministicDashboardMaterialization = "dashboard".equals(artifactKind)
                && ("create".equals(operationKind) || "explore".equals(operationKind))
                && isConcreteDashboardMaterializationPrompt(prompt)
                && explicitResourcePath(prompt).isBlank()
                && !isConfirmedDataSourceSelection(prompt);
        if (deterministicDashboardMaterialization && !hasLlmIntentResolver) {
            shouldResolveLlmIntent = false;
        }
        boolean hasExplicitSourceSelection =
                !explicitResourcePath(prompt).isBlank() || isConfirmedDataSourceSelection(prompt);
        boolean conversationHistoryIsolatedForBlankCreate = shouldIsolateConversationHistoryForBlankCreate(
                request,
                operationKind,
                artifactKind,
                changeKind);
        AgenticAuthoringIntentResolutionRequest llmResolutionRequest = conversationHistoryIsolatedForBlankCreate
                ? withoutConversationMessages(request)
                : request;
        boolean deferPreLlmApiMetadataDiscovery = shouldResolveLlmIntent
                && !hasExplicitSourceSelection
                && !shouldProvidePreLlmApiMetadataDiscoveryContext(prompt)
                && (consultativeDomainQuestion || consultativePlatformCapabilityQuestion || isQuestionLike(prompt));
        if (deterministicDashboardMaterialization || isExplicitDashboardMaterializationCommand(prompt)) {
            deferPreLlmApiMetadataDiscovery = false;
        }
        boolean usePreIntentResourceDiscoveryAsPrimaryEvidence =
                shouldUsePreIntentResourceDiscoveryAsPrimaryEvidence(
                        shouldResolveLlmIntent,
                        request,
                        target);
        boolean selectedDomainDecisionFocus = hasSelectedDomainDecisionFocus(request);
        List<AgenticAuthoringCandidate> candidates = selectedDomainDecisionFocus
                ? List.of()
                : serverIssuedQuickReplyContinuation
                ? discoverResolvedContinuationCandidates(activeDecision, artifactKind, tenantId, environment)
                : usePreIntentResourceDiscoveryAsPrimaryEvidence
                        ? contextHintCandidates(request)
                        : shouldResolveLlmIntent
                                ? deferPreLlmApiMetadataDiscovery
                                        ? discoverPreLlmContextCandidates(discoveryPrompt, artifactKind, target)
                                        : discoverInitialCandidates(discoveryPrompt, artifactKind, target, tenantId, environment)
                                : discoverCandidates(discoveryPrompt, artifactKind, target, tenantId, environment);
        if (!selectedDomainDecisionFocus
                && usePreIntentResourceDiscoveryAsPrimaryEvidence
                && candidates.isEmpty()) {
            candidates = usePreIntentResourceDiscoveryAsPrimaryEvidence && !contextHintCandidates(request).isEmpty()
                    ? contextHintCandidates(request)
                    : shouldResolveLlmIntent
                    ? deferPreLlmApiMetadataDiscovery
                            ? discoverPreLlmContextCandidates(discoveryPrompt, artifactKind, target)
                            : discoverInitialCandidates(discoveryPrompt, artifactKind, target, tenantId, environment)
                    : discoverCandidates(discoveryPrompt, artifactKind, target, tenantId, environment);
        }
        if (!selectedDomainDecisionFocus && deterministicDashboardMaterialization) {
            List<AgenticAuthoringCandidate> dashboardCandidates = new ArrayList<>(candidates);
            dashboardCandidates.addAll(discoverCandidates(discoveryPrompt, "table", target, tenantId, environment));
            candidates = deduplicateCandidates(dashboardCandidates);
        }
        candidates = withContextHintCandidates(request, candidates);
        AgenticAuthoringCandidate contextHintCandidate = contextHintCandidate(
                request,
                artifactKind,
                candidates,
                tenantId,
                environment);
        if (contextualPreviewAction) {
            target = contextualPreviewTarget(request, target, contextHintCandidate);
        }
        if (contextHintCandidate != null) {
            candidates = withPriorityCandidate(candidates, contextHintCandidate);
        }
        AgenticAuthoringCandidate preLlmGovernedResourceChoiceCandidate = preLlmGovernedResourceChoiceCandidate(
                prompt,
                operationKind,
                artifactKind,
                candidates,
                contextHintCandidate);
        boolean preLlmGovernedResourceChoiceApplied = preLlmGovernedResourceChoiceCandidate != null
                || shouldApplyPreLlmGovernedResourceChoice(
                prompt,
                operationKind,
                artifactKind,
                candidates,
                contextHintCandidate);
        if (preLlmGovernedResourceChoiceApplied) {
            if (!hasLlmIntentResolver) {
                shouldResolveLlmIntent = false;
            }
            if (preLlmGovernedResourceChoiceCandidate != null) {
                candidates = withPriorityCandidate(candidates, preLlmGovernedResourceChoiceCandidate);
            }
        }
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = serverIssuedQuickReplyContinuation
                ? new AgenticAuthoringComponentCapabilitiesService().listCapabilities()
                : componentCapabilities();
        List<AgenticAuthoringCandidate> llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
        List<String> preIntentGovernedEvidenceTrace = new ArrayList<>();
        AgenticAuthoringLlmIntentResolution llmIntent = preIntentSemanticOrientationResolution(
                shouldResolveLlmIntent,
                llmResolutionRequest,
                semanticOrientation);
        if (llmIntent == null) {
            llmIntent = preIntentGovernedEvidenceResolution(
                    shouldResolveLlmIntent,
                    llmResolutionRequest,
                    effectivePrompt,
                    llmGroundingTarget,
                    llmCandidateOptions,
                    semanticOrientation,
                    preIntentGovernedEvidenceTrace);
        }
        if (llmIntent == null) {
            llmIntent = resolveLlmIntent(
                    shouldResolveLlmIntent,
                    llmResolutionRequest,
                    effectivePrompt,
                    currentPageSummary,
                    llmGroundingTarget,
                    llmCandidateOptions,
                    componentCapabilities,
                    tenantId,
                    userId,
                    environment);
        }
        llmIntent = normalizeConstrainedAuthoringResolution(
                llmIntent,
                semanticOrientation,
                llmCandidateOptions);
        llmIntent = reconcilePreIntentWorkbenchOrientation(
                llmIntent,
                semanticOrientation);
        llmIntent = failClosedForUnconfirmedAiAuthoredResourceSelection(
                request,
                semanticOrientation,
                llmCandidateOptions,
                llmIntent);
        if (shouldPromoteCompactTarget(llmIntent, llmGroundingTarget, target)) {
            target = llmGroundingTarget;
            AgenticAuthoringCandidate compactTargetCandidate = targetCandidate(target);
            if (compactTargetCandidate != null) {
                candidates = withPriorityCandidate(candidates, compactTargetCandidate);
            }
        }
        semanticRefinement = semanticRefinement(prompt, activeDecision, llmIntent);
        decisionMemoryRefinement = semanticRefinement.active();
        JsonNode llmDiagnostics = llmDiagnostics(
                llmResolutionRequest,
                effectivePrompt,
                currentPageSummary,
                llmGroundingTarget,
                llmCandidateOptions,
                componentCapabilities,
                llmIntent);
        if (llmDiagnostics instanceof ObjectNode diagnosticsObject
                && !preIntentGovernedEvidenceTrace.isEmpty()) {
            diagnosticsObject.put(
                    "preIntentGovernedEvidenceOutcome",
                    preIntentGovernedEvidenceTrace.get(preIntentGovernedEvidenceTrace.size() - 1));
        }
        boolean llmTreatsPendingAsContinuation = turn.answeredPendingClarification()
                && (isLlmFollowUpKind(llmIntent, "clarification_answer")
                || isLlmFollowUpKind(llmIntent, "refinement"));
        boolean llmTreatsPendingAsNewInstruction = turn.answeredPendingClarification()
                && isLlmFollowUpKind(llmIntent, "new_instruction");
        boolean llmSecondPassUsed = false;
        boolean primaryLlmIntentProviderFailure = isPrimaryLlmIntentProviderFailure(shouldResolveLlmIntent, llmIntent);
        boolean deterministicFallbackApplied = legacyKeywordFallbackEnabled
                && !shouldResolveLlmIntent
                && !serverIssuedQuickReplyContinuation
                || (legacyKeywordFallbackEnabled
                && shouldResolveLlmIntent
                && (llmIntent == null || (!llmIntent.resolved() && !primaryLlmIntentProviderFailure)));
        boolean semanticPolicyRefinedVisualProjection = false;
        boolean resourceDiscoveryAuthoringDriftNormalized = false;
        boolean resourceDiscoveryFocusSelectionApplied = false;
        boolean visualProjectionRefinement = isVisualProjectionRefinementPrompt(prompt, turn, currentPageSummary);
        boolean currentPageDrilldownRefinement =
                isCurrentPageDrilldownRefinementPrompt(prompt, currentPageSummary);
        if (!semanticRefinement.active() && visualProjectionRefinement) {
            semanticRefinement = visualProjectionRefinement(prompt);
        }
        if (llmTreatsPendingAsContinuation) {
            effectivePrompt = turn.effectivePrompt();
            prompt = normalize(effectivePrompt);
            fallbackResolution = legacyKeywordFallback(prompt, currentPageSummary, target);
            if (llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = primaryLlmIntentProviderFailure ? "provider_error" : fallbackResolution.changeKind();
                deterministicFallbackApplied = legacyKeywordFallbackEnabled;
            }
            candidates = usePreIntentResourceDiscoveryAsPrimaryEvidence && !contextHintCandidates(request).isEmpty()
                    ? contextHintCandidates(request)
                    : shouldResolveLlmIntent
                    ? discoverInitialCandidates(prompt, artifactKind, target, tenantId, environment)
                    : discoverCandidates(prompt, artifactKind, target, tenantId, environment);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
            llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
        } else if (llmTreatsPendingAsNewInstruction) {
            effectivePrompt = rawPrompt;
            prompt = normalize(effectivePrompt);
            fallbackResolution = legacyKeywordFallback(prompt, currentPageSummary, target);
            if (llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = primaryLlmIntentProviderFailure ? "provider_error" : fallbackResolution.changeKind();
                deterministicFallbackApplied = legacyKeywordFallbackEnabled;
            }
            candidates = usePreIntentResourceDiscoveryAsPrimaryEvidence && !contextHintCandidates(request).isEmpty()
                    ? contextHintCandidates(request)
                    : shouldResolveLlmIntent
                    ? discoverInitialCandidates(prompt, artifactKind, target, tenantId, environment)
                    : discoverCandidates(prompt, artifactKind, target, tenantId, environment);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
            llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
        }
        if (!primaryLlmIntentProviderFailure
                && (llmIntent == null || !llmIntent.resolved())
                && !hasLlmIntentResolver
                && consultativeDomainQuestion) {
            operationKind = "explore";
            artifactKind = "api_catalog";
            changeKind = "answer_api_catalog_question";
        }
        boolean postIntentApiCatalogCandidateDiscoverySkipped = false;
        if (llmIntent != null && llmIntent.resolved()) {
            operationKind = valueOrUnknown(llmIntent.operationKind());
            artifactKind = valueOrUnknown(llmIntent.artifactKind());
            changeKind = valueOrUnknown(llmIntent.changeKind());
            boolean llmCandidateBundleHasExplicitSource = hasExplicitSourceCandidate(llmCandidateOptions);
            // Documentary refinement always runs; verified operations are preserved below only when
            // the server-reconstructed verification envelope proves them (never from candidate markers).
            String llmResourceSearchQuery = llmCandidateBundleHasExplicitSource
                    ? ""
                    : consultativePlatformCapabilityQuestion
                    && (!isDomainDataCatalogQuestion(prompt) || isConsultativeFormPolicyQuestion(prompt))
                    ? ""
                    : consultativeResourceSearchQuery(llmIntent).trim();
            if (shouldSkipPostIntentApiCatalogCandidateDiscovery(
                    prompt,
                    operationKind,
                    artifactKind,
                    changeKind,
                    llmIntent)) {
                llmResourceSearchQuery = "";
                postIntentApiCatalogCandidateDiscoverySkipped = true;
            }
            if (!llmResourceSearchQuery.isBlank()) {
                boolean emptyCandidateSetBeforeSearch = candidates.isEmpty();
                List<AgenticAuthoringCandidate> refinedCandidates = new ArrayList<>(candidates);
                refinedCandidates.addAll(discoverCandidates(
                        normalize(llmResourceSearchQuery),
                        artifactKind,
                        target,
                        tenantId,
                        environment));
                refinedCandidates.addAll(contextHintCandidates(request));
                candidates = deduplicateCandidates(refinedCandidates);
                if (!emptyCandidateSetBeforeSearch) {
                    List<AgenticAuthoringCandidate> verifiedOperations = candidates.stream()
                            .filter(candidate -> verifiedOperationForCandidate(request, candidate) != null)
                            .toList();
                    List<AgenticAuthoringCandidate> documentaryCandidates = candidates.stream()
                            .filter(candidate -> !verifiedOperations.contains(candidate))
                            .toList();
                    List<AgenticAuthoringCandidate> grounded = new ArrayList<>(verifiedOperations);
                    grounded.addAll(groundCandidates(
                            normalize(llmResourceSearchQuery),
                            documentaryCandidates,
                            tenantId,
                            environment));
                    candidates = deduplicateCandidates(grounded);
                }
                llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                    llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
                }
                if (shouldResolveLlmIntentAfterCandidateRefinement(
                        prompt,
                        llmIntent,
                        operationKind,
                        artifactKind,
                        changeKind,
                        llmCandidateOptions)) {
                    AgenticAuthoringLlmIntentResolution refinedLlmIntent = resolveLlmIntentAfterCandidateRefinement(
                            shouldResolveLlmIntent,
                            llmResolutionRequest,
                            effectivePrompt,
                            currentPageSummary,
                            target,
                            llmCandidateOptions,
                            componentCapabilities,
                            llmIntent,
                            tenantId,
                            userId,
                            environment);
                    if (refinedLlmIntent != llmIntent) {
                        llmIntent = refinedLlmIntent;
                        llmSecondPassUsed = true;
                        operationKind = valueOrUnknown(llmIntent.operationKind());
                        artifactKind = valueOrUnknown(llmIntent.artifactKind());
                        changeKind = valueOrUnknown(llmIntent.changeKind());
                    }
                }
            } else if (candidates.isEmpty() && !postIntentApiCatalogCandidateDiscoverySkipped) {
                candidates = discoverCandidates(prompt, artifactKind, target, tenantId, environment);
            }
        } else if (llmIntent != null) {
            if (primaryLlmIntentProviderFailure) {
                operationKind = "unknown";
                artifactKind = "unknown";
                changeKind = "provider_error";
                deterministicFallbackApplied = false;
            } else {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = legacyKeywordFallbackEnabled;
                if (candidates.isEmpty()
                        || isBroadArtifactDiscoveryOnly(candidates)) {
                    candidates = discoverCandidates(prompt, artifactKind, target, tenantId, environment);
                    candidates = withContextHintCandidates(request, candidates);
                    contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
                    if (contextHintCandidate != null) {
                        candidates = withPriorityCandidate(candidates, contextHintCandidate);
                    }
                }
            }
        } else if (shouldResolveLlmIntent && !"unknown".equals(artifactKind)) {
            deterministicFallbackApplied = legacyKeywordFallbackEnabled;
            if (candidates.isEmpty()
                    || isBroadArtifactDiscoveryOnly(candidates)) {
                candidates = discoverCandidates(prompt, artifactKind, target, tenantId, environment);
                candidates = withContextHintCandidates(request, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
            }
        }
        if (contextualPreviewAction) {
            operationKind = contextualPreviewOperationKind(request);
            artifactKind = contextualPreviewArtifactKind(request, artifactKind);
            changeKind = valueOrDefault(jsonText(request.contextHints(), "changeKind"), changeKind);
            deterministicFallbackApplied = false;
        }
        boolean llmRequiresGovernedAuthoring = requiresGovernedAuthoring(llmIntent);
        boolean explicitResourcePathSelectionEnabled = !explicitResourcePath(prompt).isBlank()
                || llmRequiresGovernedAuthoring
                || "shared_rule_authoring".equals(requestedAuthoringFlow(request));
        AgenticAuthoringCandidate explicitResourcePathCandidate = explicitResourcePathSelectionEnabled
                ? explicitResourcePathCandidate(prompt, artifactKind, candidates)
                : null;
        if (explicitResourcePathCandidate != null) {
            candidates = withPriorityCandidate(candidates, explicitResourcePathCandidate);
        }
        boolean explicitLocalUiComposition = isExplicitLocalUiCompositionPrompt(prompt);
        boolean explicitLocalPageComposition = explicitLocalUiComposition
                && isExplicitLocalPageCompositionPrompt(prompt);
        boolean explicitLocalTargetedComposition = explicitLocalUiComposition
                && (explicitLocalPageComposition || hasExplicitLocalComponentTarget(request, artifactKind));
        if (explicitLocalPageComposition) {
            artifactKind = "page";
            if (!isMaterializablePageCompositionOperation(operationKind)) {
                operationKind = materializablePageCompositionOperation(operationKind, prompt);
            }
            changeKind = materializablePageCompositionChangeKind(changeKind, operationKind, prompt);
        } else if (explicitLocalTargetedComposition) {
            artifactKind = explicitLocalTargetedArtifactKind(request, prompt, artifactKind);
            if (!isMaterializablePageCompositionOperation(operationKind)) {
                operationKind = materializablePageCompositionOperation(operationKind, prompt);
            }
            changeKind = materializablePageCompositionChangeKind(changeKind, operationKind, prompt);
        } else if (shouldPromoteTargetlessBusinessDashboardPrompt(prompt, operationKind, artifactKind, target)) {
            operationKind = "create";
            changeKind = businessDashboardCreationChangeKind(prompt);
        }
        boolean dataSourceReplacementPrompt = isDataSourceReplacementPrompt(prompt)
                && !isExplicitSourcePreservePrompt(prompt);
        Optional<String> currentChartTypeModification = currentChartTypeModificationChangeKind(
                prompt,
                request.currentPage(),
                dataSourceReplacementPrompt);
        if ((legacyKeywordFallbackEnabled || (llmIntent != null && llmIntent.resolved()))
                && currentChartTypeModification.isPresent()) {
            operationKind = "modify";
            artifactKind = "chart";
            changeKind = currentChartTypeModification.get();
            target = currentPageAnalyzer.resolveFirstComponentTarget(request.currentPage(), "praxis-chart");
        }
        AgenticAuthoringCandidate currentPageBoundCandidate = (visualProjectionRefinement
                || currentPageDrilldownRefinement)
                && !dataSourceReplacementPrompt
                && !explicitLocalUiComposition
                && !shouldDetachVisualProjectionFromCurrentResource(prompt, currentPageSummary, candidates)
                ? currentPageBoundResourceCandidate(currentPageSummary, candidates, artifactKind)
                : null;
        if (currentPageBoundCandidate == null && currentPageDrilldownRefinement && !dataSourceReplacementPrompt) {
            AgenticAuthoringCandidate activeResourceCandidate = activeDecisionCandidate(activeDecision, "dashboard");
            currentPageBoundCandidate = activeResourceCandidate != null
                    ? withEvidence(activeResourceCandidate, "current-page-refinement-active-decision-resource")
                    : null;
        }
        if (currentPageBoundCandidate == null
                && currentPageDrilldownRefinement
                && !dataSourceReplacementPrompt
                && contextHintCandidate != null) {
            currentPageBoundCandidate = withEvidence(
                    contextHintCandidate,
                    "current-page-refinement-context-resource");
        }
        if (currentPageBoundCandidate != null) {
            operationKind = "create";
            artifactKind = "dashboard";
            changeKind = currentPageDrilldownRefinement ? "create_chart_drilldown" : "create_artifact";
            candidates = withPriorityCandidate(candidates, currentPageBoundCandidate);
            semanticPolicyRefinedVisualProjection = true;
        }
        if (isDataSourceRefinement(semanticRefinement)
                || ((legacyKeywordFallbackEnabled || (llmIntent != null && llmIntent.resolved()))
                        && dataSourceReplacementPrompt)) {
            operationKind = "create";
            artifactKind = refinementReplacement(semanticRefinement, "artifactKind", artifactKind);
            if ("unknown".equals(artifactKind)) {
                artifactKind = "dashboard";
            }
            changeKind = "create_artifact";
        }
        candidates = filterConsultativeApiCatalogCandidates(prompt, artifactKind, candidates);
        if ("api_catalog".equals(artifactKind)) {
            candidates = deduplicateCandidates(candidates);
        }
        candidates = rankCandidatesForDecision(candidates, artifactKind, prompt);
        List<AgenticAuthoringCandidate> rankedCandidatesForSelectionReview = candidates;
        AgenticAuthoringCandidate selectedCandidate = explicitLocalUiComposition
                || primaryLlmIntentProviderFailure
                ? null
                : selectCandidate(candidates, target, operationKind, artifactKind, prompt);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectContextHintCandidate(preLlmGovernedResourceChoiceCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectLlmCandidate(request, llmIntent, candidates, selectedCandidate, artifactKind, prompt);
        boolean llmResourceSelectionOverriddenByPromptAlignment = !explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && llmResourceSelectionOverriddenByPromptAlignment(
                        llmIntent,
                        candidates,
                        selectedCandidate,
                        artifactKind,
                        prompt);
        boolean llmResourceSelectionOverriddenByGovernedRanking = !explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && llmResourceSelectionOverriddenByGovernedRanking(
                        request,
                        llmIntent,
                        candidates,
                        selectedCandidate,
                        artifactKind,
                        prompt);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectContextHintCandidate(preLlmGovernedResourceChoiceCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectFormWriteCandidate(artifactKind, candidates, selectedCandidate, prompt);
        selectedCandidate = explicitLocalUiComposition || primaryLlmIntentProviderFailure
                ? null
                : selectContextHintCandidate(explicitResourcePathCandidate, candidates, selectedCandidate);
        if (shouldSuppressWeakUnresolvedLlmSelection(
                deterministicFallbackApplied,
                llmIntent,
                operationKind,
                artifactKind,
                selectedCandidate)) {
            selectedCandidate = null;
        }
        AgenticAuthoringSemanticDecisionPolicy.AgenticAuthoringSemanticDecision policyDecision =
                semanticDecisionPolicy.apply(new AgenticAuthoringSemanticDecisionPolicy.Input(
                        prompt,
                        rawPrompt,
                        semanticRawPrompt(request, rawPrompt),
                        operationKind,
                        artifactKind,
                        changeKind,
                        selectedCandidate,
                        candidates,
                        contextHintCandidate,
                        llmIntent,
                        isOptionalDataSourceHint(prompt),
                        isResourceChoiceClarificationAnswer(turn, contextHintCandidate, artifactKind, operationKind),
                        governedResourceConfirmation,
                        jsonText(request.contextHints(), "operationKind"),
                        jsonText(request.contextHints(), "artifactKind"),
                        jsonText(request.contextHints(), "changeKind")));
        if (policyDecision != null) {
            operationKind = policyDecision.operationKind();
            artifactKind = policyDecision.artifactKind();
            changeKind = policyDecision.changeKind();
            selectedCandidate = policyDecision.selectedCandidate();
            if (selectedCandidate != null) {
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if (llmSingleChartDecisionRejectedForOpenOperationalPrompt(prompt, llmIntent)
                && "chart".equals(artifactKind)) {
            artifactKind = "page";
            if ("create_chart".equals(changeKind)) {
                changeKind = "create_artifact";
            }
        }
        if ((legacyKeywordFallbackEnabled || (llmIntent != null && llmIntent.resolved()))
                && currentChartTypeModification.isPresent()) {
            operationKind = "modify";
            artifactKind = "chart";
            changeKind = currentChartTypeModification.get();
        } else if (!hasPageWidgets(request.currentPage())
                && shouldExposeAsChartArtifact(prompt, operationKind, artifactKind, changeKind, llmIntent)) {
            artifactKind = "chart";
        }
        AgenticAuthoringCandidate visualMaterializationCandidate = visualMaterializationCandidate(
                prompt,
                operationKind,
                artifactKind,
                candidates);
        if (visualMaterializationCandidate != null
                && (selectedCandidate == null
                        || !promptMentionsSpecificCandidateToken(prompt, selectedCandidate)
                        || (promptMatchesCandidatePathIdentity(prompt, visualMaterializationCandidate)
                                && !promptMatchesCandidatePathIdentity(prompt, selectedCandidate)))
                && !sameCandidate(visualMaterializationCandidate, selectedCandidate)) {
            selectedCandidate = visualMaterializationCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
        }
        AgenticAuthoringCandidate analyticalRoleCandidate = analyticalRoleCandidateForComparisonPrompt(
                prompt,
                artifactKind,
                selectedCandidate,
                candidates);
        if (analyticalRoleCandidate != null && !sameCandidate(analyticalRoleCandidate, selectedCandidate)) {
            selectedCandidate = analyticalRoleCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
        }
        if (contextualPreviewAction && !currentPageDrilldownRefinement) {
            operationKind = contextualPreviewOperationKind(request);
            artifactKind = contextualPreviewArtifactKind(request, artifactKind);
            changeKind = valueOrDefault(jsonText(request.contextHints(), "changeKind"), changeKind);
            target = contextualPreviewTarget(request, target, contextHintCandidate);
            AgenticAuthoringCandidate currentComponentCandidate = targetCandidate(target);
            if (currentComponentCandidate != null) {
                selectedCandidate = currentComponentCandidate;
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if ("create_chart_drilldown".equals(changeKind)) {
            artifactKind = "dashboard";
        }
        if (semanticPolicyRefinedVisualProjection && currentPageBoundCandidate != null && !dataSourceReplacementPrompt) {
            selectedCandidate = currentPageBoundCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
        }
        if (decisionMemoryRefinement) {
            String refinedArtifactKind = refinementReplacement(
                    semanticRefinement,
                    "artifactKind",
                    artifactKind);
            AgenticAuthoringCandidate activeDecisionCandidate = semanticRefinement.preservesResource()
                    ? activeDecisionCandidate(activeDecision, refinedArtifactKind)
                    : null;
            if (activeDecisionCandidate != null) {
                operationKind = "create";
                artifactKind = refinedArtifactKind;
                changeKind = "create_artifact";
                selectedCandidate = activeDecisionCandidate;
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if (serverIssuedQuickReplyContinuation) {
            operationKind = valueOrUnknown(activeDecision.operationKind());
            artifactKind = valueOrUnknown(activeDecision.artifactKind());
            changeKind = valueOrUnknown(activeDecision.changeKind());
        }
        boolean unresolvedLlmWeakLexicalSelectionDeferred = shouldDeferUnresolvedLlmWeakLexicalSelection(
                llmIntent,
                selectedCandidate,
                prompt,
                candidates);
        if (unresolvedLlmWeakLexicalSelectionDeferred) {
            selectedCandidate = null;
        }
        boolean consultativeWeakLexicalSelectionDeferred = shouldDeferWeakLexicalConsultativeSelection(
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate);
        if (consultativeWeakLexicalSelectionDeferred) {
            selectedCandidate = null;
        }
        boolean apiCatalogWeakLexicalSelectionDeferred = shouldDeferWeakLexicalApiCatalogSelection(
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate);
        if (apiCatalogWeakLexicalSelectionDeferred) {
            selectedCandidate = null;
        }
        target = resolveImplicitComponentTarget(
                request.currentPage(),
                target,
                operationKind,
                artifactKind,
                changeKind);
        if ("praxis-chart".equals(valueOrDefault(target == null ? null : target.componentId(), ""))
                && chartCapabilityCatalog.resolveChangeKind(prompt)
                        .filter("set_chart_type"::equals)
                        .isPresent()) {
            operationKind = "modify";
            artifactKind = "chart";
            changeKind = "set_chart_type";
        }
        AgenticAuthoringCandidate currentComponentResourceCandidate = shouldPreserveCurrentComponentResource(
                operationKind,
                artifactKind,
                changeKind,
                target,
                dataSourceReplacementPrompt)
                ? targetCandidate(target)
                : null;
        if (currentComponentResourceCandidate != null) {
            selectedCandidate = currentComponentResourceCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
        }
        if (contextualPreviewAction) {
            target = contextualPreviewTarget(request, target, contextHintCandidate);
            AgenticAuthoringCandidate currentComponentCandidate = targetCandidate(target);
            if (currentComponentCandidate != null) {
                selectedCandidate = currentComponentCandidate;
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if (selectedCandidate == null
                && !explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && !unresolvedLlmWeakLexicalSelectionDeferred
                && !consultativeWeakLexicalSelectionDeferred
                && !apiCatalogWeakLexicalSelectionDeferred) {
            selectedCandidate = selectCandidate(candidates, target, operationKind, artifactKind, prompt);
            if (selectedCandidate == null && !shouldDetachCurrentTarget(prompt, artifactKind, target)) {
                selectedCandidate = targetCandidate(target);
            }
            if (selectedCandidate != null) {
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if (hasMaterializableResourceDiscoveryContext(request)
                && !explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && (selectedCandidate == null
                || !hasEvidence(selectedCandidate, "explicit-resource-path")
                && !hasEvidence(selectedCandidate, "quick-reply-context")
                && !hasEvidence(selectedCandidate, "current-page-target-resource")
                && (!promptMatchesCandidatePathIdentity(prompt, selectedCandidate)
                || hasRoleProjectionMismatchForGenericOperation(selectedCandidate)
                || hasProjectionResourceDiscoveryFocus(request, artifactKind)))) {
            AgenticAuthoringCandidate focusedCandidate = resourceDiscoveryFocusedCandidate(
                    request,
                    prompt,
                    artifactKind,
                    candidates);
            if (focusedCandidate != null
                    && (selectedCandidate == null
                    || !shouldPreserveSelectedProjectionForSemanticNeed(
                    request,
                    prompt,
                    artifactKind,
                    selectedCandidate,
                    focusedCandidate)
                    && shouldPreferResourceDiscoveryFocusedCandidate(request, focusedCandidate, selectedCandidate))) {
                selectedCandidate = focusedCandidate;
                candidates = withPriorityCandidate(candidates, selectedCandidate);
                resourceDiscoveryFocusSelectionApplied = true;
            }
        }
        boolean isGoverned = deterministicFallbackApplied
                ? isExplicitGovernedBusinessRulePrompt(prompt)
                : llmRequiresGovernedAuthoring;
        if (!explicitLocalUiComposition
                && isGoverned
                && candidates != null
                && !candidates.isEmpty()) {
            AgenticAuthoringCandidate promptAlignedGovernedRuleCandidate =
                    promptAlignedGovernedRuleCandidate(prompt, candidates);
            if (selectedCandidate != null && hasEvidence(selectedCandidate, "explicit-resource-path")) {
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            } else if (promptAlignedGovernedRuleCandidate != null) {
                selectedCandidate = promptAlignedGovernedRuleCandidate;
            } else if (selectedCandidate == null) {
                selectedCandidate = selectCandidate(
                        candidates,
                        target,
                        operationKind,
                        discoveryArtifactKindForPrompt(prompt, artifactKind),
                        prompt);
            }
            if (selectedCandidate == null) {
                selectedCandidate = candidates.get(0);
            }
            candidates = withPriorityCandidate(candidates, selectedCandidate);
        }
        AgenticAuthoringCandidate finalGovernedOperationalCandidate =
                strongerGovernedOperationalCandidateForFinalSelection(prompt, artifactKind, selectedCandidate, candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && !isExplicitGovernedBusinessRulePrompt(prompt)
                && finalGovernedOperationalCandidate != null
                && selectedCandidate != null
                && !sameCandidate(finalGovernedOperationalCandidate, selectedCandidate)
                && !promptMatchesCandidatePathIdentity(prompt, selectedCandidate)) {
            selectedCandidate = finalGovernedOperationalCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            llmResourceSelectionOverriddenByPromptAlignment = false;
            llmResourceSelectionOverriddenByGovernedRanking = true;
        }
        AgenticAuthoringCandidate scopedResourceDiscoveryCandidate =
                domainDiscoveryScopedOperationalCandidateForResourceDiscovery(
                        request,
                        prompt,
                        artifactKind,
                        selectedCandidate,
                        candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && scopedResourceDiscoveryCandidate != null
                && selectedCandidate != null
                && !sameCandidate(scopedResourceDiscoveryCandidate, selectedCandidate)) {
            selectedCandidate = scopedResourceDiscoveryCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            resourceDiscoveryFocusSelectionApplied = true;
            llmResourceSelectionOverriddenByPromptAlignment = false;
            llmResourceSelectionOverriddenByGovernedRanking = true;
        }
        if (hasMaterializableResourceDiscoveryContext(request)
                && !explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && selectedCandidate != null
                && !hasEvidence(selectedCandidate, "explicit-resource-path")
                && !hasEvidence(selectedCandidate, "quick-reply-context")
                && !hasEvidence(selectedCandidate, "current-page-target-resource")
                && (!promptMentionsSpecificCandidateToken(prompt, selectedCandidate)
                || hasRoleProjectionMismatchForGenericOperation(selectedCandidate)
                || hasProjectionResourceDiscoveryFocus(request, artifactKind))
                && (!promptMatchesCandidatePathIdentity(prompt, selectedCandidate)
                || hasRoleProjectionMismatchForGenericOperation(selectedCandidate)
                || hasProjectionResourceDiscoveryFocus(request, artifactKind))) {
            AgenticAuthoringCandidate focusedCandidate = resourceDiscoveryFocusedCandidate(
                    request,
                    prompt,
                    artifactKind,
                    candidates);
            if (focusedCandidate != null
                    && !shouldPreserveSelectedProjectionForSemanticNeed(
                    request,
                    prompt,
                    artifactKind,
                    selectedCandidate,
                    focusedCandidate)
                    && shouldPreferResourceDiscoveryFocusedCandidate(request, focusedCandidate, selectedCandidate)) {
                selectedCandidate = focusedCandidate;
                candidates = withPriorityCandidate(candidates, selectedCandidate);
                resourceDiscoveryFocusSelectionApplied = true;
                if (llmIntent != null && !valueOrDefault(llmIntent.selectedResourcePath(), "").isBlank()) {
                    llmResourceSelectionOverriddenByPromptAlignment = true;
                }
            }
        }
        AgenticAuthoringCandidate materializableGovernedOperationalCandidate =
                materializableResourceDiscoveryGovernedOperationalCandidate(
                        request,
                        prompt,
                        artifactKind,
                        selectedCandidate,
                        candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && materializableGovernedOperationalCandidate != null
                && selectedCandidate != null
                && !sameCandidate(materializableGovernedOperationalCandidate, selectedCandidate)) {
            selectedCandidate = materializableGovernedOperationalCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            resourceDiscoveryFocusSelectionApplied = true;
            llmResourceSelectionOverriddenByPromptAlignment = false;
            llmResourceSelectionOverriddenByGovernedRanking = true;
            // Candidate reconciliation changes grounding, never the primary intent.
        }
        AgenticAuthoringCandidate dedicatedProjectionCandidate =
                strongerDedicatedProjectionCandidateForGovernedNeed(
                        request,
                        prompt,
                        artifactKind,
                        selectedCandidate,
                        candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && dedicatedProjectionCandidate != null
                && selectedCandidate != null
                && !sameCandidate(dedicatedProjectionCandidate, selectedCandidate)) {
            selectedCandidate = dedicatedProjectionCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            resourceDiscoveryFocusSelectionApplied = true;
            llmResourceSelectionOverriddenByPromptAlignment = false;
        }
        AgenticAuthoringCandidate projectionFocusedCandidate = projectionResourceDiscoveryFocusedCandidate(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && projectionFocusedCandidate != null
                && selectedCandidate != null
                && !sameCandidate(projectionFocusedCandidate, selectedCandidate)
                && (isProjectionCandidateForSemanticNeed(request, prompt, artifactKind, projectionFocusedCandidate)
                || shouldPreferResourceDiscoveryFocusedCandidate(request, projectionFocusedCandidate, selectedCandidate))) {
            selectedCandidate = projectionFocusedCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            resourceDiscoveryFocusSelectionApplied = true;
            llmResourceSelectionOverriddenByPromptAlignment = false;
        }
        AgenticAuthoringCandidate finalSelectionGovernedOperationalCandidate =
                strongerGovernedOperationalCandidateForFinalSelection(
                        prompt,
                        artifactKind,
                        selectedCandidate,
                        candidates);
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && hasMaterializableResourceDiscoveryContext(request)
                && hasBusinessDataAuthoringSignal(request, prompt)
                && selectedCandidate != null
                && (isWeakLexicalCandidate(selectedCandidate)
                || hasEvidence(selectedCandidate, "broad-artifact-discovery")
                && !hasEvidence(selectedCandidate, "tool-search-api-resources"))
                && finalSelectionGovernedOperationalCandidate != null
                && !sameCandidate(finalSelectionGovernedOperationalCandidate, selectedCandidate)) {
            selectedCandidate = finalSelectionGovernedOperationalCandidate;
            candidates = withPriorityCandidate(candidates, selectedCandidate);
            resourceDiscoveryFocusSelectionApplied = true;
            llmResourceSelectionOverriddenByPromptAlignment = false;
            llmResourceSelectionOverriddenByGovernedRanking = true;
        }
        if (!explicitLocalUiComposition
                && !primaryLlmIntentProviderFailure
                && deterministicFallbackApplied
                && !"modify".equals(operationKind)
                && selectedCandidate != null
                && hasMaterializableResourceDiscoveryContext(request)
                && hasBusinessDataAuthoringSignal(request, prompt)
                && hasEvidence(selectedCandidate, "tool-search-api-resources")
                && hasEvidence(selectedCandidate, "semantic-retrieval")
                && hasTrustedSelectionEvidence(selectedCandidate)
                && hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                && !isDerivedProjectionCandidate(selectedCandidate)
                && semanticResourceNeed(prompt, materializableResourceDiscoveryArtifactKind(request, artifactKind))
                == SemanticResourceNeed.GENERIC_OPERATIONAL) {
            operationKind = "create";
            artifactKind = materializableResourceDiscoveryArtifactKind(request, "page");
            changeKind = "create_artifact";
            resourceDiscoveryAuthoringDriftNormalized = true;
        }
        boolean llmPlatformGuidance = llmIntent != null
                && ("platform_guidance".equals(llmIntent.semanticIntentClass())
                        || (llmIntent.resolved()
                                && ("explain".equals(operationKind) || "explore".equals(operationKind))
                                && "component".equals(artifactKind)
                                && ("answer_component_catalog_question".equals(changeKind)
                                        || "answer_component_capability_question".equals(changeKind))));
        boolean localHistoryUndo = "undo".equals(operationKind)
                && "undo_last_local_change".equals(changeKind);
        boolean llmOutOfScope = llmIntent != null
                && "out_of_scope".equals(llmIntent.semanticIntentClass());
        if (localHistoryUndo || llmOutOfScope) {
            selectedCandidate = null;
            candidates = List.of();
        }
        boolean platformGuidanceResourceCandidatesCleared = false;
        if (llmPlatformGuidance) {
            platformGuidanceResourceCandidatesCleared = selectedCandidate != null
                    || candidates != null && !candidates.isEmpty();
            selectedCandidate = null;
            candidates = List.of();
        }
        // Pre-intent tool results and late candidate reconciliation are evidence, not
        // authority to replace an unresolved primary interpretation. Revalidate this
        // boundary after all projection policies, including historical lexical paths.
        boolean unresolvedPrimaryLlmIntent = shouldResolveLlmIntent
                && (llmIntent == null || !llmIntent.resolved());
        if (primaryLlmIntentProviderFailure || unresolvedPrimaryLlmIntent) {
            operationKind = "unknown";
            artifactKind = "unknown";
            changeKind = primaryLlmIntentProviderFailure ? "provider_error" : "needs_clarification";
            selectedCandidate = null;
            deterministicFallbackApplied = false;
            resourceDiscoveryAuthoringDriftNormalized = false;
        }
        // Validate the AI-authored focus only after canonical candidate reconciliation.
        // At this point the selection can include explicit source binding and governed
        // ranking evidence that is intentionally unavailable to the initial LLM call.
        // Applying this gate earlier would incorrectly retain a clarification state even
        // when a canonical candidate has subsequently been verified.
        AgenticAuthoringCandidate verifiedResolvedCollection = verifiedCollectionForResolvedIntent(
                request, llmIntent, artifactKind, candidates, tenantId, environment);
        if (!explicitLocalUiComposition && verifiedResolvedCollection != null) {
            selectedCandidate = verifiedResolvedCollection;
        }
        boolean unconfirmedAiAuthoredResourceSelection = !unresolvedPrimaryLlmIntent
                && !explicitLocalUiComposition
                && hasUnconfirmedAiAuthoredResourceFocus(request, selectedCandidate);
        if (unconfirmedAiAuthoredResourceSelection) {
            llmIntent = withLlmWarning(
                    llmIntent,
                    "llm-resource-selection-unconfirmed-by-ai-authored-focus");
        }
        if (unconfirmedAiAuthoredResourceSelection) {
            // This is a post-semantic grounding gate. It never routes intent from user text:
            // the AI-authored focus declared the uncertainty, and canonical candidate evidence
            // proves that the selected resource was not anchored to the requested entity.
            operationKind = "unknown";
            artifactKind = "unknown";
            changeKind = "needs_clarification";
            selectedCandidate = null;
            deterministicFallbackApplied = false;
        }
        changeKind = normalizeTargetlessCreationChangeKind(
                operationKind,
                artifactKind,
                changeKind,
                target);
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                changeKind,
                target,
                selectedCandidate,
                candidates,
                isManifestOwnedComponentOperation(request, changeKind, componentCapabilities));
        gate = withPromptSpecificGateMessages(gate, rawPrompt, prompt, operationKind, artifactKind, selectedCandidate, turn);
        gate = withSharedRuleAuthoringGate(gate, request, prompt, selectedCandidate, llmRequiresGovernedAuthoring, operationKind, deterministicFallbackApplied);
        gate = withExplicitLocalUiCompositionGate(gate, explicitLocalUiComposition, explicitLocalTargetedComposition);
        gate = withPrimaryLlmProviderFailureGate(gate, primaryLlmIntentProviderFailure);
        List<String> questions = clarificationQuestions(gate, operationKind, artifactKind, selectedCandidate, candidates);
        questions = llmClarificationQuestions(llmIntent, gate, questions);
        boolean answeredBareDomainClarification = turn.answeredPendingClarification()
                && !llmTreatsPendingAsNewInstruction
                && isBareDomainPrompt(turn.sourcePrompt());
        String assistantMessage = assistantMessage(
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                gate,
                answeredBareDomainClarification,
                componentCapabilities);
        if ((!hasGovernedClarificationGate(gate) || shouldUseLlmArtifactClarification(gate))
                && llmIntent != null
                && (llmIntent.resolved() || unconfirmedAiAuthoredResourceSelection)
                && llmIntent.assistantMessage() != null
                && !llmIntent.assistantMessage().isBlank()
                && !shouldSuppressLlmAssistantMessageForExplicitLocalComposition(explicitLocalUiComposition, llmIntent)
                && !shouldUseDeterministicCurrentComponentAssistantMessage(
                        operationKind,
                        artifactKind,
                        changeKind,
                        selectedCandidate)) {
            assistantMessage = llmIntent.assistantMessage();
        }
        if (primaryLlmIntentProviderFailure) {
            assistantMessage = llmIntent != null
                    && llmIntent.assistantMessage() != null
                    && !llmIntent.assistantMessage().isBlank()
                    ? llmIntent.assistantMessage()
                    : "Não consegui confirmar a sua intenção com segurança agora. Confirme se você quer consultar dados disponíveis, criar uma tabela, montar um formulário ou gerar uma visualização.";
        }
        if (isConsultativePlatformCapabilityQuestion(prompt)
                && shouldUsePlatformGuidanceMessage(prompt, assistantMessage)) {
            assistantMessage = platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("api_catalog".equals(artifactKind)
                && shouldUseApiCatalogGuidanceMessage(assistantMessage)) {
            String sanitizedApiCatalogMessage = sanitizeApiCatalogConsultativeLanguage(
                    assistantMessage,
                    selectedCandidate,
                    candidates);
            assistantMessage = shouldUseApiCatalogGuidanceMessage(sanitizedApiCatalogMessage)
                    ? apiCatalogAssistantMessage(prompt, selectedCandidate, candidates)
                    : sanitizedApiCatalogMessage;
        }
        if (quickReplySupport.shouldHideTechnicalAddresses(
                request, operationKind, artifactKind, llmRequiresGovernedAuthoring)) {
            assistantMessage = sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        }
        assistantMessage = AgenticAuthoringPresentationText.assistantReply(
                assistantMessage,
                request.contextHints() == null ? "" : jsonText(request.contextHints(), "responseLocale"));
        assistantMessage = quickReplySupport.conciseAssistantMessage(assistantMessage);
        boolean promotedAssistantChoiceToClarification = quickReplySupport.shouldPromoteAssistantChoiceToClarification(
                llmIntent,
                gate,
                assistantMessage);
        if (promotedAssistantChoiceToClarification) {
            gate = withGateMessage(gate, "assistant-choice-confirmation-required");
            if (questions.isEmpty() && assistantMessage != null && !assistantMessage.isBlank()) {
                questions = List.of(assistantMessage);
            }
        }
        JsonNode apiCatalogAnswer = apiCatalogAnswer(prompt, operationKind, artifactKind, changeKind, selectedCandidate, candidates);
        AgenticAuthoringPendingClarification pendingClarification =
                pendingClarification(
                        effectivePrompt,
                        gate,
                        assistantMessage,
                        questions,
                        request.clientTurnId(),
                        request.attachmentSummaries());
        List<AgenticAuthoringQuickReply> llmAuthoredQuickReplies = quickReplySupport.governedLlmQuickReplies(
                llmIntent,
                selectedCandidate,
                gate);
        List<AgenticAuthoringQuickReply> fallbackQuickReplies = quickReplySupport.quickReplies(
                effectivePrompt,
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                gate,
                questions,
                candidates,
                answeredBareDomainClarification,
                this::isConsultativePlatformCapabilityQuestion,
                this::isApiCatalogCandidateChoicePrompt);
        boolean llmAuthoredQuickRepliesUsed = quickReplySupport.shouldUseLlmAuthoredQuickReplies(
                llmAuthoredQuickReplies,
                gate,
                quickReplySupport.contextHintSelectionApplied(contextHintCandidate, selectedCandidate, gate),
                promotedAssistantChoiceToClarification,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                hasTrustedSelectionEvidence(selectedCandidate));
        List<AgenticAuthoringQuickReply> quickReplies = llmAuthoredQuickRepliesUsed
                ? llmAuthoredQuickReplies
                : fallbackQuickReplies;
        quickReplies = quickReplySupport.withSemanticResourceDiscoveryToolContext(
                quickReplies,
                gate,
                llmIntent,
                artifactKind);
        boolean platformGuidanceQuickRepliesMaterialized = false;
        boolean concreteDashboardGuidance = llmPlatformGuidance
                && semanticOrientation != null
                && "dashboard".equals(semanticOrientation.artifactKind());
        if (llmPlatformGuidance
                && (quickReplies.isEmpty() || concreteDashboardGuidance && !llmAuthoredQuickRepliesUsed)) {
            quickReplies = quickReplySupport.platformCapabilityQuickReplies(effectivePrompt, semanticOrientation);
            platformGuidanceQuickRepliesMaterialized = true;
        }
        quickReplies = quickReplySupport.withPlatformQuickReplySemanticDecisions(request, quickReplies);
        boolean contextHintSelectionApplied = contextHintCandidate != null
                && selectedCandidate != null
                && "eligible".equals(gate.status());
        if (llmIntent != null
                && llmIntent.quickReplies() != null
                && !llmIntent.quickReplies().isEmpty()
                && quickReplies.isEmpty()
                && !contextHintSelectionApplied
                && !"eligible".equals(gate.status())) {
            quickReplies = llmAuthoredQuickReplies.isEmpty() ? llmIntent.quickReplies() : llmAuthoredQuickReplies;
            llmAuthoredQuickRepliesUsed = !llmAuthoredQuickReplies.isEmpty();
        }
        quickReplies = quickReplySupport.canonicalizePresentationAffordanceQuickReplies(request, quickReplies);
        if (quickReplySupport.shouldHideTechnicalAddresses(
                request, operationKind, artifactKind, llmRequiresGovernedAuthoring)) {
            quickReplies = quickReplySupport.sanitizeQuickReplies(quickReplies, selectedCandidate, candidates);
        }
        boolean clarificationRequired = !questions.isEmpty()
                || gate != null && "clarification_required".equals(gate.status());
        boolean clarificationQuickRepliesRecovered = clarificationRequired && quickReplies.isEmpty();
        if (clarificationQuickRepliesRecovered) {
            quickReplies = quickReplySupport.ensureClarificationQuickReplies(
                    effectivePrompt, clarificationRequired, quickReplies);
        }
        // A failed primary interpretation has no semantic authority for an
        // executable continuation. Broad catalog candidates remain diagnostics;
        // selecting one must not silently invent create/table intent next turn.
        if (primaryLlmIntentProviderFailure
                || unresolvedPrimaryLlmIntent && !llmAuthoredQuickRepliesUsed) {
            quickReplies = List.of();
            llmAuthoredQuickRepliesUsed = false;
            clarificationQuickRepliesRecovered = false;
        }
        boolean governedDeterministicResolution = isGovernedDeterministicResolution(
                deterministicFallbackApplied,
                gate,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate);
        boolean keywordFallbackAppliedForGovernance =
                deterministicFallbackApplied
                        && !governedDeterministicResolution
                        && !primaryLlmIntentProviderFailure;
        List<String> warnings = warnings(llmIntent);
        if (unresolvedPrimaryLlmIntent && llmIntent == null) {
            warnings = withoutWarnings(warnings, "semantic-intent-resolution-not-attempted");
            warnings = withWarning(warnings, "llm-intent-resolution-unresolved-clarification-required");
        }
        if (llmTreatsPendingAsNewInstruction) {
            warnings = withWarning(warnings, "llm-follow-up-kind-new-instruction");
        }
        if (llmSecondPassUsed) {
            warnings = withWarning(warnings, "llm-intent-resolution-second-pass-used");
        }
        if (llmResourceSelectionOverriddenByPromptAlignment) {
            warnings = withWarning(warnings, "llm-resource-selection-overridden-by-prompt-alignment");
        }
        if (llmResourceSelectionOverriddenByGovernedRanking) {
            warnings = withWarning(warnings, "llm-resource-selection-overridden-by-governed-ranking");
        }
        if (resourceDiscoveryAuthoringDriftNormalized) {
            warnings = withWarning(warnings, "llm-resource-discovery-authoring-drift-normalized");
        }
        if (resourceDiscoveryFocusSelectionApplied) {
            warnings = withWarning(warnings, "llm-resource-discovery-focus-selection-applied");
        }
        if (platformGuidanceResourceCandidatesCleared) {
            warnings = withWarning(warnings, "platform-guidance-resource-candidates-cleared");
        }
        if (platformGuidanceQuickRepliesMaterialized) {
            warnings = withWarning(warnings, "platform-guidance-quick-replies-materialized");
        }
        if (clarificationQuickRepliesRecovered) {
            warnings = withWarning(warnings, "clarification-quick-replies-recovered");
        }
        if (conversationHistoryIsolatedForBlankCreate) {
            warnings = withWarning(warnings, "llm-conversation-history-isolated-for-blank-create");
        }
        if (llmSingleChartDecisionRejectedForOpenOperationalPrompt(prompt, llmIntent)) {
            warnings = withWarning(warnings, "llm-single-chart-decision-requires-explicit-analytical-intent");
        }
        if (keywordFallbackAppliedForGovernance) {
            if (llmIntent == null) {
                warnings = withWarning(warnings, "llm-intent-resolution-fallback-deterministic");
            } else if (!llmIntent.resolved() && !isLlmProviderFailure(llmIntent)) {
                warnings = withWarning(warnings, "llm-intent-resolution-unresolved-fallback-deterministic");
            }
            warnings = withWarning(warnings, "keyword-fallback-applied");
            warnings = withWarning(warnings, "keyword-fallback-fail-safe-applied");
        } else if (governedDeterministicResolution) {
            warnings = withWarning(warnings, "governed-deterministic-resolution-applied");
        }
        if (governedResourceConfirmation) {
            warnings = withWarning(warnings, "governed-resource-confirmation-deterministic");
        }
        if (serverIssuedQuickReplyContinuation) {
            warnings = withWarning(warnings, "server-issued-quick-reply-continuation-applied");
        }
        if (semanticPolicyRefinedVisualProjection) {
            warnings = withWarning(warnings, "semantic-policy-refined-visual-projection");
        }
        if (decisionMemoryRefinement && semanticRefinement.preservesResource()) {
            warnings = withWarning(warnings, "semantic-decision-memory-refinement-applied");
        } else if (decisionMemoryRefinement) {
            warnings = withWarning(warnings, "semantic-refinement-applied");
        }
        if (preLlmGovernedResourceChoiceApplied) {
            warnings = withWarning(warnings, hasLlmIntentResolver
                    ? "pre-llm-governed-resource-choice-ranked"
                    : "pre-llm-governed-resource-choice-applied");
        }
        if (shouldRequireReviewForLowerRankedLlmSelection(
                request,
                prompt,
                artifactKind,
                llmIntent,
                selectedCandidate,
                rankedCandidatesForSelectionReview)) {
            warnings = withWarning(warnings, "llm-resource-selection-lower-ranked-than-governed-candidate");
        }
        if (shouldRequireReviewForRoleMismatchedSelection(
                prompt,
                artifactKind,
                selectedCandidate,
                rankedCandidatesForSelectionReview)) {
            warnings = withWarning(warnings, "resource-selection-role-mismatch-with-governed-candidate");
        }
        if (shouldRequireReviewForUnanchoredLowConfidenceSelection(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                rankedCandidatesForSelectionReview)) {
            warnings = withWarning(warnings, "resource-selection-unanchored-low-confidence");
        }
        if (apiCatalogWeakLexicalSelectionDeferred) {
            warnings = withWarning(warnings, "api-catalog-weak-lexical-selection-deferred");
        }
        if (unresolvedLlmWeakLexicalSelectionDeferred) {
            warnings = withWarning(warnings, "llm-unresolved-weak-lexical-selection-deferred");
        }
        if (consultativeWeakLexicalSelectionDeferred) {
            warnings = withWarning(warnings, "consultative-weak-lexical-selection-deferred");
        }
        if (promotedAssistantChoiceToClarification) {
            warnings = withWarning(warnings, "llm-assistant-choice-promoted-to-quick-replies");
        }
        if (llmAuthoredQuickRepliesUsed) {
            warnings = withWarning(warnings, "llm-authored-quick-replies-used");
        } else if (!primaryLlmIntentProviderFailure && !unresolvedPrimaryLlmIntent && !fallbackQuickReplies.isEmpty()) {
            warnings = withWarning(warnings, "deterministic-quick-replies-fallback-applied");
        }
        if (explicitLocalUiComposition) {
            warnings = withWarning(warnings, "explicit-local-ui-composition-resource-selection-bypassed");
        }
        if (explicitLocalPageComposition) {
            warnings = withWarning(warnings, "explicit-local-page-composition-normalized");
        }
        warnings = withCandidateProvenanceWarnings(warnings, selectedCandidate, candidates);
        warnings = sanitizeConsultativeWarnings(warnings, operationKind, artifactKind, changeKind);
        List<AgenticAuthoringCandidate> presentationCandidates =
                presentationCandidates(selectedCandidate, candidates);
        // A server-issued continuation already owns its visual decision. Preserve
        // it so the turn engine can re-verify operations for the same layout;
        // the generic repair label must not become a new visualization intent.
        AgenticAuthoringVisualizationDecision visualizationDecision =
                serverIssuedQuickReplyContinuation && activeDecision.visualizationDecision() != null
                        ? activeDecision.visualizationDecision()
                        : governedVisualizationDecision(
                        llmIntent,
                        operationKind,
                        artifactKind,
                        changeKind,
                        semanticRawPrompt(request, rawPrompt),
                        prompt,
                        request.currentPage(),
                        currentPageSummary,
                        selectedCandidate);
        String semanticDecisionArtifactKind = "chart".equals(artifactKind)
                && containsAny(prompt, "painel")
                && !containsAny(prompt, "grafico", "gráfico", "chart")
                ? "dashboard"
                : artifactKind;
        AgenticAuthoringCandidate decisionSelectedCandidate =
                gate != null && "blocked".equals(gate.status()) ? null : selectedCandidate;
        AgenticAuthoringSemanticDecision semanticDecision = AgenticAuthoringSemanticDecision.from(
                operationKind,
                semanticDecisionArtifactKind,
                changeKind,
                decisionSelectedCandidate,
                presentationCandidates,
                visualizationDecision,
                warnings,
                null,
                llmIntent,
                activeDecision,
                request.sessionId(),
                request.clientTurnId(),
                semanticRawPrompt(request, rawPrompt),
                activeDecisionObjective(activeDecision, semanticRawPrompt(request, rawPrompt)),
                decisionRationale(decisionMemoryRefinement, decisionSelectedCandidate),
                semanticRefinement);
        if (serverIssuedQuickReplyContinuation) {
            semanticDecision = semanticDecision.withConstraints(
                    resolvedQuickReplyContinuationConstraints(activeDecision));
        }
        llmDiagnostics = withResolutionTelemetry(
                llmDiagnostics,
                shouldResolveLlmIntent,
                llmIntent,
                keywordFallbackAppliedForGovernance,
                semanticPolicyRefinedVisualProjection,
                selectedCandidate,
                presentationCandidates);
        return new AgenticAuthoringIntentResolutionResult(
                "eligible".equals(gate.status()),
                operationKind,
                artifactKind,
                changeKind,
                authoringProfile(operationKind, artifactKind, explicitLocalPageComposition),
                valueOrDefault(request.targetApp(), ""),
                valueOrDefault(request.targetComponentId(), DEFAULT_TARGET_COMPONENT),
                target,
                selectedCandidate,
                presentationCandidates,
                gate,
                effectivePrompt,
                assistantMessage,
                apiCatalogAnswer,
                quickReplies,
                pendingClarification,
                questions,
                warnings,
                gate.messages(),
                currentPageSummary,
                llmDiagnostics,
                visualizationDecision,
                semanticDecision
        );
    }

    private List<AgenticAuthoringCandidate> presentationCandidates(
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (selectedCandidate == null) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> presentationCandidates = new ArrayList<>();
        presentationCandidates.add(selectedCandidate);
        if (candidates != null) {
            candidates.stream()
                    .filter(Objects::nonNull)
                    .filter(candidate -> !sameCandidate(candidate, selectedCandidate))
                    .forEach(presentationCandidates::add);
        }
        return presentationCandidates;
    }

    private AgenticAuthoringKeywordFallbackResolution legacyKeywordFallback(
            String prompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target) {
        if (!legacyKeywordFallbackEnabled) {
            return new AgenticAuthoringKeywordFallbackResolution("unknown", "unknown", "unknown");
        }
        return keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
    }

    private AgenticAuthoringVisualizationDecision governedVisualizationDecision(
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            String changeKind,
            String rawPrompt,
            String prompt,
            JsonNode currentPage,
            JsonNode currentPageSummary,
            AgenticAuthoringCandidate selectedCandidate) {
        AgenticAuthoringVisualizationDecision decision = llmIntent == null ? null : llmIntent.visualizationDecision();
        if (!"create".equals(operationKind) && !"explore".equals(operationKind)) {
            return null;
        }
        if (!List.of("dashboard", "chart", "table", "page").contains(valueOrUnknown(artifactKind))) {
            return null;
        }
        if (decision != null) {
            String normalizedLayoutKind = normalize(valueOrDefault(decision.layoutKind(), ""))
                    .replaceAll("[^a-z0-9]+", " ")
                    .trim();
            boolean compoundAnalyticalPrompt = hasCompoundAnalyticalSurfaceSignals(prompt);
            boolean explicitSingleChartLayout = !compoundAnalyticalPrompt
                    && List.of("chart", "single chart", "single visualization")
                            .contains(normalizedLayoutKind);
            boolean hasExplicitExclusions = decision.excludedComponentIds() != null
                    && !decision.excludedComponentIds().isEmpty();
            if (("dashboard".equals(valueOrUnknown(artifactKind))
                    || ("chart".equals(valueOrUnknown(artifactKind)) && compoundAnalyticalPrompt))
                    && ("single-table".equalsIgnoreCase(valueOrDefault(decision.layoutKind(), ""))
                    || !"praxis-chart".equalsIgnoreCase(valueOrDefault(decision.primaryComponent(), ""))
                    || (!decision.includeSummary() && !decision.includeDetailTable() && !explicitSingleChartLayout)
                    || ((!decision.includeSummary() || !decision.includeDetailTable())
                            && !explicitSingleChartLayout
                            && !hasExplicitExclusions))) {
                decision = new AgenticAuthoringVisualizationDecision(
                        valueOrDefault(decision.schemaVersion(), "1.0"),
                        valueOrDefault(decision.intent(), "dashboard"),
                        "dashboard",
                        "praxis-chart",
                        decision.axes(),
                        true,
                        true,
                        decision.excludedComponentIds(),
                        true,
                        true,
                        valueOrDefault(decision.provenance(), "llm-authored-semantic-decision"),
                        valueOrDefault(decision.targetSurfaceId(), ""));
            }
            if (!shouldTrustVisualizationDecisionForArtifact(prompt, decision)) {
                return null;
            }
            return decision;
        }
        if ("dashboard".equals(valueOrUnknown(artifactKind))
                && "create_chart_drilldown".equals(valueOrUnknown(changeKind))
                && isCurrentPageDrilldownRefinementPrompt(prompt, currentPageSummary)
                && selectedCandidate != null) {
            return currentPageChartDrilldownDecision(prompt, currentPage);
        }
        return null;
    }

    private boolean hasCompoundAnalyticalSurfaceSignals(String prompt) {
        String normalized = normalize(valueOrDefault(prompt, ""));
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized,
                "sem tabela", "sem indicadores", "sem kpi", "sem detalhes",
                "nao crie tabela", "nao inclua tabela", "nao mostrar tabela",
                "apenas grafico", "apenas um grafico", "so grafico", "so um grafico",
                "somente grafico", "somente um grafico", "unico grafico",
                "chart only", "single chart", "only one chart", "just a chart")) {
            return false;
        }
        return containsAny(normalized,
                "indicador", "indicadores", "kpi", "kpis",
                "historico detalhado", "detalhado", "detalhes",
                "painel", "dashboard");
    }

    private AgenticAuthoringTarget resolveImplicitComponentTarget(
            JsonNode currentPage,
            AgenticAuthoringTarget target,
            String operationKind,
            String artifactKind,
            String changeKind) {
        if (target != null && !valueOrDefault(target.widgetKey(), "").isBlank()) {
            return target;
        }
        if (!"modify".equals(valueOrDefault(operationKind, ""))) {
            return target;
        }
        String componentId = implicitTargetComponentId(artifactKind, changeKind);
        AgenticAuthoringTarget implicitTarget = currentPageAnalyzer.resolveFirstComponentTarget(currentPage, componentId);
        return implicitTarget == null ? target : implicitTarget;
    }

    private boolean shouldPreserveCurrentComponentResource(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringTarget target,
            boolean dataSourceReplacementPrompt) {
        if (!"modify".equals(valueOrDefault(operationKind, "")) || dataSourceReplacementPrompt) {
            return false;
        }
        if (target == null || valueOrDefault(target.resourcePath(), "").isBlank()) {
            return false;
        }
        String componentId = valueOrDefault(target.componentId(), "");
        String expectedComponentId = implicitTargetComponentId(artifactKind, changeKind);
        return !expectedComponentId.isBlank() && expectedComponentId.equals(componentId);
    }

    private Optional<String> currentChartTypeModificationChangeKind(
            String prompt,
            JsonNode currentPage,
            boolean dataSourceReplacementPrompt) {
        if (dataSourceReplacementPrompt) {
            return Optional.empty();
        }
        Optional<String> changeKind = chartCapabilityCatalog.resolveChangeKind(prompt);
        if (changeKind.isEmpty() || !"set_chart_type".equals(changeKind.get())) {
            return Optional.empty();
        }
        if (currentPageAnalyzer.resolveFirstComponentTarget(currentPage, "praxis-chart") == null) {
            return Optional.empty();
        }
        return isCurrentComponentModificationPrompt(prompt) ? changeKind : Optional.empty();
    }

    private boolean shouldUseDeterministicCurrentComponentAssistantMessage(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return "modify".equals(valueOrDefault(operationKind, ""))
                && "chart".equals(valueOrDefault(artifactKind, ""))
                && "set_chart_type".equals(valueOrDefault(changeKind, ""))
                && hasEvidence(selectedCandidate, "current-page-target-resource");
    }

    private boolean isCurrentComponentModificationPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "selecionado",
                "selecionada",
                "atual",
                "atuais",
                "existente",
                "existentes",
                "criado",
                "criada",
                "mantendo",
                "mantenha",
                "mesma fonte",
                "mesmos dados",
                "current",
                "selected",
                "existing");
    }

    private String implicitTargetComponentId(String artifactKind, String changeKind) {
        String kind = valueOrDefault(changeKind, "");
        if (kind.startsWith("set_chart_") || kind.startsWith("enable_chart_")) {
            return "praxis-chart";
        }
        if (kind.startsWith("set_column_") || "configure_export".equals(kind)) {
            return "praxis-table";
        }
        String artifact = valueOrDefault(artifactKind, "");
        if ("chart".equals(artifact)) {
            return "praxis-chart";
        }
        if ("table".equals(artifact)) {
            return "praxis-table";
        }
        return "";
    }

    private AgenticAuthoringCandidate targetCandidate(AgenticAuthoringTarget target) {
        if (target == null || valueOrDefault(target.resourcePath(), "").isBlank()) {
            return null;
        }
        String operation = valueOrDefault(target.submitMethod(), "get").toLowerCase();
        return new AgenticAuthoringCandidate(
                target.resourcePath(),
                operation,
                valueOrDefault(target.schemaUrl(), ""),
                valueOrDefault(target.submitUrl(), target.resourcePath()),
                operation,
                0.97,
                "resource preserved from existing component target",
                List.of("current-page-target-resource"));
    }

    private AgenticAuthoringVisualizationDecision currentPageChartDrilldownDecision(
            String prompt,
            JsonNode currentPage) {
        AgenticAuthoringVisualizationAxisDecision axis = currentPageChartAxisDecision(currentPage);
        boolean includeFilters = containsAny(normalize(prompt), "filtro", "filtrar", "filtre", "conectado", "vinculado");
        return new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "current-chart-drilldown-with-detail-table",
                "dashboard",
                "praxis-chart",
                List.of(axis),
                false,
                true,
                List.of("praxis-rich-content", "praxis-kpi"),
                includeFilters,
                false,
                "current-page-governed-refinement");
    }

    private AgenticAuthoringVisualizationAxisDecision currentPageChartAxisDecision(JsonNode currentPage) {
        JsonNode chartConfig = firstCurrentPageChartConfig(currentPage);
        JsonNode semanticAxis = chartConfig.path("semanticAxis");
        String field = firstNonBlank(
                jsonText(semanticAxis, "field"),
                jsonText(chartConfig.path("axes").path("x"), "field"),
                jsonText(chartConfig.path("series").path(0), "categoryField"));
        String label = firstNonBlank(
                jsonText(semanticAxis, "label"),
                jsonText(chartConfig.path("axes").path("x"), "label"),
                titleFromToken(field));
        String concept = firstNonBlank(jsonText(semanticAxis, "concept"), field);
        String chartType = firstNonBlank(
                jsonText(chartConfig, "type"),
                jsonText(chartConfig.path("series").path(0), "type"),
                "bar");
        String orientation = firstNonBlank(jsonText(chartConfig, "orientation"), "vertical");
        JsonNode metric = chartConfig.path("series").path(0).path("metric");
        return new AgenticAuthoringVisualizationAxisDecision(
                concept,
                field,
                label,
                chartType,
                orientation,
                firstNonBlank(jsonText(metric, "aggregation"), "count"),
                firstNonBlank(jsonText(metric, "field"), ""),
                firstNonBlank(jsonText(metric, "label"), "Total"),
                "current-page-chart-axis");
    }

    private JsonNode firstCurrentPageChartConfig(JsonNode currentPage) {
        JsonNode widgets = currentPage == null ? null : currentPage.path("widgets");
        if (widgets == null || !widgets.isArray()) {
            return objectMapper.missingNode();
        }
        for (JsonNode widget : widgets) {
            JsonNode definition = widget.path("definition");
            String componentId = firstNonBlank(jsonText(definition, "id"), jsonText(widget, "componentId"), jsonText(widget, "id"));
            if ("praxis-chart".equals(componentId)) {
                JsonNode definitionConfig = definition.path("inputs").path("config");
                return definitionConfig.isMissingNode()
                        ? widget.path("inputs").path("config")
                        : definitionConfig;
            }
        }
        return objectMapper.missingNode();
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

    private String titleFromToken(String token) {
        String normalized = valueOrDefault(token, "campo").replaceAll("[_-]+", " ").trim();
        if (normalized.isBlank()) {
            return "Campo";
        }
        String[] words = normalized.split("\\s+");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            titled.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return titled.isEmpty() ? "Campo" : String.join(" ", titled);
    }

    private boolean isDecisionMemoryRefinementPrompt(
            String prompt,
            AgenticAuthoringSemanticDecision activeDecision) {
        if (activeDecision == null || activeDecision.selectedResource() == null) {
            return false;
        }
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean refinementLanguage = containsAny(normalized,
                "gostei", "prefiro", "melhor", "troca", "troque", "mude", "em vez",
                "ao inves", "mantem", "mantenha", "preserve", "refina", "refine");
        boolean visualLanguage = containsAny(normalized,
                "grafico", "graficos", "chart", "charts", "dashboard", "painel",
                "visual", "visualizacao", "indicador", "indicadores", "kpi", "kpis");
        return refinementLanguage && visualLanguage;
    }

    private boolean isServerIssuedQuickReplyContinuation(AgenticAuthoringSemanticDecision activeDecision) {
        return activeDecision != null
                && activeDecision.constraints() != null
                && "server-issued-quick-reply".equals(
                        activeDecision.constraints().path("source").asText(""))
                && !activeDecision.constraints().path("quickReplyId").asText("").isBlank();
    }

    private String governedContinuationDiscoveryPrompt(
            String discoveryPrompt,
            AgenticAuthoringSemanticDecision activeDecision) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (activeDecision != null && activeDecision.constraints() != null) {
            JsonNode conceptKeys = activeDecision.constraints().path("conceptKeys");
            if (conceptKeys.isArray()) {
                for (JsonNode conceptKey : conceptKeys) {
                    String value = conceptKey.asText("").trim();
                    if (!value.isBlank()) {
                        terms.add(value);
                    }
                }
            }
        }
        return normalize(String.join(" ", List.of(
                valueOrDefault(discoveryPrompt, ""),
                String.join(" ", terms))));
    }

    private List<AgenticAuthoringCandidate> discoverResolvedContinuationCandidates(
            AgenticAuthoringSemanticDecision activeDecision,
            String artifactKind,
            String tenantId,
            String environment) {
        if (apiMetadataCandidateCatalog == null
                || activeDecision == null
                || activeDecision.constraints() == null) {
            return List.of();
        }
        String resourcePath = activeDecision.constraints().path("resourcePath").asText("").trim();
        if (!resourcePath.isBlank()) {
            // Intent and resource scope were already selected by a server-issued
            // semantic decision. Exact matching only grounds that canonical
            // candidate again; it never routes the user's text or trusts a label.
            return apiMetadataCandidateCatalog.discover(resourcePath, artifactKind, tenantId, environment, null)
                    .stream().filter(Objects::nonNull)
                    .filter(candidate -> resourcePath.equals(candidate.resourcePath()))
                    .toList();
        }
        List<String> conceptKeys = new ArrayList<>();
        JsonNode concepts = activeDecision.constraints().path("conceptKeys");
        if (concepts.isArray()) {
            concepts.forEach(node -> {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    conceptKeys.add(value);
                }
            });
        }
        return apiMetadataCandidateCatalog.discoverResolvedConcepts(
                conceptKeys,
                artifactKind,
                tenantId,
                environment);
    }

    private ObjectNode resolvedQuickReplyContinuationConstraints(
            AgenticAuthoringSemanticDecision activeDecision) {
        JsonNode issuedConstraints = activeDecision.constraints();
        ObjectNode constraints = issuedConstraints.isObject()
                ? issuedConstraints.deepCopy()
                : objectMapper.createObjectNode();
        constraints.put("source", "resolved-quick-reply-continuation");
        constraints.put("continuationDecisionId", valueOrDefault(activeDecision.decisionId(), ""));
        constraints.put("quickReplyId", issuedConstraints.path("quickReplyId").asText(""));
        constraints.put("continuationOf", issuedConstraints.path("continuationOf").asText(""));
        constraints.set("conceptKeys", issuedConstraints.path("conceptKeys").deepCopy());
        return constraints;
    }

    private AgenticAuthoringSemanticRefinement semanticRefinement(
            String prompt,
            AgenticAuthoringSemanticDecision activeDecision,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if (activeDecision == null || activeDecision.selectedResource() == null) {
            return AgenticAuthoringSemanticRefinement.none();
        }
        boolean llmDataSelectionRefinement = isLlmDataSelectionRefinement(llmIntent);
        if (isResolvedComponentAuthoringIntent(llmIntent) && !llmDataSelectionRefinement) {
            return AgenticAuthoringSemanticRefinement.none();
        }
        if (llmDataSelectionRefinement) {
            List<String> filterFields = semanticConstraintFields(llmIntent.queryConstraints());
            Map<String, List<String>> add = filterFields.isEmpty()
                    ? Map.of()
                    : Map.of("filters", filterFields);
            return new AgenticAuthoringSemanticRefinement(
                    AgenticAuthoringSemanticRefinement.SCHEMA_VERSION,
                    "filtering",
                    List.of("resource", "source"),
                    Map.of(),
                    add,
                    List.of(),
                    "The AI-resolved follow-up changes the selected record subset while preserving the active governed resource.",
                    0.92d);
        }
        String normalized = normalize(prompt);
        String followUpKind = llmIntent == null ? "" : valueOrDefault(llmIntent.followUpKind(), "");
        boolean refinementLanguage = containsAny(normalized,
                "gostei", "prefiro", "melhor", "troca", "troque", "mude", "em vez",
                "ao inves", "mantem", "mantenha", "preserve", "refina", "refine", "so muda");
        boolean explicitPreserve = containsAny(normalized,
                "mantem os dados", "mantenha os dados", "mantem a fonte", "mantenha a fonte",
                "mantem o recurso", "mantenha o recurso", "mesma fonte", "mesmos dados", "preserve a fonte");
        boolean chartRequested = containsAny(normalized,
                "grafico", "graficos", "chart", "charts", "dashboard", "painel",
                "visualizacao", "indicador", "indicadores", "kpi", "kpis", "pizza");
        boolean tableRequested = containsAny(normalized,
                "tabela", "table", "grid", "lista", "listagem");
        boolean filterRequested = containsAny(normalized, "filtro", "filtrar", "status", "situacao");
        if (isDataSourceReplacementPrompt(normalized) && !explicitPreserve) {
            return new AgenticAuthoringSemanticRefinement(
                    AgenticAuthoringSemanticRefinement.SCHEMA_VERSION,
                    "data_source",
                    List.of(),
                    Map.of("source", normalized),
                    Map.of(),
                    List.of(),
                    "Data-source refinement must be re-grounded by normal resource selection.",
                    0.64d);
        }
        if (!refinementLanguage && !explicitPreserve) {
            return AgenticAuthoringSemanticRefinement.none();
        }
        if (!chartRequested && !tableRequested && !filterRequested && !explicitPreserve) {
            return AgenticAuthoringSemanticRefinement.none();
        }
        List<String> preserve = new ArrayList<>();
        preserve.add("resource");
        preserve.add("source");
        if (explicitPreserve || filterRequested) {
            preserve.add("filters");
        }
        Map<String, String> replace = new LinkedHashMap<>();
        List<String> remove = new ArrayList<>();
        if (tableRequested && !chartRequested) {
            replace.put("artifactKind", "table");
            replace.put("visualIntent", "table");
            remove.add("chart");
            remove.add("summary");
        } else if (chartRequested) {
            replace.put("artifactKind", "dashboard");
            replace.put("visualIntent", "charts");
            if (containsAny(normalized, "pizza", "pie")) {
                replace.put("chartType", "pie");
            }
            remove.add("table");
        }
        Map<String, List<String>> add = new LinkedHashMap<>();
        if (filterRequested) {
            add.put("filters", containsAny(normalized, "status", "situacao")
                    ? List.of("status")
                    : List.of("requested-filter"));
        }
        return new AgenticAuthoringSemanticRefinement(
                AgenticAuthoringSemanticRefinement.SCHEMA_VERSION,
                filterRequested && !chartRequested && !tableRequested ? "filtering" : "visual_projection",
                List.copyOf(preserve),
                Map.copyOf(replace),
                Map.copyOf(add),
                List.copyOf(remove),
                "Semantic refinement applies a governed diff to the active decision.",
                explicitPreserve ? 0.90d : 0.86d);
    }

    private boolean isLlmDataSelectionRefinement(AgenticAuthoringLlmIntentResolution llmIntent) {
        return llmIntent != null
                && llmIntent.resolved()
                && "refinement".equals(valueOrDefault(llmIntent.followUpKind(), ""))
                && llmIntent.queryConstraints() != null
                && llmIntent.queryConstraints().path("appliesToDataSelection").asBoolean(false);
    }

    private List<String> semanticConstraintFields(JsonNode queryConstraints) {
        if (queryConstraints == null || !queryConstraints.path("filters").isArray()) {
            return List.of();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (JsonNode filter : queryConstraints.path("filters")) {
            String field = filter.path("field").asText("").trim();
            if (!field.isBlank()) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    private boolean isResolvedComponentAuthoringIntent(AgenticAuthoringLlmIntentResolution llmIntent) {
        return llmIntent != null
                && llmIntent.resolved()
                && "component_authoring".equals(valueOrDefault(llmIntent.semanticIntentClass(), ""))
                && Set.of("modify", "undo").contains(
                        valueOrDefault(llmIntent.operationKind(), ""));
    }

    private boolean shouldPromoteCompactTarget(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringTarget llmGroundingTarget,
            AgenticAuthoringTarget explicitTarget) {
        boolean scopedCompactIntent = isResolvedComponentAuthoringIntent(llmIntent)
                || hasLlmWarning(llmIntent, "llm-compact-targeted-component-clarification-used");
        if (explicitTarget != null
                || llmGroundingTarget == null
                || !scopedCompactIntent
                || llmIntent.warnings() == null
                || !llmIntent.warnings().contains("llm-compact-targeted-component-intent-used")) {
            return false;
        }
        String selectedResourcePath = normalizePath(llmIntent.selectedResourcePath());
        String targetResourcePath = normalizePath(llmGroundingTarget.resourcePath());
        return !selectedResourcePath.isBlank()
                && selectedResourcePath.equals(targetResourcePath);
    }

    private AgenticAuthoringSemanticRefinement visualProjectionRefinement(String prompt) {
        String normalized = normalize(prompt);
        Map<String, String> replace = new LinkedHashMap<>();
        List<String> remove = new ArrayList<>();
        if (containsAny(normalized, "tabela", "table", "grid", "lista", "listagem")
                && !containsAny(normalized, "grafico", "graficos", "chart", "charts", "dashboard", "painel")) {
            replace.put("artifactKind", "table");
            replace.put("visualIntent", "table");
            remove.add("chart");
        } else {
            replace.put("artifactKind", "dashboard");
            replace.put("visualIntent", "charts");
            remove.add("table");
        }
        return new AgenticAuthoringSemanticRefinement(
                AgenticAuthoringSemanticRefinement.SCHEMA_VERSION,
                "visual_projection",
                List.of("resource", "source"),
                Map.copyOf(replace),
                Map.of(),
                List.copyOf(remove),
                "Visual projection refinement preserves the current page resource.",
                0.84d);
    }

    private String refinementReplacement(
            AgenticAuthoringSemanticRefinement semanticRefinement,
            String key,
            String fallback) {
        if (semanticRefinement == null || !semanticRefinement.active()) {
            return valueOrDefault(fallback, "");
        }
        String replacement = semanticRefinement.replacement(key);
        return replacement.isBlank() ? valueOrDefault(fallback, "") : replacement;
    }

    private AgenticAuthoringCandidate activeDecisionCandidate(
            AgenticAuthoringSemanticDecision activeDecision,
            String artifactKind) {
        if (activeDecision == null || activeDecision.selectedResource() == null) {
            return null;
        }
        AgenticAuthoringSemanticDecision.SelectedResource resource = activeDecision.selectedResource();
        String resourcePath = valueOrDefault(resource.resourcePath(), "");
        if (resourcePath.isBlank()) {
            return null;
        }
        String operation = valueOrDefault(resource.operation(), "get");
        String submitMethod = valueOrDefault(resource.submitMethod(), operation).toUpperCase(Locale.ROOT);
        return new AgenticAuthoringCandidate(
                normalizePath(resourcePath),
                operation,
                valueOrDefault(resource.schemaUrl(), ""),
                valueOrDefault(resource.submitUrl(), resourcePath),
                submitMethod,
                1.0d,
                "resource preserved from active semantic decision",
                List.of("semantic-decision-memory"),
                activeDecision.retrievedEvidence());
    }

    private String activeDecisionObjective(
            AgenticAuthoringSemanticDecision activeDecision,
            String fallbackGoal) {
        if (activeDecision == null) {
            return valueOrDefault(fallbackGoal, "");
        }
        return Stream.of(
                        activeDecision.activeObjective(),
                        activeDecision.userGoal(),
                        fallbackGoal)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String decisionRationale(
            boolean decisionMemoryRefinement,
            AgenticAuthoringCandidate selectedCandidate) {
        if (decisionMemoryRefinement && selectedCandidate != null) {
            return "Refinement preserves the active decision resource and changes the requested visual projection.";
        }
        if (selectedCandidate != null) {
            return "Selected resource grounds the semantic authoring decision before materialization.";
        }
        return "";
    }

    private boolean shouldApplyPreLlmGovernedResourceChoice(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate contextHintCandidate) {
        if (contextHintCandidate != null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (!"dashboard".equals(artifactKind) && !"page".equals(artifactKind)) {
            return false;
        }
        if (!"explore".equals(operationKind) && !"create".equals(operationKind)) {
            return false;
        }
        if (!explicitResourcePath(prompt).isBlank() || isConfirmedDataSourceSelection(prompt)) {
            return false;
        }
        if ("dashboard".equals(artifactKind)
                && isConcreteDashboardMaterializationPrompt(prompt)
                && (candidates.stream().anyMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))
                || promptAlignedBusinessCandidate(prompt, candidates) != null)) {
            return true;
        }
        return "dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && isBroadAnalyticalCanvasPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate));
    }

    private AgenticAuthoringCandidate preLlmGovernedResourceChoiceCandidate(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate contextHintCandidate) {
        return candidateScoringSupport.preLlmGovernedResourceChoiceCandidate(
                prompt, operationKind, artifactKind, candidates, contextHintCandidate);
    }

    private String projectionRole(SemanticResourceNeed resourceNeed) {
        return candidateScoringSupport.projectionRole(resourceNeed);
    }

    private int preLlmDashboardCandidateAlignmentScore(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.preLlmDashboardCandidateAlignmentScore(prompt, artifactKind, candidate);
    }

    private boolean promptMatchesCandidatePathIdentity(String prompt, AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, candidate);
    }

    private boolean isStatsProjectionOperation(AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.isStatsProjectionOperation(candidate);
    }

    private boolean isConcreteDashboardMaterializationPrompt(String prompt) {
        return candidateScoringSupport.isConcreteDashboardMaterializationPrompt(prompt);
    }

    private boolean hasMaterializableResourceDiscoveryContext(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.hasMaterializableResourceDiscoveryContext(request);
    }

    private boolean hasBlockingActiveSemanticDecision(AgenticAuthoringIntentResolutionRequest request) {
        return candidateScoringSupport.hasBlockingActiveSemanticDecision(request);
    }

    private boolean shouldUsePreIntentResourceDiscoveryAsPrimaryEvidence(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget target) {
        boolean detachTargetForReplacement = shouldDetachCurrentTarget(
                normalize(request == null ? "" : valueOrDefault(request.userPrompt(), "")),
                materializableResourceDiscoveryArtifactKind(request, "unknown"),
                target);
        if (!shouldResolveLlmIntent
                || request == null
                || request.pendingClarification() != null
                || hasBlockingActiveSemanticDecision(request)
                || !hasMaterializableResourceDiscoveryContext(request)
                || !hasLlmAuthoredMaterializableResourceFocus(request)
                || (target != null && !valueOrDefault(target.widgetKey(), "").isBlank() && !detachTargetForReplacement)) {
            return false;
        }
        List<AgenticAuthoringCandidate> candidates = contextHintCandidates(request);
        if (candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(this::hasTrustedSelectionEvidence)
                .filter(candidate -> !isWeakLexicalCandidate(candidate))
                .anyMatch(candidate -> hasResourceDiscoveryCandidate(request, candidate));
    }

    private boolean hasBusinessDataAuthoringSignal(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt) {
        return contextCandidateSupport.hasBusinessDataAuthoringSignal(request, prompt);
    }

    private boolean hasLlmAuthoredMaterializableResourceFocus(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.hasLlmAuthoredMaterializableResourceFocus(request);
    }

    private String materializableResourceDiscoveryArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String fallback) {
        return contextCandidateSupport.materializableResourceDiscoveryArtifactKind(request, fallback);
    }

    private boolean hasResourceDiscoveryCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.hasResourceDiscoveryCandidate(request, candidate);
    }

    private boolean isOpenBusinessSurfaceAuthoringPrompt(String prompt) {
        String normalized = normalize(prompt);
        boolean hasSurface = containsAny(normalized,
                "tela",
                "pagina",
                "page",
                "visao",
                "visualizacao",
                "view",
                "painel",
                "dashboard",
                "tabela",
                "lista",
                "formulario");
        boolean hasAuthoringIntent = containsAny(normalized,
                "quero",
                "queria",
                "preciso",
                "gostaria",
                "crie",
                "criar",
                "monte",
                "montar",
                "gere",
                "gerar");
        return hasSurface && hasAuthoringIntent;
    }

    private boolean isBusinessDataAuthoringPrompt(String prompt) {
        return contextCandidateSupport.isBusinessDataAuthoringPrompt(prompt);
    }

    private boolean isExplicitDashboardMaterializationCommand(String prompt) {
        String normalized = normalize(prompt);
        if (!isConcreteDashboardMaterializationPrompt(normalized)) {
            return false;
        }
        if (isQuestionLike(normalized)
                && containsAny(normalized, "posso", "podem", "quais", "que dados", "quais dados", "devo")) {
            return false;
        }
        if (isQuestionLike(normalized) && !containsAny(normalized, "crie", "criar", "monte", "montar", "gere", "gerar")) {
            return false;
        }
        return containsAny(normalized,
                "quero",
                "preciso",
                "gostaria",
                "crie",
                "criar",
                "monte",
                "montar",
                "gere",
                "gerar",
                "construa",
                "construir",
                "painel para",
                "dashboard para");
    }

    private boolean shouldProvidePreLlmApiMetadataDiscoveryContext(String prompt) {
        String normalized = normalize(prompt);
        return isApiCatalogResourceListPrompt(normalized)
                && containsAny(normalized,
                "antes de criar",
                "para criar",
                "criar tabela",
                "criar tabelas",
                "criar formulario",
                "criar formularios",
                "criar formulário",
                "criar formulários",
                "criar dashboard",
                "criar painel",
                "criar grafico",
                "criar graficos",
                "criar gráfico",
                "criar gráficos",
                "criar indicadores");
    }

    private boolean isQuestionLike(String prompt) {
        String normalized = normalize(prompt);
        return normalized.contains("?")
                || containsAny(normalized,
                "qual",
                "quais",
                "o que",
                "que dados",
                "quais dados",
                "posso",
                "pode",
                "podem",
                "devo",
                "como");
    }

    private AgenticAuthoringLlmIntentResolution resolveLlmIntent(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (!shouldResolveLlmIntent || llmIntentResolverService == null) {
            return null;
        }
        Optional<AgenticAuthoringLlmIntentResolution> resolution = llmIntentResolverService.resolve(
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidates,
                        componentCapabilities,
                        tenantId,
                        userId,
                        environment);
        return resolution == null ? null : resolution.orElse(null);
    }

    private AgenticAuthoringLlmIntentResolution preIntentGovernedEvidenceResolution(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            List<String> trace) {
        if (!shouldResolveLlmIntent) {
            trace.add("skipped:llm-resolution-disabled");
            return null;
        }
        if (requiresAdditionalIntentResolution(semanticOrientation)) {
            trace.add("skipped:additional-intent-resolution-required");
            return null;
        }
        if (request == null) {
            trace.add("skipped:request-missing");
            return null;
        }
        if (request.pendingClarification() != null) {
            trace.add("skipped:pending-clarification");
            return null;
        }
        if (hasBlockingActiveSemanticDecision(request)) {
            trace.add("skipped:active-semantic-decision");
            return null;
        }
        if (!hasMaterializableResourceDiscoveryContext(request)) {
            trace.add("skipped:materializable-resource-context-missing");
            return null;
        }
        boolean detachTargetForReplacement = shouldDetachCurrentTarget(
                normalize(request == null ? "" : valueOrDefault(request.userPrompt(), "")),
                materializableResourceDiscoveryArtifactKind(request, "unknown"),
                target);
        if (target != null && !valueOrDefault(target.widgetKey(), "").isBlank() && !detachTargetForReplacement) {
            trace.add("skipped:selected-widget-target");
            return null;
        }
        String prompt = normalize(effectivePrompt);
        String artifactKind = materializableResourceDiscoveryArtifactKind(request, "unknown");
        boolean genericDashboardOrientedByPreIntent = "dashboard".equals(artifactKind)
                && semanticOrientation != null
                && !semanticOrientation.requiresFullIntentResolution();
        if (!(List.of("page", "table", "form").contains(artifactKind)
                        || genericDashboardOrientedByPreIntent)
                ) {
            trace.add("skipped:artifact-not-eligible:" + artifactKind);
            return null;
        }
        if (!hasBusinessDataAuthoringSignal(request, prompt)) {
            trace.add("skipped:business-data-authoring-signal-missing");
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            trace.add("skipped:candidates-empty");
            return null;
        }
        AgenticAuthoringCandidate focusedCandidate =
                resourceDiscoveryFocusedCandidate(request, prompt, artifactKind, candidates);
        AgenticAuthoringCandidate singleGovernedArtifactCandidate =
                singleGovernedArtifactCandidate(request, artifactKind, candidates, semanticOrientation);
        if (singleGovernedArtifactCandidate != null) {
            focusedCandidate = singleGovernedArtifactCandidate;
        }
        AgenticAuthoringCandidate projectionFocusedCandidate =
                projectionResourceDiscoveryFocusedCandidate(request, prompt, artifactKind, null, candidates);
        if (projectionFocusedCandidate != null) {
            focusedCandidate = projectionFocusedCandidate;
        } else if (focusedCandidate == null) {
            focusedCandidate = semanticRetrievalOperationalLeadCandidate(
                    request,
                    prompt,
                    artifactKind,
                    candidates);
        }
        if (focusedCandidate == null
                || !hasEvidence(focusedCandidate, "tool-search-api-resources")
                || !hasTrustedSelectionEvidence(focusedCandidate)
                || isWeakLexicalCandidate(focusedCandidate)
                || (singleGovernedArtifactCandidate == null
                        && !hasSafeFocusedResourceDiscoveryLead(
                                request,
                                prompt,
                                artifactKind,
                                focusedCandidate,
                                candidates))) {
            focusedCandidate = strongLlmAuthoredOperationalResourceDiscoveryLead(
                    request,
                    prompt,
                    artifactKind,
                    candidates);
            if (focusedCandidate == null) {
                trace.add("skipped:governed-focused-candidate-not-safe");
                return null;
            }
        }
        if (hasUnconfirmedAiAuthoredResourceFocus(request, focusedCandidate)) {
            trace.add("skipped:ai-authored-resource-focus-unconfirmed");
            return null;
        }
        if ("table".equals(artifactKind)
                && (singleGovernedArtifactCandidate == null
                        || !hasVerifiedOperationalBindingEvidence(singleGovernedArtifactCandidate))) {
            trace.add("skipped:single-table-operational-grounding-incomplete");
            return null;
        }
        trace.add("accepted:pre-intent-governed-evidence");
        return new AgenticAuthoringLlmIntentResolution(
                true,
                "create",
                artifactKind,
                "create_artifact",
                focusedCandidate.resourcePath(),
                null,
                "none",
                preIntentGovernedEvidenceAssistantMessage(artifactKind, focusedCandidate),
                List.of(),
                List.of(),
                List.of(
                        "llm-intent-resolution-satisfied-by-pre-intent-governed-evidence",
                        "llm-pre-intent-resource-discovery-used"),
                null,
                preIntentVisualizationDecision(semanticOrientation),
                false,
                "component_authoring",
                semanticOrientation == null ? null : semanticOrientation.queryConstraints(),
                List.of());
    }

    private AgenticAuthoringVisualizationDecision preIntentVisualizationDecision(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.preIntentVisualizationDecision(semanticOrientation);
    }

    private boolean requiresAdditionalIntentResolution(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.requiresAdditionalIntentResolution(semanticOrientation);
    }

    private boolean requiresGovernedRelatedResourceTargetSelection(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.requiresGovernedRelatedResourceTargetSelection(semanticOrientation);
    }

    private boolean hasPreIntentDataSelectionFilters(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        if (semanticOrientation == null || semanticOrientation.queryConstraints() == null) {
            return false;
        }
        JsonNode constraints = semanticOrientation.queryConstraints();
        return constraints.path("appliesToDataSelection").asBoolean(false)
                && constraints.path("filters").isArray()
                && !constraints.path("filters").isEmpty();
    }

    private boolean hasCompleteCompactResourceComposition(
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.hasCompleteCompactResourceComposition(semanticOrientation);
    }

    private AgenticAuthoringLlmIntentResolution preIntentSemanticOrientationResolution(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.preIntentSemanticOrientationResolution(
                shouldResolveLlmIntent,
                request,
                semanticOrientation);
    }

    private AgenticAuthoringLlmIntentResolution normalizeConstrainedAuthoringResolution(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            List<AgenticAuthoringCandidate> candidates) {
        return semanticOrientationReconciler.normalizeConstrainedAuthoringResolution(
                resolution,
                semanticOrientation,
                candidates);
    }

    private boolean preservesPreIntentQueryConstraints(JsonNode planned, JsonNode resolved) {
        return semanticOrientationReconciler.preservesPreIntentQueryConstraints(planned, resolved);
    }

    private AgenticAuthoringVisualizationDecision reconcileConstrainedPrimaryComponent(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.reconcileConstrainedPrimaryComponent(resolution, semanticOrientation);
    }

    private boolean isCompatiblePrimaryComponent(String artifactKind, String primaryComponent) {
        return semanticOrientationReconciler.isCompatiblePrimaryComponent(artifactKind, primaryComponent);
    }

    private AgenticAuthoringLlmIntentResolution failClosedForUnconfirmedAiAuthoredResourceSelection(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringLlmIntentResolution resolution) {
        if (resolution == null
                || !resolution.resolved()
                || valueOrDefault(resolution.selectedResourcePath(), "").isBlank()) {
            return resolution;
        }
        String selectedPath = normalizePath(resolution.selectedResourcePath());
        AgenticAuthoringCandidate selectedCandidate = (candidates == null
                        ? List.<AgenticAuthoringCandidate>of()
                        : candidates)
                .stream()
                .filter(Objects::nonNull)
                .filter(candidate -> selectedPath.equals(normalizePath(candidate.resourcePath())))
                .reduce(this::preferredCandidateForSameResource)
                .orElse(null);
        if (!hasUnconfirmedAiAuthoredResourceFocus(request, selectedCandidate)) {
            return resolution;
        }
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        List<String> warnings = new ArrayList<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        if (!warnings.contains("llm-resource-selection-unconfirmed-by-ai-authored-focus")) {
            warnings.add("llm-resource-selection-unconfirmed-by-ai-authored-focus");
        }
        List<String> questions = resolution.clarificationQuestions() == null
                        || resolution.clarificationQuestions().isEmpty()
                ? List.of(focus.uncertainty())
                : resolution.clarificationQuestions();
        return new AgenticAuthoringLlmIntentResolution(
                false,
                "unknown",
                "unknown",
                "needs_clarification",
                null,
                resolution.resourceSearchQuery(),
                "none",
                resolution.assistantMessage(),
                resolution.quickReplies(),
                questions,
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                null,
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private boolean hasUnconfirmedAiAuthoredResourceFocus(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus.isEmpty() || !requiresConfirmedResourceFocus(focus)) {
            return false;
        }
        if (candidate == null) {
            return true;
        }
        if (isCanonicalResourceIdentity(focus.primaryBusinessEntity())) {
            if (!canonicalResourceIdentityMatchesCandidate(
                    focus.primaryBusinessEntity(), candidate)) {
                return true;
            }
            // The exact identity-to-path reconciliation is itself governed post-semantic
            // grounding. A residual model uncertainty does not invalidate an exact canonical
            // identity that the Domain Catalog grounded to the same focused candidate. This
            // confirms identity only; schema/capability/preview gates remain independently
            // responsible for operational eligibility.
            if (focus.uncertainty().isBlank()
                    || (hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                            || (hasEvidence(candidate, "api-metadata")
                                    && hasEvidence(candidate, "semantic-retrieval")
                                    && hasEvidence(candidate, "schema-available")
                                    && !hasEvidence(candidate, "weak-evidence")
                                    && !hasEvidence(candidate, "lexical-fallback")))
                    && hasEvidence(candidate, "llm-resource-focus")) {
                return false;
            }
        }
        return !hasEvidence(candidate, "domain-anchor")
                && !hasEvidence(candidate, "domain-binding-operationally-verified")
                && !hasEvidence(candidate, "domain-binding")
                && !hasEvidence(candidate, "explicit-source-match")
                && !hasEvidence(candidate, "explicit-resource-path")
                && !hasEvidence(candidate, "quick-reply-context")
                && !hasEvidence(candidate, "current-page-target-resource")
                && !((hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                                || (hasEvidence(candidate, "api-metadata")
                                        && hasEvidence(candidate, "semantic-retrieval")
                                        && hasEvidence(candidate, "schema-available")
                                        && !hasEvidence(candidate, "weak-evidence")
                                        && !hasEvidence(candidate, "lexical-fallback")))
                        && hasEvidence(candidate, "llm-resource-focus"));
    }

    private boolean hasUnconfirmedAiAuthoredResourceFocus(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> candidates) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus.isEmpty() || !requiresConfirmedResourceFocus(focus)) {
            return false;
        }
        List<AgenticAuthoringCandidate> availableCandidates = candidates == null
                ? List.of()
                : candidates.stream().filter(Objects::nonNull).toList();
        return availableCandidates.isEmpty()
                || availableCandidates.stream()
                        .allMatch(candidate -> hasUnconfirmedAiAuthoredResourceFocus(request, candidate));
    }

    private boolean requiresConfirmedResourceFocus(AgenticAuthoringResourceSearchFocus focus) {
        return focus != null
                && (!focus.uncertainty().isBlank()
                        || isCanonicalResourceIdentity(focus.primaryBusinessEntity()));
    }

    private boolean isCanonicalResourceIdentity(String primaryBusinessEntity) {
        if (primaryBusinessEntity == null) {
            return false;
        }
        String value = primaryBusinessEntity.trim();
        int separator = value.indexOf('.');
        if (separator <= 0 || separator >= value.length() - 1 || value.indexOf(' ') >= 0) {
            return false;
        }
        // This is post-semantic grounding of an AI-authored canonical identity, not routing from
        // user wording. The exact identity must be backed by a governed binding before execution.
        return value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                || character == '.'
                || character == '-'
                || character == '_');
    }

    private boolean canonicalResourceIdentityMatchesCandidate(
            String primaryBusinessEntity,
            AgenticAuthoringCandidate candidate) {
        if (!isCanonicalResourceIdentity(primaryBusinessEntity) || candidate == null) {
            return false;
        }
        String expectedPath = normalizePath(
                "/api/" + primaryBusinessEntity.trim().replace('.', '/'));
        String candidatePath = normalizePath(candidate.resourcePath());
        if (expectedPath.equals(candidatePath)) {
            return true;
        }
        String operation = valueOrDefault(candidate.operation(), candidate.submitMethod());
        return candidatePath.startsWith(expectedPath + "/")
                && isReadProjectionOperation(candidate.submitUrl(), operation);
    }

    private AgenticAuthoringLlmIntentResolution withLlmWarning(
            AgenticAuthoringLlmIntentResolution resolution,
            String warning) {
        if (resolution == null || warning == null || warning.isBlank() || hasLlmWarning(resolution, warning)) {
            return resolution;
        }
        List<String> warnings = new ArrayList<>(resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add(warning);
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                resolution.visualizationDecision(),
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private AgenticAuthoringCandidate verifiedCollectionForResolvedIntent(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution resolution,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        if (resolution == null || !resolution.resolved()
                || !"create".equals(resolution.operationKind())
                || !List.of("page", "table").contains(artifactKind)
                || !artifactKind.equals(resolution.artifactKind())
                || candidates == null || candidates.isEmpty()) {
            return null;
        }
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus == null || !isCanonicalResourceIdentity(focus.primaryBusinessEntity())
                || valueOrDefault(resolution.selectedResourcePath(), "").isBlank()) {
            return null;
        }
        // This reconciles operations only after the AI chose the resource and artifact.
        // Documentary candidates stay separate; none inherits a binding's confirmation.
        List<AgenticAuthoringCandidate> eligible = candidates.stream()
                .filter(candidate -> resolution.selectedResourcePath().equals(candidate.resourcePath()))
                .filter(candidate -> canonicalResourceIdentityMatchesCandidate(focus.primaryBusinessEntity(), candidate))
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate))
                .filter(this::hasVerifiedOperationalBindingEvidence)
                .filter(candidate -> verifiedOperationForCandidate(request, candidate) != null)
                .filter(candidate -> candidateHasConsistentSource(candidate,
                        AgenticAuthoringCandidateProvenancePolicy.DOMAIN_BINDING))
                .filter(candidate -> {
                    List<String> scope = candidateEvidenceScope(candidate);
                    return scope != null && scope.get(0).equals(tenantId) && scope.get(1).equals(environment);
                })
                .filter(candidate -> isVerifiedCollectionOperation(candidate, verifiedOperationForCandidate(request, candidate)))
                .toList();
        if (eligible.isEmpty() || eligible.stream().map(this::candidateEvidenceScope).distinct().count() != 1) {
            return null;
        }
        // A single canonical collection projection is unambiguous. Multiple verified
        // collection operations require semantic disambiguation, not a first-match choice.
        return eligible.size() == 1 ? eligible.get(0) : null;
    }

    private boolean isVerifiedCollectionOperation(AgenticAuthoringCandidate candidate, JsonNode operation) {
        if (isCanonicalArtifactEndpointCandidate("table", candidate)) {
            return true;
        }
        return "get".equalsIgnoreCase(candidate.operation()) && operation != null
                && Set.of("all", "list").contains(jsonText(operation, "operationId"));
    }

    private JsonNode verifiedOperationForCandidate(
            AgenticAuthoringIntentResolutionRequest request, AgenticAuthoringCandidate candidate) {
        return candidateDiscoveryTelemetrySupport.verifiedOperationForCandidate(request, candidate);
    }

    private boolean candidateHasConsistentSource(AgenticAuthoringCandidate candidate, String source) {
        return candidateDiscoveryTelemetrySupport.candidateHasConsistentSource(candidate, source);
    }

    private List<String> candidateEvidenceScope(AgenticAuthoringCandidate candidate) {
        return candidateDiscoveryTelemetrySupport.candidateEvidenceScope(candidate);
    }

    private String constrainedOrientationArtifactKind(
            AgenticAuthoringPreIntentToolPlan semanticOrientation,
            AgenticAuthoringLlmIntentResolution resolution) {
        return semanticOrientationReconciler.constrainedOrientationArtifactKind(semanticOrientation, resolution);
    }

    private boolean hasProgressiveGovernedDomainKnowledge(AgenticAuthoringIntentResolutionRequest request) {
        return semanticOrientationReconciler.hasProgressiveGovernedDomainKnowledge(request);
    }

    private AgenticAuthoringCandidate singleGovernedArtifactCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return contextCandidateSupport.singleGovernedArtifactCandidate(
                request, artifactKind, candidates, semanticOrientation);
    }

    private AgenticAuthoringLlmIntentResolution reconcilePreIntentWorkbenchOrientation(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        return semanticOrientationReconciler.reconcilePreIntentWorkbenchOrientation(resolution, semanticOrientation);
    }

    private boolean isCanonicalCompactResourceCandidate(AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.isCanonicalCompactResourceCandidate(candidate);
    }

    private boolean hasVerifiedOperationalBindingEvidence(AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.hasVerifiedOperationalBindingEvidence(candidate);
    }

    private boolean isCanonicalArtifactEndpointCandidate(
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.isCanonicalArtifactEndpointCandidate(artifactKind, candidate);
    }

    private AgenticAuthoringCandidate strongLlmAuthoredOperationalResourceDiscoveryLead(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.strongLlmAuthoredOperationalResourceDiscoveryLead(
                request, prompt, artifactKind, candidates);
    }

    private boolean hasSafeFocusedResourceDiscoveryLead(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate focusedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.hasSafeFocusedResourceDiscoveryLead(
                request, prompt, artifactKind, focusedCandidate, candidates);
    }

    private AgenticAuthoringCandidate semanticRetrievalOperationalLeadCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.semanticRetrievalOperationalLeadCandidate(
                request, prompt, artifactKind, candidates);
    }

    private List<AgenticAuthoringCandidate> distinctGovernedOperationalCandidates(
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.distinctGovernedOperationalCandidates(candidates);
    }

    private String preIntentGovernedEvidenceAssistantMessage(
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.preIntentGovernedEvidenceAssistantMessage(artifactKind, candidate);
    }

    private boolean shouldResolveLlmIntentAfterCandidateRefinement(
            String prompt,
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            String changeKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (llmIntent == null || !llmIntent.resolved()) {
            return true;
        }
        if (isApiCatalogQuestion(operationKind, artifactKind, changeKind)) {
            return false;
        }
        if (hasGovernedCurrentTargetForFastModification(
                llmIntent,
                operationKind,
                artifactKind,
                changeKind,
                candidates)) {
            return false;
        }
        if (hasGovernedToolCandidateForMaterializableAuthoring(
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                candidates)) {
            return false;
        }
        if (!hasLlmWarning(llmIntent, "llm-fast-intent-resolution-used")) {
            return true;
        }
        if (!"explore".equals(operationKind) && !"explain".equals(operationKind)) {
            return true;
        }
        if ("api_catalog".equals(artifactKind)) {
            return !"answer_api_catalog_question".equals(changeKind);
        }
        if ("component".equals(artifactKind)) {
            return !"answer_component_catalog_question".equals(changeKind)
                    && !"answer_component_capability_question".equals(changeKind);
        }
        return true;
    }

    private boolean hasGovernedCurrentTargetForFastModification(
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            String changeKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (!hasLlmWarning(llmIntent, "llm-fast-intent-resolution-used")
                || !"modify".equals(operationKind)
                || !isMaterializableAuthoringIntent(operationKind, artifactKind, changeKind)
                || llmIntent.selectedResourcePath() == null
                || llmIntent.selectedResourcePath().isBlank()
                || candidates == null
                || candidates.isEmpty()) {
            return false;
        }
        String selectedPath = normalizePath(llmIntent.selectedResourcePath());
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> selectedPath.equals(normalizePath(candidate.resourcePath())))
                .filter(candidate -> hasEvidence(candidate, "current-page-target-resource")
                        || hasEvidence(candidate, "current-page"))
                .anyMatch(candidate -> !isDerivedProjectionCandidate(candidate));
    }

    private boolean hasGovernedToolCandidateForMaterializableAuthoring(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (!isMaterializableAuthoringIntent(operationKind, artifactKind, changeKind)
                || candidates == null
                || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasTrustedSelectionEvidence(candidate))
                .filter(candidate -> !isWeakLexicalCandidate(candidate))
                .anyMatch(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                        || !isDerivedProjectionCandidate(candidate)
                        || isProjectionCandidateForPromptSemanticNeed(prompt, artifactKind, candidate));
    }

    private boolean isMaterializableAuthoringIntent(
            String operationKind,
            String artifactKind,
            String changeKind) {
        if (!("create".equals(operationKind) || "modify".equals(operationKind))) {
            return false;
        }
        if (!List.of("page", "table", "form", "dashboard", "chart", "component").contains(artifactKind)) {
            return false;
        }
        String normalizedChangeKind = valueOrDefault(changeKind, "");
        return !normalizedChangeKind.startsWith("answer_")
                && !"presentation_affordance_choice".equals(normalizedChangeKind);
    }

    private boolean shouldSkipPostIntentApiCatalogCandidateDiscovery(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if ("explain".equals(operationKind)
                && "domain_decision".equals(artifactKind)
                && "explain_domain_decision".equals(changeKind)
                && llmIntent != null
                && llmIntent.resolved()) {
            return true;
        }
        return isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                && llmIntent != null
                && llmIntent.resolved()
                && hasLlmWarning(llmIntent, "llm-fast-intent-resolution-used")
                && explicitResourcePath(prompt).isBlank()
                && !isConfirmedDataSourceSelection(prompt);
    }

    private boolean hasSelectedDomainDecisionFocus(AgenticAuthoringIntentResolutionRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("selectedDomainDecisionRef").isObject();
    }

    private AgenticAuthoringLlmIntentResolution resolveLlmIntentAfterCandidateRefinement(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            AgenticAuthoringLlmIntentResolution previousLlmIntent,
            String tenantId,
            String userId,
            String environment) {
        if (!shouldResolveLlmIntent
                || llmIntentResolverService == null
                || previousLlmIntent == null
                || !previousLlmIntent.resolved()
                || consultativeResourceSearchQuery(previousLlmIntent).isBlank()
                || candidates == null
                || candidates.isEmpty()) {
            return previousLlmIntent;
        }
        Optional<AgenticAuthoringLlmIntentResolution> next = llmIntentResolverService.resolve(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities,
                tenantId,
                userId,
                environment);
        return next == null ? previousLlmIntent : next.orElse(previousLlmIntent);
    }

    private String consultativeResourceSearchQuery(AgenticAuthoringLlmIntentResolution llmIntent) {
        if (llmIntent == null) {
            return "";
        }
        String explicitQuery = valueOrDefault(llmIntent.resourceSearchQuery(), "").trim();
        if (!explicitQuery.isBlank()) {
            return explicitQuery;
        }
        AgenticAuthoringConsultativeRetrievalPlan plan = llmIntent.consultativeRetrievalPlan();
        if (plan == null || plan.semanticQueries() == null || plan.semanticQueries().isEmpty()) {
            return "";
        }
        return plan.semanticQueries().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(query -> !query.isBlank())
                .limit(4)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities() {
        return componentCapabilitiesService == null
                ? new AgenticAuthoringComponentCapabilitiesService().listCapabilities()
                : componentCapabilitiesService.listCapabilities();
    }

    private JsonNode llmDiagnostics(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if (!includeLlmDiagnostics(request)) {
            return null;
        }
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-intent-llm-diagnostics.v1");
        diagnostics.put("enabled", llmIntentResolverService != null);
        if (llmIntentResolverService == null) {
            diagnostics.put("reason", "llm-intent-resolver-unavailable");
            return diagnostics;
        }
        diagnostics.set("request", llmIntentResolverService.diagnosticProjection(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities));
        diagnostics.set(
                "providerInvocations",
                objectMapper.valueToTree(llmIntent == null || llmIntent.providerInvocations() == null
                        ? List.of()
                        : llmIntent.providerInvocations()));
        return diagnostics;
    }

    private boolean includeLlmDiagnostics(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        return contextHints != null && contextHints.path("includeLlmDiagnostics").asBoolean(false);
    }

    private String effectiveSelectedWidgetKey(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.effectiveSelectedWidgetKey(request);
    }

    private boolean isContextualPreviewAction(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.isContextualPreviewAction(request);
    }

    private AgenticAuthoringTarget contextualPreviewTarget(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget currentTarget,
            AgenticAuthoringCandidate contextHintCandidate) {
        return contextCandidateSupport.contextualPreviewTarget(request, currentTarget, contextHintCandidate);
    }

    private String contextualPreviewOperationKind(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.contextualPreviewOperationKind(request);
    }

    private String contextualPreviewArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String fallbackArtifactKind) {
        return contextCandidateSupport.contextualPreviewArtifactKind(request, fallbackArtifactKind);
    }

    private boolean isGovernedResourceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringConversationTurn turn) {
        return contextCandidateSupport.isGovernedResourceConfirmation(request, turn);
    }

    private AgenticAuthoringCandidate contextHintCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        return contextCandidateSupport.contextHintCandidate(
                request, artifactKind, candidates, tenantId, environment);
    }

    private AgenticAuthoringCandidate withEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return contextCandidateSupport.withEvidence(candidate, evidence);
    }

    private List<AgenticAuthoringCandidate> withContextHintCandidates(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.withContextHintCandidates(request, candidates);
    }

    private List<AgenticAuthoringCandidate> contextHintCandidates(AgenticAuthoringIntentResolutionRequest request) {
        return contextCandidateSupport.contextHintCandidates(request);
    }

    private List<AgenticAuthoringCandidate> withPriorityCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate priorityCandidate) {
        return contextCandidateSupport.withPriorityCandidate(candidates, priorityCandidate);
    }

    private AgenticAuthoringCandidate selectContextHintCandidate(
            AgenticAuthoringCandidate contextHintCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
        return contextCandidateSupport.selectContextHintCandidate(contextHintCandidate, candidates, fallback);
    }

    private AgenticAuthoringCandidate selectLlmCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String artifactKind,
            String prompt) {
        return contextCandidateSupport.selectLlmCandidate(
                request, llmIntent, candidates, fallback, artifactKind, prompt);
    }

    private boolean llmResourceSelectionOverriddenByPromptAlignment(
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate selectedCandidate,
            String artifactKind,
            String prompt) {
        return contextCandidateSupport.llmResourceSelectionOverriddenByPromptAlignment(
                llmIntent, candidates, selectedCandidate, artifactKind, prompt);
    }

    private boolean llmResourceSelectionOverriddenByGovernedRanking(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate selectedCandidate,
            String artifactKind,
            String prompt) {
        return contextCandidateSupport.llmResourceSelectionOverriddenByGovernedRanking(
                request, llmIntent, candidates, selectedCandidate, artifactKind, prompt);
    }

    private AgenticAuthoringCandidate strongerGovernedOperationalCandidateForLlmSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate llmCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.strongerGovernedOperationalCandidateForLlmSelection(
                prompt, artifactKind, llmCandidate, candidates);
    }

    private AgenticAuthoringCandidate strongerGovernedOperationalCandidateForFinalSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.strongerGovernedOperationalCandidateForFinalSelection(
                prompt, artifactKind, selectedCandidate, candidates);
    }

    private AgenticAuthoringCandidate materializableResourceDiscoveryGovernedOperationalCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.materializableResourceDiscoveryGovernedOperationalCandidate(
                request, prompt, artifactKind, selectedCandidate, candidates);
    }

    private AgenticAuthoringCandidate domainDiscoveryScopedOperationalCandidateForResourceDiscovery(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.domainDiscoveryScopedOperationalCandidateForResourceDiscovery(
                request, prompt, artifactKind, selectedCandidate, candidates);
    }

    private AgenticAuthoringCandidate strongerDedicatedProjectionCandidateForSemanticNeed(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return projectionRoleResolutionSupport.strongerDedicatedProjectionCandidateForSemanticNeed(
                prompt,
                artifactKind,
                selectedCandidate,
                candidates);
    }

    private AgenticAuthoringCandidate strongerDedicatedProjectionCandidateForGovernedNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return projectionRoleResolutionSupport.strongerDedicatedProjectionCandidateForGovernedNeed(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                candidates);
    }

    private boolean shouldRequireReviewForLowerRankedLlmSelection(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        return projectionRoleResolutionSupport.shouldRequireReviewForLowerRankedLlmSelection(
                request,
                prompt,
                artifactKind,
                llmIntent,
                selectedCandidate,
                rankedCandidates);
    }

    private boolean shouldRequireReviewForRoleMismatchedSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        return projectionRoleResolutionSupport.shouldRequireReviewForRoleMismatchedSelection(
                prompt,
                artifactKind,
                selectedCandidate,
                rankedCandidates);
    }

    private boolean shouldRequireReviewForUnanchoredLowConfidenceSelection(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        return projectionRoleResolutionSupport.shouldRequireReviewForUnanchoredLowConfidenceSelection(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                rankedCandidates);
    }

    private AgenticAuthoringCandidate resourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return projectionRoleResolutionSupport.resourceDiscoveryFocusedCandidate(
                request,
                prompt,
                artifactKind,
                candidates);
    }

    private boolean isDomainDiscoveryScopedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        return projectionRoleResolutionSupport.isDomainDiscoveryScopedCandidate(request, candidate);
    }

    private AgenticAuthoringCandidate projectionResourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return projectionRoleResolutionSupport.projectionResourceDiscoveryFocusedCandidate(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                candidates);
    }

    private boolean isGroundedByResourceDiscoveryFocus(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        return projectionRoleResolutionSupport.isGroundedByResourceDiscoveryFocus(
                request,
                selectedCandidate,
                rankedCandidates);
    }

    private boolean shouldPreferResourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate focusedCandidate,
            AgenticAuthoringCandidate selectedCandidate) {
        return projectionRoleResolutionSupport.shouldPreferResourceDiscoveryFocusedCandidate(
                request,
                focusedCandidate,
                selectedCandidate);
    }

    private boolean shouldPreserveSelectedProjectionForSemanticNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringCandidate focusedCandidate) {
        return projectionRoleResolutionSupport.shouldPreserveSelectedProjectionForSemanticNeed(
                request,
                prompt,
                artifactKind,
                selectedCandidate,
                focusedCandidate);
    }

    private boolean isProjectionCandidateForSemanticNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        return projectionRoleResolutionSupport.isProjectionCandidateForSemanticNeed(
                request,
                prompt,
                artifactKind,
                candidate);
    }

    private boolean isProjectionCandidateForPromptSemanticNeed(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        return projectionRoleResolutionSupport.isProjectionCandidateForPromptSemanticNeed(
                prompt,
                artifactKind,
                candidate);
    }

    private boolean hasProjectionResourceDiscoveryFocus(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind) {
        return projectionRoleResolutionSupport.hasProjectionResourceDiscoveryFocus(request, artifactKind);
    }

    private SemanticResourceNeed governedResourceDiscoveryNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind) {
        return projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind);
    }

    private AgenticAuthoringResourceSearchFocus resourceDiscoverySearchFocus(
            AgenticAuthoringIntentResolutionRequest request) {
        return projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
    }

    private int projectionResourceDiscoveryAlignmentScore(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate) {
        return projectionRoleResolutionSupport.projectionResourceDiscoveryAlignmentScore(request, focus, candidate);
    }

    private boolean hasPrimaryEntityProjectionScopeLead(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate focusedCandidate,
            AgenticAuthoringCandidate selectedCandidate) {
        return projectionRoleResolutionSupport.hasPrimaryEntityProjectionScopeLead(
                focus,
                focusedCandidate,
                selectedCandidate);
    }

    private int resourceSearchFocusAlignmentScore(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate) {
        return projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, candidate);
    }

    private boolean isDedicatedProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        return projectionRoleResolutionSupport.isDedicatedProjectionCandidate(candidate, projectionRole);
    }

    private boolean isMixedOperationalProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        return projectionRoleResolutionSupport.isMixedOperationalProjectionCandidate(candidate, projectionRole);
    }

    private boolean hasRoleProjectionMismatchForGenericOperation(AgenticAuthoringCandidate selectedCandidate) {
        return projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(selectedCandidate);
    }

    private AgenticAuthoringCandidate promptAlignedBusinessCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.promptAlignedBusinessCandidate(prompt, candidates);
    }

    private AgenticAuthoringCandidate promptAlignedDirectSemanticCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.promptAlignedDirectSemanticCandidate(prompt, candidates);
    }

    private boolean sameCandidate(AgenticAuthoringCandidate left, AgenticAuthoringCandidate right) {
        return candidateScoringSupport.sameCandidate(left, right);
    }

    private boolean isResourceChoiceClarificationAnswer(
            AgenticAuthoringConversationTurn turn,
            AgenticAuthoringCandidate contextHintCandidate,
            String artifactKind,
            String operationKind) {
        return turn != null
                && turn.answeredPendingClarification()
                && contextHintCandidate != null
                && ("dashboard".equals(artifactKind) || pendingClarificationMentionsDashboard(turn))
                && ("modify".equals(operationKind) || "create".equals(operationKind) || "unknown".equals(operationKind));
    }

    private boolean shouldPromoteTargetlessBusinessDashboardPrompt(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringTarget target) {
        if (target != null || !"modify".equals(operationKind) || !"dashboard".equals(artifactKind)) {
            return false;
        }
        String normalized = normalize(prompt);
        boolean asksForNewDashboardExperience = containsAny(normalized,
                "quero",
                "preciso",
                "necessito",
                "gostaria",
                "monte",
                "montar",
                "crie",
                "criar",
                "gerar",
                "gere")
                && containsAny(normalized, "dashboard", "painel", "grafico", "graficos", "indicador", "indicadores");
        boolean describesBusinessAnalysis = containsAny(normalized,
                "entender",
                "analisar",
                "acompanhar",
                "enxergar",
                "investigar",
                "maiores",
                "custos",
                "detalhes",
                "lista");
        boolean explicitlyTargetsExistingWidget = containsAny(normalized,
                "componente existente",
                "widget existente",
                "grafico existente",
                "painel existente",
                "nessa tabela",
                "neste grafico",
                "nesse grafico",
                "selecionado",
                "selecionada");
        return asksForNewDashboardExperience && describesBusinessAnalysis && !explicitlyTargetsExistingWidget;
    }

    private String businessDashboardCreationChangeKind(String prompt) {
        return containsAny(normalize(prompt),
                "detalhe",
                "detalhes",
                "investigar",
                "abrir registros",
                "lista",
                "listagem",
                "drill",
                "drill-down",
                "drilldown")
                ? "create_chart_drilldown"
                : "create_artifact";
    }

    private boolean answeredResourceCandidateClarification(AgenticAuthoringConversationTurn turn) {
        if (turn == null || !turn.answeredPendingClarification()) {
            return false;
        }
        String text = normalize(String.join(" ",
                valueOrDefault(turn.assistantMessage(), ""),
                turn.questions() == null ? "" : String.join(" ", turn.questions())));
        return text.contains("recurso de negocio")
                || text.contains("fonte de dados")
                || text.contains("alimentar esta tela")
                || text.contains("api principal");
    }

    private boolean pendingClarificationMentionsDashboard(AgenticAuthoringConversationTurn turn) {
        if (turn == null) {
            return false;
        }
        String text = normalize(String.join(" ",
                valueOrDefault(turn.assistantMessage(), ""),
                turn.questions() == null ? "" : String.join(" ", turn.questions())));
        return text.contains("dashboard") || text.contains("painel");
    }

    private boolean isVisualProjectionRefinementPrompt(
            String prompt,
            AgenticAuthoringConversationTurn turn,
            JsonNode currentPageSummary) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || currentPageBoundResource(currentPageSummary).isBlank()) {
            return false;
        }
        boolean asksForCharts = containsAny(normalized,
                "grafico", "graficos", "chart", "charts", "dashboard", "painel", "visualizacao", "visualizar");
        if (!asksForCharts) {
            return false;
        }
        boolean refinementLanguage = containsAny(normalized,
                "prefiro", "preferia", "gostei", "mas", "melhor",
                "ao inves", "em vez", "no lugar");
        boolean previousPageHasTable = currentPageHasArtifact(currentPageSummary, "table");
        boolean pendingTurn = turn != null && !valueOrDefault(turn.sourcePrompt(), "").isBlank();
        return refinementLanguage || previousPageHasTable || pendingTurn;
    }

    private boolean isCurrentPageDrilldownRefinementPrompt(
            String prompt,
            JsonNode currentPageSummary) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()
                || !currentPageHasArtifact(currentPageSummary, "dashboard")) {
            return false;
        }
        boolean requestsPageChange = containsAny(normalized,
                "use", "usar", "usando",
                "acrescente", "acrescentar",
                "adicione", "adicionar",
                "inclua", "incluir",
                "coloque", "colocar",
                "mostre", "mostrar",
                "exiba", "exibir",
                "crie", "criar");
        boolean referencesCurrentChart = containsAny(normalized,
                "grafico", "gráfico", "chart", "visualizacao", "visualização");
        boolean asksForDetailSurface = containsAny(normalized,
                "detalhe", "detalhes",
                "registro", "registros",
                "linha", "linhas",
                "item selecionado", "selecionado",
                "modal", "formulario", "formulário", "somente leitura",
                "drill", "drilldown", "drill-down");
        boolean asksForTable = containsAny(normalized,
                "tabela", "table", "lista", "listagem", "grid");
        boolean asksForModal = containsAny(normalized,
                "modal", "formulario", "formulário", "somente leitura");
        boolean asksForFiltering = containsAny(normalized,
                "filtro", "filtrar", "filtre", "conectado", "vinculado");
        return requestsPageChange
                && referencesCurrentChart
                && (asksForTable || asksForModal)
                && (asksForDetailSurface || asksForFiltering);
    }

    private AgenticAuthoringCandidate currentPageBoundResourceCandidate(
            JsonNode currentPageSummary,
            List<AgenticAuthoringCandidate> candidates,
            String artifactKind) {
        JsonNode binding = firstCurrentPageServerBinding(currentPageSummary);
        String resourcePath = jsonText(binding, "resourcePath");
        if (resourcePath.isBlank()) {
            return null;
        }
        String submitMethod = valueOrDefault(jsonText(binding, "submitMethod"), "get");
        String submitUrl = valueOrDefault(jsonText(binding, "submitUrl"), resourcePath);
        String schemaUrl = contextCandidateSupport.canonicalContextSchemaUrl(
                jsonText(binding, "schemaUrl"), submitUrl, submitMethod);
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(submitUrl);
        Optional<AgenticAuthoringCandidate> existing = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates)
                .stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst();
        if (existing.isPresent()) {
            return withEvidence(existing.get(), "conversation-refinement-current-page-resource");
        }
        return new AgenticAuthoringCandidate(
                normalizedResourcePath,
                submitMethod,
                schemaUrl,
                normalizePath(submitUrl),
                submitMethod,
                1.0d,
                "resource preserved from the current page during visual projection refinement",
                List.of("conversation-refinement-current-page-resource",
                        "schema-probe-pending",
                        "actions-probe-pending",
                        "capabilities-probe-pending"));
    }

    private boolean shouldDetachVisualProjectionFromCurrentResource(
            String prompt,
            JsonNode currentPageSummary,
            List<AgenticAuthoringCandidate> candidates) {
        String currentResourcePath = normalizePath(currentPageBoundResource(currentPageSummary));
        if (currentResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
        if (promptAlignedCandidate == null) {
            return false;
        }
        String alignedResourcePath = normalizePath(promptAlignedCandidate.resourcePath());
        if (alignedResourcePath.isBlank() || alignedResourcePath.equals(currentResourcePath)) {
            return false;
        }
        AgenticAuthoringCandidate currentCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> currentResourcePath.equals(normalizePath(candidate.resourcePath())))
                .findFirst()
                .orElse(null);
        int alignedScore = promptCandidateAlignmentScore(prompt, promptAlignedCandidate);
        int currentScore = promptCandidateAlignmentScore(prompt, currentCandidate);
        return alignedScore > 0 && alignedScore > currentScore;
    }

    private String currentPageBoundResource(JsonNode currentPageSummary) {
        JsonNode binding = firstCurrentPageServerBinding(currentPageSummary);
        return jsonText(binding, "resourcePath");
    }

    private JsonNode firstCurrentPageServerBinding(JsonNode currentPageSummary) {
        JsonNode bindings = currentPageSummary == null
                ? null
                : currentPageSummary.path("structuralInspection").path("serverBindings");
        if (bindings == null || !bindings.isArray() || bindings.isEmpty()) {
            return objectMapper.missingNode();
        }
        return bindings.get(0);
    }

    private boolean currentPageHasArtifact(JsonNode currentPageSummary, String artifactKind) {
        JsonNode widgets = currentPageSummary == null
                ? null
                : currentPageSummary.path("structuralInspection").path("widgets");
        if (widgets == null || !widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (artifactKind.equals(widget.path("artifactKind").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGovernedClarificationGate(AgenticAuthoringGateResult gate) {
        return gate != null
                && ("clarification_required".equals(gate.status()) || "route_required".equals(gate.status()))
                && gate.messages() != null
                && !gate.messages().isEmpty();
    }

    private boolean shouldUseLlmArtifactClarification(AgenticAuthoringGateResult gate) {
        return gate != null
                && gate.messages() != null
                && gate.messages().size() == 1
                && gate.messages().contains("intent-artifact-unknown");
    }

    private AgenticAuthoringCandidate explicitResourcePathCandidate(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return contextCandidateSupport.explicitResourcePathCandidate(prompt, artifactKind, candidates);
    }

    private String explicitResourcePath(String prompt) {
        return contextCandidateSupport.explicitResourcePath(prompt);
    }

    private AgenticAuthoringCandidate selectFormWriteCandidate(
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String prompt) {
        return contextCandidateSupport.selectFormWriteCandidate(artifactKind, candidates, fallback, prompt);
    }

    private boolean isCreateEndpointCandidate(AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.isCreateEndpointCandidate(candidate);
    }

    String semanticRawPrompt(AgenticAuthoringIntentResolutionRequest request, String rawPrompt) {
        List<AgenticAuthoringConversationMessage> messages =
                request == null || request.conversationMessages() == null
                        ? List.of()
                        : request.conversationMessages();
        List<String> userMessages = new ArrayList<>(messages.stream()
                .filter(message -> message != null && "user".equalsIgnoreCase(valueOrDefault(message.role(), "")))
                .map(AgenticAuthoringConversationMessage::text)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(message -> !message.isBlank())
                .toList());
        String currentPrompt = valueOrDefault(rawPrompt, "");
        if (!userMessages.isEmpty()
                && sameTransportPrompt(userMessages.get(userMessages.size() - 1), currentPrompt)) {
            // Transport deduplication only: this never classifies or routes the user's intent.
            userMessages.remove(userMessages.size() - 1);
        }
        String userConversation = String.join(" ", userMessages);
        return String.join(" ", valueOrDefault(rawPrompt, ""), userConversation).trim();
    }

    private boolean sameTransportPrompt(String left, String right) {
        String compactLeft = compactTransportPrompt(left);
        String compactRight = compactTransportPrompt(right);
        return !compactLeft.isBlank() && compactLeft.equalsIgnoreCase(compactRight);
    }

    private String compactTransportPrompt(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean isWriteCandidate(AgenticAuthoringCandidate candidate) {
        return contextCandidateSupport.isWriteCandidate(candidate);
    }

    private List<String> warnings(AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateDiscoveryTelemetrySupport.warnings(llmIntent);
    }

    private boolean isPrimaryLlmIntentProviderFailure(
            boolean shouldResolveLlmIntent,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateDiscoveryTelemetrySupport.isPrimaryLlmIntentProviderFailure(shouldResolveLlmIntent, llmIntent);
    }

    private boolean isLlmProviderFailure(AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateDiscoveryTelemetrySupport.isLlmProviderFailure(llmIntent);
    }

    private boolean hasLlmWarning(AgenticAuthoringLlmIntentResolution llmIntent, String warning) {
        return candidateDiscoveryTelemetrySupport.hasLlmWarning(llmIntent, warning);
    }

    private List<String> sanitizeConsultativeWarnings(
            List<String> warnings,
            String operationKind,
            String artifactKind,
            String changeKind) {
        return candidateDiscoveryTelemetrySupport.sanitizeConsultativeWarnings(
                warnings, operationKind, artifactKind, changeKind);
    }

    private List<AgenticAuthoringCandidate> filterConsultativeApiCatalogCandidates(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.filterConsultativeApiCatalogCandidates(
                prompt, artifactKind, candidates);
    }

    private List<String> llmClarificationQuestions(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringGateResult gate,
            List<String> fallbackQuestions) {
        return candidateDiscoveryTelemetrySupport.llmClarificationQuestions(llmIntent, gate, fallbackQuestions);
    }

    private List<String> withWarning(List<String> warnings, String warning) {
        return candidateDiscoveryTelemetrySupport.withWarning(warnings, warning);
    }

    private List<String> withoutWarnings(List<String> warnings, String... removedWarnings) {
        return candidateDiscoveryTelemetrySupport.withoutWarnings(warnings, removedWarnings);
    }

    private List<String> withCandidateProvenanceWarnings(
            List<String> warnings,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.withCandidateProvenanceWarnings(
                warnings, selectedCandidate, candidates);
    }

    private JsonNode withResolutionTelemetry(
            JsonNode diagnostics,
            boolean llmResolutionAttempted,
            AgenticAuthoringLlmIntentResolution llmIntent,
            boolean keywordFallbackApplied,
            boolean semanticPolicyCorrectedAnalyticalDashboardIntent,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.withResolutionTelemetry(
                diagnostics,
                llmResolutionAttempted,
                llmIntent,
                keywordFallbackApplied,
                semanticPolicyCorrectedAnalyticalDashboardIntent,
                selectedCandidate,
                candidates);
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidateDiscoveryTelemetrySupport.hasEvidence(candidate, evidence);
    }

    private boolean isGovernedDeterministicResolution(
            boolean deterministicFallbackApplied,
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return candidateDiscoveryTelemetrySupport.isGovernedDeterministicResolution(
                deterministicFallbackApplied, gate, operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean shouldSuppressWeakUnresolvedLlmSelection(
            boolean deterministicFallbackApplied,
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return candidateDiscoveryTelemetrySupport.shouldSuppressWeakUnresolvedLlmSelection(
                deterministicFallbackApplied, llmIntent, operationKind, artifactKind, selectedCandidate);
    }

    private boolean shouldDeferWeakLexicalApiCatalogSelection(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return candidateDiscoveryTelemetrySupport.shouldDeferWeakLexicalApiCatalogSelection(
                operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean shouldDeferWeakLexicalConsultativeSelection(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return candidateDiscoveryTelemetrySupport.shouldDeferWeakLexicalConsultativeSelection(
                operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean shouldDeferUnresolvedLlmWeakLexicalSelection(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate,
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.shouldDeferUnresolvedLlmWeakLexicalSelection(
                llmIntent, selectedCandidate, prompt, candidates);
    }

    private boolean isWeakLexicalCandidate(AgenticAuthoringCandidate candidate) {
        return candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate);
    }

    private boolean hasTrustedSelectionEvidence(AgenticAuthoringCandidate candidate) {
        return candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(candidate);
    }

    private boolean hasStrongGroundingEvidence(AgenticAuthoringCandidate candidate) {
        return candidateDiscoveryTelemetrySupport.hasStrongGroundingEvidence(candidate);
    }

    private boolean isLlmFollowUpKind(AgenticAuthoringLlmIntentResolution llmIntent, String expected) {
        return candidateDiscoveryTelemetrySupport.isLlmFollowUpKind(llmIntent, expected);
    }

    private AgenticAuthoringPendingClarification pendingClarification(
            String effectivePrompt,
            AgenticAuthoringGateResult gate,
            String assistantMessage,
            List<String> questions,
            String clientTurnId,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        return candidateDiscoveryTelemetrySupport.pendingClarification(
                effectivePrompt, gate, assistantMessage, questions, clientTurnId, attachmentSummaries);
    }

    private boolean isExplicitCreateConfirmation(String prompt) {
        return candidateDiscoveryTelemetrySupport.isExplicitCreateConfirmation(prompt);
    }

    private boolean isConsultativePrompt(String prompt) {
        return candidateDiscoveryTelemetrySupport.isConsultativePrompt(prompt);
    }

    private boolean isApiCatalogQuestion(String prompt) {
        return candidateDiscoveryTelemetrySupport.isApiCatalogQuestion(prompt);
    }

    private boolean isBareDomainPrompt(String prompt) {
        return candidateDiscoveryTelemetrySupport.isBareDomainPrompt(prompt);
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        return candidateDiscoveryTelemetrySupport.discoverCandidates(
                prompt, artifactKind, target, tenantId, environment);
    }

    private List<AgenticAuthoringCandidate> discoverInitialCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        return candidateDiscoveryTelemetrySupport.discoverInitialCandidates(
                prompt, artifactKind, target, tenantId, environment);
    }

    private List<AgenticAuthoringCandidate> discoverPreLlmContextCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        return candidateDiscoveryTelemetrySupport.discoverPreLlmContextCandidates(prompt, artifactKind, target);
    }

    private List<AgenticAuthoringCandidate> groundCandidates(
            String prompt,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        return candidateDiscoveryTelemetrySupport.groundCandidates(prompt, candidates, tenantId, environment);
    }

    private List<AgenticAuthoringCandidate> candidatesForLlmIntent(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.candidatesForLlmIntent(prompt, candidates);
    }

    private List<AgenticAuthoringCandidate> deduplicateCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.deduplicateCandidates(candidates);
    }

    private String discoveryArtifactKindForPrompt(String prompt, String artifactKind) {
        return candidateDiscoveryTelemetrySupport.discoveryArtifactKindForPrompt(prompt, artifactKind);
    }

    private boolean hasExplicitSourceCandidate(List<AgenticAuthoringCandidate> candidates) {
        return candidateDiscoveryTelemetrySupport.hasExplicitSourceCandidate(candidates);
    }

    private AgenticAuthoringCandidate preferredCandidateForSameResource(
            AgenticAuthoringCandidate left,
            AgenticAuthoringCandidate right) {
        return candidateDiscoveryTelemetrySupport.preferredCandidateForSameResource(left, right);
    }

    private List<AgenticAuthoringCandidate> rankCandidatesForDecision(
            List<AgenticAuthoringCandidate> candidates,
            String artifactKind,
            String prompt) {
        return candidateScoringSupport.rankCandidatesForDecision(candidates, artifactKind, prompt);
    }

    private double semanticRoleFit(AgenticAuthoringCandidate candidate, SemanticResourceNeed resourceNeed) {
        return candidateScoringSupport.semanticRoleFit(candidate, resourceNeed);
    }

    private boolean isDerivedProjectionCandidate(AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.isDerivedProjectionCandidate(candidate);
    }

    private SemanticResourceNeed semanticResourceNeed(String prompt, String artifactKind) {
        return candidateScoringSupport.semanticResourceNeed(prompt, artifactKind);
    }

    private int evidenceStrength(AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.evidenceStrength(candidate);
    }

    private AgenticAuthoringCandidate selectCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringTarget target,
            String operationKind,
            String artifactKind,
            String prompt) {
        return candidateScoringSupport.selectCandidate(candidates, target, operationKind, artifactKind, prompt);
    }

    private AgenticAuthoringCandidate analyticalRoleCandidateForComparisonPrompt(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.analyticalRoleCandidateForComparisonPrompt(
                prompt, artifactKind, selectedCandidate, candidates);
    }

    private boolean isVisualMaterializationArtifact(String artifactKind) {
        return candidateScoringSupport.isVisualMaterializationArtifact(artifactKind);
    }

    private boolean isMaterializablePageCompositionOperation(String operationId) {
        return candidateScoringSupport.isMaterializablePageCompositionOperation(operationId);
    }

    private boolean hasPageWidgets(JsonNode currentPage) {
        return candidateScoringSupport.hasPageWidgets(currentPage);
    }

    private AgenticAuthoringCandidate visualMaterializationCandidate(
            String artifactKind,
            String currentComponentId,
            String preferredComponent,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.visualMaterializationCandidate(
                artifactKind, currentComponentId, preferredComponent, candidates);
    }

    private boolean shouldTrustVisualizationDecisionForArtifact(
            String artifactKind,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return candidateScoringSupport.shouldTrustVisualizationDecisionForArtifact(artifactKind, visualizationDecision);
    }

    private boolean isBroadAnalyticalCanvasPrompt(String prompt) {
        return candidateScoringSupport.isBroadAnalyticalCanvasPrompt(prompt);
    }

    private boolean hasStrongPromptAlignment(String prompt, List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.hasStrongPromptAlignment(prompt, candidates);
    }

    private boolean isDataSourceRefinement(AgenticAuthoringSemanticRefinement semanticRefinement) {
        return candidateScoringSupport.isDataSourceRefinement(semanticRefinement);
    }

    private int promptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.promptCandidateAlignmentScore(prompt, candidate);
    }

    private AgenticAuthoringCandidate promptAlignedGovernedRuleCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.promptAlignedGovernedRuleCandidate(prompt, candidates);
    }

    private boolean canUsePromptAlignedCandidate(
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.canUsePromptAlignedCandidate(candidate, candidates);
    }

    private int directPromptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.directPromptCandidateAlignmentScore(prompt, candidate);
    }

    private boolean tokenMatchesCandidateText(String token, String candidateText) {
        return candidateScoringSupport.tokenMatchesCandidateText(token, candidateText);
    }

    private int strongestPromptCandidateAlignmentScore(String prompt, List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.strongestPromptCandidateAlignmentScore(prompt, candidates);
    }

    private List<String> promptSpecificTokens(String prompt) {
        return candidateScoringSupport.promptSpecificTokens(prompt);
    }

    private String candidateText(AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.candidateText(candidate);
    }

    private AgenticAuthoringCandidate targetBoundCandidate(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringTarget target) {
        return candidateScoringSupport.targetBoundCandidate(candidate, target);
    }

    private boolean isBroadApiCatalogResourceDiscoveryPrompt(String prompt) {
        return candidateScoringSupport.isBroadApiCatalogResourceDiscoveryPrompt(prompt);
    }

    private boolean isSpecificApiCatalogAnswerPrompt(String prompt) {
        return candidateScoringSupport.isSpecificApiCatalogAnswerPrompt(prompt);
    }

    private boolean isApiCatalogResourceListPrompt(String prompt) {
        return candidateScoringSupport.isApiCatalogResourceListPrompt(prompt);
    }

    private boolean isApiCatalogCandidateChoicePrompt(String prompt) {
        return candidateScoringSupport.isApiCatalogCandidateChoicePrompt(prompt);
    }

    private boolean promptMentionsSpecificCandidateToken(String prompt, AgenticAuthoringCandidate candidate) {
        return candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, candidate);
    }

    private boolean isGenericAnalyticalCandidateToken(String token) {
        return candidateScoringSupport.isGenericAnalyticalCandidateToken(token);
    }

    private List<String> candidatePathIdentityTokens(String normalizedPath) {
        return candidateScoringSupport.candidatePathIdentityTokens(normalizedPath);
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidateScoringSupport.isBroadArtifactDiscoveryOnly(candidates);
    }

    private boolean shouldDetachCurrentTarget(String prompt, String artifactKind, AgenticAuthoringTarget target) {
        return candidateScoringSupport.shouldDetachCurrentTarget(prompt, artifactKind, target);
    }

    private boolean isDataSourceReplacementPrompt(String normalizedPrompt) {
        return candidateScoringSupport.isDataSourceReplacementPrompt(normalizedPrompt);
    }

    private boolean isExplicitSourcePreservePrompt(String normalizedPrompt) {
        return candidateScoringSupport.isExplicitSourcePreservePrompt(normalizedPrompt);
    }

    private boolean isAnalyticalComparisonPrompt(String prompt) {
        return candidateScoringSupport.isAnalyticalComparisonPrompt(prompt);
    }

    private boolean isOptionalDataSourceHint(String prompt) {
        return candidateScoringSupport.isOptionalDataSourceHint(prompt);
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, double score, String reason, String evidence) {
        return candidateScoringSupport.candidate(resourcePath, score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, String operation, double score, String reason, String evidence) {
        return candidateScoringSupport.candidate(resourcePath, operation, score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(
            String resourcePath,
            String submitUrl,
            String operation,
            double score,
            String reason,
            String evidence) {
        return candidateScoringSupport.candidate(resourcePath, submitUrl, operation, score, reason, evidence);
    }

    private String authoringProfile(String operationKind, String artifactKind, boolean explicitLocalPageComposition) {
        return candidateScoringSupport.authoringProfile(operationKind, artifactKind, explicitLocalPageComposition);
    }

    private AgenticAuthoringGateResult withPromptSpecificGateMessages(
            AgenticAuthoringGateResult gate,
            String rawPrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringConversationTurn turn) {
        return candidateScoringSupport.withPromptSpecificGateMessages(
                gate, rawPrompt, prompt, operationKind, artifactKind, selectedCandidate, turn);
    }

    private AgenticAuthoringGateResult withGateMessage(AgenticAuthoringGateResult gate, String message) {
        return candidateScoringSupport.withGateMessage(gate, message);
    }

    private AgenticAuthoringGateResult withSharedRuleAuthoringGate(
            AgenticAuthoringGateResult gate,
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring,
            String operationKind,
            boolean deterministicFallbackApplied) {
        return candidateScoringSupport.withSharedRuleAuthoringGate(
                gate, request, prompt, selectedCandidate, llmRequiresGovernedAuthoring, operationKind, deterministicFallbackApplied);
    }

    private AgenticAuthoringGateResult withExplicitLocalUiCompositionGate(
            AgenticAuthoringGateResult gate,
            boolean explicitLocalUiComposition,
            boolean explicitLocalTargetedComposition) {
        return candidateScoringSupport.withExplicitLocalUiCompositionGate(
                gate, explicitLocalUiComposition, explicitLocalTargetedComposition);
    }

    private AgenticAuthoringGateResult withPrimaryLlmProviderFailureGate(
            AgenticAuthoringGateResult gate,
            boolean primaryLlmIntentProviderFailure) {
        return candidateScoringSupport.withPrimaryLlmProviderFailureGate(gate, primaryLlmIntentProviderFailure);
    }

    private boolean hasExplicitLocalComponentTarget(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind) {
        return candidateScoringSupport.hasExplicitLocalComponentTarget(request, artifactKind);
    }

    private boolean isManifestOwnedComponentOperation(
            AgenticAuthoringIntentResolutionRequest request,
            String changeKind,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        return candidateScoringSupport.isManifestOwnedComponentOperation(request, changeKind, componentCapabilities);
    }

    private String explicitLocalTargetedArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String currentArtifactKind) {
        return candidateScoringSupport.explicitLocalTargetedArtifactKind(request, prompt, currentArtifactKind);
    }

    private boolean requiresSharedRuleAuthoring(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring,
            String operationKind,
            boolean deterministicFallbackApplied) {
        return candidateScoringSupport.requiresSharedRuleAuthoring(
                request, prompt, selectedCandidate, llmRequiresGovernedAuthoring, operationKind, deterministicFallbackApplied);
    }

    private boolean requiresGovernedAuthoring(AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateScoringSupport.requiresGovernedAuthoring(llmIntent);
    }

    private boolean isExplicitGovernedBusinessRulePrompt(String prompt) {
        return candidateScoringSupport.isExplicitGovernedBusinessRulePrompt(prompt);
    }

    private boolean isExplicitLocalUiCompositionPrompt(String prompt) {
        return candidateScoringSupport.isExplicitLocalUiCompositionPrompt(prompt);
    }

    private boolean isExplicitLocalPageCompositionPrompt(String prompt) {
        return candidateScoringSupport.isExplicitLocalPageCompositionPrompt(prompt);
    }

    private String materializablePageCompositionOperation(String fallbackOperationKind, String prompt) {
        return candidateScoringSupport.materializablePageCompositionOperation(fallbackOperationKind, prompt);
    }

    private String materializablePageCompositionChangeKind(
            String changeKind,
            String operationKind,
            String prompt) {
        return candidateScoringSupport.materializablePageCompositionChangeKind(changeKind, operationKind, prompt);
    }

    private boolean shouldSuppressLlmAssistantMessageForExplicitLocalComposition(
            boolean explicitLocalUiComposition,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateScoringSupport.shouldSuppressLlmAssistantMessageForExplicitLocalComposition(
                explicitLocalUiComposition, llmIntent);
    }

    private boolean isConfirmedDataSourceSelection(String prompt) {
        return candidateScoringSupport.isConfirmedDataSourceSelection(prompt);
    }

    private boolean isBareConfirmationPrompt(String prompt) {
        return candidateScoringSupport.isBareConfirmationPrompt(prompt);
    }

    private List<String> clarificationQuestions(
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return apiCatalogGuidanceSupport.clarificationQuestions(
                gate, operationKind, artifactKind, selectedCandidate, candidates);
    }

    private boolean isConsultativeCatalogQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isConsultativeCatalogQuestion(prompt);
    }

    private boolean isConsultativeDomainQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isConsultativeDomainQuestion(prompt);
    }

    private boolean isDomainDataCatalogQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isDomainDataCatalogQuestion(prompt);
    }

    private boolean isConsultativeFormPolicyQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isConsultativeFormPolicyQuestion(prompt);
    }

    private boolean isConsultativePlatformCapabilityQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isConsultativePlatformCapabilityQuestion(prompt);
    }

    private String platformCapabilityChangeKind(String prompt) {
        return apiCatalogGuidanceSupport.platformCapabilityChangeKind(prompt);
    }

    private boolean isConsultativeGovernanceQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isConsultativeGovernanceQuestion(prompt);
    }

    private String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        return apiCatalogGuidanceSupport.assistantMessage(
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                gate,
                answeredBareDomainClarification,
                componentCapabilities);
    }

    private boolean shouldUsePlatformGuidanceMessage(String prompt, String assistantMessage) {
        return apiCatalogGuidanceSupport.shouldUsePlatformGuidanceMessage(prompt, assistantMessage);
    }

    private boolean shouldUseApiCatalogGuidanceMessage(String assistantMessage) {
        return apiCatalogGuidanceSupport.shouldUseApiCatalogGuidanceMessage(assistantMessage);
    }

    private String sanitizeApiCatalogConsultativeLanguage(
            String assistantMessage,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return apiCatalogGuidanceSupport.sanitizeApiCatalogConsultativeLanguage(
                assistantMessage, selectedCandidate, candidates);
    }

    private String platformCapabilityAssistantMessage(
            String prompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        return apiCatalogGuidanceSupport.platformCapabilityAssistantMessage(prompt, componentCapabilities);
    }

    private String normalizeTargetlessCreationChangeKind(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringTarget target) {
        return candidateScoringSupport.normalizeTargetlessCreationChangeKind(
                operationKind, artifactKind, changeKind, target);
    }

    private boolean shouldExposeAsChartArtifact(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateScoringSupport.shouldExposeAsChartArtifact(
                prompt, operationKind, artifactKind, changeKind, llmIntent);
    }

    private boolean llmSingleChartDecisionRejectedForOpenOperationalPrompt(
            String prompt,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return candidateScoringSupport.llmSingleChartDecisionRejectedForOpenOperationalPrompt(prompt, llmIntent);
    }

    private boolean shouldIsolateConversationHistoryForBlankCreate(
            AgenticAuthoringIntentResolutionRequest request,
            String operationKind,
            String artifactKind,
            String changeKind) {
        return candidateScoringSupport.shouldIsolateConversationHistoryForBlankCreate(
                request, operationKind, artifactKind, changeKind);
    }

    private AgenticAuthoringIntentResolutionRequest withoutConversationMessages(
            AgenticAuthoringIntentResolutionRequest request) {
        return candidateScoringSupport.withoutConversationMessages(request);
    }

    private JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return apiCatalogGuidanceSupport.apiCatalogAnswer(
                prompt, operationKind, artifactKind, changeKind, selectedCandidate, candidates);
    }

    private String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return apiCatalogGuidanceSupport.apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
    }

    private String apiCatalogResourceListAssistantMessage(List<AgenticAuthoringCandidate> candidates) {
        return apiCatalogGuidanceSupport.apiCatalogResourceListAssistantMessage(candidates);
    }

    private boolean isApiOperationCatalogQuestion(String prompt) {
        return apiCatalogGuidanceSupport.isApiOperationCatalogQuestion(prompt);
    }

    private String ambiguousResourceAssistantMessage(String artifactKind, List<AgenticAuthoringCandidate> candidates) {
        return quickReplySupport.ambiguousResourceAssistantMessage(artifactKind, candidates);
    }

    private String missingResourceAssistantMessage(String artifactKind) {
        return quickReplySupport.missingResourceAssistantMessage(artifactKind);
    }

    private List<AgenticAuthoringQuickReply> ensureClarificationQuickReplies(
            String effectivePrompt,
            boolean clarificationRequired,
            List<AgenticAuthoringQuickReply> quickReplies) {
        return quickReplySupport.ensureClarificationQuickReplies(
                effectivePrompt, clarificationRequired, quickReplies);
    }

    private boolean isApiCatalogQuestion(String operationKind, String artifactKind, String changeKind) {
        return quickReplySupport.isApiCatalogQuestion(operationKind, artifactKind, changeKind);
    }

    private String candidateLabel(AgenticAuthoringCandidate candidate) {
        return quickReplySupport.candidateLabel(candidate);
    }

    private String sanitizePresentationText(
            String text,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return quickReplySupport.sanitizePresentationText(text, selectedCandidate, candidates);
    }

    private String requestedAuthoringFlow(AgenticAuthoringIntentResolutionRequest request) {
        return quickReplySupport.requestedAuthoringFlow(request);
    }

    private String formatCandidateOptions(List<AgenticAuthoringCandidate> candidates) {
        return quickReplySupport.formatCandidateOptions(candidates);
    }

    private String humanJoin(List<String> values) {
        return AgenticAuthoringTextNormalizationSupport.humanJoin(values);
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        return AgenticAuthoringTextNormalizationSupport.isReadProjectionOperation(submitUrl, operation);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String normalizePath(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalizePath(value);
    }

    private String jsonText(JsonNode node, String field) {
        return AgenticAuthoringTextNormalizationSupport.jsonText(node, field);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }
}
