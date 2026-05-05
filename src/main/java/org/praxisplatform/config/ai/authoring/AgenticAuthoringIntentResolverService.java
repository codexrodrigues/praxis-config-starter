package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringApiCatalogConversationService apiCatalogConversationService;
    private final AgenticAuthoringLlmIntentResolverService llmIntentResolverService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
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

    public AgenticAuthoringIntentResolverService(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog) {
        this(objectMapper, apiMetadataCandidateCatalog, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService) {
        this(objectMapper, apiMetadataCandidateCatalog, apiCatalogConversationService, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        this(objectMapper,
                apiMetadataCandidateCatalog,
                apiCatalogConversationService,
                llmIntentResolverService,
                componentCapabilitiesService,
                AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            String domainCatalogServiceKey) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.apiCatalogConversationService = apiCatalogConversationService;
        this.llmIntentResolverService = llmIntentResolverService;
        this.componentCapabilitiesService = componentCapabilitiesService;
        this.domainCatalogServiceKey = domainCatalogServiceKey;
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
        String effectivePrompt = hasLlmIntentResolver && !governedResourceConfirmation
                ? rawPrompt
                : turn.effectivePrompt();
        String prompt = normalize(effectivePrompt);
        String discoveryPrompt = normalize(turn.answeredPendingClarification() ? turn.effectivePrompt() : effectivePrompt);
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage(), request.selectedWidgetKey());
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), request.selectedWidgetKey());
        AgenticAuthoringKeywordFallbackResolution fallbackResolution =
                keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
        String operationKind = fallbackResolution.operationKind();
        String artifactKind = fallbackResolution.artifactKind();
        String changeKind = fallbackResolution.changeKind();
        boolean shouldResolveLlmIntent = hasLlmIntentResolver && !governedResourceConfirmation;
        List<AgenticAuthoringCandidate> candidates = shouldResolveLlmIntent
                ? discoverInitialCandidates(discoveryPrompt, artifactKind, target)
                : discoverCandidates(discoveryPrompt, artifactKind, target);
        candidates = withContextHintCandidates(request, candidates);
        AgenticAuthoringCandidate contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
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
        AgenticAuthoringLlmIntentResolution llmIntent = resolveLlmIntent(
                shouldResolveLlmIntent,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities,
                tenantId,
                userId,
                environment);
        JsonNode llmDiagnostics = llmDiagnostics(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities);
        boolean llmTreatsPendingAsContinuation = turn.answeredPendingClarification()
                && (isLlmFollowUpKind(llmIntent, "clarification_answer")
                || isLlmFollowUpKind(llmIntent, "refinement"));
        boolean llmTreatsPendingAsNewInstruction = turn.answeredPendingClarification()
                && isLlmFollowUpKind(llmIntent, "new_instruction");
        boolean llmSecondPassUsed = false;
        boolean deterministicFallbackApplied = !shouldResolveLlmIntent;
        boolean promotedExploratoryLlmToActionableFallback = false;
        boolean promotedExploratoryArtifactKindFromFallback = false;
        boolean preservedAnalyticalDashboardFallback = false;
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
                    ? discoverInitialCandidates(prompt, artifactKind, target)
                    : discoverCandidates(prompt, artifactKind, target);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
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
                    ? discoverInitialCandidates(prompt, artifactKind, target)
                    : discoverCandidates(prompt, artifactKind, target);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
        }
        if (llmIntent != null && llmIntent.resolved()) {
            String previousArtifactKind = artifactKind;
            if (shouldPreserveAnalyticalDashboardFallback(llmIntent, fallbackResolution)) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
                preservedAnalyticalDashboardFallback = true;
            } else if (shouldPromoteExploratoryLlmToActionableFallback(llmIntent, fallbackResolution)) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
                promotedExploratoryLlmToActionableFallback = true;
            } else {
                operationKind = valueOrUnknown(llmIntent.operationKind());
                artifactKind = valueOrUnknown(llmIntent.artifactKind());
                changeKind = valueOrUnknown(llmIntent.changeKind());
                if ("explore".equals(operationKind)
                        && "unknown".equals(artifactKind)
                        && "explore".equals(valueOrUnknown(fallbackResolution.operationKind()))
                        && !"unknown".equals(valueOrUnknown(fallbackResolution.artifactKind()))) {
                    artifactKind = fallbackResolution.artifactKind();
                    changeKind = fallbackResolution.changeKind();
                    promotedExploratoryArtifactKindFromFallback = true;
                }
            }
            String llmResourceSearchQuery = valueOrDefault(llmIntent.resourceSearchQuery(), "").trim();
            if (preservedAnalyticalDashboardFallback
                    || promotedExploratoryLlmToActionableFallback
                    || !Objects.equals(previousArtifactKind, artifactKind)
                    || !llmResourceSearchQuery.isBlank()) {
                List<AgenticAuthoringCandidate> refinedCandidates = new ArrayList<>(candidates);
                refinedCandidates.addAll(discoverCandidates(
                        llmResourceSearchQuery.isBlank() ? prompt : normalize(llmResourceSearchQuery),
                        artifactKind,
                        target));
                refinedCandidates.addAll(contextHintCandidates(request));
                candidates = deduplicateCandidates(refinedCandidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
                AgenticAuthoringLlmIntentResolution refinedLlmIntent = resolveLlmIntentAfterCandidateRefinement(
                        shouldResolveLlmIntent,
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidates,
                        componentCapabilities,
                        llmIntent,
                        tenantId,
                        userId,
                        environment);
                if (refinedLlmIntent != llmIntent) {
                    llmIntent = refinedLlmIntent;
                    llmSecondPassUsed = true;
                    if (!preservedAnalyticalDashboardFallback && !promotedExploratoryLlmToActionableFallback) {
                        operationKind = valueOrUnknown(llmIntent.operationKind());
                        artifactKind = valueOrUnknown(llmIntent.artifactKind());
                        changeKind = valueOrUnknown(llmIntent.changeKind());
                    }
                }
            } else if (candidates.isEmpty()) {
                candidates = discoverCandidates(prompt, artifactKind, target);
            }
        } else if (llmIntent != null) {
            operationKind = fallbackResolution.operationKind();
            artifactKind = fallbackResolution.artifactKind();
            changeKind = fallbackResolution.changeKind();
            deterministicFallbackApplied = true;
            if (candidates.isEmpty()
                    || isBroadArtifactDiscoveryOnly(candidates)
                    || shouldRefineDashboardCandidates(prompt, artifactKind, candidates)) {
                candidates = discoverCandidates(prompt, artifactKind, target);
                candidates = withContextHintCandidates(request, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
            }
        } else if (shouldResolveLlmIntent && !"unknown".equals(artifactKind)) {
            deterministicFallbackApplied = true;
            if (candidates.isEmpty()
                    || isBroadArtifactDiscoveryOnly(candidates)
                    || shouldRefineDashboardCandidates(prompt, artifactKind, candidates)) {
                candidates = discoverCandidates(prompt, artifactKind, target);
                candidates = withContextHintCandidates(request, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
            }
        }
        boolean explicitResourcePathSelectionEnabled = !explicitResourcePath(prompt).isBlank()
                || isBusinessRuleAuthoringPrompt(prompt)
                || "shared_rule_authoring".equals(requestedAuthoringFlow(request));
        AgenticAuthoringCandidate explicitResourcePathCandidate = explicitResourcePathSelectionEnabled
                ? explicitResourcePathCandidate(prompt, artifactKind, candidates)
                : null;
        if (explicitResourcePathCandidate != null) {
            candidates = withPriorityCandidate(candidates, explicitResourcePathCandidate);
        }
        AgenticAuthoringCandidate selectedCandidate = selectCandidate(candidates, target, artifactKind, prompt);
        selectedCandidate = selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = selectLlmCandidate(llmIntent, candidates, selectedCandidate, artifactKind, prompt);
        selectedCandidate = selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = selectFormWriteCandidate(artifactKind, candidates, selectedCandidate);
        selectedCandidate = selectContextHintCandidate(explicitResourcePathCandidate, candidates, selectedCandidate);
        if (isResourceChoiceClarificationAnswer(turn, contextHintCandidate, artifactKind, operationKind)) {
            artifactKind = "dashboard";
            operationKind = "explore";
            changeKind = "recommend_dashboard_visualization";
        }
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                changeKind,
                target,
                selectedCandidate,
                candidates);
        gate = withPromptSpecificGateMessages(gate, prompt, operationKind, artifactKind, selectedCandidate);
        gate = withSharedRuleAuthoringGate(gate, request, prompt, selectedCandidate);
        List<String> questions = clarificationQuestions(gate, operationKind, artifactKind, selectedCandidate, candidates);
        questions = llmClarificationQuestions(llmIntent, gate, questions);
        boolean answeredBareDomainClarification = turn.answeredPendingClarification()
                && !llmTreatsPendingAsNewInstruction
                && isBareDomainPrompt(turn.sourcePrompt());
        String assistantMessage = assistantMessage(
                prompt,
                operationKind,
                artifactKind,
                selectedCandidate,
                candidates,
                gate,
                answeredBareDomainClarification);
        if (!promotedExploratoryLlmToActionableFallback
                && !promotedExploratoryArtifactKindFromFallback
                && !preservedAnalyticalDashboardFallback
                && !hasGovernedClarificationGate(gate)
                && llmIntent != null
                && llmIntent.assistantMessage() != null
                && !llmIntent.assistantMessage().isBlank()) {
            assistantMessage = llmIntent.assistantMessage();
        }
        if (shouldHideTechnicalAddresses(request, prompt, operationKind, artifactKind)) {
            assistantMessage = sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        }
        JsonNode apiCatalogAnswer = apiCatalogAnswer(prompt, operationKind, artifactKind, selectedCandidate, candidates);
        AgenticAuthoringPendingClarification pendingClarification =
                pendingClarification(
                        effectivePrompt,
                        gate,
                        assistantMessage,
                        questions,
                        request.clientTurnId(),
                        request.attachmentSummaries());
        List<AgenticAuthoringQuickReply> quickReplies = quickReplies(
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
        boolean contextHintSelectionApplied = contextHintCandidate != null
                && selectedCandidate != null
                && "eligible".equals(gate.status());
        if (llmIntent != null
                && llmIntent.quickReplies() != null
                && !llmIntent.quickReplies().isEmpty()
                && quickReplies.isEmpty()
                && !contextHintSelectionApplied
                && !"eligible".equals(gate.status())) {
            quickReplies = llmIntent.quickReplies();
        }
        if (shouldHideTechnicalAddresses(request, prompt, operationKind, artifactKind)) {
            quickReplies = sanitizeQuickReplies(quickReplies, selectedCandidate, candidates);
        }
        List<String> warnings = warnings(llmIntent);
        if (llmTreatsPendingAsNewInstruction) {
            warnings = withWarning(warnings, "llm-follow-up-kind-new-instruction");
        }
        if (llmSecondPassUsed) {
            warnings = withWarning(warnings, "llm-intent-resolution-second-pass-used");
        }
        if (deterministicFallbackApplied) {
            warnings = withWarning(warnings, "keyword-fallback-applied");
        }
        if (governedResourceConfirmation) {
            warnings = withWarning(warnings, "governed-resource-confirmation-deterministic");
        }
        if (promotedExploratoryLlmToActionableFallback) {
            warnings = withWarning(warnings, "llm-exploratory-response-promoted-to-actionable-fallback");
        }
        if (preservedAnalyticalDashboardFallback) {
            warnings = withWarning(warnings, "llm-operational-artifact-rejected-for-analytical-dashboard-intent");
        }
        if (preLlmGovernedResourceChoiceApplied) {
            warnings = withWarning(warnings, "pre-llm-governed-resource-choice-applied");
        }
        return new AgenticAuthoringIntentResolutionResult(
                "eligible".equals(gate.status()),
                operationKind,
                artifactKind,
                changeKind,
                authoringProfile(operationKind, artifactKind),
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
                llmDiagnostics
        );
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
                && shouldAskForAnalyticalResourceChoice(prompt, candidates);
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
                || previousLlmIntent.resourceSearchQuery() == null
                || previousLlmIntent.resourceSearchQuery().isBlank()
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

    private boolean isGovernedResourceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringConversationTurn turn) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null
                || !contextHints.isObject()
                || jsonText(contextHints, "resourcePath").isBlank()) {
            return false;
        }
        String prompt = normalize(turn == null ? request.userPrompt() : turn.effectivePrompt());
        return prompt.contains("confirmed:")
                || containsAny(
                prompt,
                "confirmado",
                "confirmada",
                "confirmo",
                "gerar previa",
                "gerar pre visualizacao",
                "usar",
                "use");
    }

    private AgenticAuthoringCandidate contextHintCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        String resourcePath = jsonText(contextHints, "resourcePath");
        if (resourcePath.isBlank()) {
            return null;
        }
        String submitUrl = valueOrDefault(jsonText(contextHints, "submitUrl"), resourcePath);
        String operation = valueOrDefault(jsonText(contextHints, "operation"), jsonText(contextHints, "submitMethod"));
        if (operation.isBlank()) {
            operation = "form".equals(artifactKind) ? "post" : "get";
        }
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(submitUrl);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst();
        if (existing.isPresent()) {
            return withEvidence(existing.get(), "quick-reply-context");
        }
        return candidate(
                resourcePath,
                submitUrl,
                operation,
                1.0d,
                "resource selected from assistant quick reply context",
                "quick-reply-context");
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
                List.copyOf(mergedEvidence));
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
            String submitUrl = valueOrDefault(jsonText(candidateNode, "submitUrl"), resourcePath);
            String submitMethod = valueOrDefault(jsonText(candidateNode, "submitMethod"), operation);
            String schemaUrl = valueOrDefault(jsonText(candidateNode, "schemaUrl"), candidate(resourcePath, submitUrl, operation, 1.0d, "", "").schemaUrl());
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
            candidates.add(new AgenticAuthoringCandidate(
                    resourcePath,
                    operation,
                    schemaUrl,
                    submitUrl,
                    submitMethod,
                    score,
                    reason,
                    List.copyOf(evidence)));
        }
        return deduplicateCandidates(candidates);
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
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && shouldAskForAnalyticalResourceChoice(prompt, candidates)
                && (llmCandidate == null || !isAnalyticsCandidate(llmCandidate))) {
            return fallback;
        }
        if ("dashboard".equals(artifactKind)
                && llmCandidate != null
                && !isAnalyticsResource(llmCandidate.resourcePath())) {
            AgenticAuthoringCandidate analyticsCandidate = candidates.stream()
                    .filter(candidate -> isAnalyticsResource(candidate.resourcePath()))
                    .filter(candidate -> llmCandidate.score() - candidate.score() < 0.08d
                            || isAnalyticalComparisonPrompt(prompt))
                    .findFirst()
                    .orElse(null);
            if (analyticsCandidate != null) {
                return analyticsCandidate;
            }
        }
        return llmCandidate;
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
                && ("modify".equals(operationKind) || "unknown".equals(operationKind));
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

    private boolean hasGovernedClarificationGate(AgenticAuthoringGateResult gate) {
        return gate != null
                && ("clarification_required".equals(gate.status()) || "route_required".equals(gate.status()))
                && gate.messages() != null
                && !gate.messages().isEmpty();
    }

    private AgenticAuthoringCandidate explicitResourcePathCandidate(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        String resourcePath = explicitResourcePath(prompt);
        if (resourcePath.isBlank()) {
            return null;
        }
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath())))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        return candidate(
                resourcePath,
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

    private AgenticAuthoringCandidate selectFormWriteCandidate(
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
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
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(this::isWriteCandidate)
                .findFirst()
                .orElse(fallback);
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

    private boolean isLlmFollowUpKind(AgenticAuthoringLlmIntentResolution llmIntent, String expected) {
        if (llmIntent == null || llmIntent.followUpKind() == null || expected == null) {
            return false;
        }
        return expected.equals(normalize(llmIntent.followUpKind()));
    }

    private boolean shouldPromoteExploratoryLlmToActionableFallback(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringKeywordFallbackResolution fallbackResolution) {
        if (llmIntent == null || fallbackResolution == null || !llmIntent.resolved()) {
            return false;
        }
        return "explore".equals(valueOrUnknown(llmIntent.operationKind()))
                && !"explore".equals(valueOrUnknown(fallbackResolution.operationKind()))
                && !"unknown".equals(valueOrUnknown(fallbackResolution.artifactKind()))
                && !llmHasActionableResourceDirection(llmIntent);
    }

    private boolean shouldPreserveAnalyticalDashboardFallback(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringKeywordFallbackResolution fallbackResolution) {
        if (llmIntent == null || fallbackResolution == null || !llmIntent.resolved()) {
            return false;
        }
        if (!"explore".equals(valueOrUnknown(fallbackResolution.operationKind()))
                || !"dashboard".equals(valueOrUnknown(fallbackResolution.artifactKind()))
                || !"recommend_dashboard_visualization".equals(valueOrUnknown(fallbackResolution.changeKind()))) {
            return false;
        }
        String llmArtifactKind = valueOrUnknown(llmIntent.artifactKind());
        if ("dashboard".equals(llmArtifactKind)) {
            return false;
        }
        String llmOperationKind = valueOrUnknown(llmIntent.operationKind());
        return "create".equals(llmOperationKind)
                || "modify".equals(llmOperationKind)
                || "form".equals(llmArtifactKind)
                || "page".equals(llmArtifactKind)
                || "table".equals(llmArtifactKind)
                || "unknown".equals(llmArtifactKind);
    }

    private boolean llmHasActionableResourceDirection(AgenticAuthoringLlmIntentResolution llmIntent) {
        return !valueOrDefault(llmIntent.selectedResourcePath(), "").isBlank()
                || !valueOrDefault(llmIntent.resourceSearchQuery(), "").isBlank()
                || (llmIntent.quickReplies() != null && !llmIntent.quickReplies().isEmpty())
                || (llmIntent.clarificationQuestions() != null && !llmIntent.clarificationQuestions().isEmpty());
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
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, artifactKind, target)) {
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
                : apiMetadataCandidateCatalog.discover(prompt, artifactKind);
        if (metadataCandidates.isEmpty() && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", artifactKind);
        }
        if (!metadataCandidates.isEmpty()) {
            candidates.addAll(metadataCandidates);
        }
        return deduplicateCandidates(candidates);
    }

    private List<AgenticAuthoringCandidate> discoverInitialCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        String effectiveArtifactKind = valueOrUnknown(artifactKind);
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
                candidates.addAll(apiMetadataCandidateCatalog.discover(prompt, effectiveArtifactKind));
            }
            candidates.addAll(apiMetadataCandidateCatalog.discover(prompt, "unknown"));
            candidates.addAll(apiMetadataCandidateCatalog.discover("", !"unknown".equals(effectiveArtifactKind)
                    ? effectiveArtifactKind
                    : "unknown"));
        }
        return deduplicateCandidates(candidates);
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
            String artifactKind,
            String prompt) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, artifactKind, target)) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && shouldAskForAnalyticalResourceChoice(prompt, candidates)) {
            return null;
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            if ("dashboard".equals(artifactKind) && isAnalyticalComparisonPrompt(prompt)) {
                List<AgenticAuthoringCandidate> analyticsCandidates = candidates.stream()
                        .filter(this::isAnalyticsCandidate)
                        .toList();
                if (analyticsCandidates.size() == 1) {
                    return analyticsCandidates.get(0);
                }
            }
            return null;
        }
        if ("dashboard".equals(artifactKind) && isAnalyticalComparisonPrompt(prompt) && !candidates.isEmpty()) {
            return candidates.stream()
                    .filter(this::isAnalyticsCandidate)
                    .findFirst()
                    .orElse(candidates.get(0));
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind) && !candidates.isEmpty()) {
            double bestScore = candidates.get(0).score();
            return candidates.stream()
                    .filter(this::isAnalyticsCandidate)
                    .filter(candidate -> bestScore - candidate.score() < 0.08d)
                    .findFirst()
                    .orElse(null);
        }
        if ("api_catalog".equals(artifactKind) && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        return null;
    }

    private boolean shouldAskForAnalyticalResourceChoice(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> analyticsCandidates = candidates == null
                ? List.of()
                : candidates.stream()
                .filter(this::isAnalyticsCandidate)
                .toList();
        return analyticsCandidates.size() > 1
                && analyticsCandidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate));
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
                "distribution", "human", "resources", "resource", "metadata", "lexical", "match",
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

    private boolean shouldRefineDashboardCandidates(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        return "dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && (candidates == null || candidates.stream().noneMatch(this::isAnalyticsCandidate));
    }

    private boolean shouldDetachCurrentTarget(String prompt, String artifactKind, AgenticAuthoringTarget target) {
        if (target == null || !"praxis-dynamic-form".equals(target.componentId())) {
            return false;
        }
        if ("form".equals(artifactKind)) {
            return false;
        }
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "nao quero cadastrar", "nao quero cadastro", "nao era isso", "em vez disso",
                "dashboard", "painel", "indicador", "indicadores",
                "master detail", "master-detail", "mestre detalhe", "mestre-detalhe",
                "lista e detalhe", "lista com detalhe", "abrir detalhes", "abrir detalhe",
                "acompanhar", "consultar", "buscar", "visualizar", "ver detalhes", "ver detalhe");
    }

    private boolean isAnalyticsResource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("analytics") || normalized.contains("/vw-") || normalized.contains("/stats/");
    }

    private boolean isAnalyticsCandidate(AgenticAuthoringCandidate candidate) {
        return candidate != null
                && (isAnalyticsResource(candidate.resourcePath()) || isAnalyticsResource(candidate.submitUrl()));
    }

    private boolean isAnalyticalComparisonPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "comparar", "compare", "comparativo", "por setor", "por departamento",
                "recebe mais", "ganha mais", "salario", "salarios", "remuneracao");
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

    private String authoringProfile(String operationKind, String artifactKind) {
        if ("api_catalog".equals(artifactKind)) {
            return "api-catalog-qa";
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
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        List<String> messages = new ArrayList<>(gate.messages());
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult(gate.gateId(), status, List.copyOf(messages));
    }

    private AgenticAuthoringGateResult withSharedRuleAuthoringGate(
            AgenticAuthoringGateResult gate,
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        if (gate == null) {
            return null;
        }
        if (!requiresSharedRuleAuthoring(request, prompt, selectedCandidate)) {
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

    private boolean requiresSharedRuleAuthoring(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        String requestedFlow = requestedAuthoringFlow(request);
        if ("shared_rule_authoring".equals(requestedFlow)) {
            return selectedCandidate != null;
        }
        return selectedCandidate != null && isBusinessRuleAuthoringPrompt(prompt);
    }

    private boolean isBusinessRuleAuthoringPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean decisionIntent = containsAny(normalized,
                "regra", "regras", "rule", "rules",
                "politica", "politicas", "policy", "policies",
                "decisao", "decisoes", "decision", "decisions",
                "validacao", "validar", "validation", "validate",
                "aprovacao", "aprovacoes", "aprovar", "approval", "approvals", "approve",
                "revisao", "revisar", "review", "review_required",
                "mascarar", "mascare", "mask", "masking",
                "privacidade", "privacy",
                "bloquear", "bloqueie", "impedir", "nao pode", "nao permitir",
                "exigir", "exige", "exigem", "obrigatorio", "obrigatoria",
                "elegibilidade", "eligibility", "compliance", "governanca", "governance");
        boolean businessSubject = containsAny(normalized,
                "lgpd", "gdpr",
                "dados sensiveis", "dado sensivel", "sensitive data", "personal data",
                "privacidade", "privacy",
                "compliance", "governanca", "governance",
                "aprovacao", "aprovacoes", "approval", "approvals",
                "elegibilidade", "eligibility",
                "validacao", "validation",
                "status", "bloqueado", "blocked", "inactive", "inativo", "invalido", "invalid");
        boolean componentAuthoringIntent = containsAny(normalized,
                "formulario", "form", "tabela", "table", "dashboard", "grafico", "chart",
                "campo", "campos", "coluna", "colunas", "widget", "componente", "pagina");
        return decisionIntent && (businessSubject || !componentAuthoringIntent);
    }

    private boolean isConfirmedDataSourceSelection(String prompt) {
        return containsAny(prompt, "fonte confirmada", "source confirmed", "data source", "usar vw-", "use vw-",
                "usar /api/", "use /api/", "recurso confirmado", "api confirmada");
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

    private String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification) {
        if (gate != null && gate.messages().contains("shared-rule-authoring-required")) {
            return sharedRuleAuthoringAssistantMessage(selectedCandidate);
        }
        if (gate != null && gate.messages().contains("resource-candidate-required")) {
            return missingResourceAssistantMessage(artifactKind);
        }
        if (gate != null
                && gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null) {
            return ambiguousResourceAssistantMessage(artifactKind, candidates);
        }
        if (!"explore".equals(operationKind)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)) {
            if (apiCatalogConversationService != null) {
                return apiCatalogConversationService.answer(prompt, selectedCandidate, candidates).assistantMessage();
            }
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (answeredBareDomainClarification) {
            return null;
        }
        if ("dashboard".equals(artifactKind)) {
            return "Encontrei uma intencao analitica. Posso preparar um dashboard governado usando a fonte de negocio mais aderente ou ajustar o recorte antes de criar.";
        }
        if ("table".equals(artifactKind)) {
            return "Posso ajudar a escolher antes de criar. Para uma tabela, normalmente faz sentido definir recurso, colunas principais, filtros, ordenacao e formato dos campos.";
        }
        return "Posso ajudar a escolher antes de criar. Opcoes comuns sao dashboards para analise, formularios para entrada de dados, paginas master-detail para navegacao e tabelas para detalhe operacional.";
    }

    private String sharedRuleAuthoringAssistantMessage(AgenticAuthoringCandidate selectedCandidate) {
        String resourcePath = selectedCandidate == null ? "" : valueOrDefault(selectedCandidate.resourcePath(), "").trim();
        if (!resourcePath.isBlank()) {
            return "Esse pedido deve seguir pela trilha governada de regra compartilhada em /api/praxis/config/domain-rules, "
                    + "e nao pelo preview de formulario/pagina. "
                    + "Use POST /api/praxis/config/domain-rules/intake com o recurso " + resourcePath
                    + " como grounding canônico, depois POST /api/praxis/config/domain-rules/simulations para validar cobertura, "
                    + "aprovações e materializações antes de publicar.";
        }
        return "Esse pedido deve seguir pela trilha governada de regra compartilhada em /api/praxis/config/domain-rules, "
                + "e nao pelo preview de formulario/pagina. "
                + "Use POST /api/praxis/config/domain-rules/intake, depois POST /api/praxis/config/domain-rules/simulations "
                + "antes de publicar.";
    }

    private JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!"explore".equals(operationKind) || !"api_catalog".equals(artifactKind)
                || apiCatalogConversationService == null) {
            return null;
        }
        return apiCatalogConversationService.answer(prompt, selectedCandidate, candidates).apiCatalogAnswer();
    }

    private String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (usableCandidates.isEmpty()) {
            return "Nao encontrei APIs candidatas no catalogo para esse tema. Posso listar endpoints, schemas, actions, filtros ou ajudar a escolher uma API quando houver metadados disponiveis.";
        }
        if (containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para consultar campos e schema, use " + candidate.schemaUrl()
                    + ". A API candidata e " + candidate.resourcePath()
                    + " (" + candidate.operation().toUpperCase(Locale.ROOT) + ").";
        }
        if (containsAny(prompt, "action", "actions", "acao", "acoes", "permite", "criar", "editar", "alterar", "excluir")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para actions e operacoes permitidas, consulte /schemas/actions para "
                    + candidate.resourcePath()
                    + ". O candidato atual usa " + candidate.operation().toUpperCase(Locale.ROOT)
                    + " em " + candidate.submitUrl() + ".";
        }
        if (containsAny(prompt, "filtro", "filtros", "filtrar", "filter")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para filtros, priorize endpoints de colecao ou consulta como "
                    + candidate.submitUrl()
                    + " e valide o contrato em " + candidate.schemaUrl()
                    + ". Se o catalogo expuser surfaces/actions, use /schemas/surfaces e /schemas/actions para confirmar filtros e operacoes.";
        }

        String endpoints = usableCandidates.stream()
                .limit(4)
                .map(candidate -> candidate.resourcePath()
                        + " (" + candidate.operation().toUpperCase(Locale.ROOT)
                        + ", schema: " + candidate.schemaUrl() + ")")
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        String message = "APIs candidatas encontradas: " + endpoints;
        if (containsAny(prompt, "devo usar", "melhor", "recomenda", "recomende", "dashboard", "tabela")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            message += ". Recomendacao: use " + candidate.resourcePath()
                    + " com " + candidate.submitUrl()
                    + " para esse objetivo antes de gerar a pagina.";
        }
        return message;
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
        if (isDashboardFilterConnectionRequest(operationKind, artifactKind, changeKind, selectedCandidate)
                && !"eligible".equals(gate.status())) {
            return dashboardFilterConnectionQuickReplies(effectivePrompt, prompt, selectedCandidate);
        }
        if (isConsultativeDashboardWithSelectedCandidate(operationKind, artifactKind, selectedCandidate)) {
            return dashboardExplorationQuickReplies(effectivePrompt, selectedCandidate);
        }
        if (!"explore".equals(operationKind) && !"clarification_required".equals(gate.status())) {
            return List.of();
        }
        if (gate.messages().contains("resource-candidate-required")) {
            return resourceDiscoveryQuickReplies(effectivePrompt, artifactKind);
        }
        if ("api_catalog".equals(artifactKind)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "api-create-dashboard",
                            "suggestion",
                            "Criar dashboard",
                            "Crie um dashboard usando a API recomendada."),
                    new AgenticAuthoringQuickReply(
                            "api-show-schema",
                            "suggestion",
                            "Ver schema",
                            "Quais campos existem no schema da API recomendada?"),
                    new AgenticAuthoringQuickReply(
                            "api-show-actions",
                            "suggestion",
                            "Ver actions",
                            "Quais actions e filtros essa API suporta?"));
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
        if (gate.messages().contains("resource-candidate-ambiguous") && selectedCandidate == null) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if ("explore".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate == null
                && candidates != null
                && !candidates.isEmpty()) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
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
        boolean askedBusinessCut = containsAny(normalizedPrompt, "area", "setor", "departamento", "grupo", "categoria", "dimensao");
        String combinedLabel = askedTemporalCut && askedBusinessCut ? "Usar periodo e area" : "Usar filtros e recortes";
        String combinedDecision = askedTemporalCut && askedBusinessCut
                ? "adicionar filtros de periodo e area ao dashboard"
                : "adicionar filtros e recortes ao dashboard";
        String temporalLabel = askedTemporalCut ? "So periodo" : "Filtro temporal";
        String businessLabel = askedBusinessCut ? "So area" : "Dimensao de negocio";
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
                        contextHints),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Refinar pedido",
                        effectivePrompt,
                        "Ajustar objetivo, dados, metricas ou escopo antes de gerar a pre-visualizacao.",
                        "tune",
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
                            candidateDescription(candidate, null),
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
        String path = valueOrDefault(candidate.resourcePath(), "");
        return switch (valueOrDefault(artifactKind, "")) {
            case "dashboard", "page" -> "Fonte candidata para alimentar o painel.";
            case "table" -> "Fonte candidata para alimentar a tabela.";
            case "form" -> "Operacao candidata para o formulario.";
            default -> "Fonte candidata encontrada no catalogo.";
        };
    }

    private boolean shouldHideTechnicalAddresses(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String operationKind,
            String artifactKind) {
        return !"api_catalog".equals(artifactKind)
                && !"explore".equals(operationKind)
                && !isBusinessRuleAuthoringPrompt(prompt)
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
        String normalized = normalize(candidate.resourcePath() + " " + candidate.submitUrl());
        if (normalized.contains("analytics") || normalized.contains("stats") || normalized.contains("vw-")) {
            return "query_stats";
        }
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "edit_note";
        }
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
        String normalized = normalize(candidate.resourcePath() + " " + candidate.submitUrl());
        if (normalized.contains("analytics") || normalized.contains("stats") || normalized.contains("vw-")) {
            return "analytics";
        }
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
}
