package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;

@Tag("unit")
class DomainKnowledgeHumanResourcesSemanticPilotTest {

    private static final Path PILOT = Path.of(
            "docs",
            "ai",
            "agentic-authoring",
            "proofs",
            "human-resources-semantic-pilot-change-set.v0.1.json");
    private static final Set<String> SEMANTIC_CLAIM_OPERATIONS = Set.of(
            "create_concept", "add_alias", "add_binding", "add_relationship");
    private static final Path SEMANTIC_RELATIONSHIP_MIGRATION = Path.of(
            "src",
            "main",
            "resources",
            "db",
            "migration",
            "V38__expand_domain_knowledge_semantic_ir_relationships.sql");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void referencePilotIsAnExecutableGovernedChangeSetWithUniqueClaimProvenance() throws Exception {
        DomainKnowledgeChangeSetCreateRequest request =
                objectMapper.readValue(PILOT.toFile(), DomainKnowledgeChangeSetCreateRequest.class);

        var validation = new DomainKnowledgeChangeSetValidator()
                .validateCreateRequest("desenv", "local", request);

        assertThat(validation.valid()).isTrue();
        assertThat(validation.errorCount()).isZero();
        assertThat(validation.nonExecutableOperationTypes()).isEmpty();
        assertThat(validation.executablePatchOperationTypes())
                .containsExactlyInAnyOrder(
                        "create_concept",
                        "add_alias",
                        "add_binding",
                        "add_relationship",
                        "add_evidence");
        assertThat(request.status()).isEqualTo("proposed");
        assertThat(request.authorType()).isEqualTo("system");
        assertThat(request.patch()).hasSize(11);

        Set<String> claimIds = new HashSet<>();
        request.patch().stream()
                .filter(operation -> SEMANTIC_CLAIM_OPERATIONS.contains(operation.operationType()))
                .forEach(operation -> {
                    JsonNode provenance = operation.payload().path("provenance");
                    assertThat(provenance.path("sourceClass").asText()).isEqualTo("authored");
                    assertThat(provenance.path("sourceRefs")).isNotEmpty();
                    assertThat(provenance.path("derivationActivity").asText()).isNotBlank();
                    assertThat(provenance.path("agent").path("id").asText()).isNotBlank();
                    assertThat(claimIds.add(provenance.path("claimId").asText()))
                            .as("claimId must be unique for operation %s", operation.operationId())
                            .isTrue();
                });

        assertThat(claimIds).hasSize(8);
    }

    @Test
    void migrationAcceptsEveryRelationshipTypeExposedByTheSemanticClaimValidator() throws Exception {
        String migration = Files.readString(SEMANTIC_RELATIONSHIP_MIGRATION);

        assertThat(DomainKnowledgeChangeSetValidator.relationshipTypes())
                .allSatisfy(relationshipType ->
                        assertThat(migration).contains("'" + relationshipType + "'"));
    }
}
