package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reference plan provider used by the Quickstart-hosted authoring demo.
 *
 * <p>This provider is intentionally narrow: it recognizes the Human Resources master-detail
 * benchmark that the official Quickstart exposes today and returns a {@code UiCompositionPlan}
 * instead of asking the minimal-form compiler to invent multi-widget page JSON.</p>
 */
public class AgenticAuthoringReferenceUiCompositionPlanProvider implements AgenticAuthoringUiCompositionPlanProvider {

    private static final String DEPARTMENTS = "/api/human-resources/departamentos";
    private static final String EMPLOYEES = "/api/human-resources/funcionarios";
    private static final String PAYROLL_ANALYTICS = "/api/human-resources/vw-analytics-folha-pagamento";
    private static final String PAYROLL = "/api/human-resources/folhas-pagamento";
    private static final String MISSION_SUMMARY = "/api/operations/vw-resumo-missoes";
    private static final String PAYROLL_DRILLDOWN_DETAIL_KEY = "payroll-drilldown-list";
    private static final PayrollBreakdown DEPARTMENT_BREAKDOWN =
            new PayrollBreakdown("departamento", "Departamento", "department", "selectedDepartment", "group-by", "bar");
    private static final PayrollBreakdown COMPETENCE_BREAKDOWN =
            new PayrollBreakdown("competencia", "Competencia", "competence", "selectedCompetence", "timeseries", "line");
    private static final PayrollBreakdown STATUS_BREAKDOWN =
            new PayrollBreakdown("composicaoFolha", "Status", "status", "selectedPayrollStatus", "group-by", "bar");
    private static final PayrollBreakdown ROLE_BREAKDOWN =
            new PayrollBreakdown("cargo", "Cargo", "role", "selectedPayrollRole", "group-by", "bar");
    private static final PayrollBreakdown TEAM_BREAKDOWN =
            new PayrollBreakdown("equipe", "Equipe", "team", "selectedPayrollTeam", "group-by", "bar");
    private static final PayrollBreakdown BASE_BREAKDOWN =
            new PayrollBreakdown("base", "Base", "base", "selectedPayrollBase", "group-by", "bar");
    private static final PayrollBreakdown PROFILE_BREAKDOWN =
            new PayrollBreakdown("payrollProfile", "Perfil", "profile", "selectedPayrollProfile", "group-by", "bar");

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;

    private record LocalEditorialWorkspaceSpec(
            String formTabId,
            String formTabLabel,
            String recordsTabId,
            String recordsTabLabel,
            String trackingTabId,
            String trackingTabLabel,
            String statePath,
            int recordCount) {}

    private record LocalFormFieldSpec(
            String name,
            String label,
            String controlType,
            boolean required,
            String promptToken) {}

    private record LocalCrudColumnSpec(
            String field,
            String header,
            String promptToken) {}

    private record LocalCrudActionSpec(
            String id,
            String label,
            String icon,
            boolean toolbarAction,
            String promptToken) {}

    public AgenticAuthoringReferenceUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        Optional<AgenticAuthoringUiCompositionPlanResult> chartModification = chartModification(request);
        if (chartModification.isPresent()) {
            return chartModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnOrderModification = tableColumnOrderModification(request);
        if (tableColumnOrderModification.isPresent()) {
            return tableColumnOrderModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnVisibilityModification = tableColumnVisibilityModification(request);
        if (tableColumnVisibilityModification.isPresent()) {
            return tableColumnVisibilityModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnFormatModification = tableColumnFormatModification(request);
        if (tableColumnFormatModification.isPresent()) {
            return tableColumnFormatModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> tableTitleModification = tableTitleModification(request);
        if (tableTitleModification.isPresent()) {
            return tableTitleModification;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> dashboardWidgetAddition = dashboardWidgetAddition(request);
        if (dashboardWidgetAddition.isPresent()) {
            return dashboardWidgetAddition;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> selectedPayrollTable = selectedPayrollTable(request);
        if (selectedPayrollTable.isPresent()) {
            return selectedPayrollTable;
        }
        if (supportsChartDrillDown(request)) {
            PayrollBreakdown breakdown = resolvePayrollBreakdown(request.userPrompt());
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of(
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown",
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown:" + breakdown.field()),
                    chartDrillDownPlan(breakdown),
                    emptyCompiledFormPatch()));
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceDashboard = selectedResourceDashboard(request);
        if (selectedResourceDashboard.isPresent()) {
            return selectedResourceDashboard;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceTabbedWorkspace =
                selectedResourceTabbedWorkspace(request);
        if (selectedResourceTabbedWorkspace.isPresent()) {
            return selectedResourceTabbedWorkspace;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> localEditorialTabbedWorkspace =
                localEditorialTabbedWorkspace(request);
        if (localEditorialTabbedWorkspace.isPresent()) {
            return localEditorialTabbedWorkspace;
        }
        Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceMasterDetail = selectedResourceMasterDetail(request);
        if (selectedResourceMasterDetail.isPresent()) {
            return selectedResourceMasterDetail;
        }
        if (supportsPayrollTable(request)) {
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:quickstart-payroll-table"),
                    payrollTablePlan(),
                    emptyCompiledFormPatch()));
        }
        if (!supports(request)) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-human-resources"),
                masterDetailPlan(),
                emptyCompiledFormPatch()));
    }

    private boolean supportsChartDrillDown(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null) {
            return false;
        }
        if (isResolvedTableCreateIntent(request)) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        boolean asksForTable = containsAny(prompt, "tabela", "grid", "lista", "listagem", "listar", "liste", "relacao");
        boolean rejectsDashboard = containsAny(prompt, "ignore", "ignorar", "desconsidere")
                && containsAny(prompt, "dashboard", "painel");
        if (asksForTable && rejectsDashboard) {
            return false;
        }
        boolean asksForChart = containsAny(prompt, "chart", "grafico", "bar", "barra", "donut", "pizza");
        boolean asksForDashboard = containsAny(prompt, "dashboard", "painel");
        boolean asksForDrillDown = containsAny(prompt, "drill down", "drill-down", "drilldown", "detalhar", "detalhe", "aprofundar");
        boolean referencesPayrollOrDepartment = containsAny(prompt,
                "folha", "pagamento", "salario", "salarios", "remuneracao", "remuneracoes", "recebe", "ganha",
                "departamento", "departamentos", "area", "areas",
                "funcionario", "funcionarios", "cargo", "cargos", "equipe", "equipes", "base", "bases",
                "perfil", "perfis");
        boolean referencesPayrollByBreakdown = containsAny(prompt,
                "departamento", "departamentos", "competencia", "competencias", "mes", "mensal",
                "status", "situacao", "setor", "setores", "area", "areas", "cargo", "cargos", "equipe", "equipes", "base", "bases",
                "perfil", "perfis", "profile", "ranking", "rank", "top", "maior", "maiores", "menor", "menores")
                && containsAny(prompt, "folha", "pagamento", "salario", "salarios", "remuneracao", "remuneracoes", "recebe", "ganha");
        return (asksForChart && asksForDrillDown && referencesPayrollOrDepartment)
                || (asksForDashboard && referencesPayrollByBreakdown);
    }

    private PayrollBreakdown resolvePayrollBreakdown(String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized, "competencia", "competencias", "mes", "mensal")) {
            return COMPETENCE_BREAKDOWN;
        }
        if (containsAny(normalized, "status", "situacao", "composicao")) {
            return STATUS_BREAKDOWN;
        }
        if (containsAny(normalized, "cargo", "cargos", "funcao", "funcoes")) {
            return ROLE_BREAKDOWN;
        }
        if (containsAny(normalized, "area", "areas", "setor", "setores", "departamento", "departamentos")) {
            return DEPARTMENT_BREAKDOWN;
        }
        if (containsAny(normalized, "equipe", "equipes", "time", "times")) {
            return TEAM_BREAKDOWN;
        }
        if (containsAny(normalized, "base", "bases", "localidade", "localidades")) {
            return BASE_BREAKDOWN;
        }
        if (containsAny(normalized, "perfil", "perfis", "profile", "payroll profile")) {
            return PROFILE_BREAKDOWN;
        }
        // Ranking requests without an explicit aggregate dimension must stay on
        // allowed analytics dimensions; record-level details remain in the table.
        return DEPARTMENT_BREAKDOWN;
    }

    private boolean supportsPayrollTable(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        boolean asksToCreate = containsAny(prompt, "crie", "criar", "gere", "gerar", "monte", "montar");
        boolean asksForTable = containsAny(prompt, "tabela", "grid", "lista", "listagem", "listar", "liste", "relacao");
        boolean referencesPayroll = containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios");
        return asksToCreate && asksForTable && referencesPayroll;
    }

