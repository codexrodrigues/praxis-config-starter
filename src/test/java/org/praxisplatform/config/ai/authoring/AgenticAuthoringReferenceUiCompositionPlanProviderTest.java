package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        JsonNode canvas = plan.path("canvas");
        assertThat(canvas.path("columns").asInt()).isEqualTo(12);
        assertThat(canvas.path("rowUnit").asText()).isEqualTo("96px");
        assertThat(canvas.path("items").path("payroll-by-department-chart").path("colSpan").asInt()).isEqualTo(12);
        assertThat(canvas.path("items").path("payroll-drilldown-list").path("colSpan").asInt()).isEqualTo(8);
        assertThat(canvas.path("items").path("payroll-drilldown-summary").path("col").asInt()).isEqualTo(9);
        assertThat(plan.path("widgets")).hasSize(3);
        JsonNode chart = plan.path("widgets").get(0);
        assertThat(chart.path("key").asText()).isEqualTo("payroll-by-department-chart");
        assertThat(chart.path("componentId").asText()).isEqualTo("praxis-chart");
        JsonNode chartConfig = chart.path("inputs").path("config");
        assertThat(chartConfig.path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(chartConfig.path("dataSource").path("query").path("statsOperation").asText())
                .isEqualTo("group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("dimensions"))
                .extracting(JsonNode::asText)
                .containsExactly("departamento");
        JsonNode statsRequest = chartConfig.path("dataSource").path("query").path("statsRequest");
        assertThat(statsRequest.path("field").asText()).isEqualTo("departamento");
        assertThat(statsRequest.path("limit").asInt()).isEqualTo(10);
        assertThat(statsRequest.path("metric").path("operation").asText()).isEqualTo("SUM");
        assertThat(statsRequest.path("metric").path("field").asText()).isEqualTo("salarioLiquido");
        assertThat(statsRequest.path("metric").path("alias").asText()).isEqualTo("salarioLiquido");
        assertThat(chartConfig.path("interactions").path("selection").asBoolean()).isTrue();
        assertThat(chartConfig.path("interactions").path("crossFilter").asBoolean()).isTrue();
        assertThat(chartConfig.path("interactions").path("eventActions").path("crossFilter").path("target").asText())
                .isEqualTo("payroll-drilldown-list");
        JsonNode list = plan.path("widgets").get(1);
        assertThat(list.path("componentId").asText()).isEqualTo("praxis-list");
        assertThat(list.path("inputs").has("resourcePath")).isFalse();
        assertThat(list.path("inputs").path("listId").asText()).isEqualTo("payroll-drilldown-list");
        JsonNode listConfig = list.path("inputs").path("config");
        assertThat(listConfig.path("title").asText()).isEqualTo("Detalhes do recorte selecionado");
        assertThat(listConfig.path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(listConfig.path("layout").path("variant").asText()).isEqualTo("cards");
        assertThat(listConfig.path("skin").path("type").asText()).isEqualTo("glass");
        assertThat(listConfig.path("templating").path("trailing").path("type").asText()).isEqualTo("chip");
        assertThat(listConfig.path("templating").path("trailing").path("expr").asText())
                .isEqualTo("${item.payrollProfile}");
        assertThat(listConfig.path("templating").path("chipLabelMap").path("RND").asText())
                .isEqualTo("Pesquisa e desenvolvimento");
        assertThat(listConfig.path("templating").path("balance").path("type").asText()).isEqualTo("currency");
        assertThat(listConfig.path("templating").path("balance").path("expr").asText()).contains("BRL:pt-BR");
        assertThat(listConfig.path("templating").path("meta").path("type").asText()).isEqualTo("date");
        assertThat(listConfig.path("templating").path("features")).hasSize(3);
        assertThat(listConfig.path("i18n").path("locale").asText()).isEqualTo("pt-BR");
        assertThat(listConfig.path("i18n").path("currency").asText()).isEqualTo("BRL");
        assertThat(plan.path("bindings")).hasSize(3);
        assertThat(plan.path("bindings")).extracting(binding -> binding.path("id").asText())
                .contains(
                        "payroll-by-department-chart.selectionChange->state.selectedDepartment",
                        "state.selectedDepartment->payroll-drilldown-list.queryContext",
                        "state.selectedDepartment->payroll-drilldown-summary.document");
        JsonNode selectionBinding = findBinding(plan.path("bindings"),
                "payroll-by-department-chart.selectionChange->state.selectedDepartment");
        assertThat(selectionBinding.path("transform").path("inputSource").asText()).isEqualTo("payload");
        assertThat(selectionBinding.path("transform").path("path").asText()).isEqualTo("filters.departamento");
        JsonNode listFilterBinding = findBinding(plan.path("bindings"),
                "state.selectedDepartment->payroll-drilldown-list.queryContext");
        assertThat(listFilterBinding.path("transform").path("inputSource").asText()).isEqualTo("payload");
        assertThat(listFilterBinding.path("transform").path("valueVar").asText()).isEqualTo("payload");
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
    void returnsPayrollRankingDashboardUiCompositionPlanForTopSalaryPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie o dashboard com ranking dos 10 maiores salarios.",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode chart = plan.path("widgets").get(0);
        JsonNode chartConfig = chart.path("inputs").path("config");
        assertThat(chart.path("key").asText()).isEqualTo("payroll-by-department-chart");
        assertThat(chartConfig.path("title").asText()).isEqualTo("Folha por departamento");
        assertThat(chartConfig.path("axes").path("x").path("field").asText()).isEqualTo("departamento");
        assertThat(chartConfig.path("series").get(0).path("categoryField").asText()).isEqualTo("departamento");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("field").asText())
                .isEqualTo("departamento");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("limit").asInt())
                .isEqualTo(10);
    }

    @Test
    void returnsPayrollDashboardUiCompositionPlanForConfirmedCompetenceBreakdown() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: folha de pagamento Confirmed: por competencia",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode chart = plan.path("widgets").get(0);
        JsonNode chartConfig = chart.path("inputs").path("config");
        assertThat(chart.path("key").asText()).isEqualTo("payroll-by-competence-chart");
        assertThat(plan.path("state").path("values").has("selectedCompetence")).isTrue();
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("dataSource").path("query").path("statsOperation").asText())
                .isEqualTo("timeseries");
        assertThat(chartConfig.path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries");
        assertThat(chartConfig.path("axes").path("x").path("field").asText()).isEqualTo("competencia");
        assertThat(chartConfig.path("series").get(0).path("categoryField").asText()).isEqualTo("competencia");
        assertThat(chartConfig.path("dataSource").path("query").path("dimensions"))
                .extracting(JsonNode::asText)
                .containsExactly("competencia");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("field").asText())
                .isEqualTo("competencia");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("granularity").asText())
                .isEqualTo("MONTH");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("metric").path("operation").asText())
                .isEqualTo("SUM");
        assertThat(plan.path("bindings")).extracting(binding -> binding.path("id").asText())
                .contains(
                        "payroll-by-competence-chart.selectionChange->state.selectedCompetence",
                        "state.selectedCompetence->payroll-drilldown-list.queryContext");
        JsonNode listFilterBinding = findBinding(plan.path("bindings"),
                "state.selectedCompetence->payroll-drilldown-list.queryContext");
        assertThat(listFilterBinding.path("transform").path("field").asText()).isEqualTo("competencia");
        JsonNode list = plan.path("widgets").get(1);
        assertThat(list.path("inputs").has("resourcePath")).isFalse();
        assertThat(list.path("inputs").path("config").path("templating").path("secondary").path("expr").asText())
                .contains("Competencia: ${item.competencia}");
    }

    @Test
    void returnsPayrollDashboardUiCompositionPlanForConfirmedStatusBreakdown() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: folha de pagamento Confirmed: por status",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode chartConfig = plan.path("widgets").get(0).path("inputs").path("config");
        assertThat(plan.path("widgets").get(0).path("key").asText()).isEqualTo("payroll-by-status-chart");
        assertThat(chartConfig.path("axes").path("x").path("field").asText()).isEqualTo("composicaoFolha");
        assertThat(chartConfig.path("dataSource").path("query").path("statsOperation").asText())
                .isEqualTo("group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("field").asText())
                .isEqualTo("composicaoFolha");
        JsonNode listFilterBinding = findBinding(plan.path("bindings"),
                "state.selectedPayrollStatus->payroll-drilldown-list.queryContext");
        assertThat(listFilterBinding.path("transform").path("field").asText()).isEqualTo("composicaoFolha");
    }

    @Test
    void returnsPayrollDashboardUiCompositionPlanForConfirmedRoleBreakdown() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: folha de pagamento Confirmed: outro Confirmed: cargo",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode chartConfig = plan.path("widgets").get(0).path("inputs").path("config");
        assertThat(plan.path("widgets").get(0).path("key").asText()).isEqualTo("payroll-by-role-chart");
        assertThat(chartConfig.path("axes").path("x").path("field").asText()).isEqualTo("cargo");
        assertThat(chartConfig.path("dataSource").path("query").path("statsOperation").asText())
                .isEqualTo("group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        assertThat(chartConfig.path("dataSource").path("query").path("statsRequest").path("field").asText())
                .isEqualTo("cargo");
        JsonNode listFilterBinding = findBinding(plan.path("bindings"),
                "state.selectedPayrollRole->payroll-drilldown-list.queryContext");
        assertThat(listFilterBinding.path("transform").path("field").asText()).isEqualTo("cargo");
    }

    @Test
    void returnsPayrollDashboardPlanFromLongCatalogAndCapabilityPrompt() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina de analise de folha de pagamento usando o catalogo de componentes: um praxis-chart com drill down por departamento e cross filter, uma praxis-list de detalhamento vinculada a selecao e um resumo lateral para explicar o departamento selecionado.",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(plan.path("widgets")).extracting(widget -> widget.path("componentId").asText())
                .containsExactly("praxis-chart", "praxis-list", "praxis-rich-content");
        assertThat(plan.path("bindings")).extracting(binding -> binding.path("id").asText())
                .contains(
                        "payroll-by-department-chart.selectionChange->state.selectedDepartment",
                        "state.selectedDepartment->payroll-drilldown-list.queryContext",
                        "state.selectedDepartment->payroll-drilldown-summary.document");
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
    void selectedPayrollTableIntentWinsOverStaleDashboardTextInPrompt() {
        String effectivePrompt = "Com base nisso, agora ignore a pergunta sobre dashboard e crie uma tabela operacional de folhas de pagamento com colunas principais";
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard de folha de pagamento Confirmed: " + effectivePrompt,
                null,
                null,
                null,
                createPayrollTableIntent(effectivePrompt)));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("single-table-page");
        assertThat(plan.path("widgets")).hasSize(1);
        assertThat(plan.path("widgets").get(0).path("key").asText()).isEqualTo("payroll-table");
        assertThat(plan.path("widgets").get(0).path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-table:selected-intent")
                .doesNotContain("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
    }

    @Test
    void rejectedDashboardPromptFallsBackToPayrollTableHeuristic() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Com base nisso, agora ignore a pergunta sobre dashboard e crie uma tabela operacional de folhas de pagamento com colunas principais",
                null,
                null,
                null));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("single-table-page");
        assertThat(plan.path("widgets").get(0).path("key").asText()).isEqualTo("payroll-table");
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-table")
                .doesNotContain("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
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
    void materializesKnownAnalyticsColumnsWhenModifyingGeneratedPayrollDashboardTable() {
        JsonNode currentPage = generatedPayrollDashboardTablePageWithoutColumns();

        Optional<AgenticAuthoringUiCompositionPlanResult> visibilityResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Na tabela selecionada, oculte a coluna salario bruto",
                null,
                null,
                null,
                currentPage,
                tableIntent("set_column_visibility", "payroll-drilldown-table", "/api/human-resources/vw-analytics-folha-pagamento")));

        JsonNode visibilityColumns = visibilityResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config").path("columns");
        assertThat(findColumn(visibilityColumns, "salarioBruto").path("visible").asBoolean()).isFalse();

        Optional<AgenticAuthoringUiCompositionPlanResult> orderResult = provider.plan(new AgenticAuthoringPlanRequest(
                "Na tabela selecionada, mova salario liquido para o inicio",
                null,
                null,
                null,
                currentPage,
                tableIntent("set_column_order", "payroll-drilldown-table", "/api/human-resources/vw-analytics-folha-pagamento")));

        JsonNode orderColumns = orderResult.orElseThrow().compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config").path("columns");
        assertThat(findColumn(orderColumns, "salarioLiquido").path("order").asInt()).isZero();
        assertThat(orderResult.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-table-column-order");
    }

    @Test
    void appendsExecutiveSummaryWidgetToExistingDashboardPage() {
        JsonNode currentPage = generatedPayrollDashboardTablePageWithoutColumns();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Adicione um novo componente de resumo executivo com total de salarios, total de descontos e media salarial",
                null,
                null,
                null,
                currentPage,
                dashboardWidgetAdditionIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:quickstart-dashboard-widget-addition");
        JsonNode patchPage = planResult.compiledFormPatch().path("patch").path("page");
        assertThat(patchPage.path("widgets")).hasSize(2);
        JsonNode appended = patchPage.path("widgets").get(1);
        assertThat(appended.path("key").asText()).isEqualTo("payroll-executive-summary");
        assertThat(appended.path("definition").path("id").asText()).isEqualTo("praxis-rich-content");
        assertThat(patchPage.path("canvas").path("items").path("payroll-executive-summary").path("colSpan").asInt())
                .isEqualTo(12);
    }

    @Test
    void returnsSelectedAnalyticsDashboardPlanForHumanDashboardWidgetIntent() {
        JsonNode currentPage = generatedPayrollDashboardTablePageWithoutColumns();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard novo para comparar os maiores valores por departamento.",
                null,
                null,
                null,
                currentPage,
                dashboardWidgetAdditionIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.compiledFormPatch().path("patch").isObject()).isTrue();
        assertThat(planResult.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(planResult.uiCompositionPlan().path("widgets"))
                .extracting(widget -> widget.path("componentId").asText())
                .containsExactly("praxis-chart", "praxis-list", "praxis-rich-content");
        assertThat(planResult.warnings())
                .contains(
                        "ui-composition-plan-provider:selected-resource-dashboard",
                        "ui-composition-plan-provider:selected-resource-dashboard:analytics-chart",
                        "ui-composition-plan-provider:quickstart-payroll-chart-drilldown")
                .doesNotContain("ui-composition-plan-provider:quickstart-dashboard-widget-addition");
    }

    @Test
    void honorsSelectedDashboardCandidateWhenCurrentPageHasAnotherAnalyticsContext() {
        JsonNode currentPage = generatedPayrollDashboardTablePageWithoutColumns();

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um novo painel para eu enxergar os maiores valores por area e conseguir detalhar os registros.",
                null,
                null,
                null,
                currentPage,
                selectedDashboardIntent(
                        "/api/human-resources/vw-perfil-heroi",
                        "/api/human-resources/vw-perfil-heroi/filter",
                        "create_dashboard")));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        JsonNode plan = planResult.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(plan.path("widgets"))
                .extracting(widget -> widget.path("componentId").asText())
                .containsExactly("praxis-table", "praxis-rich-content");
        assertThat(plan.path("widgets").get(0).path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:selected-resource-dashboard")
                .doesNotContain("ui-composition-plan-provider:selected-resource-dashboard:current-page-analytics-context");
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
        assertThat(metricConfig.path("dataSource").path("query").path("statsRequest").path("metric").path("operation").asText())
                .isEqualTo("SUM");
        assertThat(metricConfig.path("dataSource").path("query").path("statsRequest").path("metric").path("field").asText())
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
        assertThat(dimensionConfig.path("dataSource").path("query").path("statsRequest").path("field").asText())
                .isEqualTo("competencia");
        assertThat(dimensionConfig.path("dataSource").path("query").path("statsRequest").path("metric").path("operation").asText())
                .isEqualTo("SUM");

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
    void returnsResourceDashboardUiCompositionPlanForConfirmedDashboardCandidate() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: usar /api/human-resources/vw-ranking-reputacao",
                null,
                null,
                null,
                null,
                selectedDashboardIntent()));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        JsonNode plan = planResult.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(plan.path("widgets")).hasSize(2);
        JsonNode table = plan.path("widgets").get(0);
        assertThat(table.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(table.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(table.path("inputs").has("submitUrl")).isFalse();
        assertThat(table.path("inputs").has("submitMethod")).isFalse();
        assertThat(plan.path("widgets").get(1).path("componentId").asText()).isEqualTo("praxis-rich-content");
        assertThat(plan.path("widgets").get(1).path("inputs").path("document").path("nodes").get(0).path("text").asText())
                .isEqualTo("Dashboard criado para Ranking reputacao.");
        assertThat(plan.path("canvas").path("items").path("human-resources-vw-ranking-reputacao-table").path("colSpan").asInt())
                .isEqualTo(12);
        assertThat(planResult.warnings())
                .contains("ui-composition-plan-provider:selected-resource-dashboard");
        assertThat(planResult.compiledFormPatch().path("patch").isObject()).isTrue();
    }

    @Test
    void returnsResourceDashboardUiCompositionPlanForLlmDashboardChangeKind() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "quero uma tela pra ve os pagamento dos funcionario, tipo um painel bonito",
                null,
                null,
                null,
                null,
                selectedDashboardIntent(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "create_dashboard")));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.warnings())
                .contains(
                        "ui-composition-plan-provider:selected-resource-dashboard",
                        "ui-composition-plan-provider:selected-resource-dashboard:analytics-chart",
                        "ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
        JsonNode plan = planResult.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(plan.path("widgets"))
                .extracting(widget -> widget.path("componentId").asText())
                .containsExactly("praxis-chart", "praxis-list", "praxis-rich-content");
        assertThat(plan.path("widgets").get(0).path("inputs").path("config")
                .path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(plan.path("widgets").get(0).path("inputs").path("config")
                .path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
    }

    @Test
    void returnsChartFirstDashboardForConfirmedPayrollStatsCandidate() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard governado\n\nConfirmed: usar /api/human-resources/folhas-pagamento via /api/human-resources/folhas-pagamento/stats/group-by",
                null,
                null,
                null,
                null,
                selectedDashboardIntent(
                        "/api/human-resources/folhas-pagamento",
                        "/api/human-resources/folhas-pagamento/stats/group-by",
                        "create_dashboard")));

        assertThat(result).isPresent();
        AgenticAuthoringUiCompositionPlanResult planResult = result.orElseThrow();
        assertThat(planResult.warnings())
                .contains(
                        "ui-composition-plan-provider:selected-resource-dashboard",
                        "ui-composition-plan-provider:selected-resource-dashboard:analytics-chart");
        JsonNode plan = planResult.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-drilldown-dashboard");
        assertThat(plan.path("widgets").get(0).path("componentId").asText()).isEqualTo("praxis-chart");
        assertThat(plan.path("widgets").get(1).path("componentId").asText()).isEqualTo("praxis-list");
        assertThat(plan.path("widgets").get(0).path("inputs").path("config")
                .path("dataSource").path("query").path("statsPath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
    }

    @Test
    void includesPraxisFilterWhenMasterDetailPromptAsksForSearchBeforeDetails() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "quero buscar pelos dados do empregado e depois ver detalhes sem saber quais campos usar",
                null,
                null,
                null,
                null,
                selectedMasterDetailIntent()));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-search-master-detail");
        assertThat(plan.path("widgets"))
                .extracting(widget -> widget.path("componentId").asText())
                .containsExactly("praxis-filter", "praxis-table", "praxis-dynamic-form");
        JsonNode filter = plan.path("widgets").get(0);
        assertThat(filter.path("key").asText()).isEqualTo("human-resources-funcionarios-filter");
        assertThat(filter.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(filter.path("inputs").path("showFilterSettings").asBoolean()).isTrue();
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .contains(
                        "human-resources-funcionarios-filter.requestSearch->human-resources-funcionarios-master.queryContext",
                        "human-resources-funcionarios-master.rowClick->state.selectedItem",
                        "state.selectedItem->human-resources-funcionarios-detail.resourceId");
        JsonNode detailBinding = findBinding(plan.path("bindings"),
                "state.selectedItem->human-resources-funcionarios-detail.resourceId");
        assertThat(detailBinding.path("to").path("port").asText()).isEqualTo("resourceId");
        assertThat(detailBinding.path("transform").path("kind").asText()).isEqualTo("pick-path");
        assertThat(detailBinding.path("transform").path("path").asText()).isEqualTo("id");
        JsonNode filterBinding = findBinding(plan.path("bindings"),
                "human-resources-funcionarios-filter.requestSearch->human-resources-funcionarios-master.queryContext");
        assertThat(filterBinding.path("from").path("port").asText()).isEqualTo("requestSearch");
        assertThat(filterBinding.path("to").path("port").asText()).isEqualTo("queryContext");
        assertThat(filterBinding.path("transform").path("kind").asText()).isEqualTo("pick-path");
    }

    @Test
    void createsTabbedMasterDetailFormWorkspaceForSelectedResource() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "organize em abas para procurar, listar, ver detalhes e editar com formulario guiado",
                null,
                null,
                null,
                null,
                selectedTabbedMasterDetailFormIntent()));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-tabbed-master-detail-form");
        assertThat(plan.path("widgets")).hasSize(1);
        JsonNode tabs = plan.path("widgets").get(0);
        assertThat(tabs.path("componentId").asText()).isEqualTo("praxis-tabs");
        assertThat(tabs.path("inputs").path("config").path("tabs"))
                .extracting(tab -> tab.path("id").asText())
                .containsExactly("search", "details", "form");
        assertThat(tabs.path("inputs").path("config").path("tabs").path(0).path("widgets"))
                .extracting(widget -> widget.path("id").asText())
                .containsExactly("praxis-filter", "praxis-table");
        assertThat(tabs.path("inputs").path("config").path("tabs").path(2).path("widgets").path(0)
                .path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(tabs.path("inputs").path("config").path("tabs").path(1).path("widgets").path(0)
                .path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .contains(
                        "human-resources-funcionarios-workspace.human-resources-funcionarios-filter.requestSearch->human-resources-funcionarios-master.queryContext",
                        "human-resources-funcionarios-workspace.human-resources-funcionarios-master.rowClick->state.selectedItem",
                        "state.selectedItem->human-resources-funcionarios-workspace.human-resources-funcionarios-detail.resourceId",
                        "state.selectedItem->human-resources-funcionarios-workspace.human-resources-funcionarios-form.initialValue");
        JsonNode rowClick = findBinding(plan.path("bindings"),
                "human-resources-funcionarios-workspace.human-resources-funcionarios-master.rowClick->state.selectedItem");
        assertThat(rowClick.path("from").path("nestedPath").path(0).path("id").asText()).isEqualTo("search");
        assertThat(rowClick.path("from").path("nestedPath").path(1).path("key").asText())
                .isEqualTo("human-resources-funcionarios-master");
    }

    @Test
    void createsTabbedOperationalWorkspaceWithListAndCrudWhenRequested() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "organize em abas com uma lista resumida amigavel, detalhes do item escolhido e CRUD completo para criar e editar",
                null,
                null,
                null,
                null,
                selectedTabbedMasterDetailFormIntent()));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-tabbed-operational-workspace");
        JsonNode tabs = plan.path("widgets").get(0).path("inputs").path("config").path("tabs");
        assertThat(tabs)
                .extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Lista resumida", "Detalhes", "CRUD");
        assertThat(tabs.path(0).path("widgets"))
                .extracting(widget -> widget.path("id").asText())
                .containsExactly("praxis-list");
        assertThat(tabs.path(2).path("widgets").path(0).path("id").asText()).isEqualTo("praxis-crud");
        assertThat(tabs.path(1).path("widgets").path(0).path("id").asText()).isEqualTo("praxis-dynamic-form");
        JsonNode list = tabs.path(0).path("widgets").path(0);
        assertThat(list.path("inputs").path("config").path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        JsonNode crud = tabs.path(2).path("widgets").path(0);
        assertThat(crud.path("inputs").path("metadata").path("resource").path("path").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .contains(
                        "human-resources-funcionarios-workspace.human-resources-funcionarios-master.selectionChange->state.selectedItem",
                        "state.selectedItem->human-resources-funcionarios-workspace.human-resources-funcionarios-detail.resourceId");
        JsonNode detailResourceId = findBinding(plan.path("bindings"),
                "state.selectedItem->human-resources-funcionarios-workspace.human-resources-funcionarios-detail.resourceId");
        assertThat(detailResourceId.path("to").path("port").asText()).isEqualTo("resourceId");
        assertThat(detailResourceId.path("to").path("nestedPath").path(1).path("componentType").asText())
                .isEqualTo("praxis-dynamic-form");
        assertThat(detailResourceId.path("transform").path("kind").asText()).isEqualTo("pick-path");
        assertThat(detailResourceId.path("transform").path("path").asText()).isEqualTo("id");
        JsonNode selectionChange = findBinding(plan.path("bindings"),
                "human-resources-funcionarios-workspace.human-resources-funcionarios-master.selectionChange->state.selectedItem");
        assertThat(selectionChange.path("from").path("nestedPath").path(1).path("componentType").asText())
                .isEqualTo("praxis-list");
        assertThat(selectionChange.path("transform").path("path").asText()).isEqualTo("payload.value");
    }

    @Test
    void createsLocalEditorialTabbedWorkspaceWithFormAndListWithoutResourceCandidate() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina operacional com Praxis Tabs. A primeira aba Cadastro deve conter formulario com Nome, Email e Prioridade. A segunda aba Acompanhamento deve conter lista em cards com tres solicitacoes de exemplo e status curto. Use conteudo local editorial de demonstracao, sem criar regra de negocio.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("compose", "page")));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:local-editorial-tabbed-workspace");
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("local-editorial-tabbed-workspace");
        JsonNode tabsWidget = plan.path("widgets").path(0);
        assertThat(tabsWidget.path("componentId").asText()).isEqualTo("praxis-tabs");
        JsonNode tabs = tabsWidget.path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Acompanhamento");
        JsonNode form = tabs.path(0).path("widgets").path(0);
        assertThat(form.path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(form.path("bindingOrder")).extracting(JsonNode::asText)
                .containsExactly("formId", "componentInstanceId", "config", "mode", "enableCustomization");
        assertThat(form.path("inputs").has("schemaSource")).isFalse();
        assertThat(form.path("inputs").path("config").path("fieldMetadata"))
                .extracting(field -> field.path("label").asText())
                .containsExactly("Nome", "Email", "Prioridade");
        assertThat(form.path("inputs").path("config").path("sections").path(0).path("rows").path(0).path("columns").path(0).path("fields"))
                .extracting(JsonNode::asText)
                .containsExactly("nome", "email", "prioridade");
        JsonNode list = tabs.path(1).path("widgets").path(0);
        assertThat(list.path("id").asText()).isEqualTo("praxis-list");
        assertThat(list.path("bindingOrder")).extracting(JsonNode::asText)
                .containsExactly("listId", "componentInstanceId", "config", "configPersistenceStrategy", "enableCustomization");
        assertThat(list.path("inputs").path("configPersistenceStrategy").asText()).isEqualTo("input-first");
        assertThat(list.path("inputs").path("config").path("templating").path("primary").path("expr").asText())
                .isEqualTo("${item.title}");
        assertThat(list.path("inputs").path("config").path("dataSource").path("data"))
                .extracting(item -> item.path("status").asText())
                .containsExactly("Em triagem", "Aguardando revisão", "Pronto para validar");
    }

    @Test
    void createsLocalEditorialTabbedWorkspaceWithFormCrudAndRelationshipListWhenRequested() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina operacional com Praxis Tabs para solicitacoes internas. A aba Cadastro deve conter um formulario local editorial com campos Titulo, Responsavel, Prioridade e Prazo. A aba Registros deve conter um componente Praxis CRUD local editorial com tres solicitacoes ficticias e acoes visiveis de criar, editar e excluir. A aba Relacionamentos deve conter uma lista em cards relacionada aos registros com tres comentarios ficticios. Use apenas conteudo local editorial de demonstracao, sem API real, sem schema externo e sem criar regra de negocio definitiva.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("compose", "page")));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:local-editorial-tabbed-workspace");
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode tabs = plan.path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Relacionamentos");

        JsonNode form = tabs.path(0).path("widgets").path(0);
        assertThat(form.path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(form.path("inputs").path("config").path("fieldMetadata"))
                .extracting(field -> field.path("label").asText())
                .containsExactly("Título", "Responsável", "Prioridade", "Prazo");
        assertThat(form.path("inputs").path("config").path("sections").path(0).path("rows").path(0).path("columns").path(0).path("fields"))
                .extracting(JsonNode::asText)
                .containsExactly("titulo", "responsavel", "prioridade", "prazo");

        JsonNode crud = tabs.path(1).path("widgets").path(0);
        assertThat(crud.path("id").asText()).isEqualTo("praxis-crud");
        assertThat(crud.path("bindingOrder")).extracting(JsonNode::asText)
                .containsExactly("crudId", "componentInstanceId", "metadata", "enableCustomization");
        JsonNode crudMetadata = crud.path("inputs").path("metadata");
        assertThat(crudMetadata.path("component").asText()).isEqualTo("praxis-crud");
        assertThat(crudMetadata.path("resource").has("path")).isFalse();
        assertThat(crudMetadata.path("data")).hasSize(3);
        assertThat(crudMetadata.path("table").path("actions").path("row").path("display").asText())
                .isEqualTo("buttons");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("width").asText())
                .isEqualTo("280px");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("maxVisibleActions").asInt())
                .isEqualTo(2);
        assertThat(crudMetadata.path("table").path("actions").path("row").path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Editar", "Excluir");
        assertThat(crudMetadata.path("actions")).extracting(action -> action.path("action").asText())
                .containsExactly("create", "edit", "delete");

        JsonNode relationshipList = tabs.path(2).path("widgets").path(0);
        assertThat(relationshipList.path("id").asText()).isEqualTo("praxis-list");
        assertThat(relationshipList.path("inputs").path("configPersistenceStrategy").asText()).isEqualTo("input-first");
        assertThat(relationshipList.path("inputs").path("config").path("dataSource").path("data")).hasSize(3);
        assertThat(relationshipList.path("inputs").path("config").path("dataSource").path("data"))
                .extracting(item -> item.path("title").asText())
                .containsExactly(
                        "Solicitação de acesso - comentário de triagem inicial",
                        "Atualização cadastral - comentário do responsável",
                        "Revisão de permissões - comentário de validação");
    }

    @Test
    void honorsLocalEditorialCrudAndRelationshipRefinementFieldsWhenRequested() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Agora refine a pagina existente mantendo as tres abas. Na aba Registros, adicione uma coluna Categoria no CRUD e preserve as acoes Criar, Editar e Excluir. Na aba Relacionamentos, deixe os cards claramente relacionados as solicitacoes pelo titulo e inclua um campo Status do comentario. Nao use API real nem schema externo; continue como conteudo local editorial.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("modify", "table")));

        assertThat(result).isPresent();
        JsonNode tabs = result.orElseThrow().uiCompositionPlan()
                .path("widgets").path(0).path("inputs").path("config").path("tabs");
        JsonNode crudWidget = tabs.path(1).path("widgets").path(0);
        assertThat(crudWidget.path("outputs").path("rowClick").asText()).isEqualTo("emit");
        JsonNode crudMetadata = crudWidget.path("inputs").path("metadata");
        assertThat(crudMetadata.path("table").path("columns"))
                .extracting(column -> column.path("header").asText())
                .containsExactly("Título", "Responsável", "Categoria", "Prioridade", "Status");
        assertThat(crudMetadata.path("data"))
                .extracting(item -> item.path("categoria").asText())
                .containsExactly("Acesso", "Cadastro", "Permissões");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Editar", "Excluir");

        JsonNode relationshipConfig = tabs.path(2).path("widgets").path(0).path("inputs").path("config");
        assertThat(relationshipConfig.path("templating").path("secondary").path("expr").asText())
                .isEqualTo("${item.commentStatus}");
        assertThat(relationshipConfig.path("dataSource").path("data"))
                .extracting(item -> item.path("commentStatus").asText())
                .containsExactly(
                        "Status do comentário: Novo",
                        "Status do comentário: Em análise",
                        "Status do comentário: Resolvido");
    }

    @Test
    void honorsLocalEditorialTabbedAdjustmentVocabularyFromHumanSmoke() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Ajuste somente os pontos faltantes da pre-visualizacao local/editorial: a tabela da aba Registros deve ter exatamente quatro registros ficticios e cada linha deve mostrar as acoes Editar, Excluir e Ver detalhes. A aba Acompanhamento esta vazia; preencha-a com cards visuais de resumo por Status e Prioridade, usando contagens coerentes com os quatro registros. Nao mude para API real, nao use schema externo e nao crie regra de negocio definitiva.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("modify", "page")));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:local-editorial-tabbed-workspace");
        assertThat(result.orElseThrow().uiCompositionPlan().path("layoutPreset").asText())
                .isEqualTo("local-editorial-tabbed-workspace");
    }

    @Test
    void preservesLocalEditorialCrudWorkspaceWhenRefinementMentionsRegistrosColumnsSlaAndHistory() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Refine a pagina existente, preservando as tres abas e todos os componentes ja criados. Na aba Cadastro, adicione explicitamente os campos Anexos simulados e Observacoes internas ao formulario, mantendo Titulo, Responsavel, Prioridade e Prazo. Na aba Registros, substitua a coluna Prioridade por SLA, com valores ficticios como 4h, 1 dia e 3 dias, preservando Categoria, Status e as acoes Criar, Editar e Excluir. Na aba Relacionamentos, mantenha os cards relacionados por solicitacao e acrescente dentro de cada card uma mini lista Historico com Autor, Data e Status. Continue sem API real, sem schema externo e sem regra de negocio definitiva.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("modify", "page")));

        assertThat(result).isPresent();
        JsonNode tabs = result.orElseThrow().uiCompositionPlan()
                .path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Relacionamentos");

        JsonNode formConfig = tabs.path(0).path("widgets").path(0).path("inputs").path("config");
        assertThat(formConfig.path("fieldMetadata"))
                .extracting(field -> field.path("label").asText())
                .containsExactly("Título", "Responsável", "Prioridade", "Prazo", "Anexos simulados", "Observações internas");
        assertThat(formConfig.path("sections").path(0).path("rows").path(0).path("columns").path(0).path("fields"))
                .extracting(JsonNode::asText)
                .containsExactly("titulo", "responsavel", "prioridade", "prazo", "anexosSimulados", "observacoesInternas");

        JsonNode crudMetadata = tabs.path(1).path("widgets").path(0).path("inputs").path("metadata");
        assertThat(crudMetadata.path("table").path("columns"))
                .extracting(column -> column.path("header").asText())
                .containsExactly("Título", "Responsável", "Categoria", "SLA", "Status");
        assertThat(crudMetadata.path("data"))
                .extracting(item -> item.path("sla").asText())
                .containsExactly("4h", "1 dia", "3 dias");
        assertThat(crudMetadata.path("data"))
                .extracting(item -> item.has("prioridade"))
                .containsOnly(false);

        JsonNode relationshipConfig = tabs.path(2).path("widgets").path(0).path("inputs").path("config");
        assertThat(relationshipConfig.path("templating").path("secondary").path("expr").asText())
                .isEqualTo("${item.historySummary}");
        assertThat(relationshipConfig.path("dataSource").path("data"))
                .extracting(item -> item.path("historySummary").asText())
                .allSatisfy(summary -> assertThat(summary).contains("Histórico", "Autor", "Data", "Status"));
        JsonNode firstHistory = relationshipConfig.path("dataSource").path("data").path(0).path("history").path(0);
        assertThat(firstHistory.path("author").asText()).isEqualTo("Ana Souza");
        assertThat(firstHistory.path("date").asText()).isEqualTo("2026-05-06");
        assertThat(firstHistory.path("status").asText()).isEqualTo("Novo");
    }

    @Test
    void renamesLocalEditorialRelationshipsTabToTrackingAndMaterializesSlaCardsWhenRequested() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Refine esta página local/editorial preservando as abas. Em Cadastro mantenha o formulário com Título, Responsável, Prioridade e Prazo. Em Registros materialize um CRUD ou lista local com colunas Item, Status, SLA e Responsável e ações Criar, Editar e Excluir. Renomeie Relacionamentos para Acompanhamento e adicione cards locais de SLA com Abertos, Em risco e Resolvidos, mantendo um histórico local em cards. Não descubra fonte de dados, não conecte API real e não use schema externo.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("modify", "page")));

        assertThat(result).isPresent();
        JsonNode tabs = result.orElseThrow().uiCompositionPlan()
                .path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Acompanhamento");
        assertThat(tabs.path(2).path("id").asText()).isEqualTo("acompanhamento");

        JsonNode crudMetadata = tabs.path(1).path("widgets").path(0).path("inputs").path("metadata");
        assertThat(crudMetadata.path("table").path("columns"))
                .extracting(column -> column.path("header").asText())
                .containsExactly("Título", "Responsável", "SLA", "Status");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Editar", "Excluir");

        JsonNode trackingConfig = tabs.path(2).path("widgets").path(0).path("inputs").path("config");
        assertThat(trackingConfig.path("title").asText()).isEqualTo("Resumo de SLA");
        assertThat(trackingConfig.path("layout").path("variant").asText()).isEqualTo("cards");
        assertThat(trackingConfig.path("dataSource").path("data"))
                .extracting(item -> item.path("title").asText())
                .containsExactly("Abertos", "Em risco", "Resolvidos");
        assertThat(trackingConfig.path("dataSource").path("data"))
                .extracting(item -> item.path("historySummary").asText())
                .allSatisfy(summary -> assertThat(summary).contains("Histórico local"));
    }

    @Test
    void explicitTrackingTabWithSlaCardsDoesNotFallbackToRelationshipsLabel() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma tela local/editorial com abas. Na aba Cadastro coloque um formulário com Título, Responsável, Prioridade e Prazo. Na aba Registros coloque um componente CRUD ou lista local com colunas Item, Status, SLA e Responsável e ações Criar, Editar e Excluir. Na aba Acompanhamento adicione cards locais de SLA com Abertos, Em risco e Resolvidos e um histórico local em cards. Não descubra fonte de dados, não conecte API real e não use schema externo.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("compose", "page")));

        assertThat(result).isPresent();
        JsonNode tabs = result.orElseThrow().uiCompositionPlan()
                .path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Acompanhamento");
        assertThat(tabs.path(2).path("id").asText()).isEqualTo("acompanhamento");
        assertThat(tabs.path(2).path("widgets").path(0).path("inputs").path("config")
                .path("dataSource").path("data"))
                .extracting(item -> item.path("title").asText())
                .containsExactly("Abertos", "Em risco", "Resolvidos");
    }

    @Test
    void modelsComplexLocalEditorialTabsCrudSelectionAndTrackingRelationship() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Refine esta tela local/editorial mantendo as três abas. Na aba Cadastro, transforme o formulário em um cadastro avançado dentro da aba com campos Título, Responsável, Prioridade, Prazo, Categoria, SLA esperado, Descrição e Anexos simulados. Na aba Registros, mantenha um CRUD/lista local com quatro registros fictícios e colunas Item, Categoria, Status, SLA, Responsável e Última atualização; inclua ações Criar, Editar, Excluir e Ver detalhes. Quando um registro for selecionado, a aba Acompanhamento deve destacar os cards de SLA relacionados ao item selecionado e mostrar um histórico local em cards com Autor, Data, Status e Comentário. Modele o relacionamento entre Registros e Acompanhamento como estado local/seleção do Page Builder, sem API real, sem schema externo e sem descobrir fonte de dados.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("modify", "page")));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode tabs = plan.path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Registros", "Acompanhamento");
        assertThat(tabs.path(2).path("id").asText()).isEqualTo("acompanhamento");

        JsonNode formConfig = tabs.path(0).path("widgets").path(0).path("inputs").path("config");
        assertThat(formConfig.path("fieldMetadata"))
                .extracting(field -> field.path("label").asText())
                .containsExactly(
                        "Título",
                        "Responsável",
                        "Prioridade",
                        "Prazo",
                        "Categoria",
                        "SLA esperado",
                        "Descrição",
                        "Anexos simulados");

        JsonNode crudMetadata = tabs.path(1).path("widgets").path(0).path("inputs").path("metadata");
        assertThat(crudMetadata.path("table").path("columns"))
                .extracting(column -> column.path("header").asText())
                .containsExactly("Título", "Responsável", "Categoria", "SLA", "Status", "Última atualização");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Ver detalhes", "Editar", "Excluir");
        assertThat(crudMetadata.path("data")).hasSize(4);
        assertThat(crudMetadata.path("data"))
                .extracting(item -> item.path("ultimaAtualizacao").asText())
                .containsExactly("2026-05-06 09:15", "2026-05-06 10:30", "2026-05-05 16:45", "2026-05-06 11:20");

        JsonNode trackingConfig = tabs.path(2).path("widgets").path(0).path("inputs").path("config");
        assertThat(trackingConfig.path("title").asText()).isEqualTo("Resumo de SLA");
        assertThat(trackingConfig.path("dataSource").path("data"))
                .extracting(item -> item.path("title").asText())
                .containsExactly("Abertos", "Em risco", "Resolvidos");
        JsonNode firstHistory = trackingConfig.path("dataSource").path("data").path(0).path("history").path(0);
        assertThat(firstHistory.path("author").asText()).isEqualTo("Sistema editorial");
        assertThat(firstHistory.path("date").asText()).isEqualTo("2026-05-06");
        assertThat(firstHistory.path("status").asText()).isEqualTo("3 solicitações");
        assertThat(firstHistory.path("comment").asText()).contains("item selecionado");

        assertThat(plan.path("state").path("values").has("selectedItem")).isTrue();
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .containsExactly(
                        "local-solicitacoes-workspace.local-solicitacoes-crud.rowClick->state.selectedItem",
                        "state.selectedItem->local-solicitacoes-workspace.local-solicitacoes-relacionamentos-list.config");
        JsonNode trackingBinding = plan.path("bindings").path(1);
        assertThat(trackingBinding.path("to").path("port").asText()).isEqualTo("config");
        assertThat(trackingBinding.path("transform").path("kind").asText()).isEqualTo("template");
        assertThat(trackingBinding.path("transform").path("template").path("dataSource").path("data"))
                .extracting(item -> item.path("title").asText())
                .containsExactly(
                        "Item selecionado: ${payload.titulo}",
                        "SLA relacionado",
                        "Histórico local");
    }

    @Test
    void preservesUserRequestedLocalEditorialTabNamesStateAndRecordCount() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma nova variação local/editorial para uma central de chamados internos. Quero três abas: Cadastro, Fila e Detalhes. Na aba Cadastro, coloque um formulário avançado com campos Assunto, Solicitante, Área impactada, Prioridade, Canal, Prazo desejado, Descrição e Evidências simuladas. Na aba Fila, use um componente CRUD/lista local com cinco chamados fictícios e colunas Chamado, Área, Prioridade, Status, Responsável e Atualização; inclua ações Criar chamado, Atribuir, Encerrar e Ver detalhes. Modele a seleção de uma linha da Fila como estado local selectedTicket. Na aba Detalhes, mostre cards relacionados ao chamado selecionado com SLA, responsável e histórico local. Não use API real, não descubra fonte externa e materialize como decisão semântica local governada por composition.links.",
                null,
                null,
                null,
                null,
                localEditorialTabsIntent("compose", "page")));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        JsonNode tabs = plan.path("widgets").path(0).path("inputs").path("config").path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Cadastro", "Fila", "Detalhes");
        assertThat(tabs).extracting(tab -> tab.path("id").asText())
                .containsExactly("cadastro", "fila", "detalhes");

        JsonNode formConfig = tabs.path(0).path("widgets").path(0).path("inputs").path("config");
        assertThat(formConfig.path("fieldMetadata"))
                .extracting(field -> field.path("label").asText())
                .containsExactly(
                        "Assunto",
                        "Solicitante",
                        "Área impactada",
                        "Prioridade",
                        "Canal",
                        "Prazo desejado",
                        "Descrição",
                        "Evidências simuladas");
        assertThat(formConfig.path("sections").path(0).path("rows").path(0).path("columns").path(0).path("fields"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "assunto",
                        "solicitante",
                        "areaImpactada",
                        "prioridade",
                        "canal",
                        "prazoDesejado",
                        "descricao",
                        "evidenciasSimuladas");

        JsonNode crudMetadata = tabs.path(1).path("widgets").path(0).path("inputs").path("metadata");
        assertThat(crudMetadata.path("table").path("columns"))
                .extracting(column -> column.path("header").asText())
                .containsExactly("Chamado", "Área", "Prioridade", "Status", "Responsável", "Atualização");
        assertThat(crudMetadata.path("table").path("actions").path("row").path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Atribuir", "Encerrar", "Ver detalhes");
        assertThat(crudMetadata.path("actions"))
                .extracting(action -> action.path("label").asText())
                .containsExactly("Criar chamado", "Atribuir", "Encerrar", "Ver detalhes");
        assertThat(crudMetadata.path("data")).hasSize(5);
        assertThat(crudMetadata.path("data").path(0).path("area").asText()).isEqualTo("Acesso");
        assertThat(crudMetadata.path("data").path(0).path("prioridade").asText()).isEqualTo("Alta");
        assertThat(crudMetadata.path("data").path(0).path("ultimaAtualizacao").asText()).isEqualTo("2026-05-06 09:15");

        assertThat(plan.path("state").path("values").has("selectedTicket")).isTrue();
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .containsExactly(
                        "local-solicitacoes-workspace.local-solicitacoes-crud.rowClick->state.selectedTicket",
                        "state.selectedTicket->local-solicitacoes-workspace.local-solicitacoes-relacionamentos-list.config");
        assertThat(plan.path("bindings").path(0).path("from").path("nestedPath").path(0).path("id").asText())
                .isEqualTo("fila");
        assertThat(plan.path("bindings").path(1).path("to").path("nestedPath").path(0).path("id").asText())
                .isEqualTo("detalhes");
        assertThat(plan.path("bindings").path(1).path("condition").path("!!").path(0).path("var").asText())
                .isEqualTo("state.selectedTicket");
    }

    @Test
    void preservesExistingTabbedWorkspaceWhenHumanRefinementAsksToSearchAndOpenDetails() {
        JsonNode currentWorkspace = provider.plan(new AgenticAuthoringPlanRequest(
                        "organize em abas com uma lista resumida amigavel, detalhes do item escolhido e CRUD completo para criar e editar",
                        null,
                        null,
                        null,
                        null,
                        selectedTabbedMasterDetailFormIntent()))
                .orElseThrow()
                .uiCompositionPlan();
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode projectKnowledge = contextHints.putObject("projectKnowledge");
        projectKnowledge.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        projectKnowledge.put("source", "domain_knowledge_concept");
        projectKnowledge.put("influenceCount", 1);
        ObjectNode knowledgeEntry = projectKnowledge.putArray("entries").addObject();
        knowledgeEntry.put("knowledgeId", "knowledge-employee-search");
        knowledgeEntry.put("conceptKey", "employee-search-main-data");
        knowledgeEntry.put("kind", "business-rule");
        knowledgeEntry.put("visibility", "safe-for-authoring");
        knowledgeEntry.put("summary", "Use annotated employee search surfaces instead of assuming field names.");

        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Agora quero que a tela tambem me ajude a encontrar empregados usando os dados principais da pessoa e depois abrir os detalhes sem eu precisar saber quais campos existem.",
                null,
                null,
                null,
                currentWorkspace,
                selectedMasterDetailIntent(),
                null,
                null,
                null,
                null,
                null,
                contextHints));

        assertThat(result).isPresent();
        JsonNode plan = result.orElseThrow().uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-tabbed-operational-workspace");
        JsonNode tabs = plan.path("widgets").get(0).path("inputs").path("config").path("tabs");
        assertThat(tabs)
                .extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Buscar e listar", "Detalhes", "CRUD");
        assertThat(tabs.path(0).path("widgets"))
                .extracting(widget -> widget.path("id").asText())
                .containsExactly("praxis-filter", "praxis-table");
        assertThat(tabs.path(2).path("widgets").path(0).path("id").asText()).isEqualTo("praxis-crud");
        assertThat(plan.path("bindings"))
                .extracting(binding -> binding.path("id").asText())
                .contains(
                        "human-resources-funcionarios-workspace.human-resources-funcionarios-filter.requestSearch->human-resources-funcionarios-master.queryContext",
                        "human-resources-funcionarios-workspace.human-resources-funcionarios-master.rowClick->state.selectedItem",
                        "state.selectedItem->human-resources-funcionarios-workspace.human-resources-funcionarios-detail.resourceId");
        assertThat(plan.path("sourceRefs"))
                .extracting(JsonNode::asText)
                .contains(
                        "intent-resolution",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "projectKnowledge:knowledge-employee-search");
        assertThat(plan.toString()).doesNotContain("Use annotated employee search surfaces");
    }

    @Test
    void normalizesMissionSummaryCandidateToOperationsResource() {
        Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard operacional de missoes",
                null,
                null,
                null,
                null,
                selectedDashboardIntent(
                        "/api/human-resources/vw-resumo-missoes",
                        "/api/human-resources/vw-resumo-missoes/filter/cursor")));

        assertThat(result).isPresent();
        JsonNode table = result.orElseThrow().uiCompositionPlan().path("widgets").get(0);
        assertThat(table.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/operations/vw-resumo-missoes");
        assertThat(table.path("inputs").path("title").asText()).isEqualTo("Resumo missoes");
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
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento/all",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento/all",
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
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
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

    private AgenticAuthoringIntentResolutionResult dashboardWidgetAdditionIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "dashboard",
                "add_dashboard_widget",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "post",
                        0.94d,
                        "prompt asks to add a payroll analytics dashboard widget",
                        java.util.List.of("known-quickstart-analytics-view")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult selectedDashboardIntent() {
        return selectedDashboardIntent(
                "/api/human-resources/vw-ranking-reputacao",
                "/api/human-resources/vw-ranking-reputacao/filter/cursor");
    }

    private AgenticAuthoringIntentResolutionResult selectedDashboardIntent(String resourcePath, String submitUrl) {
        return selectedDashboardIntent(resourcePath, submitUrl, "create_artifact");
    }

    private AgenticAuthoringIntentResolutionResult selectedDashboardIntent(
            String resourcePath,
            String submitUrl,
            String changeKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        resourcePath,
                        "post",
                        "/schemas/filtered?path=" + submitUrl + "&operation=post&schemaType=response",
                        submitUrl,
                        "POST",
                        0.94d,
                        "user selected a dashboard resource candidate",
                        java.util.List.of("quick-reply")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult selectedMasterDetailIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_master_detail",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        0.94d,
                        "user selected an employee resource candidate",
                        java.util.List.of("quick-reply")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult selectedTabbedMasterDetailFormIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_tabbed_master_detail_form",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        0.94d,
                        "user selected a resource candidate",
                        java.util.List.of("quick-reply")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult localEditorialTabsIntent(String operationKind, String artifactKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                artifactKind,
                "create_local_editorial_tabbed_workspace",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
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
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "post"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                        "post",
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
        return tableIntent(changeKind, "payroll-table", "/api/human-resources/folhas-pagamento");
    }

    private AgenticAuthoringIntentResolutionResult createPayrollTableIntent(String effectivePrompt) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "table",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                        "/api/human-resources/folhas-pagamento",
                        "get",
                        0.95d,
                        "resource selected by resolved table intent",
                        java.util.List.of("llm-intent-resolution")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                effectivePrompt,
                "Vou criar uma tabela operacional de folhas de pagamento.",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new ObjectMapper().createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult tableIntent(String changeKind, String widgetKey, String resourcePath) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        widgetKey,
                        "praxis-table",
                        resourcePath,
                        "/schemas/filtered?path=" + resourcePath + "/all&operation=get&schemaType=response",
                        resourcePath + "/all",
                        "get"),
                new AgenticAuthoringCandidate(
                        resourcePath,
                        "get",
                        "/schemas/filtered?path=" + resourcePath + "/all&operation=get&schemaType=response",
                        resourcePath + "/all",
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

    private JsonNode generatedPayrollDashboardTablePageWithoutColumns() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode currentPage = objectMapper.createObjectNode();
        var widgets = ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage).putArray("widgets");
        var widget = widgets.addObject();
        widget.put("key", "payroll-drilldown-table");
        var definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        var inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        inputs.put("tableId", "payroll-drilldown-table");
        var canvasItems = ((com.fasterxml.jackson.databind.node.ObjectNode) currentPage)
                .putObject("canvas")
                .putObject("items");
        var tableItem = canvasItems.putObject("payroll-drilldown-table");
        tableItem.put("col", 1);
        tableItem.put("row", 5);
        tableItem.put("colSpan", 8);
        tableItem.put("rowSpan", 7);
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
        query.put("statsPath", "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by");
        query.putArray("dimensions").add("departamento");
        query.putArray("metrics").addObject()
                .put("field", "salarioLiquido")
                .put("aggregation", "sum")
                .put("alias", "salarioLiquido");
        var statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", "departamento");
        statsRequest.put("limit", 10);
        statsRequest.putObject("metric")
                .put("operation", "SUM")
                .put("field", "salarioLiquido")
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

    private JsonNode findBinding(JsonNode bindings, String id) {
        for (JsonNode binding : bindings) {
            if (id.equals(binding.path("id").asText())) {
                return binding;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
