package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeAlias;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.domain.DomainKnowledgeRelationship;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;

@Tag("unit")
class DomainKnowledgeProjectionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsCatalogItemsIntoDomainKnowledgeReadModel() {
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        DomainKnowledgeAliasRepository aliasRepository = mock(DomainKnowledgeAliasRepository.class);
        DomainKnowledgeBindingRepository bindingRepository = mock(DomainKnowledgeBindingRepository.class);
        DomainKnowledgeRelationshipRepository relationshipRepository = mock(DomainKnowledgeRelationshipRepository.class);
        DomainKnowledgeEvidenceRepository evidenceRepository = mock(DomainKnowledgeEvidenceRepository.class);
        DomainKnowledgeProjectionService service = new DomainKnowledgeProjectionService(
                conceptRepository,
                aliasRepository,
                bindingRepository,
                relationshipRepository,
                evidenceRepository,
                objectMapper);

        DomainCatalogRelease release = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:human-resources.folhas-pagamento:2026-04-22T12:00:00Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        List<DomainCatalogItem> items = List.of(
                item(release, "node", "human-resources.folhas-pagamento", "human-resources", "concept", null, null, """
                        {
                          "nodeKey": "human-resources.folhas-pagamento",
                          "nodeType": "concept",
                          "contextKey": "human-resources",
                          "label": "Folha de pagamento"
                        }
                        """),
                item(release, "node", "human-resources.folhas-pagamento.field.valor-liquido", "human-resources", "field", null, null, """
                        {
                          "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                          "nodeType": "field",
                          "contextKey": "human-resources",
                          "label": "Valor liquido",
                          "description": "Valor final pago ao funcionario.",
                          "classification": "confidential",
                          "dataCategory": "financial",
                          "complianceTags": ["LGPD"],
                          "aiUsage": {"visibility": "mask"}
                        }
                        """),
                item(release, "edge", "human-resources.folhas-pagamento.has-field.valor-liquido", "human-resources", null, null, "has_field", """
                        {
                          "edgeKey": "human-resources.folhas-pagamento.has-field.valor-liquido",
                          "sourceNodeKey": "human-resources.folhas-pagamento",
                          "targetNodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                          "edgeType": "has_field"
                        }
                        """),
                item(release, "binding", "binding:human-resources.folhas-pagamento.field.valor-liquido:dto-field",
                        "human-resources", null, "dto_field", null, """
                        {
                          "bindingKey": "binding:human-resources.folhas-pagamento.field.valor-liquido:dto-field",
                          "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                          "bindingType": "dto_field",
                          "confidence": 0.91,
                          "target": {
                            "schemaId": "WorkflowResponse",
                            "fieldName": "valorLiquido"
                          }
                        }
                        """),
                item(release, "alias", "alias:valor-liquido", "human-resources", null, null, null, """
                        {
                          "aliasKey": "alias:valor-liquido",
                          "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                          "alias": "Valor Liquido",
                          "source": "schema-field-name",
                          "confidence": 0.85
                        }
                        """),
                item(release, "evidence", "evidence:valor-liquido:workflow-response", "human-resources", null, null, null, """
                        {
                          "evidenceKey": "evidence:valor-liquido:workflow-response",
                          "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                          "evidenceType": "dto_schema",
                          "confidence": 0.88
                        }
                        """)
        );

        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(conceptRepository.save(any(DomainKnowledgeConcept.class))).thenAnswer(invocation -> {
            DomainKnowledgeConcept concept = invocation.getArgument(0);
            concept.onInsert();
            return concept;
        });
        when(aliasRepository.findByConcept_Id(any())).thenReturn(List.of());
        when(bindingRepository.findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(relationshipRepository.findByTenantIdAndEnvironmentAndSourceConcept_Id(any(), any(), any()))
                .thenReturn(List.of());
        when(evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(any(), any(), any()))
                .thenReturn(List.of());

        service.project(release, items);

        ArgumentCaptor<DomainKnowledgeConcept> conceptCaptor = ArgumentCaptor.forClass(DomainKnowledgeConcept.class);
        verify(conceptRepository, org.mockito.Mockito.times(2)).save(conceptCaptor.capture());
        assertThat(conceptCaptor.getAllValues())
                .filteredOn(concept -> "human-resources.folhas-pagamento.field.valor-liquido"
                        .equals(concept.getConceptKey()))
                .singleElement()
                .satisfies(concept -> {
                    assertThat(concept.getNodeType()).isEqualTo("field");
                    assertThat(concept.getResourceKey()).isEqualTo("human-resources.folhas-pagamento");
                    assertThat(concept.getAiVisibility()).isEqualTo("mask");
                    assertThat(concept.getClassification()).isEqualTo("confidential");
                    assertThat(concept.getComplianceTags()).isEqualTo("[\"LGPD\"]");
                });

        ArgumentCaptor<DomainKnowledgeBinding> bindingCaptor = ArgumentCaptor.forClass(DomainKnowledgeBinding.class);
        verify(bindingRepository).save(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue())
                .satisfies(binding -> {
                    assertThat(binding.getBindingType()).isEqualTo("dto_field");
                    assertThat(binding.getSchemaPointer()).isEqualTo("WorkflowResponse#/valorLiquido");
                    assertThat(binding.getConfidence()).isEqualTo(0.91);
                });

        ArgumentCaptor<DomainKnowledgeAlias> aliasCaptor = ArgumentCaptor.forClass(DomainKnowledgeAlias.class);
        verify(aliasRepository).save(aliasCaptor.capture());
        assertThat(aliasCaptor.getValue())
                .satisfies(alias -> {
                    assertThat(alias.getAliasType()).isEqualTo("technical_name");
                    assertThat(alias.getNormalizedAlias()).isEqualTo("valor liquido");
                    assertThat(alias.getWeight()).isEqualTo(0.85);
                });

        ArgumentCaptor<DomainKnowledgeRelationship> relationshipCaptor =
                ArgumentCaptor.forClass(DomainKnowledgeRelationship.class);
        verify(relationshipRepository).save(relationshipCaptor.capture());
        assertThat(relationshipCaptor.getValue())
                .satisfies(relationship -> {
                    assertThat(relationship.getRelationshipType()).isEqualTo("has_field");
                    assertThat(relationship.getCrossContext()).isFalse();
                });

        ArgumentCaptor<DomainKnowledgeEvidence> evidenceCaptor = ArgumentCaptor.forClass(DomainKnowledgeEvidence.class);
        verify(evidenceRepository, org.mockito.Mockito.times(3)).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getAllValues())
                .extracting(DomainKnowledgeEvidence::getEvidenceType)
                .contains("catalog_release", "json_schema");
    }

    private DomainCatalogItem item(
            DomainCatalogRelease release,
            String itemType,
            String itemKey,
            String contextKey,
            String nodeType,
            String bindingType,
            String edgeType,
            String payload) {
        return DomainCatalogItem.builder()
                .release(release)
                .itemType(itemType)
                .itemKey(itemKey)
                .contextKey(contextKey)
                .nodeType(nodeType)
                .bindingType(bindingType)
                .edgeType(edgeType)
                .payload(payload)
                .build();
    }
}
