package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.springframework.ai.document.Document;

@Tag("unit")
class RagProjectKnowledgeDerivedIndexServiceTest {

    private final RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
    private final RagProjectKnowledgeDerivedIndexService service = new RagProjectKnowledgeDerivedIndexService(
            ragVectorStoreService,
            new ObjectMapper(),
            new AiSensitiveDataRedactor());

    @Test
    void publishesSanitizedProjectKnowledgeDocumentForActiveEvidence() {
        DomainKnowledgeConcept concept = concept("allow", """
                {
                  "kind": "project_preference",
                  "summary": "Prefer identity card for CPF 123456789012.",
                  "sourceSummary": "approved authoring turn",
                  "influence": "layout_preference"
                }
                """);
        DomainKnowledgeEvidence evidence = activeEvidence(concept);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        service.evidenceActivated(concept, evidence);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documentsCaptor.capture());
        Document document = documentsCaptor.getValue().get(0);

        assertThat(document.getId()).contains("project_knowledge");
        assertThat(document.getText())
                .contains("Project Knowledge")
                .contains("kind: project_preference")
                .contains("summary: Prefer identity card for CPF [REDACTED].")
                .contains("influence: layout_preference")
                .doesNotContain("123456789012")
                .doesNotContain("sourceUri")
                .doesNotContain("sourcePointer");
        assertThat(document.getMetadata())
                .containsEntry(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE)
                .containsEntry(RagMetadataKeys.DOC_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE)
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_ID, concept.getId().toString())
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_ID, evidence.getId().toString())
                .containsEntry(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, "active")
                .containsEntry(RagMetadataKeys.AI_VISIBILITY, "allow");
        assertThat(document.getMetadata()).doesNotContainKeys("payload", "sourceUri", "sourcePointer", "rawPayload");
    }

    @Test
    void masksContentForMaskedVisibilityBeforeDerivedPublication() {
        DomainKnowledgeConcept concept = concept("mask", """
                {
                  "kind": "governance_constraint",
                  "summary": "CPF 123456789012 must not leak into vector text.",
                  "sourceSummary": "security review"
                }
                """);
        concept.setLabel("Sensitive CPF 123456789012 label");
        concept.setDescription("Sensitive masked description from security review");
        DomainKnowledgeEvidence evidence = activeEvidence(concept);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        service.evidenceActivated(concept, evidence);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documentsCaptor.capture());
        Document document = documentsCaptor.getValue().get(0);

        assertThat(document.getText())
                .contains("summary: Knowledge payload masked by ai_visibility policy.")
                .doesNotContain("123456789012")
                .doesNotContain("Sensitive masked description")
                .doesNotContain("security review");
        assertThat(document.getMetadata()).containsEntry(RagMetadataKeys.AI_VISIBILITY, "mask");
    }

    @Test
    void doesNotPublishWhenVectorStoreUnavailableOrEvidenceIsNotCanonicalActive() {
        DomainKnowledgeConcept concept = concept("allow", "{\"kind\":\"project_preference\",\"summary\":\"Safe\"}");
        DomainKnowledgeEvidence evidence = activeEvidence(concept);
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        service.evidenceActivated(concept, evidence);

        verify(ragVectorStoreService, never()).upsertDocuments(anyList());

        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        evidence.setStatus("superseded");

        service.evidenceActivated(concept, evidence);

        verify(ragVectorStoreService, never()).upsertDocuments(anyList());
    }

    @Test
    void deletesDerivedDocumentWhenEvidenceIsDeactivated() {
        DomainKnowledgeConcept concept = concept("allow", "{\"kind\":\"project_preference\",\"summary\":\"Safe\"}");
        DomainKnowledgeEvidence evidence = activeEvidence(concept);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        service.evidenceDeactivated(concept, evidence);

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).deleteDocuments(idsCaptor.capture());
        assertThat(idsCaptor.getValue())
                .singleElement()
                .asString()
                .contains("tenant-a/dev/knowledge_")
                .contains("project_knowledge");
    }

    private DomainKnowledgeConcept concept(String aiVisibility, String payload) {
        return DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .conceptKey("knowledge:" + UUID.randomUUID())
                .contextKey("human-resources")
                .resourceKey("human-resources.funcionarios")
                .nodeType("concept")
                .label("Identity card preference")
                .description("Semantic authoring preference")
                .lifecycle("active")
                .curationStatus("approved")
                .aiVisibility(aiVisibility)
                .payload(payload)
                .build();
    }

    private DomainKnowledgeEvidence activeEvidence(DomainKnowledgeConcept concept) {
        return DomainKnowledgeEvidence.builder()
                .id(UUID.randomUUID())
                .tenantId(concept.getTenantId())
                .environment(concept.getEnvironment())
                .evidenceKey("evidence:" + UUID.randomUUID())
                .subjectType("concept")
                .subjectId(concept.getId())
                .evidenceType("llm_proposal")
                .status("active")
                .sourceUri("https://example.invalid/private")
                .sourcePointer("/raw/pointer")
                .payload("{\"raw\":\"must not be copied\"}")
                .build();
    }
}
