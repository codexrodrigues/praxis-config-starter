package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiProviderManagementService;
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
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_PROVIDER_ATTEMPTS = 2;
    private static final long DEFAULT_PROVIDER_RETRY_DELAY_MS = 250L;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;
    private final int providerAttempts;
    private final long providerRetryDelayMs;

    public AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, DEFAULT_TIMEOUT_SECONDS);
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            int timeoutSeconds) {
        this(providerManagementService, objectMapper, timeoutSeconds, DEFAULT_PROVIDER_ATTEMPTS,
                DEFAULT_PROVIDER_RETRY_DELAY_MS);
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            int timeoutSeconds,
            int providerAttempts,
            long providerRetryDelayMs) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService,
                "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
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
        String prompt = prompt(request);
        AiJsonSchema jsonSchema = AiJsonSchema.ofSchema(schema());
        AiCallConfig callConfig = AiCallConfig.builder()
                .provider(request.provider())
                .model(request.model())
                .apiKey(request.apiKey())
                .temperature(0.0d)
                .maxTokens(MAX_PLANNING_TOKENS)
                .timeoutSeconds(timeoutSeconds)
                .build();
        String tenantId = principalContext == null ? null : principalContext.tenantId();
        String userId = principalContext == null ? null : principalContext.userId();
        String environment = principalContext == null ? null : principalContext.environment();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= providerAttempts; attempt++) {
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
                    lastFailure = new IllegalStateException(
                            "Provider returned invalid structured pre-intent planning output");
                    if (attempt < providerAttempts) {
                        log.debug(
                                "[AgenticAuthoringPreIntentToolPlanning] Retrying provider planning after invalid structured output; attempt={}/{}",
                                attempt,
                                providerAttempts);
                        sleepBeforeRetry();
                        continue;
                    }
                    break;
                }
                return toPlan(request, result);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt >= providerAttempts || !isRetryableProviderFailure(ex)) {
                    break;
                }
                log.debug(
                        "[AgenticAuthoringPreIntentToolPlanning] Retrying provider planning after transient failure; attempt={}/{} failure={}",
                        attempt,
                        providerAttempts,
                        safeProviderFailureSummary(ex));
                sleepBeforeRetry();
            }
        }
        log.debug("[AgenticAuthoringPreIntentToolPlanning] LLM planning skipped after provider failure: {}",
                lastFailure == null ? "" : lastFailure.getMessage());
        return AgenticAuthoringPreIntentToolPlanningResult.failed(
                "provider-error",
                lastFailure == null ? "RuntimeException" : lastFailure.getClass().getSimpleName());
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
        if (!result.path("shouldRetrieveGovernedResources").asBoolean(false)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-no-tool-requested");
        }
        String retrievalQuery = text(result, "retrievalQuery");
        if (!StringUtils.hasText(retrievalQuery)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-retrieval-query-empty");
        }
        AgenticAuthoringResourceSearchFocus resourceSearchFocus =
                resourceSearchFocus(result.path("resourceSearchFocus"));
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
                textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v1"),
                text(result, "reason"),
                List.of(toolCall)));
    }

    private boolean isRetryableProviderFailure(RuntimeException error) {
        if (error instanceof AiProviderCallException callException) {
            return switch (callException.getKind()) {
                case TRANSPORT, TIMEOUT, RATE_LIMIT, CAPACITY, SERVER_ERROR, UNKNOWN -> true;
                case QUOTA_EXHAUSTED, AUTH, CLIENT_ERROR -> false;
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
        return message.contains("timeout")
                || message.contains("timed out")
                || message.contains("429")
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

    private void sleepBeforeRetry() {
        if (providerRetryDelayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(providerRetryDelayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
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

    private String prompt(AgenticAuthoringTurnStreamRequest request) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-pre-intent-planning-context.v1");
        putProjectedUserPrompt(context, request.userPrompt());
        context.put("targetApp", valueOrEmpty(request.targetApp()));
        context.put("targetComponentId", valueOrEmpty(request.targetComponentId()));
        context.put("currentRoute", valueOrEmpty(request.currentRoute()));
        context.set("currentPage", compactCurrentPage(request.currentPage()));
        context.set("contextHints", compactContextHints(request.contextHints()));
        return """
                Praxis pre-intent tool planner. Decide semantically and without keyword routing whether to run
                searchApiResources before authoring. Use the tool when governed resources, fields, datasets or
                API-backed sources are needed for a page, view, table, dashboard, form, overview, analysis,
                monitoring surface, or data-source change. User wording may be vague, misspelled, colloquial, multilingual
                or loosely related to domainDiscovery. If domainDiscovery exists and resourceDiscovery
                is absent, use it as semantic context for retrievalQuery, not as a reason to skip.
                Return false only for visual/local/editorial work, existing resourceDiscovery, or no data grounding need.
                When true, author only retrievalQuery and resourceSearchFocus; do not choose resourcePath, endpoints,
                configuration, patches, or a user answer.
                Model resourceSearchFocus in two separate semantic layers. primaryBusinessEntity is the canonical
                business subject explicitly requested by the user; it must not become the name of a view, projection,
                visualization, profile, or dashboard merely because that presentation is available. desiredSurface
                describes presentation and interaction only. A collection-oriented dashboard that filters or groups
                many records and keeps a detail table remains grounded in the primary business entity, even when its
                presentation includes charts, metrics, or a 360-degree overview. Select a profile projection only when
                the user semantically requests an individual or single-record profile. Select another analytical
                projection only when that projection's business subject, such as payroll, is itself requested.
                Use artifactKind dashboard when the requested outcome depends on multiple coordinated analytical
                regions such as filters, KPIs, multiple charts and a detail/list/table surface. Use artifactKind page
                for general layout or content composition where analytics are not the dominant requested outcome.
                Context JSON: %s
                """.formatted(context.toString());
    }

    private void putProjectedUserPrompt(ObjectNode context, String userPrompt) {
        String safePrompt = valueOrEmpty(userPrompt);
        context.put("userPrompt", compactUserPrompt(safePrompt));
        if (safePrompt.length() > MAX_PLANNER_USER_PROMPT_LENGTH) {
            context.put("userPromptOriginalLength", safePrompt.length());
            context.put("userPromptProjection", "head_tail_compacted");
        }
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
                .put("description", "Canonical business subject explicitly requested by the user. Use the entity being governed, filtered, listed, or analyzed; never substitute a UI surface, profile/view name, or related analytical projection.");
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
