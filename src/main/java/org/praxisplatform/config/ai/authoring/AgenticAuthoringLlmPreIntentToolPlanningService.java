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
    private static final int MAX_PLANNER_USER_PROMPT_LENGTH = 1400;
    private static final int DEFAULT_PLANNING_BUDGET_SECONDS = 12;
    private static final int DEFAULT_PROVIDER_ATTEMPTS = 2;
    private static final long DEFAULT_PROVIDER_RETRY_DELAY_MS = 250L;
    private static final int MIN_RETRY_BUDGET_SECONDS = 2;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;
    private final int planningBudgetSeconds;
    private final int providerAttempts;
    private final long providerRetryDelayMs;

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
                DEFAULT_PROVIDER_RETRY_DELAY_MS);
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
                DEFAULT_PROVIDER_RETRY_DELAY_MS);
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
                providerRetryDelayMs);
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int planningBudgetSeconds,
            int providerAttempts,
            long providerRetryDelayMs) {
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
        String prompt = prompt(request, principalContext);
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
                if (result == null
                        || !result.isObject()
                        || !result.path("shouldRetrieveGovernedResources").isBoolean()) {
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
        return AiCallConfig.builder()
                .provider(request.provider())
                .model(request.model())
                .apiKey(request.apiKey())
                .temperature(0.0d)
                .maxTokens(MAX_PLANNING_TOKENS)
                .timeoutSeconds(attemptTimeoutSeconds)
                .build();
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

    private AgenticAuthoringPreIntentToolPlanningResult toPlan(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode result) {
        String semanticIntentClass = text(result, "semanticIntentClass");
        if (!List.of("platform_guidance", "authoring_or_other").contains(semanticIntentClass)) {
            semanticIntentClass = "authoring_or_other";
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
                    textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v2"),
                    text(result, "reason"),
                    List.of(),
                    semanticIntentClass,
                    assistantMessage));
        }
        if (!result.path("shouldRetrieveGovernedResources").asBoolean(false)) {
            return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                    textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v2"),
                    text(result, "reason"),
                    List.of(),
                    semanticIntentClass,
                    ""));
        }
        String retrievalQuery = text(result, "retrievalQuery");
        if (!StringUtils.hasText(retrievalQuery)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-retrieval-query-empty");
        }
        AgenticAuthoringResourceSearchFocus resourceSearchFocus =
                resourceSearchFocus(result.path("resourceSearchFocus"));
        resourceSearchFocus = reconcileDomainDiscoveryResourceFocus(request, resourceSearchFocus);
        retrievalQuery = focusedRetrievalQuery(retrievalQuery, resourceSearchFocus);
        String artifactKind = text(result, "artifactKind");
        if (!List.of("page", "dashboard", "chart", "table", "form", "api_catalog").contains(artifactKind)) {
            artifactKind = "page";
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                "pre_intent_resource_discovery",
                new AgenticAuthoringResourceCandidatesRequest(
                        retrievalQuery,
                        request.userPrompt(),
                        artifactKind,
                        6,
                        resourceSearchFocus));
        return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v2"),
                text(result, "reason"),
                List.of(toolCall),
                semanticIntentClass,
                ""));
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
        String governedDomainContext = governedDomainContext(request, principalContext);
        ObjectNode context = planningContext(request, governedDomainContext);
        return """
                Praxis first semantic orientation and pre-intent tool planner. Decide semantically and without keyword routing
                whether this turn is platform guidance or should continue to governed authoring.
                The minimum context is always Praxis itself, the governed host domain, the current surface, the
                current page state, and the governed component catalog. A UI recommendedIntent is optional evidence
                only: never require it, and never treat its absence as missing context.

                Set semanticIntentClass=platform_guidance when the user asks what can be done here, how Praxis or
                this assistant can help, what a useful next step is, or asks for general improvement guidance without
                requesting a concrete change. In that case, set shouldRetrieveGovernedResources=false and answer in
                assistantMessage naturally in the user's language. Ground the answer in platformGuide,
                authorableComponents, governedDomainContext, runtimeContext and the components already on the page.
                Be friendly and concrete. Do not claim a change was made and do not ask for technical endpoints.

                Set semanticIntentClass=authoring_or_other when the user requests creation, editing, removal,
                inspection, a concrete domain artifact, or another intent that needs the complete governed resolver.
                For that class, leave assistantMessage empty and decide whether to run searchApiResources before
                authoring. Use the tool when governed resources, fields, datasets or
                API-backed sources are needed for a page, view, table, dashboard, form, overview, analysis,
                monitoring surface, or data-source change. User wording may be vague, misspelled, colloquial, multilingual
                or loosely related to domainDiscovery. If domainDiscovery exists and resourceDiscovery
                is absent, use it as semantic context for retrievalQuery, not as a reason to skip.
                Return false only for visual/local/editorial work, existing resourceDiscovery, or no data grounding need.
                When retrieval is true, author only retrievalQuery and resourceSearchFocus; do not choose resourcePath,
                endpoints, configuration or patches.
                Model resourceSearchFocus in two separate semantic layers. primaryBusinessEntity is the canonical
                business subject explicitly requested by the user; it must not become the name of a view, projection,
                visualization, profile, or dashboard merely because that presentation is available. desiredSurface
                describes presentation and interaction only. A collection-oriented dashboard that filters or groups
                many records and keeps a detail table remains grounded in the primary business entity, even when its
                presentation includes charts, metrics, or a 360-degree overview. Select a profile projection only when
                the user semantically requests an individual or single-record profile. Select another analytical
                projection only when that projection's business subject, such as payroll, is itself requested.
                When domainDiscovery contains the semantically matching business subject, use its canonical resourceKey
                exactly as primaryBusinessEntity. Titles, aliases and fields explain that resource; they do not replace
                the canonical key or authorize a different related projection.
                Use artifactKind dashboard when the requested outcome depends on multiple coordinated analytical
                regions such as filters, KPIs, multiple charts and a detail/list/table surface. Use artifactKind page
                for general layout or content composition where analytics are not the dominant requested outcome.
                Context JSON: %s
                """.formatted(context.toString());
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
                fullContext.path("componentContext").path("authorableComponents").deepCopy());
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

    private String governedDomainContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        if (domainCatalogPromptContextService == null
                || request == null
                || principalContext == null
                || !StringUtils.hasText(principalContext.tenantId())
                || !StringUtils.hasText(principalContext.environment())) {
            return "";
        }
        try {
            return domainCatalogPromptContextService.buildPromptContext(
                    request.userPrompt(),
                    preIntentDomainCatalogContextHints(request.contextHints()),
                    principalContext.tenantId(),
                    principalContext.environment());
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
        ObjectNode governedHints = contextHints != null && contextHints.isObject()
                ? ((ObjectNode) contextHints).deepCopy()
                : objectMapper.createObjectNode();
        if (!governedHints.path("domainCatalog").isObject()
                && !hasText(governedHints, "domainCatalogServiceKey")) {
            governedHints.putObject("domainCatalog").put("enabled", true);
        }
        return governedHints;
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
        copyCompactObject(compact, "groundedRuntimeComponentContext", contextHints.path("groundedRuntimeComponentContext"));
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
        properties.putObject("schemaVersion").put("type", "string");
        ObjectNode semanticIntentClass = properties.putObject("semanticIntentClass");
        semanticIntentClass.put("type", "string");
        semanticIntentClass.putArray("enum")
                .add("platform_guidance")
                .add("authoring_or_other");
        properties.putObject("assistantMessage").put("type", "string");
        properties.putObject("shouldRetrieveGovernedResources").put("type", "boolean");
        ObjectNode artifactKind = properties.putObject("artifactKind");
        artifactKind.put("type", "string");
        ArrayNode artifactEnum = artifactKind.putArray("enum");
        for (String value : List.of("page", "dashboard", "chart", "table", "form", "api_catalog", "unknown")) {
            artifactEnum.add(value);
        }
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
                .add("artifactKind")
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
