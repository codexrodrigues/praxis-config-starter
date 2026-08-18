package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.data.domain.PageImpl;

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
}
