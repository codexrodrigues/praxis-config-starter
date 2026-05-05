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
class AgenticAuthoringFilterCapabilityCatalogTest {

    private final AgenticAuthoringFilterCapabilityCatalog catalog = AgenticAuthoringFilterCapabilityCatalog.INSTANCE;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void filterCapabilityCatalogConformsToPublicSchema() throws Exception {
        JsonNode schemaNode = objectMapper.readTree(AgenticAuthoringTestPaths
                .contract("component-capability-catalog.v1.schema.json")
                .toFile());
        JsonNode catalogNode;
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("ai-authoring/filter-capabilities.v0.json")) {
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
    void declaresSupportedFilterChangeKinds() {
        assertThat(catalog.version()).isEqualTo("0.1.0");
        assertThat(catalog.componentId()).isEqualTo("praxis-filter");
        assertThat(catalog.capabilities())
                .extracting(AgenticAuthoringComponentCapabilityCatalog.ComponentCapability::changeKind)
                .containsExactly(
                        "recommend_search_fields",
                        "connect_filter_to_results");
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
    void exposesUsageExamplesForEveryFilterCapability() {
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
    void resolvesFilterChangeKindFromNonExpertSearchPrompts() {
        assertThat(catalog.resolveChangeKind("quero buscar pelos dados do registro"))
                .contains("recommend_search_fields");
        assertThat(catalog.resolveChangeKind("quero buscar registros e depois ver detalhes"))
                .contains("connect_filter_to_results");
    }

    @Test
    void doesNotShipHostBusinessFieldAliasesInGenericFilterCatalog() {
        assertThat(catalog.resolveField("recommend_search_fields", "dados do empregado"))
                .isEmpty();
        assertThat(catalog.resolveField("recommend_search_fields", "filtrar por area"))
                .isEmpty();
    }
}
