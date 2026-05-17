package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;

@Slf4j
public class AgenticAuthoringPreviewMessageSynthesizerService {

    private static final int MAX_MESSAGE_LENGTH = 800;
    private static final Pattern API_PATH_PATTERN = Pattern.compile(
            "(?i)(?:\"?)((?:/api|/schemas)/[\\p{Alnum}._~:/?#\\[\\]@!$&'()*+,;=%{}-]+)(?:\"?)");
    private static final Pattern HTTP_OPERATION_PARENTHESIS_PATTERN = Pattern.compile(
            "(?i)\\s*\\(\\s*opera[cç][aã]o\\s+(?:GET|POST|PUT|PATCH|DELETE)\\s*\\)");
    private static final Pattern HTTP_OPERATION_PATTERN = Pattern.compile(
            "(?i)\\bopera[cç][aã]o\\s+(?:GET|POST|PUT|PATCH|DELETE)\\b");

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
        String deterministicModificationMessage = deterministicChartModificationMessage(
                intentResolution,
                uiCompositionPlan,
                valid,
                warnings,
                fallback);
        if (!deterministicModificationMessage.isBlank()) {
            return deterministicModificationMessage;
        }
        String deterministicMessage = deterministicSingleChartMessage(
                intentResolution,
                uiCompositionPlan,
                valid,
                fallback);
        if (!deterministicMessage.isBlank()) {
            return deterministicMessage;
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
            return sanitizeTechnicalLanguage(message, intentResolution);
        } catch (Exception ex) {
            log.debug("[AgenticAuthoring] Preview assistant message synthesis failed: {}", ex.getMessage());
            return sanitizeTechnicalLanguage(fallback, intentResolution);
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
                Return only the message text. Do not return JSON, HTML, code fences or implementation diagnostics.

                Language:
                - Use the same language as the user's request; default to pt-BR when ambiguous.

                Tone:
                - Friendly, intelligent and didactic.
                - Format it like a polished chat assistant response: one short summary line, a blank line, then 2 to 4 concise "- " bullet lines.
                - Use bullets to explain the governed source, visualization/component, validation status and next step.
                - Say "campos confirmados" or "dados confirmados" instead of "schema".
                - Keep it under 90 words.

                Safety:
                - Do not expose diagnostics, stack traces, internal prompts, API keys, tenant/user ids, raw JSON or implementation details.
                - Do not mention endpoint paths such as /api/..., /schemas/..., submitUrl, submitMethod, HTTP methods, operation POST/GET or schema URLs.
                - Do not mention warnings, warning codes, failure codes, diagnostics, technical observations, implementation notes, or "observacao tecnica".
                - Use selectedResource.label and component titles as user-facing names. Technical addresses are diagnostics only and must never be quoted.
                - Do not claim that the page was saved.
                - If valid=false, acknowledge that the selected source was found, explain that the generated plan needs adjustment, and say you will use only fields supported by the component.
                - If valid=true, explain that a preview was created from the selected resource and that the user can review, ask for another component, or save.
                - If uiCompositionSummary.semanticAxes has unsupported axes, explicitly say those requested axes were not materialized because they were not present in the confirmed fields. Do not imply that every requested chart was created.
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
        root.put("hasValidationDetails", (failureCodes != null && !failureCodes.isEmpty())
                || (warnings != null && !warnings.isEmpty()));
        root.put("localEditorialComposition", isLocalEditorialComposition(request, intentResolution, warnings));
        if (intentResolution != null) {
            root.put("operationKind", value(intentResolution.operationKind()));
            root.put("artifactKind", value(intentResolution.artifactKind()));
            root.put("changeKind", value(intentResolution.changeKind()));
            root.put("targetComponentId", value(intentResolution.targetComponentId()));
            AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
            if (candidate != null) {
                ObjectNode resource = root.putObject("selectedResource");
                resource.put("label", titleFromResourcePath(candidate.resourcePath()));
            }
        }
        root.set("uiCompositionSummary", uiCompositionSummary(uiCompositionPlan));
        return root.toPrettyString();
    }

