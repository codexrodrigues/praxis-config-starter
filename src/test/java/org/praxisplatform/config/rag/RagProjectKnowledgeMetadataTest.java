package org.praxisplatform.config.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;

@Tag("unit")
class RagProjectKnowledgeMetadataTest {

    @Test
    void buildsDerivedProjectKnowledgeProvenanceWithoutRawPayloadOrSourcePointers() {
        UUID conceptId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .id(conceptId)
                .tenantId("tenant-a")
                .environment("prod")
                .conceptKey("project-knowledge:identity-card")
                .contextKey("human-resources")
                .resourceKey("human-resources.funcionarios")
                .aiVisibility("allow")
                .payload("{\"summary\":\"must not be copied to metadata\"}")
                .build();
        DomainKnowledgeEvidence evidence = DomainKnowledgeEvidence.builder()
                .id(evidenceId)
                .tenantId("tenant-a")
                .environment("prod")
                .evidenceKey("evidence:identity-card")
                .status("active")
                .sourceUri("https://example.invalid/private")
                .sourcePointer("/private/source")
                .payload("{\"raw\":\"must not be copied to metadata\"}")
                .build();

        Map<String, Object> metadata = RagProjectKnowledgeMetadata.from(
                concept,
                evidence,
                "quickstart:human-resources:2026-05-01",
                "hash-123",
                0);

        assertThat(metadata).containsEntry(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE)
                .containsEntry(RagMetadataKeys.DOC_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE)
                .containsEntry(RagMetadataKeys.TENANT_ID, "tenant-a")
                .containsEntry(RagMetadataKeys.ENVIRONMENT, "prod")
                .containsEntry(RagMetadataKeys.RELEASE_ID, "quickstart:human-resources:2026-05-01")
                .containsEntry(RagMetadataKeys.CONTENT_HASH, "hash-123")
                .containsEntry(RagMetadataKeys.CHUNK_INDEX, 0)
                .containsEntry(RagMetadataKeys.RESOURCE_ID, "project-knowledge:identity-card")
                .containsEntry(RagMetadataKeys.COMPONENT_ID, "project-knowledge:identity-card")
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_ID, conceptId.toString())
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_KEY, "project-knowledge:identity-card")
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_ID, evidenceId.toString())
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_KEY, "evidence:identity-card")
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, "active")
                .containsEntry(RagMetadataKeys.AI_VISIBILITY, "allow")
                .containsEntry(RagMetadataKeys.CONTEXT_KEY, "human-resources")
                .containsEntry(RagMetadataKeys.RESOURCE_KEY, "human-resources.funcionarios");
        assertThat(metadata).doesNotContainKeys("payload", "sourceUri", "sourcePointer", "rawPayload");
    }

    @Test
    void keepsEvidenceStatusAsDerivedMetadataOnly() {
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("prod")
                .conceptKey("project-knowledge:old-guidance")
                .aiVisibility("allow")
                .build();
        DomainKnowledgeEvidence evidence = DomainKnowledgeEvidence.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("prod")
                .evidenceKey("evidence:old-guidance")
                .status("superseded")
                .build();

        Map<String, Object> metadata = RagProjectKnowledgeMetadata.from(concept, evidence, null, null, 3);

        assertThat(metadata)
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, "superseded")
                .containsEntry(RagMetadataKeys.CHUNK_INDEX, 3);
        assertThat(metadata).doesNotContainKeys(RagMetadataKeys.RELEASE_ID, RagMetadataKeys.CONTENT_HASH);
    }
}
