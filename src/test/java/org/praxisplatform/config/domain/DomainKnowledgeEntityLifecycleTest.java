package org.praxisplatform.config.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DomainKnowledgeEntityLifecycleTest {

    @Test
    void conceptDefaultsMatchMigrationDefaults() {
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .conceptKey("human-resources.funcionarios.field.cpf")
                .nodeType("field")
                .build();

        concept.onInsert();

        assertThat(concept.getId()).isNotNull();
        assertThat(concept.getLifecycle()).isEqualTo("candidate");
        assertThat(concept.getCurationStatus()).isEqualTo("generated");
        assertThat(concept.getAiVisibility()).isEqualTo("allow");
        assertThat(concept.getPayload()).isEqualTo("{}");
        assertThat(concept.getCreatedAt()).isNotNull();
        assertThat(concept.getUpdatedAt()).isNotNull();
    }

    @Test
    void childEntitiesDefaultJsonAndAuditFields() {
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .conceptKey("operations.missoes")
                .nodeType("concept")
                .build();

        DomainKnowledgeAlias alias = DomainKnowledgeAlias.builder()
                .concept(concept)
                .alias("missao")
                .normalizedAlias("missao")
                .build();
        DomainKnowledgeBinding binding = DomainKnowledgeBinding.builder()
                .concept(concept)
                .bindingType("api_resource")
                .bindingKey("/api/operations/missoes")
                .build();
        DomainKnowledgeRelationship relationship = DomainKnowledgeRelationship.builder()
                .sourceConcept(concept)
                .targetConcept(concept)
                .relationshipType("same_as")
                .build();
        DomainKnowledgeEvidence evidence = DomainKnowledgeEvidence.builder()
                .evidenceKey("evidence:operations.missoes")
                .subjectType("concept")
                .subjectId(concept.getId())
                .evidenceType("catalog_release")
                .build();
        DomainKnowledgeChangeSet changeSet = DomainKnowledgeChangeSet.builder()
                .changeSetKey("change:operations.missoes")
                .authorType("llm")
                .build();

        alias.onInsert();
        binding.onInsert();
        relationship.onInsert();
        evidence.onInsert();
        changeSet.onInsert();

        assertThat(alias.getAliasType()).isEqualTo("synonym");
        assertThat(alias.getWeight()).isEqualTo(1.0);
        assertThat(alias.getSource()).isEqualTo("generated");
        assertThat(alias.getCurationStatus()).isEqualTo("generated");
        assertThat(binding.getPayload()).isEqualTo("{}");
        assertThat(binding.getCurationStatus()).isEqualTo("generated");
        assertThat(relationship.getCrossContext()).isFalse();
        assertThat(relationship.getPayload()).isEqualTo("{}");
        assertThat(evidence.getPayload()).isEqualTo("{}");
        assertThat(changeSet.getStatus()).isEqualTo("draft");
        assertThat(changeSet.getPatch()).isEqualTo("[]");
        assertThat(changeSet.getCreatedAt()).isNotNull();
    }
}
