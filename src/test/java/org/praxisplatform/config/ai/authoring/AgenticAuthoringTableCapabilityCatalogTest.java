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
class AgenticAuthoringTableCapabilityCatalogTest {

    private final AgenticAuthoringTableCapabilityCatalog catalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tableCapabilityCatalogConformsToPublicSchema() throws Exception {
        JsonNode schemaNode = objectMapper.readTree(Path.of(
                "..",
                "docs",
                "ai",
                "agentic-authoring",
                "contracts",
                "component-capability-catalog.v1.schema.json").toFile());
        JsonNode catalogNode;
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("ai-authoring/table-capabilities.v0.json")) {
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
    void declaresSupportedTableModificationChangeKinds() {
        assertThat(catalog.version()).isEqualTo("0.1.0");
        assertThat(catalog.componentId()).isEqualTo("praxis-table");
        assertThat(catalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilityCatalog.ComponentCapability::changeKind)
                .containsExactly(
                        "rename_or_relabel",
                        "set_column_format",
                        "set_column_visibility",
                        "set_column_order");
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
    void exposesUsageExamplesForEveryTableCapability() {
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
    void resolvesTableChangeKindFromDeclarativeTriggers() {
        assertThat(catalog.resolveChangeKind("formate a coluna salario liquido como moeda em reais"))
                .contains("set_column_format");
        assertThat(catalog.resolveChangeKind("oculte a coluna total descontos da tabela"))
                .contains("set_column_visibility");
        assertThat(catalog.resolveChangeKind("mova a coluna salario liquido para o inicio"))
                .contains("set_column_order");
    }

    @Test
    void resolvesTableChangeKindsFromLongDocumentationStylePrompts() {
        assertThat(catalog.resolveChangeKind(
                "Na praxis-table gerada pelo dashboard de folha, aplique o recurso documentado de formatacao para exibir salario liquido como moeda em reais sem alterar a origem de dados."))
                .contains("set_column_format");
        assertThat(catalog.resolveChangeKind(
                "Para reduzir ruido no detalhamento, esconda a coluna de total de descontos quando o gestor estiver olhando o drill down por departamento."))
                .contains("set_column_visibility");
        assertThat(catalog.resolveChangeKind(
                "reordene a tabela de detalhes para colocar salario bruto antes de descontos e liquido, preservando as demais colunas do componente."))
                .contains("set_column_order");
    }

    @Test
    void resolvesCanonicalPayrollColumnFieldsFromAliases() {
        assertThat(catalog.resolveField("set_column_format", "salario liquido em reais"))
                .contains("salarioLiquido");
        assertThat(catalog.resolveField("set_column_format", "liquido em reais"))
                .contains("salarioLiquido");
        assertThat(catalog.resolveField("set_column_visibility", "oculte descontos"))
                .contains("totalDescontos");
        assertThat(catalog.resolveField("set_column_order", "mova a coluna bruto"))
                .contains("salarioBruto");
    }
}
