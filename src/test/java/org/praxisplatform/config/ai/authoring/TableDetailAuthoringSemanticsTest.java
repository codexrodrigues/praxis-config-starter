package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TableDetailAuthoringSemanticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgenticAuthoringEffectCompilerRegistry registry = new AgenticAuthoringEffectCompilerRegistry(
            mapper, new AgenticAuthoringTargetResolverRegistry());

    private ObjectNode config() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"columns":[],"extension":{"keep":true},"behavior":{"detail":{"source":{"mode":"inline",
                "inlineSchema":{"layout":"stack","items":[{"type":"value","field":"note","extension":42}]}}}}}
                """);
    }

    private List<String> compile(ObjectNode config, String operationId, String input) throws Exception {
        ObjectNode operation = mapper.createObjectNode().put("operationId", operationId);
        operation.putObject("target").put("required", false);
        operation.putArray("effects").addObject().put("kind", "compile-domain-patch").put("handler", "table-detail-configure").put("path", "behavior");
        ObjectNode plan = mapper.createObjectNode();
        plan.set("input", mapper.readTree(input));
        var patches = mapper.createArrayNode();
        var failures = new ArrayList<String>();
        registry.appendCompiledEffects("praxis-table", operation, plan, config, patches, failures, new ArrayList<>());
        if (failures.isEmpty()) {
            assertThat(patches).hasSize(1);
            assertThat(patches.get(0).path("domainHandler").asText()).isEqualTo("table-detail-configure");
            assertThat(patches.get(0).path("value")).isEqualTo(config.path("behavior"));
        } else assertThat(patches).isEmpty();
        return failures;
    }

    @Test
    void couplesBottomAndPreservesTheWholeAuthoredDocument() throws Exception {
        ObjectNode config = config();
        JsonNode original = config.deepCopy();
        assertThat(compile(config, "detail.presentation.configure", "{\"placement\":\"bottom\"}")).isEmpty();
        assertThat(config.path("behavior").path("expansion").path("enabled").asBoolean()).isFalse();
        assertThat(config.path("behavior").path("selection").path("type").asText()).isEqualTo("single");
        assertThat(config.path("behavior").path("selection").path("enabled").asBoolean()).isTrue();
        assertThat(config.path("behavior").path("detail").path("source")).isEqualTo(original.path("behavior").path("detail").path("source"));
        assertThat(config.path("extension")).isEqualTo(original.path("extension"));
        ((ObjectNode) config.path("behavior").path("selection")).put("type", "multiple");
        assertThat(compile(config, "detail.presentation.configure", "{\"placement\":\"bottom\"}")).isEmpty();
        assertThat(config.path("behavior").path("selection").path("type").asText()).isEqualTo("multiple");
    }

    @Test
    void sourceExtensionsCannotActivatePresentationAndMalformedStructuresFailAtomically() throws Exception {
        ObjectNode config = config();
        assertThat(compile(config, "detail.source.configure", "{\"mode\":\"inline\",\"presentation\":{\"placement\":\"bottom\"}}")).isEmpty();
        assertThat(config.path("behavior").path("detail").has("presentation")).isFalse();
        assertThat(config.path("behavior").has("selection")).isFalse();
        for (String input : List.of("{\"presentation\":[]}", "{\"source\":{\"mode\":\"typo\"}}", "{\"enabled\":\"false\"}")) {
            JsonNode before = config.deepCopy();
            assertThat(compile(config, "detail.configure", input)).isNotEmpty();
            assertThat(config).isEqualTo(before);
        }
        assertThat(compile(config, "detail.presentation.configure", "{\"initiallyCollapsed\":true,\"extension\":{\"preserve\":true}}")).isEmpty();
        assertThat(config.path("behavior").path("detail").path("presentation").path("extension").path("preserve").asBoolean()).isTrue();
    }

    @Test
    void expansionAndDisableResolveActivationWithoutErasingContent() throws Exception {
        ObjectNode config = config();
        assertThat(compile(config, "detail.presentation.configure", "{\"placement\":\"bottom\"}")).isEmpty();
        assertThat(compile(config, "expansion.configure", "{\"enabled\":true}")).isEmpty();
        assertThat(config.path("behavior").path("detail").path("presentation").path("placement").asText()).isEqualTo("row");
        assertThat(compile(config, "detail.configure", "{\"enabled\":false}")).isEmpty();
        assertThat(config.path("behavior").path("expansion").path("enabled").asBoolean()).isFalse();
        assertThat(config.path("behavior").path("detail").path("source").path("inlineSchema").path("items")).hasSize(1);
    }

    @Test
    void rejectsInvalidPlacementAndHeightAtomically() throws Exception {
        for (String input : List.of("{\"presentation\":{\"placement\":\"sideways\"}}", "{\"presentation\":{\"placement\":\"bottom\"},\"height\":{\"px\":0}}")) {
            ObjectNode config = config();
            JsonNode before = config.deepCopy();
            assertThat(compile(config, "detail.configure", input)).isNotEmpty();
            assertThat(config).isEqualTo(before);
        }
    }

    @Test
    void preservesSparseExpansionAndInitialCollapseWithoutActivationDefaults() throws Exception {
        ObjectNode config = (ObjectNode) mapper.readTree("{\"columns\":[]}");
        assertThat(compile(config, "expansion.configure", "{\"enabled\":false}")).isEmpty();
        assertThat(config.path("behavior").has("detail")).isFalse();
        assertThat(compile(config, "detail.presentation.configure", "{\"initiallyCollapsed\":true}")).isEmpty();
        assertThat(config.path("behavior").path("detail").has("enabled")).isFalse();
        assertThat(config.path("behavior").has("selection")).isFalse();
    }
}
