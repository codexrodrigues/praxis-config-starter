package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainKnowledgeAlias;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.domain.DomainKnowledgeRelationship;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;

@Tag("unit")
class DomainKnowledgeSemanticClaimApplierTest {

    private static final String TENANT = "tenant-a";
    private static final String ENVIRONMENT = "dev";
    private static final String CAPABILITY_KEY = "human-resources.capability.workforce-management";
    private static final String METRIC_KEY = "human-resources.metric.workforce-count";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appliesReviewedSemanticClaimsAndPublishesOnlyActiveEvidenceToProjectKnowledge() {
        DomainKnowledgeChangeSetRepository changeSetRepository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        DomainKnowledgeAliasRepository aliasRepository = mock(DomainKnowledgeAliasRepository.class);
        DomainKnowledgeBindingRepository bindingRepository = mock(DomainKnowledgeBindingRepository.class);
        DomainKnowledgeRelationshipRepository relationshipRepository = mock(DomainKnowledgeRelationshipRepository.class);
        DomainKnowledgeEvidenceRepository evidenceRepository = mock(DomainKnowledgeEvidenceRepository.class);
        ProjectKnowledgeDerivedIndexService derivedIndexService = mock(ProjectKnowledgeDerivedIndexService.class);

        Map<String, DomainKnowledgeConcept> concepts = new LinkedHashMap<>();
        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(concepts.get(invocation.getArgument(2))));
        when(conceptRepository.save(any(DomainKnowledgeConcept.class))).thenAnswer(invocation -> {
            DomainKnowledgeConcept concept = invocation.getArgument(0);
            concept.onInsert();
            concepts.put(concept.getConceptKey(), concept);
            return concept;
        });
        when(aliasRepository.findByConcept_Id(any())).thenReturn(List.of());
        when(aliasRepository.save(any(DomainKnowledgeAlias.class))).thenAnswer(invocation -> {
            DomainKnowledgeAlias alias = invocation.getArgument(0);
            alias.onInsert();
            return alias;
        });
        when(bindingRepository.findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(
                any(), any(), any(), any())).thenReturn(List.of());
        when(bindingRepository.save(any(DomainKnowledgeBinding.class))).thenAnswer(invocation -> {
            DomainKnowledgeBinding binding = invocation.getArgument(0);
            binding.onInsert();
            return binding;
        });
        when(relationshipRepository.findByTenantIdAndEnvironmentAndSourceConcept_Id(
                any(), any(), any())).thenReturn(List.of());
        when(relationshipRepository.save(any(DomainKnowledgeRelationship.class))).thenAnswer(invocation -> {
            DomainKnowledgeRelationship relationship = invocation.getArgument(0);
            relationship.onInsert();
            return relationship;
        });
        when(evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(any(), any(), any()))
                .thenReturn(List.of());
        when(evidenceRepository.save(any(DomainKnowledgeEvidence.class))).thenAnswer(invocation -> {
            DomainKnowledgeEvidence evidence = invocation.getArgument(0);
            evidence.onInsert();
            return evidence;
        });

