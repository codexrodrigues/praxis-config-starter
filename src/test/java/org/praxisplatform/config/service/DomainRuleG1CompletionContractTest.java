package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/** All remaining host contracts pass the same v2 candidate checker. No database. */
@Tag("unit")
class DomainRuleG1CompletionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> realOperationSchemasAndNestedWitnesses() throws Exception {
        String configured = System.getProperty("ontology.g1.host-contracts");
        assumeTrue(configured != null, "Cross-checkout candidate gate requires ontology.g1.host-contracts");
        Path directory = Path.of(configured).resolve("completion");
        JsonNode manifest = JSON.readTree(directory.resolve("manifest.json").toFile());
        assertThat(manifest.path("families")).hasSize(20);
        assertThat(manifest.path("operational")).hasSize(38);
        var entries = new ArrayList<JsonNode>();
        manifest.path("families").forEach(entries::add);
        manifest.path("operational").forEach(entries::add);
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode entry : entries) {
            Path file = directory.resolve(entry.path("catalog").asText());
            JsonNode catalog = JSON.readTree(file.toFile());
            JsonNode fixtures = JSON.readTree(directory.resolve(entry.path("fixtures").asText()).toFile());
            tests.add(DynamicTest.dynamicTest(file.getFileName()+" catalog", () -> {
                assertThat(Files.size(file)).isLessThanOrEqualTo(1_024_000);
                assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
            }));
            for (JsonNode operation : catalog.path("operations")) {
                String key = operation.path("operationKey").asText();
                var single = JSON.createObjectNode().put("schemaVersion", "praxis.domain-rule-fact-catalog.v2");
                single.putArray("operations").add(operation);
                JsonNode fixture = null;
                for (JsonNode row : fixtures) if (row.path("operationKey").asText().equals(key)) fixture = row;
                assertThat(fixture).as(key).isNotNull();
                JsonNode values = fixture.path("positive");
                JsonNode negativeBusiness = fixture.get("negativeBusiness");
                JsonNode negativeStructural = fixture.get("negativeStructural");
                tests.add(DynamicTest.dynamicTest(key+" values, nested items, missing/null and isolation", () -> {
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, values)).isEmpty();
                    if (negativeBusiness != null)
                        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, negativeBusiness))
                                .as("Business DENY must not be confused with a malformed snapshot").isEmpty();
                    if (negativeStructural != null)
                        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, negativeStructural))
                                .as("Malformed persisted facts must not be reported as business DENY").isNotEmpty();
                    // Populate every nullable object and every collection: null/empty-only fixtures
                    // cannot establish that nested descriptors describe actual values.
                    ObjectNode expanded = (ObjectNode) witness(operation.path("snapshotSchema"));
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, expanded)).isEmpty();
                    for (String root : List.of("current", "context", "draft")) {
                        ObjectNode missing = values.deepCopy(); missing.remove(root);
                        rejects(single, missing);
                    }
                    ObjectNode other = values.deepCopy(); other.withObject("/context").put("operation", "wrong");
                    rejects(single, other);
                    ObjectNode legacy = values.deepCopy(); legacy.putObject("facts"); rejects(single, legacy);
                    ObjectNode badDate = values.deepCopy(); badDate.withObject("/context").put("referenceDate", "2026-02-30");
                    rejects(single, badDate);
                    ObjectNode unknown = expanded.deepCopy(); unknown.put("unregistered", true); rejects(single, unknown);
                }));
            }
        }
        return tests.stream();
    }

    static JsonNode witness(JsonNode schema) {
        String type = schema.path("type").isArray() ? schema.path("type").get(0).asText() : schema.path("type").asText();
        if (schema.has("enum")) {
            for (JsonNode value : schema.path("enum")) if (!value.isNull()) return value.deepCopy();
            return JSON.nullNode();
        }
        return switch (type) {
            case "null" -> JSON.nullNode();
            case "boolean" -> JSON.getNodeFactory().booleanNode(false);
            case "integer" -> JSON.getNodeFactory().numberNode(1);
            case "number" -> JSON.getNodeFactory().numberNode(new java.math.BigDecimal("1.25"));
            case "string" -> JSON.getNodeFactory().textNode(switch(schema.path("format").asText()) {
                case "date" -> "2024-02-29";
                case "date-time" -> "2024-02-29T09:00:00-03:00";
                case "praxis-local-date-time" -> "2024-02-29T09:00:00.123456";
                case "praxis-local-time" -> "09:30:01.123";
                case "praxis-duration" -> "PT30M";
                case "praxis-zone-id" -> "America/Sao_Paulo";
                case "praxis-decimal" -> "1.00";
                default -> "A";
            });
            case "array" -> JSON.createArrayNode().add(witness(schema.path("items")));
            case "object" -> {
                ObjectNode result = JSON.createObjectNode();
                schema.path("properties").fields().forEachRemaining(e -> result.set(e.getKey(), witness(e.getValue())));
                yield result;
            }
            default -> throw new AssertionError(type);
        };
    }

    private static void rejects(JsonNode catalog, JsonNode value) {
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,value)).isNotEmpty();
    }
}
