package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ContextConfiguration(classes = PraxisConfigStarterApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:domain_knowledge_repo_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:domain-knowledge-repository-test-schema.sql",
        "spring.flyway.enabled=false"
})
@Tag("integration")
class DomainKnowledgeConceptRepositoryPersistenceTest {

    @Autowired
    private DomainKnowledgeConceptRepository conceptRepository;

    @Autowired
    private DomainCatalogReleaseRepository releaseRepository;

    @Test
    void governedProjectKnowledgeCandidatesApplyStatusVisibilityScopeAndSourceReleaseFilters() {
        DomainCatalogRelease release = releaseRepository.save(DomainCatalogRelease.builder()
                .id(UUID.randomUUID())
                .releaseKey("quickstart:human-resources:2026-04-30")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .tenantId("tenant-a")
                .environment("dev")
                .rawPayload("{}")
                .createdAt(Instant.now())
                .build());
        DomainKnowledgeConcept scopedAllowed = concept(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "active",
                "approved",
                "allow",
                "knowledge:funcionarios:preference",
                release);
        DomainKnowledgeConcept globalMasked = concept(
                "tenant-a",
                "dev",
                null,
                null,
                "active",
                "approved",
                "mask",
                "knowledge:global:constraint",
                release);
        conceptRepository.saveAll(List.of(
                scopedAllowed,
                globalMasked,
                concept("tenant-a", "dev", "finance", "finance.invoices", "active", "approved", "allow", "knowledge:wrong-scope", release),
                concept("tenant-a", "dev", "human-resources", "human-resources.funcionarios", "candidate", "approved", "allow", "knowledge:candidate", release),
                concept("tenant-a", "dev", "human-resources", "human-resources.funcionarios", "active", "review_required", "allow", "knowledge:review", release),
                concept("tenant-a", "dev", "human-resources", "human-resources.funcionarios", "active", "approved", "deny", "knowledge:deny", release),
                concept("tenant-b", "dev", "human-resources", "human-resources.funcionarios", "active", "approved", "allow", "knowledge:wrong-tenant", release)));

        List<DomainKnowledgeConcept> results = conceptRepository.findGovernedProjectKnowledgeCandidates(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                "concept",
                PageRequest.of(0, 10));

        assertThat(results)
                .extracting(DomainKnowledgeConcept::getConceptKey)
                .containsExactly(
                        "knowledge:global:constraint",
                        "knowledge:funcionarios:preference");
        assertThat(results)
                .allSatisfy(concept -> {
                    assertThat(concept.getLifecycle()).isEqualTo("active");
                    assertThat(concept.getCurationStatus()).isEqualTo("approved");
                    assertThat(concept.getAiVisibility()).isIn("allow", "mask", "summarize_only");
                    assertThat(concept.getSourceRelease()).isNotNull();
                    assertThat(concept.getSourceRelease().getReleaseKey()).isEqualTo("quickstart:human-resources:2026-04-30");
                });
    }

    private DomainKnowledgeConcept concept(
            String tenantId,
            String environment,
            String contextKey,
            String resourceKey,
            String lifecycle,
            String curationStatus,
            String aiVisibility,
            String conceptKey,
            DomainCatalogRelease release) {
        return DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .environment(environment)
                .conceptKey(conceptKey)
                .contextKey(contextKey)
                .resourceKey(resourceKey)
                .nodeType("concept")
                .label(conceptKey)
                .lifecycle(lifecycle)
                .curationStatus(curationStatus)
                .aiVisibility(aiVisibility)
                .payload("{\"kind\":\"project_preference\",\"summary\":\"Safe summary\"}")
                .sourceRelease(release)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
