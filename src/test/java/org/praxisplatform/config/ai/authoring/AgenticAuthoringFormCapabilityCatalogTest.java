package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringFormCapabilityCatalogTest {

    private final AgenticAuthoringFormCapabilityCatalog catalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void formCapabilityCatalogConformsToPublicSchema() throws Exception {
        JsonNode schemaNode = objectMapper.readTree(AgenticAuthoringTestPaths
                .contract("component-capability-catalog.v1.schema.json")
                .toFile());
        JsonNode catalogNode;
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("ai-authoring/form-capabilities.v0.json")) {
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
    void declaresSupportedFormModificationChangeKinds() {
        assertThat(catalog.version()).isEqualTo("0.1.0");
        assertThat(catalog.componentId()).isEqualTo("praxis-dynamic-form");
        assertThat(catalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilityCatalog.ComponentCapability::changeKind)
                .containsExactly(
                        "add_field",
                        "rename_or_relabel",
                        "remove_field");
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
    void exposesUsageExamplesForEveryFormCapability() {
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
    void resolvesFormChangeKindFromDeclarativeTriggers() {
        assertThat(catalog.resolveChangeKind("adicione o campo observacao interna"))
                .contains("add_field");
        assertThat(catalog.resolveChangeKind("renomeie o campo nome completo"))
                .contains("rename_or_relabel");
        assertThat(catalog.resolveChangeKind("remova o campo observacao interna"))
                .contains("remove_field");
    }

    @Test
    void resolvesFormChangeKindsFromLongDocumentationStylePrompts() {
        assertThat(catalog.resolveChangeKind(
                "No praxis-dynamic-form, use a capacidade de adicionar campo para incluir observacao interna no final do formulario sem mexer nos campos obrigatorios atuais."))
                .contains("add_field");
        assertThat(catalog.resolveChangeKind(
                "Ajuste o label exibido para o usuario: o campo nome completo deve aparecer como nome do funcionario nas telas de cadastro e revisao."))
                .contains("rename_or_relabel");
        assertThat(catalog.resolveChangeKind(
                "remova observacao interna do formulario porque esse dado deixou de fazer parte do processo documentado para abertura do registro."))
                .contains("remove_field");
    }

    @Test
    void resolvesCanonicalFormFieldsFromAliases() {
        assertThat(catalog.resolveField("add_field", "adicione observacao interna"))
                .contains("observacaoInterna");
        assertThat(catalog.resolveField("rename_or_relabel", "renomeie nome do funcionario"))
                .contains("nomeCompleto");
        assertThat(catalog.resolveField("remove_field", "remova observacao interna"))
                .contains("observacaoInterna");
    }
}