    private boolean isResolvedTableCreateIntent(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringIntentResolutionResult intent = request == null ? null : request.intentResolution();
        return intent != null
                && intent.valid()
                && "create".equals(intent.operationKind())
                && "table".equals(intent.artifactKind());
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableTitleModification(AgenticAuthoringPlanRequest request) {
        if (!supportsTableTitleModification(request)) {
            return Optional.empty();
        }
        String title = extractTitleAfterPara(request.userPrompt());
        if (title.isBlank()) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        ObjectNode tableWidget = findWidget(page, request.intentResolution().target().widgetKey());
        if (tableWidget == null || !"praxis-table".equals(tableWidget.path("definition").path("id").asText())) {
            return Optional.empty();
        }
        ObjectNode inputs = tableWidget.with("definition").with("inputs");
        inputs.put("title", title);
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-table-title-modification"),
                null,
                compiledPagePatch(page, "modify-existing-table")));
    }

    private boolean supportsTableTitleModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "rename_or_relabel".equals(request.intentResolution().changeKind())
                && tableCapabilityCatalog.supports("rename_or_relabel", prompt)
                && containsAny(prompt, "tabela", "grid", "lista", "listagem");
    }

    private String extractTitleAfterPara(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        String lower = normalize(normalized);
        int index = lower.lastIndexOf(" para ");
        if (index < 0) {
            return "";
        }
        return normalized.substring(index + " para ".length()).trim().replaceAll("[.?!]+$", "").trim();
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> dashboardWidgetAddition(AgenticAuthoringPlanRequest request) {
        if (!supportsDashboardWidgetAddition(request)) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        String key = nextAvailableWidgetKey(page, "payroll-executive-summary");
        addExecutiveSummaryPageWidget(page, key);
        addCanvasItemForAppendedWidget(page, key, 1, nextCanvasRow(page), 12, 2);
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-dashboard-widget-addition"),
                null,
                compiledPagePatch(page, "add-dashboard-widget")));
    }

    private boolean supportsDashboardWidgetAddition(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        return "modify".equals(request.intentResolution().operationKind())
                && "dashboard".equals(request.intentResolution().artifactKind())
                && "add_dashboard_widget".equals(request.intentResolution().changeKind())
                && containsAny(prompt, "resumo", "executivo", "kpi", "indicador", "indicadores", "total", "media", "media salarial")
                && containsAny(prompt, "folha", "pagamento", "salario", "salarios", "descontos");
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceDashboard(AgenticAuthoringPlanRequest request) {
        if (!supportsSelectedResourceDashboard(request)) {
            return Optional.empty();
        }
        AgenticAuthoringCandidate originalCandidate = canonicalSelectedResourceCandidate(request.intentResolution().selectedCandidate());
        AgenticAuthoringCandidate candidate = effectiveSelectedDashboardCandidate(request);
        if (isSelectedPayrollAnalyticsDashboardCandidate(candidate)) {
            PayrollBreakdown breakdown = resolvePayrollBreakdown(request.userPrompt());
            List<String> warnings = usesCurrentPagePayrollAnalyticsContext(request, originalCandidate, candidate)
                    ? List.of(
                            "ui-composition-plan-provider:selected-resource-dashboard",
                            "ui-composition-plan-provider:selected-resource-dashboard:current-page-analytics-context",
                            "ui-composition-plan-provider:selected-resource-dashboard:analytics-chart",
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown",
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown:" + breakdown.field())
                    : List.of(
                            "ui-composition-plan-provider:selected-resource-dashboard",
                            "ui-composition-plan-provider:selected-resource-dashboard:analytics-chart",
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown",
                            "ui-composition-plan-provider:quickstart-payroll-chart-drilldown:" + breakdown.field());
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    warnings,
                    chartDrillDownPlan(breakdown),
                    emptyCompiledFormPatch()));
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:selected-resource-dashboard"),
                selectedResourceDashboardPlan(request, candidate),
                emptyCompiledFormPatch()));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceMasterDetail(AgenticAuthoringPlanRequest request) {
        if (!supportsSelectedResourceMasterDetail(request)) {
            return Optional.empty();
        }
        AgenticAuthoringCandidate candidate = request.intentResolution().selectedCandidate();
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:selected-resource-master-detail"),
                selectedResourceMasterDetailPlan(request, candidate, shouldIncludeSearchFilter(request.userPrompt())),
                emptyCompiledFormPatch()));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> selectedResourceTabbedWorkspace(
            AgenticAuthoringPlanRequest request) {
        if (!supportsSelectedResourceTabbedWorkspace(request)) {
            return Optional.empty();
        }
        AgenticAuthoringCandidate candidate = request.intentResolution().selectedCandidate();
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:selected-resource-tabbed-workspace"),
                selectedResourceTabbedWorkspacePlan(request, candidate),
                emptyCompiledFormPatch()));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> localEditorialTabbedWorkspace(
            AgenticAuthoringPlanRequest request) {
        if (!supportsLocalEditorialTabbedWorkspace(request)) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:local-editorial-tabbed-workspace"),
                localEditorialTabbedWorkspacePlan(request),
                emptyCompiledFormPatch()));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> selectedPayrollTable(AgenticAuthoringPlanRequest request) {
        if (!supportsSelectedPayrollTable(request)) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-payroll-table:selected-intent"),
                payrollTablePlan(),
                emptyCompiledFormPatch()));
    }

    private boolean supportsSelectedPayrollTable(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        String resourcePath = valueOrEmpty(candidate == null ? null : candidate.resourcePath());
        return intent.valid()
                && "create".equals(intent.operationKind())
                && "table".equals(intent.artifactKind())
                && (resourcePath.equals(PAYROLL) || resourcePath.startsWith(PAYROLL + "/"));
    }

    private boolean supportsSelectedResourceDashboard(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        boolean createsDashboard = "create".equals(intent.operationKind())
                && ("create_artifact".equals(intent.changeKind())
                || "create_dashboard".equals(intent.changeKind()));
        boolean modifiesSelectedAnalyticsDashboard = "modify".equals(intent.operationKind())
                && "add_dashboard_widget".equals(intent.changeKind())
                && isSelectedPayrollAnalyticsDashboardCandidate(effectiveSelectedDashboardCandidate(request));
        AgenticAuthoringCandidate candidate = effectiveSelectedDashboardCandidate(request);
        return intent.valid()
                && "dashboard".equals(intent.artifactKind())
                && (createsDashboard || modifiesSelectedAnalyticsDashboard)
                && candidate.resourcePath() != null
                && !candidate.resourcePath().isBlank();
    }

    private boolean isSelectedPayrollAnalyticsDashboardCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String resourcePath = valueOrEmpty(candidate.resourcePath());
        String submitUrl = valueOrEmpty(candidate.submitUrl());
        return (PAYROLL_ANALYTICS.equals(resourcePath) || PAYROLL.equals(resourcePath))
                && (submitUrl.startsWith(PAYROLL_ANALYTICS + "/stats/")
                || submitUrl.startsWith(PAYROLL + "/stats/"));
    }

    private AgenticAuthoringCandidate effectiveSelectedDashboardCandidate(AgenticAuthoringPlanRequest request) {
        AgenticAuthoringCandidate candidate = canonicalSelectedResourceCandidate(
                request == null || request.intentResolution() == null
                        ? null
                        : request.intentResolution().selectedCandidate());
        if (isSelectedPayrollAnalyticsDashboardCandidate(candidate)) {
            return candidate;
        }
        if (candidate != null && candidate.resourcePath() != null && !candidate.resourcePath().isBlank()) {
            return candidate;
        }
        if (currentPageReferencesResource(request == null ? null : request.currentPage(), PAYROLL_ANALYTICS)
                && isDashboardLikePrompt(request == null ? null : request.userPrompt())) {
            return payrollAnalyticsStatsCandidate("current page analytics context");
        }
        return candidate;
    }

    private boolean usesCurrentPagePayrollAnalyticsContext(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate originalCandidate,
            AgenticAuthoringCandidate effectiveCandidate) {
        return isSelectedPayrollAnalyticsDashboardCandidate(effectiveCandidate)
                && !isSelectedPayrollAnalyticsDashboardCandidate(originalCandidate)
                && currentPageReferencesResource(request == null ? null : request.currentPage(), PAYROLL_ANALYTICS);
    }

    private AgenticAuthoringCandidate payrollAnalyticsStatsCandidate(String reason) {
        return new AgenticAuthoringCandidate(
                PAYROLL_ANALYTICS,
                "post",
                "/schemas/filtered?path=" + PAYROLL_ANALYTICS + "/stats/group-by&operation=post&schemaType=response",
                PAYROLL_ANALYTICS + "/stats/group-by",
                "post",
                0.94d,
                reason,
                List.of("current-page", "analytics-context"));
    }

    private boolean isDashboardLikePrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized, "dashboard", "painel", "grafico", "grafico", "analise", "analytics", "ranking", "maior", "maiores");
    }

    private boolean currentPageReferencesResource(JsonNode node, String resourcePath) {
        if (node == null || node.isMissingNode() || node.isNull() || resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        if (node.isTextual()) {
            return resourcePath.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (currentPageReferencesResource(item, resourcePath)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                if (currentPageReferencesResource(child, resourcePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean currentPageReferencesComponent(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || componentId == null || componentId.isBlank()) {
            return false;
        }
        if (node.isTextual()) {
            return componentId.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (currentPageReferencesComponent(item, componentId)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                if (currentPageReferencesComponent(child, componentId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean supportsSelectedResourceMasterDetail(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null
                || request.intentResolution().selectedCandidate() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        return intent.valid()
                && "create".equals(intent.operationKind())
                && "page".equals(intent.artifactKind())
                && ("create_master_detail".equals(intent.changeKind())
                || "create_artifact".equals(intent.changeKind()))
                && candidate.resourcePath() != null
                && !candidate.resourcePath().isBlank();
    }

    private boolean supportsSelectedResourceTabbedWorkspace(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null
                || request.intentResolution().selectedCandidate() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        AgenticAuthoringCandidate candidate = intent.selectedCandidate();
        boolean createsTabbedWorkspace = "create".equals(intent.operationKind())
                && "page".equals(intent.artifactKind())
                && "create_tabbed_master_detail_form".equals(intent.changeKind());
        boolean refinesExistingTabbedWorkspace = "create".equals(intent.operationKind())
                && "page".equals(intent.artifactKind())
                && ("create_master_detail".equals(intent.changeKind())
                || "create_artifact".equals(intent.changeKind()))
                && currentPageReferencesComponent(request.currentPage(), "praxis-tabs")
                && currentPageReferencesResource(request.currentPage(), candidate.resourcePath())
                && shouldIncludeSearchFilter(request.userPrompt());
        return intent.valid()
                && (createsTabbedWorkspace || refinesExistingTabbedWorkspace)
                && candidate.resourcePath() != null
                && !candidate.resourcePath().isBlank();
    }

    private boolean supportsLocalEditorialTabbedWorkspace(AgenticAuthoringPlanRequest request) {
        if (request == null || request.intentResolution() == null || request.userPrompt() == null) {
            return false;
        }
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        String prompt = normalize(request.userPrompt());
        boolean localDemoIntent = containsAny(prompt,
                "conteudo local",
                "conteudo editorial",
                "editorial local",
                "local/editorial",
                "dados locais",
                "demo",
                "demonstracao",
                "exemplo",
                "exemplos",
                "sem dependencia de api",
                "sem api real",
                "sem schema externo",
                "sem conectar dados",
                "sem conectar api",
                "sem regra de negocio",
                "sem criar regra");
        boolean tabbedFormListIntent = containsAny(prompt, "tab", "tabs", "aba", "abas")
                && containsAny(prompt, "formulario", "form")
                && containsAny(prompt, "lista", "list", "cards", "cartoes");
        boolean localTabbedWorkspaceRefinement = containsAny(prompt, "tab", "tabs", "aba", "abas")
                && containsAny(prompt, "crud", "registros", "relacionamento", "relacionamentos", "comentario", "comentarios")
                && containsAny(prompt,
                "refine", "refinar", "adicione", "adicionar", "inclua", "incluir",
                "ajuste", "ajustar", "preencha", "preencher", "complete", "completar",
                "corrija", "corrigir", "preserve", "manter", "mantendo");
        boolean pageCompositionIntent = "page".equals(intent.artifactKind())
                || "dashboard".equals(intent.artifactKind())
                || "unknown".equals(intent.artifactKind())
                || localTabbedWorkspaceRefinement;
        return intent.valid()
                && pageCompositionIntent
                && localDemoIntent
                && (tabbedFormListIntent || localTabbedWorkspaceRefinement);
    }

    private ObjectNode localEditorialTabbedWorkspacePlan(AgenticAuthoringPlanRequest request) {
        String tabsKey = "local-solicitacoes-workspace";
        LocalEditorialWorkspaceSpec spec = localEditorialWorkspaceSpec(request.userPrompt());
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "local-editorial-tabbed-workspace");
        ArrayNode sourceRefs = plan.putArray("sourceRefs");
        addSourceRef(sourceRefs, "intent-resolution");
        addSourceRef(sourceRefs, "local-editorial-demo-content");
        addProjectKnowledgeSourceRefs(sourceRefs, request);

        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "96px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        canvas.put("collisionPolicy", "block");
        addCanvasItem(canvas.putObject("items"), tabsKey, 1, 1, 12, 7);

        ArrayNode widgets = plan.putArray("widgets");
        addLocalEditorialTabs(widgets, tabsKey, request.userPrompt(), spec);
        if (shouldModelLocalSelectionRelationship(request.userPrompt())) {
            plan.putObject("state").putObject("values").putNull(spec.statePath());
            ArrayNode bindings = plan.putArray("bindings");
            addNestedLocalCrudRowClickStateBinding(
                    bindings,
                    tabsKey,
                    "local-solicitacoes-crud",
                    spec.recordsTabId(),
                    spec.statePath());
            addNestedLocalSelectedItemTrackingBinding(
                    bindings,
                    tabsKey,
                    "local-solicitacoes-relacionamentos-list",
                    spec.trackingTabId(),
                    spec.statePath());
        } else {
            plan.putArray("bindings");
        }
        return plan;
    }

    private LocalEditorialWorkspaceSpec localEditorialWorkspaceSpec(String prompt) {
        String normalizedPrompt = normalize(prompt);
        String recordsLabel = containsAny(normalizedPrompt, "aba fila", "tab fila")
                ? "Fila"
                : "Registros";
        String trackingLabel = containsAny(normalizedPrompt, "aba detalhes", "tab detalhes")
                ? "Detalhes"
                : containsAny(normalizedPrompt, "acompanhamento")
                        ? "Acompanhamento"
                        : "Relacionamentos";
        String statePath = containsAny(normalizedPrompt, "selectedticket", "selected ticket")
                ? "selectedTicket"
                : "selectedItem";
        return new LocalEditorialWorkspaceSpec(
                "cadastro",
                "Cadastro",
                slugTabId(recordsLabel),
                recordsLabel,
                slugTabId(trackingLabel),
                trackingLabel,
                statePath,
                requestedLocalRecordCount(normalizedPrompt));
    }

    private String slugTabId(String label) {
        String slug = normalize(label).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "tab" : slug;
    }

    private int requestedLocalRecordCount(String normalizedPrompt) {
        if (containsAny(normalizedPrompt, "cinco", "5 chamados", "5 registros", "5 itens", "5 solicitacoes")) {
            return 5;
        }
        if (containsAny(normalizedPrompt, "quatro", "4 chamados", "4 registros", "4 itens", "4 solicitacoes")) {
            return 4;
        }
        return 3;
    }

    private boolean formTabSectionMentions(String normalizedPrompt, String... terms) {
        String formSection = localFormSection(normalizedPrompt);
        return !formSection.isBlank() && containsAny(formSection, terms);
    }

    private String localFormSection(String normalizedPrompt) {
        int formIndex = normalizedPrompt.indexOf("aba cadastro");
        if (formIndex < 0) {
            formIndex = normalizedPrompt.indexOf("cadastro");
        }
        if (formIndex < 0) {
            return "";
        }
        int nextTabIndex = nextTabMentionIndex(normalizedPrompt, formIndex + 1);
        return nextTabIndex > formIndex
                ? normalizedPrompt.substring(formIndex, nextTabIndex)
                : normalizedPrompt.substring(formIndex);
    }

    private int nextTabMentionIndex(String normalizedPrompt, int fromIndex) {
        int next = -1;
        for (String marker : List.of("aba registros", "aba relacionamento", "aba relacionamentos", "aba acompanhamento", "aba fila", "aba detalhes")) {
            int index = normalizedPrompt.indexOf(marker, fromIndex);
            if (index >= 0 && (next < 0 || index < next)) {
                next = index;
            }
        }
        return next;
    }

    private String localRecordsSection(String normalizedPrompt) {
        int recordsIndex = normalizedPrompt.indexOf("aba fila");
        if (recordsIndex < 0) {
            recordsIndex = normalizedPrompt.indexOf("aba registros");
        }
        if (recordsIndex < 0) {
            recordsIndex = normalizedPrompt.indexOf("fila");
        }
        if (recordsIndex < 0) {
            recordsIndex = normalizedPrompt.indexOf("registros");
        }
        if (recordsIndex < 0) {
            return "";
        }
        int nextTabIndex = nextTabMentionIndex(normalizedPrompt, recordsIndex + 1);
        return nextTabIndex > recordsIndex
                ? normalizedPrompt.substring(recordsIndex, nextTabIndex)
                : normalizedPrompt.substring(recordsIndex);
    }

    private void addLocalEditorialTabs(
            ArrayNode widgets,
            String widgetKey,
            String prompt,
            LocalEditorialWorkspaceSpec spec) {
        String normalizedPrompt = normalize(prompt);
        boolean useCrudWorkspace = shouldUseCrudWorkspace(normalizedPrompt);
        boolean useInternalRequestFields = containsAny(normalizedPrompt, "titulo", "responsavel", "prazo");
        List<LocalFormFieldSpec> requestedFormFields = requestedLocalFormFields(normalizedPrompt);
        boolean includeAttachmentFields = containsAny(normalizedPrompt, "anexo", "anexos");
        boolean includeInternalNotes = containsAny(normalizedPrompt,
                "observacao", "observacoes", "observação", "observações");
        boolean includeFormCategory = containsAny(normalizedPrompt, "categoria", "category")
                && formTabSectionMentions(normalizedPrompt, "categoria", "category");
        boolean includeFormSlaExpected = containsAny(normalizedPrompt, "sla esperado")
                || formTabSectionMentions(normalizedPrompt, "sla");
        boolean includeFormDescription = containsAny(normalizedPrompt, "descricao", "descrição", "description");
        boolean includeCrudCategory = containsAny(normalizedPrompt, "categoria", "category");
        boolean includeCrudSla = containsAny(normalizedPrompt, "sla");
        boolean includeCrudLastUpdate = containsAny(normalizedPrompt,
                "ultima atualizacao", "última atualização", "ultima atualização", "última atualizacao");
        boolean includeCrudDetailsAction = containsAny(normalizedPrompt, "ver detalhes", "detalhes");
        List<LocalCrudColumnSpec> requestedCrudColumns = requestedLocalCrudColumns(normalizedPrompt);
        List<LocalCrudActionSpec> requestedCrudActions = requestedLocalCrudActions(normalizedPrompt);
        boolean includeCommentStatusLabel = containsAny(normalizedPrompt,
                "status do comentario", "status do comentário", "comment status");
        boolean useRelationshipComments = containsAny(normalizedPrompt,
                "relacionamento", "relacionamentos", "comentario", "comentarios", "comentário", "comentários",
                "historico", "histórico");
        boolean includeRelationshipHistory = containsAny(normalizedPrompt, "historico", "histórico", "autor", "data");
        boolean renameRelationshipsToTracking = containsAny(normalizedPrompt, "acompanhamento")
                && containsAny(normalizedPrompt, "renomeie", "renomear", "renomeado", "renomeada", "nomeie", "chame");
        boolean includeSlaSummaryCards = containsAny(normalizedPrompt, "sla")
                && containsAny(normalizedPrompt, "card", "cards", "cartao", "cartoes", "cartão", "cartões")
                && (containsAny(normalizedPrompt, "abertos", "em risco", "resolvidos")
                        || containsAny(normalizedPrompt,
                                "item selecionado",
                                "registro selecionado",
                                "selecionado",
                                "selecionada",
                                "relacionado ao item",
                                "relacionados ao item"));
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-tabs");
        widget.put("role", "local-editorial-workspace");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("tabsId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.putObject("group").put("dynamicHeight", true).put("preserveContent", true);
        ArrayNode tabs = config.putArray("tabs");

        ObjectNode cadastroTab = tabs.addObject();
        cadastroTab.put("id", spec.formTabId());
        cadastroTab.put("textLabel", spec.formTabLabel());
        addNestedLocalEditorialForm(
                cadastroTab.putArray("widgets"),
                "local-solicitacao-form",
                requestedFormFields,
                useInternalRequestFields,
                includeAttachmentFields,
                includeInternalNotes,
                includeFormCategory,
                includeFormSlaExpected,
                includeFormDescription);

        if (useCrudWorkspace) {
            ObjectNode registrosTab = tabs.addObject();
            registrosTab.put("id", spec.recordsTabId());
            registrosTab.put("textLabel", spec.recordsTabLabel());
            addNestedLocalEditorialCrud(
                    registrosTab.putArray("widgets"),
                    "local-solicitacoes-crud",
                    requestedCrudColumns,
                    requestedCrudActions,
                    includeCrudCategory,
                    includeCrudSla,
                    includeCrudLastUpdate,
                    includeCrudDetailsAction,
                    spec.recordCount());

            ObjectNode relacionamentosTab = tabs.addObject();
            relacionamentosTab.put("id", spec.trackingTabId());
            relacionamentosTab.put("textLabel", spec.trackingTabLabel());
            addNestedLocalEditorialList(
                    relacionamentosTab.putArray("widgets"),
                    "local-solicitacoes-relacionamentos-list",
                    useRelationshipComments,
                    includeCommentStatusLabel,
                    includeRelationshipHistory,
                    includeSlaSummaryCards);
        } else {
            ObjectNode acompanhamentoTab = tabs.addObject();
            acompanhamentoTab.put("id", "acompanhamento");
            acompanhamentoTab.put("textLabel", "Acompanhamento");
            addNestedLocalEditorialList(
                    acompanhamentoTab.putArray("widgets"),
                    "local-solicitacoes-list",
                    false,
                    includeCommentStatusLabel,
                    includeRelationshipHistory,
                    includeSlaSummaryCards);
        }
    }

    private void addNestedLocalEditorialForm(
            ArrayNode widgets,
            String widgetKey,
            List<LocalFormFieldSpec> requestedFormFields,
            boolean useInternalRequestFields,
            boolean includeAttachmentFields,
            boolean includeInternalNotes,
            boolean includeFormCategory,
            boolean includeFormSlaExpected,
            boolean includeFormDescription) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-dynamic-form");
        widget.put("childWidgetKey", widgetKey);
        widget.putArray("bindingOrder")
                .add("formId")
                .add("componentInstanceId")
                .add("config")
                .add("mode")
                .add("enableCustomization");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("mode", "edit");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", "Solicitacao");
        ArrayNode fields = config.putArray("fieldMetadata");
        if (!requestedFormFields.isEmpty()) {
            requestedFormFields.forEach(field ->
                    addLocalFormField(fields, field.name(), field.label(), field.controlType(), field.required()));
        } else if (useInternalRequestFields) {
            addLocalFormField(fields, "titulo", "Título", "input", true);
            addLocalFormField(fields, "responsavel", "Responsável", "input", true);
            addLocalFormField(fields, "prioridade", "Prioridade", "select", false);
            addLocalFormField(fields, "prazo", "Prazo", "date", false);
            if (includeFormCategory) {
                addLocalFormField(fields, "categoria", "Categoria", "input", false);
            }
            if (includeFormSlaExpected) {
                addLocalFormField(fields, "slaEsperado", "SLA esperado", "input", false);
            }
            if (includeFormDescription) {
                addLocalFormField(fields, "descricao", "Descrição", "textarea", false);
            }
            if (includeAttachmentFields) {
                addLocalFormField(fields, "anexosSimulados", "Anexos simulados", "textarea", false);
            }
            if (includeInternalNotes) {
                addLocalFormField(fields, "observacoesInternas", "Observações internas", "textarea", false);
            }
        } else {
            addLocalFormField(fields, "nome", "Nome", "input", true);
            addLocalFormField(fields, "email", "Email", "email", true);
            addLocalFormField(fields, "prioridade", "Prioridade", "select", false);
        }
        ArrayNode sections = config.putArray("sections");
        ObjectNode section = sections.addObject();
        section.put("id", "cadastro");
        section.put("title", "Cadastro");
        ArrayNode rows = section.putArray("rows");
        ObjectNode row = rows.addObject();
        row.put("id", "cadastro-row");
        ArrayNode columns = row.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("id", "cadastro-column");
        ArrayNode sectionFields = column.putArray("fields");
        if (!requestedFormFields.isEmpty()) {
            requestedFormFields.forEach(field -> sectionFields.add(field.name()));
        } else if (useInternalRequestFields) {
            sectionFields.add("titulo").add("responsavel").add("prioridade").add("prazo");
            if (includeFormCategory) {
                sectionFields.add("categoria");
            }
            if (includeFormSlaExpected) {
                sectionFields.add("slaEsperado");
            }
            if (includeFormDescription) {
                sectionFields.add("descricao");
            }
            if (includeAttachmentFields) {
                sectionFields.add("anexosSimulados");
            }
            if (includeInternalNotes) {
                sectionFields.add("observacoesInternas");
            }
        } else {
            sectionFields.add("nome").add("email").add("prioridade");
        }
    }

    private List<LocalFormFieldSpec> requestedLocalFormFields(String normalizedPrompt) {
        String formSection = localFormSection(normalizedPrompt);
        if (formSection.isBlank() || !containsAny(formSection, "campo", "campos", "formulario", "formulário")) {
            return List.of();
        }
        String explicitFieldSection = explicitLocalFormFieldsSection(formSection);
        if (explicitFieldSection.isBlank()) {
            return List.of();
        }
        List<LocalFormFieldSpec> knownFields = List.of(
                new LocalFormFieldSpec("assunto", "Assunto", "input", true, "assunto"),
                new LocalFormFieldSpec("solicitante", "Solicitante", "input", true, "solicitante"),
                new LocalFormFieldSpec("areaImpactada", "Área impactada", "input", false, "area impactada"),
                new LocalFormFieldSpec("titulo", "Título", "input", true, "titulo"),
                new LocalFormFieldSpec("responsavel", "Responsável", "input", true, "responsavel"),
                new LocalFormFieldSpec("prioridade", "Prioridade", "select", false, "prioridade"),
                new LocalFormFieldSpec("canal", "Canal", "input", false, "canal"),
                new LocalFormFieldSpec("prazoDesejado", "Prazo desejado", "date", false, "prazo desejado"),
                new LocalFormFieldSpec("prazo", "Prazo", "date", false, "prazo"),
                new LocalFormFieldSpec("categoria", "Categoria", "input", false, "categoria"),
                new LocalFormFieldSpec("slaEsperado", "SLA esperado", "input", false, "sla esperado"),
                new LocalFormFieldSpec("descricao", "Descrição", "textarea", false, "descricao"),
                new LocalFormFieldSpec("evidenciasSimuladas", "Evidências simuladas", "textarea", false, "evidencias simuladas"),
                new LocalFormFieldSpec("anexosSimulados", "Anexos simulados", "textarea", false, "anexos simulados"),
                new LocalFormFieldSpec("observacoesInternas", "Observações internas", "textarea", false, "observacoes internas"));
        List<LocalFormFieldSpec> requestedFields = new ArrayList<>();
        for (LocalFormFieldSpec field : knownFields) {
            if ("prazo".equals(field.name()) && explicitFieldSection.contains("prazo desejado")) {
                continue;
            }
            if (explicitFieldSection.contains(field.promptToken())) {
                requestedFields.add(field);
            }
        }
        requestedFields.sort(Comparator.comparingInt(field -> explicitFieldSection.indexOf(field.promptToken())));
        return requestedFields.size() >= 2 ? List.copyOf(requestedFields) : List.of();
    }

    private String explicitLocalFormFieldsSection(String formSection) {
        int fieldsIndex = formSection.indexOf("campos ");
        if (fieldsIndex < 0) {
            fieldsIndex = formSection.indexOf("campo ");
        }
        if (fieldsIndex < 0) {
            return "";
        }
        int start = fieldsIndex + (formSection.startsWith("campos ", fieldsIndex) ? "campos ".length() : "campo ".length());
        if (formSection.indexOf(" mantendo ", start) >= 0 || formSection.indexOf(" preservando ", start) >= 0) {
            return "";
        }
        int sentenceEnd = formSection.indexOf('.', start);
        int nextInstruction = firstPositive(
                formSection.indexOf(" mantendo ", start),
                formSection.indexOf(" preservando ", start),
                formSection.indexOf(" ao formulario", start),
                formSection.indexOf(" ao formulário", start));
        int end = sentenceEnd >= 0 && nextInstruction >= 0
                ? Math.min(sentenceEnd, nextInstruction)
                : sentenceEnd >= 0 ? sentenceEnd : nextInstruction;
        return end > start ? formSection.substring(start, end) : formSection.substring(start);
    }

    private int firstPositive(int... indexes) {
        int first = -1;
        for (int index : indexes) {
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private void addLocalFormField(ArrayNode fields, String name, String label, String controlType, boolean required) {
        ObjectNode field = fields.addObject();
        field.put("name", name);
        field.put("label", label);
        field.put("controlType", controlType);
        field.put("required", required);
        field.put("binding", "local-transient");
        field.put("submitPolicy", "never");
        if ("prioridade".equals(name)) {
            ArrayNode options = field.putArray("options");
            options.addObject().put("label", "Alta").put("value", "alta");
            options.addObject().put("label", "Media").put("value", "media");
            options.addObject().put("label", "Baixa").put("value", "baixa");
        }
    }

    private void addNestedLocalEditorialList(
            ArrayNode widgets,
            String widgetKey,
            boolean relationshipComments,
            boolean includeCommentStatusLabel,
            boolean includeRelationshipHistory,
            boolean includeSlaSummaryCards) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-list");
        widget.put("childWidgetKey", widgetKey);
        widget.putArray("bindingOrder")
                .add("listId")
                .add("componentInstanceId")
                .add("config")
                .add("configPersistenceStrategy")
                .add("enableCustomization");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("listId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", includeSlaSummaryCards
                ? "Resumo de SLA"
                : relationshipComments ? "Comentarios relacionados" : "Solicitacoes em acompanhamento");
        config.putObject("layout").put("variant", "cards").put("density", "comfortable");
        ObjectNode templating = config.putObject("templating");
        templating.putObject("primary").put("type", "text").put("expr", "${item.title}");
        String statusExpression = includeCommentStatusLabel ? "${item.commentStatus}" : "${item.status}";
        templating.putObject("secondary")
                .put("type", "text")
                .put("expr", includeRelationshipHistory ? "${item.historySummary}" : statusExpression);
        templating.putObject("trailing").put("type", "chip").put("expr", statusExpression);
        templating.put("statusPosition", "top-right");
        ObjectNode dataSource = config.putObject("dataSource");
        ArrayNode data = dataSource.putArray("data");
        if (includeSlaSummaryCards) {
            addLocalSlaSummaryItem(data, "Abertos", "3 solicitações", "Acompanhar entradas ainda sem conclusão", includeRelationshipHistory);
            addLocalSlaSummaryItem(data, "Em risco", "1 solicitação", "Priorizar antes do vencimento do SLA", includeRelationshipHistory);
            addLocalSlaSummaryItem(data, "Resolvidos", "2 solicitações", "Histórico local de encerramentos recentes", includeRelationshipHistory);
        } else if (relationshipComments) {
            addLocalListItem(
                    data,
                    "Solicitação de acesso - comentário de triagem inicial",
                    "Novo",
                    includeCommentStatusLabel,
                    includeRelationshipHistory);
            addLocalListItem(
                    data,
                    "Atualização cadastral - comentário do responsável",
                    "Em análise",
                    includeCommentStatusLabel,
                    includeRelationshipHistory);
            addLocalListItem(
                    data,
                    "Revisão de permissões - comentário de validação",
                    "Resolvido",
                    includeCommentStatusLabel,
                    includeRelationshipHistory);
        } else {
            addLocalListItem(data, "Integração de novo fornecedor", "Em triagem", includeCommentStatusLabel, includeRelationshipHistory);
            addLocalListItem(
                    data,
                    "Revisão LGPD do formulário",
                    "Aguardando revisão",
                    includeCommentStatusLabel,
                    includeRelationshipHistory);
            addLocalListItem(data, "Acesso para liderança", "Pronto para validar", includeCommentStatusLabel, includeRelationshipHistory);
        }
    }

    private void addLocalSlaSummaryItem(
            ArrayNode data,
            String title,
            String status,
            String summary,
            boolean includeRelationshipHistory) {
        ObjectNode item = data.addObject();
        item.put("title", title);
        item.put("name", title);
        item.put("subtitle", summary);
        item.put("status", status);
        if (includeRelationshipHistory) {
            item.put("historySummary", summary + " | Histórico local: atualizado em 2026-05-06");
            ObjectNode history = item.putArray("history").addObject();
            history.put("author", "Sistema editorial");
            history.put("date", "2026-05-06");
            history.put("status", status);
            history.put("comment", "Histórico local relacionado ao item selecionado.");
        }
    }

    private void addLocalListItem(
            ArrayNode data,
            String title,
            String status,
            boolean includeCommentStatusLabel,
            boolean includeRelationshipHistory) {
        ObjectNode item = data.addObject();
        item.put("title", title);
        item.put("name", title);
        item.put("subtitle", includeCommentStatusLabel ? "Status do comentário: " + status : status);
        item.put("status", status);
        if (includeCommentStatusLabel) {
            item.put("commentStatus", "Status do comentário: " + status);
        }
        if (includeRelationshipHistory) {
            item.put("historySummary", "Histórico: Autor: Ana Souza | Data: 2026-05-06 | Status: " + status);
            ObjectNode history = item.putArray("history").addObject();
            history.put("author", "Ana Souza");
            history.put("date", "2026-05-06");
            history.put("status", status);
            history.put("comment", "Comentário local para acompanhar o registro selecionado.");
        }
    }

    private void addNestedLocalEditorialCrud(
            ArrayNode widgets,
            String widgetKey,
            List<LocalCrudColumnSpec> requestedColumns,
            List<LocalCrudActionSpec> requestedActions,
            boolean includeCategory,
            boolean includeSla,
            boolean includeLastUpdate,
            boolean includeDetailsAction,
            int recordCount) {
        boolean hasRequestedColumns = !requestedColumns.isEmpty();
        boolean hasRequestedActions = !requestedActions.isEmpty();
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-crud");
        widget.put("childWidgetKey", widgetKey);
        widget.putObject("outputs").put("rowClick", "emit");
        widget.putArray("bindingOrder")
                .add("crudId")
                .add("componentInstanceId")
                .add("metadata")
                .add("enableCustomization");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("crudId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        ObjectNode metadata = inputs.putObject("metadata");
        metadata.put("component", "praxis-crud");
        metadata.putObject("resource").put("idField", "id");
        metadata.putObject("defaults").put("openMode", "modal");
        ObjectNode table = metadata.putObject("table");
        table.put("title", "Solicitacoes internas");
        ObjectNode toolbar = table.putObject("toolbar");
        toolbar.put("visible", true);
        toolbar.put("position", "top");
        ArrayNode columns = table.putArray("columns");
        if (hasRequestedColumns) {
            requestedColumns.forEach(column -> addLocalCrudColumn(columns, column.field(), column.header()));
        } else {
            addLocalCrudColumn(columns, "titulo", "Título");
            addLocalCrudColumn(columns, "responsavel", "Responsável");
            if (includeCategory) {
                addLocalCrudColumn(columns, "categoria", "Categoria");
            }
            if (includeSla) {
                addLocalCrudColumn(columns, "sla", "SLA");
            } else {
                addLocalCrudColumn(columns, "prioridade", "Prioridade");
            }
            addLocalCrudColumn(columns, "status", "Status");
            if (includeLastUpdate) {
                addLocalCrudColumn(columns, "ultimaAtualizacao", "Última atualização");
            }
        }
        ObjectNode tableActions = table.putObject("actions");
        ObjectNode rowActions = tableActions.putObject("row");
        rowActions.put("enabled", true);
        rowActions.put("position", "end");
        rowActions.put("width", "280px");
        rowActions.put("display", "buttons");
        rowActions.put("trigger", "always");
        rowActions.put("maxVisibleActions", hasRequestedActions
                ? Math.max(1, (int) requestedActions.stream().filter(action -> !action.toolbarAction()).count())
                : includeDetailsAction ? 3 : 2);
        rowActions.putObject("header").put("label", "Ações");
        ArrayNode rowActionItems = rowActions.putArray("actions");
        if (hasRequestedActions) {
            requestedActions.stream()
                    .filter(action -> !action.toolbarAction())
                    .forEach(action -> addLocalCrudRowAction(rowActionItems, action.id(), action.label(), action.icon()));
        } else {
            if (includeDetailsAction) {
                addLocalCrudRowAction(rowActionItems, "details", "Ver detalhes", "visibility");
            }
            addLocalCrudRowAction(rowActionItems, "edit", "Editar", "edit");
            addLocalCrudRowAction(rowActionItems, "delete", "Excluir", "delete");
        }
        ArrayNode actions = metadata.putArray("actions");
        if (hasRequestedActions) {
            requestedActions.forEach(action -> addLocalCrudMetadataAction(actions, action));
        } else {
            actions.addObject().put("action", "create").put("label", "Criar").put("formId", "local-solicitacao-create").put("openMode", "modal");
            actions.addObject().put("action", "edit").put("label", "Editar").put("formId", "local-solicitacao-edit").put("openMode", "modal");
            actions.addObject().put("action", "delete").put("label", "Excluir").put("openMode", "modal");
            if (includeDetailsAction) {
                actions.addObject().put("action", "details").put("label", "Ver detalhes").put("openMode", "side-panel");
            }
        }
        ArrayNode data = metadata.putArray("data");
        addLocalCrudItem(data, 1, "Solicitação de acesso", "Ana Souza", "Acesso", "Alta", "4h", "Em triagem", "2026-05-06 09:15", hasRequestedColumns || includeCategory, hasRequestedColumns || includeSla, hasRequestedColumns || includeLastUpdate, hasRequestedColumns, hasRequestedColumns);
        addLocalCrudItem(data, 2, "Atualização cadastral", "Bruno Lima", "Cadastro", "Média", "1 dia", "Em análise", "2026-05-06 10:30", hasRequestedColumns || includeCategory, hasRequestedColumns || includeSla, hasRequestedColumns || includeLastUpdate, hasRequestedColumns, hasRequestedColumns);
        addLocalCrudItem(data, 3, "Revisão de permissões", "Carla Nunes", "Permissões", "Baixa", "3 dias", "Pronto", "2026-05-05 16:45", hasRequestedColumns || includeCategory, hasRequestedColumns || includeSla, hasRequestedColumns || includeLastUpdate, hasRequestedColumns, hasRequestedColumns);
        if (recordCount >= 4) {
            addLocalCrudItem(data, 4, "Revisão de contrato", "Diego Martins", "Contratos", "Alta", "2h", "Em risco", "2026-05-06 11:20", hasRequestedColumns || includeCategory, hasRequestedColumns || includeSla, hasRequestedColumns || includeLastUpdate, hasRequestedColumns, hasRequestedColumns);
        }
        if (recordCount >= 5) {
            addLocalCrudItem(data, 5, "Instabilidade no acesso", "Marina Costa", "Operações", "Alta", "6h", "Pendente", "2026-05-06 12:05", hasRequestedColumns || includeCategory, hasRequestedColumns || includeSla, hasRequestedColumns || includeLastUpdate, hasRequestedColumns, hasRequestedColumns);
        }
    }

    private void addLocalCrudColumn(ArrayNode columns, String field, String header) {
        ObjectNode column = columns.addObject();
        column.put("field", field);
        column.put("header", header);
    }

    private void addLocalCrudRowAction(ArrayNode actions, String id, String label, String icon) {
        ObjectNode action = actions.addObject();
        action.put("id", id);
        action.put("label", label);
        action.put("icon", icon);
        action.put("action", id);
        action.put("tooltip", label);
    }

    private List<LocalCrudColumnSpec> requestedLocalCrudColumns(String normalizedPrompt) {
        String recordsSection = localRecordsSection(normalizedPrompt);
        String columnsSection = explicitLocalCrudSection(recordsSection, "colunas");
        if (columnsSection.isBlank()) {
            return List.of();
        }
        if (!recordsSection.contains("fila") && !columnsSection.contains("chamado")) {
            return List.of();
        }
        List<LocalCrudColumnSpec> knownColumns = List.of(
                new LocalCrudColumnSpec("titulo", "Chamado", "chamado"),
                new LocalCrudColumnSpec("titulo", "Item", "item"),
                new LocalCrudColumnSpec("area", "Área", "area"),
                new LocalCrudColumnSpec("categoria", "Categoria", "categoria"),
                new LocalCrudColumnSpec("prioridade", "Prioridade", "prioridade"),
                new LocalCrudColumnSpec("status", "Status", "status"),
                new LocalCrudColumnSpec("responsavel", "Responsável", "responsavel"),
                new LocalCrudColumnSpec("sla", "SLA", "sla"),
                new LocalCrudColumnSpec("ultimaAtualizacao", "Atualização", "atualizacao"),
                new LocalCrudColumnSpec("ultimaAtualizacao", "Última atualização", "ultima atualizacao"));
        List<LocalCrudColumnSpec> requestedColumns = new ArrayList<>();
        for (LocalCrudColumnSpec column : knownColumns) {
            if (columnsSection.contains(column.promptToken())
                    && requestedColumns.stream().noneMatch(existing -> existing.field().equals(column.field()))) {
                requestedColumns.add(column);
            }
        }
        requestedColumns.sort(Comparator.comparingInt(column -> columnsSection.indexOf(column.promptToken())));
        return requestedColumns.size() >= 2 ? List.copyOf(requestedColumns) : List.of();
    }

    private List<LocalCrudActionSpec> requestedLocalCrudActions(String normalizedPrompt) {
        String recordsSection = localRecordsSection(normalizedPrompt);
        String actionsSection = explicitLocalCrudSection(recordsSection, "acoes");
        if (actionsSection.isBlank()) {
            actionsSection = explicitLocalCrudSection(recordsSection, "ações");
        }
        if (actionsSection.isBlank()) {
            return List.of();
        }
        if (!recordsSection.contains("fila")) {
            return List.of();
        }
        final String explicitActionsSection = actionsSection;
        List<LocalCrudActionSpec> knownActions = List.of(
                new LocalCrudActionSpec("create", "Criar chamado", "add", true, "criar chamado"),
                new LocalCrudActionSpec("create", "Criar", "add", true, "criar"),
                new LocalCrudActionSpec("assign", "Atribuir", "person_add", false, "atribuir"),
                new LocalCrudActionSpec("close", "Encerrar", "task_alt", false, "encerrar"),
                new LocalCrudActionSpec("details", "Ver detalhes", "visibility", false, "ver detalhes"),
                new LocalCrudActionSpec("edit", "Editar", "edit", false, "editar"),
                new LocalCrudActionSpec("delete", "Excluir", "delete", false, "excluir"));
        List<LocalCrudActionSpec> requestedActions = new ArrayList<>();
        for (LocalCrudActionSpec action : knownActions) {
            if ("create".equals(action.id()) && requestedActions.stream().anyMatch(existing -> "create".equals(existing.id()))) {
                continue;
            }
            if (explicitActionsSection.contains(action.promptToken())) {
                requestedActions.add(action);
            }
        }
        requestedActions.sort(Comparator.comparingInt(action -> explicitActionsSection.indexOf(action.promptToken())));
        return requestedActions.size() >= 2 ? List.copyOf(requestedActions) : List.of();
    }

    private String explicitLocalCrudSection(String recordsSection, String marker) {
        if (recordsSection == null || recordsSection.isBlank()) {
            return "";
        }
        int index = recordsSection.indexOf(marker + " ");
        if (index < 0) {
            return "";
        }
        int start = index + marker.length() + 1;
        int end = recordsSection.indexOf('.', start);
        return end > start ? recordsSection.substring(start, end) : recordsSection.substring(start);
    }

    private void addLocalCrudMetadataAction(ArrayNode actions, LocalCrudActionSpec action) {
        ObjectNode metadataAction = actions.addObject();
        metadataAction.put("action", action.id());
        metadataAction.put("label", action.label());
        metadataAction.put("openMode", "details".equals(action.id()) ? "side-panel" : "modal");
        if ("create".equals(action.id())) {
            metadataAction.put("formId", "local-solicitacao-create");
        } else if ("edit".equals(action.id())) {
            metadataAction.put("formId", "local-solicitacao-edit");
        }
    }

    private void addLocalCrudItem(
            ArrayNode data,
            int id,
            String titulo,
            String responsavel,
            String categoria,
            String prioridade,
            String sla,
            String status,
            String ultimaAtualizacao,
            boolean includeCategory,
            boolean includeSla,
            boolean includeLastUpdate) {
        addLocalCrudItem(data, id, titulo, responsavel, categoria, prioridade, sla, status, ultimaAtualizacao,
                includeCategory, includeSla, includeLastUpdate, false, !includeSla);
    }

    private void addLocalCrudItem(
            ArrayNode data,
            int id,
            String titulo,
            String responsavel,
            String categoria,
            String prioridade,
            String sla,
            String status,
            String ultimaAtualizacao,
            boolean includeCategory,
            boolean includeSla,
            boolean includeLastUpdate,
            boolean includeArea,
            boolean includePriority) {
        ObjectNode item = data.addObject();
        item.put("id", id);
        item.put("titulo", titulo);
        item.put("item", titulo);
        item.put("title", titulo);
        item.put("responsavel", responsavel);
        if (includeArea) {
            item.put("area", categoria);
        }
        if (includePriority) {
            item.put("prioridade", prioridade);
        }
        if (includeCategory) {
            item.put("categoria", categoria);
        }
        if (includeSla) {
            item.put("sla", sla);
        } else {
            item.put("prioridade", prioridade);
        }
        item.put("status", status);
        if (includeLastUpdate) {
            item.put("ultimaAtualizacao", ultimaAtualizacao);
        }
    }

    private boolean shouldModelLocalSelectionRelationship(String prompt) {
        String normalizedPrompt = normalize(prompt);
        return containsAny(normalizedPrompt,
                        "selecionado",
                        "selecionada",
                        "selecao",
                        "seleção",
                        "estado local",
                        "relacionamento entre",
                        "relacao entre",
                        "relação entre")
                && containsAny(normalizedPrompt, "registros", "crud", "lista")
                && containsAny(normalizedPrompt, "acompanhamento", "historico", "histórico", "cards");
    }

    private void addNestedLocalCrudRowClickStateBinding(
            ArrayNode bindings,
            String tabsWidget,
            String sourceWidget,
            String recordsTabId,
            String statePath) {
        ObjectNode binding = baseBinding(
                bindings,
                tabsWidget + "." + sourceWidget + ".rowClick->state." + statePath,
                "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", tabsWidget);
        from.put("port", "rowClick");
        from.put("direction", "output");
        addNestedWidgetPath(from.putArray("nestedPath"), recordsTabId, 1, sourceWidget, "praxis-crud");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", statePath);
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-selected-local-record");
        transform.put("path", "payload.row");
        addMetadata(binding, "local-selection-state");
    }

    private void addNestedLocalSelectedItemTrackingBinding(
            ArrayNode bindings,
            String tabsWidget,
            String targetWidget,
            String trackingTabId,
            String statePath) {
        ObjectNode binding = baseBinding(
                bindings,
                "state." + statePath + "->" + tabsWidget + "." + targetWidget + ".config",
                "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", statePath);
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", tabsWidget);
        to.put("port", "config");
        to.put("direction", "input");
        addNestedWidgetPath(to.putArray("nestedPath"), trackingTabId, 2, targetWidget, "praxis-list");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state." + statePath);
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", "selected-local-record-tracking-config");
        transform.put("inputSource", "payload");
        ObjectNode template = transform.putObject("template");
        template.put("title", "Acompanhamento do registro selecionado");
        template.putObject("layout").put("variant", "cards").put("density", "comfortable");
        ObjectNode templating = template.putObject("templating");
        templating.putObject("primary").put("type", "text").put("expr", "${item.title}");
        templating.putObject("secondary").put("type", "text").put("expr", "${item.historySummary}");
        templating.putObject("trailing").put("type", "chip").put("expr", "${item.status}");
        templating.put("statusPosition", "top-right");
        ObjectNode dataSource = template.putObject("dataSource");
        ArrayNode data = dataSource.putArray("data");
        ObjectNode selected = data.addObject();
        selected.put("title", "Item selecionado: ${payload.titulo}");
        selected.put("status", "${payload.status}");
        selected.put("historySummary", "Autor: ${payload.responsavel} | Data: ${payload.ultimaAtualizacao} | SLA: ${payload.sla}");
        ObjectNode sla = data.addObject();
        sla.put("title", "SLA relacionado");
        sla.put("status", "${payload.sla}");
        sla.put("historySummary", "Categoria: ${payload.categoria} | Status atual: ${payload.status}");
        ObjectNode comment = data.addObject();
        comment.put("title", "Histórico local");
        comment.put("status", "Relacionado");
        comment.put("historySummary", "Comentário: acompanhamento local derivado da seleção de ${payload.titulo}.");
        addMetadata(binding, "local-tracking-context");
    }

    private AgenticAuthoringCandidate canonicalSelectedResourceCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String resourcePath = valueOrEmpty(candidate.resourcePath());
        if (resourcePath.endsWith("/vw-resumo-missoes")) {
            return new AgenticAuthoringCandidate(
                    MISSION_SUMMARY,
                    valueOrEmpty(candidate.operation()).isBlank() ? "post" : candidate.operation(),
                    "/schemas/filtered?path=" + MISSION_SUMMARY + "/filter/cursor&operation=post&schemaType=response",
                    MISSION_SUMMARY + "/filter/cursor",
                    "post",
                    candidate.score(),
                    valueOrEmpty(candidate.reason()).isBlank()
                            ? "resource normalized to canonical operations mission summary endpoint"
                            : candidate.reason(),
                    candidate.evidence());
        }
        return candidate;
    }

    private ObjectNode selectedResourceDashboardPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        String widgetKey = resourceWidgetKey(candidate.resourcePath());
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "resource-dashboard");
        addSourceRefs(plan, request, candidate);

        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "96px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        canvas.put("collisionPolicy", "block");
        ObjectNode items = canvas.putObject("items");
        addCanvasItem(items, widgetKey, 1, 1, 12, 6);
        addCanvasItem(items, widgetKey + "-summary", 1, 7, 12, 2);

        ArrayNode widgets = plan.putArray("widgets");
        addSelectedResourceTable(widgets, candidate, widgetKey);
        addSelectedResourceSummary(widgets, candidate, widgetKey + "-summary");
        plan.putArray("bindings");
        return plan;
    }

    private ObjectNode selectedResourceMasterDetailPlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate,
            boolean includeSearchFilter) {
        String widgetKey = resourceWidgetKey(candidate.resourcePath()).replace("-table", "");
        String filterKey = widgetKey + "-filter";
        String masterKey = widgetKey + "-master";
        String detailKey = widgetKey + "-detail";
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", includeSearchFilter ? "resource-search-master-detail" : "resource-master-detail");
        addSourceRefs(plan, request, candidate);

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull("selectedItem");

        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "96px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        canvas.put("collisionPolicy", "block");
        ObjectNode items = canvas.putObject("items");
        if (includeSearchFilter) {
            addCanvasItem(items, filterKey, 1, 1, 12, 2);
            addCanvasItem(items, masterKey, 1, 3, 7, 6);
            addCanvasItem(items, detailKey, 8, 3, 5, 6);
        } else {
            addCanvasItem(items, masterKey, 1, 1, 7, 7);
            addCanvasItem(items, detailKey, 8, 1, 5, 7);
        }

        ArrayNode widgets = plan.putArray("widgets");
        if (includeSearchFilter) {
            addSelectedResourceFilter(widgets, candidate, filterKey);
        }
        addSelectedResourceTable(widgets, candidate, masterKey);
        addSelectedResourceDetail(widgets, candidate, detailKey);

        ArrayNode bindings = plan.putArray("bindings");
        if (includeSearchFilter) {
            addFilterSearchQueryContextBinding(bindings, filterKey, masterKey);
        }
        addTableRowClickStateBinding(bindings, masterKey);
        addSelectedResourceDetailBinding(bindings, detailKey);
        return plan;
    }

    private ObjectNode selectedResourceTabbedWorkspacePlan(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        String widgetKey = resourceWidgetKey(candidate.resourcePath()).replace("-table", "");
        String tabsKey = widgetKey + "-workspace";
        String filterKey = widgetKey + "-filter";
        String masterKey = widgetKey + "-master";
        String detailKey = widgetKey + "-detail";
        String formKey = widgetKey + "-form";
        boolean includeSearchFilter = shouldIncludeSearchFilter(request.userPrompt());
        boolean useSummaryList = shouldUseSummaryList(request.userPrompt())
                || (currentPageReferencesComponent(request.currentPage(), "praxis-list") && !includeSearchFilter);
        boolean useCrudWorkspace = shouldUseCrudWorkspace(request.userPrompt())
                || currentPageReferencesComponent(request.currentPage(), "praxis-crud");

        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", useSummaryList || useCrudWorkspace
                ? "resource-tabbed-operational-workspace"
                : "resource-tabbed-master-detail-form");
        addSourceRefs(plan, request, candidate);

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull("selectedItem");

        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "96px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        canvas.put("collisionPolicy", "block");
        addCanvasItem(canvas.putObject("items"), tabsKey, 1, 1, 12, 8);

        ArrayNode widgets = plan.putArray("widgets");
        addSelectedResourceTabs(widgets, candidate, tabsKey, filterKey, masterKey, detailKey, formKey,
                useSummaryList, useCrudWorkspace);

        ArrayNode bindings = plan.putArray("bindings");
        if (!useSummaryList) {
            addNestedFilterSearchQueryContextBinding(bindings, tabsKey, filterKey, masterKey);
            addNestedTableRowClickStateBinding(bindings, tabsKey, masterKey);
        } else {
            addNestedListSelectionChangeStateBinding(bindings, tabsKey, masterKey);
        }
        addNestedSelectedResourceDetailBinding(bindings, tabsKey, detailKey);
        if (!useCrudWorkspace) {
            addNestedSelectedResourceFormInitialValueBinding(bindings, tabsKey, formKey);
        }
        return plan;
    }

    private boolean shouldUseSummaryList(String prompt) {
        String normalized = normalize(prompt == null ? "" : prompt);
        return containsAny(normalized,
                "lista resumida",
                "lista amigavel",
                "lista simples",
                "cards",
                "cartoes",
                "resumo em lista",
                "listagem resumida");
    }

    private boolean shouldUseCrudWorkspace(String prompt) {
        String normalized = normalize(prompt == null ? "" : prompt);
        if (containsAny(normalized,
                "crud",
                "manutencao completa",
                "manutenção completa",
                "criar e editar",
                "criar ou editar",
                "incluir e editar",
                "cadastrar e editar")) {
            return true;
        }
        return containsAny(normalized, "registros", "tabela", "table")
                && containsAny(normalized,
                "coluna", "colunas", "acoes", "ações", "criar", "editar", "excluir", "sla", "categoria");
    }

    private boolean shouldIncludeSearchFilter(String prompt) {
        String normalized = normalize(prompt == null ? "" : prompt);
        return containsAny(normalized,
                "buscar", "busca", "procurar", "pesquisar", "encontrar", "localizar", "filtrar", "filtro", "consulta", "consultar")
                && containsAny(normalized, "detalhe", "detalhes", "dados", "informacoes", "resultado", "resultados");
    }

    private void addSourceRefs(
            ObjectNode plan,
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringCandidate candidate) {
        ArrayNode sourceRefs = plan.putArray("sourceRefs");
        addSourceRef(sourceRefs, "intent-resolution");
        if (candidate != null) {
            addSourceRef(sourceRefs, candidate.schemaUrl());
        }
        addProjectKnowledgeSourceRefs(sourceRefs, request);
    }

    private void addProjectKnowledgeSourceRefs(ArrayNode sourceRefs, AgenticAuthoringPlanRequest request) {
        JsonNode entries = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("projectKnowledge").path("entries");
        if (entries == null || !entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                continue;
            }
            String knowledgeId = valueOrEmpty(entry.path("knowledgeId").asText(""));
            String conceptKey = valueOrEmpty(entry.path("conceptKey").asText(""));
            addSourceRef(sourceRefs, "projectKnowledge:" + (knowledgeId.isBlank() ? conceptKey : knowledgeId));
        }
    }

    private void addSourceRef(ArrayNode sourceRefs, String sourceRef) {
        String normalized = valueOrEmpty(sourceRef);
        if (normalized.isBlank()) {
            return;
        }
        for (JsonNode existing : sourceRefs) {
            if (normalized.equals(existing.asText())) {
                return;
            }
        }
        sourceRefs.add(normalized);
    }

    private void addSelectedResourceFilter(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-filter");
        widget.put("role", "filter");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("filterId", widgetKey);
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        inputs.put("showFilterSettings", true);
        inputs.put("persistenceKey", widgetKey);
        inputs.put("changeDebounceMs", 300);
    }

    private void addSelectedResourceTabs(
            ArrayNode widgets,
            AgenticAuthoringCandidate candidate,
            String widgetKey,
            String filterKey,
            String masterKey,
            String detailKey,
            String formKey,
            boolean useSummaryList,
            boolean useCrudWorkspace) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-tabs");
        widget.put("role", "workspace");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("tabsId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.putObject("group").put("dynamicHeight", true).put("preserveContent", true);
        ArrayNode tabs = config.putArray("tabs");

        ObjectNode searchTab = tabs.addObject();
        searchTab.put("id", "search");
        searchTab.put("textLabel", useSummaryList ? "Lista resumida" : "Buscar e listar");
        ArrayNode searchWidgets = searchTab.putArray("widgets");
        if (useSummaryList) {
            addNestedSelectedResourceList(searchWidgets, candidate, masterKey);
        } else {
            addNestedSelectedResourceFilter(searchWidgets, candidate, filterKey);
            addNestedSelectedResourceTable(searchWidgets, candidate, masterKey);
        }

        ObjectNode detailTab = tabs.addObject();
        detailTab.put("id", "details");
        detailTab.put("textLabel", "Detalhes");
        addNestedSelectedResourceDetail(detailTab.putArray("widgets"), candidate, detailKey);

        ObjectNode formTab = tabs.addObject();
        formTab.put("id", "form");
        formTab.put("textLabel", useCrudWorkspace ? "CRUD" : "Formulario guiado");
        if (useCrudWorkspace) {
            addNestedSelectedResourceCrud(formTab.putArray("widgets"), candidate, formKey);
        } else {
            addNestedSelectedResourceForm(formTab.putArray("widgets"), candidate, formKey);
        }
    }

    private void addNestedSelectedResourceList(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-list");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("selectionChange", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("listId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", titleFromResourcePath(candidate.resourcePath()));
        config.putObject("dataSource").put("resourcePath", candidate.resourcePath());
        ObjectNode selection = config.putObject("selection");
        selection.put("mode", "single");
    }

    private void addNestedSelectedResourceFilter(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-filter");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("requestSearch", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("filterId", widgetKey);
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        inputs.put("showFilterSettings", true);
        inputs.put("persistenceKey", widgetKey);
        inputs.put("changeDebounceMs", 300);
    }

    private void addNestedSelectedResourceTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-table");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode outputs = widget.putObject("outputs");
        outputs.put("rowClick", "emit");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("tableId", widgetKey);
        inputs.put("title", titleFromResourcePath(candidate.resourcePath()));
        inputs.putObject("config").putArray("columns");
    }

    private void addNestedSelectedResourceDetail(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-dynamic-form");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        inputs.putNull("resourceId");
        inputs.putObject("config").put("title", "Detalhes de " + titleFromResourcePath(candidate.resourcePath()));
    }

    private void addNestedSelectedResourceForm(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-dynamic-form");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("mode", "edit");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        inputs.putObject("config").put("title", "Editar dados principais");
    }

    private void addNestedSelectedResourceCrud(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("id", "praxis-crud");
        widget.put("childWidgetKey", widgetKey);
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("crudId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("enableCustomization", true);
        ObjectNode metadata = inputs.putObject("metadata");
        metadata.put("component", "praxis-crud");
        metadata.putObject("resource").put("path", candidate.resourcePath());
        metadata.put("title", "Manutencao de " + titleFromResourcePath(candidate.resourcePath()));
    }

    private void addFilterSearchQueryContextBinding(ArrayNode bindings, String sourceWidget, String targetWidget) {
        ObjectNode binding = baseBinding(bindings, sourceWidget + ".requestSearch->" + targetWidget + ".queryContext", "component-port");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", sourceWidget);
        from.put("port", "requestSearch");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", targetWidget);
        to.put("port", "queryContext");
        to.put("direction", "input");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "filter-request-to-query-context");
        transform.put("inputSource", "payload");
        transform.put("path", "payload");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, "filter-to-table");
    }

    private void addNestedFilterSearchQueryContextBinding(
            ArrayNode bindings,
            String tabsWidget,
            String sourceWidget,
            String targetWidget) {
        ObjectNode binding = baseBinding(
                bindings,
                tabsWidget + "." + sourceWidget + ".requestSearch->" + targetWidget + ".queryContext",
                "component-port");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", tabsWidget);
        from.put("port", "requestSearch");
        from.put("direction", "output");
        addNestedWidgetPath(from.putArray("nestedPath"), "search", 0, sourceWidget, "praxis-filter");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", tabsWidget);
        to.put("port", "queryContext");
        to.put("direction", "input");
        addNestedWidgetPath(to.putArray("nestedPath"), "search", 0, targetWidget, "praxis-table");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "filter-request-to-query-context");
        transform.put("inputSource", "payload");
        transform.put("path", "payload");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, "filter-to-table");
    }

    private void addSelectedResourceTable(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-table");
        widget.put("role", "main");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("tableId", widgetKey);
        inputs.put("title", titleFromResourcePath(candidate.resourcePath()));
        inputs.putObject("config").putArray("columns");
    }

    private void addSelectedResourceSummary(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "supporting");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Dashboard criado para " + titleFromResourcePath(candidate.resourcePath()) + ".");
    }

    private void addSelectedResourceDetail(ArrayNode widgets, AgenticAuthoringCandidate candidate, String widgetKey) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", widgetKey);
        widget.put("componentId", "praxis-dynamic-form");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", candidate.resourcePath());
        inputs.put("formId", widgetKey);
        inputs.put("componentInstanceId", widgetKey);
        inputs.put("mode", "view");
        inputs.put("schemaSource", "resource");
        inputs.put("enableCustomization", true);
        inputs.putNull("resourceId");
        inputs.putObject("config").put("title", "Detalhes de " + titleFromResourcePath(candidate.resourcePath()));
    }

    private void addSelectedResourceDetailBinding(ArrayNode bindings, String targetWidget) {
        ObjectNode binding = baseBinding(bindings, "state.selectedItem->" + targetWidget + ".resourceId", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedItem");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", targetWidget);
        to.put("port", "resourceId");
        to.put("direction", "input");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedItem");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "selected-item-resource-id");
        transform.put("inputSource", "payload");
        transform.put("path", "id");
        addMetadata(binding, "detail-resource-id");
    }

    private void addNestedSelectedResourceDetailBinding(ArrayNode bindings, String tabsWidget, String targetWidget) {
        ObjectNode binding = baseBinding(bindings, "state.selectedItem->" + tabsWidget + "." + targetWidget + ".resourceId", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedItem");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", tabsWidget);
        to.put("port", "resourceId");
        to.put("direction", "input");
        addNestedWidgetPath(to.putArray("nestedPath"), "details", 1, targetWidget, "praxis-dynamic-form");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedItem");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "selected-item-resource-id");
        transform.put("inputSource", "payload");
        transform.put("path", "id");
        addMetadata(binding, "detail-resource-id");
    }

    private void addNestedSelectedResourceFormInitialValueBinding(ArrayNode bindings, String tabsWidget, String targetWidget) {
        ObjectNode binding = baseBinding(bindings, "state.selectedItem->" + tabsWidget + "." + targetWidget + ".initialValue", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedItem");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", tabsWidget);
        to.put("port", "initialValue");
        to.put("direction", "input");
        addNestedWidgetPath(to.putArray("nestedPath"), "form", 2, targetWidget, "praxis-dynamic-form");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedItem");
        addMetadata(binding, "form-initial-value");
    }

    private void addTableRowClickStateBinding(ArrayNode bindings, String sourceWidget) {
        ObjectNode binding = baseBinding(bindings, sourceWidget + ".rowClick->state.selectedItem", "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", sourceWidget);
        from.put("port", "rowClick");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", "selectedItem");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-selected-row");
        transform.put("path", "payload.row");
        addMetadata(binding, "state");
    }

    private void addNestedTableRowClickStateBinding(ArrayNode bindings, String tabsWidget, String sourceWidget) {
        ObjectNode binding = baseBinding(bindings, tabsWidget + "." + sourceWidget + ".rowClick->state.selectedItem", "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", tabsWidget);
        from.put("port", "rowClick");
        from.put("direction", "output");
        addNestedWidgetPath(from.putArray("nestedPath"), "search", 0, sourceWidget, "praxis-table");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", "selectedItem");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-selected-row");
        transform.put("path", "payload.row");
        addMetadata(binding, "state");
    }

    private void addNestedListSelectionChangeStateBinding(ArrayNode bindings, String tabsWidget, String sourceWidget) {
        ObjectNode binding = baseBinding(bindings, tabsWidget + "." + sourceWidget + ".selectionChange->state.selectedItem", "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", tabsWidget);
        from.put("port", "selectionChange");
        from.put("direction", "output");
        addNestedWidgetPath(from.putArray("nestedPath"), "search", 0, sourceWidget, "praxis-list");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", "selectedItem");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-selected-list-value");
        transform.put("path", "payload.value");
        addMetadata(binding, "state");
    }

    private void addNestedWidgetPath(
            ArrayNode nestedPath,
            String tabId,
            int tabIndex,
            String widgetKey,
            String componentType) {
        ObjectNode tab = nestedPath.addObject();
        tab.put("kind", "tab");
        tab.put("id", tabId);
        tab.put("index", tabIndex);
        ObjectNode widget = nestedPath.addObject();
        widget.put("kind", "widget");
        widget.put("key", widgetKey);
        widget.put("componentType", componentType);
    }

    private String resourceWidgetKey(String resourcePath) {
        String normalized = normalize(resourcePath == null ? "resource" : resourcePath)
                .replace("/api/", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "resource-dashboard-table" : normalized + "-table";
    }

    private String titleFromResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "Dashboard";
        }
        String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1)
                .replace("vw-", "")
                .replace('-', ' ')
                .trim();
        if (lastSegment.isBlank()) {
            return "Dashboard";
        }
        return Character.toUpperCase(lastSegment.charAt(0)) + lastSegment.substring(1);
    }

    private String nextAvailableWidgetKey(ObjectNode page, String baseKey) {
        if (findWidget(page, baseKey) == null) {
            return baseKey;
        }
        int suffix = 2;
        while (findWidget(page, baseKey + "-" + suffix) != null) {
            suffix++;
        }
        return baseKey + "-" + suffix;
    }

    private void addExecutiveSummaryPageWidget(ObjectNode page, String key) {
        ArrayNode widgets = page.withArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", key);
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-rich-content");
        ObjectNode document = definition.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Resumo executivo: total de salarios, total de descontos e media salarial por departamento.");
    }

    private void addCanvasItemForAppendedWidget(ObjectNode page, String key, int col, int row, int colSpan, int rowSpan) {
        JsonNode items = page.path("canvas").path("items");
        if (!items.isObject()) {
            return;
        }
        addCanvasItem((ObjectNode) items, key, col, row, colSpan, rowSpan);
    }

    private int nextCanvasRow(ObjectNode page) {
        JsonNode items = page.path("canvas").path("items");
        if (!items.isObject()) {
            return 1;
        }
        int row = 1;
        for (JsonNode item : items) {
            int itemEnd = item.path("row").asInt(1) + item.path("rowSpan").asInt(1);
            row = Math.max(row, itemEnd);
        }
        return row;
    }

    private ObjectNode findWidget(ObjectNode page, String widgetKey) {
        if (widgetKey == null || widgetKey.isBlank()) {
            return null;
        }
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray()) {
            return null;
        }
        for (JsonNode widget : widgets) {
            if (widget.isObject() && widgetKey.equals(widget.path("key").asText())) {
                return (ObjectNode) widget;
            }
        }
        return null;
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> chartModification(AgenticAuthoringPlanRequest request) {
        if (!supportsChartModification(request)) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        ObjectNode chartWidget = findWidget(page, request.intentResolution().target().widgetKey());
        if (chartWidget == null || !"praxis-chart".equals(chartWidget.path("definition").path("id").asText())) {
            return Optional.empty();
        }
        ObjectNode config = chartWidget.with("definition").with("inputs").with("config");
        String prompt = normalize(request.userPrompt());
        String changeKind = request.intentResolution().changeKind();
        boolean changed = switch (changeKind) {
            case "set_chart_type" -> applyChartType(config, prompt);
            case "set_chart_metric" -> applyChartMetric(config, prompt);
            case "set_chart_dimension" -> applyChartDimension(config, prompt);
            case "set_chart_value_format" -> applyChartValueFormat(config, prompt);
            case "enable_chart_drilldown" -> applyChartDrillDown(config);
            default -> false;
        };
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-chart-modification"),
                null,
                compiledPagePatch(page, "modify-existing-chart")));
    }

    private boolean supportsChartModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        return "modify".equals(request.intentResolution().operationKind())
                && "dashboard".equals(request.intentResolution().artifactKind())
                && "praxis-chart".equals(request.intentResolution().target().componentId())
                && (chartCapabilityCatalog.supports(request.intentResolution().changeKind(), prompt)
                || isKnownChartChangeKind(request.intentResolution().changeKind()));
    }

    private boolean applyChartType(ObjectNode config, String prompt) {
        String type = chartCapabilityCatalog.resolveField("set_chart_type", prompt).orElse("");
        if (type.isBlank()) {
            return false;
        }
        config.put("type", type);
        ArrayNode series = config.withArray("series");
        if (!series.isEmpty() && series.get(0).isObject()) {
            ((ObjectNode) series.get(0)).put("type", type);
        }
        return true;
    }

    private boolean applyChartMetric(ObjectNode config, String prompt) {
        String field = chartCapabilityCatalog.resolveField("set_chart_metric", prompt)
                .orElseGet(() -> resolveKnownColumnField(prompt));
        if (field.isBlank()) {
            return false;
        }
        String dimension = firstText(config.path("dataSource").path("query").path("dimensions"), config.path("axes").path("x").path("field").asText("departamento"));
        config.with("axes").with("y").put("field", field);
        ObjectNode series = firstSeries(config);
        series.with("metric").put("field", field);
        series.with("metric").put("aggregation", "sum");
        configureGroupByStatsQuery(config, dimension, field);
        return true;
    }

    private boolean applyChartDimension(ObjectNode config, String prompt) {
        String field = chartCapabilityCatalog.resolveField("set_chart_dimension", prompt)
                .orElseGet(() -> resolveKnownColumnField(prompt));
        if (field.isBlank()) {
            return false;
        }
        config.with("axes").with("x").put("field", field);
        firstSeries(config).put("categoryField", field);
        String metricField = firstSeries(config).path("metric").path("field").asText("salarioLiquido");
        configureGroupByStatsQuery(config, field, metricField);
        return true;
    }

    private boolean applyChartValueFormat(ObjectNode config, String prompt) {
        String format = chartCapabilityCatalog.resolveField("set_chart_value_format", prompt).orElse("");
        if (format.isBlank()) {
            return false;
        }
        config.with("axes").with("y").with("labels").put("format", format);
        firstSeries(config).with("labels").put("format", format);
        return true;
    }

    private boolean applyChartDrillDown(ObjectNode config) {
        ObjectNode interactions = config.with("interactions");
        interactions.put("selection", true);
        interactions.put("crossFilter", true);
        ObjectNode eventActions = interactions.with("eventActions");
        ObjectNode selection = eventActions.with("selectionChange");
        selection.put("action", "emit");
        if (selection.path("mapping").isMissingNode()) {
            selection.putObject("mapping");
        }
        return true;
    }

    private ObjectNode firstSeries(ObjectNode config) {
        ArrayNode series = config.withArray("series");
        if (series.isEmpty() || !series.get(0).isObject()) {
            ObjectNode created = series.addObject();
            created.put("id", "series-1");
            return created;
        }
        return (ObjectNode) series.get(0);
    }

    private ObjectNode firstQueryMetric(ObjectNode config) {
        ArrayNode metrics = config.with("dataSource").with("query").withArray("metrics");
        if (metrics.isEmpty() || !metrics.get(0).isObject()) {
            return metrics.addObject();
        }
        return (ObjectNode) metrics.get(0);
    }

    private boolean supports(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        boolean asksForPageComposition = containsAny(prompt,
                "master detail",
                "master-detail",
                "mestre detalhe",
                "mestre-detalhe",
                "dashboard",
                "painel");
        boolean referencesDepartments = containsAny(prompt, "departamento", "departamentos", "department", "departments");
        boolean referencesEmployees = containsAny(prompt, "funcionario", "funcionarios", "colaborador", "colaboradores", "employee", "employees");
        return asksForPageComposition && referencesDepartments && referencesEmployees;
    }

    private ObjectNode chartDrillDownPlan() {
        return chartDrillDownPlan(DEPARTMENT_BREAKDOWN);
    }

    private ObjectNode chartDrillDownPlan(PayrollBreakdown breakdown) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "chart-drilldown-dashboard");

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull(breakdown.statePath());
        addPayrollDrillDownCanvas(plan, breakdown);

        ArrayNode widgets = plan.putArray("widgets");
        addPayrollDrillDownChart(widgets, breakdown);
        addPayrollDrillDownList(widgets, breakdown);
        addPayrollDrillDownSummary(widgets, breakdown);

        ArrayNode bindings = plan.putArray("bindings");
        addChartSelectionStateBinding(bindings, breakdown);
        addChartStateQueryContextBinding(bindings, breakdown);
        addChartStateSummaryBinding(bindings, breakdown);
        return plan;
    }

    private void addPayrollDrillDownCanvas(ObjectNode plan) {
        addPayrollDrillDownCanvas(plan, DEPARTMENT_BREAKDOWN);
    }

    private void addPayrollDrillDownCanvas(ObjectNode plan, PayrollBreakdown breakdown) {
        ObjectNode canvas = plan.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.put("rowUnit", "96px");
        canvas.put("gap", "16px");
        canvas.put("autoRows", "fixed");
        canvas.put("collisionPolicy", "block");
        ObjectNode items = canvas.putObject("items");
        addCanvasItem(items, breakdown.chartKey(), 1, 1, 12, 4);
        addCanvasItem(items, PAYROLL_DRILLDOWN_DETAIL_KEY, 1, 5, 8, 7);
        addCanvasItem(items, "payroll-drilldown-summary", 9, 5, 4, 2);
    }

    private void addCanvasItem(ObjectNode items, String key, int col, int row, int colSpan, int rowSpan) {
        ObjectNode item = items.putObject(key);
        item.put("col", col);
        item.put("row", row);
        item.put("colSpan", colSpan);
        item.put("rowSpan", rowSpan);
    }

    private ObjectNode masterDetailPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "master-detail-dashboard");

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull("selectedDepartmentId");
        values.putNull("selectedDepartmentName");

        ArrayNode widgets = plan.putArray("widgets");
        addDepartmentMaster(widgets);
        addEmployeesDetail(widgets);
        addPayrollChart(widgets);
        addDepartmentSummary(widgets);

        ArrayNode bindings = plan.putArray("bindings");
        addPickStateBinding(bindings, "department-master.itemSelect->state.selectedDepartmentId",
                "selectedDepartmentId", "pick-department-id", "payload.item.id");
        addPickStateBinding(bindings, "department-master.itemSelect->state.selectedDepartmentName",
                "selectedDepartmentName", "pick-department-name", "payload.item.nome");
        addQueryContextBinding(bindings, "state.selectedDepartmentId->department-employees.queryContext",
                "department-employees", "department-filter", "departamentoId", "table-filter");
        addQueryContextBinding(bindings, "state.selectedDepartmentId->department-payroll.queryContext",
                "department-payroll", "department-payroll-filter", "departamentoId", "chart-filter");
        addSummaryDocumentBinding(bindings);
        return plan;
    }

    private ObjectNode payrollTablePlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "single-table-page");

        ArrayNode widgets = plan.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-table");
        widget.put("componentId", "praxis-table");
        widget.put("role", "main");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", PAYROLL);
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");
        addPayrollTableColumns(inputs.putObject("config").putArray("columns"));

        plan.putArray("bindings");
        return plan;
    }

    private void addPayrollTableColumns(ArrayNode columns) {
        addTableColumn(columns, "id", "ID", "number");
        addTableColumn(columns, "ano", "Ano", "number");
        addTableColumn(columns, "mes", "Mes", "number");
        addTableColumn(columns, "salarioBruto", "Salario bruto", "number");
        addTableColumn(columns, "totalDescontos", "Total descontos", "number");
        addTableColumn(columns, "salarioLiquido", "Salario liquido", "number");
        addTableColumn(columns, "dataPagamento", "Data de pagamento", "date");
        addTableColumn(columns, "funcionarioId", "Funcionario", "number");
    }

    private void addPayrollAnalyticsTableColumns(ArrayNode columns) {
        addTableColumn(columns, "nomeCompleto", "Nome completo", "text");
        addTableColumn(columns, "universo", "Universo", "text");
        addTableColumn(columns, "cargo", "Cargo", "text");
        addTableColumn(columns, "codinome", "Codinome", "text");
        addTableColumn(columns, "exposicaoPublica", "Exposicao publica", "boolean");
        addTableColumn(columns, "departamento", "Departamento", "text");
        addTableColumn(columns, "equipe", "Equipe", "text");
        addTableColumn(columns, "base", "Base", "text");
        addTableColumn(columns, "ano", "Ano", "number");
        addTableColumn(columns, "mes", "Mes", "number");
        addTableColumn(columns, "competencia", "Competencia", "text");
        addTableColumn(columns, "dataPagamento", "Data pagamento", "date");
        addTableColumn(columns, "salarioBruto", "Salario bruto", "number");
        addTableColumn(columns, "totalDescontos", "Total descontos", "number");
        addTableColumn(columns, "salarioLiquido", "Salario liquido", "number");
        addTableColumn(columns, "payrollProfile", "Perfil folha", "text");
        addTableColumn(columns, "composicaoFolha", "Composicao folha", "text");
    }

    private void addTableColumn(ArrayNode columns, String field, String header, String type) {
        ObjectNode column = columns.addObject();
        column.put("field", field);
        column.put("header", header);
        column.put("type", type);
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnFormatModification(AgenticAuthoringPlanRequest request) {
        if (!supportsTableColumnFormatModification(request)) {
            return Optional.empty();
        }
        String field = resolveFormatField(request.userPrompt());
        if (field.isBlank()) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        ObjectNode tableWidget = findWidget(page, request.intentResolution().target().widgetKey());
        if (tableWidget == null || !"praxis-table".equals(tableWidget.path("definition").path("id").asText())) {
            return Optional.empty();
        }
        ObjectNode inputs = tableWidget.with("definition").with("inputs");
        ObjectNode config = inputs.with("config");
        ArrayNode columns = config.withArray("columns");
        materializeKnownTableColumnsIfEmpty(inputs, columns);
        ObjectNode column = findColumn(columns, field);
        if (column == null) {
            return Optional.empty();
        }
        column.put("format", "BRL|symbol|2");
        column.put("type", "currency");
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-table-column-format"),
                null,
                compiledPagePatch(page, "modify-existing-table")));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnVisibilityModification(AgenticAuthoringPlanRequest request) {
        if (!supportsTableColumnVisibilityModification(request)) {
            return Optional.empty();
        }
        String field = resolveVisibilityField(request.userPrompt());
        if (field.isBlank()) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        ObjectNode tableWidget = findWidget(page, request.intentResolution().target().widgetKey());
        if (tableWidget == null || !"praxis-table".equals(tableWidget.path("definition").path("id").asText())) {
            return Optional.empty();
        }
        ObjectNode inputs = tableWidget.with("definition").with("inputs");
        ArrayNode columns = inputs.with("config").withArray("columns");
        materializeKnownTableColumnsIfEmpty(inputs, columns);
        ObjectNode column = findColumn(columns, field);
        if (column == null) {
            return Optional.empty();
        }
        column.put("visible", false);
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-table-column-visibility"),
                null,
                compiledPagePatch(page, "modify-existing-table")));
    }

    private Optional<AgenticAuthoringUiCompositionPlanResult> tableColumnOrderModification(AgenticAuthoringPlanRequest request) {
        if (!supportsTableColumnOrderModification(request)) {
            return Optional.empty();
        }
        String field = resolveOrderField(request.userPrompt());
        if (field.isBlank()) {
            return Optional.empty();
        }
        ObjectNode page = request.currentPage().deepCopy();
        ObjectNode tableWidget = findWidget(page, request.intentResolution().target().widgetKey());
        if (tableWidget == null || !"praxis-table".equals(tableWidget.path("definition").path("id").asText())) {
            return Optional.empty();
        }
        ObjectNode inputs = tableWidget.with("definition").with("inputs");
        ArrayNode columns = inputs.with("config").withArray("columns");
        materializeKnownTableColumnsIfEmpty(inputs, columns);
        ObjectNode target = findColumn(columns, field);
        if (target == null) {
            return Optional.empty();
        }
        int order = 0;
        target.put("order", order++);
        for (JsonNode column : columns) {
            if (column.isObject() && column != target) {
                ((ObjectNode) column).put("order", order++);
            }
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-table-column-order"),
                null,
                compiledPagePatch(page, "modify-existing-table")));
    }

    private boolean supportsTableColumnFormatModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "set_column_format".equals(request.intentResolution().changeKind());
    }

    private boolean supportsTableColumnVisibilityModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "set_column_visibility".equals(request.intentResolution().changeKind());
    }

    private boolean supportsTableColumnOrderModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "set_column_order".equals(request.intentResolution().changeKind());
    }

    private String resolveFormatField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_format",
                normalize(prompt == null ? "" : prompt))
                .orElseGet(() -> resolveKnownColumnField(prompt));
    }

    private String resolveVisibilityField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_visibility",
                normalize(prompt == null ? "" : prompt))
                .orElseGet(() -> resolveKnownColumnField(prompt));
    }

    private String resolveOrderField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_order",
                normalize(prompt == null ? "" : prompt))
                .orElseGet(() -> resolveKnownColumnField(prompt));
    }

    private boolean isKnownChartChangeKind(String changeKind) {
        return "set_chart_type".equals(changeKind)
                || "set_chart_metric".equals(changeKind)
                || "set_chart_dimension".equals(changeKind)
                || "set_chart_value_format".equals(changeKind)
                || "enable_chart_drilldown".equals(changeKind);
    }

    private String resolveKnownColumnField(String prompt) {
        String normalized = normalize(prompt == null ? "" : prompt);
        if (containsAny(normalized, "salario liquido", "liquido")) {
            return "salarioLiquido";
        }
        if (containsAny(normalized, "salario bruto", "bruto")) {
            return "salarioBruto";
        }
        if (containsAny(normalized, "total descontos", "descontos", "desconto")) {
            return "totalDescontos";
        }
        if (containsAny(normalized, "competencia", "mes")) {
            return "competencia";
        }
        if (containsAny(normalized, "departamento", "area", "setor")) {
            return "departamento";
        }
        if (containsAny(normalized, "cargo", "funcao")) {
            return "cargo";
        }
        if (containsAny(normalized, "nome completo", "nome")) {
            return "nomeCompleto";
        }
        if (containsAny(normalized, "id", "identificador")) {
            return "id";
        }
        return "";
    }

    private ObjectNode findColumn(ArrayNode columns, String field) {
        for (JsonNode column : columns) {
            if (column.isObject() && field.equals(column.path("field").asText())) {
                return (ObjectNode) column;
            }
        }
        return null;
    }

    private void materializeKnownTableColumnsIfEmpty(ObjectNode inputs, ArrayNode columns) {
        if (!columns.isEmpty()) {
            return;
        }
        String resourcePath = inputs.path("resourcePath").asText("");
        if (PAYROLL_ANALYTICS.equals(resourcePath)) {
            addPayrollAnalyticsTableColumns(columns);
        } else if (PAYROLL.equals(resourcePath)) {
            addPayrollTableColumns(columns);
        }
    }

    private void addPayrollDrillDownChart(ArrayNode widgets) {
        addPayrollDrillDownChart(widgets, DEPARTMENT_BREAKDOWN);
    }

    private void addPayrollDrillDownChart(ArrayNode widgets, PayrollBreakdown breakdown) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", breakdown.chartKey());
        widget.put("componentId", "praxis-chart");
        widget.put("role", "master");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("id", breakdown.chartKey());
        config.put("type", breakdown.chartType());
        config.put("title", "Folha por " + breakdown.label().toLowerCase(Locale.ROOT));
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", breakdown.field()).put("label", breakdown.label()).put("type", "category");
        axes.putObject("y")
                .put("field", "salarioLiquido")
                .put("label", "Salario liquido")
                .put("type", "value")
                .putObject("labels")
                .put("format", "BRL|symbol|2");
        ObjectNode series = config.putArray("series").addObject();
        series.put("id", "salario-liquido");
        series.put("name", "Salario liquido");
        series.put("type", breakdown.chartType());
        series.put("categoryField", breakdown.field());
        series.putObject("labels").put("format", "BRL|symbol|2");
        ObjectNode metric = series.putObject("metric");
        metric.put("field", "salarioLiquido");
        metric.put("aggregation", "sum");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", PAYROLL_ANALYTICS);
        configurePayrollBreakdownStatsQuery(config, breakdown);
        ObjectNode interactions = config.putObject("interactions");
        interactions.put("selection", true);
        interactions.put("crossFilter", true);
        ObjectNode eventActions = interactions.putObject("eventActions");
        ObjectNode selection = eventActions.putObject("selectionChange");
        selection.put("action", "emit");
        selection.putObject("mapping").put(breakdown.field(), breakdown.field());
        ObjectNode crossFilter = eventActions.putObject("crossFilter");
        crossFilter.put("action", "filter-widget");
        crossFilter.put("target", PAYROLL_DRILLDOWN_DETAIL_KEY);
        crossFilter.putObject("mapping").put(breakdown.field(), breakdown.field());
    }

    private void addPayrollDrillDownList(ArrayNode widgets) {
        addPayrollDrillDownList(widgets, DEPARTMENT_BREAKDOWN);
    }

    private void addPayrollDrillDownList(ArrayNode widgets, PayrollBreakdown breakdown) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", PAYROLL_DRILLDOWN_DETAIL_KEY);
        widget.put("componentId", "praxis-list");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("listId", PAYROLL_DRILLDOWN_DETAIL_KEY);
        inputs.put("componentInstanceId", PAYROLL_DRILLDOWN_DETAIL_KEY);
        inputs.put("configPersistenceStrategy", "input-first");
        inputs.put("enableCustomization", true);
        ObjectNode config = inputs.putObject("config");
        config.put("title", "Detalhes do recorte selecionado");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("resourcePath", PAYROLL_ANALYTICS);
        dataSource.putArray("sort").add("salarioLiquido,desc");
        ObjectNode layout = config.putObject("layout");
        layout.put("variant", "cards");
        layout.put("density", "comfortable");
        layout.put("itemSpacing", "relaxed");
        layout.put("lines", 3);
        layout.put("model", "standard");
        ObjectNode rowLayout = layout.putObject("rowLayout");
        rowLayout.put("type", "grid");
        rowLayout.put("gap", "14px");
        ArrayNode columns = rowLayout.putArray("columns");
        addListRowLayoutColumn(columns, "leading", "52px");
        addListRowLayoutColumn(columns, "identity", "1.45fr");
        addListRowLayoutColumn(columns, "balance", "minmax(150px, auto)");
        addListRowLayoutColumn(columns, "limit", "minmax(130px, auto)");
        addListRowLayoutColumn(columns, "trailing", "minmax(120px, auto)");
        ObjectNode skin = config.putObject("skin");
        skin.put("type", "glass");
        skin.put("radius", "20px");
        skin.put("shadow", "0 18px 45px rgba(15, 23, 42, 0.16)");
        ObjectNode templating = config.putObject("templating");
        templating.putObject("leading").put("type", "icon").put("expr", "payments");
        templating.putObject("identity").put("type", "text").put("expr", "${item.nomeCompleto}");
        templating.putObject("primary").put("type", "text").put("expr", "${item.nomeCompleto}");
        templating.putObject("secondary")
                .put("type", "text")
                .put("expr", "${item.cargo} · " + breakdown.label() + ": ${item." + breakdown.field() + "}");
        templating.putObject("meta").put("type", "date").put("expr", "${item.dataPagamento}|pt-BR:short");
        templating.putObject("balance").put("type", "currency").put("expr", "${item.salarioLiquido}|BRL:pt-BR");
        templating.putObject("limit").put("type", "currency").put("expr", "${item.totalDescontos}|BRL:pt-BR");
        templating.putObject("risk").put("type", "text").put("expr", "${item.composicaoFolha}");
        templating.putObject("trailing").put("type", "chip").put("expr", "${item.payrollProfile}");
        templating.put("metaPlacement", "line");
        templating.put("metaPrefixIcon", "event");
        templating.put("statusPosition", "top-right");
        ObjectNode chipColorMap = templating.putObject("chipColorMap");
        chipColorMap.put("EXEC", "primary");
        chipColorMap.put("GENERAL", "basic");
        chipColorMap.put("MEDIA", "accent");
        chipColorMap.put("OPERATIONS", "basic");
        chipColorMap.put("RND", "primary");
        chipColorMap.put("SECURITY", "warn");
        ObjectNode chipLabelMap = templating.putObject("chipLabelMap");
        chipLabelMap.put("EXEC", "Executivo");
        chipLabelMap.put("GENERAL", "Geral");
        chipLabelMap.put("MEDIA", "Midia");
        chipLabelMap.put("OPERATIONS", "Operacoes");
        chipLabelMap.put("RND", "Pesquisa e desenvolvimento");
        chipLabelMap.put("SECURITY", "Seguranca");
        ArrayNode features = templating.putArray("features");
        addListFeature(features, "account_tree", "${item.departamento}");
        addListFeature(features, "groups", "${item.equipe}");
        addListFeature(features, "public", "${item.universo}");
        templating.put("featuresVisible", true);
        templating.put("featuresMode", "icons+labels");
        ObjectNode interaction = config.putObject("interaction");
        interaction.put("expandable", true);
        interaction.put("expandTrigger", "row+icon");
        interaction.put("expandMode", "single");
        interaction.put("expandPlacement", "trailing");
        ObjectNode expansion = config.putObject("expansion");
        ArrayNode sections = expansion.putArray("sections");
        ObjectNode metadata = sections.addObject();
        metadata.put("id", "payroll-values");
        metadata.put("title", "Valores formatados");
        metadata.put("type", "metadata");
        metadata.put("itemsExpr", "$item");
        metadata.putObject("metadata").put("orientation", "horizontal").put("columns", 2);
        ObjectNode context = sections.addObject();
        context.put("id", "payroll-context");
        context.put("title", "Contexto do registro");
        context.put("type", "key-value");
        context.put("itemsExpr", "$item");
        ObjectNode rendering = expansion.putObject("rendering");
        rendering.put("shell", "attached");
        rendering.put("columns", 2);
        rendering.put("gap", "12px");
        ObjectNode i18n = config.putObject("i18n");
        i18n.put("locale", "pt-BR");
        i18n.put("currency", "BRL");
        ObjectNode ui = config.putObject("ui");
        ui.put("showSearch", true);
        ui.put("searchField", "nomeCompleto");
    }

    private void addListRowLayoutColumn(ArrayNode columns, String slot, String width) {
        ObjectNode column = columns.addObject();
        column.put("slot", slot);
        column.put("width", width);
    }

    private void addListFeature(ArrayNode features, String icon, String expr) {
        ObjectNode feature = features.addObject();
        feature.put("icon", icon);
        feature.put("expr", expr);
    }

    private void addPayrollAnalyticsProjection(ObjectNode config, PayrollBreakdown breakdown) {
        ObjectNode projection = config.putObject("analyticsProjection");
        projection.put("id", "payroll-" + breakdown.keySuffix() + "-ranking-table");
        projection.put("intent", "ranking");
        ObjectNode source = projection.putObject("source");
        source.put("kind", "praxis.stats");
        source.put("resource", PAYROLL_ANALYTICS);
        source.put("operation", breakdown.statsOperation());
        ObjectNode bindings = projection.putObject("bindings");
        ObjectNode dimension = bindings.putObject("primaryDimension");
        dimension.put("field", breakdown.field());
        dimension.put("role", "category");
        dimension.put("label", breakdown.label());
        ObjectNode metric = bindings.putArray("primaryMetrics").addObject();
        metric.put("field", "salarioLiquido");
        metric.put("aggregation", "sum");
        metric.put("label", "Salario liquido");
        ObjectNode defaults = projection.putObject("defaults");
        defaults.put("limit", 10);
        ObjectNode sort = defaults.putArray("sort").addObject();
        sort.put("field", "salarioLiquido");
        sort.put("direction", "desc");
        projection.putObject("presentationHints").putArray("preferredFamilies").add("analytic-table");
    }

    private void addPayrollDrillDownSummary(ArrayNode widgets) {
        addPayrollDrillDownSummary(widgets, DEPARTMENT_BREAKDOWN);
    }

    private void addPayrollDrillDownSummary(ArrayNode widgets, PayrollBreakdown breakdown) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-drilldown-summary");
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "detail");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Clique em uma barra do grafico para detalhar a folha daquele recorte.");
    }

    private void configureGroupByStatsQuery(ObjectNode config, String dimensionField, String metricField) {
        ObjectNode dataSource = config.with("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", PAYROLL_ANALYTICS);

        ObjectNode query = dataSource.with("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "group-by");
        query.put("statsPath", PAYROLL_ANALYTICS + "/stats/group-by");

        ArrayNode dimensions = query.withArray("dimensions");
        dimensions.removeAll();
        dimensions.add(dimensionField);

        ObjectNode queryMetric = firstQueryMetric(config);
        queryMetric.put("field", metricField);
        queryMetric.put("aggregation", "sum");
        queryMetric.put("alias", metricField);

        ObjectNode statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", dimensionField);
        statsRequest.put("limit", 10);
        ObjectNode requestMetric = statsRequest.putObject("metric");
        requestMetric.put("operation", "SUM");
        requestMetric.put("field", metricField);
        requestMetric.put("alias", metricField);
    }

    private void configurePayrollBreakdownStatsQuery(ObjectNode config, PayrollBreakdown breakdown) {
        if ("timeseries".equals(breakdown.statsOperation())) {
            configureTimeSeriesStatsQuery(config, breakdown.field(), "salarioLiquido");
        } else {
            configureGroupByStatsQuery(config, breakdown.field(), "salarioLiquido");
        }
    }

    private void configureTimeSeriesStatsQuery(ObjectNode config, String timeField, String metricField) {
        ObjectNode dataSource = config.with("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", PAYROLL_ANALYTICS);

        ObjectNode query = dataSource.with("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "timeseries");
        query.put("statsPath", PAYROLL_ANALYTICS + "/stats/timeseries");
        query.put("granularity", "month");
        query.put("fillGaps", true);

        ArrayNode dimensions = query.withArray("dimensions");
        dimensions.removeAll();
        dimensions.add(timeField);

        ObjectNode queryMetric = firstQueryMetric(config);
        queryMetric.put("field", metricField);
        queryMetric.put("aggregation", "sum");
        queryMetric.put("alias", metricField);

        ObjectNode statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", timeField);
        statsRequest.put("granularity", "MONTH");
        statsRequest.put("fillGaps", true);
        ObjectNode requestMetric = statsRequest.putObject("metric");
        requestMetric.put("operation", "SUM");
        requestMetric.put("field", metricField);
        requestMetric.put("alias", metricField);
    }

    private String firstText(JsonNode node, String fallback) {
        if (node != null && node.isArray() && !node.isEmpty()) {
            String value = node.get(0).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private void addChartSelectionStateBinding(ArrayNode bindings) {
        addChartSelectionStateBinding(bindings, DEPARTMENT_BREAKDOWN);
    }

    private void addChartSelectionStateBinding(ArrayNode bindings, PayrollBreakdown breakdown) {
        ObjectNode binding = baseBinding(bindings, breakdown.chartKey() + ".selectionChange->state." + breakdown.statePath(), "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", breakdown.chartKey());
        from.put("port", "selectionChange");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", breakdown.statePath());
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-" + breakdown.keySuffix());
        transform.put("inputSource", "payload");
        transform.put("path", "filters." + breakdown.field());
        addMetadata(binding, "chart-drilldown");
    }

    private void addChartStateQueryContextBinding(ArrayNode bindings) {
        addChartStateQueryContextBinding(bindings, DEPARTMENT_BREAKDOWN);
    }

    private void addChartStateQueryContextBinding(ArrayNode bindings, PayrollBreakdown breakdown) {
        ObjectNode binding = baseBinding(bindings, "state." + breakdown.statePath() + "->" + PAYROLL_DRILLDOWN_DETAIL_KEY + ".queryContext", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", breakdown.statePath());
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", PAYROLL_DRILLDOWN_DETAIL_KEY);
        to.put("port", "queryContext");
        to.put("direction", "input");
        ObjectNode condition = binding.putObject("condition");
        condition.putArray("!!").addObject().put("var", "state." + breakdown.statePath());
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "query-context");
        transform.put("id", breakdown.keySuffix() + "-text-filter");
        transform.put("field", breakdown.field());
        transform.put("inputSource", "payload");
        transform.put("valueVar", "payload");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", "value");
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, "list-filter");
    }

    private void addChartStateSummaryBinding(ArrayNode bindings) {
        addChartStateSummaryBinding(bindings, DEPARTMENT_BREAKDOWN);
    }

    private void addChartStateSummaryBinding(ArrayNode bindings, PayrollBreakdown breakdown) {
        ObjectNode binding = baseBinding(bindings, "state." + breakdown.statePath() + "->payroll-drilldown-summary.document", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", breakdown.statePath());
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", "payroll-drilldown-summary");
        to.put("port", "document");
        to.put("direction", "input");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state." + breakdown.statePath());
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", "payroll-drilldown-summary-document");
        transform.put("inputSource", "payload");
        ObjectNode template = transform.putObject("template");
        template.put("kind", "praxis.rich-content.document");
        template.put("version", "1.0.0");
        ObjectNode node = template.putArray("nodes").addObject();
        node.put("type", "text");
        node.put("text", "Detalhando folha por " + breakdown.label().toLowerCase(Locale.ROOT) + ": ${payload}");
        addMetadata(binding, "rich-content");
    }

    private void addDepartmentMaster(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-master");
        widget.put("componentId", "praxis-list");
        widget.put("role", "master");
        widget.putObject("inputs").putObject("config").putObject("dataSource").put("resourcePath", DEPARTMENTS);
    }

    private void addEmployeesDetail(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-employees");
        widget.put("componentId", "praxis-table");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", EMPLOYEES);
        inputs.put("tableId", "department-employees");
    }

    private void addPayrollChart(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-payroll");
        widget.put("componentId", "praxis-chart");
        widget.put("role", "detail");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("chartId", "department-payroll");
        config.put("resourcePath", PAYROLL_ANALYTICS);
    }

    private void addDepartmentSummary(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-summary");
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "detail");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Selecione um departamento para revisar funcionarios, folha e observacoes.");
    }

    private void addPickStateBinding(
            ArrayNode bindings,
            String id,
            String statePath,
            String transformId,
            String payloadPath) {
        addPickStateBinding(bindings, id, "department-master", statePath, transformId, payloadPath);
    }

    private void addPickStateBinding(
            ArrayNode bindings,
            String id,
            String sourceWidget,
            String statePath,
            String transformId,
            String payloadPath) {
        ObjectNode binding = baseBinding(bindings, id, "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", sourceWidget);
        from.put("port", "itemSelect");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", statePath);
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", transformId);
        transform.put("path", payloadPath);
        addMetadata(binding, "state");
    }

    private void addQueryContextBinding(
            ArrayNode bindings,
            String id,
            String targetWidget,
            String transformId,
            String field,
            String tag) {
        ObjectNode binding = baseBinding(bindings, id, "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartmentId");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", targetWidget);
        to.put("port", "queryContext");
        to.put("direction", "input");
        ObjectNode condition = binding.putObject("condition");
        ArrayNode notEquals = condition.putArray("!=");
        notEquals.addObject().put("var", "state.selectedDepartmentId");
        notEquals.addNull();
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "query-context");
        transform.put("id", transformId);
        transform.put("field", field);
        transform.put("inputSource", "payload");
        transform.put("valueVar", "payload");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, tag);
    }

    private void addSummaryDocumentBinding(ArrayNode bindings) {
        ObjectNode binding = baseBinding(bindings, "state.selectedDepartmentName->department-summary.document", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartmentName");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", "department-summary");
        to.put("port", "document");
        to.put("direction", "input");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedDepartmentName");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", "department-summary-document");
        transform.put("inputSource", "payload");
        ObjectNode template = transform.putObject("template");
        template.put("kind", "praxis.rich-content.document");
        template.put("version", "1.0.0");
        ObjectNode node = template.putArray("nodes").addObject();
        node.put("type", "text");
        node.put("text", "Departamento selecionado: ${payload}");
        addMetadata(binding, "rich-content");
    }

    private ObjectNode baseBinding(ArrayNode bindings, String id, String intent) {
        ObjectNode binding = bindings.addObject();
        binding.put("id", id);
        binding.put("intent", intent);
        return binding;
    }

    private void addMetadata(ObjectNode binding, String tag) {
        ObjectNode metadata = binding.putObject("metadata");
        metadata.put("source", "ui-composition-plan");
        ArrayNode tags = metadata.putArray("tags");
        tags.add("composition");
        tags.add(tag);
    }

    private record PayrollBreakdown(
            String field,
            String label,
            String keySuffix,
            String statePath,
            String statsOperation,
            String chartType) {
        String chartKey() {
            return "payroll-by-" + keySuffix + "-chart";
        }
    }

    private ObjectNode emptyCompiledFormPatch() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.put("profileId", "ui-composition-plan");
        patch.put("targetComponentId", "praxis-dynamic-page-builder");
        patch.putObject("patch");
        patch.putObject("compatibility")
                .put("aiHttpContract", "v1.1")
                .put("publicResponseKind", "ui-composition-plan")
                .put("requiresV12", false);
        patch.put("builderVersion", "ui-composition-plan-provider@0.1.0-draft");
        patch.putArray("warnings").add("compiled-form-patch-materialized-by-page-builder");
        return patch;
    }

    private ObjectNode compiledPagePatch(ObjectNode page, String profileId) {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.put("profileId", profileId);
        patch.put("targetComponentId", "praxis-dynamic-page-builder");
        patch.putObject("patch").set("page", page);
        patch.putObject("compatibility")
                .put("aiHttpContract", "v1.1")
                .put("publicResponseKind", "patch")
                .put("requiresV12", false);
        patch.put("builderVersion", "ui-composition-plan-provider@0.1.0-draft");
        patch.putArray("warnings").add("compiled-as-current-page-modification");
        return patch;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
