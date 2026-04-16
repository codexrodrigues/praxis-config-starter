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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
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
    void exposesUsageExamplesForEveryChartCapability() {
        for (AgenticAuthoringComponentCapabilityCatalog.ComponentCapability capability : catalog.capabilities()) {
            assertThat(capability.examples())
                    .as("examples for %s", capability.id())
                    .isNotEmpty();
            assertThat(capability.examples().get(0).prompt()).isNotBlank();
            assertThat(capability.examples().get(0).intent()).isNotBlank();
            assertThat(capability.examples().get(0).configHints()).isNotEmpty();
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
    void resolvesChartChangeKindsFromLongDocumentationStylePrompts() {
        assertThat(catalog.resolveChangeKind(
                "No componente praxis-chart, use a capacidade documentada de drill down para que o clique no departamento atualize a tabela de detalhes e mantenha o filtro cruzado da folha."))
                .contains("enable_chart_drilldown");
        assertThat(catalog.resolveChangeKind(
                "Para o dashboard executivo, configure o grafico para agrupar os dados da folha por departamento, porque o usuario precisa comparar centros de custo antes de abrir o detalhe."))
                .contains("set_chart_dimension");
        assertThat(catalog.resolveChangeKind(
                "Ajuste a metrica principal do chart para salario liquido, mantendo o restante da composicao sem alterar a tabela de detalhamento."))
                .contains("set_chart_metric");
    }

    @Test
    void prefersSpecificValueFormatCapabilityOverGenericChartTypeTrigger() {
        assertThat(catalog.resolveChangeKind("no grafico selecionado, formate o eixo y e os valores em moeda brl"))
                .contains("set_chart_value_format");
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
