package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic fallback assistant message synthesis and semantic-axis/detail summary formatting
 * for {@link AgenticAuthoringPreviewService}.
 */
final class AgenticAuthoringPreviewAssistantMessageSupport {

    String deterministicPreviewAssistantMessage(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes) {
        if (!valid) {
            if (failureCodes != null && failureCodes.contains("semantic-preview-chart-required")) {
                return "Encontrei a fonte certa, mas ainda nao consegui montar o grafico pedido com seguranca. Vou deixar a proposta em revisao e ajustar a visualizacao antes de voce salvar.";
            }
            if (failureCodes != null && failureCodes.contains("semantic-preview-operational-dashboard-required")) {
                return "Encontrei a fonte certa, mas a tela ainda nao virou um dashboard operacional completo. Vou manter em revisao ate incluir indicadores, graficos e detalhe conectado de forma coerente.";
            }
            if (failureCodes != null && failureCodes.contains("semantic-preview-axis-schema-verification-required")) {
                return "Encontrei a base de dados, mas ainda nao consegui confirmar campos seguros para alguns graficos. Vou manter a proposta em revisao e sugerir eixos compativeis antes de aplicar.";
            }
            if (failureCodes != null
                    && failureCodes.contains(AgenticAuthoringSemanticMaterializationPolicy.PRIMARY_COMPONENT_REQUIRED_FAILURE)) {
                return "Entendi o componente que voce pediu, mas a pre-visualizacao ainda nao montou esse componente com seguranca. Vou manter em revisao e ajustar a tela antes de aplicar.";
            }
            if (failureCodes != null && failureCodes.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(code -> code.equals("semantic-decision-required")
                            || code.startsWith("semantic-decision-review-required"))) {
                return governedReviewAssistantMessage(intentResolution, uiCompositionPlan);
            }
            return "Encontrei a fonte de dados, mas o plano gerado usou propriedades incompativeis com o componente de tabela. Vou ajustar para usar apenas os campos suportados.";
        }
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null || value(candidate.resourcePath()).isBlank()) {
            return "";
        }
        String resourceLabel = AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
        if ("modify".equals(value(intentResolution.operationKind()))
                && ("chart".equals(value(intentResolution.artifactKind()))
                || "set_chart_type".equals(value(intentResolution.changeKind())))
                && AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            String chartType = chartTypeLabel(chartType(uiCompositionPlan));
            return "Atualizei o grafico selecionado"
                    + (chartType.isBlank() ? "" : " para " + chartType)
                    + " mantendo a fonte \"" + resourceLabel + "\" e o recorte atual.";
        }
        if (AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, "praxis-chart")) {
            SemanticAxisAssistantSummary axisSummary = semanticAxisAssistantSummary(uiCompositionPlan);
            String visualization = axisSummary.verifiedAxes().isEmpty()
                    ? "inclui um grafico conectado ao recorte que voce pediu"
                    : "inclui grafico por " + joinHuman(axisSummary.verifiedAxes());
            String governance = axisSummary.unsupportedAxes().isEmpty()
                    ? "conferi os campos antes de montar a proposta"
                    : "deixei de fora " + joinHuman(axisSummary.unsupportedAxes())
                    + " porque ainda nao encontrei esses campos com seguranca";
            if (isSingleChartPlan(uiCompositionPlan)) {
                return "Montei um grafico usando \"" + resourceLabel + "\". "
                        + sentenceWithPeriod(visualization)
                        + " " + sentenceWithPeriod(governance)
                        + " Nao inclui tabela, filtros nem KPIs.";
            }
            return "Montei uma primeira versao de dashboard usando \"" + resourceLabel + "\". "
                    + sentenceWithPeriod(visualization)
                    + " " + sentenceWithPeriod(governance)
                    + " Tambem mantive " + detailSupportAssistantLine(uiCompositionPlan).toLowerCase(Locale.ROOT)
                    + " para apoiar a revisao.";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            JsonNode actionWorkbenchGrounding = uiCompositionPlan.path("diagnostics").path("actionWorkbenchGrounding");
            if ("verified".equals(value(actionWorkbenchGrounding.path("status").asText("")))) {
                String actionId = value(actionWorkbenchGrounding.path("actionId").asText(""));
                String actionLabel = actionId.isBlank() ? "a acao selecionada" : "\"" + actionId + "\"";
                return "Montei uma bancada de aprovacao usando \"" + resourceLabel + "\". "
                        + "A tabela coordena a selecao do registro com o formulario da acao de workflow " + actionLabel
                        + (containsComponent(uiCompositionPlan, "praxis-filter") ? " e o filtro do workspace. " : ". ")
                        + "Selecione uma linha para executar a acao ou salve quando estiver de acordo.";
            }
            if (containsComponent(uiCompositionPlan, "praxis-related-resource-outlet")) {
                return "Montei uma tela pai-filho usando \"" + resourceLabel + "\" como recurso principal. "
                        + "Ao selecionar um registro na tabela principal, a superficie relacionada exibe os itens vinculados. "
                        + "Revise o resultado e salve quando estiver de acordo.";
            }
            if (containsComponent(uiCompositionPlan, "praxis-dynamic-form")) {
                String richTableNote = containsRichTableRendering(uiCompositionPlan)
                        ? " A tabela ja vem com colunas organizadas, valores formatados e acoes por linha."
                        : "";
                return "Montei um workspace mestre-detalhe usando \"" + resourceLabel + "\". "
                        + "A tabela principal coordena a selecao com o painel de detalhes"
                        + (containsComponent(uiCompositionPlan, "praxis-filter") ? " e o filtro do recurso." : ".")
                        + richTableNote
                        + " Revise o resultado e salve quando estiver de acordo.";
            }
            if (containsRichTableRendering(uiCompositionPlan)) {
                return "Montei uma primeira versao usando \"" + resourceLabel + "\". "
                        + "A tabela ja vem com colunas organizadas, valores formatados e acoes por linha. "
                        + "Revise os detalhes ou me peca uma visualizacao analitica se quiser enxergar tendencias.";
            }
            boolean userRejectedAnalyticSupport = structuredAnalyticSupportRejected(request);
            String closing = userRejectedAnalyticSupport
                    ? "Mantive somente a tabela solicitada."
                    : "Se fizer sentido, posso transformar isso em um dashboard com graficos.";
            return "Montei uma primeira versao usando \"" + resourceLabel + "\" como base. "
                    + "Deixei a tabela conectada para voce revisar as informacoes e ajustar as colunas. "
                    + closing;
        }
        return "Montei uma primeira versao usando \"" + resourceLabel + "\". "
                + "Revise o resultado e salve quando estiver de acordo.";
    }

    String chartType(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            String type = value(node.path("type").asText(""));
            if (!type.isBlank()) {
                return type;
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

    private String chartTypeLabel(String type) {
        return switch (value(type)) {
            case "line" -> "linhas";
            case "bar" -> "barras";
            case "pie" -> "pizza";
            case "donut" -> "donut";
            default -> value(type);
        };
    }

    private String sentenceWithPeriod(String value) {
        String normalized = value(value).trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.endsWith(".") ? normalized : normalized + ".";
    }

    private boolean structuredAnalyticSupportRejected(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringVisualizationDecision decision =
                request == null || request.intentResolution() == null
                        ? null
                        : request.intentResolution().visualizationDecision();
        if (decision == null) {
            return false;
        }
        return !decision.includeSummary()
                || !decision.includeFilters()
                || !decision.includeKpis()
                || excludesComponent(decision, "praxis-chart")
                || excludesComponent(decision, "praxis-tabs");
    }

    private boolean excludesComponent(
            AgenticAuthoringVisualizationDecision decision,
            String componentId) {
        if (decision == null || decision.excludedComponentIds() == null) {
            return false;
        }
        String expected = normalize(componentId);
        return decision.excludedComponentIds().stream()
                .map(this::normalize)
                .anyMatch(expected::equals);
    }

    private String governedReviewAssistantMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        String resourceLabel = candidate == null || value(candidate.resourcePath()).isBlank()
                ? "a fonte encontrada"
                : AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
        if (containsComponent(uiCompositionPlan, "praxis-chart")) {
            if (isSingleChartPlan(uiCompositionPlan)) {
                return "Montei uma primeira pre-visualizacao de grafico para " + resourceLabel + ".\n\n"
                        + "- Fonte usada: \"" + resourceLabel + "\".\n"
                        + "- O que ja foi criado: um grafico conectado ao recorte pedido.\n"
                        + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                        + "- Como prosseguir: confirme a fonte, peca outro recorte ou diga \"salvar este grafico\".";
            }
            return "Montei uma primeira pre-visualizacao de dashboard para " + resourceLabel + ".\n\n"
                    + "- Fonte usada: \"" + resourceLabel + "\".\n"
                    + "- O que ja foi criado: componentes analiticos conectados ao schema da fonte.\n"
                    + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                    + "- Como prosseguir: confirme a fonte, peca outro recorte ou diga \"salvar este dashboard\".";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "Montei uma primeira pre-visualizacao de tabela para " + resourceLabel + ".\n\n"
                    + "- Fonte usada: \"" + resourceLabel + "\".\n"
                    + "- O que ja foi criado: uma tabela conectada, com colunas vindas do schema da fonte.\n"
                    + "- Por que ficou em revisao: a escolha da fonte ainda veio de evidencia semantica fraca, entao nao vou salvar automaticamente.\n"
                    + "- Como prosseguir: confirme que esta fonte esta correta, peca ajustes nas colunas ou diga \"salvar esta tabela\".";
        }
        return "Montei uma primeira pre-visualizacao usando \"" + resourceLabel + "\".\n\n"
                + "- Por que ficou em revisao: a decisao semantica ainda precisa de confirmacao antes de salvar.\n"
                + "- Como prosseguir: confirme a fonte, peca ajustes ou diga que posso salvar.";
    }

    private SemanticAxisAssistantSummary semanticAxisAssistantSummary(JsonNode uiCompositionPlan) {
        List<String> verifiedAxes = new ArrayList<>();
        List<String> unsupportedAxes = new ArrayList<>();
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return new SemanticAxisAssistantSummary(List.of(), List.of());
        }
        for (JsonNode axis : axes) {
            String label = semanticAxisDisplayName(axis);
            if (label.isBlank()) {
                continue;
            }
            if ("unsupported".equals(axis.path("schemaProbeStatus").asText(""))) {
                addUnique(unsupportedAxes, label);
            } else if (axis.path("schemaVerified").asBoolean(false)) {
                addUnique(verifiedAxes, label);
            }
        }
        return new SemanticAxisAssistantSummary(List.copyOf(verifiedAxes), List.copyOf(unsupportedAxes));
    }

    private String semanticAxisDisplayName(JsonNode axis) {
        if (axis == null || axis.isMissingNode() || axis.isNull()) {
            return "";
        }
        String label = firstNonBlank(
                axis.path("schemaLabel").asText(""),
                firstNonBlank(axis.path("label").asText(""), axis.path("field").asText("")));
        String requestedField = axis.path("requestedField").asText("");
        if (axis.path("schemaVerified").asBoolean(false)
                && !requestedField.isBlank()
                && !normalize(requestedField).equals(normalize(axis.path("field").asText("")))) {
            return label + " (pedido como " + requestedField + ")";
        }
        return label;
    }

    private String detailSupportAssistantLine(JsonNode uiCompositionPlan) {
        if (containsComponent(uiCompositionPlan, "praxis-list") && containsComponent(uiCompositionPlan, "praxis-table")) {
            return "lista de detalhe em cards ricos, tabela de detalhe e exploracao em modal pelos graficos para validar os dados antes de salvar";
        }
        if (containsComponent(uiCompositionPlan, "praxis-list")) {
            return "lista de detalhe em cards ricos para validar os dados antes de salvar";
        }
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "tabela de detalhe conectada ao recurso para validar os dados antes de salvar";
        }
        return "componentes de apoio para validar os dados antes de salvar";
    }

    private void addUnique(List<String> values, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        for (String existing : values) {
            if (normalize(existing).equals(normalized)) {
                return;
            }
        }
        values.add(value);
    }

    String joinHuman(List<String> values) {
        List<String> filtered = values == null
                ? List.of()
                : values.stream().filter(value -> !value(value).isBlank()).toList();
        if (filtered.isEmpty()) {
            return "";
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        if (filtered.size() == 2) {
            return filtered.get(0) + " e " + filtered.get(1);
        }
        return String.join(", ", filtered.subList(0, filtered.size() - 1))
                + " e "
                + filtered.get(filtered.size() - 1);
    }

    private boolean containsComponent(JsonNode uiCompositionPlan, String componentId) {
        return AgenticAuthoringSemanticMaterializationPolicy.containsComponent(uiCompositionPlan, componentId);
    }

    private boolean isSingleChartPlan(JsonNode uiCompositionPlan) {
        return uiCompositionPlan != null
                && ("single-chart-page".equals(value(uiCompositionPlan.path("layoutPreset").asText()))
                || "single-chart".equals(value(uiCompositionPlan.path("compositionConstraints").path("mode").asText())));
    }

    private boolean containsRichTableRendering(JsonNode uiCompositionPlan) {
        JsonNode widgets = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (!"praxis-table".equals(widget.path("componentId").asText())) {
                continue;
            }
            JsonNode columns = widget.path("inputs").path("config").path("columns");
            if (!columns.isArray()) {
                continue;
            }
            for (JsonNode column : columns) {
                if (column.has("renderer") || !value(column.path("format").asText()).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String firstNonBlank(String first, String second) {
        return !value(first).isBlank() ? first : value(second);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record SemanticAxisAssistantSummary(
            List<String> verifiedAxes,
            List<String> unsupportedAxes) {
    }
}
