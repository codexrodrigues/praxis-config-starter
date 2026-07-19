package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DomainCatalogContractSynchronizationTest {

    private static final Path DOCUMENTARY_SCHEMA = Path.of(
            "docs", "domain-catalog", "contracts", "praxis-domain-catalog-v0.2.schema.json");
    private static final Path RUNTIME_SCHEMA = Path.of(
            "src", "main", "resources", "domain-catalog", "contracts", "praxis-domain-catalog-v0.2.schema.json");
    private static final Path BINDING_CONSTRAINT_MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V39__align_domain_knowledge_binding_constraint_with_catalog_v02.sql");
    private static final Path RELATIONSHIP_CONSTRAINT_MIGRATION = Path.of(
            "src", "main", "resources", "db", "migration",
            "V40__align_domain_knowledge_relationship_constraint_with_catalog_v02.sql");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void documentaryAndPackagedSchemasRemainSemanticallyIdentical() throws Exception {
        JsonNode documentarySchema = objectMapper.readTree(DOCUMENTARY_SCHEMA.toFile());
        JsonNode runtimeSchema = objectMapper.readTree(RUNTIME_SCHEMA.toFile());

        assertThat(documentarySchema).isEqualTo(runtimeSchema);
    }

    @Test
    void v02KeepsGeneratedStatsAndOptionSourceProjectionVocabulary() throws Exception {
        JsonNode schema = objectMapper.readTree(RUNTIME_SCHEMA.toFile());

        assertThat(values(schema.at("/$defs/node/properties/nodeType/enum")))
                .contains("stats", "policy_hint");
        assertThat(values(schema.at("/$defs/edge/properties/edgeType/enum")))
                .contains("has_stats", "uses_concept", "selectable_when", "blocked_when");
        assertThat(values(schema.at("/$defs/binding/properties/bindingType/enum")))
                .contains("stats_endpoint", "option_source");
        assertThat(values(schema.at("/$defs/evidence/properties/evidenceType/enum")))
                .contains("openapi_stats", "option_source");
    }

    @Test
    void databaseConstraintAcceptsGeneratedBindingVocabulary() throws Exception {
        JsonNode schema = objectMapper.readTree(RUNTIME_SCHEMA.toFile());
        String migration = java.nio.file.Files.readString(BINDING_CONSTRAINT_MIGRATION);

        assertThat(values(schema.at("/$defs/binding/properties/bindingType/enum")))
                .allSatisfy(bindingType -> assertThat(migration).contains("'" + bindingType + "'"));
    }

    @Test
    void databaseConstraintAcceptsGeneratedRelationshipVocabulary() throws Exception {
        JsonNode schema = objectMapper.readTree(RUNTIME_SCHEMA.toFile());
        String migration = java.nio.file.Files.readString(RELATIONSHIP_CONSTRAINT_MIGRATION);

        assertThat(values(schema.at("/$defs/edge/properties/edgeType/enum")))
                .allSatisfy(edgeType -> assertThat(migration).contains("'" + edgeType + "'"));
    }

    private Set<String> values(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
