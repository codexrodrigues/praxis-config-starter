package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final int MAX_ASSISTANT_MESSAGE_CHARS = 700;
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringLlmIntentResolverService llmIntentResolverService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer;
    private final ObjectMapper objectMapper;
    private final String domainCatalogServiceKey;
    private final AgenticAuthoringFormCapabilityCatalog formCapabilityCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringKeywordFallbackResolver keywordFallbackResolver =
            new AgenticAuthoringKeywordFallbackResolver(
                    formCapabilityCatalog,
                    tableCapabilityCatalog,
                    chartCapabilityCatalog);
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator =
            new AgenticAuthoringConversationTurnOrchestrator();
    private final AgenticAuthoringSemanticDecisionPolicy semanticDecisionPolicy =
            new AgenticAuthoringSemanticDecisionPolicy();

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
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.llmIntentResolverService = llmIntentResolverService;
        this.componentCapabilitiesService = componentCapabilitiesService;
        this.domainCatalogServiceKey = domainCatalogServiceKey;
        this.domainCatalogCandidateEnhancer = domainCatalogCandidateEnhancer;
    }

    public AgenticAuthoringIntentResolutionResult resolve(AgenticAuthoringIntentResolutionRequest request) {
        return resolve(request, null, null, null);
    }

    public AgenticAuthoringIntentResolutionResult resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String tenantId,
            String userId,
            String environment) {
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
        AgenticAuthoringSemanticRefinement semanticRefinement = semanticRefinement(prompt, activeDecision, null);
        boolean decisionMemoryRefinement = semanticRefinement.active();
        String effectiveSelectedWidgetKey = effectiveSelectedWidgetKey(request);
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage(), effectiveSelectedWidgetKey);
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), effectiveSelectedWidgetKey);
        AgenticAuthoringKeywordFallbackResolution fallbackResolution =
                keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
        String operationKind = fallbackResolution.operationKind();
        String artifactKind = fallbackResolution.artifactKind();
        String changeKind = fallbackResolution.changeKind();
        boolean contextualPreviewAction = isContextualPreviewAction(request);
        if (contextualPreviewAction) {
            operationKind = contextualPreviewOperationKind(request);
            artifactKind = contextualPreviewArtifactKind(request, artifactKind);
            changeKind = valueOrDefault(jsonText(request.contextHints(), "changeKind"), changeKind);
        }
        if (consultativePlatformCapabilityQuestion) {
            operationKind = "explain";
            artifactKind = "component";
            changeKind = platformCapabilityChangeKind(prompt);
        }
        if (consultativeDomainQuestion) {
            operationKind = "explore";
            artifactKind = "api_catalog";
            changeKind = "answer_api_catalog_question";
        }
        boolean shouldResolveLlmIntent = hasLlmIntentResolver
                && !governedResourceConfirmation
                && !contextualPreviewAction;
        List<AgenticAuthoringCandidate> candidates = shouldResolveLlmIntent
                ? discoverInitialCandidates(discoveryPrompt, artifactKind, target, tenantId, environment)
                : discoverCandidates(discoveryPrompt, artifactKind, target, tenantId, environment);
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
        boolean preLlmGovernedResourceChoiceApplied = shouldApplyPreLlmGovernedResourceChoice(
                prompt,
                operationKind,
                artifactKind,
                candidates,
                contextHintCandidate);
        if (preLlmGovernedResourceChoiceApplied) {
            shouldResolveLlmIntent = false;
        }
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = componentCapabilities();
        List<AgenticAuthoringCandidate> llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
        AgenticAuthoringLlmIntentResolution llmIntent = resolveLlmIntent(
                shouldResolveLlmIntent,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                llmCandidateOptions,
                componentCapabilities,
                tenantId,
                userId,
                environment);
        semanticRefinement = semanticRefinement(prompt, activeDecision, llmIntent);
        decisionMemoryRefinement = semanticRefinement.active();
        JsonNode llmDiagnostics = llmDiagnostics(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                llmCandidateOptions,
                componentCapabilities);
        boolean llmTreatsPendingAsContinuation = turn.answeredPendingClarification()
                && (isLlmFollowUpKind(llmIntent, "clarification_answer")
                || isLlmFollowUpKind(llmIntent, "refinement"));
        boolean llmTreatsPendingAsNewInstruction = turn.answeredPendingClarification()
                && isLlmFollowUpKind(llmIntent, "new_instruction");
        boolean llmSecondPassUsed = false;
        boolean deterministicFallbackApplied = !shouldResolveLlmIntent
                || (shouldResolveLlmIntent && (llmIntent == null || !llmIntent.resolved()));
        boolean semanticPolicyRefinedVisualProjection = false;
        boolean visualProjectionRefinement = isVisualProjectionRefinementPrompt(prompt, turn, currentPageSummary);
        boolean currentPageDrilldownRefinement =
                isCurrentPageDrilldownRefinementPrompt(prompt, currentPageSummary);
        if (!semanticRefinement.active() && visualProjectionRefinement) {
            semanticRefinement = visualProjectionRefinement(prompt);
        }
        if (llmTreatsPendingAsContinuation) {
            effectivePrompt = turn.effectivePrompt();
            prompt = normalize(effectivePrompt);
            fallbackResolution = keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
            if (!shouldResolveLlmIntent || llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
            }
            candidates = shouldResolveLlmIntent
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
            fallbackResolution = keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
            if (!shouldResolveLlmIntent || llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
            }
            candidates = shouldResolveLlmIntent
                    ? discoverInitialCandidates(prompt, artifactKind, target, tenantId, environment)
                    : discoverCandidates(prompt, artifactKind, target, tenantId, environment);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
            llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
        }
        if ((llmIntent == null || !llmIntent.resolved()) && consultativeDomainQuestion) {
            operationKind = "explore";
            artifactKind = "api_catalog";
            changeKind = "answer_api_catalog_question";
        } else if (llmIntent != null && llmIntent.resolved()) {
            operationKind = valueOrUnknown(llmIntent.operationKind());
            artifactKind = valueOrUnknown(llmIntent.artifactKind());
            changeKind = valueOrUnknown(llmIntent.changeKind());
            if (consultativePlatformCapabilityQuestion) {
                operationKind = "explain";
                artifactKind = "component";
                changeKind = platformCapabilityChangeKind(prompt);
            }
            if (consultativeDomainQuestion) {
                operationKind = "explore";
                artifactKind = "api_catalog";
                changeKind = "answer_api_catalog_question";
            }
            changeKind = normalizeTargetlessCreationChangeKind(prompt, operationKind, artifactKind, changeKind, target);
            boolean llmCandidateBundleHasExplicitSource = hasExplicitSourceCandidate(llmCandidateOptions);
            String llmResourceSearchQuery = llmCandidateBundleHasExplicitSource
                    ? ""
                    : consultativePlatformCapabilityQuestion
                    && (!isDomainDataCatalogQuestion(prompt) || isConsultativeFormPolicyQuestion(prompt))
                    ? ""
                    : consultativeResourceSearchQuery(llmIntent).trim();
            if (!llmResourceSearchQuery.isBlank()) {
                List<AgenticAuthoringCandidate> refinedCandidates = new ArrayList<>(candidates);
                refinedCandidates.addAll(discoverCandidates(
                        normalize(llmResourceSearchQuery),
                        artifactKind,
                        target,
                        tenantId,
                        environment));
                refinedCandidates.addAll(contextHintCandidates(request));
                candidates = deduplicateCandidates(refinedCandidates);
                candidates = groundCandidates(
                        normalize(llmResourceSearchQuery),
                        candidates,
                        tenantId,
                        environment);
                llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                    llmCandidateOptions = candidatesForLlmIntent(prompt, candidates);
                }
                AgenticAuthoringLlmIntentResolution refinedLlmIntent = resolveLlmIntentAfterCandidateRefinement(
                        shouldResolveLlmIntent,
                        request,
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
                    if (consultativePlatformCapabilityQuestion) {
                        operationKind = "explain";
                        artifactKind = "component";
                        changeKind = platformCapabilityChangeKind(prompt);
                    }
                    if (consultativeDomainQuestion) {
                        operationKind = "explore";
                        artifactKind = "api_catalog";
                        changeKind = "answer_api_catalog_question";
                    }
                    changeKind = normalizeTargetlessCreationChangeKind(
                            prompt,
                            operationKind,
                            artifactKind,
                            changeKind,
                            target);
                }
            } else if (candidates.isEmpty()) {
                candidates = discoverCandidates(prompt, artifactKind, target, tenantId, environment);
            }
        } else if (llmIntent != null) {
            operationKind = fallbackResolution.operationKind();
            artifactKind = fallbackResolution.artifactKind();
            changeKind = fallbackResolution.changeKind();
            deterministicFallbackApplied = true;
            if (candidates.isEmpty()
                    || isBroadArtifactDiscoveryOnly(candidates)) {
                candidates = discoverCandidates(prompt, artifactKind, target, tenantId, environment);
                candidates = withContextHintCandidates(request, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates, tenantId, environment);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
            }
        } else if (shouldResolveLlmIntent && !"unknown".equals(artifactKind)) {
            deterministicFallbackApplied = true;
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
                operationKind = materializablePageCompositionOperation(fallbackResolution.operationKind(), prompt);
            }
            changeKind = materializablePageCompositionChangeKind(changeKind, operationKind, prompt);
        } else if (explicitLocalTargetedComposition) {
            artifactKind = explicitLocalTargetedArtifactKind(request, prompt, artifactKind);
            if (!isMaterializablePageCompositionOperation(operationKind)) {
                operationKind = materializablePageCompositionOperation(fallbackResolution.operationKind(), prompt);
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
        if (currentChartTypeModification.isPresent()) {
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
        if (isDataSourceRefinement(semanticRefinement) || dataSourceReplacementPrompt) {
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
        AgenticAuthoringCandidate selectedCandidate = explicitLocalUiComposition
                ? null
                : selectCandidate(candidates, target, operationKind, artifactKind, prompt);
        selectedCandidate = explicitLocalUiComposition
                ? null
                : selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition
                ? null
                : selectLlmCandidate(llmIntent, candidates, selectedCandidate, artifactKind, prompt);
        boolean llmResourceSelectionOverriddenByPromptAlignment = !explicitLocalUiComposition
                && llmResourceSelectionOverriddenByPromptAlignment(
                        llmIntent,
                        candidates,
                        selectedCandidate,
                        artifactKind,
                        prompt);
        selectedCandidate = explicitLocalUiComposition
                ? null
                : selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = explicitLocalUiComposition
                ? null
                : selectFormWriteCandidate(artifactKind, candidates, selectedCandidate, prompt);
        selectedCandidate = explicitLocalUiComposition
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
        if (currentChartTypeModification.isPresent()) {
            operationKind = "modify";
            artifactKind = "chart";
            changeKind = currentChartTypeModification.get();
        } else if (!hasPageWidgets(request.currentPage())
                && shouldExposeAsChartArtifact(prompt, operationKind, artifactKind, changeKind, llmIntent)) {
            artifactKind = "chart";
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
        if (selectedCandidate == null && !explicitLocalUiComposition && !apiCatalogWeakLexicalSelectionDeferred) {
            selectedCandidate = selectCandidate(candidates, target, operationKind, artifactKind, prompt);
            if (selectedCandidate == null) {
                selectedCandidate = targetCandidate(target);
            }
            if (selectedCandidate != null) {
                candidates = withPriorityCandidate(candidates, selectedCandidate);
            }
        }
        if (!explicitLocalUiComposition
                && isExplicitGovernedBusinessRulePrompt(prompt)
                && candidates != null
                && !candidates.isEmpty()) {
            AgenticAuthoringCandidate promptAlignedGovernedRuleCandidate =
                    promptAlignedGovernedRuleCandidate(prompt, candidates);
            if (promptAlignedGovernedRuleCandidate != null) {
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
        if (selectedCandidate != null
                && !turn.answeredPendingClarification()
                && startsWithConfirmation(rawPrompt)) {
            selectedCandidate = null;
        }
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                changeKind,
                target,
                selectedCandidate,
                candidates);
        gate = withPromptSpecificGateMessages(gate, rawPrompt, prompt, operationKind, artifactKind, selectedCandidate, turn);
        gate = withSharedRuleAuthoringGate(gate, request, prompt, selectedCandidate, llmRequiresGovernedAuthoring);
        gate = withExplicitLocalUiCompositionGate(gate, explicitLocalUiComposition, explicitLocalTargetedComposition);
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
                && llmIntent.resolved()
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
        if (isConsultativePlatformCapabilityQuestion(prompt)
                && shouldUsePlatformGuidanceMessage(prompt, assistantMessage)) {
            assistantMessage = platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("api_catalog".equals(artifactKind)
                && isConsultativeDomainQuestion(prompt)
                && shouldUseApiCatalogGuidanceMessage(assistantMessage)) {
            String sanitizedApiCatalogMessage = sanitizeApiCatalogConsultativeLanguage(
                    assistantMessage,
                    selectedCandidate,
                    candidates);
            assistantMessage = shouldUseApiCatalogGuidanceMessage(sanitizedApiCatalogMessage)
                    ? apiCatalogAssistantMessage(prompt, selectedCandidate, candidates)
                    : sanitizedApiCatalogMessage;
        }
        if (shouldHideTechnicalAddresses(request, operationKind, artifactKind, llmRequiresGovernedAuthoring)) {
            assistantMessage = sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        }
        assistantMessage = conciseAssistantMessage(assistantMessage);
        boolean promotedAssistantChoiceToClarification = shouldPromoteAssistantChoiceToClarification(
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
        List<AgenticAuthoringQuickReply> llmAuthoredQuickReplies = governedLlmQuickReplies(
                llmIntent,
                selectedCandidate);
        List<AgenticAuthoringQuickReply> fallbackQuickReplies = quickReplies(
                effectivePrompt,
                prompt,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate,
                gate,
                questions,
                candidates,
                answeredBareDomainClarification);
        boolean llmAuthoredQuickRepliesUsed = shouldUseLlmAuthoredQuickReplies(
                llmAuthoredQuickReplies,
                gate,
                contextHintSelectionApplied(contextHintCandidate, selectedCandidate, gate),
                promotedAssistantChoiceToClarification,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate);
        List<AgenticAuthoringQuickReply> quickReplies = llmAuthoredQuickRepliesUsed
                ? llmAuthoredQuickReplies
                : fallbackQuickReplies;
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
        if (shouldHideTechnicalAddresses(request, operationKind, artifactKind, llmRequiresGovernedAuthoring)) {
            quickReplies = sanitizeQuickReplies(quickReplies, selectedCandidate, candidates);
        }
        boolean governedDeterministicResolution = isGovernedDeterministicResolution(
                deterministicFallbackApplied,
                gate,
                operationKind,
                artifactKind,
                changeKind,
                selectedCandidate);
        boolean keywordFallbackAppliedForGovernance =
                deterministicFallbackApplied && !governedDeterministicResolution;
        List<String> warnings = warnings(llmIntent);
        if (llmTreatsPendingAsNewInstruction) {
            warnings = withWarning(warnings, "llm-follow-up-kind-new-instruction");
        }
        if (llmSecondPassUsed) {
            warnings = withWarning(warnings, "llm-intent-resolution-second-pass-used");
        }
        if (llmResourceSelectionOverriddenByPromptAlignment) {
            warnings = withWarning(warnings, "llm-resource-selection-overridden-by-prompt-alignment");
        }
        if (keywordFallbackAppliedForGovernance) {
            warnings = withWarning(warnings, "keyword-fallback-applied");
            warnings = withWarning(warnings, "keyword-fallback-fail-safe-applied");
        } else if (governedDeterministicResolution) {
            warnings = withWarning(warnings, "governed-deterministic-resolution-applied");
        }
        if (governedResourceConfirmation) {
            warnings = withWarning(warnings, "governed-resource-confirmation-deterministic");
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
            warnings = withWarning(warnings, "pre-llm-governed-resource-choice-applied");
        }
        if (apiCatalogWeakLexicalSelectionDeferred) {
            warnings = withWarning(warnings, "api-catalog-weak-lexical-selection-deferred");
        }
        if (promotedAssistantChoiceToClarification) {
            warnings = withWarning(warnings, "llm-assistant-choice-promoted-to-quick-replies");
        }
        if (llmAuthoredQuickRepliesUsed) {
            warnings = withWarning(warnings, "llm-authored-quick-replies-used");
        } else if (!fallbackQuickReplies.isEmpty()) {
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
        AgenticAuthoringVisualizationDecision visualizationDecision =
                governedVisualizationDecision(
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
        AgenticAuthoringSemanticDecision semanticDecision = AgenticAuthoringSemanticDecision.from(
                operationKind,
                semanticDecisionArtifactKind,
                changeKind,
                selectedCandidate,
                candidates,
                visualizationDecision,
                warnings,
                null,
                llmIntent,
                activeDecision,
                request.sessionId(),
                request.clientTurnId(),
                semanticRawPrompt(request, rawPrompt),
                activeDecisionObjective(activeDecision, semanticRawPrompt(request, rawPrompt)),
                decisionRationale(decisionMemoryRefinement, selectedCandidate),
                semanticRefinement);
        llmDiagnostics = withResolutionTelemetry(
                llmDiagnostics,
                shouldResolveLlmIntent,
                llmIntent,
                keywordFallbackAppliedForGovernance,
                semanticPolicyRefinedVisualProjection,
                selectedCandidate,
                candidates);
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
                candidates,
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

    private AgenticAuthoringSemanticRefinement semanticRefinement(
            String prompt,
            AgenticAuthoringSemanticDecision activeDecision,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if (activeDecision == null || activeDecision.selectedResource() == null) {
            return AgenticAuthoringSemanticRefinement.none();
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
        return "dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && isBroadAnalyticalCanvasPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate));
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
        return llmIntentResolverService.resolve(
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidates,
                        componentCapabilities,
                        tenantId,
                        userId,
                        environment)
                .orElse(null);
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
        return next.orElse(previousLlmIntent);
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
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
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
        diagnostics.set("request", llmIntentResolverService.diagnosticSnapshot(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities));
        return diagnostics;
    }

    private boolean includeLlmDiagnostics(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        return contextHints != null && contextHints.path("includeLlmDiagnostics").asBoolean(false);
    }

    private String effectiveSelectedWidgetKey(AgenticAuthoringIntentResolutionRequest request) {
        String selectedWidgetKey = request == null ? "" : valueOrDefault(request.selectedWidgetKey(), "");
        if (!selectedWidgetKey.isBlank()) {
            return selectedWidgetKey;
        }
        JsonNode contextHints = request == null ? null : request.contextHints();
        String contextualSelection = valueOrDefault(jsonText(contextHints, "selectedWidgetKey"), "");
        if (!contextualSelection.isBlank()) {
            return contextualSelection;
        }
        return valueOrDefault(jsonText(contextHints, "targetWidgetKey"), "");
    }

    private boolean isContextualPreviewAction(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String source = jsonText(contextHints, "source");
        String kind = jsonText(contextHints, "kind");
        String changeKind = jsonText(contextHints, "changeKind");
        return "component-capability-catalog".equals(source)
                || "contextual-preview-action".equals(kind)
                || (!valueOrDefault(changeKind, "").isBlank()
                && !valueOrDefault(jsonText(contextHints, "targetComponentId"), "").isBlank());
    }

    private AgenticAuthoringTarget contextualPreviewTarget(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget currentTarget,
            AgenticAuthoringCandidate contextHintCandidate) {
        if (!isContextualPreviewAction(request)) {
            return currentTarget;
        }
        String componentId = valueOrDefault(jsonText(request.contextHints(), "targetComponentId"), "");
        if (componentId.isBlank()) {
            componentId = valueOrDefault(jsonText(request.contextHints(), "selectedComponentId"), "");
        }
        if (componentId.isBlank()) {
            componentId = currentTarget == null ? "" : valueOrDefault(currentTarget.componentId(), "");
        }
        if (componentId.isBlank()) {
            return currentTarget;
        }
        String widgetKey = valueOrDefault(jsonText(request.contextHints(), "targetWidgetKey"), "");
        if (widgetKey.isBlank()) {
            widgetKey = valueOrDefault(jsonText(request.contextHints(), "selectedWidgetKey"), "");
        }
        if (widgetKey.isBlank() && currentTarget != null) {
            widgetKey = valueOrDefault(currentTarget.widgetKey(), "");
        }
        boolean hasHintedWidgetKey = !widgetKey.isBlank()
                && !widgetKey.equals(valueOrDefault(currentTarget == null ? "" : currentTarget.widgetKey(), ""));
        if (!hasHintedWidgetKey
                && currentTarget != null
                && !valueOrDefault(currentTarget.widgetKey(), "").isBlank()
                && componentId.equals(valueOrDefault(currentTarget.componentId(), componentId))) {
            return currentTarget;
        }
        String resourcePath = firstNonBlank(
                currentTarget == null ? "" : currentTarget.resourcePath(),
                contextHintCandidate == null ? "" : contextHintCandidate.resourcePath(),
                jsonText(request.contextHints(), "resourcePath"));
        String schemaUrl = firstNonBlank(
                currentTarget == null ? "" : currentTarget.schemaUrl(),
                contextHintCandidate == null ? "" : contextHintCandidate.schemaUrl(),
                jsonText(request.contextHints(), "schemaUrl"));
        String submitUrl = firstNonBlank(
                currentTarget == null ? "" : currentTarget.submitUrl(),
                contextHintCandidate == null ? "" : contextHintCandidate.submitUrl(),
                jsonText(request.contextHints(), "submitUrl"),
                resourcePath);
        String submitMethod = firstNonBlank(
                currentTarget == null ? "" : currentTarget.submitMethod(),
                contextHintCandidate == null ? "" : contextHintCandidate.submitMethod(),
                contextHintCandidate == null ? "" : contextHintCandidate.operation(),
                jsonText(request.contextHints(), "submitMethod"),
                jsonText(request.contextHints(), "operation"),
                "get");
        return new AgenticAuthoringTarget(
                widgetKey,
                componentId,
                resourcePath,
                schemaUrl,
                submitUrl,
                submitMethod);
    }

    private String contextualPreviewOperationKind(AgenticAuthoringIntentResolutionRequest request) {
        String operationKind = valueOrDefault(jsonText(request == null ? null : request.contextHints(), "operationKind"), "");
        if (!operationKind.isBlank() && !"explore".equals(operationKind) && !"explain".equals(operationKind)) {
            return operationKind;
        }
        return "modify";
    }

    private String contextualPreviewArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String fallbackArtifactKind) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String hintedArtifact = valueOrDefault(jsonText(contextHints, "artifactKind"), "");
        if (!hintedArtifact.isBlank() && !"unknown".equals(hintedArtifact)) {
            return hintedArtifact;
        }
        String componentId = valueOrDefault(jsonText(contextHints, "targetComponentId"), "");
        if (componentId.contains("chart")) {
            return "chart";
        }
        if (componentId.contains("table")) {
            return "table";
        }
        if (componentId.contains("form")) {
            return "form";
        }
        return valueOrDefault(fallbackArtifactKind, "dashboard");
    }

    private boolean isGovernedResourceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringConversationTurn turn) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String prompt = normalize(String.join(" ",
                request == null ? "" : valueOrDefault(request.userPrompt(), ""),
                turn == null ? "" : valueOrDefault(turn.effectivePrompt(), "")));
        String hintedResourcePath = contextHints != null && contextHints.isObject()
                ? jsonText(contextHints, "resourcePath")
                : "";
        if (hintedResourcePath.isBlank() && explicitResourcePath(prompt).isBlank()) {
            return false;
        }
        return prompt.contains("confirmed:")
                || prompt.contains("confirmado:")
                || prompt.contains("confirmada:")
                || prompt.contains("confirmo")
                || containsAny(
                prompt,
                "gerar previa",
                "gerar pre visualizacao");
    }

    private AgenticAuthoringCandidate contextHintCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        String resourcePath = jsonText(contextHints, "resourcePath");
        JsonNode domainCatalogHint = contextHints.path("domainCatalog");
        String domainCatalogResourceKey = normalizeDomainCatalogResourceKey(jsonText(domainCatalogHint, "resourceKey"));
        boolean domainCatalogResourceHint = false;
        boolean derivedFromDomainCatalogHint = false;
        if (resourcePath.isBlank()) {
            resourcePath = resourcePathFromDomainCatalogResourceKey(domainCatalogResourceKey);
            domainCatalogResourceHint = !resourcePath.isBlank();
            derivedFromDomainCatalogHint = domainCatalogResourceHint;
        } else {
            domainCatalogResourceHint = !domainCatalogResourceKey.isBlank()
                    && resourcePath.equals(resourcePathFromDomainCatalogResourceKey(domainCatalogResourceKey));
        }
        if (resourcePath.isBlank()) {
            return null;
        }
        String operation = valueOrDefault(jsonText(contextHints, "operation"), jsonText(contextHints, "submitMethod"));
        if (operation.isBlank() && domainCatalogHint != null && domainCatalogHint.isObject()) {
            operation = valueOrDefault(jsonText(domainCatalogHint, "operation"), jsonText(domainCatalogHint, "submitMethod"));
        }
        if (operation.isBlank()) {
            operation = "form".equals(artifactKind)
                    || "table".equals(artifactKind)
                    || "dashboard".equals(artifactKind)
                    || "page".equals(artifactKind)
                    ? "post"
                    : "get";
        }
        String submitUrl = canonicalContextSubmitUrl(
                resourcePath,
                valueOrDefault(jsonText(contextHints, "submitUrl"), resourcePath),
                operation,
                artifactKind);
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(submitUrl);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst();
        if (existing.isPresent()) {
            return domainCatalogResourceHint && verifiedDomainCatalogResourceKey(domainCatalogResourceKey, tenantId, environment)
                    ? verifiedDomainCatalogContextCandidate(
                            domainCatalogResourceKey,
                            existing.get(),
                            artifactKind,
                            tenantId,
                            environment)
                    : withEvidence(existing.get(), "quick-reply-context");
        }
        if (derivedFromDomainCatalogHint) {
            AgenticAuthoringCandidate verifiedApiCandidate = verifiedApiMetadataCandidate(
                    resourcePath,
                    artifactKind,
                    tenantId,
                    environment);
            return verifiedDomainCatalogContextCandidate(
                    domainCatalogResourceKey,
                    verifiedApiCandidate,
                    artifactKind,
                    tenantId,
                    environment);
        }
        return candidate(
                normalizePath(resourcePath),
                submitUrl,
                operation,
                1.0d,
                "resource selected from assistant quick reply context",
                "quick-reply-context");
    }

    private AgenticAuthoringCandidate verifiedDomainCatalogContextCandidate(
            String resourceKey,
            AgenticAuthoringCandidate candidate,
            String artifactKind,
            String tenantId,
            String environment) {
        if (candidate == null
                || !verifiedDomainCatalogResourceKey(resourceKey, tenantId, environment)) {
            return null;
        }
        return withDomainCatalogContextEvidence(candidate, resourceKey);
    }

    private boolean verifiedDomainCatalogResourceKey(String resourceKey, String tenantId, String environment) {
        return domainCatalogCandidateEnhancer != null
                && domainCatalogCandidateEnhancer.hasResourceKey(resourceKey, tenantId, environment);
    }

    private AgenticAuthoringCandidate verifiedApiMetadataCandidate(
            String resourcePath,
            String artifactKind,
            String tenantId,
            String environment) {
        String normalizedResourcePath = normalizePath(resourcePath);
        if (normalizedResourcePath.isBlank() || apiMetadataCandidateCatalog == null) {
            return null;
        }
        String query = normalize(normalizedResourcePath
                .replaceFirst("^/api/", "")
                .replace('/', ' ')
                .replace('-', ' '));
        return apiMetadataCandidateCatalog.discover(query, artifactKind, tenantId, environment, null).stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizePath(candidate.submitUrl()).startsWith(normalizedResourcePath + "/"))
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringCandidate withDomainCatalogContextEvidence(
            AgenticAuthoringCandidate candidate,
            String resourceKey) {
        AgenticAuthoringCandidate grounded = withEvidence(
                withEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING),
                "domain-catalog-context");
        if (grounded == null
                || (grounded.evidenceBundle() != null
                && AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG
                .equals(grounded.evidenceBundle().retrievalSource()))) {
            return grounded;
        }
        return new AgenticAuthoringCandidate(
                grounded.resourcePath(),
                grounded.operation(),
                grounded.schemaUrl(),
                grounded.submitUrl(),
                grounded.submitMethod(),
                grounded.score(),
                grounded.reason(),
                grounded.evidence(),
                AgenticAuthoringEvidenceBundle.of(
                        AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG,
                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG,
                                "domain_catalog_context_hint",
                                valueOrDefault(resourceKey, grounded.resourcePath()),
                                "Governed domain catalog context hint selected this resource.",
                                0.96d,
                                List.of(valueOrDefault(resourceKey, grounded.resourcePath())),
                                "",
                                "",
                                ""))));
    }

    private AgenticAuthoringCandidate withEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        if (candidate == null || evidence == null || evidence.isBlank()) {
            return candidate;
        }
        List<String> currentEvidence = candidate.evidence() == null ? List.of() : candidate.evidence();
        if (currentEvidence.contains(evidence)) {
            return candidate;
        }
        List<String> mergedEvidence = new ArrayList<>(currentEvidence);
        mergedEvidence.add(evidence);
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                candidate.operation(),
                candidate.schemaUrl(),
                candidate.submitUrl(),
                candidate.submitMethod(),
                candidate.score(),
                candidate.reason(),
                List.copyOf(mergedEvidence),
                candidate.evidenceBundle());
    }

    private List<AgenticAuthoringCandidate> withContextHintCandidates(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> contextCandidates = contextHintCandidates(request);
        if (contextCandidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> merged = new ArrayList<>(candidates == null ? List.of() : candidates);
        merged.addAll(contextCandidates);
        return deduplicateCandidates(merged);
    }

    private List<AgenticAuthoringCandidate> contextHintCandidates(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        JsonNode candidateNodes = contextHints == null ? null : contextHints.path("resourceDiscovery").path("candidates");
        if (candidateNodes == null || !candidateNodes.isArray() || candidateNodes.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        for (JsonNode candidateNode : candidateNodes) {
            if (candidateNode == null || !candidateNode.isObject()) {
                continue;
            }
            String resourcePath = jsonText(candidateNode, "resourcePath");
            if (resourcePath.isBlank()) {
                continue;
            }
            String operation = valueOrDefault(jsonText(candidateNode, "operation"), jsonText(candidateNode, "submitMethod"));
            if (operation.isBlank()) {
                operation = "post";
            }
            String submitMethod = valueOrDefault(jsonText(candidateNode, "submitMethod"), operation);
            String submitUrl = canonicalContextSubmitUrl(
                    resourcePath,
                    valueOrDefault(jsonText(candidateNode, "submitUrl"), resourcePath),
                    submitMethod,
                    artifactKindFromContextCandidate(candidateNode, request));
            String schemaUrl = canonicalContextSchemaUrl(
                    jsonText(candidateNode, "schemaUrl"),
                    submitUrl,
                    submitMethod);
            double score = candidateNode.path("score").isNumber() ? candidateNode.path("score").asDouble() : 1.0d;
            String reason = valueOrDefault(jsonText(candidateNode, "reason"), "resource discovered by backend tool");
            List<String> evidence = new ArrayList<>();
            JsonNode evidenceNode = candidateNode.path("evidence");
            if (evidenceNode.isArray()) {
                evidenceNode.forEach(item -> {
                    if (item != null && item.isTextual() && !item.asText().isBlank()) {
                        evidence.add(item.asText());
                    }
                });
            }
            evidence.add("tool-search-api-resources");
            AgenticAuthoringEvidenceBundle evidenceBundle = evidenceBundle(candidateNode.path("evidenceBundle"));
            candidates.add(new AgenticAuthoringCandidate(
                    normalizePath(resourcePath),
                    operation,
                    schemaUrl,
                    submitUrl,
                    submitMethod,
                    score,
                    reason,
                    List.copyOf(evidence),
                    evidenceBundle));
        }
        return deduplicateCandidates(candidates);
    }

    private AgenticAuthoringEvidenceBundle evidenceBundle(JsonNode evidenceBundleNode) {
        if (evidenceBundleNode == null || evidenceBundleNode.isMissingNode() || evidenceBundleNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.convertValue(evidenceBundleNode, AgenticAuthoringEvidenceBundle.class);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String artifactKindFromContextCandidate(
            JsonNode candidateNode,
            AgenticAuthoringIntentResolutionRequest request) {
        String artifactKind = valueOrDefault(jsonText(candidateNode, "artifactKind"), "");
        if (!artifactKind.isBlank()) {
            return artifactKind;
        }
        JsonNode contextHints = request == null ? null : request.contextHints();
        return valueOrUnknown(jsonText(contextHints, "artifactKind"));
    }

    private String normalizeDomainCatalogResourceKey(String resourceKey) {
        String normalized = valueOrDefault(resourceKey, "").trim();
        if (normalized.startsWith("/api/")) {
            normalized = normalized.substring("/api/".length()).replace('/', '.');
        }
        return normalized.replaceAll("^\\.+|\\.+$", "");
    }

    private String resourcePathFromDomainCatalogResourceKey(String resourceKey) {
        String normalizedResourceKey = normalizeDomainCatalogResourceKey(resourceKey);
        if (normalizedResourceKey.isBlank()) {
            return "";
        }
        return normalizePath("/api/" + normalizedResourceKey.replace('.', '/'));
    }

    private String canonicalContextSubmitUrl(
            String resourcePath,
            String submitUrl,
            String operation,
            String artifactKind) {
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(valueOrDefault(submitUrl, normalizedResourcePath));
        String normalizedArtifactKind = valueOrUnknown(artifactKind);
        if (List.of("page", "table", "unknown").contains(normalizedArtifactKind)
                && "post".equalsIgnoreCase(valueOrDefault(operation, "post"))) {
            if (normalizedSubmitUrl.endsWith("/filter")) {
                return normalizedSubmitUrl + "/cursor";
            }
            if (normalizedSubmitUrl.equals(normalizedResourcePath)) {
                return normalizedSubmitUrl + "/filter/cursor";
            }
        }
        if ("dashboard".equals(normalizedArtifactKind)
                && normalizedSubmitUrl.equals(normalizedResourcePath)
                && !normalizedSubmitUrl.contains("/{")) {
            return normalizedSubmitUrl + "/stats/group-by";
        }
        return normalizedSubmitUrl;
    }

    private String canonicalContextSchemaUrl(String schemaUrl, String submitUrl, String operation) {
        String normalizedSubmitUrl = normalizePath(submitUrl);
        if (!valueOrDefault(schemaUrl, "").isBlank()
                && schemaUrl.contains("path=" + normalizedSubmitUrl + "&operation=" + valueOrDefault(operation, "post"))) {
            return schemaUrl;
        }
        String normalizedOperation = valueOrDefault(operation, "post").toLowerCase(Locale.ROOT);
        String schemaType = "get".equalsIgnoreCase(normalizedOperation) || isReadProjectionOperation(normalizedSubmitUrl, normalizedOperation)
                ? "response"
                : "request";
        return "/schemas/filtered?path=" + normalizedSubmitUrl + "&operation=" + normalizedOperation
                + "&schemaType=" + schemaType;
    }

    private List<AgenticAuthoringCandidate> withPriorityCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate priorityCandidate) {
        if (priorityCandidate == null) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> next = new ArrayList<>();
        next.add(priorityCandidate);
        if (candidates != null) {
            next.addAll(candidates);
        }
        return deduplicateCandidates(next);
    }

    private AgenticAuthoringCandidate selectContextHintCandidate(
            AgenticAuthoringCandidate contextHintCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
        if (contextHintCandidate == null) {
            return fallback;
        }
        String normalizedResourcePath = normalizePath(contextHintCandidate.resourcePath());
        String normalizedSubmitUrl = normalizePath(contextHintCandidate.submitUrl());
        return (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst()
                .orElse(contextHintCandidate);
    }

    private AgenticAuthoringCandidate selectLlmCandidate(
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String artifactKind,
            String prompt) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        AgenticAuthoringCandidate llmCandidate = candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(fallback);
        AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
        if (promptAlignedCandidate != null
                && llmCandidate != null
                && !sameCandidate(promptAlignedCandidate, llmCandidate)
                && !promptMentionsSpecificCandidateToken(prompt, llmCandidate)) {
            return promptAlignedCandidate;
        }
        return llmCandidate;
    }

    private boolean llmResourceSelectionOverriddenByPromptAlignment(
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate selectedCandidate,
            String artifactKind,
            String prompt) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        AgenticAuthoringCandidate llmCandidate = candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(null);
        if (llmCandidate == null
                || selectedCandidate == null
                || sameCandidate(llmCandidate, selectedCandidate)) {
            return false;
        }
        return promptAlignedBusinessCandidate(prompt, candidates) != null
                && !promptMentionsSpecificCandidateToken(prompt, llmCandidate)
                && ("dashboard".equals(artifactKind) || "page".equals(artifactKind) || "table".equals(artifactKind));
    }

    private AgenticAuthoringCandidate promptAlignedBusinessCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        promptCandidateAlignmentScore(prompt, candidate)))
                .filter(alignment -> alignment.score() > 0)
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .map(CandidatePromptAlignment::candidate)
                .orElse(null);
    }

    private boolean sameCandidate(AgenticAuthoringCandidate left, AgenticAuthoringCandidate right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizePath(left.resourcePath()).equals(normalizePath(right.resourcePath()))
                || normalizePath(left.submitUrl()).equals(normalizePath(right.submitUrl()));
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
        String schemaUrl = canonicalContextSchemaUrl(jsonText(binding, "schemaUrl"), submitUrl, submitMethod);
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
        String resourcePath = explicitResourcePath(prompt);
        if (resourcePath.isBlank()) {
            return null;
        }
        String submitUrl = explicitSubmitUrl(prompt, resourcePath);
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath())))
                .findFirst();
        if (existing.isPresent()) {
            AgenticAuthoringCandidate candidate = existing.get();
            if (submitUrl.isBlank() || normalizePath(submitUrl).equals(normalizePath(candidate.submitUrl()))) {
                return candidate;
            }
            AgenticAuthoringCandidate explicitSubmitCandidate = candidate(
                    candidate.resourcePath(),
                    normalizePath(submitUrl),
                    candidate.operation(),
                    candidate.score(),
                    candidate.reason(),
                    "explicit-submit-url");
            return new AgenticAuthoringCandidate(
                    explicitSubmitCandidate.resourcePath(),
                    explicitSubmitCandidate.operation(),
                    explicitSubmitCandidate.schemaUrl(),
                    explicitSubmitCandidate.submitUrl(),
                    explicitSubmitCandidate.submitMethod(),
                    explicitSubmitCandidate.score(),
                    explicitSubmitCandidate.reason(),
                    Stream.concat(
                                    candidate.evidence().stream(),
                                    Stream.of("explicit-submit-url"))
                            .distinct()
                            .toList(),
                    candidate.evidenceBundle());
        }
        return candidate(
                resourcePath,
                submitUrl.isBlank() ? "post" : normalizePath(submitUrl),
                "post",
                0.99d,
                "resource path explicitly provided by the user prompt",
                "explicit-resource-path");
    }

    private String explicitResourcePath(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(/api/[a-z0-9][a-z0-9_./-]*)")
                .matcher(prompt);
        return matcher.find() ? matcher.group(1).replaceAll("/+$", "") : "";
    }

    private String explicitSubmitUrl(String prompt, String resourcePath) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\b(?:via|submitUrl|submit url)\\s+(/api/[a-z0-9][a-z0-9_./-]*)")
                .matcher(prompt);
        if (!matcher.find()) {
            return "";
        }
        String submitUrl = normalizePath(matcher.group(1));
        String normalizedResourcePath = normalizePath(resourcePath);
        return !normalizedResourcePath.isBlank() && submitUrl.startsWith(normalizedResourcePath + "/")
                ? submitUrl
                : "";
    }

    private AgenticAuthoringCandidate selectFormWriteCandidate(
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String prompt) {
        if (!"form".equals(artifactKind)
                || candidates == null
                || candidates.isEmpty()) {
            return fallback;
        }
        if (fallback != null && isWriteCandidate(fallback)) {
            return fallback;
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            return fallback;
        }
        if (fallback == null
                && candidates.size() > 1
                && !hasStrongPromptAlignment(prompt, candidates)) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(this::isWriteCandidate)
                .findFirst()
                .orElse(fallback);
    }

    private String semanticRawPrompt(AgenticAuthoringIntentResolutionRequest request, String rawPrompt) {
        List<AgenticAuthoringConversationMessage> messages =
                request == null || request.conversationMessages() == null
                        ? List.of()
                        : request.conversationMessages();
        String userConversation = messages.stream()
                .filter(message -> message != null && "user".equalsIgnoreCase(valueOrDefault(message.role(), "")))
                .map(AgenticAuthoringConversationMessage::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        return String.join(" ", valueOrDefault(rawPrompt, ""), userConversation).trim();
    }

    private boolean isWriteCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String operation = valueOrDefault(candidate.operation(), "");
        String schemaUrl = valueOrDefault(candidate.schemaUrl(), "");
        return "post".equalsIgnoreCase(operation) || schemaUrl.contains("operation=post");
    }

    private List<String> warnings(AgenticAuthoringLlmIntentResolution llmIntent) {
        List<String> warnings = new ArrayList<>();
        warnings.add("metadata-probe-not-run");
        if (llmIntent == null) {
            warnings.add("llm-intent-resolution-fallback-deterministic");
        } else {
            warnings.add("llm-intent-resolution-used");
            if (!llmIntent.resolved()) {
                warnings.add("llm-intent-resolution-unresolved-fallback-deterministic");
            }
            if (llmIntent.warnings() != null) {
                warnings.addAll(llmIntent.warnings());
            }
        }
        return List.copyOf(warnings);
    }

    private List<String> sanitizeConsultativeWarnings(
            List<String> warnings,
            String operationKind,
            String artifactKind,
            String changeKind) {
        boolean consultativeComponent = "component".equals(artifactKind)
                && ("answer_component_catalog_question".equals(changeKind)
                || "answer_component_capability_question".equals(changeKind));
        boolean consultativeApiCatalog = "api_catalog".equals(artifactKind);
        if ((!consultativeComponent && !consultativeApiCatalog)
                || (!"explain".equals(operationKind) && !"explore".equals(operationKind))) {
            return warnings == null ? List.of() : warnings;
        }
        return (warnings == null ? List.<String>of() : warnings).stream()
                .filter(this::isDiagnosticWarningCode)
                .distinct()
                .toList();
    }

    private boolean isDiagnosticWarningCode(String warning) {
        return warning != null && warning.matches("[a-z0-9._-]+");
    }

    private List<AgenticAuthoringCandidate> filterConsultativeApiCatalogCandidates(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (!"api_catalog".equals(artifactKind)
                || !isApiCatalogResourceListPrompt(prompt)
                || candidates == null
                || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                        && candidate.score() >= 0.80d);
        if (!hasGroundedCandidate) {
            return candidates;
        }
        List<AgenticAuthoringCandidate> filtered = candidates.stream()
                .filter(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                        || hasEvidence(candidate, "lexical-fallback")
                        || candidate.score() >= 0.70d)
                .toList();
        return filtered.isEmpty() ? candidates : deduplicateCandidates(filtered);
    }

    private List<String> llmClarificationQuestions(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringGateResult gate,
            List<String> fallbackQuestions) {
        if (llmIntent == null
                || !llmIntent.resolved()
                || gate == null
                || !"clarification_required".equals(gate.status())
                || llmIntent.clarificationQuestions() == null
                || llmIntent.clarificationQuestions().isEmpty()) {
            return fallbackQuestions;
        }
        List<String> questions = llmIntent.clarificationQuestions().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(question -> !question.isBlank())
                .distinct()
                .toList();
        return questions.isEmpty() ? fallbackQuestions : questions;
    }

    private List<String> withWarning(List<String> warnings, String warning) {
        List<String> next = new ArrayList<>(warnings == null ? List.of() : warnings);
        next.add(warning);
        return List.copyOf(next);
    }

    private List<String> withCandidateProvenanceWarnings(
            List<String> warnings,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> next = new ArrayList<>(warnings == null ? List.of() : warnings);
        if (hasEvidence(selectedCandidate, "domain-anchor")) {
            next.add("resource-selection-domain-anchor-selected");
        }
        if (hasEvidence(selectedCandidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            next.add("resource-selection-domain-catalog-grounding-selected");
        }
        if (hasEvidence(selectedCandidate, "lexical-fallback")) {
            next.add("resource-selection-lexical-fallback-selected");
        }
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "domain-anchor"))) {
            next.add("resource-selection-domain-anchor-candidates-present");
        }
        if (usableCandidates.stream().anyMatch(candidate -> hasEvidence(
                candidate,
                AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING))) {
            next.add("resource-selection-domain-catalog-grounding-candidates-present");
        }
        return next.stream().distinct().toList();
    }

    private JsonNode withResolutionTelemetry(
            JsonNode diagnostics,
            boolean llmResolutionAttempted,
            AgenticAuthoringLlmIntentResolution llmIntent,
            boolean deterministicFallbackApplied,
            boolean semanticPolicyCorrectedAnalyticalDashboardIntent,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        ObjectNode root = diagnostics != null && diagnostics.isObject()
                ? (ObjectNode) diagnostics.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode telemetry = root.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", llmResolutionAttempted);
        telemetry.put("llmResolved", llmIntent != null && llmIntent.resolved());
        telemetry.put("fallbackPolicy", "fail-safe");
        telemetry.put("keywordFallbackApplied", deterministicFallbackApplied);
        telemetry.put("semanticPolicyApplied", semanticPolicyCorrectedAnalyticalDashboardIntent);
        telemetry.put("selectedCandidateUsesDomainAnchor",
                hasEvidence(selectedCandidate, "domain-anchor") && !hasStrongGroundingEvidence(selectedCandidate));
        telemetry.put("selectedCandidateUsesDomainCatalogGrounding",
                hasEvidence(selectedCandidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
        telemetry.put("selectedCandidateUsesLexicalFallback", hasEvidence(selectedCandidate, "lexical-fallback"));
        telemetry.put("selectedCandidateUsesBroadArtifactDiscovery",
                hasEvidence(selectedCandidate, "broad-artifact-discovery"));
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        telemetry.put("candidateSetContainsDomainAnchor",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "domain-anchor")));
        telemetry.put("candidateSetContainsDomainCatalogGrounding",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)));
        telemetry.put("candidateSetContainsLexicalFallback",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "lexical-fallback")));
        telemetry.put("candidateSetContainsBroadArtifactDiscovery",
                usableCandidates.stream().anyMatch(candidate -> hasEvidence(candidate, "broad-artifact-discovery")));
        if (selectedCandidate != null) {
            telemetry.put("selectedResourcePath", valueOrDefault(selectedCandidate.resourcePath(), ""));
            telemetry.set("selectedCandidateEvidence",
                    objectMapper.valueToTree(selectedCandidate.evidence() == null ? List.of() : selectedCandidate.evidence()));
        }
        return root;
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private boolean isGovernedDeterministicResolution(
            boolean deterministicFallbackApplied,
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!deterministicFallbackApplied
                || selectedCandidate == null
                || gate == null
                || !"eligible".equals(gate.status())
                || "unknown".equals(valueOrUnknown(operationKind))
                || "unknown".equals(valueOrUnknown(artifactKind))
                || "unknown".equals(valueOrUnknown(changeKind))) {
            return false;
        }
        if (hasEvidence(selectedCandidate, "lexical-fallback")
                || hasEvidence(selectedCandidate, "weak-evidence")
                || hasEvidence(selectedCandidate, "broad-artifact-discovery")) {
            return false;
        }
        if (hasEvidence(selectedCandidate, "domain-anchor") && !hasStrongGroundingEvidence(selectedCandidate)) {
            return false;
        }
        return selectedCandidate.score() >= 0.75d
                || hasStrongGroundingEvidence(selectedCandidate)
                || hasEvidence(selectedCandidate, "semantic-retrieval")
                || hasEvidence(selectedCandidate, "tool-search-api-resources")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "explicit-resource-path");
    }

    private boolean shouldSuppressWeakUnresolvedLlmSelection(
            boolean deterministicFallbackApplied,
            AgenticAuthoringLlmIntentResolution llmIntent,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!deterministicFallbackApplied
                || llmIntent == null
                || llmIntent.resolved()
                || selectedCandidate == null) {
            return false;
        }
        if (!"unknown".equals(valueOrUnknown(operationKind))
                && !"unknown".equals(valueOrUnknown(artifactKind))) {
            return false;
        }
        return hasEvidence(selectedCandidate, "lexical-fallback")
                || hasEvidence(selectedCandidate, "weak-evidence")
                || hasEvidence(selectedCandidate, "broad-artifact-discovery");
    }

    private boolean shouldDeferWeakLexicalApiCatalogSelection(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                && isWeakLexicalCandidate(selectedCandidate)
                && !hasTrustedSelectionEvidence(selectedCandidate);
    }

    private boolean isWeakLexicalCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (hasEvidence(candidate, "lexical-fallback") || hasEvidence(candidate, "weak-evidence")) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        return bundle != null
                && "lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""));
    }

    private boolean hasTrustedSelectionEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return hasStrongGroundingEvidence(candidate)
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                || hasEvidence(candidate, "explicit-resource-path")
                || hasEvidence(candidate, "quick-reply-context")
                || hasEvidence(candidate, "current-page");
    }

    private boolean hasStrongGroundingEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null) {
            return false;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if ("lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""))) {
            return false;
        }
        double strongestEvidence = bundle.evidence().stream()
                .mapToDouble(AgenticAuthoringEvidenceBundle.Evidence::confidence)
                .max()
                .orElse(0d);
        boolean structuralEvidence = bundle.evidence().stream()
                .map(AgenticAuthoringEvidenceBundle.Evidence::kind)
                .map(kind -> valueOrDefault(kind, ""))
                .anyMatch(kind -> kind.equals("schema_grounding")
                        || kind.equals("operation_grounding")
                        || kind.equals("resource_capability_hint")
                        || kind.equals("retrieved_candidate"));
        return strongestEvidence >= 0.62d && structuralEvidence;
    }

    private boolean isLlmFollowUpKind(AgenticAuthoringLlmIntentResolution llmIntent, String expected) {
        if (llmIntent == null || llmIntent.followUpKind() == null || expected == null) {
            return false;
        }
        return expected.equals(normalize(llmIntent.followUpKind()));
    }

    private AgenticAuthoringPendingClarification pendingClarification(
            String effectivePrompt,
            AgenticAuthoringGateResult gate,
            String assistantMessage,
            List<String> questions,
            String clientTurnId,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (gate == null || !"clarification_required".equals(gate.status()) || questions == null || questions.isEmpty()) {
            return null;
        }
        String prompt = valueOrDefault(effectivePrompt, "").trim();
        if (prompt.isBlank()) {
            return null;
        }
        String message = valueOrDefault(assistantMessage, "").trim();
        if (message.isBlank()) {
            message = questions.get(0);
        }
        return new AgenticAuthoringPendingClarification(
                prompt,
                List.copyOf(questions),
                message,
                clientTurnId,
                pendingClarificationDiagnostics(attachmentSummaries));
    }

    private JsonNode pendingClarificationDiagnostics(List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (attachmentSummaries == null || attachmentSummaries.isEmpty()) {
            return null;
        }
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.set("attachmentSummaries", objectMapper.valueToTree(attachmentSummaries));
        return diagnostics;
    }

    private boolean isExplicitCreateConfirmation(String prompt) {
        return containsAny(prompt,
                "confirmed: criar",
                "confirmed: create",
                "confirmado: criar",
                "confirmado: crie",
                "sim, crie",
                "sim crie",
                "pode criar",
                "pode fazer",
                "fazer agora",
                "faz agora",
                "pode montar",
                "pode gerar",
                "confirmo criar",
                "confirmo, crie");
    }

    private boolean isConsultativePrompt(String prompt) {
        return containsAny(prompt, "melhor forma", "como visualizar", "visualizar informacoes",
                "visualizar informacao", "visualizar", "analisar", "analise", "explorar", "explore",
                "me ajude", "ajude", "escolher", "sugerir", "sugira", "como ver",
                "opcao", "opcoes", "alternativa", "alternativas", "indicar", "indique", "voce indica", "me indica",
                "recomendar", "recomende", "recomendacao", "recomendacoes", "comparar", "compare",
                "comparativo", "orientar", "oriente", "me oriente", "faz mais sentido", "devo usar",
                "ver", "visao", "mostrar", "mostre");
    }

    private boolean isApiCatalogQuestion(String prompt) {
        if (!containsApiCatalogSubject(prompt)) {
            return false;
        }
        return containsAny(prompt,
                "qual", "quais", "que api", "que apis", "que endpoint", "que endpoints",
                "que schema", "que schemas", "o que", "essa", "esse", "esta", "este", "existe", "existem",
                "listar", "liste", "mostrar", "mostre", "consultar", "consulta", "devo usar",
                "usar para", "campos existem", "suporta", "permite", "relacionad", "complement",
                "combinar", "combine", "vincul");
    }

    private boolean containsApiCatalogSubject(String prompt) {
        String wordPadded = " " + prompt.replaceAll("[^a-z0-9]+", " ") + " ";
        if (wordPadded.contains(" api ") || wordPadded.contains(" apis ")
                || wordPadded.contains(" acao ") || wordPadded.contains(" acoes ")
                || wordPadded.contains(" action ") || wordPadded.contains(" actions ")) {
            return true;
        }
        return containsAny(prompt, "endpoint", "endpoints", "schema", "schemas", "filtro", "filtros", "filtrar");
    }

    private boolean isBareDomainPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || !explicitResourcePath(normalized).isBlank()) {
            return false;
        }
        String[] tokens = normalized.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        String effectiveArtifactKind = discoveryArtifactKindForPrompt(prompt, artifactKind);
        if ("component".equals(effectiveArtifactKind)) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, effectiveArtifactKind, target)) {
            String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                    ? "post"
                    : target.submitMethod();
            candidates.add(candidate(
                    target.resourcePath(),
                    operation,
                    0.95d,
                    "resource resolved from current target widget",
                    "current-page"));
        }
        List<AgenticAuthoringCandidate> metadataCandidates = apiMetadataCandidateCatalog == null
                ? List.of()
                : apiMetadataCandidateCatalog.discover(prompt, effectiveArtifactKind, tenantId, environment, null);
        if (metadataCandidates.isEmpty() && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", effectiveArtifactKind, tenantId, environment, null);
        }
        if (!metadataCandidates.isEmpty()) {
            candidates.addAll(metadataCandidates);
        }
        return groundCandidates(prompt, deduplicateCandidates(candidates), tenantId, environment);
    }

    private List<AgenticAuthoringCandidate> discoverInitialCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target,
            String tenantId,
            String environment) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        String effectiveArtifactKind = discoveryArtifactKindForPrompt(prompt, artifactKind);
        if ("component".equals(effectiveArtifactKind)) {
            return List.of();
        }
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, effectiveArtifactKind, target)) {
            String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                    ? "post"
                    : target.submitMethod();
            candidates.add(candidate(
                    target.resourcePath(),
                    operation,
                    0.95d,
                    "resource resolved from current target widget",
                    "current-page"));
        }
        if (apiMetadataCandidateCatalog != null) {
            if (!"unknown".equals(effectiveArtifactKind)) {
                candidates.addAll(apiMetadataCandidateCatalog.discover(
                        prompt,
                        effectiveArtifactKind,
                        tenantId,
                        environment,
                        null));
                if (hasExplicitSourceCandidate(candidates)) {
                    return groundCandidates(prompt, explicitSourceDiscoveryScope(candidates), tenantId, environment);
                }
            }
            candidates.addAll(apiMetadataCandidateCatalog.discover(prompt, "unknown", tenantId, environment, null));
            if (hasExplicitSourceCandidate(candidates)) {
                return groundCandidates(prompt, explicitSourceDiscoveryScope(candidates), tenantId, environment);
            }
            candidates.addAll(apiMetadataCandidateCatalog.discover(
                    "",
                    !"unknown".equals(effectiveArtifactKind) ? effectiveArtifactKind : "unknown",
                    tenantId,
                    environment,
                    null));
        }
        return groundCandidates(prompt, deduplicateCandidates(candidates), tenantId, environment);
    }

    private String discoveryArtifactKindForPrompt(String prompt, String artifactKind) {
        String effectiveArtifactKind = valueOrUnknown(artifactKind);
        if (isExplicitGovernedBusinessRulePrompt(prompt)) {
            return "form";
        }
        return effectiveArtifactKind;
    }

    private boolean hasExplicitSourceCandidate(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && candidates.stream().anyMatch(candidate -> hasEvidence(candidate, "explicit-source-match"));
    }

    private List<AgenticAuthoringCandidate> explicitSourceDiscoveryScope(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match")
                        || hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path"))
                .toList();
    }

    private List<AgenticAuthoringCandidate> groundCandidates(
            String prompt,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        if (domainCatalogCandidateEnhancer == null || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> explicitSourceCandidates = candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        if (!explicitSourceCandidates.isEmpty()) {
            List<AgenticAuthoringCandidate> merged = new ArrayList<>(explicitSourceCandidates);
            candidates.stream()
                    .filter(candidate -> !hasEvidence(candidate, "explicit-source-match"))
                    .forEach(merged::add);
            return pruneWeakLexicalCandidatesWhenGrounded(deduplicateCandidates(merged));
        }
        return pruneWeakLexicalCandidatesWhenGrounded(deduplicateCandidates(
                domainCatalogCandidateEnhancer.enhance(prompt, candidates, tenantId, environment)));
    }

    private List<AgenticAuthoringCandidate> candidatesForLlmIntent(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> explicitSourceCandidates = candidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        if (explicitSourceCandidates.isEmpty()) {
            return candidates;
        }
        List<AgenticAuthoringCandidate> trustedContextCandidates = candidates.stream()
                .filter(candidate -> !hasEvidence(candidate, "explicit-source-match"))
                .filter(candidate -> hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path"))
                .toList();
        List<AgenticAuthoringCandidate> scoped = new ArrayList<>(explicitSourceCandidates);
        scoped.addAll(trustedContextCandidates);
        return deduplicateCandidates(scoped);
    }

    private List<AgenticAuthoringCandidate> pruneWeakLexicalCandidatesWhenGrounded(
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
        if (!hasGroundedCandidate) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> !isWeakLexicalCandidate(candidate))
                .toList();
    }

    private List<AgenticAuthoringCandidate> deduplicateCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .collect(
                        java.util.stream.Collectors.toMap(
                                AgenticAuthoringCandidate::resourcePath,
                                candidate -> candidate,
                                (left, right) -> left.score() >= right.score() ? left : right,
                                java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private AgenticAuthoringCandidate selectCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringTarget target,
            String operationKind,
            String artifactKind,
            String prompt) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, artifactKind, target)) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .map(candidate -> targetBoundCandidate(candidate, target))
                    .findFirst()
                    .orElse(null);
        }
        if (isDataSourceReplacementPrompt(normalize(prompt)) && !candidates.isEmpty()) {
            AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
            return promptAlignedCandidate == null ? candidates.get(0) : promptAlignedCandidate;
        }
        if (shouldDeferResourceSelectionForGovernedChoice(prompt, operationKind, artifactKind, candidates)) {
            return null;
        }
        if ("page".equals(artifactKind)
                && "explore".equals(operationKind)
                && containsAny(normalize(prompt), "nao sei quais informacoes existem", "quais informacoes existem")) {
            AgenticAuthoringCandidate pageCandidate = candidates.stream()
                    .filter(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))
                    .findFirst()
                    .orElse(null);
            if (pageCandidate != null) {
                return pageCandidate;
            }
        }
        if (isBroadArtifactDiscoveryOnly(candidates)
                && "form".equals(artifactKind)
                && "create".equals(operationKind)
                && strongestPromptCandidateAlignmentScore(prompt, candidates) < 6) {
            return null;
        }
        AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
        if (promptAlignedCandidate != null && shouldPreferPromptAlignedCandidate(promptAlignedCandidate, candidates)) {
            return promptAlignedCandidate;
        }
        if ("form".equals(artifactKind)
                && "create".equals(operationKind)
                && candidates.size() > 1
                && !hasStrongPromptAlignment(prompt, candidates)) {
            return null;
        }
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && !isBroadAnalyticalCanvasPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return null;
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && isBroadApiCatalogResourceDiscoveryPrompt(prompt)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && isSpecificApiCatalogAnswerPrompt(prompt)
                && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        if (candidates.stream().allMatch(candidate ->
                isWeakLexicalCandidate(candidate) && !hasTrustedSelectionEvidence(candidate))) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return null;
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        return null;
    }

    private boolean isDataSourceRefinement(AgenticAuthoringSemanticRefinement semanticRefinement) {
        return semanticRefinement != null
                && semanticRefinement.active()
                && "data_source".equals(semanticRefinement.refinementKind());
    }

    private boolean shouldPreferPromptAlignedCandidate(
            AgenticAuthoringCandidate promptAlignedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (promptAlignedCandidate == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        double bestScore = candidates.stream()
                .filter(Objects::nonNull)
                .mapToDouble(AgenticAuthoringCandidate::score)
                .max()
                .orElse(0d);
        return bestScore - promptAlignedCandidate.score() <= 0.20d;
    }

    private boolean hasStrongPromptAlignment(String prompt, List<AgenticAuthoringCandidate> candidates) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> promptMentionsSpecificCandidateToken(normalizedPrompt, candidate));
    }

    private int promptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        List<String> promptTokens = promptSpecificTokens(prompt);
        if (promptTokens.isEmpty()) {
            return 0;
        }
        List<String> evidenceTerms = candidateEvidenceTerms(candidate);
        int score = 0;
        for (String token : promptTokens) {
            if (termListMatchesToken(evidenceTerms, token)) {
                score += 3;
            } else if (promptMentionsSpecificCandidateToken(token, candidate)) {
                score += 1;
            }
        }
        return score;
    }

    private AgenticAuthoringCandidate promptAlignedGovernedRuleCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(candidate -> new CandidatePromptAlignment(candidate, directPromptCandidateAlignmentScore(prompt, candidate)))
                .filter(alignment -> alignment.score() > 0)
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .map(CandidatePromptAlignment::candidate)
                .orElse(null);
    }

    private int directPromptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        String candidateText = directCandidateSemanticText(candidate);
        if (candidateText.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : promptSpecificTokens(prompt)) {
            if (tokenMatchesCandidateText(token, candidateText)) {
                score += 1;
            }
        }
        return score;
    }

    private boolean tokenMatchesCandidateText(String token, String candidateText) {
        if (token == null || token.isBlank() || candidateText == null || candidateText.isBlank()) {
            return false;
        }
        String normalizedToken = normalize(token);
        String paddedText = " " + candidateText.replaceAll("[^a-z0-9]+", " ") + " ";
        if (paddedText.contains(" " + normalizedToken + " ")) {
            return true;
        }
        if (normalizedToken.endsWith("s") && normalizedToken.length() > 4
                && paddedText.contains(" " + normalizedToken.substring(0, normalizedToken.length() - 1) + " ")) {
            return true;
        }
        return normalizedToken.length() > 4 && paddedText.contains(" " + normalizedToken + "s ");
    }

    private String directCandidateSemanticText(AgenticAuthoringCandidate candidate) {
        Stream<String> evidenceText = candidate.evidenceBundle() == null
                || candidate.evidenceBundle().evidence() == null
                ? Stream.empty()
                : candidate.evidenceBundle().evidence().stream()
                .flatMap(evidence -> Stream.of(evidence.ref(), evidence.summary()));
        return normalize(Stream.concat(
                        Stream.of(candidate.resourcePath(), candidate.submitUrl(), candidate.reason()),
                        evidenceText)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" ")));
    }

    private int strongestPromptCandidateAlignmentScore(String prompt, List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .mapToInt(candidate -> promptCandidateAlignmentScore(prompt, candidate))
                .max()
                .orElse(0);
    }

    private List<String> promptSpecificTokens(String prompt) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank()) {
            return List.of();
        }
        return Stream.of(normalizedPrompt.replaceAll("[^a-z0-9]+", " ").split("\\s+"))
                .map(String::trim)
                .filter(token -> !isGenericAnalyticalCandidateToken(token))
                .filter(token -> !isPromptAlignmentStopword(token))
                .distinct()
                .toList();
    }

    private boolean isPromptAlignmentStopword(String token) {
        return Set.of(
                "uma", "um", "de", "do", "da", "dos", "das", "para", "por", "com",
                "crie", "criar", "monte", "montar", "gere", "gerar",
                "tabela", "table", "lista", "listagem", "dashboard", "painel",
                "grafico", "graficos", "campos", "colunas", "somente", "apenas")
                .contains(valueOrDefault(token, ""));
    }

    private List<String> candidateEvidenceTerms(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null || candidate.evidenceBundle().evidence() == null) {
            return List.of();
        }
        return candidate.evidenceBundle().evidence().stream()
                .flatMap(evidence -> Stream.concat(
                        evidence.matchedTerms() == null ? Stream.empty() : evidence.matchedTerms().stream(),
                        Stream.of(evidence.ref(), evidence.summary())))
                .map(this::normalize)
                .flatMap(value -> Stream.of(value.replaceAll("[^a-z0-9]+", " ").split("\\s+")))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> !isGenericAnalyticalCandidateToken(token))
                .distinct()
                .toList();
    }

    private boolean termListMatchesToken(List<String> terms, String token) {
        if (terms == null || terms.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        String normalizedToken = normalize(token);
        return terms.stream().anyMatch(term -> tokenMatchesTerm(normalizedToken, term));
    }

    private boolean tokenMatchesTerm(String token, String term) {
        if (token == null || token.isBlank() || term == null || term.isBlank()) {
            return false;
        }
        if (term.equals(token)) {
            return true;
        }
        if (token.endsWith("s") && token.length() > 4 && term.equals(token.substring(0, token.length() - 1))) {
            return true;
        }
        return term.endsWith("s") && term.length() > 4 && token.equals(term.substring(0, term.length() - 1));
    }

    private String candidateText(AgenticAuthoringCandidate candidate) {
        return normalize(String.join(" ",
                valueOrDefault(candidate.resourcePath(), ""),
                valueOrDefault(candidate.submitUrl(), ""),
                valueOrDefault(candidate.reason(), "")));
    }

    private AgenticAuthoringCandidate targetBoundCandidate(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringTarget target) {
        if (candidate == null || target == null || valueOrDefault(target.submitMethod(), "").isBlank()) {
            return candidate;
        }
        String submitMethod = valueOrDefault(target.submitMethod(), candidate.submitMethod());
        String submitUrl = valueOrDefault(target.submitUrl(), candidate.submitUrl());
        String schemaUrl = valueOrDefault(target.schemaUrl(), candidate.schemaUrl());
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                submitMethod,
                schemaUrl,
                submitUrl,
                submitMethod,
                candidate.score(),
                candidate.reason(),
                candidate.evidence(),
                candidate.evidenceBundle());
    }

    private boolean shouldDeferResourceSelectionForGovernedChoice(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return false;
        }
        String normalized = normalize(prompt);
        if (isBareDomainPrompt(normalized)) {
            return true;
        }
        if ("dashboard".equals(artifactKind)
                && containsAny(normalized, "dashboard", "painel", "grafico", "graficos")
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return true;
        }
        return false;
    }

    private boolean isBroadApiCatalogResourceDiscoveryPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized,
                "schema",
                "campos",
                "filtros",
                "actions",
                "acoes",
                "ações",
                "permite criar",
                "permite editar",
                "permite excluir",
                "qual endpoint devo usar",
                "qual api devo usar",
                "recomendacao",
                "recomendação")) {
            return false;
        }
        return containsAny(normalized,
                "quais apis",
                "quais dados",
                "quais recursos",
                "dados disponiveis",
                "dados disponíveis",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas",
                "areas de dados",
                "áreas de dados",
                "para criar graficos",
                "para criar gráficos",
                "para indicadores");
    }

    private boolean isSpecificApiCatalogAnswerPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "schema",
                "campos",
                "filtros",
                "actions",
                "acoes",
                "ações",
                "permite criar",
                "permite editar",
                "permite excluir",
                "qual endpoint devo usar",
                "qual api devo usar",
                "recomendacao",
                "recomendação");
    }

    private boolean isApiCatalogResourceListPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais dados",
                "que dados",
                "dados existem",
                "dados disponiveis",
                "dados disponíveis",
                "o que posso consultar",
                "posso consultar",
                "que telas recomenda",
                "quais telas recomenda",
                "telas recomenda criar",
                "quais apis",
                "quais api",
                "apis de",
                "api de",
                "quais recursos",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas");
    }

    private boolean isApiCatalogCandidateChoicePrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais apis",
                "quais api",
                "apis de",
                "api de",
                "quais recursos",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas");
    }

    private boolean isBroadAnalyticalCanvasPrompt(String prompt) {
        String normalized = normalize(prompt);
        boolean asksForCanvas = containsAny(normalized,
                "tela", "pagina", "painel", "dashboard", "visao");
        boolean asksForInspection = containsAny(normalized,
                "enxergar", "visualizar", "mostrar", "ver", "entender", "analisar");
        boolean asksForDetail = containsAny(normalized,
                "registros por tras", "registros por trás", "dados por tras", "dados por trás",
                "detalhes por tras", "detalhes por trás", "detalhar", "detalhes", "drill");
        return asksForCanvas && asksForInspection && asksForDetail;
    }

    private boolean promptMentionsSpecificCandidateToken(String prompt, AgenticAuthoringCandidate candidate) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank() || candidate == null) {
            return false;
        }
        String candidateText = normalize(String.join(" ",
                valueOrDefault(candidate.resourcePath(), ""),
                valueOrDefault(candidate.submitUrl(), ""),
                valueOrDefault(candidate.reason(), "")));
        for (String token : candidateText.replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (isGenericAnalyticalCandidateToken(token)) {
                continue;
            }
            if (normalizedPrompt.contains(token)) {
                return true;
            }
            if (token.endsWith("s") && token.length() > 4 && normalizedPrompt.contains(token.substring(0, token.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean isGenericAnalyticalCandidateToken(String token) {
        return token == null
                || token.length() < 4
                || Set.of(
                "api", "post", "get", "path", "filter", "cursor", "stats", "group", "timeseries",
                "distribution", "resource", "metadata", "lexical", "match",
                "broad", "artifact", "discovery", "analytics", "analitica", "analitico",
                "dashboard", "ranking", "rank", "valor", "valores", "maior", "maiores",
                "indicador", "indicadores", "empresa", "business")
                .contains(token);
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    private boolean shouldDetachCurrentTarget(String prompt, String artifactKind, AgenticAuthoringTarget target) {
        String normalized = normalize(prompt);
        if (target != null
                && target.resourcePath() != null
                && !target.resourcePath().isBlank()
                && isDataSourceReplacementPrompt(normalized)
                && !isExplicitSourcePreservePrompt(normalized)) {
            return true;
        }
        if (target == null || !"praxis-dynamic-form".equals(target.componentId())) {
            return false;
        }
        if ("form".equals(artifactKind)) {
            return false;
        }
        return containsAny(normalized,
                "nao quero cadastrar", "nao quero cadastro", "nao era isso", "em vez disso",
                "dashboard", "painel", "indicador", "indicadores",
                "master detail", "master-detail", "mestre detalhe", "mestre-detalhe",
                "lista e detalhe", "lista com detalhe", "abrir detalhes", "abrir detalhe",
                "acompanhar", "consultar", "buscar", "visualizar", "ver detalhes", "ver detalhe");
    }

    private boolean isDataSourceReplacementPrompt(String normalizedPrompt) {
        if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return false;
        }
        boolean sourceLanguage = containsAny(normalizedPrompt,
                "fonte", "dados", "tabela de", "recurso", "source", "data source");
        boolean explicitSourceReference = !explicitResourcePath(normalizedPrompt).isBlank();
        boolean sourceReplacementIntentLanguage = containsAny(normalizedPrompt,
                "troca", "troque", "mude", "muda", "usar", "usa", "use",
                "deve ser", "precisa ser", "como fonte", "nova fonte", "outra fonte");
        return containsAny(normalizedPrompt,
                "troca a fonte", "troque a fonte", "mude a fonte",
                "outra fonte", "nova fonte", "fonte deve ser", "fonte precisa ser")
                || (sourceLanguage && sourceReplacementIntentLanguage && explicitSourceReference);
    }

    private boolean isExplicitSourcePreservePrompt(String normalizedPrompt) {
        return containsAny(normalizedPrompt,
                "mantem os dados", "mantenha os dados", "mantem a fonte", "mantenha a fonte",
                "mantem o recurso", "mantenha o recurso", "mesma fonte", "mesmos dados", "preserve a fonte");
    }

    private boolean isAnalyticalComparisonPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "comparar", "compare", "comparativo", "grupo", "grupos", "categoria", "categorias");
    }

    private boolean isOptionalDataSourceHint(String prompt) {
        return containsAny(normalize(prompt),
                "se precisar usa",
                "se precisar, usa",
                "se precisar use",
                "se precisar, use",
                "se precisar usar",
                "se precisar, usar",
                "caso precise usa",
                "caso precise, usa",
                "caso precise use",
                "caso precise, use",
                "caso precise usar",
                "caso precise, usar");
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, double score, String reason, String evidence) {
        return candidate(resourcePath, "post", score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, String operation, double score, String reason, String evidence) {
        return candidate(resourcePath, resourcePath, operation, score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(
            String resourcePath,
            String submitUrl,
            String operation,
            double score,
            String reason,
            String evidence) {
        String normalizedOperation = operation.toLowerCase(Locale.ROOT);
        String schemaType = "get".equalsIgnoreCase(operation) || isReadProjectionOperation(submitUrl, operation)
                ? "response"
                : "request";
        String schemaPath = "/schemas/filtered?path=" + submitUrl + "&operation=" + normalizedOperation
                + "&schemaType=" + schemaType;
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaPath,
                submitUrl,
                operation,
                score,
                reason,
                List.of(evidence, "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending")
        );
    }

    private String authoringProfile(String operationKind, String artifactKind, boolean explicitLocalPageComposition) {
        if ("api_catalog".equals(artifactKind)) {
            return "api-catalog-qa";
        }
        if ("component".equals(artifactKind)
                && ("explore".equals(operationKind) || "explain".equals(operationKind))) {
            return "component-catalog-qa";
        }
        if (explicitLocalPageComposition
                && "page".equals(artifactKind)
                && ("create".equals(operationKind) || "modify".equals(operationKind) || "remove".equals(operationKind))) {
            return "ui-composition-plan@0.1.0";
        }
        if ("form".equals(artifactKind) && ("create".equals(operationKind)
                || "modify".equals(operationKind)
                || "remove".equals(operationKind))) {
            return "create-minimal-form";
        }
        return "generic-page-change";
    }

    private AgenticAuthoringGateResult withPromptSpecificGateMessages(
            AgenticAuthoringGateResult gate,
            String rawPrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringConversationTurn turn) {
        List<String> messages = new ArrayList<>(gate.messages());
        if ("create".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && isOptionalDataSourceHint(prompt)) {
            messages.remove("resource-candidate-ambiguous");
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult(gate.gateId(), status, List.copyOf(messages));
    }

    private boolean startsWithConfirmation(String prompt) {
        String normalized = normalize(prompt);
        return normalized.startsWith("sim")
                || normalized.startsWith("ok")
                || normalized.startsWith("confirmo")
                || normalized.startsWith("confirmado")
                || normalized.startsWith("confirmed");
    }

    private AgenticAuthoringGateResult withGateMessage(AgenticAuthoringGateResult gate, String message) {
        if (gate == null || message == null || message.isBlank()) {
            return gate;
        }
        List<String> messages = new ArrayList<>(gate.messages() == null ? List.of() : gate.messages());
        if (!messages.contains(message)) {
            messages.add(message);
        }
        return new AgenticAuthoringGateResult(gate.gateId(), "clarification_required", List.copyOf(messages));
    }

    private AgenticAuthoringGateResult withSharedRuleAuthoringGate(
            AgenticAuthoringGateResult gate,
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring) {
        if (gate == null) {
            return null;
        }
        if (!requiresSharedRuleAuthoring(request, prompt, selectedCandidate, llmRequiresGovernedAuthoring)) {
            return gate;
        }
        List<String> messages = new ArrayList<>(gate.messages());
        if (!messages.contains("shared-rule-authoring-required")) {
            messages.add("shared-rule-authoring-required");
        }
        return new AgenticAuthoringGateResult(
                gate.gateId(),
                "route_required",
                List.copyOf(messages));
    }

    private AgenticAuthoringGateResult withExplicitLocalUiCompositionGate(
            AgenticAuthoringGateResult gate,
            boolean explicitLocalUiComposition,
            boolean explicitLocalTargetedComposition) {
        if (!explicitLocalUiComposition || gate == null || gate.messages() == null || gate.messages().isEmpty()) {
            return gate;
        }
        List<String> messages = gate.messages().stream()
                .filter(message -> !"resource-candidate-required".equals(message))
                .filter(message -> !"resource-candidate-ambiguous".equals(message))
                .filter(message -> !explicitLocalTargetedComposition || !"target-widget-required".equals(message))
                .toList();
        String status = messages.isEmpty() ? "eligible" : gate.status();
        return new AgenticAuthoringGateResult(gate.gateId(), status, messages);
    }

    private boolean hasExplicitLocalComponentTarget(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind) {
        if (request == null) {
            return false;
        }
        if (request.selectedWidgetKey() != null && !request.selectedWidgetKey().isBlank()) {
            return true;
        }
        String targetComponentId = valueOrDefault(request.targetComponentId(), "").trim();
        if (targetComponentId.isBlank() || "praxis-dynamic-page-builder".equals(targetComponentId)) {
            return false;
        }
        return containsAny(normalize(targetComponentId),
                artifactKind,
                "table",
                "tabela",
                "crud",
                "form",
                "formulario",
                "list",
                "lista",
                "component",
                "componente");
    }

    private String explicitLocalTargetedArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String currentArtifactKind) {
        String normalizedComponent = normalize(valueOrDefault(request.targetComponentId(), ""));
        String normalizedPrompt = normalize(prompt);
        if (containsAny(normalizedComponent, "table", "tabela")
                || containsAny(normalizedPrompt, "tabela", "table", "crud", "coluna", "colunas")) {
            return "table";
        }
        if (containsAny(normalizedComponent, "form", "formulario")
                || containsAny(normalizedPrompt, "formulario", "form", "campo", "campos")) {
            return "form";
        }
        if (containsAny(normalizedComponent, "list", "lista")
                || containsAny(normalizedPrompt, "lista", "list", "cards")) {
            return "list";
        }
        if ("form".equals(currentArtifactKind)
                || "table".equals(currentArtifactKind)
                || "dashboard".equals(currentArtifactKind)
                || "page".equals(currentArtifactKind)) {
            return currentArtifactKind;
        }
        return "component";
    }

    private boolean requiresSharedRuleAuthoring(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring) {
        String requestedFlow = requestedAuthoringFlow(request);
        if ("shared_rule_authoring".equals(requestedFlow) && !isExplicitLocalUiCompositionPrompt(prompt)) {
            return selectedCandidate != null;
        }
        return selectedCandidate != null
                && (llmRequiresGovernedAuthoring || isExplicitGovernedBusinessRulePrompt(prompt));
    }

    private boolean requiresGovernedAuthoring(AgenticAuthoringLlmIntentResolution llmIntent) {
        return llmIntent != null && llmIntent.requiresGovernedAuthoring();
    }

    private boolean isExplicitGovernedBusinessRulePrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || isExplicitLocalUiCompositionPrompt(prompt)) {
            return false;
        }
        boolean ruleAuthoring = containsAny(normalized,
                "regra", "regras",
                "politica", "policy",
                "validacao", "validar", "valide",
                "aprovacao", "aprovar",
                "governada", "governado", "governanca", "governance",
                "compliance", "lgpd", "gdpr",
                "elegibilidade", "eligibility");
        boolean businessConstraint = containsAny(normalized,
                "nao pode", "nao deve", "impedir", "impeca",
                "bloqueado", "bloqueada", "blocked",
                "inativo", "inactive",
                "obrigatorio", "obrigatoria", "obrigatorios", "obrigatorias",
                "exigir", "exija", "require",
                "antes de salvar", "antes de seguir",
                "selecionado", "selecionada", "selecionavel", "selecionavel",
                "mascarar", "mascare", "dados sensiveis", "privacidade");
        return ruleAuthoring && businessConstraint;
    }

    private boolean isExplicitLocalUiCompositionPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean localDemoIntent = containsAny(normalized,
                "conteudo local",
                "conteudo editorial",
                "editorial local",
                "local/editorial",
                "dados locais",
                "local data",
                "demo",
                "demonstracao",
                "demonstracao",
                "exemplo",
                "exemplos",
                "sem dependencia de api",
                "sem api real",
                "sem schema externo",
                "sem conectar dados",
                "sem conectar api",
                "sem conectar api real",
                "nao conecte api",
                "nao conecte api real",
                "nao conectar api",
                "nao conectar api real",
                "nao use api real",
                "nao usar api real",
                "nao descubra fonte",
                "nao descubra fonte de dados",
                "nao descobrir fonte",
                "nao descobrir fonte de dados",
                "sem regra de negocio",
                "sem criar regra",
                "sem criar regra de negocio",
                "without business rule",
                "without creating business rule");
        boolean uiCompositionIntent = containsAny(normalized,
                "pagina",
                "page",
                "aba",
                "abas",
                "tab",
                "tabs",
                "formulario",
                "form",
                "lista",
                "list",
                "cards",
                "dashboard",
                "widget",
                "componente",
                "component",
                "tabela",
                "table",
                "crud",
                "coluna",
                "colunas",
                "column",
                "columns",
                "acao",
                "acoes",
                "action",
                "actions");
        return localDemoIntent && uiCompositionIntent;
    }

    private boolean isExplicitLocalPageCompositionPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean tabbedWorkspaceIntent = containsAny(normalized, "pagina", "page", "aba", "abas", "tab", "tabs")
                && containsAny(normalized, "crud", "registros", "formulario", "form", "relacionamento", "relacionamentos",
                "comentario", "comentarios", "lista", "list", "cards");
        boolean refinementIntent = containsAny(normalized,
                "refine", "refinar", "adicione", "adicionar", "inclua", "incluir",
                "ajuste", "ajustar", "preencha", "preencher", "complete", "completar",
                "corrija", "corrigir", "preserve", "preservar", "manter", "mantendo", "continue");
        return tabbedWorkspaceIntent && (refinementIntent || containsAny(normalized, "pagina", "page"));
    }

    private boolean isMaterializablePageCompositionOperation(String operationKind) {
        return "create".equals(operationKind) || "modify".equals(operationKind) || "remove".equals(operationKind);
    }

    private String materializablePageCompositionOperation(String fallbackOperationKind, String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized, "refine", "refinar", "adicione", "adicionar", "inclua", "incluir",
                "ajuste", "ajustar", "preencha", "preencher", "complete", "completar",
                "corrija", "corrigir", "preserve", "preservar", "manter", "mantendo", "continue")) {
            return "modify";
        }
        if (isMaterializablePageCompositionOperation(fallbackOperationKind)) {
            return fallbackOperationKind;
        }
        return "create";
    }

    private String materializablePageCompositionChangeKind(
            String changeKind,
            String operationKind,
            String prompt) {
        if ("answer_api_catalog_question".equals(changeKind)) {
            return "create".equals(operationKind) ? "create_artifact" : "unknown";
        }
        if ("unknown".equals(changeKind) && "create".equals(operationKind)
                && isExplicitLocalPageCompositionPrompt(prompt)) {
            return "create_artifact";
        }
        return changeKind;
    }

    private boolean shouldSuppressLlmAssistantMessageForExplicitLocalComposition(
            boolean explicitLocalUiComposition,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return explicitLocalUiComposition
                && llmIntent != null
                && "api_catalog".equals(valueOrUnknown(llmIntent.artifactKind()));
    }

    private boolean isConfirmedDataSourceSelection(String prompt) {
        return containsAny(prompt, "fonte confirmada", "source confirmed", "data source", "usar vw-", "use vw-",
                "usar /api/", "use /api/", "recurso confirmado", "api confirmada");
    }

    private boolean isBareConfirmationPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean materializationRequest = containsAny(normalized,
                "preview",
                "previa",
                "pre visualizacao",
                "materializar",
                "materialize");
        boolean generationVerb = containsAny(normalized, "gere", "gerar", "generate");
        boolean newInstruction = containsAny(normalized,
                "crie",
                "criar",
                "adicione",
                "adicionar",
                "altere",
                "alterar",
                "remova",
                "remover",
                "monte",
                "montar",
                "create",
                "add",
                "change",
                "remove",
                "build")
                || (generationVerb && !materializationRequest);
        if (newInstruction) {
            return false;
        }
        return containsAny(normalized,
                "sim",
                "confirmo",
                "confirmado",
                "confirmed",
                "ok",
                "siga",
                "seguir",
                "pode seguir",
                "pode fazer",
                "fazer agora",
                "materializar",
                "materialize",
                "faca isso",
                "faça isso");
    }

    private List<String> clarificationQuestions(
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> questions = new ArrayList<>();
        for (String message : gate.messages()) {
            if ("resource-candidate-required".equals(message)) {
                questions.add("Qual recurso de negocio deve alimentar esta tela?");
            } else if ("resource-candidate-ambiguous".equals(message)) {
                String options = formatCandidateOptions(candidates);
                if (options.isBlank()) {
                    questions.add("Qual recurso candidato deve ser usado?");
                } else {
                    questions.add("Encontrei recursos proximos: " + options + ". Qual deles voce quer usar?");
                }
            } else if ("target-widget-required".equals(message)) {
                questions.add("Qual componente existente deve ser alterado?");
            } else if ("intent-operation-unknown".equals(message)) {
                questions.add("O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?");
            } else if ("intent-artifact-unknown".equals(message)) {
                questions.add("Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?");
            } else if ("analytics-breakdown-required".equals(message)) {
                questions.add("Qual recorte, metrica ou dimensao governada deve orientar este dashboard?");
            } else if ("intent-confirmation-required".equals(message)) {
                questions.add(confirmationQuestion(operationKind, artifactKind, selectedCandidate));
            }
        }
        return List.copyOf(questions);
    }

    private String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("table".equals(artifactKind)) {
            return "Posso criar uma tabela usando o recurso de negocio selecionado?";
        }
        if ("form".equals(artifactKind)) {
            return "Posso criar um formulario usando o recurso de negocio selecionado?";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Posso criar um dashboard usando o recurso de negocio selecionado?";
        }
        return "Posso aplicar esta alteracao usando o recurso de negocio selecionado?";
    }

    private boolean isConsultativeCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksQuestion = containsAny(normalized,
                "quais", "qual", "que outras", "outras tabelas", "outras fontes",
                "o que posso", "o que da para", "o que dá para", "sugira", "sugerir",
                "compare", "comparar", "compara", "mostre", "mostrar", "liste", "listar");
        boolean materializationIntent = containsAny(normalized,
                "crie", "criar tela", "criar uma tela", "organize", "organizar",
                "editar", "edite", "formulario guiado", "materializar", "preview");
        if (materializationIntent) {
            return false;
        }
        boolean asksCatalog = containsAny(normalized,
                "tabela", "tabelas", "tela", "telas", "pagina", "paginas",
                "api", "apis", "fonte", "fontes", "recurso", "recursos",
                "outras fontes", "outra fonte", "outros recursos", "outro recurso",
                "dados", "dado",
                "campo", "campos", "schema", "schemas");
        boolean asksFieldCatalog = containsAny(normalized, "campo", "campos", "schema", "schemas");
        boolean asksRelationship = containsAny(normalized,
                "referencia", "referencias", "relacao", "relacionamento", "relacionadas", "relacionados");
        boolean asksCreation = containsAny(normalized,
                "podem ser criadas", "posso criar", "daria para criar", "da para criar", "dá para criar",
                "recomenda criar", "recomende criar", "recomendar criar");
        return asksQuestion && ((asksCatalog && (asksRelationship || asksCreation)) || asksFieldCatalog);
    }

    private boolean isConsultativeDomainQuestion(String prompt) {
        return isConsultativeCatalogQuestion(prompt)
                || isConsultativeGovernanceQuestion(prompt);
    }

    private boolean isDomainDataCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "dados", "fonte", "fontes", "recurso", "recursos",
                "api", "apis", "schema", "schemas", "campo", "campos",
                "tabela de dados", "catalogo de dados", "catálogo de dados",
                "dominio", "domínio", "entidade", "entidades");
    }

    private boolean isConsultativeFormPolicyQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
    }

    private boolean isConsultativePlatformCapabilityQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "o que posso", "o que consigo", "o que da para", "o que dá para",
                "o que voce pode", "o que você pode", "como faco", "como faço",
                "como habilito", "como configurar", "como configuro", "como funciona",
                "como criar", "como montar", "quais componentes", "que componentes",
                "quais widgets", "que widgets", "quais telas", "que telas",
                "quais paginas", "que paginas", "posso criar", "daria para criar",
                "da para criar", "dá para criar", "posso montar", "posso fazer");
        boolean platformSubject = containsAny(normalized,
                "aqui", "praxis", "page builder", "builder", "assistente",
                "componente", "componentes", "widget", "widgets",
                "tela", "telas", "pagina", "paginas", "dashboard", "painel",
                "formulario", "formulário", "tabela", "grafico", "gráfico",
                "botao", "botão", "exportar", "selecionada", "selecionadas",
                "aba", "abas", "tabs", "stepper", "administrativo", "admin",
                "livremente", "predefinido", "predefinidos", "pre definido", "pre definidos");
        boolean directMaterializationCommand = containsAny(normalized,
                "crie ", "criar agora", "monte ", "montar agora", "gere ", "gerar agora",
                "faca ", "faça ", "materialize", "materializar", "preview", "pre visualizacao");
        boolean dataCatalogSubject = containsAny(normalized,
                "dados", "fonte", "fontes", "api", "apis", "schema", "schemas",
                "campo", "campos", "recurso", "recursos", "entidade", "entidades");
        boolean formPolicySubject = containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
        return question && platformSubject && !directMaterializationCommand && (!dataCatalogSubject || formPolicySubject);
    }

    private String platformCapabilityChangeKind(String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized,
                "habilito", "configurar", "configuro", "botao", "botão",
                "exportar", "selecionada", "selecionadas", "linhas selecionadas")) {
            return "answer_component_capability_question";
        }
        return "answer_component_catalog_question";
    }

    private boolean isConsultativeGovernanceQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "quais", "qual", "que campos", "quais campos", "onde", "como",
                "liste", "listar", "mostre", "mostrar", "consultar", "consulta",
                "what", "which", "where", "how", "list", "show");
        boolean governanceSubject = containsAny(normalized,
                "lgpd", "gdpr",
                "governanca", "governance",
                "mascar", "mask",
                "privacidade", "privacy",
                "dados sensiveis", "dado sensivel", "sensitive data",
                "personal data", "dados pessoais");
        boolean authoringAction = containsAny(normalized,
                "crie", "criar", "create",
                "aplique", "aplicar", "apply",
                "implemente", "implementar", "implement",
                "bloqueie", "bloquear", "block",
                "mascare", "mascarar",
                "exigir", "exija", "require",
                "valide", "validar", "validate",
                "publique", "publicar", "publish",
                "simule", "simular", "simulate",
                "aprove", "aprovar", "approve");
        return question && governanceSubject && !authoringAction;
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
        if (gate != null && gate.messages().contains("shared-rule-authoring-required")) {
            return sharedRuleAuthoringAssistantMessage(selectedCandidate);
        }
        if ("api_catalog".equals(artifactKind) && isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("component".equals(artifactKind) && isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("explore".equals(operationKind)
                && "api_catalog".equals(artifactKind)
                && isConsultativeDomainQuestion(prompt)) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (gate != null && gate.messages().contains("resource-candidate-required")) {
            return missingResourceAssistantMessage(artifactKind);
        }
        if (gate != null
                && gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null) {
            return ambiguousResourceAssistantMessage(artifactKind, candidates);
        }
        if ("unknown".equals(valueOrUnknown(operationKind))
                || "unknown".equals(valueOrUnknown(artifactKind))) {
            return unresolvedIntentAssistantMessage();
        }
        if (!"explore".equals(operationKind)) {
            return authoringAssistantMessage(operationKind, artifactKind, changeKind, selectedCandidate);
        }
        if ("api_catalog".equals(artifactKind)) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (answeredBareDomainClarification) {
            return null;
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi que voce quer um painel analitico. Posso preparar uma previa com a fonte de negocio mais aderente, ou ajustar antes quais metricas e recortes devem aparecer.";
        }
        if ("table".equals(artifactKind)) {
            return "Consigo montar essa tabela. Antes de criar, preciso apenas confirmar a fonte de negocio e quais colunas devem aparecer como principais.";
        }
        return "Posso ajudar a transformar esse pedido em uma tela. Se voce me disser se prefere dashboard, tabela, formulario ou uma pagina mais completa, eu sigo pelo caminho certo.";
    }

    private boolean shouldUsePlatformGuidanceMessage(String prompt, String assistantMessage) {
        String normalizedPrompt = normalize(prompt);
        String normalizedMessage = normalize(assistantMessage);
        if (normalizedMessage.isBlank()) {
            return true;
        }
        boolean asksForms = containsAny(normalizedPrompt, "formulario", "formulário", "livre", "predefinido", "pre definido");
        boolean asksComponents = containsAny(normalizedPrompt,
                "componentes", "widgets", "telas", "paginas", "páginas", "o que posso",
                "administrativo", "admin", "painel", "dashboard");
        boolean asksComponentCapability = containsAny(normalizedPrompt,
                "habilito", "habilitar", "configurar", "configuro",
                "botao", "botão", "exportar", "selecionada", "selecionadas", "tabela");
        boolean explainsPlatform = containsAny(normalizedMessage,
                "praxis", "componente", "componentes", "governado", "governada",
                "formulario", "formulário", "tabela", "dashboard", "grafico", "gráfico");
        boolean dataCatalogAnswer = containsAny(normalizedMessage,
                "encontrei dados governados em", "pelos campos confirmados", "campos confirmados",
                "fonte de dados", "fontes de dados");
        boolean technicalLeak = containsAny(normalizedMessage,
                "/api/", "/schemas/", "endpoint", "endpoints", "schema", "schemas", "json", "post", "get",
                ".enabled", ".scope", ".formats", "selection.enabled", "export.general", "toolbar.actions",
                "api", "apis", "backend", "schemas", "esquemas", "actions", "acoes", "ações");
        boolean plainLanguageRequested = containsAny(normalizedPrompt,
                "sem termos tecnicos", "sem termo tecnico", "linguagem natural", "conversa natural");
        return (asksForms || asksComponents || asksComponentCapability)
                && (!explainsPlatform || dataCatalogAnswer || technicalLeak || plainLanguageRequested);
    }

    private boolean shouldUseApiCatalogGuidanceMessage(String assistantMessage) {
        String rawMessage = valueOrDefault(assistantMessage, "").toLowerCase(Locale.ROOT);
        if (rawMessage.contains("/api") || rawMessage.contains("/schemas") || rawMessage.contains("url")) {
            return true;
        }
        String normalizedMessage = normalize(assistantMessage);
        return containsAny(normalizedMessage,
                "/api/",
                "/schemas/",
                "endpoint",
                "endpoints",
                "schema",
                "schemas",
                "esquema",
                "esquemas",
                "json",
                "post",
                "get",
                "actions");
    }

    private String sanitizeApiCatalogConsultativeLanguage(
            String assistantMessage,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        String sanitized = sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        if (sanitized == null || sanitized.isBlank()) {
            return sanitized;
        }
        return sanitized
                .replaceAll("(?i)\\bendpoints?\\b", "fontes")
                .replaceAll("(?i)\\bschemas?\\b", "campos confirmados")
                .replaceAll("(?i)\\bjson\\b", "estrutura")
                .replaceAll("(?i)\\burls? de submiss[aã]o\\b", "formas de uso")
                .replaceAll("(?i)\\burls?\\b", "referencias")
                .replaceAll("(?i)\\bPOST\\b", "consulta ou envio")
                .replaceAll("(?i)\\bGET\\b", "consulta")
                .replaceAll("(?i)\\bPUT\\b", "atualizacao")
                .replaceAll("(?i)\\bPATCH\\b", "ajuste")
                .replaceAll("(?i)\\bDELETE\\b", "remocao")
                .replaceAll("(?i)\\bactions?\\b", "acoes disponiveis")
                .replace("cada fontes", "cada fonte");
    }

    private String platformCapabilityAssistantMessage(
            String prompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        String normalized = normalize(prompt);
        Optional<ComponentCapabilityMatch> capabilityMatch = bestComponentCapabilityMatch(normalized, componentCapabilities);
        if (capabilityMatch.isPresent()
                && capabilityMatch.get().score() >= 3
                && containsAny(normalized,
                "habilito", "habilitar", "configurar", "configuro", "como",
                "botao", "botão", "exportar", "selecionada", "selecionadas")) {
            return componentCapabilityAssistantMessage(capabilityMatch.get());
        }
        List<String> families = componentFamilyLabels(componentCapabilities);
        String componentText = families.isEmpty()
                ? "tabelas, formularios, dashboards, graficos, filtros, abas e fluxos em etapas"
                : humanJoin(families);
        if (containsAny(normalized, "formulario", "formulário", "livre", "predefinido", "pre definido")) {
            return "Voce pode descrever o formulario em linguagem natural, mas no Praxis a versao pronta para uso deve ficar ligada a uma decisao governada: dados, campos, acoes e regras confirmados pelo backend. "
                    + "Tambem posso ajudar com um rascunho mais livre para explorar a ideia, mas antes de publicar ou materializar eu valido o que existe no dominio. "
                    + "Na pratica, eu posso transformar seu pedido em formulario de cadastro, edicao ou solicitacao quando houver uma fonte e uma acao governada para sustentar isso.";
        }
        if (containsAny(normalized, "administrativo", "admin", "painel")) {
            return "Para montar um painel administrativo, voce pode me dizer o objetivo em linguagem natural: acompanhar indicadores, revisar registros, operar cadastros ou organizar etapas de um processo. "
                    + "Eu descubro os componentes governados disponiveis, combino isso com o dominio e proponho uma tela com tabelas, graficos, filtros, formularios ou abas conforme fizer sentido. "
                    + "Nada precisa nascer como JSON manual: a decisao vem primeiro, e a tela e materializada a partir dela.";
        }
        if (containsAny(normalized,
                "exportar", "selecionada", "selecionadas", "linhas selecionadas", "botao", "botão", "tabela")) {
            return "Em uma tabela do Praxis, a exportacao de linhas selecionadas deve ser tratada como capacidade governada do componente: a tabela precisa permitir selecao, expor uma acao de toolbar ou acao em massa e executar a exportacao usando apenas as linhas marcadas. "
                    + "Eu posso te orientar nessa configuracao ou, quando houver uma tabela selecionada, preparar a alteracao para revisao.";
        }
        return "Aqui voce pode conversar comigo em linguagem natural sobre a intencao da tela antes de criar qualquer coisa. "
                + "O Praxis usa componentes governados como " + componentText + ", escolhidos de acordo com o objetivo, o dominio disponivel e as regras de materializacao. "
                + "Voce pode pedir algo como um dashboard para acompanhar indicadores, uma tabela operacional, um formulario governado, uma pagina com abas ou um fluxo em etapas. "
                + "Quando o pedido ficar concreto, eu valido dados, campos e acoes antes de gerar a previa.";
    }

    private Optional<ComponentCapabilityMatch> bestComponentCapabilityMatch(
            String normalizedPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (normalizedPrompt == null
                || normalizedPrompt.isBlank()
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return Optional.empty();
        }
        return componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .flatMap(catalog -> nullToEmpty(catalog.capabilities()).stream()
                        .filter(Objects::nonNull)
                        .map(capability -> new ComponentCapabilityMatch(
                                catalog.componentId(),
                                capability,
                                componentCapabilityScore(normalizedPrompt, catalog.componentId(), capability))))
                .filter(match -> match.score() > 0)
                .max(Comparator
                        .comparingInt(ComponentCapabilityMatch::score)
                        .thenComparing(match -> valueOrDefault(match.componentId(), "")));
    }

    private int componentCapabilityScore(
            String normalizedPrompt,
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability) {
        int score = 0;
        String normalizedComponent = normalize(componentId);
        if (!normalizedComponent.isBlank() && normalizedPrompt.contains(normalizedComponent.replace('-', ' '))) {
            score += 2;
        }
        String normalizedChangeKind = normalize(capability.changeKind()).replace('_', ' ');
        if (!normalizedChangeKind.isBlank() && normalizedPrompt.contains(normalizedChangeKind)) {
            score += 2;
        }
        for (String term : nullToEmpty(capability.triggerTerms())) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && normalizedPrompt.contains(normalizedTerm)) {
                score += normalizedTerm.contains(" ") ? 2 : 1;
            }
        }
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example : nullToEmpty(capability.examples())) {
            String exampleText = normalize(valueOrDefault(example.prompt(), "") + " " + valueOrDefault(example.intent(), ""));
            for (String token : normalizedPrompt.split("\\s+")) {
                if (token.length() >= 5 && exampleText.contains(token)) {
                    score++;
                }
            }
        }
        return score;
    }

    private String componentCapabilityAssistantMessage(ComponentCapabilityMatch match) {
        String componentLabel = componentLabel(match.componentId());
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = match.capability();
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example =
                nullToEmpty(capability.examples()).stream().findFirst().orElse(null);
        String intent = example == null ? "" : valueOrDefault(example.intent(), "");
        List<String> hints = example == null ? List.of() : nullToEmpty(example.configHints());
        StringBuilder message = new StringBuilder();
        message.append("Sim. No Praxis, isso e uma capacidade governada do componente ")
                .append(componentLabel)
                .append(", nao um atalho solto da tela.");
        if (!intent.isBlank()) {
            message.append(" A intencao e ").append(stripTrailingSentencePunctuation(lowercaseFirst(intent))).append(".");
        }
        if (!hints.isEmpty()) {
            message.append(" Para funcionar bem, a decisao precisa cobrir ")
                    .append(humanJoin(hints.stream()
                            .map(this::humanizeConfigHint)
                            .filter(hint -> !hint.isBlank())
                            .distinct()
                            .limit(5)
                            .toList()))
                    .append(".");
        }
        message.append(" Se ja houver uma tabela selecionada na pagina, eu posso preparar essa alteracao para revisao usando a configuracao governada do componente.");
        return message.toString();
    }

    private String humanizeConfigHint(String hint) {
        String normalized = normalize(hint);
        if (normalized.contains("selection.enabled")) {
            return "selecao de linhas habilitada";
        }
        if (normalized.contains("toolbar") || normalized.contains("acao em massa") || normalized.contains("action")) {
            return "uma acao de exportar na barra da tabela ou nas acoes em massa";
        }
        if (normalized.contains("export.enabled")) {
            return "exportacao habilitada";
        }
        if (normalized.contains("export.formats")) {
            return "formatos permitidos, como CSV ou Excel";
        }
        if (normalized.contains("scope=selected") || normalized.contains("scope selected")) {
            return "escopo limitado aos registros selecionados";
        }
        if (normalized.contains("includeheaders")) {
            return "cabecalhos no arquivo exportado quando fizer sentido";
        }
        return hint
                .replace("=", " ")
                .replace(".", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String componentLabel(String componentId) {
        String normalized = valueOrDefault(componentId, "").trim();
        if (normalized.isBlank()) {
            return "governado";
        }
        return Stream.of(normalized.split("[^A-Za-z0-9]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.equalsIgnoreCase("praxis")
                        ? "Praxis"
                        : part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String lowercaseFirst(String value) {
        String text = valueOrDefault(value, "").trim();
        if (text.isBlank()) {
            return text;
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private String stripTrailingSentencePunctuation(String value) {
        return valueOrDefault(value, "").replaceAll("[.!?]+$", "").trim();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ComponentCapabilityMatch(
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            int score) {
    }

    private List<String> componentFamilyLabels(AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            String componentId = valueOrDefault(catalog == null ? null : catalog.componentId(), "");
            String normalized = normalize(componentId);
            if (normalized.contains("chart")) {
                labels.add("graficos");
            } else if (normalized.contains("table") || normalized.contains("grid")) {
                labels.add("tabelas");
            } else if (normalized.contains("form")) {
                labels.add("formularios");
            } else if (normalized.contains("tabs")) {
                labels.add("abas");
            } else if (normalized.contains("stepper") || normalized.contains("wizard")) {
                labels.add("fluxos em etapas");
            } else if (normalized.contains("filter")) {
                labels.add("filtros");
            } else if (normalized.contains("crud")) {
                labels.add("CRUDs governados");
            }
        }
        return labels.stream().distinct().limit(8).toList();
    }

    private String unresolvedIntentAssistantMessage() {
        return "Ainda nao consegui entender isso com seguranca. Nao vou criar nem alterar nada agora. "
                + "Voce pode me pedir para explorar os dados disponiveis, comparar fontes de negocio ou gerar uma tela especifica.";
    }

    private String authoringAssistantMessage(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        String resourceSummary = selectedCandidate == null || valueOrDefault(selectedCandidate.reason(), "").isBlank()
                ? "a fonte de negocio mais aderente"
                : "a fonte de negocio selecionada";
        if ("set_chart_type".equals(changeKind)) {
            return "Atualizei o grafico selecionado mantendo os dados, a dimensao e a metrica atuais.";
        }
        if ("chart".equals(artifactKind) && "modify".equals(operationKind)) {
            return "Atualizei o grafico mantendo os dados confirmados e o contexto atual.";
        }
        if ("chart".equals(artifactKind)) {
            return "Entendi. Vou preparar o grafico usando dados confirmados e manter a decisao pronta para revisao.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart_drilldown".equals(changeKind)) {
            return "Atualizei a composicao para mostrar detalhes a partir da selecao do grafico, mantendo a fonte e os campos confirmados.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart".equals(changeKind)) {
            return "Entendi. Vou preparar somente o grafico pedido, usando " + resourceSummary
                    + ", sem adicionar tabela, filtros ou KPIs.";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi o painel que voce quer montar. Vou usar " + resourceSummary
                    + " para preparar uma previa com metricas e recortes coerentes.";
        }
        if ("table".equals(artifactKind)) {
            return "Entendi a tabela que voce quer montar. Vou usar " + resourceSummary
                    + " para preparar as colunas principais e manter a decisao pronta para revisao.";
        }
        if ("form".equals(artifactKind)) {
            return "Entendi o formulario que voce quer criar. Vou usar " + resourceSummary
                    + " para sugerir os campos certos e manter a decisao pronta para revisao.";
        }
        return "Entendi o que voce quer criar. Vou usar " + resourceSummary
                + " para preparar uma previa e manter a decisao pronta para revisao.";
    }

    private String normalizeTargetlessCreationChangeKind(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringTarget target) {
        if (target != null || !"create".equals(operationKind) || !isCreatableArtifactKind(artifactKind)) {
            return changeKind;
        }
        if (!containsAny(prompt, "crie", "criar", "monte", "montar", "gere", "gerar", "build", "create", "make")) {
            return changeKind;
        }
        if (containsAny(changeKind, "add_field", "add_column", "add_filter", "modify_field", "update_field")) {
            return "create_artifact";
        }
        return changeKind;
    }

    private boolean isCreatableArtifactKind(String artifactKind) {
        return "dashboard".equals(artifactKind)
                || "table".equals(artifactKind)
                || "form".equals(artifactKind)
                || "page".equals(artifactKind);
    }

    private boolean shouldExposeAsChartArtifact(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if (!"create".equals(operationKind) || !"dashboard".equals(artifactKind)) {
            return false;
        }
        if ("create_chart".equals(changeKind)) {
            return true;
        }
        String normalized = normalize(prompt);
        if (containsAny(normalized, "tipo um painel", "tipo painel")
                && !containsAny(normalized, "dashboard")
                && !containsAny(normalized, "tabela", "formulario", "formulário", "lista", "listagem")) {
            return true;
        }
        AgenticAuthoringVisualizationDecision visualizationDecision =
                llmIntent == null ? null : llmIntent.visualizationDecision();
        if (visualizationDecision != null
                && "single_chart".equals(valueOrDefault(visualizationDecision.layoutKind(), ""))
                && "praxis-chart".equals(valueOrDefault(visualizationDecision.primaryComponent(), ""))) {
            return true;
        }
        return containsAny(normalized, "apenas", "somente", "so ", "só ")
                && containsAny(normalized, "grafico", "graficos", "chart", "charts");
    }

    private boolean hasPageWidgets(JsonNode currentPage) {
        return currentPage != null
                && currentPage.path("widgets").isArray()
                && !currentPage.path("widgets").isEmpty();
    }

    private String sharedRuleAuthoringAssistantMessage(AgenticAuthoringCandidate selectedCandidate) {
        String resourcePath = selectedCandidate == null ? "" : valueOrDefault(selectedCandidate.resourcePath(), "").trim();
        if (!resourcePath.isBlank()) {
            return "Esse pedido parece ser uma regra compartilhada do dominio, nao apenas uma mudanca visual da tela. Use /api/praxis/config/domain-rules/intake para registrar a intencao, /api/praxis/config/domain-rules/simulations para simulacao de impacto e cobertura, e siga por revisao e publicacao antes de aplicar qualquer materializacao.";
        }
        return "Esse pedido parece ser uma regra compartilhada do dominio, nao apenas uma mudanca visual da tela. Primeiro confirme o recurso de negocio e entao use /api/praxis/config/domain-rules/intake e /api/praxis/config/domain-rules/simulations para seguir por simulacao, revisao e publicacao com governanca.";
    }

    private JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                || candidates == null || candidates.isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis-agentic-authoring-api-catalog-grounding.v1",
                "questionType", "api_catalog_grounding",
                "candidateApis", candidates));
    }

    private String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, usableCandidates);
        }
        if (usableCandidates.isEmpty()) {
            return "Ainda nao encontrei uma fonte de negocio confiavel para esse tema. Posso procurar melhor no catalogo ou voce pode me dizer qual dominio devo usar.";
        }
        if (isConsultativeDomainQuestion(prompt)) {
            if (isApiCatalogResourceListPrompt(prompt)) {
                return apiCatalogResourceListAssistantMessage(usableCandidates);
            }
            if (!isApiOperationCatalogQuestion(prompt)) {
                return "Encontrei algumas fontes de dados relacionadas ao que voce perguntou. Posso comparar para que serve cada uma, explicar os campos disponiveis e recomendar telas sem criar tela nem alterar regra agora.";
            }
        }
        if (containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            return "Encontrei a fonte de negocio para essa pergunta. Posso te responder sobre os campos e cuidados de governanca sem criar tela nem alterar regra agora.";
        }
        if (isApiOperationCatalogQuestion(prompt)) {
            return "Encontrei a fonte de negocio e posso verificar quais operacoes fazem sentido para ela. Vou manter isso como consulta, sem alterar a aplicacao.";
        }
        if (containsAny(prompt, "filtro", "filtros", "filtrar", "filter")) {
            return "Encontrei a fonte de negocio e posso olhar os filtros e recortes disponiveis para ela. Nao vou criar nada ainda; primeiro respondo a consulta.";
        }
        if (isConsultativeDomainQuestion(prompt)) {
            return "Encontrei algumas fontes de dados que podem responder isso. Posso comparar as opcoes em linguagem simples antes de criar qualquer tela ou regra.";
        }

        String endpoints = usableCandidates.stream()
                .limit(4)
                .map(candidate -> valueOrDefault(candidate.reason(), "fonte de negocio do catalogo"))
                .reduce((left, right) -> left + "; " + right)
                .orElse("fonte de negocio do catalogo");
        String message = "Encontrei algumas fontes de negocio candidatas: " + endpoints;
        if (containsAny(prompt, "devo usar", "melhor", "recomenda", "recomende", "dashboard", "tabela")) {
            message += ". Posso te ajudar a escolher a que melhor representa o objetivo antes de criar a tela.";
        }
        return message;
    }

    private String apiCatalogResourceListAssistantMessage(List<AgenticAuthoringCandidate> candidates) {
        List<String> labels = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .limit(4)
                .map(candidate -> {
                    String reason = valueOrDefault(candidate.reason(), "");
                    return isHumanCatalogReason(reason) ? reason : candidateLabel(candidate);
                })
                .map(this::humanizeCatalogCandidate)
                .filter(label -> !label.isBlank())
                .distinct()
                .toList();
        if (labels.isEmpty()) {
            return "Encontrei fontes de negocio candidatas para esse tema, mas ainda preciso abrir os detalhes governados antes de recomendar uma delas.";
        }
        return "Encontrei " + labels.size() + " fonte" + (labels.size() == 1 ? "" : "s")
                + " de dados relacionada" + (labels.size() == 1 ? "" : "s")
                + ": " + humanJoin(labels) + ". Posso comparar para que serve cada uma, explicar os campos disponiveis ou usar a mais aderente para montar uma tela. Vou manter isso como consulta ate voce pedir para criar algo.";
    }

    private boolean isApiOperationCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksOperation = containsAny(normalized,
                "action", "actions", "acao", "acoes", "ação", "ações",
                "operacao", "operacoes", "operação", "operações",
                "permite", "permitidas", "permitidos",
                "criar registro", "editar registro", "alterar registro", "excluir registro",
                "salvar", "atualizar", "remover", "submeter");
        boolean asksRecommendedScreen = containsAny(normalized,
                "tela", "telas", "pagina", "paginas", "página", "páginas",
                "dashboard", "painel", "recomenda criar", "recomende criar", "que telas");
        boolean asksDataCatalog = containsAny(normalized,
                "quais dados", "que dados", "dados existem", "fontes", "recursos",
                "o que posso consultar", "posso consultar", "dados disponiveis", "dados disponíveis");
        return asksOperation && !asksRecommendedScreen && !asksDataCatalog;
    }

    private boolean isHumanCatalogReason(String reason) {
        String normalized = normalize(reason);
        return !normalized.isBlank()
                && !containsAny(normalized,
                "evidence",
                "fallback",
                "weak lexical",
                "api metadata",
                "api_metadata",
                "semantic retrieval",
                "semantic_retrieval",
                "retrieval",
                "domain catalog",
                "domain_catalog",
                "grounded",
                "resource selection",
                "provenance");
    }

    private String humanizeCatalogCandidate(String value) {
        String text = valueOrDefault(value, "")
                .replace("/api/", "")
                .replace("vw-", "")
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private String platformCapabilityAssistantMessage(String prompt, List<AgenticAuthoringCandidate> candidates) {
        StringBuilder message = new StringBuilder(
                "Aqui voce pode conversar comigo em linguagem natural para explorar o dominio, escolher componentes governados e criar paginas, dashboards, tabelas, graficos, formularios ou composicoes como abas e fluxos por etapas.");
        if (containsAny(prompt, "formulario", "formulário", "livremente", "predefinido", "predefinidos")) {
            message.append(" Formularios nao precisam ser escolhidos como um template fixo: voce descreve o processo, mas os campos e a acao final precisam ser confirmados por dados, acoes e governanca antes de salvar.");
        } else if (containsAny(prompt, "painel", "dashboard", "administrativo", "admin")) {
            message.append(" Para um painel administrativo, normalmente eu ajudo a escolher a fonte certa, sugerir indicadores, graficos, filtros e detalhes, e so materializo quando houver uma direcao confirmada.");
        }
        if (candidates != null && !candidates.isEmpty()) {
            message.append(" Tambem posso usar as fontes de negocio encontradas para recomendar caminhos mais concretos antes de criar qualquer coisa.");
        }
        return message.toString();
    }

    private String ambiguousResourceAssistantMessage(String artifactKind, List<AgenticAuthoringCandidate> candidates) {
        String artifactLabel = switch (artifactKind) {
            case "dashboard" -> "dashboard";
            case "table" -> "tabela";
            case "form" -> "formulario";
            case "page" -> "pagina";
            default -> "tela";
        };
        String options = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .limit(4)
                .map(candidate -> candidateLabel(candidate) + " - " + candidateDescription(candidate, artifactKind))
                .reduce((left, right) -> left + "; " + right)
                .orElse("opcoes do catalogo de APIs");
        return "Encontrei mais de uma fonte de dados possivel para este " + artifactLabel + ". "
                + "Escolha a fonte que melhor representa o recorte de negocio antes de gerar a pre-visualizacao. "
                + "Opcoes encontradas: " + options + ".";
    }

    private String missingResourceAssistantMessage(String artifactKind) {
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            return "Consigo montar esse painel, mas ainda preciso escolher a fonte de dados. "
                    + "Para graficos, normalmente faz sentido usar uma API analitica ou uma colecao com campos numericos. "
                    + "Posso buscar opcoes no catalogo de APIs ou voce pode informar o dominio que quer visualizar.";
        }
        if ("table".equals(artifactKind)) {
            return "Consigo criar a tabela, mas preciso saber qual recurso de negocio deve alimentar as linhas. "
                    + "Posso buscar colecoes disponiveis no catalogo de APIs ou voce pode informar o dominio desejado.";
        }
        if ("form".equals(artifactKind)) {
            return "Consigo criar o formulario, mas preciso escolher qual operacao de negocio ele deve executar. "
                    + "Posso buscar APIs de criacao no catalogo ou voce pode informar o recurso que quer cadastrar.";
        }
        return "Consigo ajudar, mas ainda falta escolher o recurso de negocio. "
                + "Posso buscar opcoes reais no catalogo de APIs ou voce pode informar o dominio que quer usar.";
    }

    private List<AgenticAuthoringQuickReply> quickReplies(
            String effectivePrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate,
            List<String> questions,
            List<AgenticAuthoringCandidate> candidates,
            boolean answeredBareDomainClarification) {
        if (gate.messages().contains("assistant-choice-confirmation-required")) {
            return assistantChoiceQuickReplies(effectivePrompt, questions.isEmpty() ? "" : questions.get(0));
        }
        if (isDashboardFilterConnectionRequest(operationKind, artifactKind, changeKind, selectedCandidate)
                && !"eligible".equals(gate.status())) {
            return dashboardFilterConnectionQuickReplies(effectivePrompt, prompt, selectedCandidate);
        }
        if (selectedCandidate != null
                && "clarification_required".equals(gate.status())
                && gate.messages().contains("resource-candidate-ambiguous")) {
            return revisionQuickReplies(effectivePrompt);
        }
        if (isConsultativeDashboardWithSelectedCandidate(operationKind, artifactKind, selectedCandidate)) {
            return dashboardExplorationQuickReplies(effectivePrompt, selectedCandidate);
        }
        if ("component".equals(artifactKind) && isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityQuickReplies(effectivePrompt);
        }
        if (!"explore".equals(operationKind) && !"clarification_required".equals(gate.status())) {
            return List.of();
        }
        if (gate.messages().contains("resource-candidate-required")) {
            return resourceDiscoveryQuickReplies(effectivePrompt, artifactKind);
        }
        if (!"explore".equals(operationKind)
                && gate.messages().contains("intent-confirmation-required")
                && !questions.isEmpty()) {
            return confirmationQuickReplies(effectivePrompt, questions.get(0));
        }
        if (answeredBareDomainClarification
                && gate.messages().contains("intent-confirmation-required")
                && !questions.isEmpty()) {
            return confirmationQuickReplies(effectivePrompt, questions.get(0));
        }
        if (gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null
                && !"api_catalog".equals(artifactKind)) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if ("explore".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate == null
                && candidates != null
                && !candidates.isEmpty()) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if ("api_catalog".equals(artifactKind)) {
            if (isConsultativePlatformCapabilityQuestion(prompt)) {
                return platformCapabilityQuickReplies(effectivePrompt);
            }
            if (isLiteralApiCatalogResourceListPrompt(prompt) && candidates != null && !candidates.isEmpty()) {
                return candidateResourceQuickReplies(effectivePrompt, candidates);
            }
            if (selectedCandidate == null && candidates != null && !candidates.isEmpty()) {
                if (isApiCatalogCandidateChoicePrompt(prompt) || isLiteralApiCatalogResourceListPrompt(prompt)) {
                    return candidateResourceQuickReplies(effectivePrompt, candidates);
                }
                return apiCatalogQuickReplies(effectivePrompt, prompt, candidates.get(0));
            }
            return apiCatalogQuickReplies(effectivePrompt, prompt, selectedCandidate);
        }
        if ("explore".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null) {
            return dashboardExplorationQuickReplies(effectivePrompt, selectedCandidate);
        }
        if ("clarification_required".equals(gate.status())) {
            return revisionQuickReplies(effectivePrompt);
        }
        return List.of(
                new AgenticAuthoringQuickReply(
                        "dashboard-suggestion",
                        "suggestion",
                        "Dashboard",
                        "Crie um dashboard com KPIs, grafico e tabela de detalhes."),
                new AgenticAuthoringQuickReply(
                        "form-suggestion",
                        "suggestion",
                        "Formulario",
                        "Crie um formulario com apenas os campos necessarios para o processo de negocio."),
                new AgenticAuthoringQuickReply(
                        "master-detail-suggestion",
                        "suggestion",
                        "Master detail",
                        "Crie uma pagina master-detail com uma lista de resumo e uma area de detalhe vinculada."));
    }

    private List<AgenticAuthoringQuickReply> governedLlmQuickReplies(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate) {
        if (llmIntent == null || llmIntent.quickReplies() == null || llmIntent.quickReplies().isEmpty()) {
            return List.of();
        }
        ObjectNode selectedCandidateHints = selectedCandidate == null
                ? null
                : dashboardContextHints("", selectedCandidate);
        return llmIntent.quickReplies().stream()
                .filter(Objects::nonNull)
                .filter(this::isUsableLlmQuickReply)
                .map(reply -> enrichLlmQuickReply(reply, selectedCandidateHints))
                .limit(4)
                .toList();
    }

    private boolean isUsableLlmQuickReply(AgenticAuthoringQuickReply reply) {
        String label = valueOrDefault(reply.label(), "").trim();
        String prompt = valueOrDefault(reply.prompt(), "").trim();
        return !valueOrDefault(reply.id(), "").trim().isBlank()
                && !valueOrDefault(reply.kind(), "").trim().isBlank()
                && !label.isBlank()
                && !prompt.isBlank()
                && !containsTechnicalAddress(label)
                && !containsTechnicalAddress(valueOrDefault(reply.description(), ""));
    }

    private AgenticAuthoringQuickReply enrichLlmQuickReply(
            AgenticAuthoringQuickReply reply,
            ObjectNode selectedCandidateHints) {
        if (selectedCandidateHints == null) {
            return reply;
        }
        ObjectNode mergedHints = selectedCandidateHints.deepCopy();
        JsonNode replyHints = reply.contextHints();
        if (replyHints != null && replyHints.isObject()) {
            replyHints.fields().forEachRemaining(entry -> {
                if (!isTechnicalContextHintKey(entry.getKey())) {
                    mergedHints.set(entry.getKey(), entry.getValue());
                }
            });
        }
        return new AgenticAuthoringQuickReply(
                reply.id(),
                reply.kind(),
                reply.label(),
                reply.prompt(),
                reply.description(),
                reply.icon(),
                reply.tone(),
                mergedHints);
    }

    private boolean shouldUseLlmAuthoredQuickReplies(
            List<AgenticAuthoringQuickReply> llmAuthoredQuickReplies,
            AgenticAuthoringGateResult gate,
            boolean contextHintSelectionApplied,
            boolean promotedAssistantChoiceToClarification,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (isApiCatalogQuestion(operationKind, artifactKind, changeKind)) {
            return selectedCandidate != null
                    && hasTrustedSelectionEvidence(selectedCandidate)
                    && llmAuthoredQuickReplies != null
                    && !llmAuthoredQuickReplies.isEmpty();
        }
        if (isConsultativeDashboardWithSelectedCandidate(operationKind, artifactKind, selectedCandidate)) {
            return llmAuthoredQuickReplies != null
                    && !llmAuthoredQuickReplies.isEmpty()
                    && llmAuthoredQuickReplies.stream()
                    .noneMatch(reply -> valueOrDefault(reply.id(), "").startsWith("llm-"));
        }
        return llmAuthoredQuickReplies != null
                && !llmAuthoredQuickReplies.isEmpty()
                && gate != null
                && !"eligible".equals(gate.status())
                && !contextHintSelectionApplied
                && !promotedAssistantChoiceToClarification;
    }

    private boolean isApiCatalogQuestion(String operationKind, String artifactKind, String changeKind) {
        return ("explore".equals(operationKind) || "explain".equals(operationKind))
                && "api_catalog".equals(artifactKind)
                && ("answer_api_catalog_question".equals(changeKind)
                || "answer_catalog_question".equals(changeKind)
                || "api_catalog_followup".equals(changeKind));
    }

    private boolean contextHintSelectionApplied(
            AgenticAuthoringCandidate contextHintCandidate,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate) {
        return contextHintCandidate != null
                && selectedCandidate != null
                && gate != null
                && "eligible".equals(gate.status());
    }

    private boolean isTechnicalContextHintKey(String key) {
        return "technicalDetails".equals(key)
                || "resourcePath".equals(key)
                || "submitUrl".equals(key)
                || "schemaUrl".equals(key)
                || "submitMethod".equals(key);
    }

    private boolean containsTechnicalAddress(String value) {
        String normalized = valueOrDefault(value, "").toLowerCase(Locale.ROOT);
        return normalized.contains("/api/")
                || normalized.contains("/schemas/")
                || normalized.contains("http://")
                || normalized.contains("https://");
    }

    private boolean isConsultativeDashboardWithSelectedCandidate(
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return ("explore".equals(operationKind) || "compose".equals(operationKind))
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null;
    }

    private boolean isDashboardFilterConnectionRequest(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!"dashboard".equals(artifactKind) || selectedCandidate == null) {
            return false;
        }
        String normalizedOperation = normalize(valueOrDefault(operationKind, ""));
        String normalizedChange = normalize(valueOrDefault(changeKind, ""));
        return "connect".equals(normalizedOperation)
                || normalizedChange.contains("filter")
                || normalizedChange.contains("filtro")
                || normalizedChange.contains("recorte")
                || normalizedChange.contains("dimension");
    }

    private List<AgenticAuthoringQuickReply> dashboardFilterConnectionQuickReplies(
            String effectivePrompt,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = dashboardContextHints(effectivePrompt, selectedCandidate);
        String normalizedPrompt = normalize(valueOrDefault(prompt, ""));
        boolean askedTemporalCut = containsAny(normalizedPrompt, "period", "tempo", "data", "mes", "ano", "competencia");
        boolean askedBusinessCut = containsAny(normalizedPrompt, "grupo", "categoria", "dimensao");
        String combinedLabel = askedTemporalCut && askedBusinessCut ? "Usar periodo e dimensao" : "Usar filtros e recortes";
        String combinedDecision = askedTemporalCut && askedBusinessCut
                ? "adicionar filtros de periodo e dimensao ao dashboard"
                : "adicionar filtros e recortes ao dashboard";
        String temporalLabel = askedTemporalCut ? "So periodo" : "Filtro temporal";
        String businessLabel = askedBusinessCut ? "So dimensao" : "Dimensao de negocio";
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm-dashboard-filters",
                        "confirm",
                        combinedLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, combinedDecision),
                        "Conecta controles antes da visualizacao usando a fonte semantica selecionada.",
                        "filter_alt",
                        "analytics",
                        contextHints.deepCopy()),
                new AgenticAuthoringQuickReply(
                        "dashboard-filter-period",
                        "confirm",
                        temporalLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "adicionar filtro temporal ao dashboard"),
                        "Permite recortar a analise por datas, ciclos ou periodos do dominio.",
                        "calendar_month",
                        "neutral",
                        contextHints.deepCopy()),
                new AgenticAuthoringQuickReply(
                        "dashboard-filter-dimension",
                        "confirm",
                        businessLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "adicionar filtro por dimensao de negocio ao dashboard"),
                        "Permite recortar a analise por uma dimensao semantica do dominio.",
                        "category",
                        "neutral",
                        contextHints.deepCopy()),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        "",
                        null,
                        null,
                        null,
                        null));
    }

    private List<AgenticAuthoringQuickReply> platformCapabilityQuickReplies(String effectivePrompt) {
        String normalized = normalize(effectivePrompt);
        if (containsAny(normalized,
                "exportar", "selecionada", "selecionadas", "linhas selecionadas", "botao", "botão", "tabela")) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-explain",
                            "suggestion",
                            "Ver configuração",
                            "Explique os passos para habilitar seleção, ação em massa e exportação das linhas marcadas em uma tabela Praxis.",
                            "Mostra como a tabela deve combinar seleção, toolbar e ação governada.",
                            "download",
                            "neutral",
                            quickReplyPresentation(
                                    "Boa para entender o comportamento antes de alterar a tela.",
                                    "Retorna orientação específica de tabela, sem buscar APIs de domínio por palavras soltas.",
                                    "Clique para detalhar a configuração.")),
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-apply",
                            "suggestion",
                            "Aplicar na tabela",
                            "Na tabela selecionada, prepare a alteração para exportar apenas as linhas marcadas.",
                            "Usa o componente selecionado quando houver uma tabela ativa na página.",
                            "table_chart",
                            "resource",
                            quickReplyPresentation(
                                    "Boa quando você já está com a tabela correta selecionada.",
                                    "Retorna uma proposta de alteração governada para revisão.",
                                    "Clique depois de selecionar a tabela no canvas.")),
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-backend",
                            "suggestion",
                            "Ver dados necessários",
                            "Quais dados ou ação de backend preciso confirmar para exportar as linhas selecionadas dessa tabela?",
                            "Separa a capacidade do componente da integração real com dados.",
                            "rule",
                            "analytics",
                            quickReplyPresentation(
                                    "Boa para validar o que falta antes de materializar.",
                                    "Retorna os pontos de dados e ação que precisam ser governados.",
                                    "Clique para revisar os pré-requisitos.")));
        }
        return List.of(
                new AgenticAuthoringQuickReply(
                        "platform-create-admin-dashboard",
                        "suggestion",
                        "Painel administrativo",
                        "Me ajude a criar um painel administrativo: primeiro recomende dados, componentes e recortes antes da pre-visualizacao.",
                        "Explora fontes, metricas, graficos, filtros e detalhes antes de materializar.",
                        "dashboard_customize",
                        "analytics",
                        quickReplyPresentation(
                                "Boa para administrar e acompanhar processos com indicadores e detalhes.",
                                "Retorna uma recomendacao de composicao governada antes da criacao.",
                                "Clique para pedir uma recomendacao de painel.")),
                new AgenticAuthoringQuickReply(
                        "platform-create-form",
                        "suggestion",
                        "Formulario governado",
                        "Quero criar um formulario. Me ajude a escolher o processo, campos e acao governada antes de salvar.",
                        "Explica como transformar uma intencao em formulario sem inventar regra de negocio.",
                        "dynamic_form",
                        "resource",
                        quickReplyPresentation(
                                "Boa para cadastros, solicitacoes e edicoes guiadas.",
                                "Retorna perguntas e opcoes para groundar campos e acao de salvar.",
                                "Clique para iniciar a conversa de formulario.")),
                new AgenticAuthoringQuickReply(
                        "platform-explore-components",
                        "suggestion",
                        "Ver possibilidades",
                        "Quais componentes governados fazem sentido para o dominio atual e para que serve cada um?",
                        "Mostra opcoes como tabela, grafico, formulario, abas e fluxos quando estiverem disponiveis.",
                        "widgets",
                        "neutral",
                        quickReplyPresentation(
                                "Boa para entender o que pode ser criado antes de escolher uma tela.",
                                "Retorna uma explicacao em linguagem simples sobre componentes e limites.",
                                "Clique para pedir o guia de componentes.")));
    }

    private ObjectNode dashboardContextHints(
            String effectivePrompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", selectedCandidate.resourcePath());
        contextHints.put("submitUrl", selectedCandidate.submitUrl());
        contextHints.put("operation", selectedCandidate.operation());
        contextHints.put("schemaUrl", selectedCandidate.schemaUrl());
        contextHints.put("submitMethod", selectedCandidate.submitMethod());
        contextHints.set("technicalDetails", technicalDetails(selectedCandidate));
        AgenticAuthoringDomainCatalogHints.enrich(
                contextHints,
                selectedCandidate,
                null,
                effectivePrompt,
                domainCatalogServiceKey);
        return contextHints;
    }

    private List<AgenticAuthoringQuickReply> dashboardExplorationQuickReplies(
            String effectivePrompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = dashboardContextHints(effectivePrompt, selectedCandidate);
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm-dashboard",
                        "confirm",
                        "Gerar previa governada",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "criar dashboard com " + candidateLabel(selectedCandidate)),
                        "Cria uma pre-visualizacao governada antes de salvar ou materializar.",
                        "dashboard_customize",
                        "analytics",
                        withQuickReplyPresentation(
                                contextHints,
                                "Indicada quando a fonte de dados ja parece correta e voce quer ver a primeira composicao.",
                                "Retorna uma previa com KPIs, graficos e componentes conectados ao recurso escolhido.",
                                "Clique para materializar uma proposta governada antes de salvar.")),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Refinar pedido",
                        effectivePrompt,
                        "Ajustar objetivo, dados, metricas ou escopo antes de gerar a pre-visualizacao.",
                        "tune",
                        "neutral",
                        quickReplyPresentation(
                                "Indicada quando ainda falta definir indicadores, filtros, granularidade ou publico do painel.",
                                "Retorna para a conversa para melhorar o pedido sem perder o contexto descoberto.",
                                "Clique para reformular o pedido com mais detalhes.")),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        "",
                        "Encerra esta sugestao sem gerar previa ou alterar a pagina atual.",
                        "close",
                        "neutral",
                        quickReplyPresentation(
                                "Indicada quando a recomendacao nao combina com o objetivo atual.",
                                "Retorna ao estado atual da pagina sem aplicar mudancas.",
                                "Clique para descartar esta trilha de criacao.")));
    }

    private boolean hasExplicitContextHintResource(AgenticAuthoringCandidate selectedCandidate) {
        return selectedCandidate != null
                && selectedCandidate.evidence() != null
                && selectedCandidate.evidence().contains("quick-reply-context");
    }

    private List<AgenticAuthoringQuickReply> candidateResourceQuickReplies(
            String effectivePrompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> visibleCandidates = candidates.stream()
                .limit(6)
                .toList();
        Map<String, Long> resourcePathCounts = visibleCandidates.stream()
                .collect(Collectors.groupingBy(
                        candidate -> valueOrDefault(candidate.resourcePath(), ""),
                        Collectors.counting()));
        return visibleCandidates.stream()
                .map(candidate -> {
                    ObjectNode contextHints = objectMapper.createObjectNode();
                    contextHints.put("resourcePath", candidate.resourcePath());
                    contextHints.put("submitUrl", candidate.submitUrl());
                    contextHints.put("operation", candidate.operation());
                    contextHints.put("schemaUrl", candidate.schemaUrl());
                    contextHints.put("submitMethod", candidate.submitMethod());
                    contextHints.set("technicalDetails", technicalDetails(candidate));
                    contextHints.set("presentation", candidatePresentation(candidate, null));
                    AgenticAuthoringDomainCatalogHints.enrich(
                            contextHints,
                            candidate,
                            null,
                            effectivePrompt,
                            domainCatalogServiceKey);
                    boolean duplicatedResourcePath = resourcePathCounts.getOrDefault(
                            valueOrDefault(candidate.resourcePath(), ""),
                            0L) > 1L;
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate, duplicatedResourcePath),
                            "suggestion",
                            candidateLabel(candidate),
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "usar " + candidateLabel(candidate)),
                            candidateFriendlyDescription(candidate, null),
                            candidateIcon(candidate),
                            candidateTone(candidate),
                            contextHints);
                })
                .toList();
    }

    private List<AgenticAuthoringQuickReply> resourceDiscoveryQuickReplies(
            String effectivePrompt,
            String artifactKind) {
        String resolvedArtifactKind = artifactKind == null ? "unknown" : artifactKind;
        String query = switch (resolvedArtifactKind) {
            case "dashboard", "page" -> "Quais APIs analiticas ou colecoes com campos numericos podem alimentar este painel de graficos?";
            case "table" -> "Quais APIs de colecao podem alimentar esta tabela?";
            case "form" -> "Quais APIs de criacao podem alimentar este formulario?";
            default -> "Quais APIs disponiveis podem alimentar esta tela?";
        };
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("tool", "searchApiResources");
        contextHints.put("artifactKind", resolvedArtifactKind);
        contextHints.put("retrievalQuery", query);

        return List.of(
                new AgenticAuthoringQuickReply(
                        "search-api-resources",
                        "suggestion",
                        "Buscar APIs",
                        query,
                        "Consulta o catalogo para encontrar recursos reais antes de gerar a tela.",
                        "manage_search",
                        "resource",
                        contextHints),
                new AgenticAuthoringQuickReply(
                        "describe-business-domain",
                        "revise",
                        "Informar dominio",
                        effectivePrompt,
                        "Explique qual area de negocio, entidade ou indicador deve alimentar a tela.",
                        "edit_note",
                        "neutral",
                        null),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        "",
                        null,
                        null,
                        null,
                        null));
    }

    private List<AgenticAuthoringQuickReply> apiCatalogQuickReplies(
            String effectivePrompt,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = selectedCandidate == null
                ? null
                : dashboardContextHints(effectivePrompt, selectedCandidate);
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (!containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            replies.add(new AgenticAuthoringQuickReply(
                        "api-create-dashboard",
                        contextHints == null ? "suggestion" : "confirm",
                        "Criar dashboard",
                        contextHints == null
                                ? "Crie um dashboard usando a API recomendada."
                                : AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "criar dashboard com " + candidateLabel(selectedCandidate)),
                        "Use quando voce ja quer transformar a fonte recomendada em uma primeira versao de painel governado.",
                        "dashboard_customize",
                        "analytics",
                        withQuickReplyPresentation(
                                contextHints,
                                "Boa para sair da descoberta e materializar um painel inicial.",
                                "Retorna uma pre-visualizacao com KPIs, graficos e componentes conectados ao recorte escolhido.",
                                "Clique para pedir a primeira composicao do dashboard.")));
        }
        replies.add(new AgenticAuthoringQuickReply(
                        "api-show-schema",
                        "suggestion",
                        "Ver campos",
                        "Quais campos existem na fonte recomendada?",
                        "Use quando voce quer entender quais campos, dimensoes e medidas podem virar filtros, colunas ou eixos.",
                        "view_list",
                        "resource",
                        withQuickReplyPresentation(
                                contextHints == null ? null : contextHints.deepCopy(),
                                "Boa para avaliar se a fonte tem os dados que voce precisa antes de criar a tela.",
                                "Retorna os campos principais, tipos e pistas de uso para formularios, tabelas e graficos.",
                                "Clique para pedir uma explicacao dos campos disponiveis.")));
        replies.add(new AgenticAuthoringQuickReply(
                        "api-show-actions",
                        "suggestion",
                        "Ver ações",
                        "Quais acoes e filtros essa fonte permite?",
                        "Use quando voce quer saber o que a fonte permite fazer: filtrar, consultar, criar, atualizar ou detalhar.",
                        "rule",
                        "resource",
                        withQuickReplyPresentation(
                                contextHints == null ? null : contextHints.deepCopy(),
                                "Boa para decidir a interacao certa da pagina, como filtros, drill-downs ou acoes de linha.",
                                "Retorna acoes, filtros e capacidades expostas pelo catalogo governado.",
                                "Clique para explorar as acoes suportadas pela fonte.")));
        return List.copyOf(replies);
    }

    private boolean isLiteralApiCatalogResourceListPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais apis",
                "que apis",
                "apis relacionadas",
                "apis disponiveis",
                "fontes de dados",
                "fontes disponiveis",
                "fontes relacionadas");
    }

    private ObjectNode quickReplyPresentation(String bestFor, String returns, String nextStep) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode presentation = contextHints.putObject("presentation");
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return contextHints;
    }

    private ObjectNode withQuickReplyPresentation(
            ObjectNode contextHints,
            String bestFor,
            String returns,
            String nextStep) {
        ObjectNode enriched = contextHints == null ? objectMapper.createObjectNode() : contextHints;
        ObjectNode presentation = enriched.putObject("presentation");
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return enriched;
    }

    private String conciseAssistantMessage(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.length() <= MAX_ASSISTANT_MESSAGE_CHARS) {
            return assistantMessage;
        }
        int cutoff = assistantMessage.lastIndexOf('.', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        if (cutoff < 240) {
            cutoff = assistantMessage.lastIndexOf(' ', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        }
        if (cutoff < 240) {
            cutoff = MAX_ASSISTANT_MESSAGE_CHARS - 1;
        }
        return assistantMessage.substring(0, cutoff).trim() + "...";
    }

    private String quickReplyId(AgenticAuthoringCandidate candidate, boolean includeOperation) {
        String base = "resource-" + normalize(candidate.resourcePath())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (!includeOperation) {
            return base;
        }
        String operation = valueOrDefault(candidate.operation(), "operation");
        String submitUrl = valueOrDefault(candidate.submitUrl(), candidate.resourcePath());
        String suffix = normalize(operation + "-" + submitUrl)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return suffix.isBlank() ? base : base + "-" + suffix;
    }

    private String candidateLabel(AgenticAuthoringCandidate candidate) {
        String path = candidate.resourcePath();
        if (path == null || path.isBlank()) {
            return "Recurso";
        }
        String lastSegment = path.replaceAll("/+$", "");
        int slash = lastSegment.lastIndexOf('/');
        lastSegment = slash >= 0 ? lastSegment.substring(slash + 1) : lastSegment;
        return lastSegment
                .replace("vw-", "")
                .replace("-", " ");
    }

    private String candidateDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        return switch (valueOrDefault(artifactKind, "")) {
            case "dashboard", "page" -> "Fonte candidata para alimentar o painel.";
            case "table" -> "Fonte candidata para alimentar a tabela.";
            case "form" -> "Operação candidata para o formulário.";
            default -> "Fonte candidata encontrada no catálogo.";
        };
    }

    private String candidateFriendlyDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        String resolvedKind = valueOrDefault(artifactKind, "");
        if ("dashboard".equals(resolvedKind) || "page".equals(resolvedKind)) {
            return "Indicada para montar um painel. Retorna dados que podem virar listas, indicadores e gráficos quando o schema confirmar os recortes.";
        }
        if ("table".equals(resolvedKind)) {
            return "Indicada quando você quer uma lista navegável. Retorna registros para tabela, filtros e detalhes.";
        }
        if ("form".equals(resolvedKind)) {
            return "Indicada quando você precisa cadastrar ou atualizar dados. Retorna uma operação governada para o formulário.";
        }
        return "Opção encontrada no catálogo semântico. Use para explorar quais dados ela oferece antes de criar a tela.";
    }

    private ObjectNode candidatePresentation(AgenticAuthoringCandidate candidate, String artifactKind) {
        ObjectNode presentation = objectMapper.createObjectNode();
        String resolvedKind = valueOrDefault(artifactKind, "");
        if ("dashboard".equals(resolvedKind) || "page".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para painéis com acompanhamento de registros, gráficos e drill-down quando o schema confirmar os recortes.");
            presentation.put("returns", "Retorna dados de negócio que podem alimentar cards, listas e gráficos materializados por schema.");
            presentation.put("nextStep", "Clique para usar esta fonte como recorte inicial da pré-visualização.");
            return presentation;
        }
        if ("table".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para consultar, filtrar e comparar registros em uma lista.");
            presentation.put("returns", "Retorna coleções navegáveis com campos para colunas, busca e detalhes.");
            presentation.put("nextStep", "Clique para criar a tabela usando esta fonte.");
            return presentation;
        }
        if ("form".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para capturar ou atualizar informações com governança.");
            presentation.put("returns", "Retorna a operação que o formulário deve executar ao salvar.");
            presentation.put("nextStep", "Clique para usar esta operação no formulário.");
            return presentation;
        }
        presentation.put("bestFor", "Boa para explorar uma fonte semântica disponível no catálogo.");
        presentation.put("returns", "Retorna dados ou operações que podem ser materializados em componentes.");
        presentation.put("nextStep", "Clique para investigar esta opção no próximo passo.");
        return presentation;
    }

    private boolean shouldHideTechnicalAddresses(
            AgenticAuthoringIntentResolutionRequest request,
            String operationKind,
            String artifactKind,
            boolean llmRequiresGovernedAuthoring) {
        return !"api_catalog".equals(artifactKind)
                && !"explore".equals(operationKind)
                && !llmRequiresGovernedAuthoring
                && !"shared_rule_authoring".equals(requestedAuthoringFlow(request));
    }

    private List<AgenticAuthoringQuickReply> sanitizeQuickReplies(
            List<AgenticAuthoringQuickReply> quickReplies,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (quickReplies == null || quickReplies.isEmpty()) {
            return List.of();
        }
        return quickReplies.stream()
                .map(reply -> new AgenticAuthoringQuickReply(
                        reply.id(),
                        reply.kind(),
                        sanitizePresentationText(reply.label(), selectedCandidate, candidates),
                        sanitizePresentationText(reply.prompt(), selectedCandidate, candidates),
                        sanitizePresentationText(reply.description(), selectedCandidate, candidates),
                        reply.icon(),
                        reply.tone(),
                        reply.contextHints()))
                .toList();
    }

    private String sanitizePresentationText(
            String text,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = text;
        List<AgenticAuthoringCandidate> allCandidates = new ArrayList<>();
        if (selectedCandidate != null) {
            allCandidates.add(selectedCandidate);
        }
        if (candidates != null) {
            allCandidates.addAll(candidates);
        }
        for (AgenticAuthoringCandidate candidate : allCandidates.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                candidate -> valueOrDefault(candidate.resourcePath(), "") + "|" + valueOrDefault(candidate.submitUrl(), ""),
                                candidate -> candidate,
                                (left, right) -> left),
                        map -> map.values().stream()
                                .sorted(Comparator.comparingInt((AgenticAuthoringCandidate candidate) ->
                                        technicalAddressLength(candidate)).reversed())
                                .toList()))) {
            String label = candidateLabel(candidate);
            sanitized = replaceTechnicalAddress(sanitized, candidate.schemaUrl(), "Detalhes técnicos");
            sanitized = replaceTechnicalAddress(sanitized, candidate.submitUrl(), label);
            sanitized = replaceTechnicalAddress(sanitized, candidate.resourcePath(), label);
        }
        return sanitized;
    }

    private int technicalAddressLength(AgenticAuthoringCandidate candidate) {
        return Math.max(
                valueOrDefault(candidate.submitUrl(), "").length(),
                Math.max(
                        valueOrDefault(candidate.schemaUrl(), "").length(),
                        valueOrDefault(candidate.resourcePath(), "").length()));
    }

    private String replaceTechnicalAddress(String text, String address, String replacement) {
        String normalizedAddress = valueOrDefault(address, "").trim();
        String normalizedReplacement = valueOrDefault(replacement, "").trim();
        if (text == null
                || normalizedAddress.isBlank()
                || normalizedReplacement.isBlank()
                || !normalizedAddress.contains("/")) {
            return text;
        }
        String next = text.replaceAll(
                "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+" + Pattern.quote(normalizedAddress),
                normalizedReplacement);
        return next.replace(normalizedAddress, normalizedReplacement);
    }

    private ObjectNode technicalDetails(AgenticAuthoringCandidate candidate) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("resourcePath", valueOrDefault(candidate.resourcePath(), ""));
        details.put("submitUrl", valueOrDefault(candidate.submitUrl(), candidate.resourcePath()));
        details.put("submitMethod", valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT));
        details.put("operation", valueOrDefault(candidate.operation(), ""));
        details.put("schemaUrl", valueOrDefault(candidate.schemaUrl(), ""));
        return details;
    }

    private String candidateIcon(AgenticAuthoringCandidate candidate) {
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "edit_note";
        }
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "primary";
        }
        return "resource";
    }

    private List<AgenticAuthoringQuickReply> confirmationQuickReplies(String effectivePrompt, String question) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm",
                        "confirm",
                        "Sim, criar",
                        confirmationPromptFromQuestion(effectivePrompt, question)),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Quero ajustar",
                        effectivePrompt),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        ""));
    }

    private List<AgenticAuthoringQuickReply> assistantChoiceQuickReplies(String effectivePrompt, String question) {
        if (isDashboardTableChoiceQuestion(question)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "dashboard-completo",
                            "confirm",
                            "Dashboard completo",
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "criar um dashboard completo com resumo, graficos, filtros e tabela detalhada"),
                            "Inclui indicadores, graficos de status/valor, filtros e uma tabela para investigar os pedidos.",
                            "dashboard_customize",
                            "primary",
                            quickReplyPresentation(
                                    "Indicada quando voce quer acompanhar um processo em nivel executivo e operacional.",
                                    "Retorna uma pagina com resumo, graficos, filtros e detalhes conectados ao recurso escolhido.",
                                    "Clique para gerar uma previa governada mais completa.")),
                    new AgenticAuthoringQuickReply(
                            "tabela-filtravel",
                            "confirm",
                            "Apenas tabela filtravel",
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "criar apenas uma tabela filtravel com valores formatados, chips de status e acoes por linha"),
                            "Foca na listagem dos pedidos com colunas ricas, formatos, chips e acoes de investigacao.",
                            "table_chart",
                            "neutral",
                            quickReplyPresentation(
                                    "Indicada quando a prioridade e localizar, comparar e abrir detalhes dos pedidos rapidamente.",
                                    "Retorna uma tabela com filtros, valores formatados, status visual e acoes por item.",
                                    "Clique para gerar uma previa focada em listagem.")),
                    new AgenticAuthoringQuickReply(
                            "ajustar-pedido",
                            "revise",
                            "Ajustar pedido",
                            effectivePrompt,
                            "Permite acrescentar colunas, filtros, agrupamentos ou regras antes de gerar a previa.",
                            "tune",
                            "neutral",
                            quickReplyPresentation(
                                    "Indicada quando ainda falta algum detalhe de negocio ou formato.",
                                    "Mantem o contexto e permite refinar a solicitacao.",
                                    "Clique para continuar conversando antes da criacao.")));
        }
        return confirmationQuickReplies(effectivePrompt, question);
    }

    private List<AgenticAuthoringQuickReply> revisionQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Quero ajustar",
                        effectivePrompt),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        ""));
    }

    private String requestedAuthoringFlow(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        JsonNode domainCatalog = contextHints == null ? null : contextHints.path("domainCatalog");
        if (domainCatalog == null || domainCatalog.isMissingNode()) {
            return "";
        }
        return normalize(domainCatalog.path("recommendedAuthoringFlow").asText(""));
    }

    private String confirmationPromptFromQuestion(String effectivePrompt, String question) {
        String normalizedQuestion = question == null ? "" : question.trim().replaceAll("\\?+$", "");
        String directive = normalizedQuestion
                .replaceFirst("(?i)^posso\\s+criar\\s+", "Crie ")
                .replaceFirst("(?i)^can\\s+i\\s+create\\s+", "Create ");
        if (directive.isBlank() || directive.equals(normalizedQuestion)) {
            return AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, question);
        }
        return directive;
    }

    private boolean shouldPromoteAssistantChoiceToClarification(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringGateResult gate,
            String assistantMessage) {
        if (llmIntent == null
                || llmIntent.quickReplies() == null
                || !llmIntent.quickReplies().isEmpty()
                || gate == null
                || !"eligible".equals(gate.status())
                || assistantMessage == null
                || assistantMessage.isBlank()) {
            return false;
        }
        String normalized = normalize(assistantMessage);
        return normalized.contains("?")
                && (containsAny(normalized, "deseja", "prefere", "voce prefere", "você prefere", "quer que eu", "posso")
                || normalized.contains(" ou "))
                && containsAny(normalized, "dashboard", "tabela", "grafico", "filtro", "painel");
    }

    private boolean isDashboardTableChoiceQuestion(String question) {
        String normalized = normalize(question);
        return (normalized.contains("dashboard") || normalized.contains("painel"))
                && normalized.contains("tabela")
                && (normalized.contains(" ou ") || normalized.contains("apenas"));
    }

    private String formatCandidateOptions(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        return candidates.stream()
                .limit(3)
                .map(this::candidateLabel)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String humanJoin(List<String> values) {
        List<String> clean = values == null
                ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
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

    private boolean containsAny(String value, String... tokens) {
        String wordPaddedValue = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalize(token);
            if (requiresWholeWordMatch(normalizedToken)) {
                if (wordPaddedValue == null) {
                    wordPaddedValue = " " + value.replaceAll("[^a-z0-9]+", " ") + " ";
                }
                if (wordPaddedValue.contains(" " + normalizedToken + " ")) {
                    return true;
                }
                continue;
            }
            if (value.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresWholeWordMatch(String token) {
        return token.length() <= 4 && token.chars().allMatch(Character::isLetterOrDigit);
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        String normalized = submitUrl == null ? "" : submitUrl.toLowerCase(Locale.ROOT);
        return "post".equalsIgnoreCase(operation)
                && (normalized.endsWith("/stats/group-by")
                || normalized.endsWith("/stats/timeseries")
                || normalized.endsWith("/stats/distribution")
                || normalized.endsWith("/filter")
                || normalized.endsWith("/filter/cursor"));
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private String jsonText(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String valueOrUnknown(String value) {
        return valueOrDefault(value, "unknown");
    }

    private record CandidatePromptAlignment(AgenticAuthoringCandidate candidate, int score) {
    }
}
