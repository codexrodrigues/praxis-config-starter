package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reconciled G1 candidate contracts, without registering a production validator or using JDBC. */
@Tag("unit")
class DomainRuleG1SharedTreeReconciliationTest {
    @Test void functionalActionsAreClosedPerProfileAndPreserveTheSecondaryMigration() throws Exception {
        int profiles=0;
        for(String family:List.of("cargos-por-setor","setores-ergon"))
            for(JsonNode operation:catalog(family).path("operations")) {
                profiles++;
                var scoped=JSON.createObjectNode();scoped.putArray("operations").add(operation);
                ObjectNode value=(ObjectNode)DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
                value.withObject("/context").putNull("action");
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped,value)).isEmpty();
                value.withObject("/context").put("action","migrar-e-encerrar-setor-extinto");
                var errors=DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped,value);
                if(operation.path("operationKey").asText().endsWith(":update")
                        || family.equals("cargos-por-setor") && operation.path("operationKey").asText().endsWith(":create"))assertThat(errors).isEmpty();
                else assertThat(errors).isNotEmpty();
                value.withObject("/context").put("action","forged-action");
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped,value)).isNotEmpty();
            }
        assertThat(profiles).isEqualTo(6);
    }

    @Test void migrationContextHasOneCanonicalActionAndCivilEffectDate() throws Exception {
        for (JsonNode operation : catalog("cargos-por-setor").path("operations")) {
            var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
            ObjectNode value = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
            value.withObject("/context").putNull("destinationSetor");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isEmpty();
            for (String alias : List.of("actionId", "transferDate", "transferDestination", "transferParentId", "transferEffect")) {
                var invalid = value.deepCopy(); invalid.withObject("/context").putNull(alias);
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, invalid)).as(alias).isNotEmpty();
            }
            var invalid = value.deepCopy(); invalid.withObject("/context").remove("destinationSetor");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, invalid)).isNotEmpty();
            invalid = value.deepCopy(); invalid.withObject("/context").put("effectDate", 20500);
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, invalid)).isNotEmpty();
        }
    }

    @Test void directoryPopulationsRejectMissingRowsAndPreserveAnEmptyLoadedResult() throws Exception {
        int profiles = 0;
        for (String family : List.of("grupos-setores", "subempresas-localidades"))
            for (JsonNode operation : catalog(family).path("operations")) {
                profiles++;
                var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
                ObjectNode base = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, base)).isEmpty();
                for (String population : List.of("unidadesLocais", "gruposSetor", "historicosSetor", "setoresDiretorioFisico")) {
                    var value = base.deepCopy(); value.withObject("/neighborhood").putArray(population);
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(population).isEmpty();
                    value.withObject("/neighborhood").withArray(population).addNull();
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(population).isNotEmpty();
                    value = base.deepCopy(); value.withObject("/neighborhood").putNull(population);
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(population).isNotEmpty();
                    value.withObject("/neighborhood").remove(population);
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(population).isNotEmpty();
                }
                var inventedConcept = base.deepCopy(); inventedConcept.putObject("subempresaPai").putArray("vigencias");
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, inventedConcept)).isNotEmpty();
            }
        assertThat(profiles).isEqualTo(6);
    }

    @Test void functionalOrganizationCannotForgeUnavailableHistoryOrIncompleteCollections() throws Exception {
        int profiles = 0;
        for (String family : List.of("cargos-por-setor", "setores-ergon"))
            for (JsonNode operation : catalog(family).path("operations")) {
                profiles++;
                var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
                ObjectNode base = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, base)).isEmpty();
                for (String source : family.equals("cargos-por-setor")
                        ? List.of("historicosCargo", "ocupacoesQuadro", "compromissosQuadro") : List.of("historicosCargo")) {
                    assertTemporalEnvelope(scoped, base, source);
                }
                var collections = base.path("neighborhood").fields();
                while (collections.hasNext()) {
                    var collection = collections.next();
                    if (!collection.getValue().isArray()) continue;
                    var value = base.deepCopy(); value.withObject("/neighborhood").putArray(collection.getKey());
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isEmpty();
                    value.withObject("/neighborhood").withArray(collection.getKey()).addNull();
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isNotEmpty();
                    value.withObject("/neighborhood").remove(collection.getKey());
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isNotEmpty();
                }
            }
        assertThat(profiles).isEqualTo(6);
    }

    @Test void organizationHistoryKeepsUnavailableSourcesDistinctFromEmptyPopulations() throws Exception {
        var candidate = catalog("historico-setores");
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(candidate)).isEmpty();
        assertThat(candidate.path("operations")).hasSize(4);
        for (JsonNode operation : candidate.path("operations")) {
            var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
            ObjectNode base = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, base)).isEmpty();
            var invalidPeriod = base.deepCopy();
            ((ObjectNode) invalidPeriod.at("/neighborhood/substituicoes/0")).putNull("dtfim");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, invalidPeriod)).isNotEmpty();
            var invalidZone = base.deepCopy(); invalidZone.withObject("/context").put("userTimeZone", "America/Invented");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, invalidZone)).isNotEmpty();
            for (String source : List.of("provimentos", "exercicios")) {
                assertTemporalEnvelope(scoped, base, source);
            }
            for (String collection : List.of("historicosSetor", "unidadesLocais", "parametrizacoesSetor",
                    "designacoes", "substituicoes", "cargosSetor", "vagasFuncao", "movimentosVaga")) {
                var value = base.deepCopy(); value.withObject("/neighborhood").putArray(collection);
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isEmpty();
                value.withObject("/neighborhood").withArray(collection).addNull();
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isNotEmpty();
                value.withObject("/neighborhood").remove(collection);
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).isNotEmpty();
            }
        }
    }

    /** Structural validation is not receipt, hash, scope or source-completeness admission. */
    private static void assertTemporalEnvelope(JsonNode scoped, ObjectNode base, String source) {
        String pointer = "/neighborhood/" + source;
        assertThat(base.at(pointer).isObject()).as(source).isTrue();
        assertThat(base.at("/context/fontesTemporais").isMissingNode()).isTrue();
        var unavailable = base.deepCopy(); unavailable.withObject("/neighborhood").putNull(source);
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, unavailable)).isEmpty();
        var empty = base.deepCopy(); ((ObjectNode) empty.at(pointer)).putArray("registros");
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, empty)).isEmpty();
        var missing = base.deepCopy(); missing.withObject("/neighborhood").remove(source);
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, missing)).isNotEmpty();
        for (String required : List.of("lote", "selecao", "registros")) {
            var incomplete = base.deepCopy(); ((ObjectNode) incomplete.at(pointer)).remove(required);
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, incomplete)).as(required).isNotEmpty();
        }
        var forged = base.deepCopy(); forged.withObject("/neighborhood").putArray(source);
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, forged)).isNotEmpty();
        forged = base.deepCopy(); forged.withObject("/context/fontesTemporais").put(source, "AVAILABLE");
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, forged)).isNotEmpty();
        var nullRow = base.deepCopy(); ((ObjectNode) nullRow.at(pointer)).putArray("registros").addNull();
        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, nullRow)).isNotEmpty();
    }

    @Test void temporalSourceFixturesSeparateSchemaValidityFromSourceAdmission() throws Exception {
        int cases = 0;
        for (JsonNode source : read("temporal-source-fixtures.json").path("cases")) {
            String family = source.path("family").asText();
            JsonNode operation = null;
            for (JsonNode candidate : catalog(family).path("operations"))
                if (candidate.path("operationKey").equals(source.path("operationKey"))) operation = candidate;
            assertThat(operation).as(source.path("operationKey").asText()).isNotNull();
            var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
            ObjectNode base = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
            for (JsonNode example : source.path("schemaCases")) {
                cases++;
                ObjectNode snapshot = base.deepCopy();
                snapshot.withObject("/neighborhood").set(source.path("source").asText(), example.path("value"));
                var errors = DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, snapshot);
                assertThat(errors.isEmpty()).as(family + "/" + source.path("source").asText() + "/" + example.path("id").asText())
                        .isEqualTo(example.path("schemaValid").asBoolean());
            }
            for (String kind : List.of("missingRootCase", "retiredStatusCase")) {
                cases++;
                JsonNode example = source.path(kind);
                assertThat(example.isObject()).as(kind).isTrue();
                ObjectNode snapshot = base.deepCopy();
                String path = example.path(example.has("removePath") ? "removePath" : "addPath").asText();
                int split = path.lastIndexOf('/');
                assertThat(split).as(path).isGreaterThan(0);
                ObjectNode parent = snapshot.withObject(path.substring(0, split));
                String field = path.substring(split + 1);
                if (example.has("removePath")) parent.remove(field);
                else parent.set(field, example.path("value"));
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, snapshot).isEmpty())
                        .as(family + "/" + source.path("source").asText() + "/" + kind)
                        .isEqualTo(example.path("schemaValid").asBoolean());
            }
        }
        assertThat(cases).isEqualTo(427);
    }

    @Test void customFieldConfigurationSnapshotsPreserveGlobalScopeAndRequiredOrder() throws Exception {
        int cases = 0;
        for (JsonNode profile : read("campos-customizados.additional-cases.json").path("profiles"))
            for (JsonNode example : profile.path("cases")) {
                cases++;
                assertThat(errors("campos-customizados", example.path("facts")).isEmpty())
                    .as(profile.path("operationKey").asText() + "/" + example.path("id").asText())
                    .isEqualTo(example.path("schemaValid").asBoolean());
            }
        assertThat(cases).isEqualTo(22);
    }

    @Test void persistedParameterNullsArePresentValuesNotMissingDataOrDefaults() throws Exception {
        int checked = 0;
        for (String family : List.of("parametros-contagem-ferias", "ferias-gozo", "periodos-aquisitivos-ferias",
                "licencas-especiais-gozo", "periodos-aquisitivos-licenca-especial", "vendas-licenca-especial"))
            for (JsonNode operation : catalog(family).path("operations")) {
                ObjectNode witness = (ObjectNode) DomainRuleG1CompletionContractTest.witness(operation.path("snapshotSchema"));
                for (JsonNode fact : operation.path("facts")) {
                    String pointer = fact.path("schemaPointer").asText();
                    JsonNode shape = operation.path("snapshotSchema").at(pointer.substring(1));
                    boolean parameter = fact.path("evidenceRefs").toString().contains("ParametroContagemFerias")
                            || pointer.endsWith("/regimeClt") && (family.equals("parametros-contagem-ferias") || pointer.contains("/parametrosContagem/"));
                    if (!parameter || !shape.path("type").isArray() || !shape.path("type").get(0).asText().equals("boolean")) continue;
                    checked++;
                    String valuePointer = pointer.substring(1).replace("/properties/", "/").replace("/items", "/0");
                    int split = valuePointer.lastIndexOf('/');
                    ObjectNode value = witness.deepCopy();
                    ObjectNode parent = (ObjectNode) value.at(valuePointer.substring(0, split));
                    String field = valuePointer.substring(split + 1);
                    var scoped = JSON.createObjectNode(); scoped.putArray("operations").add(operation);
                    parent.putNull(field);
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(pointer).isEmpty();
                    parent.put(field, "N");
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(pointer).isNotEmpty();
                    parent.remove(field);
                    assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, value)).as(pointer).isNotEmpty();
                }
            }
        assertThat(checked).isGreaterThanOrEqualTo(193);
    }

    @Test void localParameterCoordinatesAndDecimalFactsKeepTheirDistinctWireTypes() throws Exception {
        int timestamps = 0, decimals = 0;
        for (String family : List.of("parametros-contagem-ferias", "ferias-gozo", "periodos-aquisitivos-ferias",
                "licencas-especiais-gozo", "periodos-aquisitivos-licenca-especial", "vendas-licenca-especial",
                "operational-previa-fracao-abono")) {
            for (JsonNode operation : catalog(family).path("operations")) {
                for (JsonNode fact : operation.path("facts")) {
                    String pointer = fact.path("schemaPointer").asText();
                    JsonNode shape = operation.path("snapshotSchema").at(pointer.substring(1));
                    var sample = JSON.createObjectNode();
                    sample.putArray("operations").addObject().putObject("snapshotSchema")
                            .put("type", "object").putArray("required").add("value");
                    ((ObjectNode) sample.at("/operations/0/snapshotSchema"))
                            .putObject("properties").set("value", shape);
                    if (shape.path("format").asText().equals("praxis-local-date-time")) {
                        timestamps++;
                        for (String valid : List.of("2024-02-29T00:00:00", "2024-03-10T08:15:03.123456"))
                            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(sample,
                                    JSON.createObjectNode().put("value", valid))).as(pointer).isEmpty();
                        for (String invalid : List.of("2024-02-30T00:00:00", "2024-03-10", "2024-03-10T08:15",
                                "2024-03-10T24:00:00", "2024-03-10T08:15:03Z", "2024-03-10T08:15:03-03:00"))
                            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(sample,
                                    JSON.createObjectNode().put("value", invalid))).as(pointer + ": " + invalid).isNotEmpty();
                    } else if (List.of("numDiasAbonoFer", "minDiasPeraqfer", "maxDiasPeraqfer", "numDiasAbonoLicEsp")
                            .stream().anyMatch(field -> pointer.endsWith("/properties/" + field))) {
                        decimals++;
                        assertThat(shape.path("type").toString()).contains("number").doesNotContain("integer");
                        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(sample,
                                JSON.createObjectNode().put("value", new java.math.BigDecimal("0.125")))).isEmpty();
                        assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(sample,
                                JSON.createObjectNode().put("value", "0.125"))).isNotEmpty();
                    }
                }
            }
        }
        assertThat(timestamps).isEqualTo(44);
        assertThat(decimals).isEqualTo(75);
    }

    @Test void parameterOrdinalsAreRequiredNullableServerOwnedCalculations() throws Exception {
        var value = facts("parametros-contagem-ferias");
        for (String field : List.of("dtiniOrdinal", "dtfimOrdinal")) {
            value.withObject("/draft").putNull(field);
            assertThat(errors("parametros-contagem-ferias", value)).isEmpty();
            value.withObject("/draft").put(field, new java.math.BigDecimal("1704067200.123456"));
            assertThat(errors("parametros-contagem-ferias", value)).isEmpty();
            var missing = value.deepCopy(); missing.withObject("/draft").remove(field);
            assertThat(errors("parametros-contagem-ferias", missing)).isNotEmpty();
            var wrongType = value.deepCopy(); wrongType.withObject("/draft").put(field, "1704067200");
            assertThat(errors("parametros-contagem-ferias", wrongType)).isNotEmpty();
        }
        for (JsonNode profile : read("parametros-contagem-ferias.operation-bindings.candidate.json").path("profiles")) {
            assertThat(profile.path("serverOwnedProjection").isEmpty()).isFalse();
            profile.path("serverOwnedProjection").forEach(binding -> assertThat(binding.asText())
                    .contains("RECOMPUTE_AFTER_HYDRATION", "REJECT_CLIENT_VALUE", "NO_ZONE"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"afastamentos", "dependentes", "frequencias", "vinculos"})
    void documentAdministrativeOwnerIsNullableButCannotBeOmittedOrReplaceMembership(String family) throws Exception {
        int checked = 0;
        for (JsonNode fixture : read(family + ".fixtures.json")) {
            ObjectNode facts = fixture.path("positive").deepCopy();
            if (!facts.has("documentoLegal")) continue;
            checked++;
            var doc = facts.putObject("documentoLegal");
            doc.put("id", 901).putNull("empCodigo").put("tipoDocumento", "LEI");
            assertThat(errors(family, facts)).isEmpty();
            doc.put("empCodigo", "2");
            assertThat(errors(family, facts)).isNotEmpty();
            doc.remove("empCodigo");
            assertThat(errors(family, facts)).isNotEmpty();
            doc.putNull("empCodigo");
            doc.putArray("orgaosHabilitados").add(1).add(2);
            assertThat(errors(family, facts)).as("membership belongs to structural authorization, not published rule facts").isNotEmpty();
        }
        assertThat(checked).isEqualTo(3);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode read(String file) throws Exception {
        String root = System.getProperty("ontology.g1.host-contracts");
        assumeTrue(root != null, "Requires the captured host candidate contracts");
        return JSON.readTree(Path.of(root, "completion", file).toFile());
    }
    private static JsonNode catalog(String family) throws Exception { return read(family + ".fact-catalog.candidate.json"); }
    private static ObjectNode facts(String family) throws Exception { return read(family + ".fixtures.json").get(1).path("positive").deepCopy(); }
    private static List<String> errors(String family, JsonNode facts) throws Exception {
        var scoped = JSON.createObjectNode().put("schemaVersion", "praxis.domain-rule-fact-catalog.v2");
        var operations = scoped.putArray("operations");
        for (JsonNode operation : catalog(family).path("operations"))
            if (operation.path("operationKey").equals(facts.at("/context/operation"))) operations.add(operation);
        assertThat(operations).hasSize(1);
        return DomainRuleFactCatalogCandidateContractTest.snapshotErrors(scoped, facts);
    }

    @Test void historyIsRequiredTypedServerOwnedDataButEmptyLoadedHistoryIsLegitimate() throws Exception {
        var facts = facts("dependentes");
        facts.withObject("/current").putArray("iniciosHistorico").add("2020-02-29").add("2022-01-01");
        assertThat(errors("dependentes", facts)).isEmpty();
        facts.withObject("/current").putArray("iniciosHistorico");
        assertThat(errors("dependentes", facts)).isEmpty();
        for (String malformed : List.of("null", "{}", "[null]", "[42]", "[\"2020-02-30\"]")) {
            var invalid = facts.deepCopy();
            invalid.withObject("/current").set("iniciosHistorico", JSON.readTree(malformed));
            assertThat(errors("dependentes", invalid)).as(malformed).isNotEmpty();
        }
        facts.withObject("/current").remove("iniciosHistorico");
        assertThat(errors("dependentes", facts)).isNotEmpty();
        for (JsonNode op : catalog("dependentes").path("operations")) {
            assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog("dependentes"))).isEmpty();
            for (JsonNode fact : op.path("facts"))
                if (fact.path("schemaPointer").asText().contains("iniciosHistorico"))
                    assertThat(fact.path("redaction").asText()).isEqualTo("OMIT");
        }
    }

    @Test void historySharesTheSnapshotBudgetAndCannotBeSilentlyTruncated() throws Exception {
        var facts = facts("dependentes");
        var history = facts.withObject("/current").putArray("iniciosHistorico");
        for (int i = 0; i < 20000; i++) history.add("2020-01-01");
        assertThat(errors("dependentes", facts)).anyMatch(e -> e.startsWith("engine fact limits:"));
    }

    @Test void calendarAndRegimeFactsRemainTypedAndDoNotRestoreFacadePredicates() throws Exception {
        var dependent = facts("dependentes");
        dependent.withObject("/draft").put("aniversario120", "2140-02-29");
        assertThat(errors("dependentes", dependent)).isEmpty();
        dependent.withObject("/draft").put("aniversario120", "2140-02-30");
        assertThat(errors("dependentes", dependent)).isNotEmpty();
        var vacation = facts("ferias-gozo");
        vacation.withObject("/context").put("executarMaxPeriod", false);
        assertThat(errors("ferias-gozo", vacation)).isEmpty();
        vacation.withObject("/context").put("executarMaxPeriod", "false");
        assertThat(errors("ferias-gozo", vacation)).isNotEmpty();
        var parameter = facts("parametros-contagem-ferias");
        parameter.withObject("/draft").put("regimeClt", true);
        assertThat(errors("parametros-contagem-ferias", parameter)).isEmpty();
        parameter.withObject("/draft").put("regimeClt", "CLT");
        assertThat(errors("parametros-contagem-ferias", parameter)).isNotEmpty();
    }

    @Test void vacationMatricesAreTenantOwnedAndPredicatesRemainInTheRuleSet() throws Exception {
        int checked = 0;
        for (String family : List.of("ferias-gozo", "periodos-aquisitivos-ferias", "parametros-contagem-ferias",
                "licencas-especiais-gozo", "periodos-aquisitivos-licenca-especial", "vendas-licenca-especial"))
            for (JsonNode profile : read(family + ".operation-bindings.candidate.json").path("profiles"))
                for (JsonNode binding : profile.path("collectionBindings"))
                    if (binding.path("targetResourceKey").asText().equals("administracao-pessoal.parametros-contagem-ferias")) {
                        checked++;
                        assertThat(binding.path("scope").asText()).isEqualTo("TENANT");
                        assertThat(binding.path("predicateOwner").asText()).isEqualTo("RULESET");
                    }
        assertThat(checked).isGreaterThan(10);
    }

    @Test void serverOwnedProjectionsRespectNewAndOldAvailabilityForEachOperation() throws Exception {
        for (JsonNode profile : read("dependentes.operation-bindings.candidate.json").path("profiles")) {
            JsonNode operation = null;
            for (JsonNode item : catalog("dependentes").path("operations"))
                if (item.path("operationKey").equals(profile.path("operationKey"))) operation = item;
            assertThat(operation).isNotNull();
            var projection = profile.path("serverOwnedProjection");
            var fields = projection.fieldNames();
            while (fields.hasNext()) {
                String path = fields.next();
                JsonNode shape = operation.path("snapshotSchema");
                for (String segment : path.split("\\.")) {
                    shape = shape.path("properties").path(segment);
                    assertThat(shape.isMissingNode()).as(profile.path("operationKey") + ":" + path).isFalse();
                }
            }
            String key = profile.path("operationKey").asText();
            assertThat(projection.has("current.iniciosHistorico")).isEqualTo(key.endsWith(":update") || key.endsWith(":delete"));
            assertThat(projection.has("draft.aniversario120")).isEqualTo(!key.endsWith(":delete"));
        }
    }
}
