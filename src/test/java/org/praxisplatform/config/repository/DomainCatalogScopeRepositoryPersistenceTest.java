package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.Scope;
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
        "spring.datasource.url=jdbc:h2:mem:domain_catalog_scope_repo_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:domain-catalog-scope-repository-test-schema.sql",
        "spring.flyway.enabled=false"
})
@Tag("integration")
class DomainCatalogScopeRepositoryPersistenceTest {

    @Autowired
    private DomainCatalogReleaseRepository releaseRepository;

    @Autowired
    private DomainCatalogItemRepository itemRepository;

    @Autowired
    private AiRegistryRepository aiRegistryRepository;

    @Autowired
    private ApiMetadataRepository apiMetadataRepository;

    @Test
    void isolatesEqualReleaseKeysAndTheirItemsByTenantAndEnvironment() {
        DomainCatalogRelease tenantA = release("shared-release", "tenant-a", "dev");
        DomainCatalogRelease tenantB = release("shared-release", "tenant-b", "prod");
        releaseRepository.saveAndFlush(tenantA);
        releaseRepository.saveAndFlush(tenantB);
        itemRepository.saveAndFlush(item(tenantA, "tenant-a-field"));
        itemRepository.saveAndFlush(item(tenantB, "tenant-b-field"));

        assertThat(releaseRepository.findByReleaseKeyAndScope("shared-release", "tenant-a", "dev"))
                .contains(tenantA);
        assertThat(releaseRepository.findByReleaseKeyAndScope("shared-release", "tenant-b", "prod"))
                .contains(tenantB);
        assertThat(releaseRepository.findByReleaseKeyAndScope("shared-release", "tenant-a", "prod"))
                .isEmpty();

        assertThat(itemRepository.search(tenantA, "node", null, null, null, PageRequest.of(0, 10)))
                .extracting(DomainCatalogItem::getItemKey)
                .containsExactly("tenant-a-field");
        assertThat(itemRepository.search(tenantB, "node", null, null, null, PageRequest.of(0, 10)))
                .extracting(DomainCatalogItem::getItemKey)
                .containsExactly("tenant-b-field");

        var releaseSummaries = releaseRepository.findLatestSummaries(
                "praxis-service",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                PageRequest.of(0, 10));
        assertThat(releaseSummaries).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo(tenantA.getId());
                    assertThat(summary.releaseKey()).isEqualTo("shared-release");
                    assertThat(summary.resourceKey()).isEqualTo("human-resources.folhas-pagamento");
                });

        assertThat(itemRepository.searchSummariesAcrossReleaseIds(
                        List.of(tenantA.getId()),
                        "node",
                        null,
                        "field",
                        null,
                        PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.releaseKey()).isEqualTo("shared-release");
                    assertThat(summary.itemKey()).isEqualTo("tenant-a-field");
                    assertThat(summary.payload()).contains("Scoped field");
                });
    }

    @Test
    void readsRegistryMaterialWithoutHydratingTheEmbeddingColumn() {
        AiRegistry definition = AiRegistry.builder()
                .registryType("component_definition")
                .registryKey("praxis-table")
                .componentType("component-definition")
                .scope(Scope.SYSTEM)
                .scopeKey("GLOBAL")
                .payload("{\"componentDefinition\":{\"id\":\"praxis-table\"}}")
                .build();
        aiRegistryRepository.saveAndFlush(definition);

        assertThat(aiRegistryRepository.findMaterialSummaries(
                        "component_definition",
                        "component-definition",
                        Scope.SYSTEM,
                        "GLOBAL"))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo(definition.getId());
                    assertThat(summary.registryKey()).isEqualTo("praxis-table");
                    assertThat(summary.payload()).contains("componentDefinition");
                });
    }

    @Test
    void readsApiCandidateSummariesWithoutHydratingLargeArtifacts() {
        ApiMetadata endpoint = new ApiMetadata(
                "/api/operations/missoes/filter",
                "POST",
                "desenv",
                "local",
                "default",
                "v1",
                "1.0.0",
                "2026-08-31T00:00:00Z",
                "operations,missions",
                "Filtra missões",
                "Busca governada de missões",
                "filterMissions",
                "{\"type\":\"object\"}",
                "{\"type\":\"array\"}",
                "[]",
                "{\"path\":\"/api/operations/missoes/filter\"}",
                List.of(0.1f, 0.2f));
        apiMetadataRepository.saveAndFlush(endpoint);

        assertThat(apiMetadataRepository.findCandidateProjectionsByScope(
                        "desenv", "local", "default", "v1"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getPath()).isEqualTo("/api/operations/missoes/filter");
                    assertThat(candidate.getMethod()).isEqualTo("POST");
                    assertThat(candidate.getOperationId()).isEqualTo("filterMissions");
                    assertThat(candidate.getRequestSchema()).isNull();
                    assertThat(candidate.getResponseSchema()).isNull();
                    assertThat(candidate.getParameters()).isNull();
                    assertThat(candidate.getRawJson()).isNull();
                });
    }

    private DomainCatalogRelease release(String releaseKey, String tenantId, String environment) {
        return DomainCatalogRelease.builder()
                .id(UUID.randomUUID())
                .releaseKey(releaseKey)
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .resourceKey("human-resources.folhas-pagamento")
                .sourceHash("source-hash")
                .tenantId(tenantId)
                .environment(environment)
                .rawPayload("{}")
                .createdAt(Instant.now())
                .build();
    }

    private DomainCatalogItem item(DomainCatalogRelease release, String itemKey) {
        return DomainCatalogItem.builder()
                .id(UUID.randomUUID())
                .release(release)
                .itemType("node")
                .itemKey(itemKey)
                .nodeType("field")
                .payload("{\"label\":\"Scoped field\"}")
                .searchableText("Scoped field")
                .createdAt(Instant.now())
                .build();
    }
}
