package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderFailureClassifier;
import org.praxisplatform.config.service.AiProviderInvocationMetrics;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.AiProviderInvocationTrace;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class AgenticAuthoringLlmPreIntentToolPlanningService implements AgenticAuthoringPreIntentToolPlanningService {

    private static final Logger log = LoggerFactory.getLogger(AgenticAuthoringLlmPreIntentToolPlanningService.class);
    private static final int MAX_PLANNING_TOKENS = 640;
    private static final int MAX_PLANNER_CONTEXT_ARRAY_ITEMS = 8;
    private static final int MAX_PLANNER_PAGE_WIDGETS = 12;
    private static final int MAX_PLANNER_TEXT_LENGTH = 700;
    private static final int MAX_PLANNER_USER_PROMPT_LENGTH = 850;
    private static final int DEFAULT_PLANNING_BUDGET_SECONDS = 12;
    private static final int DEFAULT_PROVIDER_ATTEMPTS = 2;
    private static final long DEFAULT_PROVIDER_RETRY_DELAY_MS = 250L;
    private static final int MIN_RETRY_BUDGET_SECONDS = 2;
    private static final List<String> AUTHORABLE_PRIMARY_COMPONENTS = List.of(
            "praxis-crud",
            "praxis-table",
            "praxis-dynamic-form",
            "praxis-list",
            "praxis-chart",
            "praxis-tabs",
            "praxis-stepper",
            "praxis-expansion",
            "praxis-related-resource-outlet",
            "praxis-rich-content",
            "praxis-files-upload");
    private static final List<String> COMPACT_RESOURCE_COMPOSITION_LAYOUTS = List.of(
            "single-table",
            "resource-master-detail",
            "parent-child-related-resource",
            "resource-crud",
            "tabs_layout");

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;
    private final int planningBudgetSeconds;
    private final int providerAttempts;
    private final long providerRetryDelayMs;
    private final String openAiPlanningModel;

    public AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, null);
    }

    public AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService) {
        this(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService,
                DEFAULT_PLANNING_BUDGET_SECONDS,
                DEFAULT_PROVIDER_ATTEMPTS,
                DEFAULT_PROVIDER_RETRY_DELAY_MS,
                "");
    }

    public AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            String openAiPlanningModel) {
        this(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService,
                DEFAULT_PLANNING_BUDGET_SECONDS,
                DEFAULT_PROVIDER_ATTEMPTS,
                DEFAULT_PROVIDER_RETRY_DELAY_MS,
                openAiPlanningModel);
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            int planningBudgetSeconds) {
        this(
                providerManagementService,
                objectMapper,
                null,
                planningBudgetSeconds,
                DEFAULT_PROVIDER_ATTEMPTS,
                DEFAULT_PROVIDER_RETRY_DELAY_MS,
                "");
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            int planningBudgetSeconds,
            int providerAttempts,
            long providerRetryDelayMs) {
        this(
                providerManagementService,
                objectMapper,
                null,
                planningBudgetSeconds,
                providerAttempts,
                providerRetryDelayMs,
                "");
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int planningBudgetSeconds,
            int providerAttempts,
            long providerRetryDelayMs) {
        this(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService,
                planningBudgetSeconds,
                providerAttempts,
                providerRetryDelayMs,
                "");
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int planningBudgetSeconds,
            int providerAttempts,
            long providerRetryDelayMs,
            String openAiPlanningModel) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService,
                "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogPromptContextService = domainCatalogPromptContextService;
        this.planningBudgetSeconds = planningBudgetSeconds > 0
                ? planningBudgetSeconds
                : DEFAULT_PLANNING_BUDGET_SECONDS;
        this.providerAttempts = Math.max(1, providerAttempts);
        this.providerRetryDelayMs = Math.max(0L, providerRetryDelayMs);
        this.openAiPlanningModel = normalizeModel(openAiPlanningModel);
    }

    @Override
    public AgenticAuthoringPreIntentToolPlanningResult plan(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        if (request == null) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("request-unavailable");
        }
        if (!StringUtils.hasText(request.userPrompt())) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("user-prompt-empty");
        }
        if (request.pendingClarification() != null) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("pending-clarification-present");
        }
        if (request.activeSemanticDecision() != null) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("active-semantic-decision-present");
        }
        if (hasResourceDiscoveryContext(request)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("resource-discovery-context-present");
        }
        long promptStartedAt = System.nanoTime();
        String prompt = prompt(request, principalContext);
        log.debug(
                "[AgenticAuthoringPreIntentToolPlanning] Prompt context prepared; latencyMs={}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - promptStartedAt));
        AiJsonSchema jsonSchema = AiJsonSchema.ofSchema(schema());
        String tenantId = principalContext == null ? null : principalContext.tenantId();
        String userId = principalContext == null ? null : principalContext.userId();
        String environment = principalContext == null ? null : principalContext.environment();
        RuntimeException lastFailure = null;
        List<AiProviderInvocationTelemetry> providerInvocations = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(planningBudgetSeconds);
        for (int attempt = 1; attempt <= providerAttempts; attempt++) {
            int attemptTimeoutSeconds = remainingTimeoutSeconds(deadlineNanos);
            if (attemptTimeoutSeconds <= 0) {
                break;
            }
            AiProviderInvocationTrace trace = new AiProviderInvocationTrace(
                    "pre_intent_tool_plan", attempt, request.provider(), request.model());
            AiCallConfig callConfig = callConfig(request, attemptTimeoutSeconds)
                    .toBuilder()
                    .invocationTrace(trace)
                    .build();
            boolean traceRecorded = false;
            try {
                JsonNode result = providerManagementService.generateJson(
                        prompt,
                        jsonSchema,
                        callConfig,
                        tenantId,
                        userId,
                        environment);
                if (!isValidStructuredPlan(result)) {
                    trace.failed("invalid_response");
                    recordInvocation(trace, providerInvocations);
                    traceRecorded = true;
                    lastFailure = new IllegalStateException(
                            "Provider returned invalid structured pre-intent planning output");
                    if (canRetryWithinBudget(attempt, deadlineNanos)) {
                        log.debug(
                                "[AgenticAuthoringPreIntentToolPlanning] Retrying provider planning after invalid structured output; attempt={}/{}",
                                attempt,
                                providerAttempts);
                        if (!sleepBeforeRetry()) {
                            break;
                        }
                        continue;
                    }
                    break;
                }
                trace.succeeded();
                recordInvocation(trace, providerInvocations);
                traceRecorded = true;
                return withProviderInvocations(toPlan(request, result), providerInvocations);
            } catch (RuntimeException ex) {
                trace.failed(AiProviderFailureClassifier.classify(ex));
                lastFailure = ex;
                if (!isRetryableProviderFailure(ex) || !canRetryWithinBudget(attempt, deadlineNanos)) {
                    break;
                }
                log.debug(
                        "[AgenticAuthoringPreIntentToolPlanning] Retrying provider planning after transient failure; attempt={}/{} failure={}",
                        attempt,
                        providerAttempts,
                        safeProviderFailureSummary(ex));
                if (!sleepBeforeRetry()) {
                    break;
                }
            } finally {
                if (!traceRecorded) {
                    recordInvocation(trace, providerInvocations);
                }
            }
        }
        log.debug("[AgenticAuthoringPreIntentToolPlanning] LLM planning skipped after provider failure: {}",
                lastFailure == null ? "" : lastFailure.getMessage());
        return AgenticAuthoringPreIntentToolPlanningResult.failed(
                "provider-error",
                lastFailure == null ? "RuntimeException" : lastFailure.getClass().getSimpleName(),
                providerInvocations);
    }

    private AgenticAuthoringPreIntentToolPlanningResult withProviderInvocations(
            AgenticAuthoringPreIntentToolPlanningResult result,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (result == null) {
            return AgenticAuthoringPreIntentToolPlanningResult.failed(
                    "planner-result-empty", "empty", providerInvocations);
        }
        if (result.planned()) {
            return AgenticAuthoringPreIntentToolPlanningResult.planned(result.plan(), providerInvocations);
        }
        if (!result.errorCode().isBlank()) {
            return AgenticAuthoringPreIntentToolPlanningResult.failed(
                    result.skipReason(), result.errorCode(), providerInvocations);
        }
        return AgenticAuthoringPreIntentToolPlanningResult.skipped(
                result.skipReason(), providerInvocations);
    }

    private boolean isValidStructuredPlan(JsonNode result) {
        if (result == null
                || !result.isObject()
                || !result.path("shouldRetrieveGovernedResources").isBoolean()) {
            return false;
        }
        if (!"praxis-agentic-authoring-pre-intent-tool-plan.v3".equals(text(result, "schemaVersion"))) {
            return false;
        }
        String authoredLayoutKind = text(result, "layoutKind");
        String layoutKind = layoutKind(result);
        if (!authoredLayoutKind.isBlank() && layoutKind.isBlank()) {
            return false;
        }
        String primaryComponent = primaryComponent(result);
        if ("single-table".equals(layoutKind)
                && (!("table".equals(text(result, "artifactKind")))
                        || !"praxis-table".equals(primaryComponent))) {
            return false;
        }
        if ("resource-master-detail".equals(layoutKind)
                && !"praxis-table".equals(primaryComponent)) {
            return false;
        }
        if ("resource-crud".equals(layoutKind)
                && !"praxis-crud".equals(primaryComponent)) {
            return false;
        }
        if ("tabs_layout".equals(layoutKind)
                && (!("page".equals(text(result, "artifactKind")))
                        || !"praxis-tabs".equals(primaryComponent))) {
            return false;
        }
        String groundingProfile = text(result, "groundingProfile");
        boolean resourceScopedGrounding = Set.of(
                        "api_resource",
                        "domain_binding",
                        "operation_verification")
                .contains(groundingProfile);
        if (!result.path("shouldRetrieveGovernedResources").asBoolean(false)
                || !"authoring_or_other".equals(text(result, "semanticIntentClass"))
                || (!resourceScopedGrounding
                        && !result.path("requiresFullIntentResolution").asBoolean(false))) {
            return true;
        }
        return StringUtils.hasText(text(
                result.path("resourceSearchFocus"),
                "primaryBusinessEntity"));
    }

    private void recordInvocation(
            AiProviderInvocationTrace trace,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        AiProviderInvocationTelemetry invocation = trace.snapshot();
        providerInvocations.add(invocation);
        AiProviderInvocationMetrics.record(invocation);
    }

    private AiCallConfig callConfig(
            AgenticAuthoringTurnStreamRequest request,
            int attemptTimeoutSeconds) {
        return AiCallConfig.agenticAuthoringBuilder()
                .provider(request.provider())
                .model(request.model())
                .providerModelOverrides(StringUtils.hasText(openAiPlanningModel)
                        ? java.util.Map.of("openai", openAiPlanningModel) : java.util.Map.of())
                .apiKey(request.apiKey())
                .temperature(0.0d)
                .maxTokens(MAX_PLANNING_TOKENS)
                .timeoutSeconds(attemptTimeoutSeconds)
                .build();
    }

    private static String normalizeModel(String value) {
        return value == null ? "" : value.trim();
    }

    private int remainingTimeoutSeconds(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0;
        }
        long wholeSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
        long roundedSeconds = wholeSeconds
                + (remainingNanos % TimeUnit.SECONDS.toNanos(1L) == 0L ? 0L : 1L);
        return (int) Math.min(planningBudgetSeconds, Math.max(1L, roundedSeconds));
    }

    private boolean canRetryWithinBudget(int attempt, long deadlineNanos) {
        if (attempt >= providerAttempts) {
            return false;
        }
        long requiredNanos = TimeUnit.SECONDS.toNanos(MIN_RETRY_BUDGET_SECONDS)
                + TimeUnit.MILLISECONDS.toNanos(providerRetryDelayMs);
        return deadlineNanos - System.nanoTime() >= requiredNanos;
    }

    private boolean hasResourceDiscoveryContext(AgenticAuthoringTurnStreamRequest request) {
        return request.contextHints() != null
                && request.contextHints().path("resourceDiscovery").isObject()
                && request.contextHints().path("resourceDiscovery").path("candidates").isArray()
                && !request.contextHints().path("resourceDiscovery").path("candidates").isEmpty();
    }

    private boolean hasSelectedDomainDecision(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("selectedDomainDecisionRef").isObject();
    }

    private AgenticAuthoringPreIntentToolPlanningResult toPlan(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode result) {
        String semanticIntentClass = text(result, "semanticIntentClass");
        if (!List.of("platform_guidance", "governed_domain_discovery", "authoring_or_other")
                .contains(semanticIntentClass)) {
            semanticIntentClass = "authoring_or_other";
        }
        if ("authoring_or_other".equals(semanticIntentClass)
                && hasSelectedDomainDecision(request)) {
            return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                    textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v3"),
                    "selected-domain-decision-deferred-to-full-semantic-resolution",
                    List.of(),
                    semanticIntentClass,
                    "",
                    true,
                    result.path("queryConstraints").deepCopy(),
                    "unknown",
                    "",
                    ""));
        }
        String assistantMessage = "platform_guidance".equals(semanticIntentClass)
                ? text(result, "assistantMessage")
                : "";
        if ("platform_guidance".equals(semanticIntentClass)) {
            if (!StringUtils.hasText(assistantMessage)) {
                return AgenticAuthoringPreIntentToolPlanningResult.skipped(
                        "llm-platform-guidance-answer-empty");
            }
            return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                    textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v3"),
                    text(result, "reason"),
                    List.of(),
                    semanticIntentClass,
                    assistantMessage,
                    false,
                    result.path("queryConstraints").deepCopy(),
                    text(result, "artifactKind"),
                    primaryComponent(result),
                    layoutKind(result)));
        }
        if (!result.path("shouldRetrieveGovernedResources").asBoolean(false)) {
            return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                    textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v3"),
                    text(result, "reason"),
                    List.of(),
                    semanticIntentClass,
                    "",
                    result.path("requiresFullIntentResolution").asBoolean(false),
                    result.path("queryConstraints").deepCopy(),
                    text(result, "artifactKind"),
                    primaryComponent(result),
                    layoutKind(result)));
        }
        String groundingProfile = text(result, "groundingProfile");
        if (!List.of("domain_context", "domain_capability", "domain_concept", "domain_binding", "operation_verification", "api_resource", "domain_decision")
                .contains(groundingProfile)) {
            groundingProfile = "api_resource";
        }
        String retrievalQuery = text(result, "retrievalQuery");
        if ("api_resource".equals(groundingProfile) && !StringUtils.hasText(retrievalQuery)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-retrieval-query-empty");
        }
        AgenticAuthoringResourceSearchFocus resourceSearchFocus =
                resourceSearchFocus(result.path("resourceSearchFocus"));
        resourceSearchFocus = reconcileDomainDiscoveryResourceFocus(request, resourceSearchFocus);
        // Once the LLM has semantically classified an executable authoring request and resolved
        // its canonical business entity, the single pre-intent read must retrieve an executable
        // API resource. Spending that call on the concept already named by the orientation forces
        // a second full-model pass and discards the stronger operational binding evidence.
        boolean concreteResourceBackedAuthoringProjection =
                "authoring_or_other".equals(semanticIntentClass)
                        && List.of("page", "dashboard", "chart", "table", "form")
                                .contains(text(result, "artifactKind"))
                        && (AUTHORABLE_PRIMARY_COMPONENTS.contains(primaryComponent(result))
                                || resourceSearchFocus != null
                                        && StringUtils.hasText(resourceSearchFocus.primaryBusinessEntity()));
        if ((!"domain_decision".equals(groundingProfile)
                        || concreteResourceBackedAuthoringProjection)
                && "authoring_or_other".equals(semanticIntentClass)
                && (result.path("requiresFullIntentResolution").asBoolean(false)
                        || resourceSearchFocus != null
                                && StringUtils.hasText(resourceSearchFocus.primaryBusinessEntity()))) {
            groundingProfile = "api_resource";
        }
        // Supporting concepts enrich governed resource retrieval; they are not, by themselves,
        // executable predicates or presentation constraints. Promoting them to a mandatory second
        // provider pass made generic authoring (for example an employee dashboard) resolve the same
        // intent twice even after a single strong operational resource had been grounded. The LLM
        // remains responsible for declaring full resolution when the user actually requests a
        // predicate, grouping, aggregation, ordering or layout constraint.
        boolean requiresFullIntentResolution =
                result.path("requiresFullIntentResolution").asBoolean(false);
        if (!"domain_decision".equals(groundingProfile)) {
            retrievalQuery = focusedRetrievalQuery(retrievalQuery, resourceSearchFocus);
        }
        String artifactKind = text(result, "artifactKind");
        if (!List.of("page", "dashboard", "chart", "table", "form", "api_catalog", "unknown")
                .contains(artifactKind)) {
            artifactKind = "page";
        }
        AgenticAuthoringToolCall toolCall = progressiveToolCall(
                request, groundingProfile, retrievalQuery, artifactKind, resourceSearchFocus);
        return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v3"),
                text(result, "reason"),
                List.of(toolCall),
                semanticIntentClass,
                "",
                requiresFullIntentResolution,
                result.path("queryConstraints").deepCopy(),
                artifactKind,
                primaryComponent(result),
                layoutKind(result)));
    }

    private AgenticAuthoringToolCall progressiveToolCall(
            AgenticAuthoringTurnStreamRequest request,
            String groundingProfile,
            String retrievalQuery,
            String artifactKind,
            AgenticAuthoringResourceSearchFocus resourceSearchFocus) {
        if ("domain_decision".equals(groundingProfile)) {
            ObjectNode payload = objectMapper.createObjectNode();
            if (StringUtils.hasText(retrievalQuery)) {
                payload.put("query", retrievalQuery.trim());
            }
            payload.put("page", 0);
            payload.put("limit", 6);
            return new AgenticAuthoringToolCall(
                    AgenticAuthoringToolRegistry.SEARCH_DOMAIN_RULES,
                    "pre_intent_resource_discovery",
                    payload);
        }
        if ("api_resource".equals(groundingProfile)) {
            return new AgenticAuthoringToolCall(
                    AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                    "pre_intent_resource_discovery",
                    new AgenticAuthoringResourceCandidatesRequest(
                            retrievalQuery,
                            request.userPrompt(),
                            artifactKind,
                            6,
                            resourceSearchFocus));
        }
        String toolName = switch (groundingProfile) {
            case "domain_context" -> AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONTEXTS;
            case "domain_capability" -> AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CAPABILITIES;
            case "domain_binding" -> AgenticAuthoringToolRegistry.INSPECT_DOMAIN_BINDINGS;
            case "operation_verification" -> AgenticAuthoringToolRegistry.VERIFY_DOMAIN_OPERATION;
            default -> AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONCEPTS;
        };
        String contextKey = request.contextHints() == null ? "" : text(request.contextHints(), "contextKey");
        String resourceKey = resourceSearchFocus == null ? "" : resourceSearchFocus.primaryBusinessEntity();
        Object payload = switch (groundingProfile) {
            case "domain_binding" -> new DomainBindingToolRequest(resourceKey, 6);
            case "operation_verification" -> new DomainOperationVerificationToolRequest(
                    resourceKey,
                    request.contextHints() == null ? "" : text(request.contextHints(), "requestBaseUrl"));
            default -> new DomainKnowledgeToolRequest(contextKey, resourceKey, 0);
        };
        return new AgenticAuthoringToolCall(toolName, "advisory_authoring", payload);
    }

    private boolean isRetryableProviderFailure(RuntimeException error) {
        if (error instanceof AiProviderCallException callException) {
            return switch (callException.getKind()) {
                case TRANSPORT, RATE_LIMIT, CAPACITY, SERVER_ERROR, UNKNOWN -> true;
                case TIMEOUT, QUOTA_EXHAUSTED, AUTH, CLIENT_ERROR -> false;
            };
        }
        String message = String.valueOf(error == null ? "" : error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("quota")
                || message.contains("billing")
                || message.contains("401")
                || message.contains("403")
                || message.contains("unauthorized")
                || message.contains("forbidden")
                || message.contains("400")
                || message.contains("bad request")) {
            return false;
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return false;
        }
        return message.contains("429")
                || message.contains("rate limit")
                || message.contains("500")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504")
                || message.contains("capacity")
                || message.contains("unavailable")
                || message.contains("transport")
                || message.contains("connect")
                || message.contains("socket");
    }

    private boolean sleepBeforeRetry() {
        if (providerRetryDelayMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(providerRetryDelayMs);
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String safeProviderFailureSummary(RuntimeException error) {
        if (error instanceof AiProviderCallException callException) {
            String status = callException.getStatusCode() == null
                    ? "none"
                    : String.valueOf(callException.getStatusCode());
            return callException.getClass().getSimpleName()
                    + "{kind=" + callException.getKind()
                    + ",status=" + status + "}";
        }
        return error == null ? "RuntimeException" : error.getClass().getSimpleName();
    }

    private String prompt(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        // Only the compact canonical resource identity index is baseline context. Detailed
        // concepts, fields, bindings, endpoints and live values remain progressively retrieved
        // after semantic orientation.
        ObjectNode context = planningContext(request, governedDomainContext(request, principalContext));
        String responseLocale = request.contextHints() == null
                ? ""
                : text(request.contextHints(), "responseLocale");
        boolean hasSelectedDomainDecision = request.contextHints() != null
                && request.contextHints().path("selectedDomainDecisionRef").isObject();
        String selectedDomainDecisionInstruction = hasSelectedDomainDecision
                ? """
                planningHints.selectedDomainDecisionRef is an exact governed semantic focus. For explanation, use
                semanticIntentClass=authoring_or_other, shouldRetrieveGovernedResources=false,
                requiresFullIntentResolution=true, artifactKind=unknown and primaryComponent=null. Defer semantic
                classification and attested reread to the full resolver; never generic-search, simulate, preview,
                apply or materialize from this untrusted hint.
                """
                : "";
        return """
                Praxis first semantic orientation and pre-intent tool planner. Decide semantically and without keyword routing
                whether this turn is platform guidance or should continue to governed authoring.
                The minimum context is always Praxis itself, the governed host domain, the current surface, the
                current page state, and the governed component catalog. A UI recommendedIntent is optional evidence
                only: never require it, and never treat its absence as missing context.

                Set semanticIntentClass=platform_guidance when the user asks what can be done here, how Praxis or
                this assistant can help, what a useful next step is, or asks for general improvement guidance without
                requesting a concrete change. In that case, set shouldRetrieveGovernedResources=false and answer in
                assistantMessage in responseLocale when it is present; otherwise use the user's language. Do not infer
                a different response language from domain labels. Ground the answer in platformGuide,
                authorableComponents, governedDomainContext, runtimeContext and the components already on the page.
                Be friendly and concrete. Do not claim a change was made and do not ask for technical endpoints.
                Keep this class for generic platform guidance.
                Feasibility questions stay platform_guidance even with an artifact or subject; they do not authorize
                creation. Preserve artifactKind. Example: "É possível criar um dashboard sobre informações salariais
                dos funcionários?" is platform_guidance, artifactKind=dashboard.

                Set semanticIntentClass=governed_domain_discovery when the user asks which actual business domains,
                themes, administrative subjects or governed business capabilities are available in the current host,
                including when the user mentions a future form, table, dashboard or page only as the reason for asking.
                This class always sets shouldRetrieveGovernedResources=true, assistantMessage empty and starts with
                groundingProfile=domain_context. It is governed domain enumeration, not generic platform guidance and
                not yet concrete artifact authoring. Semantically equivalent examples include asking which administrative
                themes are available for an interactive dashboard, what business areas the host knows, or which governed
                domains can be used before choosing what to build. A question such as "sobre quais assuntos posso criar
                tabelas ou dashboards para obter informações visuais?" is domain discovery: the user is asking for the
                available business subjects, not yet commanding creation of either artifact. The same remains true with
                grammar errors, omitted words, or without the literal word "available". This discovery must happen before capability, concept,
                binding or API-resource discovery.

                Set semanticIntentClass=authoring_or_other when the user requests creation, editing, removal,
                inspection, a concrete domain artifact, or another intent that needs the complete governed resolver.
                %s
                A request to show records on an empty canvas is UI authoring even without a component name.
                Preserve its constraints for schema-grounded materialization.
                Set queryConstraints.appliesToDataSelection=true and populate filters only for record selection.
                For headers, labels, renderers, formatting, composed cells, displayed values, or no subset, use false and
                filters=[]; retrieval metadata is never a filter.
                Preserve a semantic category, such as an organizational area, as text or a text list on this first pass.
                After resource and field grounding, live option resolution replaces it with current canonical IDs.
                Never invent an option value or use textual contains.
                Set requiresFullIntentResolution=true for semantics not completely preserved by this structure. A layout
                fully represented by layoutKind needs no second pass. Resource discovery and defaults are not constraints.
                Use groundingProfile=domain_decision for LLM semantic discovery; searchDomainRules returns identities,
                never conditions or authority.
                Select other groundingProfile values progressively: domain_context for macro business orientation,
                domain_capability when a governed context is known but the business capability is not, domain_concept
                for concepts inside an already scoped context/resource, domain_binding after a canonical resourceKey
                has been resolved but its operational binding is not yet grounded, operation_verification after a binding
                exists but its exact schema and current capability have not been checked, and api_resource only when an operational
                resource, field, dataset, binding or endpoint is actually needed. Use API resource retrieval when
                governed resources, fields, datasets or
                API-backed sources are needed for a page, view, table, dashboard, form, overview, analysis,
                monitoring surface, or data-source change. User wording may be vague, misspelled, colloquial, multilingual
                or loosely related to domainDiscovery. If domainDiscovery exists and resourceDiscovery
                is absent, use it as semantic context for retrievalQuery, not as a reason to skip.
                Return false only for visual/local/editorial work, existing resourceDiscovery, or no data grounding need.
                When retrieval is true, author only retrievalQuery and resourceSearchFocus; do not choose resourcePath,
                endpoints, configuration or patches.
                Model resourceSearchFocus in two semantic layers. primaryBusinessEntity is the canonical business
                subject explicitly requested, never a view or UI artifact; desiredSurface is presentation only. A
                collection-oriented dashboard with filters, charts and a detail table stays on that subject. Use an
                individual or single-record profile only when requested. Select another analytical projection only when
                its subject, such as payroll, is itself requested. Decide by what is being measured or explained:
                payroll is primaryBusinessEntity for employee salary or compensation; employee headcount, status, role
                or department stays on employees. For KPI/chart dashboards, prefer the governed analytical projection
                of the measured subject over contributing operational/history records. After confirmation, select the
                canonical payroll analytical resource. A related category constraining displayed records belongs in
                supportingConcepts/queryConstraints, never as primaryBusinessEntity because it ranks higher.
                When domainDiscovery contains the semantically matching business subject, use its canonical resourceKey
                exactly as primaryBusinessEntity. Titles, aliases and fields explain that resource; they do not replace
                the canonical key or authorize a different related projection.
                When governedDomainContext contains DOMAIN_RESOURCE_IDENTITY_CATALOG, semantically select the matching
                business subject from that compact catalog and return its resourceKey exactly. This is semantic selection
                by the LLM, not keyword routing. If shouldRetrieveGovernedResources=true for authoring that requires a
                business resource, primaryBusinessEntity must never be null or blank. If no canonical identity is
                available, return a concise semantic business-entity phrase in the user's language; do not move the
                displayed entity into supportingConcepts and do not use a UI artifact as the entity.
                Use artifactKind dashboard when the requested outcome depends on multiple coordinated analytical
                regions such as filters, KPIs, multiple charts and a detail/list/table surface. Use artifactKind page
                for general layout or content composition where analytics are not the dominant requested outcome.
                Choose layoutKind: single-table + table + praxis-table; resource-master-detail + praxis-table;
                parent-child-related-resource + praxis-related-resource-outlet; resource-crud + praxis-crud;
                tabs_layout + page + praxis-tabs covers Table -> state -> Form; no full pass.
                Never keyword-route or substitute primaryComponent for layoutKind.
                Canonical response locale: %s
                Context JSON: %s
                """.formatted(selectedDomainDecisionInstruction, responseLocale, context.toString());
    }

    private ObjectNode planningContext(
            AgenticAuthoringTurnStreamRequest request,
            String governedDomainContext) {
        JsonNode planningHints = compactContextHints(request.contextHints());
        String projectedPrompt = compactUserPrompt(request.userPrompt());
        AgenticAuthoringIntentResolutionRequest intentRequest = new AgenticAuthoringIntentResolutionRequest(
                projectedPrompt,
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
                planningHints,
                request.activeSemanticDecision());
        ObjectNode fullContext = AgenticAuthoringContextBundle.create(
                objectMapper,
                intentRequest,
                projectedPrompt,
                compactCurrentPage(request.currentPage()),
                null,
                List.of(),
                request.componentCapabilities(),
                governedDomainContext);
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-semantic-orientation-context.v1");
        context.set("runtimeContext", fullContext.path("runtimeContext").deepCopy());
        context.set("userIntent", fullContext.path("userIntent").deepCopy());
        context.set("governedDomainContext", fullContext.path("governedDomainContext").deepCopy());
        ObjectNode componentContext = context.putObject("componentContext");
        componentContext.set(
                "platformGuide",
                fullContext.path("componentContext").path("platformGuide").deepCopy());
        componentContext.set(
                "authorableComponents",
                compactAuthorableComponents(
                        fullContext.path("componentContext").path("authorableComponents")));
        JsonNode authoringScopePolicy = fullContext.path("componentContext").path("authoringScopePolicy");
        if (!authoringScopePolicy.isMissingNode()) {
            componentContext.set("authoringScopePolicy", authoringScopePolicy.deepCopy());
        }
        context.set("conversationContext", fullContext.path("conversationContext").deepCopy());
        context.set("planningHints", planningHints);
        if (request.userPrompt() != null && request.userPrompt().length() > MAX_PLANNER_USER_PROMPT_LENGTH) {
            context.put("userPromptOriginalLength", request.userPrompt().length());
            context.put("userPromptProjection", "head_tail_compacted");
        }
        return context;
    }

    private ArrayNode compactAuthorableComponents(JsonNode source) {
        ArrayNode compact = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return compact;
        }
        for (JsonNode component : source) {
            if (component == null || !component.isObject()) {
                continue;
            }
            ObjectNode projection = compact.addObject();
            copyText(projection, "componentId", component, "componentId");
            copyText(projection, "purpose", component, "purpose");
            copyText(projection, "bestFor", component, "bestFor");
            copyText(projection, "authoringBoundary", component, "authoringBoundary");
        }
        return compact;
    }

    private String governedDomainContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        if (domainCatalogPromptContextService == null
                || request == null
                || principalContext == null
                || !StringUtils.hasText(principalContext.tenantId())
                || !StringUtils.hasText(principalContext.environment())
                || hasExplicitDomainCatalogOptOut(request.contextHints())) {
            return "";
        }
        try {
            String identities = domainCatalogPromptContextService.buildResourceIdentityContext(
                    principalContext.tenantId(),
                    principalContext.environment(),
                    30);
            if (!hasExplicitDomainCatalogScope(request.contextHints())) {
                return identities;
            }
            String detailed = domainCatalogPromptContextService.buildPromptContext(
                        request.userPrompt(),
                        preIntentDomainCatalogContextHints(request.contextHints()),
                        principalContext.tenantId(),
                        principalContext.environment());
            if (!StringUtils.hasText(identities)) {
                return detailed;
            }
            return StringUtils.hasText(detailed) ? identities + "\n\n" + detailed : identities;
        } catch (RuntimeException ex) {
            log.debug(
                    "[AgenticAuthoringPreIntentToolPlanning] Governed domain context unavailable; tenant={} environment={} failure={}",
                    principalContext.tenantId(),
                    principalContext.environment(),
                    ex.getClass().getSimpleName());
            return "";
        }
    }

    private JsonNode preIntentDomainCatalogContextHints(JsonNode contextHints) {
        return contextHints != null && contextHints.isObject()
                ? ((ObjectNode) contextHints).deepCopy()
                : objectMapper.createObjectNode();
    }

    private boolean hasExplicitDomainCatalogScope(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return false;
        }
        JsonNode domainCatalog = contextHints.path("domainCatalog");
        return hasAnyText(
                        domainCatalog,
                        "resourceKey",
                        "contextKey",
                        "query",
                        "q",
                        "nodeType",
                        "itemType",
                        "type")
                || hasAnyText(
                        contextHints,
                        "domainResourceKey",
                        "domainContextKey",
                        "domainCatalogQuery",
                        "retrievalQuery",
                        "domainNodeType");
    }

    private boolean hasAnyText(JsonNode source, String... fields) {
        if (source == null || !source.isObject() || fields == null) {
            return false;
        }
        for (String field : fields) {
            if (hasText(source, field)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitDomainCatalogOptOut(JsonNode contextHints) {
        return contextHints != null
                && contextHints.path("domainCatalog").isObject()
                && contextHints.path("domainCatalog").has("enabled")
                && !contextHints.path("domainCatalog").path("enabled").asBoolean(true);
    }

    private String compactUserPrompt(String value) {
        String text = valueOrEmpty(value).trim();
        if (text.length() <= MAX_PLANNER_USER_PROMPT_LENGTH) {
            return text;
        }
        int edgeLength = Math.max(120, (MAX_PLANNER_USER_PROMPT_LENGTH - 80) / 2);
        String head = text.substring(0, edgeLength).trim();
        String tail = text.substring(text.length() - edgeLength).trim();
        return head + "\n...[middle omitted for planner performance]...\n" + tail;
    }

    private ObjectNode compactCurrentPage(JsonNode currentPage) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("schemaVersion", "praxis-agentic-authoring-pre-intent-current-page-projection.v1");
        compact.put("present", currentPage != null && currentPage.isObject());
        if (currentPage == null || currentPage.isMissingNode() || currentPage.isNull()) {
            return compact;
        }
        copyText(compact, "type", currentPage, "type");
        copyText(compact, "title", currentPage, "title");
        copyText(compact, "name", currentPage, "name");
        ArrayNode widgets = compact.putArray("widgets");
        collectWidgets(widgets, currentPage, 0);
        compact.put("projectedWidgetCount", widgets.size());
        compact.put("projectionPolicy", "Compact structural projection for deciding whether governed resource discovery is needed; not a page patch source.");
        return compact;
    }

    private void collectWidgets(ArrayNode widgets, JsonNode node, int depth) {
        if (node == null || widgets.size() >= MAX_PLANNER_PAGE_WIDGETS || depth > 8) {
            return;
        }
        if (node.isObject()) {
            if (isWidgetLike(node)) {
                ObjectNode widget = widgets.addObject();
                copyText(widget, "key", node, "key");
                copyText(widget, "id", node, "id");
                copyText(widget, "componentId", node, "componentId");
                copyText(widget, "type", node, "type");
                copyText(widget, "title", node, "title");
                copyText(widget, "label", node, "label");
                copyText(widget, "resourcePath", node, "resourcePath");
                copyText(widget, "schemaUrl", node, "schemaUrl");
                copyText(widget, "submitUrl", node, "submitUrl");
                copyText(widget, "submitMethod", node, "submitMethod");
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext() && widgets.size() < MAX_PLANNER_PAGE_WIDGETS) {
                collectWidgets(widgets, values.next(), depth + 1);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (widgets.size() >= MAX_PLANNER_PAGE_WIDGETS) {
                    return;
                }
                collectWidgets(widgets, item, depth + 1);
            }
        }
    }

    private boolean isWidgetLike(JsonNode node) {
        return node != null
                && node.isObject()
                && (hasText(node, "componentId")
                || hasText(node, "widgetKey")
                || hasText(node, "resourcePath")
                || hasText(node, "schemaUrl")
                || hasText(node, "submitUrl"));
    }

    private ObjectNode compactContextHints(JsonNode contextHints) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("schemaVersion", "praxis-agentic-authoring-pre-intent-context-hints-projection.v1");
        compact.put("present", contextHints != null && contextHints.isObject());
        if (contextHints == null || !contextHints.isObject()) {
            return compact;
        }
        for (String field : List.of(
                "source",
                "kind",
                "operationKind",
                "artifactKind",
                "changeKind",
                "targetComponentId",
                "selectedComponentId",
                "componentId",
                "targetWidgetKey",
                "selectedWidgetKey",
                "resourceKey",
                "contextKey",
                "serviceKey",
                "resourcePath",
                "schemaUrl",
                "submitUrl",
                "submitMethod",
                "operation",
                "requestBaseUrl")) {
            copyText(compact, field, contextHints, field);
        }
        copyCompactArray(compact, "domainDiscovery", contextHints.path("domainDiscovery"));
        copyCompactObject(compact, "domainCatalog", contextHints.path("domainCatalog"));
        copyCompactObject(compact, "projectKnowledge", contextHints.path("projectKnowledge"));
        copyCompactObject(compact, "domainBindings", contextHints.path("domainBindings"));
        copyCompactObject(compact, "verifiedDomainOperations", contextHints.path("verifiedDomainOperations"));
        copyCompactObject(compact, "groundedRuntimeComponentContext", contextHints.path("groundedRuntimeComponentContext"));
        copyCompactObject(compact, "selectedDomainDecisionRef", contextHints.path("selectedDomainDecisionRef"));
        return compact;
    }

    private void copyCompactObject(ObjectNode target, String name, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        ObjectNode object = target.putObject(name);
        int copied = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext() && copied < MAX_PLANNER_CONTEXT_ARRAY_ITEMS) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                setCompactValue(object, field.getKey(), value);
                copied++;
            } else if (value.isArray()) {
                copyCompactArray(object, field.getKey(), value);
                copied++;
            }
        }
    }

    private void copyCompactArray(ObjectNode target, String name, JsonNode source) {
        if (source == null || !source.isArray()) {
            return;
        }
        ArrayNode array = target.putArray(name);
        int count = 0;
        for (JsonNode item : source) {
            if (count >= MAX_PLANNER_CONTEXT_ARRAY_ITEMS) {
                break;
            }
            if (item == null || item.isNull() || item.isMissingNode()) {
                continue;
            }
            if (item.isObject()) {
                ObjectNode compactItem = array.addObject();
                copyCommonCompactFields(compactItem, item);
                copyCompactArray(compactItem, "aliases", item.path("aliases"));
                copyCompactArray(compactItem, "tags", item.path("tags"));
            } else if (item.isTextual() || item.isNumber() || item.isBoolean()) {
                array.add(compactText(item.asText()));
            }
            count++;
        }
    }

    private void copyCommonCompactFields(ObjectNode target, JsonNode source) {
        for (String field : List.of(
                "resourceKey",
                "contextKey",
                "serviceKey",
                "title",
                "label",
                "name",
                "description",
                "resourcePath",
                "schemaUrl",
                "knowledgeId",
                "conceptKey",
                "kind",
                "summary",
                "sourceSummary",
                "influence",
                "visibility",
                "resourceRole",
                "classification",
                "dataCategory",
                "intent",
                "type",
                "nodeType",
                "query")) {
            copyText(target, field, source, field);
        }
    }

    private void setCompactValue(ObjectNode target, String field, JsonNode value) {
        if (value.isTextual()) {
            target.put(field, compactText(value.asText()));
        } else if (value.isNumber()) {
            target.set(field, value);
        } else if (value.isBoolean()) {
            target.put(field, value.asBoolean());
        }
    }

    private void copyText(ObjectNode target, String targetField, JsonNode source, String sourceField) {
        if (source != null && source.path(sourceField).isTextual() && StringUtils.hasText(source.path(sourceField).asText())) {
            target.put(targetField, compactText(source.path(sourceField).asText()));
        }
    }

    private boolean hasText(JsonNode source, String field) {
        return source != null && source.path(field).isTextual() && StringUtils.hasText(source.path(field).asText());
    }

    private String compactText(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= MAX_PLANNER_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_PLANNER_TEXT_LENGTH) + "...";
    }

    private String schema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ObjectNode schemaVersion = properties.putObject("schemaVersion");
        schemaVersion.put("type", "string");
        schemaVersion.putArray("enum")
                .add("praxis-agentic-authoring-pre-intent-tool-plan.v3");
        ObjectNode semanticIntentClass = properties.putObject("semanticIntentClass");
        semanticIntentClass.put("type", "string");
        semanticIntentClass.putArray("enum")
                .add("platform_guidance")
                .add("governed_domain_discovery")
                .add("authoring_or_other");
        semanticIntentClass.put(
                "description",
                "Semantic routing decision authored by the model. Use platform_guidance for feasibility or capability questions even when a concrete artifact or domain subject is named; use governed_domain_discovery for questions asking which business subjects, themes, or domains can be used for possible tables, dashboards, forms, or pages. Neither form is concrete authoring without an explicit creation or modification request.");
        properties.putObject("assistantMessage").put("type", "string");
        properties.putObject("shouldRetrieveGovernedResources").put("type", "boolean");
        properties.putObject("requiresFullIntentResolution")
                .put("type", "boolean")
                .put("description", "True when user-requested semantics are not completely preserved by this structured plan. A layout fully represented by layoutKind, artifact kind, resource discovery and governed defaults do not require a second pass by themselves.");
        ObjectNode queryConstraints = properties.putObject("queryConstraints");
        queryConstraints.put("type", "object");
        ObjectNode queryConstraintProperties = queryConstraints.putObject("properties");
        queryConstraintProperties.putObject("appliesToDataSelection")
                .put("type", "boolean")
                .put(
                        "description",
                        "True only when filters constrain which backend records are retrieved or displayed; false for headers, labels, renderers, formatting, composed cells, and displayed-value edits.");
        ObjectNode queryFilters = queryConstraintProperties.putObject("filters");
        queryFilters.put("type", "array");
        ObjectNode queryFilter = queryFilters.putObject("items");
        queryFilter.put("type", "object");
        ObjectNode queryFilterProperties = queryFilter.putObject("properties");
        queryFilterProperties.putObject("concept").put("type", "string");
        queryFilterProperties.putObject("field").put("type", "string");
        ObjectNode queryOperator = queryFilterProperties.putObject("operator");
        queryOperator.put("type", "string");
        queryOperator.putArray("enum").add("eq").add("contains").add("in").add("gte").add("lte").add("between");
        ObjectNode queryValue = queryFilterProperties.putObject("value");
        ArrayNode queryValueAlternatives = queryValue.putArray("anyOf");
        queryValueAlternatives.addObject().put("type", "string");
        queryValueAlternatives.addObject().put("type", "number");
        queryValueAlternatives.addObject().put("type", "boolean");
        queryValueAlternatives.addObject().put("type", "null");
        ObjectNode queryValueArray = queryValueAlternatives.addObject();
        queryValueArray.put("type", "array");
        queryValueArray.put("minItems", 1);
        queryValueArray.put("maxItems", 100);
        ObjectNode queryValueArrayItems = queryValueArray.putObject("items");
        ArrayNode queryValueArrayItemTypes = queryValueArrayItems.putArray("anyOf");
        queryValueArrayItemTypes.addObject().put("type", "string");
        queryValueArrayItemTypes.addObject().put("type", "number");
        queryValueArrayItemTypes.addObject().put("type", "boolean");
        queryFilter.putArray("required").add("concept").add("field").add("operator").add("value");
        queryFilter.put("additionalProperties", false);
        queryConstraints.putArray("required").add("appliesToDataSelection").add("filters");
        queryConstraints.put("additionalProperties", false);
        ObjectNode groundingProfile = properties.putObject("groundingProfile");
        groundingProfile.put("type", "string");
        groundingProfile.putArray("enum")
                .add("none")
                .add("domain_context")
                .add("domain_capability")
                .add("domain_concept")
                .add("domain_binding")
                .add("operation_verification")
                .add("domain_decision")
                .add("api_resource");
        ObjectNode artifactKind = properties.putObject("artifactKind");
        artifactKind.put("type", "string");
        ArrayNode artifactEnum = artifactKind.putArray("enum");
        for (String value : List.of("page", "dashboard", "chart", "table", "form", "api_catalog", "unknown")) {
            artifactEnum.add(value);
        }
        ObjectNode primaryComponent = properties.putObject("primaryComponent");
        primaryComponent.putArray("type").add("string").add("null");
        ArrayNode primaryComponentEnum = primaryComponent.putArray("enum");
        AUTHORABLE_PRIMARY_COMPONENTS.forEach(primaryComponentEnum::add);
        primaryComponentEnum.addNull();
        primaryComponent.put(
                "description",
                "Primary runtime component selected after semantic intent; it does not decide layoutKind.");
        ObjectNode layoutKind = properties.putObject("layoutKind");
        layoutKind.putArray("type").add("string").add("null");
        ArrayNode layoutKindEnum = layoutKind.putArray("enum");
        COMPACT_RESOURCE_COMPOSITION_LAYOUTS.forEach(layoutKindEnum::add);
        layoutKindEnum.addNull();
        layoutKind.put(
                "description",
                "AI-authored semantic composition archetype: single-table, resource-master-detail, parent-child-related-resource, resource-crud, or tabs_layout. tabs_layout requires artifactKind=page and primaryComponent=praxis-tabs; it completely represents a governed collection Table and Dynamic Form detail synchronized through persisted state and nestedPath links, so those materialization details do not require another intent pass.");
        nullableString(properties, "retrievalQuery");
        nullableString(properties, "reason");
        ObjectNode focus = properties.putObject("resourceSearchFocus");
        focus.put("type", "object");
        ObjectNode focusProperties = focus.putObject("properties");
        nullableString(focusProperties, "primaryBusinessEntity")
                .put("description", "Canonical business subject explicitly requested by the user. When domainDiscovery provides the matching subject, return its resourceKey exactly. Use the entity being governed, filtered, listed, or analyzed; never substitute a UI surface, profile/view name, or related analytical projection.");
        ObjectNode supportingConcepts = focusProperties.putObject("supportingConcepts");
        supportingConcepts.put("type", "array");
        supportingConcepts.putObject("items").put("type", "string");
        supportingConcepts.put("maxItems", 8);
        supportingConcepts.put("description", "Dimensions, fields, filters, groupings, and supporting concepts that refine the primary business entity without replacing it.");
        nullableString(focusProperties, "desiredSurface")
                .put("description", "Presentation and interaction requested by the user, such as a collection dashboard with filters, charts, and a detail table, or an explicitly individual profile.");
        nullableString(focusProperties, "uncertainty")
                .put("description", "Material semantic ambiguity that remains after separating the business subject from its presentation.");
        nullableString(focusProperties, "rationale")
                .put("description", "Short explanation of why the primary business entity is the correct grounding subject and how supporting concepts and desired surface relate to it.");
        focus.putArray("required")
                .add("primaryBusinessEntity")
                .add("supportingConcepts")
                .add("desiredSurface")
                .add("uncertainty")
                .add("rationale");
        focus.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        required.add("schemaVersion")
                .add("semanticIntentClass")
                .add("assistantMessage")
                .add("shouldRetrieveGovernedResources")
                .add("requiresFullIntentResolution")
                .add("queryConstraints")
                .add("groundingProfile")
                .add("artifactKind")
                .add("primaryComponent")
                .add("layoutKind")
                .add("retrievalQuery")
                .add("resourceSearchFocus")
                .add("reason");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private ObjectNode nullableString(ObjectNode properties, String name) {
        ObjectNode node = properties.putObject(name);
        ArrayNode type = node.putArray("type");
        type.add("string");
        type.add("null");
        return node;
    }

    private String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }

    private String primaryComponent(JsonNode node) {
        String componentId = text(node, "primaryComponent");
        return AUTHORABLE_PRIMARY_COMPONENTS.contains(componentId) ? componentId : "";
    }

    private String layoutKind(JsonNode node) {
        String value = text(node, "layoutKind");
        return COMPACT_RESOURCE_COMPOSITION_LAYOUTS.contains(value) ? value : "";
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private AgenticAuthoringResourceSearchFocus resourceSearchFocus(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new AgenticAuthoringResourceSearchFocus("", List.of(), "", "", "");
        }
        List<String> supportingConcepts = node.path("supportingConcepts").isArray()
                ? streamTextValues(node.path("supportingConcepts"))
                : List.of();
        return new AgenticAuthoringResourceSearchFocus(
                text(node, "primaryBusinessEntity"),
                supportingConcepts,
                text(node, "desiredSurface"),
                text(node, "uncertainty"),
                text(node, "rationale"));
    }

    /**
     * Grounds an already LLM-authored business subject in the canonical resource keys supplied by
     * domain discovery. This is post-resolution target grounding: it never decides whether the turn
     * needs a resource or which authoring intent applies. Ambiguous identity matches remain untouched
     * so the normal semantic retrieval/clarification flow can resolve them safely.
     */
    private AgenticAuthoringResourceSearchFocus reconcileDomainDiscoveryResourceFocus(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceSearchFocus focus) {
        if (request == null
                || focus == null
                || !StringUtils.hasText(focus.primaryBusinessEntity())
                || request.contextHints() == null) {
            return focus;
        }
        JsonNode domainDiscovery = request.contextHints().path("domainDiscovery");
        if (!domainDiscovery.isArray() || domainDiscovery.isEmpty()) {
            return focus;
        }
        List<DomainDiscoveryResourceMatch> matches = new ArrayList<>();
        for (JsonNode item : domainDiscovery) {
            String resourceKey = canonicalDomainDiscoveryResourceKey(item);
            if (!StringUtils.hasText(resourceKey)) {
                continue;
            }
            int score = domainDiscoveryIdentityScore(focus.primaryBusinessEntity(), item, resourceKey);
            if (score > 0) {
                matches.add(new DomainDiscoveryResourceMatch(resourceKey, score));
            }
        }
        matches.sort((left, right) -> Integer.compare(right.score(), left.score()));
        if (matches.isEmpty() || matches.get(0).score() < 65) {
            return focus;
        }
        DomainDiscoveryResourceMatch best = matches.get(0);
        if (matches.size() > 1 && matches.get(1).score() >= best.score() - 10) {
            return focus;
        }
        if (best.resourceKey().equals(focus.primaryBusinessEntity())) {
            return focus;
        }
        return new AgenticAuthoringResourceSearchFocus(
                best.resourceKey(),
                focus.supportingConcepts(),
                focus.desiredSurface(),
                focus.uncertainty(),
                focus.rationale());
    }

    private int domainDiscoveryIdentityScore(
            String semanticEntity,
            JsonNode item,
            String resourceKey) {
        String normalizedEntity = normalizeIdentity(semanticEntity);
        if (normalizedEntity.isBlank()) {
            return 0;
        }
        String normalizedResourceKey = normalizeIdentity(resourceKey);
        String terminalResourceKey = resourceKey.substring(resourceKey.lastIndexOf('.') + 1);
        int score = identityScore(normalizedEntity, normalizedResourceKey, 110, 72);
        score = Math.max(score, identityScore(
                normalizedEntity,
                normalizeIdentity(terminalResourceKey),
                105,
                76));
        for (String field : List.of("title", "label", "name")) {
            score = Math.max(score, identityScore(
                    normalizedEntity,
                    normalizeIdentity(text(item, field)),
                    100,
                    70));
        }
        JsonNode aliases = item.path("aliases");
        if (aliases.isArray()) {
            for (JsonNode alias : aliases) {
                if (alias != null && alias.isTextual()) {
                    score = Math.max(score, identityScore(
                            normalizedEntity,
                            normalizeIdentity(alias.asText()),
                            95,
                            68));
                }
            }
        }
        return score;
    }

    private int identityScore(
            String normalizedEntity,
            String normalizedIdentity,
            int exactScore,
            int containmentScore) {
        if (normalizedEntity.isBlank() || normalizedIdentity.isBlank()) {
            return 0;
        }
        if (normalizedEntity.equals(normalizedIdentity)) {
            return exactScore;
        }
        if (normalizedEntity.length() >= 4 && normalizedIdentity.contains(normalizedEntity)
                || normalizedIdentity.length() >= 4 && normalizedEntity.contains(normalizedIdentity)) {
            return containmentScore;
        }
        return 0;
    }

    private String canonicalDomainDiscoveryResourceKey(JsonNode item) {
        String resourceKey = text(item, "resourceKey");
        if (!StringUtils.hasText(resourceKey)) {
            return "";
        }
        String canonical = resourceKey.trim();
        if (canonical.startsWith("/api/")) {
            canonical = canonical.substring(5);
        }
        canonical = canonical
                .replace('/', '.')
                .replaceAll("^\\.+|\\.+$", "")
                .replaceAll("\\.+", ".");
        return canonical.matches("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+$") ? canonical : "";
    }

    private String normalizeIdentity(String value) {
        return Normalizer.normalize(valueOrEmpty(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record DomainDiscoveryResourceMatch(String resourceKey, int score) {
    }

    private List<String> streamTextValues(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private String focusedRetrievalQuery(
            String retrievalQuery,
            AgenticAuthoringResourceSearchFocus resourceSearchFocus) {
        if (resourceSearchFocus == null || resourceSearchFocus.isEmpty()) {
            return retrievalQuery;
        }
        String prefix = resourceSearchFocus.toRetrievalQueryPrefix();
        if (!StringUtils.hasText(prefix)) {
            return retrievalQuery;
        }
        return prefix + " semantic query: " + retrievalQuery;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
