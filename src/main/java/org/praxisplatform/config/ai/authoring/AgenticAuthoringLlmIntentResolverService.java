package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.springframework.util.StringUtils;

public class AgenticAuthoringLlmIntentResolverService {

    private static final String SYSTEM_PROMPT_TEMPLATE_ID = "ai-authoring/page-builder-system-prompt.v1.md";
    private static final String SYSTEM_PROMPT_TEMPLATE = loadSystemPromptTemplate();
    private static final int MAX_ASSISTANT_MESSAGE_CHARS = 700;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, null);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogPromptContextService = domainCatalogPromptContextService;
    }

    public Optional<AgenticAuthoringLlmIntentResolution> resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (request == null || effectivePrompt == null || effectivePrompt.isBlank()) {
            return Optional.empty();
        }
        List<AgenticAuthoringCandidate> usableCandidates =
                candidateOptions == null ? List.of() : candidateOptions;
        try {
            PromptInput promptInput = promptInput(
                    request,
                    effectivePrompt,
                    currentPageSummary,
                    target,
                    usableCandidates,
                    componentCapabilities,
                    tenantId,
                    environment);
            JsonNode result = providerManagementService.generateJson(
                    promptInput.prompt(),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(3600)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            return toResolution(result);
        } catch (RuntimeException ex) {
            return Optional.of(failedResolution(ex));
        }
    }

    private AgenticAuthoringLlmIntentResolution failedResolution(RuntimeException ex) {
        return new AgenticAuthoringLlmIntentResolution(
                false,
                "unknown",
                "unknown",
                "unknown",
                null,
                null,
                "provider_error",
                "Tive um problema para concluir essa leitura agora. Posso continuar com o recurso mais provavel, mas antes de criar algo preciso confirmar se ele representa o dominio certo.",
                List.of(),
                List.of("Qual recurso de negocio deve orientar esta decisao?"),
                List.of("llm-intent-resolution-failed", "llm-provider-error"),
                null,
                null);
    }

    JsonNode diagnosticSnapshot(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        PromptInput promptInput = promptInput(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                null,
                null);
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-llm-diagnostics.v1");
        diagnostics.put("promptTemplateId", SYSTEM_PROMPT_TEMPLATE_ID);
        diagnostics.set("contextBundle", promptInput.contextBundle());
        diagnostics.put("prompt", promptInput.prompt());
        return diagnostics;
    }

    private PromptInput promptInput(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String environment) {
        String governedDomainContext = governedDomainContext(request, effectivePrompt, tenantId, environment);
        ObjectNode contextBundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                governedDomainContext);
        return new PromptInput(
                contextBundle,
                SYSTEM_PROMPT_TEMPLATE.formatted(contextBundle.toPrettyString()));
    }

    private String governedDomainContext(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            String tenantId,
            String environment) {
        if (domainCatalogPromptContextService == null || request == null || request.contextHints() == null) {
            return "";
        }
        try {
            return StringUtils.hasText(effectivePrompt)
                    ? domainCatalogPromptContextService.buildPromptContext(
                            effectivePrompt,
                            request.contextHints(),
                            tenantId,
                            environment)
                    : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private Optional<AgenticAuthoringLlmIntentResolution> toResolution(JsonNode result) {
        if (result == null || !result.isObject()) {
            return Optional.empty();
        }
        boolean resolved = result.path("resolved").asBoolean(false);
        List<AgenticAuthoringQuickReply> quickReplies = quickReplies(result.path("quickReplies"));
        List<String> clarificationQuestions = strings(result.path("clarificationQuestions"));
        List<String> warnings = strings(result.path("warnings"));
        String operationKind = text(result, "operationKind");
        String artifactKind = text(result, "artifactKind");
        String changeKind = text(result, "changeKind");
        String selectedResourcePath = nullableText(result, "selectedResourcePath");
        String resourceSearchQuery = nullableText(result, "resourceSearchQuery");
        String followUpKind = text(result, "followUpKind");
        String assistantMessage = conciseAssistantMessage(nullableText(result, "assistantMessage"));
        AgenticAuthoringVisualizationDecision visualizationDecision =
                visualizationDecision(result.path("visualizationDecision"));
        AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan =
                consultativeRetrievalPlan(result.path("consultativeRetrievalPlan"));
        if (!resolved
                && operationKind.isBlank()
                && artifactKind.isBlank()
                && changeKind.isBlank()
                && (assistantMessage == null || assistantMessage.isBlank())
                && quickReplies.isEmpty()
                && clarificationQuestions.isEmpty()
                && warnings.isEmpty()
                && consultativeRetrievalPlan == null
                && visualizationDecision == null) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringLlmIntentResolution(
                resolved,
                operationKind,
                artifactKind,
                changeKind,
                selectedResourcePath,
                resourceSearchQuery,
                followUpKind,
                assistantMessage,
                quickReplies,
                clarificationQuestions,
                warnings,
                consultativeRetrievalPlan,
                visualizationDecision));
    }

    private AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> requiredContext = strings(node.path("requiredContext"));
        List<String> semanticQueries = strings(node.path("semanticQueries"));
        List<String> expectedEvidence = strings(node.path("expectedEvidence"));
        String answerStrategy = text(node, "answerStrategy");
        if (requiredContext.isEmpty() && semanticQueries.isEmpty() && expectedEvidence.isEmpty() && answerStrategy.isBlank()) {
            return null;
        }
        return new AgenticAuthoringConsultativeRetrievalPlan(
                valueOrDefault(nullableText(node, "schemaVersion"), "praxis-agentic-authoring-consultative-retrieval-plan.v1"),
                requiredContext,
                semanticQueries,
                answerStrategy,
                expectedEvidence);
    }

    private AgenticAuthoringVisualizationDecision visualizationDecision(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<AgenticAuthoringVisualizationAxisDecision> axes = visualizationAxes(node.path("axes"));
        String intent = text(node, "intent");
        String layoutKind = text(node, "layoutKind");
        String primaryComponent = valueOrDefault(nullableText(node, "primaryComponentId"), text(node, "primaryComponent"));
        if (intent.isBlank() && layoutKind.isBlank() && primaryComponent.isBlank() && axes.isEmpty()) {
            return null;
        }
        return new AgenticAuthoringVisualizationDecision(
                valueOrDefault(nullableText(node, "schemaVersion"), "praxis-agentic-authoring-visualization-decision.v1"),
                intent,
                layoutKind,
                primaryComponent,
                axes,
                node.path("includeSummary").asBoolean(true),
                node.path("includeDetailTable").asBoolean(true),
                valueOrDefault(nullableText(node, "provenance"), "llm-authored-semantic-decision"));
    }

    private List<AgenticAuthoringVisualizationAxisDecision> visualizationAxes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgenticAuthoringVisualizationAxisDecision> axes = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String field = text(item, "field");
            String concept = text(item, "concept");
            if (field.isBlank() || concept.isBlank()) {
                continue;
            }
            axes.add(new AgenticAuthoringVisualizationAxisDecision(
                    concept,
                    field,
                    valueOrDefault(nullableText(item, "label"), field),
                    valueOrDefault(nullableText(item, "chartType"), "bar"),
                    valueOrDefault(nullableText(item, "orientation"), "vertical"),
                    valueOrDefault(nullableText(item, "metricAggregation"), "count"),
                    nullableText(item, "metricField"),
                    valueOrDefault(nullableText(item, "metricLabel"), "Total"),
                    valueOrDefault(nullableText(item, "provenance"), "llm-authored-semantic-axis")));
        }
        return List.copyOf(axes);
    }

    private List<AgenticAuthoringQuickReply> quickReplies(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (JsonNode item : node) {
            String id = text(item, "id");
            String kind = text(item, "kind");
            String label = text(item, "label");
            String prompt = text(item, "prompt");
            if (id.isBlank() || kind.isBlank() || label.isBlank()) {
                continue;
            }
            if (isRedactedPrompt(prompt)) {
                prompt = label;
            }
            replies.add(new AgenticAuthoringQuickReply(
                    id,
                    kind,
                    label,
                    prompt,
                    nullableText(item, "description"),
                    nullableText(item, "icon"),
                    nullableText(item, "tone"),
                    item.path("contextHints").isObject() ? item.path("contextHints") : null));
        }
        return List.copyOf(replies);
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

    private boolean isRedactedPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.isBlank() || "[REDACTED]".equalsIgnoreCase(value);
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private String schema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.putObject("resolved").put("type", "boolean");
        stringEnum(properties, "operationKind", List.of("create", "modify", "remove", "compose", "connect", "explore", "explain", "unknown"));
        stringEnum(properties, "artifactKind", List.of("dashboard", "table", "form", "page", "api_catalog", "component", "unknown"));
        properties.putObject("changeKind").put("type", "string");
        nullableString(properties, "selectedResourcePath");
        nullableString(properties, "resourceSearchQuery");
        stringEnum(properties, "followUpKind", List.of(
                "clarification_answer",
                "new_instruction",
                "refinement",
                "api_catalog_followup",
                "none",
                "unknown"));
        nullableString(properties, "assistantMessage");
        arrayOfStrings(properties, "clarificationQuestions");
        arrayOfStrings(properties, "warnings");
        properties.set("visualizationDecision", visualizationDecisionSchema());
        properties.set("consultativeRetrievalPlan", consultativeRetrievalPlanSchema());

        ObjectNode reply = objectMapper.createObjectNode();
        reply.put("type", "object");
        ObjectNode replyProps = reply.putObject("properties");
        replyProps.putObject("id").put("type", "string");
        replyProps.putObject("kind").put("type", "string");
        replyProps.putObject("label").put("type", "string");
        replyProps.putObject("prompt").put("type", "string");
        nullableString(replyProps, "description");
        nullableString(replyProps, "icon");
        nullableString(replyProps, "tone");
        replyProps.putObject("contextHints").put("type", "object").put("additionalProperties", true);
        ArrayNode replyRequired = reply.putArray("required");
        replyRequired.add("id").add("kind").add("label").add("prompt");
        reply.put("additionalProperties", true);
        properties.putObject("quickReplies")
                .put("type", "array")
                .set("items", reply);

        ArrayNode required = root.putArray("required");
        required.add("resolved")
                .add("operationKind")
                .add("artifactKind")
                .add("changeKind")
                .add("followUpKind")
                .add("quickReplies")
                .add("clarificationQuestions")
                .add("warnings");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private ObjectNode consultativeRetrievalPlanSchema() {
        ObjectNode plan = objectMapper.createObjectNode();
        ArrayNode types = plan.putArray("type");
        types.add("object").add("null");
        ObjectNode properties = plan.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string");
        ObjectNode requiredContext = properties.putObject("requiredContext");
        requiredContext.put("type", "array");
        ObjectNode requiredContextItems = requiredContext.putObject("items");
        requiredContextItems.put("type", "string");
        requiredContextItems.putArray("enum")
                .add("platform_capabilities")
                .add("component_registry")
                .add("domain_catalog")
                .add("api_resources")
                .add("runtime_context")
                .add("conversation_context");
        arrayOfStrings(properties, "semanticQueries");
        properties.putObject("answerStrategy").put("type", "string");
        arrayOfStrings(properties, "expectedEvidence");
        plan.putArray("required")
                .add("schemaVersion")
                .add("requiredContext")
                .add("semanticQueries")
                .add("answerStrategy")
                .add("expectedEvidence");
        plan.put("additionalProperties", false);
        return plan;
    }

    private ObjectNode visualizationDecisionSchema() {
        ObjectNode decision = objectMapper.createObjectNode();
        ArrayNode decisionTypes = decision.putArray("type");
        decisionTypes.add("object").add("null");
        ObjectNode properties = decision.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string");
        properties.putObject("intent").put("type", "string");
        properties.putObject("layoutKind").put("type", "string");
        properties.putObject("primaryComponent").put("type", "string");
        nullableString(properties, "primaryComponentId");
        properties.putObject("includeSummary").put("type", "boolean");
        properties.putObject("includeDetailTable").put("type", "boolean");
        properties.putObject("provenance").put("type", "string");

        ObjectNode axis = objectMapper.createObjectNode();
        axis.put("type", "object");
        ObjectNode axisProperties = axis.putObject("properties");
        axisProperties.putObject("concept").put("type", "string");
        axisProperties.putObject("field").put("type", "string");
        axisProperties.putObject("label").put("type", "string");
        stringEnum(axisProperties, "chartType", List.of("bar", "horizontal-bar", "line", "area", "pie", "donut"));
        stringEnum(axisProperties, "orientation", List.of("vertical", "horizontal", "temporal"));
        stringEnum(axisProperties, "metricAggregation", List.of("count", "sum", "avg", "min", "max"));
        nullableString(axisProperties, "metricField");
        axisProperties.putObject("metricLabel").put("type", "string");
        axisProperties.putObject("provenance").put("type", "string");
        axis.putArray("required")
                .add("concept")
                .add("field")
                .add("label")
                .add("chartType")
                .add("orientation")
                .add("metricAggregation")
                .add("metricLabel")
                .add("provenance");
        axis.put("additionalProperties", false);

        properties.putObject("axes")
                .put("type", "array")
                .set("items", axis);
        decision.putArray("required")
                .add("schemaVersion")
                .add("intent")
                .add("layoutKind")
                .add("primaryComponent")
                .add("axes")
                .add("includeSummary")
                .add("includeDetailTable")
                .add("provenance");
        decision.put("additionalProperties", false);
        return decision;
    }

    private void nullableString(ObjectNode properties, String name) {
        ArrayNode types = properties.putObject(name).putArray("type");
        types.add("string").add("null");
    }

    private void arrayOfStrings(ObjectNode properties, String name) {
        properties.putObject(name)
                .put("type", "array")
                .putObject("items")
                .put("type", "string");
    }

    private void stringEnum(ObjectNode properties, String name, List<String> values) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "string");
        ArrayNode allowed = field.putArray("enum");
        values.forEach(allowed::add);
    }

    private String text(JsonNode node, String field) {
        return nullableText(node, field) == null ? "" : nullableText(node, field);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : null;
    }

    private static String loadSystemPromptTemplate() {
        try (InputStream inputStream = AgenticAuthoringLlmIntentResolverService.class
                .getClassLoader()
                .getResourceAsStream(SYSTEM_PROMPT_TEMPLATE_ID)) {
            if (inputStream == null) {
                throw new IllegalStateException("Agentic authoring system prompt not found: " + SYSTEM_PROMPT_TEMPLATE_ID);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read agentic authoring system prompt: " + SYSTEM_PROMPT_TEMPLATE_ID, exception);
        }
    }

    private record PromptInput(
            ObjectNode contextBundle,
            String prompt) {
    }
}
