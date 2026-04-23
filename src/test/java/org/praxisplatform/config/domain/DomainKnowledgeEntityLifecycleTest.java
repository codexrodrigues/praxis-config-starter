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

    @Test
    void sharedRuleEntitiesDefaultGovernanceAndMaterializationPayloads() {
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .ruleKey("human-resources.funcionarios.rule.lgpd-cpf-guidance")
                .ruleType("visual_guidance")
                .resourceKey("human-resources.funcionarios")
                .createdByType("llm")
                .build();

        definition.onInsert();

        DomainRuleMaterialization materialization = DomainRuleMaterialization.builder()
                .ruleDefinition(definition)
                .materializationKey("funcionarios-form-demo:formRules:lgpd-cpf-guidance")
                .targetLayer("form_config")
                .targetArtifactType("praxis-dynamic-form")
                .targetArtifactKey("funcionarios-form-demo")
                .materializedRuleId("lgpd-cpf-guidance")
                .build();

        materialization.onInsert();

        assertThat(definition.getId()).isNotNull();
        assertThat(definition.getVersion()).isEqualTo(1);
        assertThat(definition.getStatus()).isEqualTo("draft");
        assertThat(definition.getDefinition()).isEqualTo("{}");
        assertThat(definition.getParameters()).isEqualTo("{}");
        assertThat(definition.getGovernance()).isEqualTo("{}");
        assertThat(definition.getCreatedAt()).isNotNull();
        assertThat(definition.getUpdatedAt()).isNotNull();
        assertThat(materialization.getId()).isNotNull();
        assertThat(materialization.getStatus()).isEqualTo("draft");
        assertThat(materialization.getMaterializedPayload()).isEqualTo("{}");
        assertThat(materialization.getCreatedAt()).isNotNull();
        assertThat(materialization.getUpdatedAt()).isNotNull();
    }

    @Test
    void federationEntitiesDefaultLifecycleAndEvidencePayloads() {
        DomainFederationRelease release = DomainFederationRelease.builder()
                .releaseKey("domain-federation:default:dev:v2026-04-23T16:00Z")
                .tenantId("default")
                .environment("dev")
                .build();

        release.onInsert();

        DomainSource source = DomainSource.builder()
                .federationRelease(release)
                .sourceKey("praxis-api-quickstart")
                .sourceType("microservice")
                .build();
        DomainContext context = DomainContext.builder()
                .federationRelease(release)
                .contextKey("human-resources")
                .sourceKey("praxis-api-quickstart")
                .build();
        DomainContextRelationship relationship = DomainContextRelationship.builder()
                .federationRelease(release)
                .relationshipKey("human-resources.references.security")
                .sourceContextKey("human-resources")
                .targetContextKey("security")
                .relationshipType("references")
                .build();
        DomainContract contract = DomainContract.builder()
                .federationRelease(release)
                .contractKey("security.users.lookup.v1")
                .contractType("rest_endpoint")
                .providerSourceKey("security-service")
                .providerContextKey("security")
                .build();
        DomainResolution resolution = DomainResolution.builder()
                .federationRelease(release)
                .resolutionKey("hr.funcionario.same_as.security.user.employee")
                .sourceConceptKey("human-resources.funcionario")
                .targetConceptKey("security.user.employee")
                .sourceContextKey("human-resources")
                .targetContextKey("security")
                .resolutionType("same_as")
                .build();

        source.onInsert();
        context.onInsert();
        relationship.onInsert();
        contract.onInsert();
        resolution.onInsert();

        assertThat(release.getId()).isNotNull();
        assertThat(release.getStatus()).isEqualTo("candidate");
        assertThat(release.getSourceReleaseIds()).isEqualTo("[]");
        assertThat(release.getValidationReport()).isEqualTo("{}");
        assertThat(release.getCreatedAt()).isNotNull();
        assertThat(source.getTrustLevel()).isEqualTo("generated");
        assertThat(source.getStatus()).isEqualTo("active");
        assertThat(source.getEvidence()).isEqualTo("{}");
        assertThat(context.getContextType()).isEqualTo("bounded_context");
        assertThat(context.getStatus()).isEqualTo("candidate");
        assertThat(context.getEvidence()).isEqualTo("{}");
        assertThat(relationship.getDirection()).isEqualTo("source_to_target");
        assertThat(relationship.getOwnership()).isEqualTo("unknown");
        assertThat(relationship.getStatus()).isEqualTo("candidate");
        assertThat(relationship.getEvidence()).isEqualTo("{}");
        assertThat(contract.getCompatibility()).isEqualTo("experimental");
        assertThat(contract.getVisibility()).isEqualTo("internal");
        assertThat(contract.getStatus()).isEqualTo("candidate");
        assertThat(contract.getEvidence()).isEqualTo("{}");
        assertThat(resolution.getStatus()).isEqualTo("candidate");
        assertThat(resolution.getEvidence()).isEqualTo("{}");
    }
}
