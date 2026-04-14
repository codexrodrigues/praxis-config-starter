package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgenticAuthoringChartCapabilityCatalogTest {

    private final AgenticAuthoringComponentCapabilityCatalog catalog =
            AgenticAuthoringComponentCapabilityCatalog.load("ai-authoring/chart-capabilities.v0.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chartCapabilityCatalogConformsToPublicSchema() throws Exception {
        JsonNode schemaNode = objectMapper.readTree(Path.of(
                "..",
                "docs",
                "ai",
                "agentic-authoring",
                "contracts",
                "component-capability-catalog.v1.schema.json").toFile());
        JsonNode catalogNode;
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("ai-authoring/chart-capabilities.v0.json")) {
            assertThat(inputStream).isNotNull();
            catalogNode = objectMapper.readTree(inputStream);
        }

        Set<ValidationMessage> errors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode)
                .validate(catalogNode);

        assertThat(errors).isEmpty();
    }

    @Test
    void declaresSupportedChartModificationChangeKinds() {
        assertThat(catalog.version()).isEqualTo("0.1.0");
        assertThat(catalog.componentId()).isEqualTo("praxis-chart");
        assertThat(catalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilityCatalog.ComponentCapability::changeKind)
                .containsExactly(
                        "set_chart_type",
                        "set_chart_metric",
                        "set_chart_dimension",
                        "set_chart_value_format",
                        "enable_chart_drilldown");
    }

    @Test
    void declaresUniqueCapabilityIds() {
        Set<String> ids = new HashSet<>();

        for (AgenticAuthoringComponentCapabilityCatalog.ComponentCapability capability : catalog.capabilities()) {
            assertThat(ids.add(capability.id()))
                    .as("duplicated capability id %s", capability.id())
                    .isTrue();
        }
    }

    @Test
    void resolvesChartChangeKindsFromDeclarativeTriggers() {
        assertThat(catalog.resolveChangeKind("troque o grafico para barra"))
                .contains("set_chart_type");
        assertThat(catalog.resolveChangeKind("use salario liquido como metrica"))
                .contains("set_chart_metric");
        assertThat(catalog.resolveChangeKind("agrupe por departamento"))
                .contains("set_chart_dimension");
        assertThat(catalog.resolveChangeKind("formate os valores em reais"))
                .contains("set_chart_value_format");
        assertThat(catalog.resolveChangeKind("crie drill down para filtrar o detalhe"))
                .contains("enable_chart_drilldown");
    }

    @Test
    void resolvesCanonicalChartFieldsFromAliases() {
        assertThat(catalog.resolveField("set_chart_type", "grafico de linha"))
                .contains("line");
        assertThat(catalog.resolveField("set_chart_metric", "metrica salario liquido"))
                .contains("salarioLiquido");
        assertThat(catalog.resolveField("set_chart_dimension", "agrupar por departamento"))
                .contains("departamento");
        assertThat(catalog.resolveField("set_chart_value_format", "valores em brl"))
                .contains("BRL|symbol|2");
        assertThat(catalog.resolveField("enable_chart_drilldown", "filtrar detalhe no clique"))
                .contains("selectionChange");
    }
}
