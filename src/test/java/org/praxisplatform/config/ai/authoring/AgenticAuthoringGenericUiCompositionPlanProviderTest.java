package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringGenericUiCompositionPlanProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringGenericUiCompositionPlanProvider provider =
            new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);

    @Test
    void createsHostNeutralDashboardFromSelectedCandidate() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard de acompanhamento",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/acme/orders", List.of(axis(
                        "status",
                        "status",
                        "Status",
                        "bar",
                        "vertical"))))).orElseThrow();

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).containsExactly("ui-composition-plan-provider:generic-resource-dashboard");
        assertThat(result.compiledFormPatch().path("compatibility").path("publicResponseKind").asText())
                .isEqualTo("ui-composition-plan");
        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(plan.path("version").asText()).isEqualTo("1.0");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(plan.path("canvas").path("mode").asText()).isEqualTo("grid");
        assertThat(plan.path("canvas").path("columns").asInt()).isEqualTo(12);
        assertThat(plan.path("canvas").path("rowUnit").asText()).isEqualTo("72px");
        assertThat(plan.path("canvas").path("items").path("orders-summary").path("rowSpan").asInt()).isEqualTo(2);
        assertThat(plan.path("canvas").path("items").has("orders-kpis")).isFalse();
        assertThat(plan.path("canvas").path("items").path("orders-filter").path("row").asInt()).isEqualTo(3);
        assertThat(plan.path("canvas").path("items").path("orders-filter").path("rowSpan").asInt()).isEqualTo(1);
        assertThat(plan.path("canvas").path("items").path("orders-chart-status").path("row").asInt()).isEqualTo(4);
        assertThat(plan.path("canvas").path("items").path("orders-chart-status").path("colSpan").asInt()).isEqualTo(12);
        assertThat(plan.path("canvas").path("items").path("orders-chart-status").path("rowSpan").asInt()).isEqualTo(4);
        assertThat(plan.path("canvas").path("items").path("orders-table").path("row").asInt()).isEqualTo(8);
        assertThat(plan.path("widgets")).hasSize(4);
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
                        "praxis-filter",
                        "praxis-chart",
                        "praxis-table");
        assertThat(plan.path("widgets").toString())
                .contains("praxis-chart")
                .contains("praxis-filter")
                .contains("praxis-table")
                .contains("/api/acme/orders")
                .contains("\"statsEndpointInference\":\"canonical-resource-stats-group-by\"")
                .doesNotContain("kpi-band")
                .doesNotContain("human-resources")
                .doesNotContain("payroll")
                .doesNotContain("quickstart");
        assertRuntimeInputsDoNotContainGovernanceEvidence(plan);
        JsonNode summaryInputs = findWidgetInputs(plan, "praxis-rich-content", "supporting");
        assertThat(summaryInputs.path("document").path("kind").asText()).isEqualTo("praxis.rich-content");
        assertThat(summaryInputs.path("document").path("nodes").path(0).path("type").asText()).isEqualTo("card");
        assertThat(summaryInputs.path("document").path("nodes").path(0).path("size").asText()).isEqualTo("sm");
        assertThat(summaryInputs.path("document").path("nodes").path(0).path("density").asText()).isEqualTo("compact");
        assertThat(summaryInputs.path("document").path("nodes").path(0).path("orientation").asText())
                .isEqualTo("horizontal");
        assertThat(summaryInputs.path("document").path("nodes").toString())
                .contains("Pre-visualizacao analitica")
                .contains("Visao inicial baseada em Orders")
                .contains("selecoes no grafico filtram os detalhes")
                .doesNotContain("Preview for");
        JsonNode filterInputs = findWidgetInputs(plan, "praxis-filter");
        assertThat(filterInputs.has("schemaUrl")).isFalse();
        assertThat(filterInputs.has("schemaVerification")).isFalse();
        JsonNode chartWidget = findWidget(plan, "praxis-chart", "main");
        assertThat(chartWidget.path("outputs").path("pointClick").asText()).isEqualTo("emit");
        assertThat(chartWidget.path("outputs").path("selectionChange").asText()).isEqualTo("emit");
        assertThat(chartWidget.path("outputs").path("crossFilter").asText()).isEqualTo("emit");
        assertThat(chartWidget.path("inputs").path("config").path("interactions").path("pointClick").asBoolean()).isTrue();
        assertThat(chartWidget.path("inputs").path("config").path("interactions").path("selection").asBoolean()).isTrue();
        assertThat(plan.path("bindings").toString())
                .contains("orders-filter.requestSearch->orders-chart-status.queryContext")
                .contains("orders-chart-status.pointClick->orders-table.queryContext");
        JsonNode filterToChart = findBinding(plan.path("bindings"),
                "orders-filter.requestSearch->orders-chart-status.queryContext");
        assertThat(filterToChart.path("from").path("kind").asText()).isEqualTo("component-port");
        assertThat(filterToChart.path("from").path("widget").asText()).isEqualTo("orders-filter");
        assertThat(filterToChart.path("from").path("direction").asText()).isEqualTo("output");
        assertThat(filterToChart.path("from").has("widgetKey")).isFalse();
        assertThat(filterToChart.path("to").path("kind").asText()).isEqualTo("component-port");
        assertThat(filterToChart.path("to").path("widget").asText()).isEqualTo("orders-chart-status");
        assertThat(filterToChart.path("to").path("direction").asText()).isEqualTo("input");
        assertThat(filterToChart.path("to").has("widgetKey")).isFalse();
        JsonNode chartToTable = findBinding(plan.path("bindings"),
                "orders-chart-status.pointClick->orders-table.queryContext");
        assertThat(chartToTable.path("transform").path("kind").asText()).isEqualTo("query-context");
        assertThat(chartToTable.path("transform").path("field").asText()).isEqualTo("status");
        assertThat(chartToTable.path("transform").path("valueVar").asText()).isEqualTo("payload.category");
    }

    @Test
    void createsOperationalMonitoringDashboardWithSemanticCharts() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/operations/incidentes", List.of(
                        axis("severity", "gravidade", "Gravidade", "bar", "vertical"),
                        axis("status", "andamento", "Andamento", "bar", "vertical"),
                        axis("owner", "responsavel", "Responsavel", "horizontal-bar", "horizontal"))))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("widgets")).hasSize(6);
        assertThat(plan.path("canvas").path("items").path("incidentes-chart-gravidade").path("col").asInt())
                .isEqualTo(1);
        assertThat(plan.path("canvas").path("items").path("incidentes-chart-andamento").path("col").asInt())
                .isEqualTo(5);
        assertThat(plan.path("canvas").path("items").path("incidentes-chart-responsavel").path("col").asInt())
                .isEqualTo(9);
        assertThat(plan.path("canvas").path("items").path("incidentes-chart-responsavel").path("colSpan").asInt())
                .isEqualTo(4);
        assertThat(plan.path("canvas").path("items").path("incidentes-table").path("row").asInt())
                .isEqualTo(8);
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
                        "praxis-filter",
                        "praxis-chart",
                        "praxis-chart",
                        "praxis-chart",
                        "praxis-table");
        assertThat(plan.path("widgets").toString())
                .contains("\"field\":\"gravidade\"")
                .contains("\"field\":\"andamento\"")
                .contains("\"field\":\"responsavel\"")
                .contains("\"statsOperation\":\"group-by\"")
                .contains("\"statsPath\":\"/api/operations/incidentes/stats/group-by\"")
                .contains("\"statsEndpointInference\":\"canonical-resource-stats-group-by\"")
                .contains("\"operation\":\"COUNT\"")
                .contains("\"resourcePath\":\"/api/operations/incidentes\"")
                .contains("\"provenance\":\"llm-authored-semantic-axis\"")
                .contains("Registros por Gravidade")
                .doesNotContain("Chamados por");
        JsonNode tableInputs = findWidgetInputs(plan, "praxis-table");
        assertThat(tableInputs.path("resourcePath").asText()).isEqualTo("/api/operations/incidentes");
        assertThat(tableInputs.has("title")).isFalse();
        assertThat(tableInputs.path("config").path("title").asText()).isEqualTo("Incidentes");
        assertThat(tableInputs.has("schemaUrl")).isFalse();
        assertThat(tableInputs.has("submitUrl")).isFalse();
        assertThat(tableInputs.has("submitMethod")).isFalse();
        assertThat(plan.path("diagnostics").path("semanticAxes").toString())
                .contains("\"schemaVerified\":false")
                .contains("\"schemaProbeStatus\":\"pending\"");
        assertThat(plan.path("bindings").toString())
                .contains("incidentes-filter.requestSearch->incidentes-chart-gravidade.queryContext")
                .contains("incidentes-chart-gravidade.pointClick->incidentes-table.queryContext");
        JsonNode chartToTable = findBinding(plan.path("bindings"),
                "incidentes-chart-gravidade.pointClick->incidentes-table.queryContext");
        assertThat(chartToTable.path("transform").path("kind").asText()).isEqualTo("query-context");
        assertThat(chartToTable.path("transform").path("field").asText()).isEqualTo("gravidade");
        assertThat(chartToTable.path("transform").path("valueVar").asText()).isEqualTo("payload.category");
    }

    @Test
    void deduplicatesDashboardAxesByCanonicalField() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Quero um painel 360 de funcionarios por departamento e departamento.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/rh/funcionarios", List.of(
                        axis("departamento", "departamento", "Departamento", "bar", "vertical"),
                        axis("department", "departamento", "Departamento", "bar", "vertical"),
                        axis("cargo", "cargo", "Cargo", "bar", "vertical"))))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("widgets").findValuesAsText("key"))
                .contains("funcionarios-chart-departamento", "funcionarios-chart-cargo");
        assertThat(plan.path("widgets").findValuesAsText("key"))
                .filteredOn("funcionarios-chart-departamento"::equals)
                .hasSize(1);
        assertThat(plan.path("canvas").path("items").has("funcionarios-chart-departamento")).isTrue();
        assertThat(plan.path("canvas").path("items").has("funcionarios-chart-cargo")).isTrue();
        assertThat(plan.path("bindings").toString())
                .contains("funcionarios-chart-departamento.pointClick->funcionarios-table.queryContext")
                .contains("funcionarios-chart-cargo.pointClick->funcionarios-table.queryContext");
    }

    @Test
    void infersEmployeeDashboardChartsWhenLlmReturnsTableBiasedDashboardDecision() {
        AgenticAuthoringVisualizationDecision tableBiasedDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "dashboard with table",
                "dashboard",
                "praxis-table",
                List.of(),
                true,
                true,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "ficou só tabela. transforme em um dashboard 360 completo: mantenha a tabela de funcionarios, "
                        + "adicione filtros, grafico por departamento e grafico por cargo, "
                        + "e conecte clique nos graficos para filtrar a tabela",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "table", "create_artifact", "/api/rh/funcionarios", tableBiasedDecision)))
                .orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .contains("praxis-rich-content", "praxis-filter", "praxis-chart", "praxis-table");
        assertThat(plan.path("widgets").findValuesAsText("key"))
                .contains(
                        "funcionarios-filter",
                        "funcionarios-chart-departamentoNome",
                        "funcionarios-chart-cargoNome",
                        "funcionarios-table");
        assertThat(plan.path("bindings").toString())
                .contains("funcionarios-chart-departamentoNome.pointClick->funcionarios-table.queryContext")
                .contains("funcionarios-chart-cargoNome.pointClick->funcionarios-table.queryContext");
        assertThat(plan.path("bindings").toString())
                .contains("\"field\":\"departamentoNome\"")
                .contains("\"field\":\"cargoNome\"");
    }

    @Test
    void createsChartSurfaceOpenModalDrilldownFromContextualAction() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("kind", "contextual-preview-action");
        contextHints.put("surfaceActionId", "surface.open");
        contextHints.put("surfacePresentation", "modal");
        contextHints.put("surfaceWidgetId", "praxis-table");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Abra os registros da categoria selecionada do gráfico em um modal de detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                objectMapper.createObjectNode(),
                dashboardIntent("/api/risk-intelligence/vw-indicadores-incidentes",
                        List.of(axis("severity", "severidade", "Severidade", "bar", "vertical"))),
                null,
                null,
                null,
                null,
                null,
                contextHints)).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("chart-surface-drilldown");
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-rich-content", "praxis-filter", "praxis-chart");
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .doesNotContain("praxis-table");
        assertThat(plan.has("composition")).isFalse();
        JsonNode link = plan.path("bindings").get(0);
        assertThat(link.path("intent").asText()).isEqualTo("command-dispatch");
        assertThat(link.path("from").path("port").asText()).isEqualTo("pointClick");
        assertThat(link.path("to").path("kind").asText()).isEqualTo("global-action");
        assertThat(link.path("to").path("actionId").asText()).isEqualTo("surface.open");
        JsonNode payload = link.path("to").path("payload");
        assertThat(payload.path("presentation").asText()).isEqualTo("modal");
        assertThat(payload.path("widget").path("id").asText()).isEqualTo("praxis-table");
        assertThat(payload.path("widget").path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(payload.path("widget").path("inputs").path("componentInstanceId").asText())
                .isEqualTo(payload.path("widget").path("inputs").path("tableId").asText());
        assertThat(payload.path("widget").path("inputs").path("queryContext").has("filters")).isTrue();
        assertThat(payload.path("widget").path("bindingOrder").toString())
                .isEqualTo("[\"tableId\",\"componentInstanceId\",\"resourcePath\",\"config\",\"queryContext\"]");
        assertThat(payload.path("widget").path("inputs").path("config").path("columns").get(0).path("field").asText())
                .isEqualTo("severidade");
        assertThat(payload.path("bindings").get(0).path("from").asText())
                .isEqualTo("payload.category");
        assertThat(payload.path("bindings").get(0).path("to").asText())
                .isEqualTo("widget.inputs.queryContext.filters.severidade");
        assertThat(link.path("policy").path("distinctBy").asText()).isEqualTo("payload.category");
        assertThat(plan.path("canvas").path("items").has("vw-indicadores-incidentes-table")).isFalse();
    }

    @Test
    void createsOnlyChartWhenUserExplicitlyRejectsDashboardSupportWidgets() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "single-chart",
                "single_chart",
                "praxis-chart",
                List.of(axis("severity", "severidade", "Severidade", "bar", "vertical")),
                false,
                false,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de barras simples de incidentes por severidade. "
                        + "Use a fonte Indicadores Incidentes e o campo Severidade. "
                        + "Nao crie tabela, filtros nem KPIs.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "dashboard", "create_artifact",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        decision))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(result.warnings()).containsExactly("ui-composition-plan-provider:generic-resource-chart");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("single-chart-page");
        assertThat(plan.path("widgets")).hasSize(1);
        assertThat(plan.path("widgets").findValuesAsText("componentId")).containsExactly("praxis-chart");
        assertThat(plan.toString())
                .contains("\"field\":\"severidade\"")
                .contains("\"type\":\"bar\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by\"")
                .doesNotContain("praxis-table")
                .doesNotContain("praxis-filter")
                .doesNotContain("kpi-band");
        assertThat(plan.path("compositionConstraints").path("mode").asText()).isEqualTo("single-chart");
    }

    @Test
    void createsOnlyTableWhenUserExplicitlyRejectsChartsKpisAndTabs() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "tabbed-resource-workspace",
                "tabs",
                "praxis-tabs",
                List.of(axis("severity", "severidade", "Severidade", "bar", "vertical")),
                false,
                false,
                List.of("praxis-tabs", "praxis-chart", "praxis-filter", "praxis-rich-content"),
                false,
                false,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie apenas uma tabela de detalhes de incidentes com a fonte Indicadores Incidentes. "
                        + "Nao crie graficos, filtros, KPIs nem abas.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "table", "create_artifact",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        decision))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(result.warnings()).containsExactly("ui-composition-plan-provider:generic-resource-table");
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("single-table-page");
        assertThat(plan.path("widgets")).hasSize(1);
        assertThat(plan.path("widgets").path(0).path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(plan.toString())
                .doesNotContain("praxis-tabs")
                .doesNotContain("praxis-chart")
                .doesNotContain("praxis-filter")
                .doesNotContain("kpi");
    }

    @Test
    void normalizesTimeseriesCandidateToBusinessResourceButPreservesTimeseriesStatsOperation() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie grafico temporal de incidentes por ocorrido em",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries", List.of(axis(
                        "incidentDate",
                        "ocorridoEm",
                        "Ocorrido em",
                        "line",
                        "temporal"))))).orElseThrow();

        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"resourcePath\":\"/api/risk-intelligence/vw-indicadores-incidentes\"")
                .contains("\"schemaUrl\":\"/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response\"")
                .contains("\"submitUrl\":\"/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor\"")
                .contains("\"statsOperation\":\"timeseries\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries\"")
                .contains("\"granularity\":\"MONTH\"")
                .contains("\"fillGaps\":false")
                .contains("\"type\":\"time\"")
                .doesNotContain("\"resourcePath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries\"");
    }

    @Test
    void bindsChartSeriesMetricToStatsOutputAlias() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard de pagamentos por departamento somando salario liquido",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/human-resources/vw-analytics-folha-pagamento", List.of(new AgenticAuthoringVisualizationAxisDecision(
                        "department",
                        "departamento",
                        "Departamento",
                        "bar",
                        "vertical",
                        "sum",
                        "salario_liquido",
                        "Salario liquido",
                        "llm-authored-semantic-axis"))))).orElseThrow();

        String widgets = result.uiCompositionPlan().path("widgets").toString();
        assertThat(widgets)
                .contains("\"metric\":{\"field\":\"salarioLiquido\",\"aggregation\":\"sum\",\"label\":\"Total\"}")
                .contains("\"metric\":{\"operation\":\"SUM\",\"field\":\"salarioLiquido\",\"alias\":\"salarioLiquido\"}")
                .contains("\"metrics\":[{\"aggregation\":\"sum\",\"field\":\"salarioLiquido\",\"alias\":\"salarioLiquido\"}]");
    }

    @Test
    void foldsMetricOnlyAxisIntoRequestedGroupingAxis() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um grafico horizontal de folha por departamento somando salario liquido",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/human-resources/vw-analytics-folha-pagamento", List.of(
                        axis("dimension", "departamento", "Departamento", "horizontal-bar", "horizontal"),
                        new AgenticAuthoringVisualizationAxisDecision(
                                "metric",
                                "salarioLiquido",
                                "Salario liquido",
                                "horizontal-bar",
                                "horizontal",
                                "sum",
                                "salarioLiquido",
                                "Soma do salario liquido",
                                "llm-authored-semantic-axis"))))).orElseThrow();

        String widgets = result.uiCompositionPlan().path("widgets").toString();
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-rich-content", "praxis-filter", "praxis-chart", "praxis-table");
        assertThat(widgets)
                .contains("\"field\":\"departamento\"")
                .contains("\"metric\":{\"field\":\"salarioLiquido\",\"aggregation\":\"sum\",\"label\":\"Total\"}")
                .contains("\"statsRequest\":{\"filter\":{},\"field\":\"departamento\"")
                .contains("\"metrics\":[{\"aggregation\":\"sum\",\"field\":\"salarioLiquido\",\"alias\":\"salarioLiquido\"}]")
                .doesNotContain("\"statsRequest\":{\"filter\":{},\"field\":\"salarioLiquido\"");
    }

    @Test
    void requiresSchemaGroundedAxisForGenericChartRequestWithoutLlmAxes() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Quero graficos sobre pedidos por status",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "dashboard", "create_artifact", "/api/acme/orders",
                        new AgenticAuthoringVisualizationDecision(
                                "praxis-agentic-authoring-visualization-decision.v1",
                                "generic-chart-dashboard",
                                "dashboard",
                                "praxis-chart",
                                List.of(),
                                true,
                                true,
                                "llm-authored-semantic-decision")))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-rich-content", "praxis-filter", "praxis-table");
        assertThat(plan.path("canvas").path("items").path("orders-table").path("row").asInt())
                .isEqualTo(4);
        assertThat(plan.path("widgets").toString()).doesNotContain("\"field\":\"unresolved\"");
        assertThat(plan.path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"unresolved\"")
                .contains("\"schemaVerified\":false")
                .contains("\"schemaProbeStatus\":\"pending\"")
                .contains("\"provenance\":\"schema-grounding-required\"");
    }

    @Test
    void rejectsPromptScaffoldAsDashboardAxis() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie dashboard de incidentes com KPIs, graficos, filtros e tabela",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/risk-intelligence/vw-indicadores-incidentes", List.of(axis(
                        "table",
                        "tabela_crie_dashboard_de_incidentes",
                        "Tabela Crie Dashboard De Incidentes",
                        "bar",
                        "vertical"))))).orElseThrow();

        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"field\":\"unresolved\"")
                .contains("\"provenance\":\"schema-grounding-required\"")
                .doesNotContain("Tabela Crie Dashboard De Incidentes")
                .doesNotContain("tabela_crie_dashboard_de_incidentes");
        assertThat(result.uiCompositionPlan().toString()).doesNotContain("kpi-band");
        JsonNode filterInputs = findWidgetInputs(result.uiCompositionPlan(), "praxis-filter");
        assertThat(filterInputs.path("selectedFieldIds")).isEmpty();
    }

    @Test
    void materializesDashboardFromSemanticDecisionWhenLegacySelectedCandidateIsMissing() {
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "resource-backed-dashboard",
                "dashboard",
                "praxis-chart",
                List.of(axis("department", "departamento", "Departamento", "bar", "vertical")),
                true,
                true,
                "semantic-decision-memory");
        AgenticAuthoringIntentResolutionResult intent = intentWithoutSelectedCandidate(
                "create",
                "dashboard",
                "create_artifact",
                new AgenticAuthoringSemanticDecision(
                        AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                        "decision-payroll-dashboard",
                        "create",
                        "dashboard",
                        "create_artifact",
                        new AgenticAuthoringSemanticDecision.SelectedResource(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "post",
                                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=post&schemaType=response",
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "POST"),
                        visualizationDecision,
                        null,
                        null,
                        true,
                        "keyword-fallback-fail-safe",
                        "",
                        "",
                        "conversation-1",
                        "turn-3",
                        "criar painel de pagamentos",
                        "dashboard de pagamentos",
                        "create:dashboard:create_artifact",
                        "resource-backed-dashboard",
                        objectMapper.createObjectNode(),
                        null,
                        "decision-previous",
                        "semantic decision selected the payroll analytics resource",
                        0.50d),
                visualizationDecision);

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Gerar previa governada",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent)).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .contains("praxis-chart", "praxis-table");
        assertThat(plan.path("widgets").toString())
                .contains("\"resourcePath\":\"/api/human-resources/vw-analytics-folha-pagamento\"")
                .contains("\"statsEndpointInference\":\"canonical-resource-stats-group-by\"");
        assertRuntimeInputsDoNotContainGovernanceEvidence(plan);
    }

    @Test
    void honorsSelectedResourceInsteadOfSwitchingToRelatedAnalyticalCandidateForCharts() {
        AgenticAuthoringCandidate selectedPeople = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter",
                "POST",
                0.82d,
                "LLM selected the people resource from governed evidence.",
                List.of("semantic-retrieval", "llm-selected-resource"));
        AgenticAuthoringCandidate relatedPayrollAnalytics = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/filter",
                "POST",
                0.93d,
                "Related analytical source with stats support.",
                List.of("semantic-retrieval", "stats group by"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "people-by-department",
                "dashboard",
                "praxis-chart",
                List.of(axis("department", "departamento", "Departamento", "bar", "vertical")),
                true,
                true,
                "llm-authored-semantic-decision");
        AgenticAuthoringIntentResolutionResult intent = intentWithCandidates(
                "create",
                "dashboard",
                "create_artifact",
                selectedPeople,
                List.of(relatedPayrollAnalytics, selectedPeople),
                visualizationDecision);

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um dashboard de funcionarios por departamento",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent)).orElseThrow();

        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"resourcePath\":\"/api/human-resources/funcionarios\"")
                .contains("\"statsPath\":\"/api/human-resources/funcionarios/stats/group-by\"")
                .contains("\"field\":\"departamento\"")
                .doesNotContain("/api/human-resources/vw-analytics-folha-pagamento");
    }


    @Test
    void ignoresFormIntentSoMinimalFormCompilerCanOwnIt() {
        assertThat(provider.plan(new AgenticAuthoringPlanRequest(
                "Crie um formulario",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "form", "create_minimal_form", "/api/acme/orders")))).isEmpty();
    }

    @Test
    void createsGovernedTabsPageWhenPromptAsksForTabsEvenIfPrimaryComponentIsChart() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "tabbed-resource-workspace",
                "dashboard",
                "praxis-chart",
                List.of(),
                true,
                true,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina com duas abas para listar e ver detalhes",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "page", "create_artifact", "/api/acme/orders", decision))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-tabs-page");
        assertThat(plan.path("warnings").isMissingNode()).isTrue();
        assertThat(result.warnings()).containsExactly("ui-composition-plan-provider:generic-resource-page");
        JsonNode tabsWidget = plan.path("widgets").path(0);
        assertThat(tabsWidget.path("componentId").asText()).isEqualTo("praxis-tabs");
        assertThat(tabsWidget.path("inputs").path("configPersistenceStrategy").asText()).isEqualTo("input-first");
        assertThat(tabsWidget.path("inputs").path("config").path("tabs")).hasSize(2);
        assertThat(tabsWidget.toString())
                .contains("\"id\":\"praxis-table\"")
                .contains("\"id\":\"praxis-dynamic-form\"")
                .contains("\"resourcePath\":\"/api/acme/orders\"")
                .doesNotContain("human-resources")
                .doesNotContain("payroll");
        assertThat(plan.path("canvas").path("items").path("orders-tabs").path("colSpan").asInt()).isEqualTo(12);
    }

    @Test
    void includesChartInsideTabsWhenTabbedPromptAsksForAnalyticalTab() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "tabbed chart workspace",
                "tabs-with-chart",
                "praxis-tabs",
                List.of(axis("severity", "severidade", "Severidade", "bar", "vertical")),
                true,
                true,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina com duas abas: uma aba com grafico de incidentes por severidade "
                        + "e outra aba com detalhes de incidentes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "page", "create_artifact",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        decision))).orElseThrow();

        JsonNode tabsWidget = result.uiCompositionPlan().path("widgets").path(0);
        assertThat(tabsWidget.path("inputs").path("configPersistenceStrategy").asText()).isEqualTo("input-first");
        JsonNode tabs = tabsWidget.path("inputs").path("config").path("tabs");
        assertThat(tabs).hasSize(2);
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Grafico", "Detalhes");
        JsonNode chart = tabs.path(0).path("widgets").path(0);
        assertThat(chart.path("id").asText()).isEqualTo("praxis-chart");
        assertThat(chart.path("inputs").has("componentInstanceId")).isFalse();
        assertThat(chart.path("inputs").path("config").toString())
                .contains("\"field\":\"severidade\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by\"")
                .contains("\"statsEndpointInference\":\"canonical-resource-stats-group-by\"");
        assertThat(tabs.path(1).path("widgets").path(0).path("id").asText()).isEqualTo("praxis-table");
        assertThat(result.uiCompositionPlan().toString())
                .contains("praxis-chart")
                .contains("praxis-table")
                .doesNotContain("human-resources")
                .doesNotContain("payroll");
    }

    @Test
    void keepsListAndDetailTabsWhenAxesDescribeNonChartTabMetadata() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "pagina de funcionarios com abas lista e detalhes",
                "tabs",
                "praxis-tabs",
                List.of(axis("primary_label", "nomeCompleto", "Nome do funcionario", "bar", "vertical")),
                false,
                false,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina de funcionarios com duas abas: lista de pessoas e detalhes do funcionario.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "page", "create_artifact", "/api/human-resources/funcionarios", decision))).orElseThrow();

        JsonNode tabs = result.uiCompositionPlan()
                .path("widgets")
                .path(0)
                .path("inputs")
                .path("config")
                .path("tabs");
        assertThat(tabs).extracting(tab -> tab.path("textLabel").asText())
                .containsExactly("Lista", "Detalhes");
        assertThat(tabs.toString())
                .contains("praxis-table", "praxis-dynamic-form")
                .doesNotContain("praxis-chart");
    }

    @Test
    void createsGovernedExpansionPageWhenDecisionSelectsAccordionComponent() {
        AgenticAuthoringVisualizationDecision decision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "resource accordion workspace",
                "accordion",
                "praxis-expansion",
                List.of(),
                false,
                true,
                "llm-authored-semantic-decision");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie uma pagina com accordion: dados gerais, detalhes e acoes de funcionarios.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                intent("create", "page", "create_artifact", "/api/human-resources/funcionarios", decision))).orElseThrow();

        JsonNode plan = result.uiCompositionPlan();
        assertThat(plan.path("layoutPreset").asText()).isEqualTo("resource-expansion-page");
        assertThat(result.warnings()).containsExactly("ui-composition-plan-provider:generic-resource-page");
        JsonNode expansion = plan.path("widgets").path(0);
        assertThat(expansion.path("componentId").asText()).isEqualTo("praxis-expansion");
        assertThat(expansion.path("inputs").path("strictValidation").asBoolean()).isTrue();
        JsonNode panels = expansion.path("inputs").path("config").path("panels");
        assertThat(panels).hasSize(3);
        assertThat(panels).extracting(panel -> panel.path("title").asText())
                .containsExactly("Dados gerais", "Detalhes", "Acoes");
        assertThat(panels.toString())
                .contains("praxis-rich-content")
                .contains("praxis-table")
                .contains("praxis-dynamic-form")
                .contains("/api/human-resources/funcionarios")
                .doesNotContain("payroll")
                .doesNotContain("quickstart");
        assertThat(plan.path("canvas").path("items").path("funcionarios-expansion").path("colSpan").asInt())
                .isEqualTo(12);
    }

    @Test
    void modifiesExistingChartTypeFromComponentCapabilityAction() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Altere o gráfico selecionado para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent())).orElseThrow();

        assertThat(result.uiCompositionPlan()).isNull();
        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-chart-modification");
        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("series").get(0).path("type").asText()).isEqualTo("line");
        assertThat(result.compiledFormPatch().path("compatibility").path("publicResponseKind").asText())
                .isEqualTo("patch");
    }

    @Test
    void modifiesOnlyExistingChartWhenTargetKeyDiffersFromRuntimeWidgetKey() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "runtime-generated-chart-key");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Altere o gráfico selecionado para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent())).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
    }

    @Test
    void modifiesExistingChartWhenIntentArtifactIsChart() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Trocar para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent("chart"))).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-chart-modification");
    }

    @Test
    void modifiesExistingChartTypeFromCanonicalCapabilityChangeKind() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Altere o grafico selecionado para linhas, mantendo os dados atuais.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent("chart", "praxis-chart", "praxis-chart.type.set@0.1.0"))).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("series").get(0).path("type").asText()).isEqualTo("line");
        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-chart-modification");
    }

    @Test
    void modifiesExistingChartWhenContextualActionTargetsPageBuilder() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Trocar para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent("dashboard", "praxis-dynamic-page-builder"))).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
    }

    @Test
    void surfaceOpenModalModificationEnablesChartSelectionAndAddsGlobalActionComposition() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode inputs = definition.putObject("inputs");
        ObjectNode config = inputs.putObject("config");
        config.put("type", "bar");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("resourcePath", "/api/risk-intelligence/vw-indicadores-incidentes");
        dataSource.put("schemaUrl", "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes");
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", "severidade").put("label", "Severidade");
        config.putArray("series").addObject().put("type", "bar");
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("kind", "contextual-preview-action");
        contextHints.put("surfaceActionId", "surface.open");
        contextHints.put("surfacePresentation", "modal");
        contextHints.put("surfaceWidgetId", "praxis-table");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Abra os registros da categoria selecionada do gráfico em um modal de detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent("chart", "praxis-chart", "enable_chart_drilldown"),
                null,
                null,
                null,
                null,
                null,
                contextHints)).orElseThrow();

        JsonNode patchedPage = result.compiledFormPatch().path("patch").path("page");
        JsonNode chartConfig = patchedPage.path("widgets").get(0)
                .path("definition").path("inputs").path("config");
        JsonNode link = patchedPage.path("composition").path("links").get(0);

        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-chart-surface-open-modification");
        assertThat(chartConfig.path("interactions").path("pointClick").asBoolean()).isTrue();
        assertThat(chartConfig.path("interactions").path("selection").asBoolean()).isTrue();
        assertThat(patchedPage.path("widgets").get(0).path("definition").path("outputs").path("pointClick").path("type").asText())
                .isEqualTo("surface.open");
        assertThat(patchedPage.path("widgets").get(0).path("definition").path("outputs").path("pointClick")
                .path("params").path("bindings").get(0).path("to").asText())
                .isEqualTo("widget.inputs.queryContext.filters.severidade");
        assertThat(patchedPage.path("widgets").get(0).path("definition").path("outputs").path("selectionChange").asText())
                .isEqualTo("emit");
        assertThat(link.path("from").path("ref").path("port").asText()).isEqualTo("pointClick");
        assertThat(link.path("to").path("ref").path("actionId").asText()).isEqualTo("surface.open");
        assertThat(link.path("to").path("ref").path("payload").path("presentation").asText()).isEqualTo("modal");
    }

    @Test
    void llmResolvedChartDrilldownModificationDoesNotRequireContextualActionHints() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode inputs = definition.putObject("inputs");
        ObjectNode config = inputs.putObject("config");
        config.put("type", "bar");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("resourcePath", "/api/risk-intelligence/vw-indicadores-incidentes");
        dataSource.put("schemaUrl", "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes");
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", "severidade").put("label", "Severidade");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Abra os registros da categoria selecionada do gráfico em um modal de detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                page,
                chartModificationIntent("chart", "praxis-chart", "enable_chart_drilldown"))).orElseThrow();

        JsonNode patchedChart = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition");
        JsonNode link = result.compiledFormPatch().path("patch").path("page")
                .path("composition").path("links").get(0);

        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-chart-surface-open-modification");
        assertThat(patchedChart.path("outputs").path("pointClick").path("type").asText())
                .isEqualTo("surface.open");
        assertThat(patchedChart.path("outputs").path("pointClick").path("params").path("bindings").get(0).path("to").asText())
                .isEqualTo("widget.inputs.queryContext.filters.severidade");
        assertThat(link.path("from").path("ref").path("port").asText()).isEqualTo("pointClick");
        assertThat(link.path("to").path("ref").path("actionId").asText()).isEqualTo("surface.open");
    }

    @Test
    void modifiesPreviewChartWhenHostCurrentPageIsNotMaterializedYet() {
        ObjectNode previewPage = objectMapper.createObjectNode();
        ObjectNode widget = previewPage.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.set("previewPage", previewPage);

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Trocar para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                objectMapper.createObjectNode(),
                chartModificationIntent("dashboard", "praxis-dynamic-page-builder"),
                null,
                null,
                null,
                null,
                null,
                contextHints)).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
    }

    @Test
    void modifiesTargetWidgetSnapshotWhenPageSnapshotIsNotAvailable() {
        ObjectNode widget = objectMapper.createObjectNode();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("source", "component-capability-catalog");
        contextHints.put("kind", "contextual-preview-action");
        contextHints.put("operationKind", "modify");
        contextHints.put("artifactKind", "chart");
        contextHints.put("changeKind", "set_chart_type");
        contextHints.put("targetComponentId", "praxis-chart");
        contextHints.put("targetWidgetKey", "incidentes-chart-severidade");
        contextHints.set("targetWidgetSnapshot", widget);

        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Trocar para linhas",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                objectMapper.createObjectNode(),
                chartModificationIntent("chart", "praxis-chart"),
                null,
                null,
                null,
                null,
                null,
                contextHints)).orElseThrow();

        JsonNode chartConfig = result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config");
        assertThat(chartConfig.path("type").asText()).isEqualTo("line");
        assertThat(chartConfig.path("series").get(0).path("type").asText()).isEqualTo("line");
    }

    private AgenticAuthoringIntentResolutionResult dashboardIntent(
            String resourcePath,
            List<AgenticAuthoringVisualizationAxisDecision> axes) {
        return intent("create", "dashboard", "create_artifact", resourcePath, new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "operational-monitoring-dashboard",
                "dashboard",
                "praxis-chart",
                axes,
                true,
                true,
                "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult intent(
            String operationKind,
            String artifactKind,
            String changeKind,
            String resourcePath) {
        return intent(operationKind, artifactKind, changeKind, resourcePath, null);
    }

    private AgenticAuthoringIntentResolutionResult intent(
            String operationKind,
            String artifactKind,
            String changeKind,
            String resourcePath,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                artifactKind,
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        resourcePath,
                        "post",
                        "/schemas/filtered?path=" + resourcePath + "/filter&operation=post&schemaType=response",
                        resourcePath + "/filter",
                        "POST",
                        0.92d,
                        "selected resource candidate",
                        List.of("api-metadata", "semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                visualizationDecision);
    }

    private AgenticAuthoringIntentResolutionResult chartModificationIntent() {
        return chartModificationIntent("dashboard");
    }

    private AgenticAuthoringIntentResolutionResult chartModificationIntent(String artifactKind) {
        return chartModificationIntent(artifactKind, "praxis-chart");
    }

    private AgenticAuthoringIntentResolutionResult chartModificationIntent(String artifactKind, String targetComponentId) {
        return chartModificationIntent(artifactKind, targetComponentId, "set_chart_type");
    }

    private AgenticAuthoringIntentResolutionResult chartModificationIntent(
            String artifactKind,
            String targetComponentId,
            String changeKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                artifactKind,
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "incidentes-chart-severidade",
                        targetComponentId,
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "",
                        "",
                        ""),
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.92d,
                        "selected resource candidate",
                        List.of("api-metadata", "semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult intentWithCandidates(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                artifactKind,
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                selectedCandidate,
                candidates,
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                visualizationDecision);
    }

    private AgenticAuthoringIntentResolutionResult intentWithoutSelectedCandidate(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                artifactKind,
                changeKind,
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                visualizationDecision,
                semanticDecision);
    }

    private AgenticAuthoringVisualizationAxisDecision axis(
            String concept,
            String field,
            String label,
            String chartType,
            String orientation) {
        return new AgenticAuthoringVisualizationAxisDecision(
                concept,
                field,
                label,
                chartType,
                orientation,
                "count",
                null,
                "Total",
                "llm-authored-semantic-axis");
    }

    private JsonNode findWidgetInputs(JsonNode plan, String componentId) {
        return findWidgetInputs(plan, componentId, "");
    }

    private JsonNode findWidgetInputs(JsonNode plan, String componentId, String role) {
        return findWidget(plan, componentId, role).path("inputs");
    }

    private JsonNode findWidget(JsonNode plan, String componentId, String role) {
        for (JsonNode widget : plan.path("widgets")) {
            if (componentId.equals(widget.path("componentId").asText())
                    && (role == null || role.isBlank() || role.equals(widget.path("role").asText()))) {
                return widget;
            }
        }
        throw new AssertionError("Widget not found: " + componentId);
    }

    private void assertRuntimeInputsDoNotContainGovernanceEvidence(JsonNode plan) {
        assertThat(plan.path("widgets").toString())
                .doesNotContain("schemaVerification")
                .doesNotContain("schemaEvidenceSource")
                .doesNotContain("schemaEvidenceUrl");
    }

    private JsonNode findBinding(JsonNode bindings, String id) {
        for (JsonNode binding : bindings) {
            if (id.equals(binding.path("id").asText())) {
                return binding;
            }
        }
        throw new AssertionError("Binding not found: " + id);
    }
}
