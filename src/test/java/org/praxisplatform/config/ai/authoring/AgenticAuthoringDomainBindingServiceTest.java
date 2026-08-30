package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;

@Tag("unit")
class AgenticAuthoringDomainBindingServiceTest {

    private final DomainKnowledgeBindingRepository bindingRepository = mock(DomainKnowledgeBindingRepository.class);
    private final DomainKnowledgeEvidenceRepository evidenceRepository = mock(DomainKnowledgeEvidenceRepository.class);
    private final AgenticAuthoringDomainBindingService service =
            new AgenticAuthoringDomainBindingService(bindingRepository, evidenceRepository);

    @Test
    void projectsOnlyGovernedBindingBackedByActiveConceptEvidence() {
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .conceptKey("hr:employee-management")
                .tenantId("tenant")
                .environment("dev")
                .build();
        DomainKnowledgeBinding binding = DomainKnowledgeBinding.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .environment("dev")
                .concept(concept)
                .bindingType("resource")
                .bindingKey("resource:human-resources.funcionarios")
                .resourceKey("human-resources.funcionarios")
                .apiPath("/api/funcionarios")
                .apiMethod("GET")
                .schemaPointer("/schemas/filtered?path=/api/funcionarios&operation=get")
                .confidence(1.0)
                .curationStatus("approved")
                .build();
        when(bindingRepository.findGovernedOperationalBindings(
                "tenant", "dev", "human-resources.funcionarios")).thenReturn(List.of(binding));
        when(evidenceRepository.findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectIdAndStatus(
                "tenant", "dev", "concept", concept.getId(), "active"))
                .thenReturn(List.of(DomainKnowledgeEvidence.builder().id(UUID.randomUUID()).build()));

        List<AgenticAuthoringDomainBindingService.BindingProjection> result =
                service.resolve("tenant", "dev", "human-resources.funcionarios", 4);

        assertThat(result).singleElement().satisfies(projected -> {
            assertThat(projected.conceptKey()).isEqualTo("hr:employee-management");
            assertThat(projected.resourceKey()).isEqualTo("human-resources.funcionarios");
            assertThat(projected.apiPath()).isEqualTo("/api/funcionarios");
            assertThat(projected.apiMethod()).isEqualTo("GET");
            assertThat(projected.evidence()).contains("domain-knowledge:evidence-status:active");
        });
    }

    @Test
    void rejectsRepositoryRowsOutsideTheAuthenticatedTenantAndEnvironment() {
        DomainKnowledgeConcept concept = concept("other-tenant", "prod", null);
        DomainKnowledgeBinding binding = binding("other-tenant", "prod", concept, null);
        when(bindingRepository.findGovernedOperationalBindings(
                "tenant", "dev", "human-resources.funcionarios")).thenReturn(List.of(binding));

        assertThat(service.resolve("tenant", "dev", "human-resources.funcionarios", 4)).isEmpty();
    }

    @Test
    void rejectsBindingFromAReleaseThatNoLongerOwnsItsConcept() {
        DomainCatalogRelease currentRelease = DomainCatalogRelease.builder().id(UUID.randomUUID()).build();
        DomainCatalogRelease staleRelease = DomainCatalogRelease.builder().id(UUID.randomUUID()).build();
        DomainKnowledgeConcept concept = concept("tenant", "dev", currentRelease);
        DomainKnowledgeBinding binding = binding("tenant", "dev", concept, staleRelease);
        when(bindingRepository.findGovernedOperationalBindings(
                "tenant", "dev", "human-resources.funcionarios")).thenReturn(List.of(binding));

        assertThat(service.resolve("tenant", "dev", "human-resources.funcionarios", 4)).isEmpty();
    }

    @Test
    void preservesNativeWorkflowActionIdFromTheCanonicalBindingTarget() {
        DomainKnowledgeConcept concept = concept("tenant", "dev", null);
        concept.setConceptKey("operations.missoes.action.start");
        DomainKnowledgeBinding binding = DomainKnowledgeBinding.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .environment("dev")
                .concept(concept)
                .bindingType("workflow_action")
                .bindingKey("binding:operations.missoes.action.start:workflow-action")
                .resourceKey("operations.missoes")
                .apiPath("/api/operations/missoes/{id}/actions/start")
                .apiMethod("POST")
                .payload("""
                        {"target":{"id":"start","operationId":"startMission"}}
                        """)
                .curationStatus("approved")
                .build();
        when(bindingRepository.findGovernedOperationalBindings(
                "tenant", "dev", "operations.missoes")).thenReturn(List.of(binding));
        when(evidenceRepository.findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectIdAndStatus(
                "tenant", "dev", "concept", concept.getId(), "active"))
                .thenReturn(List.of(DomainKnowledgeEvidence.builder().id(UUID.randomUUID()).build()));

        assertThat(service.resolve("tenant", "dev", "operations.missoes", 4))
                .singleElement()
                .satisfies(projected -> assertThat(projected.operationId()).isEqualTo("start"));
    }

    private DomainKnowledgeConcept concept(
            String tenantId,
            String environment,
            DomainCatalogRelease sourceRelease) {
        return DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .conceptKey("hr:employee-management")
                .tenantId(tenantId)
                .environment(environment)
                .sourceRelease(sourceRelease)
                .build();
    }

    private DomainKnowledgeBinding binding(
            String tenantId,
            String environment,
            DomainKnowledgeConcept concept,
            DomainCatalogRelease sourceRelease) {
        return DomainKnowledgeBinding.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .environment(environment)
                .concept(concept)
                .bindingType("resource")
                .bindingKey("resource:human-resources.funcionarios")
                .resourceKey("human-resources.funcionarios")
                .apiPath("/api/funcionarios")
                .apiMethod("GET")
                .schemaPointer("/schemas/filtered?path=/api/funcionarios&operation=get")
                .confidence(1.0)
                .curationStatus("approved")
                .sourceRelease(sourceRelease)
                .build();
    }
}
