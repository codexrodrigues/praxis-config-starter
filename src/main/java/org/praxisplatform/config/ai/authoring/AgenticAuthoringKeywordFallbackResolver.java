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
        if (mentionsLiteralMasterDetail(prompt)) {
            return "explore";
        }
        if (isTabbedMasterDetailFormPrompt(prompt)) {
            return "create";
        }
        if (isMasterDetailPrompt(prompt) && !mentionsLiteralMasterDetail(prompt)) {
            return "create";
        }
        if (isReadDetailPagePrompt(prompt) && !isConsultativePrompt(prompt)) {
            return "create";
        }
        if (isRecommendationQuestion(prompt) || asksToExploreUnknownInformation(prompt)) {
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
        if (containsAny(prompt, "criar", "crie", "gerar", "gere", "montar", "monte",
                "construir", "construa", "novo", "nova", "cadastrar", "abrir")) {
            return "create";
        }
        if (isConsultativePrompt(prompt)) {
            return "explore";
        }
        if (isAnalyticalVisualizationIntent(prompt)) {
            return "explore";
        }
        if (isBusinessAuthoringRequest(prompt)) {
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
        if (isExplicitDashboardPrompt(prompt) && !asksToExploreUnknownInformation(prompt)) {
            return "dashboard";
        }
        if (isTabbedMasterDetailFormPrompt(prompt)) {
            return "page";
        }
        if (isMasterDetailPrompt(prompt)) {
            return "page";
        }
        if (isTablePrompt(prompt) && !isExplicitPageCompositionPrompt(prompt)) {
            return "table";
        }
        if (isReadDetailPagePrompt(prompt)) {
            return "page";
        }
        if (isOperationalMonitoringDashboardPrompt(prompt)) {
            return "dashboard";
        }
        if (isAnalyticalVisualizationIntent(prompt)) {
            return "dashboard";
        }
        if (isOperationalTrackingPrompt(prompt)) {
            return "table";
        }
        if (isBusinessAuthoringRequest(prompt)) {
            return "table";
        }
        if (containsAny(prompt, "formulario", "form", "campo", "campos", "cadastrar", "cadastro")) {
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
        if (isDashboardWidgetAdditionPrompt(prompt)) {
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
        if ("create".equals(operationKind) && "page".equals(artifactKind)
                && isTabbedMasterDetailFormPrompt(prompt)) {
            return "create_tabbed_master_detail_form";
        }
        if ("create".equals(operationKind) && "page".equals(artifactKind)
                && (isMasterDetailPrompt(prompt) || isReadDetailPagePrompt(prompt))) {
            return "create_master_detail";
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
                "confirmed: criar",
                "confirmed: create",
                "confirmado: criar",
                "confirmado: crie",
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

    private boolean isRecommendationQuestion(String prompt) {
        return containsAny(prompt,
                "melhor forma", "como visualizar", "como ver", "quais opcoes", "que opcoes",
                "devo usar", "faz mais sentido", "recomenda", "recomende", "recomendacao",
                "quais alternativas", "compare alternativas", "antes de criar", "ainda nao sei se devo usar");
    }

    private boolean asksToExploreUnknownInformation(String prompt) {
        return containsAny(prompt,
                "nao sei quais informacoes existem",
                "não sei quais informações existem",
                "quais informacoes existem",
                "quais informações existem");
    }

    private boolean isAnalyticalVisualizationIntent(String prompt) {
        if (isApiCatalogQuestion(prompt)) {
            return false;
        }
        if (containsAny(prompt, "nao sei", "não sei", "quais informacoes existem", "quais informações existem")) {
            return false;
        }
        if (containsAny(prompt,
                "como visualizar", "melhor forma", "antes de criar", "ainda nao sei",
                "quais opcoes", "que opcoes", "compare alternativas", "devo usar",
                "faz mais sentido", "me oriente", "oriente")) {
            return false;
        }
        boolean wantsAnalysis = containsAny(prompt,
                "entender", "compreender", "analisar", "analise", "acompanhar",
                "comparar", "compare", "comparativo", "visualizar", "mostrar", "mostre",
                "ver", "visao");
        boolean hasAnalyticalShape = containsAny(prompt,
                "por", "agrup", "grupo", "segment", "recorte", "area", "categoria",
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "total", "totais", "media", "medias", "evolucao", "historico",
                "distribuicao", "indicador", "indicadores", "kpi", "metric", "metrica");
        boolean writeOrRecordIntent = containsAny(prompt,
                "cadastro", "cadastrar", "salvar", "gravar", "preencher",
                "formulario", "campo", "campos", "editar", "alterar");
        return wantsAnalysis && hasAnalyticalShape && !writeOrRecordIntent;
    }

    private boolean isDashboardWidgetAdditionPrompt(String prompt) {
        return containsAny(prompt, "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente")
                && containsAny(prompt, "widget", "componente", "resumo", "executivo", "kpi", "indicador", "indicadores");
    }

    private boolean isExplicitDashboardPrompt(String prompt) {
        return containsAny(prompt, "dashboard", "grafico", "graficos", "chart", "charts",
                "kpi", "indicador", "indicadores", "drill down", "drill-down", "drilldown")
                || isDashboardPanelPrompt(prompt);
    }

    private boolean isDashboardPanelPrompt(String prompt) {
        if (!containsAny(prompt, "painel")) {
            return false;
        }
        if (isMasterDetailPrompt(prompt) || isReadDetailPagePrompt(prompt)) {
            return false;
        }
        return containsAny(prompt,
                "painel bonito", "painel executivo", "painel gerencial", "painel analitico",
                "painel analítico", "painel de controle", "painel de indicadores",
                "painel geral",
                "visualizar", "ver", "visao", "visão", "acompanhar", "monitorar",
                "analise", "analisar", "comparar", "comparativo");
    }

    private boolean isMasterDetailPrompt(String prompt) {
        return containsAny(prompt,
                "master detail", "master-detail", "mestre detalhe", "mestre-detalhe",
                "lista e detalhe", "lista com detalhe", "abrir detalhes", "abrir detalhe",
                "ver detalhes", "ver detalhe", "ver os detalhes", "area de detalhe", "detalhe lateral",
                "painel de detalhes", "painel lateral", "detalhes ao selecionar",
                "detalhes quando selecionar");
    }

    private boolean isExplicitPageCompositionPrompt(String prompt) {
        return containsAny(prompt,
                "pagina", "page", "tela", "layout", "master detail", "master-detail",
                "mestre detalhe", "mestre-detalhe", "lista e detalhe", "lista com detalhe",
                "tabela e detalhe", "tabela com detalhe", "painel lateral", "detalhe lateral",
                "detalhes ao selecionar", "detalhes quando selecionar");
    }

    private boolean isBusinessAuthoringRequest(String prompt) {
        return containsAny(prompt,
                "quero", "preciso", "gostaria", "necessito", "acompanhar", "controlar",
                "avaliar", "conferir", "entender", "investigar", "organizar", "comparar",
                "saber", "monitorar", "analisar")
                && containsAny(prompt,
                "tabela", "lista", "listagem", "dashboard", "painel", "grafico", "graficos",
                "indicador", "indicadores", "visao", "visão", "tela", "detalhes", "status",
                "prioridade", "valor", "valores", "custo", "custos", "evolucao", "evolução",
                "atividade", "atividades", "responsavel", "responsável", "etapa", "local",
                "locais", "base", "bases", "acesso", "acessos", "estrutura",
                "item", "itens", "recurso", "recursos", "severidade", "andamento");
    }

    private boolean isOperationalTrackingPrompt(String prompt) {
        return containsAny(prompt,
                "acompanhar", "controlar", "conferir", "organizar", "avaliar", "investigar",
                "registrar", "monitorar", "ver", "mostrar", "encontrar", "saber",
                "entender", "analisar")
                && containsAny(prompt,
                "status", "situacao", "situação", "prioridade", "detalhe", "detalhes",
                "contato", "prazo", "periodo", "período", "disponibilidade", "manutencao",
                "manutenção", "aprovacao", "aprovação", "vigencia", "vigência",
                "item", "itens", "atividade", "atividades",
                "responsavel", "responsável", "atraso", "atrasos", "base", "bases",
                "acesso", "acessos", "local", "locais", "usado", "usados", "uso", "usos",
                "estrutura", "contrato", "contratos", "caso",
                "casos", "andamento", "recurso", "recursos");
    }

    private boolean isOperationalMonitoringDashboardPrompt(String prompt) {
        if (isTablePrompt(prompt)) {
            return false;
        }
        boolean monitoringIntent = containsAny(prompt,
                "monitorar", "acompanhar", "controlar", "painel de controle", "observabilidade");
        int operationalAxes = 0;
        if (containsAny(prompt, "gravidade", "severidade", "prioridade")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "andamento", "status", "situacao", "situação", "etapa", "fila")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "responsavel", "responsável", "dono", "owner")) {
            operationalAxes++;
        }
        if (containsAny(prompt, "ocorrencia", "ocorrencias", "caso", "casos")) {
            operationalAxes++;
        }
        return monitoringIntent && operationalAxes >= 2;
    }

    private boolean mentionsLiteralMasterDetail(String prompt) {
        return containsAny(prompt, "master detail", "master-detail", "mestre detalhe", "mestre-detalhe");
    }

    private boolean isTabbedMasterDetailFormPrompt(String prompt) {
        return containsAny(prompt, "aba", "abas", "tab", "tabs")
                && (isMasterDetailPrompt(prompt) || isReadDetailPagePrompt(prompt) || hasSearchDetailAndFormShape(prompt))
                && containsAny(prompt, "formulario", "form", "editar", "alterar", "dados principais", "campos");
    }

    private boolean hasSearchDetailAndFormShape(String prompt) {
        boolean hasSearchOrList = containsAny(prompt,
                "procurar", "buscar", "pesquisar", "listar", "lista", "listagem", "tabela", "dados disponiveis");
        boolean hasSelectionOrDetail = containsAny(prompt,
                "selecionado", "selecionar", "detalhe", "detalhes", "ver dados", "ver informacoes");
        boolean hasFormOrEdit = containsAny(prompt,
                "formulario", "form", "editar", "alterar", "dados principais", "campos");
        return hasSearchOrList && hasSelectionOrDetail && hasFormOrEdit;
    }

    private boolean isReadDetailPagePrompt(String prompt) {
        return containsAny(prompt, "acompanhar", "consultar", "buscar", "encontrar", "ver", "mostrar", "visualizar")
                && containsAny(prompt, "detalhe", "detalhes", "informacoes", "dados")
                && !containsAny(prompt, "cadastro", "cadastrar", "salvar", "gravar", "preencher", "formulario");
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
