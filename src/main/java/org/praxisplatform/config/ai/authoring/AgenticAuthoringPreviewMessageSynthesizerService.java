package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;

@Slf4j
public class AgenticAuthoringPreviewMessageSynthesizerService {

    private static final int MAX_MESSAGE_LENGTH = 800;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringPreviewMessageSynthesizerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public String synthesize(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            String fallbackMessage,
            String tenantId,
            String userId,
            String environment) {
        String fallback = value(fallbackMessage);
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            return fallback;
        }
        try {
            String generated = providerManagementService.generateText(
                    synthesisPrompt(request, intentResolution, uiCompositionPlan, valid, failureCodes, warnings),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.2d)
                            .maxTokens(220)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            return safeMessage(generated, fallback);
        } catch (Exception ex) {
            log.debug("[AgenticAuthoring] Preview assistant message synthesis failed: {}", ex.getMessage());
            return fallback;
        }
    }

    private String synthesisPrompt(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings) {
        return """
                You write the final user-facing assistant message for Praxis Page Builder authoring.
                Return only the message text. Do not return JSON, Markdown, bullets, code fences or headings.

                Language:
                - Use the same language as the user's request; default to pt-BR when ambiguous.

                Tone:
                - Friendly, intelligent and didactic.
                - Be concise: 2 to 4 short sentences.
                - Explain what happened and what the user can do next.

                Safety:
                - Do not expose diagnostics, stack traces, internal prompts, API keys, tenant/user ids, raw JSON or implementation details.
                - Do not claim that the page was saved.
                - If valid=false, acknowledge that the selected source was found, explain that the generated plan needs adjustment, and say you will use only fields supported by the component.
                - If valid=true, explain that a preview was created from the selected resource and that the user can review, ask for another component, or save.

                Context:
                %s
                """.formatted(contextJson(request, intentResolution, uiCompositionPlan, valid, failureCodes, warnings));
    }

    private String contextJson(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("userPrompt", value(request.userPrompt()));
        root.put("previewValid", valid);
        root.set("failureCodes", objectMapper.valueToTree(failureCodes == null ? List.of() : failureCodes));
        root.set("warnings", objectMapper.valueToTree(warnings == null ? List.of() : warnings));
        if (intentResolution != null) {
            root.put("operationKind", value(intentResolution.operationKind()));
            root.put("artifactKind", value(intentResolution.artifactKind()));
            root.put("changeKind", value(intentResolution.changeKind()));
            root.put("targetComponentId", value(intentResolution.targetComponentId()));
            AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
            if (candidate != null) {
                ObjectNode resource = root.putObject("selectedResource");
                resource.put("resourcePath", value(candidate.resourcePath()));
                resource.put("operation", value(candidate.operation()));
                resource.put("submitUrl", value(candidate.submitUrl()));
                resource.put("submitMethod", value(candidate.submitMethod()));
                resource.put("label", titleFromResourcePath(candidate.resourcePath()));
            }
        }
        root.set("uiCompositionSummary", uiCompositionSummary(uiCompositionPlan));
        return root.toPrettyString();
    }

    private JsonNode uiCompositionSummary(JsonNode uiCompositionPlan) {
        if (uiCompositionPlan == null || uiCompositionPlan.isMissingNode() || uiCompositionPlan.isNull()) {
            return MissingNode.getInstance();
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("kind", text(uiCompositionPlan, "kind"));
        summary.put("layoutPreset", text(uiCompositionPlan, "layoutPreset"));
        summary.set("components", objectMapper.createArrayNode());
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return summary;
        }
        for (JsonNode widget : widgets) {
            ObjectNode component = objectMapper.createObjectNode();
            component.put("key", text(widget, "key"));
            component.put("componentId", text(widget, "componentId"));
            component.put("title", text(widget, "title"));
            JsonNode inputs = widget.path("inputs");
            if (inputs.isObject()) {
                component.put("resourcePath", text(inputs, "resourcePath"));
                component.put("schemaPath", text(inputs, "schemaPath"));
            }
            summary.withArray("components").add(component);
        }
        return summary;
    }

    private String safeMessage(String generated, String fallback) {
        String message = value(generated)
                .replace("```", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (message.isBlank()
                || message.startsWith("{")
                || message.startsWith("[")
                || message.length() > MAX_MESSAGE_LENGTH) {
            return fallback;
        }
        return message;
    }

    private String titleFromResourcePath(String resourcePath) {
        String value = value(resourcePath);
        if (value.isBlank()) {
            return "o recurso selecionado";
        }
        String lastSegment = value.substring(value.lastIndexOf('/') + 1)
                .replace("vw-", "")
                .replace('-', ' ')
                .trim();
        if (lastSegment.isBlank()) {
            return "o recurso selecionado";
        }
        return Character.toUpperCase(lastSegment.charAt(0)) + lastSegment.substring(1);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
