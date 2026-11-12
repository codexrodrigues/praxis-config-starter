package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/** Explicit cross-checkout G1 gate; no host startup, persistence, or runtime v2 registration. */
@Tag("unit")
class DomainRuleCuratedHostCandidateContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> curatedOperationsConformToTheSingleCandidateProfile() throws Exception {
        String configured = System.getProperty("ontology.g1.host-contracts");
        assumeTrue(configured != null, "Run the cross-checkout gate with -Dontology.g1.host-contracts=<curated-directory>");
        Path directory = Path.of(configured);
        JsonNode manifest = JSON.readTree(directory.resolve("manifest.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode family : manifest.path("families")) {
            JsonNode catalog = JSON.readTree(directory.resolve(family.path("catalog").asText()).toFile());
            JsonNode fixtures = JSON.readTree(directory.resolve(family.path("fixtures").asText()).toFile());
            tests.add(DynamicTest.dynamicTest(family.path("resourceKey").asText() + " catalog", () -> {
                assertThat(Files.size(directory.resolve(family.path("catalog").asText()))).isLessThanOrEqualTo(1_024_000);
                assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
            }));
            assertThat(catalog.path("operations").size()).isEqualTo(fixtures.size());
            for (JsonNode operation : catalog.path("operations")) {
                String key = operation.path("operationKey").asText();
                JsonNode fixture = null;
                for (JsonNode candidate : fixtures) {
                    if (key.equals(candidate.path("operationKey").asText())) fixture = candidate;
                }
                assertThat(fixture).as(key).isNotNull();
                JsonNode positive = fixture.path("positive");
                ObjectNode single = JSON.createObjectNode().put("schemaVersion", catalog.path("schemaVersion").asText());
                single.putArray("operations").add(operation);
                tests.add(DynamicTest.dynamicTest(key + " values and negative boundaries", () -> {
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, positive)).as(key).isEmpty();
                    ObjectNode missing = positive.deepCopy();
                    missing.remove("current");
                    rejects(single, missing, "missing is not null");
                    ObjectNode wrongScope = positive.deepCopy();
                    wrongScope.withObject("/context").put("operation", "other-operation");
                    rejects(single, wrongScope, "operation isolation");
                    ObjectNode date = positive.deepCopy();
                    date.withObject("/context").put("referenceDate", "2026-02-30");
                    rejects(single, date, "civil calendar");
                    ObjectNode alias = positive.deepCopy();
                    alias.putObject("facts");
                    rejects(single, alias, "legacy root");
                    ObjectNode old = positive.deepCopy();
                    if (positive.path("current").isNull()) old.putObject("current");
                    else old.putNull("current");
                    rejects(single, old, "OLD availability");
                    if (positive.path("current").isObject()) {
                        ObjectNode oldWithoutIdentity = positive.deepCopy();
                        oldWithoutIdentity.withObject("/current").putNull("id");
                        rejects(single, oldWithoutIdentity, "persisted identity");
                    }
                    for (String pointer : List.of("/draft/titularId", "/draft/substitutoId",
                            "/draft/substituto1Id", "/substituto/id")) {
                        if (positive.at(pointer).isObject()) {
                            ObjectNode partial = positive.deepCopy();
                            ((ObjectNode) partial.at(pointer)).remove("numero");
                            rejects(single, partial, "incomplete composite key " + pointer);
                        }
                    }
                    if (family.path("entity").asText().equals("VagaFuncao")
                            && positive.at("/neighborhood/designacoes").isArray()) {
                        ObjectNode temporal = positive.deepCopy();
                        var act = temporal.withArray("/neighborhood/designacoes").removeAll().addObject();
                        act.put("id", 103).put("empCodigo", 1).put("vagaFuncaoId", 102)
                                .put("situacao", "EM_EXERCICIO").put("dtini", "2026-01-01")
                                .putNull("dtfim").put("dtExercicio", "2026-01-02");
                        act.putObject("titularId").put("numfunc", 1001).put("numero", 2);
                        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(single, temporal)).isEmpty();
                        for (String field : List.of("dtini", "dtfim", "dtExercicio")) {
                            ObjectNode missingDate = temporal.deepCopy();
                            ((ObjectNode) missingDate.at("/neighborhood/designacoes/0")).remove(field);
                            rejects(single, missingDate, "missing occupancy evidence " + field);
                            ObjectNode invalidDate = temporal.deepCopy();
                            ((ObjectNode) invalidDate.at("/neighborhood/designacoes/0")).put(field, "2026-02-30");
                            rejects(single, invalidDate, "invalid occupancy date " + field);
                        }
                        ObjectNode facade = temporal.deepCopy();
                        facade.withObject("/neighborhood").put("titularProjetado", 1001);
                        rejects(single, facade, "native projected-holder alias is not candidate authority");
                    }
                }));
            }
        }
        return tests.stream();
    }

    private static void rejects(JsonNode catalog, JsonNode value, String reason) {
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog, value))
                .as(reason).isNotEmpty();
    }
}

