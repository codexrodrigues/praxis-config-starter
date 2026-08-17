package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.service.DomainRuleCatalogQueryService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;

@Tag("unit")
class DomainRuleCatalogControllerTest {

    @Test
    void resolvesScopeAndReaderAuthorityBeforeReturningCatalogPage() {
        DomainRuleCatalogQueryService catalog = mock(DomainRuleCatalogQueryService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("trusted-tenant", "reader", "prod");
        DomainRuleCatalogResponse response = new DomainRuleCatalogResponse(
                DomainRuleCatalogResponse.SCHEMA_VERSION, List.of(), 2, 10, false);
        when(resolver.resolve(request, "caller-tenant", "caller-env", "RULE_DEFINITION_READER"))
                .thenReturn(principal);
        when(catalog.search("payroll", "calculation", "approved", "payroll", 2, 10, principal))
                .thenReturn(response);

        var entity = new DomainRuleCatalogController(catalog, resolver).catalog(
                "caller-tenant", "caller-env", "payroll", "calculation", "approved", "payroll", 2, 10, request);

        assertThat(entity.getBody()).isSameAs(response);
        verify(resolver).resolve(request, "caller-tenant", "caller-env", "RULE_DEFINITION_READER");
        verify(catalog).search("payroll", "calculation", "approved", "payroll", 2, 10, principal);
    }
}
