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
        assertThat(plan.path("bindings")).isEmpty();
        assertThat(result.orElseThrow().warnings())
                .contains("ui-composition-plan-provider:quickstart-payroll-table");
        assertThat(result.orElseThrow().compiledFormPatch().path("patch").isObject()).isTrue();
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
}
