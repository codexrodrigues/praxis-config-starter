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
import java.util.stream.Collectors;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final String PAYROLL_ANALYTICS = "/api/human-resources/vw-analytics-folha-pagamento";
    private static final String PAYROLL = "/api/human-resources/folhas-pagamento";

    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringApiCatalogConversationService apiCatalogConversationService;
    private final AgenticAuthoringLlmIntentResolverService llmIntentResolverService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final ObjectMapper objectMapper;
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
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.apiCatalogConversationService = apiCatalogConversationService;
        this.llmIntentResolverService = llmIntentResolverService;
        this.componentCapabilitiesService = componentCapabilitiesService;
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
        String effectivePrompt = hasLlmIntentResolver ? rawPrompt : turn.effectivePrompt();
        String prompt = normalize(effectivePrompt);
        String discoveryPrompt = normalize(turn.answeredPendingClarification() ? turn.effectivePrompt() : effectivePrompt);
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage());
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), request.selectedWidgetKey());
        AgenticAuthoringKeywordFallbackResolution fallbackResolution =
                keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
        String operationKind = fallbackResolution.operationKind();
        String artifactKind = fallbackResolution.artifactKind();
        String changeKind = fallbackResolution.changeKind();
        List<AgenticAuthoringCandidate> candidates = hasLlmIntentResolver
                ? discoverInitialCandidates(discoveryPrompt, target)
                : discoverCandidates(discoveryPrompt, artifactKind, target);
        candidates = withContextHintCandidates(request, candidates);
        AgenticAuthoringCandidate contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
        if (contextHintCandidate != null) {
            candidates = withPriorityCandidate(candidates, contextHintCandidate);
        }
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = componentCapabilities();
        AgenticAuthoringLlmIntentResolution llmIntent = resolveLlmIntent(
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
        boolean deterministicFallbackApplied = !hasLlmIntentResolver;
        if (llmTreatsPendingAsContinuation) {
            effectivePrompt = turn.effectivePrompt();
            prompt = normalize(effectivePrompt);
            fallbackResolution = keywordFallbackResolver.resolve(prompt, currentPageSummary, target);
            if (!hasLlmIntentResolver || llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
            }
            candidates = hasLlmIntentResolver
                    ? discoverInitialCandidates(prompt, target)
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
            if (!hasLlmIntentResolver || llmIntent == null || !llmIntent.resolved()) {
                operationKind = fallbackResolution.operationKind();
                artifactKind = fallbackResolution.artifactKind();
                changeKind = fallbackResolution.changeKind();
                deterministicFallbackApplied = true;
            }
            candidates = hasLlmIntentResolver
                    ? discoverInitialCandidates(prompt, target)
                    : discoverCandidates(prompt, artifactKind, target);
            candidates = withContextHintCandidates(request, candidates);
            contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
            if (contextHintCandidate != null) {
                candidates = withPriorityCandidate(candidates, contextHintCandidate);
            }
        }
        if (llmIntent != null && llmIntent.resolved()) {
            String previousArtifactKind = artifactKind;
            operationKind = valueOrUnknown(llmIntent.operationKind());
            artifactKind = valueOrUnknown(llmIntent.artifactKind());
            changeKind = valueOrUnknown(llmIntent.changeKind());
            String llmResourceSearchQuery = valueOrDefault(llmIntent.resourceSearchQuery(), "").trim();
            if (!Objects.equals(previousArtifactKind, artifactKind) || !llmResourceSearchQuery.isBlank()) {
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
                    operationKind = valueOrUnknown(llmIntent.operationKind());
                    artifactKind = valueOrUnknown(llmIntent.artifactKind());
                    changeKind = valueOrUnknown(llmIntent.changeKind());
                }
            } else if (candidates.isEmpty()) {
                candidates = discoverCandidates(prompt, artifactKind, target);
            }
        } else if (llmIntent != null) {
            operationKind = fallbackResolution.operationKind();
            artifactKind = fallbackResolution.artifactKind();
            changeKind = fallbackResolution.changeKind();
            deterministicFallbackApplied = true;
            if (candidates.isEmpty() || isBroadArtifactDiscoveryOnly(candidates)) {
                candidates = discoverCandidates(prompt, artifactKind, target);
                candidates = withContextHintCandidates(request, candidates);
                contextHintCandidate = contextHintCandidate(request, artifactKind, candidates);
                if (contextHintCandidate != null) {
                    candidates = withPriorityCandidate(candidates, contextHintCandidate);
                }
            }
        }
        candidates = withEmployeeFormPriorityCandidate(prompt, candidates);
        boolean deterministicEmployeeFormCreate = target == null && isEmployeeFormCreatePrompt(prompt);
        if (deterministicEmployeeFormCreate) {
            operationKind = "create";
            artifactKind = "form";
            changeKind = "create_minimal_form";
        }
        AgenticAuthoringCandidate selectedCandidate = selectCandidate(candidates, target, artifactKind);
        selectedCandidate = selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = selectLlmCandidate(llmIntent, candidates, selectedCandidate);
        selectedCandidate = selectContextHintCandidate(contextHintCandidate, candidates, selectedCandidate);
        selectedCandidate = selectDeterministicFormCandidate(prompt, candidates, selectedCandidate);
        boolean deterministicPayrollDashboardConfirmation = isConfirmedPayrollDashboardCreate(
                prompt,
                effectivePrompt,
                selectedCandidate);
        if (deterministicPayrollDashboardConfirmation) {
            operationKind = "create";
            artifactKind = "dashboard";
            changeKind = "create_chart_drilldown";
        }
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                changeKind,
                target,
                selectedCandidate,
                candidates);
        gate = withPromptSpecificGateMessages(gate, prompt, operationKind, artifactKind, selectedCandidate);
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
        if (llmIntent != null && llmIntent.assistantMessage() != null && !llmIntent.assistantMessage().isBlank()) {
            assistantMessage = llmIntent.assistantMessage();
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
                && !contextHintSelectionApplied) {
            quickReplies = llmIntent.quickReplies();
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
        if (deterministicEmployeeFormCreate) {
            warnings = withWarning(warnings, "deterministic-employee-form-create-applied");
        }
        if (deterministicPayrollDashboardConfirmation) {
            warnings = withWarning(warnings, "deterministic-payroll-dashboard-confirmation-applied");
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

    private AgenticAuthoringLlmIntentResolution resolveLlmIntent(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (llmIntentResolverService == null) {
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
        if (llmIntentResolverService == null
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
            return existing.get();
        }
        return candidate(
                resourcePath,
                submitUrl,
                operation,
                1.0d,
                "resource selected from assistant quick reply context",
                "quick-reply-context");
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
            AgenticAuthoringCandidate fallback) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        return candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(fallback);
    }

    private AgenticAuthoringCandidate selectDeterministicFormCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
        if (!isEmployeeFormCreatePrompt(prompt) || candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> "/api/human-resources/funcionarios".equals(candidate.resourcePath()))
                .filter(candidate -> "post".equalsIgnoreCase(candidate.operation())
                        || candidate.schemaUrl().contains("operation=post"))
                .findFirst()
                .orElse(fallback);
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

    private boolean isPayrollAnalyticsPrompt(String prompt) {
        return containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios",
                "departamento", "departamentos");
    }

    private boolean isBareDomainPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (!isPayrollAnalyticsPrompt(normalized)) {
            return false;
        }
        String[] tokens = normalized.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    private boolean isDashboardWidgetAdditionPrompt(String prompt) {
        return containsAny(prompt, "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente")
                && containsAny(prompt, "widget", "componente", "resumo", "executivo", "kpi", "indicador", "indicadores");
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
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
        candidates.addAll(discoverKnownCandidates(prompt));
        return deduplicateCandidates(candidates);
    }

    private List<AgenticAuthoringCandidate> discoverInitialCandidates(
            String prompt,
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
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
            candidates.addAll(apiMetadataCandidateCatalog.discover(prompt, "unknown"));
            candidates.addAll(apiMetadataCandidateCatalog.discover("", "unknown"));
        }
        candidates.addAll(discoverBroadKnownCandidates());
        return deduplicateCandidates(candidates);
    }

    private List<AgenticAuthoringCandidate> discoverBroadKnownCandidates() {
        return List.of(
                candidate("/api/human-resources/funcionarios", "post", 0.40d,
                        "broad employee creation candidate for LLM selection", "broad-artifact-discovery"),
                candidate(PAYROLL_ANALYTICS, PAYROLL_ANALYTICS + "/stats/group-by", "post", 0.40d,
                        "broad payroll analytics candidate for LLM selection", "broad-artifact-discovery"),
                candidate(PAYROLL, PAYROLL + "/all", "get", 0.40d,
                        "broad payroll collection candidate for LLM selection", "broad-artifact-discovery"));
    }

    private List<AgenticAuthoringCandidate> discoverKnownCandidates(String prompt) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (isEmployeeFormCreatePrompt(prompt)) {
            candidates.add(employeeFormCandidate());
        } else if (containsAny(prompt, "funcionario", "funcionarios", "funsionario", "funsionarios", "colaborador", "colaboradores")
                || (containsAny(prompt, "rh", "human resources") && !isPayrollAnalyticsPrompt(prompt))) {
            candidates.add(candidate("/api/human-resources/funcionarios", 0.90d, "prompt mentions funcionarios/colaboradores", "known-quickstart-resource"));
        }
        if (isPayrollAnalyticsPrompt(prompt) && isDashboardWidgetAdditionPrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt asks to add a payroll analytics dashboard widget",
                    "known-quickstart-analytics-view"));
        } else if (prefersPayrollDashboardRecommendation(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt asks for payroll dashboard recommendations",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt) && isExplicitDashboardPrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt asks for payroll analytics dashboard",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt) && isTablePrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL,
                    PAYROLL + "/all",
                    "get",
                    0.94d,
                    "prompt asks for operational payroll table/listing",
                    "known-quickstart-resource"));
        } else if (isPayrollAnalyticsPrompt(prompt)
                && (isConsultativePrompt(prompt)
                || containsAny(prompt, "chart", "grafico", "dashboard", "painel", "drill down", "drill-down",
                "drilldown", "visualizar", "mostrar", "mostre", "ver"))) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt mentions payroll analytics chart drill-down",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.78d,
                    "approximate payroll analytics endpoint match",
                    "known-quickstart-analytics-view"));
            candidates.add(candidate(
                    PAYROLL,
                    PAYROLL + "/all",
                    "get",
                    0.72d,
                    "approximate payroll collection endpoint match",
                    "known-quickstart-resource"));
        }
        return candidates;
    }

    private AgenticAuthoringCandidate employeeFormCandidate() {
        return candidate(
                "/api/human-resources/funcionarios",
                "post",
                0.99d,
                "prompt asks for an employee registration form",
                "known-quickstart-employee-form");
    }

    private List<AgenticAuthoringCandidate> withEmployeeFormPriorityCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (!isEmployeeFormCreatePrompt(prompt)) {
            return candidates == null ? List.of() : candidates;
        }
        java.util.LinkedHashMap<String, AgenticAuthoringCandidate> prioritized = new java.util.LinkedHashMap<>();
        AgenticAuthoringCandidate priorityCandidate = employeeFormCandidate();
        prioritized.put(priorityCandidate.resourcePath(), priorityCandidate);
        if (candidates != null) {
            for (AgenticAuthoringCandidate candidate : candidates) {
                if (candidate == null || candidate.resourcePath() == null || candidate.resourcePath().isBlank()) {
                    continue;
                }
                prioritized.putIfAbsent(candidate.resourcePath(), candidate);
            }
        }
        return List.copyOf(prioritized.values());
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
            String artifactKind) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            return null;
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind) && !candidates.isEmpty()) {
            double bestScore = candidates.get(0).score();
            return candidates.stream()
                    .filter(candidate -> isAnalyticsResource(candidate.resourcePath()))
                    .filter(candidate -> bestScore - candidate.score() < 0.08d)
                    .findFirst()
                    .orElse(null);
        }
        if ("api_catalog".equals(artifactKind) && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        return null;
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    private boolean isAnalyticsResource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("analytics") || normalized.contains("/vw-");
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
        if (requiresPayrollDashboardBreakdown(prompt, operationKind, artifactKind, selectedCandidate)) {
            String breakdownMessage = isPayrollDashboardAlternativeBreakdownAnswer(prompt)
                    ? "analytics-custom-breakdown-required"
                    : "analytics-breakdown-required";
            if (!messages.contains(breakdownMessage)) {
                messages.add(breakdownMessage);
            }
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult(gate.gateId(), status, List.copyOf(messages));
    }

    private boolean requiresPayrollDashboardBreakdown(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return "create".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null
                && PAYROLL_ANALYTICS.equals(selectedCandidate.resourcePath())
                && !isExplicitCreateConfirmation(prompt)
                && !hasPayrollDashboardBreakdown(prompt);
    }

    private boolean isConfirmedPayrollDashboardCreate(
            String prompt,
            String effectivePrompt,
            AgenticAuthoringCandidate selectedCandidate) {
        if (selectedCandidate == null || !PAYROLL_ANALYTICS.equals(selectedCandidate.resourcePath())) {
            return false;
        }
        String combinedPrompt = normalize(valueOrDefault(effectivePrompt, "") + "\n" + valueOrDefault(prompt, ""));
        if (!isExplicitDashboardPrompt(combinedPrompt) || !hasPayrollDashboardBreakdown(combinedPrompt)) {
            return false;
        }
        return isExplicitCreateConfirmation(combinedPrompt)
                || isConfirmedDataSourceSelection(combinedPrompt);
    }

    private boolean isConfirmedDataSourceSelection(String prompt) {
        return containsAny(prompt, "fonte confirmada", "source confirmed", "data source", "usar vw-", "use vw-",
                "usar /api/", "use /api/", "recurso confirmado", "api confirmada")
                || (prompt.contains(PAYROLL_ANALYTICS)
                && containsAny(prompt, "usar", "use", "confirmada", "confirmado", "selecionada", "selecionado"));
    }

    private boolean hasPayrollDashboardBreakdown(String prompt) {
        return containsAny(prompt,
                "departamento", "departamentos", "competencia", "competencias", "mes", "mensal",
                "status", "setor", "setores", "perfil", "perfis", "cargo", "cargos", "equipe", "equipes", "base", "bases",
                "funcionario", "funcionarios",
                "drill down", "drill-down", "drilldown");
    }

    private boolean isPayrollDashboardAlternativeBreakdownAnswer(String prompt) {
        return containsAny(prompt, "outro", "outra", "outros", "outras");
    }

    private boolean isTablePrompt(String prompt) {
        return containsAny(prompt, "tabela", "grid", "lista", "listagem", "listar", "liste", "relacao");
    }

    private boolean isEmployeeFormCreatePrompt(String prompt) {
        if (containsAny(prompt, "beneficio", "beneficios", "dependente", "dependentes", "habilidade", "habilidades")) {
            return false;
        }
        if (!containsAny(prompt,
                "funcionario", "funcionarios", "funsionario", "funsionarios",
                "colaborador", "colaboradores", "rh", "human resources")) {
            return false;
        }
        boolean asksForForm = containsAny(prompt,
                "formulario", "form", "ficha", "cadastro", "cadastrar", "cadastra",
                "preencher", "prencher", "preenche", "pagina de preencher", "pagina de prencher",
                "salvar", "salva", "gravar", "criar registro");
        boolean mentionsEmployeeFields = containsAny(prompt,
                "nome", "cargo", "salario", "salarios", "departamento", "departameto", "setor");
        if (isTablePrompt(prompt) && !asksForForm) {
            return false;
        }
        return asksForForm || mentionsEmployeeFields;
    }

    private boolean isExplicitDashboardPrompt(String prompt) {
        return containsAny(prompt, "dashboard", "painel", "grafico", "graficos", "chart", "charts",
                "indicador", "indicadores", "drill down", "drill-down", "drilldown",
                "visualizar informacoes", "visualizar informacao", "folha de pagamento");
    }

    private boolean prefersPayrollDashboardRecommendation(String prompt) {
        if (!isConsultativePrompt(prompt) || !isPayrollAnalyticsPrompt(prompt)) {
            return false;
        }
        if (!isTablePrompt(prompt)) {
            return true;
        }
        return containsAny(prompt,
                "dashboard", "dashboards", "painel", "grafico", "graficos", "chart", "charts",
                "indicador", "indicadores", "drill down", "drill-down", "drilldown", "cross filter",
                "analise", "analisar", "analitica", "opcao", "opcoes", "alternativa", "alternativas",
                "recomendacao", "recomendacoes", "compare", "comparar");
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
            } else if ("analytics-breakdown-required".equals(message)) {
                questions.add("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
            } else if ("analytics-custom-breakdown-required".equals(message)) {
                questions.add("Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?");
            }
        }
        return List.copyOf(questions);
    }

    private String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("explore".equals(operationKind) && "table".equals(artifactKind)
                && selectedCandidate != null
                && PAYROLL.equals(selectedCandidate.resourcePath())) {
            return "Posso criar uma tabela operacional de folhas de pagamento usando /api/human-resources/folhas-pagamento?";
        }
        return "Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?";
    }

    private String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification) {
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
        if (isPayrollAnalyticsPrompt(prompt)) {
            return "Para folha de pagamento, as melhores opcoes sao: 1. dashboard executivo com KPIs e total da folha; "
                    + "2. drill-down por departamento com grafico filtrando uma tabela de detalhes; "
                    + "3. evolucao mensal para identificar tendencias de custo; "
                    + "4. tabela detalhada com filtros e valores monetarios formatados. Escolha uma opcao ou descreva o que quer criar.";
        }
        if ("table".equals(artifactKind)) {
            return "Posso ajudar a escolher antes de criar. Para uma tabela, normalmente faz sentido definir recurso, colunas principais, filtros, ordenacao e formato dos campos.";
        }
        return "Posso ajudar a escolher antes de criar. Opcoes comuns sao dashboards para analise, formularios para entrada de dados, paginas master-detail para navegacao e tabelas para detalhe operacional.";
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
                .map(candidate -> candidateLabel(candidate) + " (" + candidate.submitMethod().toUpperCase(Locale.ROOT)
                        + " " + candidate.submitUrl() + ")")
                .reduce((left, right) -> left + "; " + right)
                .orElse("opcoes do catalogo de APIs");
        return "Encontrei mais de uma fonte de dados possivel para este " + artifactLabel + ". "
                + "Escolha a API que melhor representa o recorte de negocio antes de gerar a pre-visualizacao. "
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
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate,
            List<String> questions,
            List<AgenticAuthoringCandidate> candidates,
            boolean answeredBareDomainClarification) {
        if (!"explore".equals(operationKind) && !"clarification_required".equals(gate.status())) {
            return List.of();
        }
        if (gate.messages().contains("analytics-breakdown-required")) {
            return payrollBreakdownQuickReplies(effectivePrompt);
        }
        if (gate.messages().contains("analytics-custom-breakdown-required")) {
            return payrollCustomBreakdownQuickReplies(effectivePrompt);
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
        if (isPayrollAnalyticsPrompt(prompt)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "payroll-executive-dashboard",
                            "suggestion",
                            "Dashboard executivo",
                            "Crie um dashboard de folha de pagamento com KPIs, folha por departamento, evolucao mensal e tabela de detalhes."),
                    new AgenticAuthoringQuickReply(
                            "payroll-department-drilldown",
                            "suggestion",
                            "Drill-down por departamento",
                            "Crie um dashboard de folha de pagamento com grafico por departamento, indicadores e drill-down para painel de detalhamento ao selecionar uma barra."),
                    new AgenticAuthoringQuickReply(
                            "payroll-detail-table",
                            "suggestion",
                            "Tabela detalhada",
                            "Crie uma tabela detalhada de folha de pagamento com filtros, valores monetarios formatados e colunas por funcionario."));
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
                    boolean duplicatedResourcePath = resourcePathCounts.getOrDefault(
                            valueOrDefault(candidate.resourcePath(), ""),
                            0L) > 1L;
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate, duplicatedResourcePath),
                            "suggestion",
                            candidateLabel(candidate),
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "usar " + candidate.resourcePath()),
                            candidateDescription(candidate),
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

    private String candidateDescription(AgenticAuthoringCandidate candidate) {
        String method = valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT);
        return method + " " + candidate.submitUrl();
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

    private List<AgenticAuthoringQuickReply> payrollBreakdownQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-department",
                        "suggestion",
                        "Por departamento",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por departamento")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-competence",
                        "suggestion",
                        "Por competencia",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por competencia")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-status",
                        "suggestion",
                        "Por status",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por status")));
    }

    private List<AgenticAuthoringQuickReply> payrollCustomBreakdownQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-role",
                        "suggestion",
                        "Por cargo",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por cargo")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-team",
                        "suggestion",
                        "Por equipe",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por equipe")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-base",
                        "suggestion",
                        "Por base",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por base")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-profile",
                        "suggestion",
                        "Por perfil",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por perfil")));
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
                .map(candidate -> candidate.resourcePath() + " (" + candidate.operation().toUpperCase(Locale.ROOT) + ")")
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