        List<DomainKnowledgeChangeSetOperationRequest> patch = semanticPilotPatch();
        DomainKnowledgeChangeSet changeSet = approvedChangeSet(patch);
        when(changeSetRepository.findById(changeSet.getId())).thenReturn(Optional.of(changeSet));
        when(changeSetRepository.save(any(DomainKnowledgeChangeSet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DomainKnowledgeChangeSetService service = new DomainKnowledgeChangeSetService(
                changeSetRepository,
                conceptRepository,
                aliasRepository,
                bindingRepository,
                relationshipRepository,
                evidenceRepository,
                new DomainKnowledgeChangeSetValidator(),
                objectMapper,
                derivedIndexService);

        var response = service.apply(changeSet.getId(), TENANT, ENVIRONMENT);

        assertThat(response.status()).isEqualTo("applied");
        assertThat(concepts).containsKeys(CAPABILITY_KEY, METRIC_KEY);
        assertThat(concepts.values()).allSatisfy(concept -> {
            assertThat(concept.getLifecycle()).isEqualTo("active");
            assertThat(concept.getCurationStatus()).isEqualTo("approved");
            assertThat(concept.getSemanticOwner()).isEqualTo("people-operations");
            assertThat(concept.getPayload()).contains("\"sourceClass\":\"inferred\"");
        });

        ArgumentCaptor<DomainKnowledgeAlias> aliasCaptor = ArgumentCaptor.forClass(DomainKnowledgeAlias.class);
        verify(aliasRepository).save(aliasCaptor.capture());
        assertThat(aliasCaptor.getValue().getNormalizedAlias()).isEqualTo("gestao da forca de trabalho");
        assertThat(aliasCaptor.getValue().getSource()).isEqualTo("llm_proposed");
        assertThat(aliasCaptor.getValue().getCurationStatus()).isEqualTo("approved");

        ArgumentCaptor<DomainKnowledgeBinding> bindingCaptor = ArgumentCaptor.forClass(DomainKnowledgeBinding.class);
        verify(bindingRepository).save(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getBindingType()).isEqualTo("api_resource");
        assertThat(bindingCaptor.getValue().getResourceKey()).isEqualTo("human-resources.funcionarios");
        assertThat(bindingCaptor.getValue().getCurationStatus()).isEqualTo("approved");

        ArgumentCaptor<DomainKnowledgeRelationship> relationshipCaptor =
                ArgumentCaptor.forClass(DomainKnowledgeRelationship.class);
        verify(relationshipRepository).save(relationshipCaptor.capture());
        assertThat(relationshipCaptor.getValue().getRelationshipType()).isEqualTo("measured_by");
        assertThat(relationshipCaptor.getValue().getSourceConcept().getConceptKey()).isEqualTo(CAPABILITY_KEY);
        assertThat(relationshipCaptor.getValue().getTargetConcept().getConceptKey()).isEqualTo(METRIC_KEY);

        ArgumentCaptor<DomainKnowledgeEvidence> evidenceCaptor =
                ArgumentCaptor.forClass(DomainKnowledgeEvidence.class);
        verify(evidenceRepository, times(6)).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getAllValues())
                .extracting(DomainKnowledgeEvidence::getSubjectType)
                .contains("concept", "alias", "binding", "relationship");
        DomainKnowledgeEvidence explicitEvidence = evidenceCaptor.getAllValues().stream()
                .filter(evidence -> "evidence:hr:workforce-management:v1".equals(evidence.getEvidenceKey()))
                .findFirst()
                .orElseThrow();
        assertThat(explicitEvidence.getSubjectId()).isEqualTo(concepts.get(CAPABILITY_KEY).getId());
        verify(derivedIndexService).evidenceActivated(concepts.get(CAPABILITY_KEY), explicitEvidence);
    }

    private List<DomainKnowledgeChangeSetOperationRequest> semanticPilotPatch() {
        return List.of(
                operation("create-capability", "create_concept", target(CAPABILITY_KEY),
                        conceptPayload("business_capability", "Workforce Management",
                                "Manage the employee lifecycle and workforce structure.",
                                "claim:hr:workforce-management:v1")),
                operation("create-metric", "create_concept", target(METRIC_KEY),
                        conceptPayload("metric", "Workforce Count",
                                "Number of active employees in the governed workforce scope.",
                                "claim:hr:workforce-count:v1")),
                operation("alias-capability", "add_alias", target(CAPABILITY_KEY),
                        claimPayload("claim:hr:workforce-management:alias:pt-BR:v1")
                                .put("alias", "Gestão da força de trabalho")
                                .put("aliasType", "preferred_term")
                                .put("locale", "pt-BR")),
                operation("bind-capability", "add_binding", target(CAPABILITY_KEY),
                        claimPayload("claim:hr:workforce-management:binding:funcionarios:v1")
                                .put("bindingType", "api_resource")
                                .put("bindingKey", "human-resources.funcionarios")
                                .put("resourceKey", "human-resources.funcionarios")),
                operation("relate-capability-metric", "add_relationship",
                        relationshipTarget(CAPABILITY_KEY, METRIC_KEY),
                        claimPayload("claim:hr:workforce-management:measured-by:workforce-count:v1")
                                .put("relationshipType", "measured_by")),
                operation("evidence-capability", "add_evidence", target(CAPABILITY_KEY),
                        objectMapper.createObjectNode()
                                .put("evidenceKey", "evidence:hr:workforce-management:v1")
                                .put("evidenceType", "catalog_release")
                                .put("sourceUri", "praxis-domain-catalog://praxis-service/human-resources")
                                .put("sourcePointer", "/contexts/0")));
    }

    private DomainKnowledgeChangeSetOperationRequest operation(
            String operationId,
            String operationType,
            JsonNode target,
            JsonNode payload) {
        return new DomainKnowledgeChangeSetOperationRequest(
                operationId,
                operationType,
                target,
                "Materialize the reviewed Human Resources semantic pilot.",
                List.of("domain-catalog:praxis-service:human-resources:025f0d304a66669b"),
                0.92,
                payload);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode conceptPayload(
            String nodeType,
            String label,
            String description,
            String claimId) {
        return claimPayload(claimId)
                .put("contextKey", "human-resources")
                .put("nodeType", nodeType)
                .put("label", label)
                .put("description", description)
                .put("semanticOwner", "people-operations")
                .put("aiVisibility", "allow");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode claimPayload(String claimId) {
        var provenance = objectMapper.createObjectNode()
                .put("claimId", claimId)
                .put("sourceClass", "inferred")
                .put("derivationActivity", "semantic-pilot-synthesis")
                .put("model", "gpt-5.6-mini")
                .put("templateHash", "sha256:semantic-pilot-v1");
        provenance.set("sourceRefs", objectMapper.createArrayNode()
                .add("domain-catalog:praxis-service:human-resources:025f0d304a66669b"));
        provenance.set("agent", objectMapper.createObjectNode()
                .put("type", "model")
                .put("id", "openai:gpt-5.6-mini"));
        var payload = objectMapper.createObjectNode();
        payload.set("provenance", provenance);
        return payload;
    }

    private JsonNode target(String conceptKey) {
        return objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("conceptKey", conceptKey);
    }

    private JsonNode relationshipTarget(String sourceConceptKey, String targetConceptKey) {
        return objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("sourceConceptKey", sourceConceptKey)
                .put("targetConceptKey", targetConceptKey);
    }

    private DomainKnowledgeChangeSet approvedChangeSet(List<DomainKnowledgeChangeSetOperationRequest> patch) {
        DomainKnowledgeChangeSet changeSet = new DomainKnowledgeChangeSet();
        changeSet.setId(UUID.randomUUID());
        changeSet.setTenantId(TENANT);
        changeSet.setEnvironment(ENVIRONMENT);
        changeSet.setChangeSetKey("project-knowledge:hr:semantic-pilot:v1");
        changeSet.setStatus("approved");
        changeSet.setAuthorType("llm");
        changeSet.setAuthorId("openai:gpt-5.6-mini");
        changeSet.setReviewerId("reviewer:people-operations");
        changeSet.setIntent("Teach the governed Human Resources business model to Praxis.");
        changeSet.setReason("The Page Builder needs business-level grounding before API discovery.");
        changeSet.setPatch(write(objectMapper.valueToTree(patch)));
        changeSet.setValidationResult("""
                {"validationStatus":"valid","errorCount":0,"warningCount":0,"issues":[]}
                """);
        changeSet.setCreatedAt(Instant.now());
        changeSet.setReviewedAt(Instant.now());
        return changeSet;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
