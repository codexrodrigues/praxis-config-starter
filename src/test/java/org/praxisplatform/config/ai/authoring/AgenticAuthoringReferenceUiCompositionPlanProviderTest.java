package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringReferenceUiCompositionPlanProviderTest {

    private final AgenticAuthoringReferenceUiCompositionPlanProvider provider =
            new AgenticAuthoringReferenceUiCompositionPlanProvider(new ObjectMapper());

    @Test
    void returnsDepartmentMasterDetailUiCompositionPlanForSupportedPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma tela master detail de departamentos com funcionarios e folha",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("version").asText()).isEqualTo("1.0");
        assertThat(plan.path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("master-detail-dashboard");
        assertThat(plan.path("widgets")).hasSize(4);
        assertThat(plan.path("widgets").get(0).path("inputs").path("config").path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/departamentos");
        assertThat(plan.path("widgets").get(1).path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(plan.path("widgets").get(2).path("inputs").path("config").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(plan.path("bindings")).hasSize(5);
        assertThat(result.orElseThrow().compiledFormPatch().path("patch").isObject()).isTrue();
    }

    @Test
    void returnsPayrollChartDrillDownUiCompositionPlanForSupportedPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Use um chart para criar drill down da folha por departamento",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("version").asText()).isEqualTo("1.0");
        assertThat(plan.path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(plan.path("state").path("values").has("selectedDepartment")).isTrue();
        assertThat(plan.path("widgets")).hasSize(3);
        JsonNode chart = plan.path("widgets").get(0);
        assertThat(chart.path("key").asText()).isEqualTo("payroll-by-department-chart");
        assertThat(chart.path("componentId").asText()).isEqualTo("praxis-chart");
        JsonNode chartConfig = chart.path("inputs").path("config");
        assertThat(chartConfig.path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(chartConfig.path("dataSource").path("query").path("statsOperation").asText())
                .isEqualTo("group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("dimensions"))
                .extracting(JsonNode::asText)
                .containsExactly("departamento");
        assertThat(chartConfig.path("interactions").path("selection").asBoolean()).isTrue();
        assertThat(chartConfig.path("interactions").path("crossFilter").asBoolean()).isTrue();
        assertThat(chartConfig.path("interactions").path("eventActions").path("crossFilter").path("target").asText())
                .isEqualTo("payroll-drilldown-table");
        JsonNode table = plan.path("widgets").get(1);
        assertThat(table.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(table.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(plan.path("bindings")).hasSize(3);
        assertThat(plan.path("bindings")).extracting(binding -> binding.path("id").asText())
                .contains(
                        "payroll-by-department-chart.selectionChange->state.selectedDepartment",
                        "state.selectedDepartment->payroll-drilldown-table.queryContext",
                        "state.selectedDepartment->payroll-drilldown-summary.document");
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
        assertThat(result.orElseThrow().compiledFormPatch().path("patch").isObject()).isTrue();
    }

    @Test
    void returnsPayrollDashboardUiCompositionPlanForDirectDashboardPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard de folha de pagamento por departamento",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(plan.path("widgets")).hasSize(3);
        assertThat(plan.path("widgets").get(0).path("componentId").asText()).isEqualTo("praxis-chart");
        assertThat(plan.path("widgets").get(0).path("inputs").path("config").path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
    }

    @Test
    void returnsPayrollTableUiCompositionPlanForConfirmedTablePrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Sim, crie uma tabela operacional de folhas de pagamento",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("version").asText()).isEqualTo("1.0");
        assertThat(plan.path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("single-table-page");
        assertThat(plan.path("widgets")).hasSize(1);
        JsonNode table = plan.path("widgets").get(0);
        assertThat(table.path("key").asText()).isEqualTo("payroll-table");
        assertThat(table.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(table.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(table.path("inputs").path("tableId").asText()).isEqualTo("payroll-table");
        assertThat(table.path("inputs").path("config").path("columns"))
                .extracting(column -> column.path("field").asText())
                .contains("salarioLiquido");
        assertThat(plan.path("bindings")).isEmpty();
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-table");
        assertThat(result.orElseThrow().compiledFormPatch().path("patch").isObject()).isTrue();
    }

    @Test
    void returnsCompiledPagePatchForSelectedTableTitleModification() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode currentPage = objectMapper.createObjectNode();
        var widgets = ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putArray("widgets");
        var widget = widgets.addObject();
        widget.put("key", "payroll-table");
        var definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        var inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/folhas-pagamento");
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");
        ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putObject("composition").putArray("links");

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Altere o titulo da tabela para Folha operacional revisada",
                null,
                null,
                null,
                currentPage,
                tableModifyIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.uiCompositionPlan()).isNull();
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:quickstart-table-title-modification");
        JsonNode patchPage = planResult.compiledFormPatch().path("patch").path("page");
        assertThat(patchPage.path("widgets")).hasSize(1);
        assertThat(patchPage.path("widgets").get(0).path("definition").path("id").asText())
                .isEqualTo("praxis-table");
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("tableId").asText())
                .isEqualTo("payroll-table");
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("title").asText())
                .isEqualTo("Folha operacional revisada");
    }

    @Test
    void returnsCompiledPagePatchForSelectedTableCurrencyFormatModification() {
        JsonNode currentPage = payrollTablePage();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Formate a coluna salario liquido da tabela como moeda em reais",
                null,
                null,
                null,
                currentPage,
                tableFormatIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.uiCompositionPlan()).isNull();
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:quickstart-table-column-format");
        JsonNode columns = planResult.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config").path("columns");
        JsonNode salarioLiquido = findColumn(columns, "salarioLiquido");
        assertThat(salarioLiquido.path("format").asText()).isEqualTo("BRL|symbol|2");
        assertThat(salarioLiquido.path("type").asText()).isEqualTo("currency");
        assertThat(planResult.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
    }

    @Test
    void returnsCompiledPagePatchForSelectedTableColumnVisibilityModification() {
        JsonNode currentPage = payrollTablePage();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Oculte a coluna total descontos da tabela",
                null,
                null,
                null,
                currentPage,
                tableVisibilityIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.uiCompositionPlan()).isNull();
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:quickstart-table-column-visibility");
        JsonNode patchPage = planResult.compiledFormPatch().path("patch").path("page");
        JsonNode columns = patchPage.path("widgets").get(0).path("definition").path("inputs")
                .path("config").path("columns");
        assertThat(findColumn(columns, "totalDescontos").path("visible").asBoolean()).isFalse();
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("tableId").asText())
                .isEqualTo("payroll-table");
    }

    @Test
    void returnsCompiledPagePatchForSelectedTableColumnOrderModification() {
        JsonNode currentPage = payrollTablePage();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Mova a coluna salario liquido da tabela para o inicio",
                null,
                null,
                null,
                currentPage,
                tableOrderIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.uiCompositionPlan()).isNull();
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:quickstart-table-column-order");
        JsonNode patchPage = planResult.compiledFormPatch().path("patch").path("page");
        JsonNode columns = patchPage.path("widgets").get(0).path("definition").path("inputs")
                .path("config").path("columns");
        assertThat(findColumn(columns, "salarioLiquido").path("order").asInt()).isZero();
        assertThat(findColumn(columns, "id").path("order").asInt()).isEqualTo(1);
        assertThat(findColumn(columns, "totalDescontos").path("order").asInt()).isEqualTo(2);
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(patchPage.path("widgets").get(0).path("definition").path("inputs").path("tableId").asText())
                .isEqualTo("payroll-table");
    }

    @Test
    void returnsCompiledPagePatchForSelectedChartTypeModification() {
        JsonNode currentPage = payrollChartPage();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Troque o grafico para linha",
                null,
                null,
                null,
                currentPage,
                chartIntent("set_chart_type")));

        assertThat(result).isPresent();
        JsonNode chartConfig = result.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("series").get(0).path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-chart-modification");
    }

    @Test
    void returnsCompiledPagePatchForSelectedChartMetricDimensionFormatAndDrilldownModifications() {
        JsonNode currentPage = payrollChartPage();

        Optional<AgenticAuthoringUiCompositionPlanResult> metricResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Use salario bruto como metrica",
                null,
                null,
                null,
                currentPage,
                chartIntent("set_chart_metric")));
        JsonNode metricConfig = metricResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(metricConfig.path("axes").path("y").path("field").asText()).isEqualTo("salarioBruto");
        assertThat(metricConfig.path("series").get(0).path("metric").path("field").asText()).isEqualTo("salarioBruto");
        assertThat(metricConfig.path("dataSource").path("query").path("metrics").get(0).path("alias").asText())
                .isEqualTo("salarioBruto");

        Optional<AgenticAuthoringUiCompositionPlanResult> dimensionResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Agrupe por competencia",
                null,
                null,
                null,
                currentPage,
                chartIntent("set_chart_dimension")));
        JsonNode dimensionConfig = dimensionResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(dimensionConfig.path("axes").path("x").path("field").asText()).isEqualTo("competencia");
        assertThat(dimensionConfig.path("series").get(0).path("categoryField").asText()).isEqualTo("competencia");
        assertThat(dimensionConfig.path("dataSource").path("query").path("dimensions"))
                .extracting(JsonNode::asText)
                .containsExactly("competencia");

        Optional<AgenticAuthoringUiCompositionPlanResult> formatResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Formate os valores em reais",
                null,
                null,
                null,
                currentPage,
                chartIntent("set_chart_value_format")));
        JsonNode formatConfig = formatResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(formatConfig.path("axes").path("y").path("labels").path("format").asText())
                .isEqualTo("BRL|symbol|2");
        assertThat(formatConfig.path("series").get(0).path("labels").path("format").asText())
                .isEqualTo("BRL|symbol|2");

        Optional<AgenticAuthoringUiCompositionPlanResult> drilldownResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Ative drill down no clique",
                null,
                null,
                null,
                currentPage,
                chartIntent("enable_chart_drilldown")));
        JsonNode drilldownConfig = drilldownResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(drilldownConfig.path("interactions").path("selection").asBoolean()).isTrue();
        assertThat(drilldownConfig.path("interactions").path("crossFilter").asBoolean()).isTrue();
        assertThat(drilldownConfig.path("interactions").path("eventActions").path("selectionChange").path("action").asText())
                .isEqualTo("emit");
    }

    @Test
    void ignoresPromptsOutsideTheControlledReferenceComposition() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um formulario simples para chamado",
                null,
                null,
                null));

        assertThat(result).isEmpty();
    }

    private AgenticAuthoringIntentResolutionResult tableModifyIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "rename_or_relabel",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "payroll-table",
                        "praxis-table",
                        "/api/human-resources/folhas-pagamento",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        0.95d,
                        "resource resolved from current target widget",
                        java.util.List.of("current-page")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult tableFormatIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "set_column_format",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "payroll-table",
                        "praxis-table",
                        "/api/human-resources/folhas-pagamento",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        0.95d,
                        "resource resolved from current target widget",
                        java.util.List.of("current-page")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult tableVisibilityIntent() {
        return tableIntent("set_column_visibility");
    }

    private AgenticAuthoringIntentResolutionResult tableOrderIntent() {
        return tableIntent("set_column_order");
    }

    private AgenticAuthoringIntentResolutionResult chartIntent(String changeKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "dashboard",
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "payroll-chart",
                        "praxis-chart",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "get",
                        0.95d,
                        "resource resolved from current target widget",
                        java.util.List.of("current-page")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult tableIntent(String changeKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "payroll-table",
                        "praxis-table",
                        "/api/human-resources/folhas-pagamento",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        0.95d,
                        "resource resolved from current target widget",
                        java.util.List.of("current-page")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private JsonNode payrollTablePage() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode currentPage = objectMapper.createObjectNode();
        var widgets = ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putArray("widgets");
        var widget = widgets.addObject();
        widget.put("key", "payroll-table");
        var definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        var inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/folhas-pagamento");
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");
        var columns = inputs.putObject("config").putArray("columns");
        var id = columns.addObject();
        id.put("field", "id");
        id.put("header", "ID");
        id.put("type", "number");
        var salarioLiquido = columns.addObject();
        salarioLiquido.put("field", "salarioLiquido");
        salarioLiquido.put("header", "Salario liquido");
        salarioLiquido.put("type", "number");
        var totalDescontos = columns.addObject();
        totalDescontos.put("field", "totalDescontos");
        totalDescontos.put("header", "Total descontos");
        totalDescontos.put("type", "number");
        ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putObject("composition").putArray("links");
        return currentPage;
    }

    private JsonNode payrollChartPage() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode currentPage = objectMapper.createObjectNode();
        var widgets = ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putArray("widgets");
        var widget = widgets.addObject();
        widget.put("key", "payroll-chart");
        var definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        var config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        var axes = config.putObject("axes");
        axes.putObject("x").put("field", "departamento");
        axes.putObject("y").put("field", "salarioLiquido");
        var series = config.putArray("series").addObject();
        series.put("id", "salario-liquido");
        series.put("categoryField", "departamento");
        series.putObject("metric").put("field", "salarioLiquido").put("aggregation", "sum");
        var dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        var query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "group-by");
        query.putArray("dimensions").add("departamento");
        query.putArray("metrics").addObject()
                .put("field", "salarioLiquido")
                .put("aggregation", "sum")
                .put("alias", "salarioLiquido");
        ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putObject("composition").putArray("links");
        return currentPage;
    }

    private JsonNode findColumn(JsonNode columns, String field) {
        for (JsonNode column : columns) {
            if (field.equals(column.path("field").asText())) {
                return column;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
