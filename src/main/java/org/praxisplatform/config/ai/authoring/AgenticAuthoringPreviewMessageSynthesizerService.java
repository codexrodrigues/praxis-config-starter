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
        boolean localEditorialComposition = isLocalEditorialComposition(request, intentResolution, warnings);
        String fallback = value(fallbackMessage);
        if (localEditorialComposition && fallback.isBlank()) {
            fallback = localEditorialPreviewMessage(valid);
        }
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
            String message = safeMessage(generated, fallback);
            if (localEditorialComposition && hasResourceSourceClaim(message)) {
                return localEditorialPreviewMessage(valid);
            }
            return message;
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
                - If localEditorialComposition=true or selectedResource is absent, say the preview was created from local/editorial demo content. Do not mention selected resource, source of data, API, schema or grounding.

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
        root.put("localEditorialComposition", isLocalEditorialComposition(request, intentResolution, warnings));
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

    private boolean isLocalEditorialComposition(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> warnings) {
        if (containsWarning(warnings, "explicit-local-ui-composition-resource-selection-bypassed")
                || containsWarning(intentResolution == null ? null : intentResolution.warnings(),
                "explicit-local-ui-composition-resource-selection-bypassed")) {
            return true;
        }
        if (intentResolution != null && intentResolution.selectedCandidate() != null) {
            return false;
        }
        String prompt = value(request == null ? null : request.userPrompt()).toLowerCase(java.util.Locale.ROOT);
        return (prompt.contains("conteudo local")
                || prompt.contains("conteúdo local")
                || prompt.contains("conteudo editorial")
                || prompt.contains("conteúdo editorial")
                || prompt.contains("local/editorial")
                || prompt.contains("demonstracao")
                || prompt.contains("demonstração"))
                && (prompt.contains("pagina")
                || prompt.contains("página")
                || prompt.contains("tabs")
                || prompt.contains("formulario")
                || prompt.contains("formulário")
                || prompt.contains("lista")
                || prompt.contains("cards"));
    }

    private boolean containsWarning(List<String> warnings, String value) {
        return warnings != null && warnings.contains(value);
    }

    private boolean hasResourceSourceClaim(String message) {
        String normalized = value(message).toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("recurso selecionado")
                || normalized.contains("fonte de dados")
                || normalized.contains("fonte governada")
                || normalized.contains("selected resource")
                || normalized.contains("data source");
    }

    private String localEditorialPreviewMessage(boolean valid) {
        if (!valid) {
            return "Montei uma pre-visualizacao com conteudo local/editorial de demonstracao, mas o plano ainda precisa de ajuste antes de revisar. Vou manter o exemplo desacoplado de API, schema e regra de negocio definitiva.";
        }
        return "Criei uma pre-visualizacao com conteudo local/editorial de demonstracao, sem vincular API, schema ou regra de negocio definitiva. Voce pode revisar o resultado, pedir ajustes na composicao ou salvar quando estiver de acordo.";
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
