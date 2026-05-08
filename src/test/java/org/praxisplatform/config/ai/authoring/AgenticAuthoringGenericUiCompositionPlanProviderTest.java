package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertThat(plan.path("widgets")).hasSize(5);
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
                        "praxis-rich-content",
                        "praxis-filter",
                        "praxis-chart",
                        "praxis-table");
        assertThat(plan.path("widgets").toString())
                .contains("praxis-chart")
                .contains("praxis-filter")
                .contains("kpi-band")
                .contains("praxis-table")
                .contains("/api/acme/orders")
                .contains("\"statsEndpointInference\":\"canonical-resource-stats-group-by\"")
                .doesNotContain("human-resources")
                .doesNotContain("payroll")
                .doesNotContain("quickstart");
        assertThat(plan.path("bindings").toString())
                .contains("orders-filter.requestSearch->orders-chart-status.queryContext")
                .contains("orders-chart-status.selectionChange->orders-table.queryContext");
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
        assertThat(plan.path("widgets")).hasSize(7);
        assertThat(plan.path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
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
        assertThat(tableInputs.has("schemaUrl")).isFalse();
        assertThat(tableInputs.has("submitUrl")).isFalse();
        assertThat(tableInputs.has("submitMethod")).isFalse();
        assertThat(plan.path("diagnostics").path("semanticAxes").toString())
                .contains("\"schemaVerified\":false")
                .contains("\"schemaProbeStatus\":\"pending\"");
        assertThat(plan.path("bindings").toString())
                .contains("incidentes-filter.requestSearch->incidentes-chart-gravidade.queryContext")
                .contains("incidentes-chart-gravidade.selectionChange->incidentes-table.queryContext");
    }

    @Test
    void normalizesAnalyticsCandidateToBusinessResourceForDashboardMaterialization() {
        AgenticAuthoringUiCompositionPlanResult result = provider.plan(new AgenticAuthoringPlanRequest(
                "Crie graficos de incidentes por gravidade",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                dashboardIntent("/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries", List.of(axis(
                        "severity",
                        "gravidade",
                        "Gravidade",
                        "bar",
                        "vertical"))))).orElseThrow();

        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"resourcePath\":\"/api/risk-intelligence/vw-indicadores-incidentes\"")
                .contains("\"schemaUrl\":\"/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response\"")
                .contains("\"submitUrl\":\"/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by\"")
                .doesNotContain("\"resourcePath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries\"");
    }

    @Test
    void infersTraceablePendingAxisForGenericChartRequestWithoutLlmAxes() {
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
        assertThat(plan.path("widgets").findValuesAsText("componentId")).contains("praxis-chart");
        assertThat(plan.path("widgets").toString())
                .contains("\"field\":\"status\"")
                .contains("\"schemaVerified\":false")
                .contains("\"schemaProbeStatus\":\"pending\"")
                .contains("\"provenance\":\"generic-dashboard-field-inference\"");
        assertThat(plan.path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"status\"")
                .contains("\"schemaVerified\":false");
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
        for (JsonNode widget : plan.path("widgets")) {
            if (componentId.equals(widget.path("componentId").asText())) {
                return widget.path("inputs");
            }
        }
        throw new AssertionError("Widget not found: " + componentId);
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
