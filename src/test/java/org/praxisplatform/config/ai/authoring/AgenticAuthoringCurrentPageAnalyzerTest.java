package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringCurrentPageAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringCurrentPageAnalyzer analyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);

    @Test
    void inspectsCurrentPageStructureWithoutInferringBusinessPolicy() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode formWidget = widgets.addObject();
        formWidget.put("key", "funcionarios-form");
        ObjectNode definition = formWidget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");
        ObjectNode config = inputs.putObject("config");
        var fieldMetadata = config.putArray("fieldMetadata");
        fieldMetadata.addObject()
                .put("name", "nome")
                .put("label", "Nome")
                .put("controlType", "text");
        fieldMetadata.addObject()
                .put("name", "observacaoInterna")
                .put("label", "Observacao interna")
                .put("controlType", "textarea")
                .put("source", "local")
                .put("transient", true)
                .put("submitPolicy", "omit");

        var inspection = analyzer.inspect(page, "funcionarios-form");

        assertThat(inspection.path("artifactKind").asText()).isEqualTo("form");
        assertThat(inspection.path("componentType").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(inspection.path("boundResource").asText()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(inspection.path("widgets").path(0).path("artifactKind").asText()).isEqualTo("form");
        assertThat(inspection.path("editableRegions")).extracting(node -> node.path("region").asText())
                .containsExactly("form.fields", "form.layout");
        assertThat(inspection.path("fields")).extracting(node -> node.path("name").asText())
                .containsExactly("nome", "observacaoInterna");
        assertThat(inspection.path("fields").path(0).path("binding").asText()).isEqualTo("server");
        assertThat(inspection.path("fields").path(1).path("binding").asText()).isEqualTo("transient");
        assertThat(inspection.path("serverBindings").path(0).path("submitUrl").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(inspection.path("transientBindings").path(0).path("fieldName").asText())
                .isEqualTo("observacaoInterna");
        assertThat(inspection.has("domainRule")).isFalse();
        assertThat(inspection.has("policy")).isFalse();
    }

    @Test
    void summaryKeepsLegacyFormWidgetsAndAddsStructuralInspection() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode chartWidget = widgets.addObject();
        chartWidget.put("key", "payroll-chart");
        ObjectNode definition = chartWidget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode dataSource = definition.putObject("inputs")
                .putObject("config")
                .putObject("dataSource");
        dataSource.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");

        var summary = analyzer.summarize(page);

        assertThat(summary.path("widgetsCount").asInt()).isEqualTo(1);
        assertThat(summary.path("formWidgets").isArray()).isTrue();
        assertThat(summary.path("structuralInspection").path("artifactKind").asText())
                .isEqualTo("dashboard");
        assertThat(summary.path("structuralInspection").path("widgets").path(0).path("artifactKind").asText())
                .isEqualTo("dashboard");
        assertThat(summary.path("structuralInspection").path("serverBindings").path(0).path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void exposesTabsActiveContentAsEditableContainerContext() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode tabsWidget = widgets.addObject();
        tabsWidget.put("key", "team-workspace");
        ObjectNode definition = tabsWidget.putObject("definition");
        definition.put("id", "praxis-tabs");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.putObject("group").put("selectedIndex", 1);
        var tabs = config.putArray("tabs");
        tabs.addObject()
                .put("id", "overview")
                .put("textLabel", "Overview")
                .putArray("widgets");
        tabs.addObject()
                .put("id", "training")
                .put("textLabel", "Training")
                .putArray("widgets");

        var inspection = analyzer.inspect(page, "team-workspace");

        assertThat(inspection.path("artifactKind").asText()).isEqualTo("container");
        assertThat(inspection.path("componentType").asText()).isEqualTo("praxis-tabs");
        assertThat(inspection.path("editableRegions")).extracting(node -> node.path("region").asText())
                .containsExactly("tabs.items", "tabs.activeContent");
        var widgetSummary = inspection.path("widgets").path(0);
        assertThat(widgetSummary.path("selectedIndex").asInt()).isEqualTo(1);
        assertThat(widgetSummary.path("activeTab").path("id").asText()).isEqualTo("training");
        assertThat(widgetSummary.path("activeTab").path("label").asText()).isEqualTo("Training");
        assertThat(widgetSummary.path("activeTab").path("widgetsCount").asInt()).isZero();
    }
}