    private String deterministicChartModificationMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> warnings,
            String fallback) {
        boolean chartModificationPlan = containsWarning(warnings, "ui-composition-plan-provider:generic-chart-modification");
        boolean chartModificationIntent = intentResolution != null
                && "modify".equals(value(intentResolution.operationKind()))
                && ("chart".equals(value(intentResolution.artifactKind()))
                || "set_chart_type".equals(value(intentResolution.changeKind())));
        if ((!chartModificationPlan && !chartModificationIntent)
                || !containsComponent(uiCompositionPlan, "praxis-chart")) {
            return "";
        }
        if (!valid) {
            return sanitizeTechnicalLanguage(value(fallback), intentResolution);
        }
        String sourceLabel = selectedResourceLabel(intentResolution);
        if (isChartSurfaceOpenModification(intentResolution, warnings)) {
            StringBuilder message = new StringBuilder(
                    "Configurei o grafico para abrir os detalhes da categoria selecionada em um modal.");
            if (!sourceLabel.isBlank()) {
                message.append("\n\n- Mantive a fonte governada: ").append(sourceLabel).append(".");
            }
            message.append("\n- O modal usa uma tabela de detalhes conectada a selecao do grafico.");
            message.append("\n- Proximo passo: revise o comportamento e salve quando estiver ok.");
            return sanitizeTechnicalLanguage(message.toString(), intentResolution);
        }
        String chartType = chartTypeLabel(chartType(uiCompositionPlan));
        StringBuilder message = new StringBuilder("Atualizei o grafico selecionado");
        if (!chartType.isBlank()) {
            message.append(" para ").append(chartType);
        }
        message.append(".");
        if (!sourceLabel.isBlank()) {
            message.append("\n\n- Mantive a fonte governada: ").append(sourceLabel).append(".");
        }
        message.append("\n- Preservei a dimensao, a metrica e o recorte atual do grafico.");
        message.append("\n- Proximo passo: revise a visualizacao ou escolha outra acao sugerida.");
        return sanitizeTechnicalLanguage(message.toString(), intentResolution);
    }

    private boolean isChartSurfaceOpenModification(
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> warnings) {
        if (containsWarning(warnings, "ui-composition-plan-provider:generic-chart-surface-open-modification")) {
            return true;
        }
        return intentResolution != null
                && "modify".equals(value(intentResolution.operationKind()))
                && "chart".equals(value(intentResolution.artifactKind()))
                && "enable_chart_drilldown".equals(value(intentResolution.changeKind()));
    }

    private JsonNode uiCompositionSummary(JsonNode uiCompositionPlan) {
        if (uiCompositionPlan == null || uiCompositionPlan.isMissingNode() || uiCompositionPlan.isNull()) {
            return MissingNode.getInstance();
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("kind", text(uiCompositionPlan, "kind"));
        summary.put("layoutPreset", text(uiCompositionPlan, "layoutPreset"));
        summary.set("components", objectMapper.createArrayNode());
        summary.set("semanticAxes", semanticAxesSummary(uiCompositionPlan));
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return summary;
        }
        for (JsonNode widget : widgets) {
            ObjectNode component = objectMapper.createObjectNode();
            component.put("componentKind", userFacingComponentKind(text(widget, "componentId")));
            component.put("title", text(widget, "title"));
            JsonNode inputs = widget.path("inputs");
            if (inputs.isObject()) {
                String sourceLabel = titleFromResourcePath(text(inputs, "resourcePath"));
                if (!sourceLabel.isBlank() && !"o recurso selecionado".equals(sourceLabel)) {
                    component.put("sourceLabel", sourceLabel);
                }
            }
            summary.withArray("components").add(component);
        }
        return summary;
    }

    private ArrayNode semanticAxesSummary(JsonNode uiCompositionPlan) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return result;
        }
        for (JsonNode axis : axes) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("concept", text(axis, "concept"));
            item.put("requestedField", text(axis, "requestedField"));
            item.put("field", text(axis, "field"));
            item.put("label", text(axis, "label"));
            item.put("schemaLabel", text(axis, "schemaLabel"));
            item.put("schemaVerified", axis.path("schemaVerified").asBoolean(false));
            item.put("schemaProbeStatus", text(axis, "schemaProbeStatus"));
            result.add(item);
        }
        return result;
    }

    private boolean containsComponent(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || componentId == null || componentId.isBlank()) {
            return false;
        }
        if (node.isObject()) {
            if (componentId.equals(text(node, "componentId"))
                    || componentId.equals(text(node.path("definition"), "id"))
                    || componentId.equals(text(node, "id"))) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsComponent(child, componentId)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsComponent(child, componentId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String chartType(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            if ("praxis-chart".equals(text(node, "componentId"))
                    || "praxis-chart".equals(text(node.path("definition"), "id"))
                    || "praxis-chart".equals(text(node, "id"))) {
                String type = chartWidgetType(node);
                if (!type.isBlank()) {
                    return type;
                }
            }
            for (JsonNode child : node) {
                String nested = chartType(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = chartType(child);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private String chartWidgetType(JsonNode widget) {
        String type = text(widget.path("inputs").path("config"), "type");
        if (!type.isBlank()) {
            return type;
        }
        type = text(widget.path("definition").path("inputs").path("config"), "type");
        if (!type.isBlank()) {
            return type;
        }
        return text(widget.path("config"), "type");
    }

    private String chartTypeLabel(String type) {
        return switch (value(type)) {
            case "line" -> "linhas";
            case "bar" -> "barras";
            case "vertical-bar" -> "barras";
            case "horizontal-bar" -> "barras horizontais";
            case "area" -> "área";
            case "pie" -> "pizza";
            case "donut" -> "donut";
            default -> "";
        };
    }

    private String deterministicSingleChartMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            String fallback) {
        if (!isSingleChartPlan(uiCompositionPlan)) {
            return "";
        }
        if (!valid) {
            return sanitizeTechnicalLanguage(value(fallback), intentResolution);
        }
        String sourceLabel = selectedResourceLabel(intentResolution);
        String axisLabel = singleChartAxisLabel(uiCompositionPlan);
        StringBuilder message = new StringBuilder("Criei uma pre-visualizacao com um grafico simples.");
        if (!sourceLabel.isBlank()) {
            message.append("\n\n- Fonte governada: ").append(sourceLabel).append(".");
        }
        message.append("\n- Visualizacao: grafico");
        if (!axisLabel.isBlank()) {
            message.append(" por ").append(axisLabel);
        }
        message.append(", sem tabela, filtros ou KPIs.");
        message.append("\n- Validacao: usei apenas campos confirmados para a pre-visualizacao.");
        message.append("\n- Proximo passo: revise o grafico ou peça outro ajuste.");
        return sanitizeTechnicalLanguage(message.toString(), intentResolution);
    }

    private boolean isSingleChartPlan(JsonNode uiCompositionPlan) {
        if (uiCompositionPlan == null || !uiCompositionPlan.isObject()) {
            return false;
        }
        if (!"single-chart-page".equals(text(uiCompositionPlan, "layoutPreset"))) {
            return false;
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        return widgets.isArray()
                && widgets.size() == 1
                && "praxis-chart".equals(text(widgets.path(0), "componentId"));
    }

    private String singleChartAxisLabel(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (axes.isArray() && axes.size() > 0) {
            String schemaLabel = text(axes.path(0), "schemaLabel");
            if (!schemaLabel.isBlank()) {
                return schemaLabel;
            }
            String label = text(axes.path(0), "label");
            if (!label.isBlank()) {
                return label;
            }
            String field = text(axes.path(0), "field");
            if (!field.isBlank()) {
                return titleFromResourcePath("/" + field);
            }
        }
        JsonNode widget = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("widgets").path(0);
        JsonNode config = widget.path("inputs").path("config");
        String field = text(config, "field");
        if (field.isBlank()) {
            field = text(config.path("query"), "field");
        }
        return field.isBlank() ? "" : titleFromResourcePath("/" + field);
    }

    private String safeMessage(String generated, String fallback) {
        String message = value(generated)
                .replace("```", "")
                .replaceAll("\\r\\n?", "\n")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (message.isBlank()
                || message.startsWith("{")
                || message.startsWith("[")
                || message.length() > MAX_MESSAGE_LENGTH) {
            return fallback;
        }
        return message;
    }

    private String sanitizeTechnicalLanguage(
            String message,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String value = value(message);
        if (value.isBlank()) {
            return value;
        }
        String sourceLabel = selectedResourceLabel(intentResolution);
        String replacement = sourceLabel.isBlank() ? "a fonte selecionada" : sourceLabel;
        String sanitized = API_PATH_PATTERN.matcher(value).replaceAll(Matcher.quoteReplacement(replacement));
        sanitized = HTTP_OPERATION_PARENTHESIS_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = HTTP_OPERATION_PATTERN.matcher(sanitized).replaceAll("fonte confirmada");
        sanitized = removeTechnicalObservationSentences(sanitized);
        sanitized = sanitized
                .replaceAll("(?i)\\bsubmitUrl\\b", "envio")
                .replaceAll("(?i)\\bsubmitMethod\\b", "metodo de envio")
                .replaceAll("(?i)\\bpraxis-chart\\b", "gráfico")
                .replaceAll("(?i)\\bpraxis-table\\b", "tabela")
                .replaceAll("(?i)\\bpraxis-filter\\b", "filtro")
                .replaceAll("(?i)\\bpraxis-dynamic-form\\b", "formulário")
                .replaceAll("(?i)\\s*\\([a-z0-9]+(?:-[a-z0-9]+){2,}\\)", "")
                .replaceAll("(?i)\\bvw-[a-z0-9-]+\\b", "fonte selecionada")
                .replaceAll("(?i)salve o dashboard", "salve a pré-visualização")
                .replaceAll("(?i)salvar o dashboard", "salvar a pré-visualização")
                .replaceAll("(?i)\\bschema\\b", "campos confirmados")
                .replaceAll("(?i)\\bwarnings?\\b", "pontos de revisao")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([,.;:])", "$1")
                .trim();
        return sanitized.length() > MAX_MESSAGE_LENGTH ? value.substring(0, MAX_MESSAGE_LENGTH).trim() : sanitized;
    }

    private String removeTechnicalObservationSentences(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return Pattern.compile(
                        "(?is)(?:^|\\n|(?<=\\.))\\s*-?\\s*(?:observa[cç][aã]o t[eé]cnica|technical note|implementation note)\\s*:[^\\n.]*(?:\\.|\\n|$)")
                .matcher(message)
                .replaceAll(" ")
                .trim();
    }

    private String selectedResourceLabel(AgenticAuthoringIntentResolutionResult intentResolution) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null) {
            return "";
        }
        return titleFromResourcePath(candidate.resourcePath());
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
        String prompt = value(request == null ? null : request.userPrompt()).toLowerCase(Locale.ROOT);
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
        String normalized = value(message).toLowerCase(Locale.ROOT);
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

    private String userFacingComponentKind(String componentId) {
        String normalized = value(componentId).toLowerCase(Locale.ROOT);
        if (normalized.contains("chart")) {
            return "gráfico";
        }
        if (normalized.contains("table")) {
            return "tabela";
        }
        if (normalized.contains("filter")) {
            return "filtro";
        }
        if (normalized.contains("form")) {
            return "formulário";
        }
        if (normalized.contains("tab")) {
            return "abas";
        }
        return "componente";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
