package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

final class AgenticAuthoringKeywordFallbackResolver {

    private final AgenticAuthoringFormCapabilityCatalog formCapabilityCatalog;
    private final AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog;

    AgenticAuthoringKeywordFallbackResolver(
            AgenticAuthoringFormCapabilityCatalog formCapabilityCatalog,
            AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog,
            AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog) {
        this.formCapabilityCatalog = formCapabilityCatalog;
        this.tableCapabilityCatalog = tableCapabilityCatalog;
        this.chartCapabilityCatalog = chartCapabilityCatalog;
    }

    AgenticAuthoringKeywordFallbackResolution resolve(
            String prompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target) {
        String operationKind = resolveOperationKind(prompt);
        String artifactKind = resolveArtifactKind(prompt, currentPageSummary, target);
        String changeKind = resolveChangeKind(prompt, operationKind, artifactKind);
        return new AgenticAuthoringKeywordFallbackResolution(operationKind, artifactKind, changeKind);
    }

    private String resolveOperationKind(String prompt) {
        if (isApiCatalogQuestion(prompt)) {
            return "explore";
        }
        if (containsAny(prompt, "conectar", "ligar", "vincular", "relacionar")) {
            return "connect";
        }
        if (containsAny(prompt, "remover", "remova", "remove", "excluir", "exclua", "apagar", "apague", "retirar", "retire")) {
            return "remove";
        }
        if (containsAny(prompt, "alterar", "altere", "mudar", "mude", "trocar", "troque",
                "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente",
                "dividir", "divida", "renomear", "renomeie", "formatar", "formate", "formatacao")) {
            return "modify";
        }
        if (isExplicitCreateConfirmation(prompt)) {
            return "create";
        }
        if (isConsultativePrompt(prompt)) {
            return "explore";
        }
        if (containsAny(prompt, "criar", "crie", "gerar", "gere", "montar", "monte",
                "construir", "construa", "novo", "nova", "cadastrar", "abrir")) {
            return "create";
        }
        if (tableCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (chartCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (formCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (containsAny(prompt, "explicar", "explique", "porque", "por que")) {
            return "explain";
        }
        return "unknown";
    }

    private String resolveArtifactKind(String prompt, JsonNode currentPageSummary, AgenticAuthoringTarget target) {
        if (isApiCatalogQuestion(prompt)) {
            return "api_catalog";
        }
        if (prefersPayrollDashboardRecommendation(prompt)) {
            return "dashboard";
        }
        if (containsAny(prompt, "formulario", "form", "campo", "campos", "cadastrar", "cadastro", "abrir chamado")) {
            return "form";
        }
        if (target != null && "praxis-dynamic-form".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt))
                || "remove".equals(resolveOperationKind(prompt))
                || formCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "form";
        }
        if (target != null && "praxis-table".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt)) || tableCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "table";
        }
        if (target != null && "praxis-chart".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt)) || chartCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "dashboard";
        }
        if (isExplicitDashboardPrompt(prompt)) {
            return "dashboard";
        }
        if (isTablePrompt(prompt)) {
            return "table";
        }
        if (isDashboardWidgetAdditionPrompt(prompt) && isPayrollAnalyticsPrompt(prompt)) {
            return "dashboard";
        }
        if (prefersPayrollDashboardRecommendation(prompt)) {
            return "dashboard";
        }
        if (containsAny(prompt, "stepper", "etapa", "etapas", "passo", "passos")) {
            return "stepper";
        }
        if (hasInspectedArtifact(currentPageSummary, "form")
                || (currentPageSummary.path("formWidgets").isArray() && !currentPageSummary.path("formWidgets").isEmpty())) {
            return "form";
        }
        return "unknown";
    }

    private boolean hasInspectedArtifact(JsonNode currentPageSummary, String artifactKind) {
        JsonNode widgets = currentPageSummary.path("structuralInspection").path("widgets");
        if (!widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (artifactKind.equals(widget.path("artifactKind").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String resolveChangeKind(String prompt, String operationKind, String artifactKind) {
        if ("connect".equals(operationKind)) {
            return "connect_widgets";
        }
        if ("remove".equals(operationKind) && containsAny(prompt, "campo", "campos")) {
            return formCapabilityCatalog.resolveChangeKind(prompt).orElse("remove_field");
        }
        if ("modify".equals(operationKind) && "form".equals(artifactKind)) {
            Optional<String> formChangeKind = formCapabilityCatalog.resolveChangeKind(prompt);
            if (formChangeKind.isPresent()) {
                return formChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && "table".equals(artifactKind)) {
            Optional<String> tableChangeKind = tableCapabilityCatalog.resolveChangeKind(prompt);
            if (tableChangeKind.isPresent()) {
                return tableChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && "dashboard".equals(artifactKind)) {
            if (isDashboardWidgetAdditionPrompt(prompt)) {
                return "add_dashboard_widget";
            }
            Optional<String> chartChangeKind = chartCapabilityCatalog.resolveChangeKind(prompt);
            if (chartChangeKind.isPresent()) {
                return chartChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && containsAny(prompt,
                "renomear", "renomeie", "label", "rotulo", "titulo")) {
            return "rename_or_relabel";
        }
        if ("modify".equals(operationKind) && containsAny(prompt, "adicionar", "adicione",
                "incluir", "inclua", "acrescentar", "acrescente", "campo", "campos")) {
            return "add_field";
        }
        if ("modify".equals(operationKind) && containsAny(prompt, "etapa", "etapas", "passo", "passos", "dividir")) {
            return "split_into_steps";
        }
        if ("create".equals(operationKind) && "form".equals(artifactKind)) {
            return "create_minimal_form";
        }
        if ("create".equals(operationKind) && "dashboard".equals(artifactKind)
                && containsAny(prompt, "drill down", "drill-down", "drilldown", "detalhar", "detalhe", "aprofundar")) {
            return "create_chart_drilldown";
        }
        if ("create".equals(operationKind)) {
            return "create_artifact";
        }
        if ("explore".equals(operationKind) && "dashboard".equals(artifactKind)) {
            return "recommend_dashboard_visualization";
        }
        if ("explore".equals(operationKind) && "table".equals(artifactKind)) {
            return "recommend_table_visualization";
        }
        if ("explore".equals(operationKind) && "api_catalog".equals(artifactKind)) {
            return "answer_api_catalog_question";
        }
        return "unknown";
    }

    private boolean isApiCatalogQuestion(String prompt) {
        if (!containsApiCatalogSubject(prompt)) {
            return false;
        }
        return containsAny(prompt,
                "qual", "quais", "que api", "que apis", "que endpoint", "que endpoints",
                "que schema", "que schemas", "o que", "essa", "esse", "esta", "este", "existe", "existem",
                "listar", "liste", "mostrar", "mostre", "consultar", "consulta", "devo usar",
                "usar para", "campos existem", "suporta", "permite", "relacionad", "complement",
                "combinar", "combine", "vincul");
    }

    private boolean containsApiCatalogSubject(String prompt) {
        String wordPadded = " " + prompt.replaceAll("[^a-z0-9]+", " ") + " ";
        if (wordPadded.contains(" api ") || wordPadded.contains(" apis ")
                || wordPadded.contains(" acao ") || wordPadded.contains(" acoes ")
                || wordPadded.contains(" action ") || wordPadded.contains(" actions ")) {
            return true;
        }
        return containsAny(prompt, "endpoint", "endpoints", "schema", "schemas", "filtro", "filtros", "filtrar");
    }

    private boolean isExplicitCreateConfirmation(String prompt) {
        return containsAny(prompt,
                "sim, crie",
                "sim crie",
                "pode criar",
                "pode fazer",
                "fazer agora",
                "faz agora",
                "pode montar",
                "pode gerar",
                "confirmo criar",
                "confirmo, crie");
    }

    private boolean isConsultativePrompt(String prompt) {
        return containsAny(prompt, "melhor forma", "como visualizar", "visualizar informacoes",
                "visualizar informacao", "visualizar", "analisar", "analise", "explorar", "explore",
                "me ajude", "ajude", "escolher", "sugerir", "sugira", "como ver",
                "opcao", "opcoes", "alternativa", "alternativas", "indicar", "indique", "voce indica", "me indica",
                "recomendar", "recomende", "recomendacao", "recomendacoes", "comparar", "compare",
                "comparativo", "orientar", "oriente", "me oriente", "faz mais sentido", "devo usar",
                "ver", "visao", "mostrar", "mostre");
    }

    private boolean isPayrollAnalyticsPrompt(String prompt) {
        return containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios",
                "departamento", "departamentos");
    }

    private boolean isDashboardWidgetAdditionPrompt(String prompt) {
        return containsAny(prompt, "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente")
                && containsAny(prompt, "widget", "componente", "resumo", "executivo", "kpi", "indicador", "indicadores");
    }

    private boolean prefersPayrollDashboardRecommendation(String prompt) {
        return isPayrollAnalyticsPrompt(prompt)
                && containsAny(prompt, "melhor forma", "como visualizar", "visualizar informacoes",
                "visualizar informacao", "ver", "visao", "mostrar", "mostre", "analisar", "analise",
                "recomendar", "recomende", "recomendacao", "recomendacoes", "opcao", "opcoes",
                "alternativa", "alternativas", "orientar", "oriente", "me oriente", "faz mais sentido",
                "devo usar", "comparar", "compare", "comparativo");
    }

    private boolean isExplicitDashboardPrompt(String prompt) {
        return containsAny(prompt, "dashboard", "painel", "grafico", "graficos", "chart", "charts",
                "kpi", "indicador", "indicadores", "drill down", "drill-down", "drilldown");
    }

    private boolean isTablePrompt(String prompt) {
        return containsAny(prompt, "tabela", "table", "lista", "listagem", "grade", "grid");
    }

    private boolean containsAny(String value, String... tokens) {
        String wordPaddedValue = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalize(token);
            if (normalizedToken.matches("[a-z0-9]+")) {
                if (wordPaddedValue == null) {
                    wordPaddedValue = " " + value.replaceAll("[^a-z0-9]+", " ") + " ";
                }
                if (wordPaddedValue.contains(" " + normalizedToken + " ")) {
                    return true;
                }
                continue;
            }
            if (value.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
