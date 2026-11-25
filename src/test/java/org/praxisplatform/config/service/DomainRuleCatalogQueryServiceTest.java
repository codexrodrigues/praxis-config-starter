package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleCatalogSummaryResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@Tag("unit")
class DomainRuleCatalogQueryServiceTest {

    @Test
    void returnsOnlySafeScopedIdentityFieldsWithBoundedPagination() {
        DomainRuleDefinitionRepository repository = Mockito.mock(DomainRuleDefinitionRepository.class);
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("prod")
                .ruleKey("human-resources.payroll.net-salary")
                .version(3)
                .ruleType("calculation")
                .status("approved")
                .contextKey("human-resources.payroll")
                .resourceKey("human-resources.folhas-pagamento")
                .serviceKey("payroll")
                .semanticOwner("people-operations")
                .steward("payroll-policy")
                .condition("{\"var\":\"salary\"}")
                .governance("{\"secret\":true}")
                .updatedAt(Instant.parse("2026-08-16T12:00:00Z"))
                .build();
        when(repository.searchCatalogCandidates(
                eq("tenant-a"), eq("prod"), eq("salary"), eq("calculation"), eq("approved"),
                eq("human-resources.folhas-pagamento"), any()))
                .thenReturn(new PageImpl<>(List.of(definition)));

        DomainRuleCatalogResponse result = new DomainRuleCatalogQueryService(repository).search(
                " salary ",
                "calculation",
                "approved",
                "human-resources.folhas-pagamento",
                0,
                6,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod"));

        assertThat(result.schemaVersion()).isEqualTo("praxis-domain-rule-catalog.v1");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.ruleKey()).isEqualTo("human-resources.payroll.net-salary");
            assertThat(candidate.version()).isEqualTo(3);
            assertThat(candidate.resourceKey()).isEqualTo("human-resources.folhas-pagamento");
        });
        assertThat(result.toString()).doesNotContain("salary\"}", "secret");
        verify(repository).searchCatalogCandidates(
                eq("tenant-a"), eq("prod"), eq("salary"), eq("calculation"), eq("approved"),
                eq("human-resources.folhas-pagamento"), any());
    }

    @Test
    void rejectsUnboundedOrUnscopedSearches() {
        DomainRuleCatalogQueryService service = new DomainRuleCatalogQueryService(
                Mockito.mock(DomainRuleDefinitionRepository.class));

        assertThatThrownBy(() -> service.search(null, null, null, null, 0, 13,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.search(null, null, null, null, 0, 6,
                new DomainRuleGovernancePrincipal(null, "reader", "prod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void usesTypedEmptyQuerySentinelWhenSearchTextIsAbsent() {
        DomainRuleDefinitionRepository repository = Mockito.mock(DomainRuleDefinitionRepository.class);
        when(repository.searchCatalogCandidates(
                eq("tenant-a"), eq("prod"), eq(""), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of()));

        new DomainRuleCatalogQueryService(repository).search(
                null, null, null, null, 0, 6,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod"));

        verify(repository).searchCatalogCandidates(
                eq("tenant-a"), eq("prod"), eq(""), eq(null), eq(null), eq(null), any());
    }

    @Test
    void browseSupportsHigherWorkstationLimitTotalMetadataAndSafeLabelsWithoutLeakingConditionOrGovernance() {
        DomainRuleDefinitionRepository repository = Mockito.mock(DomainRuleDefinitionRepository.class);
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("prod")
                .ruleKey("human-resources.payroll.net-salary")
                .version(2)
                .ruleType("calculation")
                .status("active")
                .contextKey("human-resources.payroll")
                .resourceKey("human-resources.folhas-pagamento")
                .serviceKey("payroll")
                .definition("""
                        {
                          "documentation": { "title": "Salário Líquido Base" },
                          "presentationLabels": {
                            "domain": { "pt-BR": "Folha de Pagamento" },
                            "ruleSet": { "pt-BR": "Cálculo Salarial" }
                          }
                        }
                        """)
                .condition("{\"var\":\"hidden_secret_salary_fact\"}")
                .governance("{\"hiddenSecretFlag\":true}")
                .updatedAt(Instant.parse("2026-08-16T12:00:00Z"))
                .build();
        when(repository.searchCatalogCandidatesByContext(
                eq("tenant-a"), eq("prod"), eq(""), eq(null), eq(null), eq(null),
                eq("human-resources.payroll"), eq(PageRequest.of(0, 100))))
                .thenReturn(new PageImpl<>(List.of(definition), PageRequest.of(0, 100), 1_006));

        DomainRuleCatalogQueryService service = new DomainRuleCatalogQueryService(
                repository, null, new ObjectMapper());
        DomainRuleCatalogResponse response = service.browse(
                null, null, null, null, "human-resources.payroll", 0, 100,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod"));

        assertThat(response.totalElements()).isEqualTo(1_006L);
        assertThat(response.totalPages()).isEqualTo(11);
        assertThat(response.truncated()).isFalse();
        assertThat(response.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.displayName()).isEqualTo("Salário Líquido Base");
            assertThat(candidate.domainLabel()).isEqualTo("Folha de Pagamento");
            assertThat(candidate.ruleSetLabel()).isEqualTo("Cálculo Salarial");
        });
        assertThat(response.toString())
                .doesNotContain("hidden_secret_salary_fact")
                .doesNotContain("hiddenSecretFlag");

        assertThatThrownBy(() -> service.browse(
                null, null, null, null, null, 0, 101,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.browse(
                null, null, null, null, null, 501, 50,
                new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @Test
    void summarizesLatestPortfolioCountsAndWorkspaceQueueByScope() {
        DomainRuleDefinitionRepository definitions = Mockito.mock(DomainRuleDefinitionRepository.class);
        DomainRuleChangeWorkspaceRepository workspaces = Mockito.mock(DomainRuleChangeWorkspaceRepository.class);
        when(definitions.countLatestByRuleType("tenant-a", "prod"))
                .thenReturn(List.of(
                        new Object[] {"validation", 800L},
                        new Object[] {"calculation", 206L},
                        new Object[] {"authorization_policy", 14L}));
        when(definitions.countLatestBusinessByContextKey("tenant-a", "prod", "authorization_policy"))
                .thenReturn(List.of(new Object[] {"workforce.payroll", 600L}, new Object[] {"workforce.benefits", 406L}));
        when(definitions.countLatestBusinessByResourceKey("tenant-a", "prod", "authorization_policy"))
                .thenReturn(List.<Object[]>of(new Object[] {"workforce.payroll.runs", 1006L}));
        when(definitions.countLatestBusinessByStatus("tenant-a", "prod", "authorization_policy"))
                .thenReturn(List.of(new Object[] {"active", 1000L}, new Object[] {"draft", 6L}));
        when(workspaces.countByStatus("tenant-a", "prod"))
                .thenReturn(List.of(
                        new Object[] {"OPEN", 3L},
                        new Object[] {"SUBMITTED", 2L},
                        new Object[] {"APPROVED", 1L},
                        new Object[] {"REJECTED", 1L},
                        new Object[] {"PROMOTED", 9L}));

        DomainRuleCatalogSummaryResponse summary = new DomainRuleCatalogQueryService(
                definitions, workspaces, new ObjectMapper())
                .summarize(new DomainRuleGovernancePrincipal("tenant-a", "reader", "prod"));

        assertThat(summary.schemaVersion()).isEqualTo(DomainRuleCatalogSummaryResponse.SCHEMA_VERSION);
        assertThat(summary.totalDefinitions()).isEqualTo(1_020L);
        assertThat(summary.totalBusinessRules()).isEqualTo(1_006L);
        assertThat(summary.totalAuthorizationPolicies()).isEqualTo(14L);
        assertThat(summary.byContextKey()).extracting(DomainRuleCatalogSummaryResponse.BucketCount::key)
                .containsExactly("workforce.payroll", "workforce.benefits");
        assertThat(summary.reviewQueue().openDrafts()).isEqualTo(3L);
        assertThat(summary.reviewQueue().inReview()).isEqualTo(2L);
        assertThat(summary.reviewQueue().approved()).isEqualTo(1L);
        assertThat(summary.reviewQueue().rejected()).isEqualTo(1L);
        assertThat(summary.reviewQueue().promoted()).isEqualTo(9L);
        assertThat(summary.reviewQueue().totalPending()).isEqualTo(7L);
    }
}
