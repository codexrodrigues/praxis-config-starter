package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
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

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;

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
        if (supportsChartDrillDown(request)) {
            return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:quickstart-payroll-chart-drilldown"),
                    chartDrillDownPlan(),
                    emptyCompiledFormPatch()));
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
        String prompt = normalize(request.userPrompt());
        boolean asksForChart = containsAny(prompt, "chart", "grafico", "bar", "barra", "donut", "pizza");
        boolean asksForDashboard = containsAny(prompt, "dashboard", "painel");
        boolean asksForDrillDown = containsAny(prompt, "drill down", "drill-down", "drilldown", "detalhar", "detalhe", "aprofundar");
        boolean referencesPayrollOrDepartment = containsAny(prompt,
                "folha", "pagamento", "salario", "salarios", "departamento", "departamentos", "funcionario", "funcionarios");
        boolean referencesPayrollByDepartment = containsAny(prompt, "departamento", "departamentos")
                && containsAny(prompt, "folha", "pagamento", "salario", "salarios");
        return (asksForChart && asksForDrillDown && referencesPayrollOrDepartment)
                || (asksForDashboard && referencesPayrollByDepartment);
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
                && chartCapabilityCatalog.supports(request.intentResolution().changeKind(), prompt);
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
        String field = chartCapabilityCatalog.resolveField("set_chart_metric", prompt).orElse("");
        if (field.isBlank()) {
            return false;
        }
        config.with("axes").with("y").put("field", field);
        ObjectNode series = firstSeries(config);
        series.with("metric").put("field", field);
        series.with("metric").put("aggregation", "sum");
        ObjectNode queryMetric = firstQueryMetric(config);
        queryMetric.put("field", field);
        queryMetric.put("aggregation", "sum");
        queryMetric.put("alias", field);
        return true;
    }

    private boolean applyChartDimension(ObjectNode config, String prompt) {
        String field = chartCapabilityCatalog.resolveField("set_chart_dimension", prompt).orElse("");
        if (field.isBlank()) {
            return false;
        }
        config.with("axes").with("x").put("field", field);
        firstSeries(config).put("categoryField", field);
        ArrayNode dimensions = config.with("dataSource").with("query").withArray("dimensions");
        dimensions.removeAll();
        dimensions.add(field);
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
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "chart-drilldown-dashboard");

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull("selectedDepartment");

        ArrayNode widgets = plan.putArray("widgets");
        addPayrollDrillDownChart(widgets);
        addPayrollDrillDownTable(widgets);
        addPayrollDrillDownSummary(widgets);

        ArrayNode bindings = plan.putArray("bindings");
        addChartSelectionStateBinding(bindings);
        addChartStateQueryContextBinding(bindings);
        addChartStateSummaryBinding(bindings);
        return plan;
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
        ArrayNode columns = tableWidget.with("definition").with("inputs").with("config").withArray("columns");
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
        ArrayNode columns = tableWidget.with("definition").with("inputs").with("config").withArray("columns");
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
                && "set_column_format".equals(request.intentResolution().changeKind())
                && tableCapabilityCatalog.supports("set_column_format", normalize(request.userPrompt()));
    }

    private boolean supportsTableColumnVisibilityModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "set_column_visibility".equals(request.intentResolution().changeKind())
                && tableCapabilityCatalog.supports("set_column_visibility", normalize(request.userPrompt()));
    }

    private boolean supportsTableColumnOrderModification(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null || request.currentPage() == null
                || request.intentResolution() == null || request.intentResolution().target() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        return "modify".equals(request.intentResolution().operationKind())
                && "table".equals(request.intentResolution().artifactKind())
                && "set_column_order".equals(request.intentResolution().changeKind())
                && tableCapabilityCatalog.supports("set_column_order", prompt);
    }

    private String resolveFormatField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_format",
                normalize(prompt == null ? "" : prompt)).orElse("");
    }

    private String resolveVisibilityField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_visibility",
                normalize(prompt == null ? "" : prompt)).orElse("");
    }

    private String resolveOrderField(String prompt) {
        return tableCapabilityCatalog.resolveField(
                "set_column_order",
                normalize(prompt == null ? "" : prompt)).orElse("");
    }

    private ObjectNode findColumn(ArrayNode columns, String field) {
        for (JsonNode column : columns) {
            if (column.isObject() && field.equals(column.path("field").asText())) {
                return (ObjectNode) column;
            }
        }
        return null;
    }

    private void addPayrollDrillDownChart(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-by-department-chart");
        widget.put("componentId", "praxis-chart");
        widget.put("role", "master");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("id", "payroll-by-department-chart");
        config.put("type", "bar");
        config.put("title", "Folha por departamento");
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", "departamento").put("label", "Departamento").put("type", "category");
        axes.putObject("y").put("field", "salarioLiquido").put("label", "Salario liquido").put("type", "value");
        ObjectNode series = config.putArray("series").addObject();
        series.put("id", "salario-liquido");
        series.put("name", "Salario liquido");
        series.put("categoryField", "departamento");
        ObjectNode metric = series.putObject("metric");
        metric.put("field", "salarioLiquido");
        metric.put("aggregation", "sum");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", PAYROLL_ANALYTICS);
        ObjectNode query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "group-by");
        query.putArray("dimensions").add("departamento");
        ObjectNode queryMetric = query.putArray("metrics").addObject();
        queryMetric.put("field", "salarioLiquido");
        queryMetric.put("aggregation", "sum");
        queryMetric.put("alias", "salarioLiquido");
        ObjectNode interactions = config.putObject("interactions");
        interactions.put("selection", true);
        interactions.put("crossFilter", true);
        ObjectNode eventActions = interactions.putObject("eventActions");
        ObjectNode selection = eventActions.putObject("selectionChange");
        selection.put("action", "emit");
        selection.putObject("mapping").put("departamento", "departamento");
        ObjectNode crossFilter = eventActions.putObject("crossFilter");
        crossFilter.put("action", "filter-widget");
        crossFilter.put("target", "payroll-drilldown-table");
        crossFilter.putObject("mapping").put("departamento", "departamento");
    }

    private void addPayrollDrillDownTable(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-drilldown-table");
        widget.put("componentId", "praxis-table");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", PAYROLL_ANALYTICS);
        inputs.put("tableId", "payroll-drilldown-table");
    }

    private void addPayrollDrillDownSummary(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-drilldown-summary");
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "detail");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Clique em uma barra do grafico para detalhar a folha daquele departamento.");
    }

    private void addChartSelectionStateBinding(ArrayNode bindings) {
        ObjectNode binding = baseBinding(bindings, "payroll-by-department-chart.selectionChange->state.selectedDepartment", "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", "payroll-by-department-chart");
        from.put("port", "selectionChange");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", "selectedDepartment");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", "pick-selected-department");
        transform.put("path", "filters.departamento");
        addMetadata(binding, "chart-drilldown");
    }

    private void addChartStateQueryContextBinding(ArrayNode bindings) {
        ObjectNode binding = baseBinding(bindings, "state.selectedDepartment->payroll-drilldown-table.queryContext", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartment");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", "payroll-drilldown-table");
        to.put("port", "queryContext");
        to.put("direction", "input");
        ObjectNode condition = binding.putObject("condition");
        condition.putArray("!!").addObject().put("var", "state.selectedDepartment");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "query-context");
        transform.put("id", "department-text-filter");
        transform.put("field", "departamento");
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("distinctBy", "value");
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, "table-filter");
    }

    private void addChartStateSummaryBinding(ArrayNode bindings) {
        ObjectNode binding = baseBinding(bindings, "state.selectedDepartment->payroll-drilldown-summary.document", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartment");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", "payroll-drilldown-summary");
        to.put("port", "document");
        to.put("direction", "input");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedDepartment");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", "payroll-drilldown-summary-document");
        ObjectNode template = transform.putObject("template");
        template.put("kind", "praxis.rich-content.document");
        template.put("version", "1.0.0");
        ObjectNode node = template.putArray("nodes").addObject();
        node.put("type", "text");
        ArrayNode concat = node.putObject("text").putArray("concat");
        concat.add("Detalhando folha do departamento: ");
        concat.addObject().put("var", "value");
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
        ObjectNode binding = baseBinding(bindings, id, "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", "department-master");
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
        ObjectNode template = transform.putObject("template");
        template.put("kind", "praxis.rich-content.document");
        template.put("version", "1.0.0");
        ObjectNode node = template.putArray("nodes").addObject();
        node.put("type", "text");
        ArrayNode concat = node.putObject("text").putArray("concat");
        concat.add("Departamento selecionado: ");
        concat.addObject().put("var", "value");
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
}
