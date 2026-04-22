package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
                          "aiUsage": {"visibility": "mask"},
                          "sourceEvidenceKeys": ["evidence:valor-liquido:workflow-response"]
                        }
                        """),
                item(release, "node", "human-resources.folhas-pagamento.surface.create", "human-resources",
                        "surface", null, null, """
                        {
                          "nodeKey": "human-resources.folhas-pagamento.surface.create",
                          "nodeType": "surface",
                          "contextKey": "human-resources",
                          "label": "Criar folha"
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
                item(release, "edge", "human-resources.folhas-pagamento.has-surface.create", "human-resources",
                        null, null, "has_surface", """
                        {
                          "edgeKey": "human-resources.folhas-pagamento.has-surface.create",
                          "sourceNodeKey": "human-resources.folhas-pagamento",
                          "targetNodeKey": "human-resources.folhas-pagamento.surface.create",
                          "edgeType": "has_surface",
                          "sourceEvidenceKeys": ["evidence:surface:create"]
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
                item(release, "binding", "binding:human-resources.folhas-pagamento.surface.create:ui-surface",
                        "human-resources", null, "ui_surface", null, """
                        {
                          "bindingKey": "binding:human-resources.folhas-pagamento.surface.create:ui-surface",
                          "nodeKey": "human-resources.folhas-pagamento.surface.create",
                          "bindingType": "ui_surface",
                          "confidence": 0.93,
                          "target": {
                            "apiPath": "/api/folhas-pagamento",
                            "apiMethod": "POST"
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
                          "evidenceType": "dto_schema",
                          "confidence": 0.88
                        }
                        """),
                item(release, "evidence", "evidence:surface:create", "human-resources", null, null, null, """
                        {
                          "evidenceKey": "evidence:surface:create",
                          "evidenceType": "api_spec",
                          "confidence": 0.82
                        }
                        """)
        );

        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(conceptRepository.saveAll(any())).thenAnswer(invocation -> {
            List<DomainKnowledgeConcept> concepts = toList(invocation.getArgument(0));
            concepts.forEach(DomainKnowledgeConcept::onInsert);
            return concepts;
        });
        when(aliasRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(aliasRepository.findByConcept_IdIn(any())).thenReturn(List.of());
        when(bindingRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindingRepository.findByTenantIdAndEnvironmentAndBindingKeyIn(any(), any(), any()))
                .thenReturn(List.of());
        when(relationshipRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relationshipRepository.findByTenantIdAndEnvironmentAndSourceConcept_IdIn(any(), any(), any()))
                .thenReturn(List.of());
        when(evidenceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKeyIn(any(), any(), any()))
                .thenReturn(List.of());

        service.project(release, items);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DomainKnowledgeConcept>> conceptCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(conceptRepository).saveAll(conceptCaptor.capture());
        assertThat(toList(conceptCaptor.getValue()))
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DomainKnowledgeBinding>> bindingCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(bindingRepository).findByTenantIdAndEnvironmentAndBindingKeyIn(any(), any(), any());
        verify(bindingRepository, never())
                .findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(any(), any(), any(), any());
        verify(bindingRepository).saveAll(bindingCaptor.capture());
        List<DomainKnowledgeBinding> savedBindings = toList(bindingCaptor.getValue());
        assertThat(savedBindings)
                .filteredOn(binding -> "dto_field".equals(binding.getBindingType()))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getSchemaPointer()).isEqualTo("WorkflowResponse#/valorLiquido");
                    assertThat(binding.getConfidence()).isEqualTo(0.91);
                });
        assertThat(savedBindings)
                .filteredOn(binding -> "ui_surface".equals(binding.getBindingType()))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getApiPath()).isEqualTo("/api/folhas-pagamento");
                    assertThat(binding.getApiMethod()).isEqualTo("POST");
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DomainKnowledgeAlias>> aliasCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(aliasRepository).findByConcept_IdIn(any());
        verify(aliasRepository, never()).findByConcept_Id(any());
        verify(aliasRepository).saveAll(aliasCaptor.capture());
        assertThat(toList(aliasCaptor.getValue()))
                .singleElement()
                .satisfies(alias -> {
                    assertThat(alias.getAliasType()).isEqualTo("technical_name");
                    assertThat(alias.getNormalizedAlias()).isEqualTo("valor liquido");
                    assertThat(alias.getWeight()).isEqualTo(0.85);
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DomainKnowledgeRelationship>> relationshipCaptor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(relationshipRepository).findByTenantIdAndEnvironmentAndSourceConcept_IdIn(any(), any(), any());
        verify(relationshipRepository, never()).findByTenantIdAndEnvironmentAndSourceConcept_Id(any(), any(), any());
        verify(relationshipRepository).saveAll(relationshipCaptor.capture());
        List<DomainKnowledgeRelationship> savedRelationships = toList(relationshipCaptor.getValue());
        assertThat(savedRelationships)
                .extracting(DomainKnowledgeRelationship::getRelationshipType)
                .containsExactly("has_field", "has_surface");
        assertThat(savedRelationships)
                .allSatisfy(relationship -> assertThat(relationship.getCrossContext()).isFalse());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DomainKnowledgeEvidence>> evidenceCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(evidenceRepository).findByTenantIdAndEnvironmentAndEvidenceKeyIn(any(), any(), any());
        verify(evidenceRepository, never()).findByTenantIdAndEnvironmentAndEvidenceKey(any(), any(), any());
        verify(evidenceRepository).saveAll(evidenceCaptor.capture());
        List<DomainKnowledgeEvidence> savedEvidence = toList(evidenceCaptor.getValue());
        assertThat(savedEvidence)
                .extracting(DomainKnowledgeEvidence::getEvidenceType)
                .contains("catalog_release", "json_schema");
        assertThat(savedEvidence)
                .filteredOn(evidence -> "evidence:valor-liquido:workflow-response".equals(evidence.getEvidenceKey()))
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.getSubjectId()).isNotNull());
        assertThat(savedEvidence)
                .filteredOn(evidence -> "evidence:surface:create".equals(evidence.getEvidenceKey()))
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.getSubjectId()).isNotNull());
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

    private static <T> List<T> toList(Iterable<T> values) {
        List<T> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }
}
