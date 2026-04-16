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

public class AgenticAuthoringLlmIntentResolverService {

    private static final String SYSTEM_PROMPT_TEMPLATE_ID = "ai-authoring/page-builder-system-prompt.v1.md";
    private static final String SYSTEM_PROMPT_TEMPLATE = loadSystemPromptTemplate();

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
            PromptInput promptInput = promptInput(request, effectivePrompt, currentPageSummary, target, usableCandidates, componentCapabilities);
            JsonNode result = providerManagementService.generateJson(
                    promptInput.prompt(),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(1800)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            return toResolution(result);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    JsonNode diagnosticSnapshot(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        PromptInput promptInput = promptInput(request, effectivePrompt, currentPageSummary, target, candidateOptions, componentCapabilities);
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
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode contextBundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities);
        return new PromptInput(
                contextBundle,
                SYSTEM_PROMPT_TEMPLATE.formatted(contextBundle.toPrettyString()));
    }

    private Optional<AgenticAuthoringLlmIntentResolution> toResolution(JsonNode result) {
        if (result == null || !result.isObject() || !result.path("resolved").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringLlmIntentResolution(
                true,
                text(result, "operationKind"),
                text(result, "artifactKind"),
                text(result, "changeKind"),
                nullableText(result, "selectedResourcePath"),
                text(result, "followUpKind"),
                nullableText(result, "assistantMessage"),
                quickReplies(result.path("quickReplies")),
                strings(result.path("clarificationQuestions")),
                strings(result.path("warnings"))));
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
