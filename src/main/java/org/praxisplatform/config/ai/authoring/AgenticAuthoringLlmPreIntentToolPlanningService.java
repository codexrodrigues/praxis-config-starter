package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class AgenticAuthoringLlmPreIntentToolPlanningService implements AgenticAuthoringPreIntentToolPlanningService {

    private static final Logger log = LoggerFactory.getLogger(AgenticAuthoringLlmPreIntentToolPlanningService.class);
    private static final int MAX_PLANNING_TOKENS = 900;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, DEFAULT_TIMEOUT_SECONDS);
    }

    AgenticAuthoringLlmPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            int timeoutSeconds) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService,
                "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
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
        try {
            JsonNode result = providerManagementService.generateJson(
                    prompt(request),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_PLANNING_TOKENS)
                            .timeoutSeconds(timeoutSeconds)
                            .build(),
                    principalContext == null ? null : principalContext.tenantId(),
                    principalContext == null ? null : principalContext.userId(),
                    principalContext == null ? null : principalContext.environment());
            return toPlan(request, result);
        } catch (RuntimeException ex) {
            log.debug("[AgenticAuthoringPreIntentToolPlanning] LLM planning skipped after provider failure: {}",
                    ex.getMessage());
            return AgenticAuthoringPreIntentToolPlanningResult.failed(
                    "provider-error",
                    ex.getClass().getSimpleName());
        }
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
        if (result == null || !result.path("shouldRetrieveGovernedResources").asBoolean(false)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-no-tool-requested");
        }
        String retrievalQuery = text(result, "retrievalQuery");
        if (!StringUtils.hasText(retrievalQuery)) {
            return AgenticAuthoringPreIntentToolPlanningResult.skipped("llm-retrieval-query-empty");
        }
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
                        6));
        return AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                textOrDefault(result, "schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v1"),
                text(result, "reason"),
                List.of(toolCall)));
    }

    private String prompt(AgenticAuthoringTurnStreamRequest request) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-pre-intent-planning-context.v1");
        context.put("userPrompt", request.userPrompt());
        context.put("targetApp", valueOrEmpty(request.targetApp()));
        context.put("targetComponentId", valueOrEmpty(request.targetComponentId()));
        context.put("currentRoute", valueOrEmpty(request.currentRoute()));
        context.set("currentPage", request.currentPage() == null ? objectMapper.createObjectNode() : request.currentPage());
        context.set("contextHints", request.contextHints() == null ? objectMapper.createObjectNode() : request.contextHints());
        return """
                You are the Praxis pre-intent tool planner.

                Decide whether the assistant needs read-only governed resource discovery before resolving the user's authoring intent.
                Use reasoning, not keyword matching. The user may use misspellings, slang, synonyms or incomplete domain wording.

                You may only request this tool:
                - searchApiResources: discovers governed API resources from backend catalogs using a semantic retrievalQuery.

                Return shouldRetrieveGovernedResources=true only when the next safe step is to search governed data resources,
                fields, datasets or API-backed sources before creating, changing, explaining, or reconnecting a dynamic component.
                Return false when the request is purely visual, local/editorial, already grounded by resourceDiscovery, or does not need data grounding.

                If the user asks for a page, view, table, dashboard, form, overview, analysis, monitoring experience, or
                any other dynamic surface that appears to depend on business records, prefer governed resource discovery.
                This remains true when the user wording is vague, misspelled, colloquial, multilingual, or only loosely
                related to the domain terms in contextHints.domainDiscovery. When domainDiscovery is present but
                resourceDiscovery is absent, treat domainDiscovery as semantic context for the retrievalQuery, not as a
                reason to skip discovery.

                If true, write retrievalQuery as a natural-language semantic search query authored by you. Include domain concepts,
                likely business vocabulary and purpose. Do not output endpoint paths unless the user explicitly provided one.
                Do not select a resourcePath. Do not create configuration. Do not answer the user.

                Context:
                %s
                """.formatted(context.toPrettyString());
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
        ArrayNode required = root.putArray("required");
        required.add("schemaVersion")
                .add("shouldRetrieveGovernedResources")
                .add("artifactKind")
                .add("retrievalQuery")
                .add("reason");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private void nullableString(ObjectNode properties, String name) {
        ObjectNode node = properties.putObject(name);
        ArrayNode type = node.putArray("type");
        type.add("string");
        type.add("null");
    }

    private String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
