package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.springframework.data.domain.Pageable;

@Tag("unit")
class AgenticAuthoringProjectKnowledgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiSensitiveDataRedactor redactor = new AiSensitiveDataRedactor();

    @Test
    void retrievesOnlyGovernedMatchingProjectKnowledgeAsSafeProjection() {
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        AgenticAuthoringProjectKnowledgeService service = new AgenticAuthoringProjectKnowledgeService(
                conceptRepository,
                objectMapper,
                redactor);
        DomainCatalogRelease release = DomainCatalogRelease.builder()
                .releaseKey("quickstart:human-resources:2026-04-30")
                .build();
        DomainKnowledgeConcept allowed = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "allow",
                """
                {
                  "kind": "project_preference",
                  "summary": "Prefer employee pages with compact identity cards.",
                  "sourceSummary": "accepted authoring turn",
                  "influence": "layout_preference"
                }
                """);
        allowed.setSourceRelease(release);
        DomainKnowledgeConcept masked = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "mask",
                """
                {
                  "kind": "governance_constraint",
                  "summary": "CPF 123456789012 must never be rendered raw.",
                  "sourceSummary": "security review"
                }
                """);
        DomainKnowledgeConcept denied = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "deny",
                "{\"kind\":\"project_preference\",\"summary\":\"hidden\"}");
        DomainKnowledgeConcept draft = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "candidate",
                "approved",
                "allow",
                "{\"kind\":\"project_preference\",\"summary\":\"draft\"}");
        DomainKnowledgeConcept wrongKind = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "allow",
                "{\"kind\":\"integration_note\",\"summary\":\"ignore\"}");
        when(conceptRepository.findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq("concept"),
                any(Pageable.class)))
                .thenReturn(List.of(allowed, masked, denied, draft, wrongKind));

        List<AgenticAuthoringProjectKnowledgeProjection> projections = service.retrieve(
                new AgenticAuthoringProjectKnowledgeQuery(
                        " tenant-a ",
                        " dev ",
                        " human-resources ",
                        " human-resources.funcionarios ",
                        List.of("project_preference", "governance_constraint"),
                        " concept ",
                        5));

        assertThat(projections).hasSize(2);
        assertThat(projections.get(0))
                .satisfies(projection -> {
                    assertThat(projection.kind()).isEqualTo("project_preference");
                    assertThat(projection.scope().tenantId()).isEqualTo("tenant-a");
                    assertThat(projection.scope().contextKey()).isEqualTo("human-resources");
                    assertThat(projection.visibility()).isEqualTo("allow");
                    assertThat(projection.summary()).isEqualTo("Prefer employee pages with compact identity cards.");
                    assertThat(projection.sourceSummary()).isEqualTo("accepted authoring turn");
                    assertThat(projection.influence()).isEqualTo("layout_preference");
                    assertThat(projection.evidence()).contains(
                            "project-knowledge-kind:project_preference",
                            "source-release:quickstart:human-resources:2026-04-30");
                });
        assertThat(projections.get(1))
                .satisfies(projection -> {
                    assertThat(projection.kind()).isEqualTo("governance_constraint");
                    assertThat(projection.visibility()).isEqualTo("mask");
                    assertThat(projection.summary()).isEqualTo("Knowledge payload masked by ai_visibility policy.");
                    assertThat(projection.influence()).isEqualTo("masked_context");
                    assertThat(projection.summary()).doesNotContain("123456789012");
                });
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(conceptRepository).findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq("concept"),
                pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void rejectsMissingTenantOrEnvironmentBeforeRepositoryAccess() {
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        AgenticAuthoringProjectKnowledgeService service = new AgenticAuthoringProjectKnowledgeService(
                conceptRepository,
                objectMapper,
                redactor);

        assertThat(service.retrieve(new AgenticAuthoringProjectKnowledgeQuery(
                null,
                "dev",
                null,
                null,
                List.of("project_preference"),
                null,
                5))).isEmpty();

        verifyNoInteractions(conceptRepository);
    }

    @Test
    void clampsQueryLimitAndKeepsOnlyScopedMatches() {
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        AgenticAuthoringProjectKnowledgeService service = new AgenticAuthoringProjectKnowledgeService(
                conceptRepository,
                objectMapper,
                redactor);
        DomainKnowledgeConcept scoped = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "summarize_only",
                "{\"kind\":\"resource_selection_rationale\",\"summary\":\"Use employees resource.\"}");
        DomainKnowledgeConcept global = concept(
                "tenant-a",
                "dev",
                null,
                null,
                "active",
                "approved",
                "allow",
                "{\"kind\":\"resource_selection_rationale\",\"summary\":\"Global preference.\"}");
        DomainKnowledgeConcept otherScope = concept(
                "tenant-a",
                "dev",
                "finance",
                "finance.invoices",
                "active",
                "approved",
                "allow",
                "{\"kind\":\"resource_selection_rationale\",\"summary\":\"Wrong scope.\"}");
        when(conceptRepository.findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq(null),
                any(Pageable.class)))
                .thenReturn(List.of(scoped, global, otherScope));

        List<AgenticAuthoringProjectKnowledgeProjection> projections = service.retrieve(
                new AgenticAuthoringProjectKnowledgeQuery(
                        "tenant-a",
                        "dev",
                        "human-resources",
                        "human-resources.funcionarios",
                        List.of("resource_selection_rationale"),
                        null,
                        99));

        assertThat(projections)
                .extracting(AgenticAuthoringProjectKnowledgeProjection::summary)
                .containsExactly("Use employees resource.", "Global preference.");
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(conceptRepository).findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq(null),
                pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    private DomainKnowledgeConcept concept(
            String tenantId,
            String environment,
            String contextKey,
            String resourceKey,
            String lifecycle,
            String curationStatus,
            String aiVisibility,
            String payload) {
        return DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .environment(environment)
                .conceptKey("knowledge:" + UUID.randomUUID())
                .contextKey(contextKey)
                .resourceKey(resourceKey)
                .nodeType("concept")
                .label("Project knowledge")
                .lifecycle(lifecycle)
                .curationStatus(curationStatus)
                .aiVisibility(aiVisibility)
                .payload(payload)
                .build();
    }
}
