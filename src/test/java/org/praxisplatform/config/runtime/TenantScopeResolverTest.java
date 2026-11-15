package org.praxisplatform.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class TenantScopeResolverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exposesDomainAgnosticCanonicalTenantAndOrganizationContracts() {
        assertThat(TenantScopeResolver.TENANT_HEADERS)
                .containsExactly("X-Tenant-ID", "X-Tenant");
        assertThat(TenantScopeResolver.ORGANIZATION_HEADERS)
                .containsExactly("X-Organization-ID");
        assertThat(TenantScopeResolver.TENANT_CONTEXT_KEYS)
                .containsExactly("tenantId");
        assertThat(TenantScopeResolver.ORGANIZATION_CONTEXT_KEYS)
                .containsExactly("organizationId", "companyId");
    }

    @Test
    void allowsTenantAndIntraTenantOrganizationHeadersSimultaneouslyWithoutConflict() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-ID", " rio ");
        request.addHeader("X-Tenant", "rio");
        request.addHeader("X-Organization-ID", " 2 ");

        assertThat(TenantScopeResolver.resolveHeaderTenant(request)).contains("rio");
        assertThat(TenantScopeResolver.resolveHeaderOrganization(request)).contains("2");
    }

    @Test
    void rejectsContradictoryTenantHeadersAndDuplicateValuesFailClosed() {
        MockHttpServletRequest crossHeaderConflict = new MockHttpServletRequest();
        crossHeaderConflict.addHeader("X-Tenant-ID", "rio");
        crossHeaderConflict.addHeader("X-Tenant", "df");

        assertThatThrownBy(() -> TenantScopeResolver.resolveHeaderTenant(crossHeaderConflict))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        MockHttpServletRequest duplicateTenantConflict = new MockHttpServletRequest();
        duplicateTenantConflict.addHeader("X-Tenant-ID", "rio");
        duplicateTenantConflict.addHeader("X-Tenant-ID", "rj");

        assertThatThrownBy(() -> TenantScopeResolver.resolveHeaderTenant(duplicateTenantConflict))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        MockHttpServletRequest duplicateOrgConflict = new MockHttpServletRequest();
        duplicateOrgConflict.addHeader("X-Organization-ID", "1");
        duplicateOrgConflict.addHeader("X-Organization-ID", "2");

        assertThatThrownBy(
                        () -> TenantScopeResolver.resolveHeaderOrganization(duplicateOrgConflict))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void enforcesAuthorizedTenantAndAuthorizedIntraTenantOrganizations() {
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("rio", "gestor@rio.gov.br", "prod");

        assertThat(TenantScopeResolver.requireAuthorizedTenant(null, principal)).isEqualTo("rio");
        assertThat(TenantScopeResolver.requireAuthorizedTenant(" rio ", principal))
                .isEqualTo("rio");

        assertThatThrownBy(() -> TenantScopeResolver.requireAuthorizedTenant("rj", principal))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        DomainRuleGovernancePrincipal missingTenant =
                new DomainRuleGovernancePrincipal(" ", "anon", "prod");
        assertThatThrownBy(() -> TenantScopeResolver.requireAuthorizedTenant(null, missingTenant))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        Set<String> allowedOrgs = Set.of("1", "2", "GROUP:ADM_INDIRETA");
        assertThat(TenantScopeResolver.requireAuthorizedOrganization(null, allowedOrgs)).isEmpty();
        assertThat(TenantScopeResolver.requireAuthorizedOrganization("  ", allowedOrgs)).isEmpty();
        assertThat(TenantScopeResolver.requireAuthorizedOrganization("ALL", allowedOrgs)).isEmpty();
        assertThat(TenantScopeResolver.requireAuthorizedOrganization("ORG:2", allowedOrgs))
                .contains("2");
        assertThat(TenantScopeResolver.requireAuthorizedOrganization(" 2 ", allowedOrgs))
                .contains("2");

        assertThatThrownBy(() -> TenantScopeResolver.requireAuthorizedOrganization("ALL", Set.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(
                        () -> TenantScopeResolver.requireAuthorizedOrganization("ORG:9", allowedOrgs))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> TenantScopeResolver.requireAuthorizedOrganization("9", allowedOrgs))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(
                        TenantScopeResolver.requireAuthorizedOrganizationScope(
                                "ALL", Set.of("1", "3"), ignored -> java.util.Optional.empty()))
                .containsExactlyInAnyOrder("1", "3");
        assertThat(
                        TenantScopeResolver.requireAuthorizedOrganizationScope(
                                "ORG:3", Set.of("1", "3"), ignored -> java.util.Optional.empty()))
                .containsExactly("3");
        assertThat(
                        TenantScopeResolver.requireAuthorizedOrganizationScope(
                                "GROUP:REDE_INTEGRADA",
                                Set.of("1", "2", "3"),
                                key ->
                                        "REDE_INTEGRADA".equals(key)
                                                ? java.util.Optional.of(Set.of("1", "3"))
                                                : java.util.Optional.empty()))
                .containsExactlyInAnyOrder("1", "3");
        assertThatThrownBy(
                        () ->
                                TenantScopeResolver.requireAuthorizedOrganizationScope(
                                        "GROUP:REDE_INTEGRADA",
                                        Set.of("1", "2"),
                                        key -> java.util.Optional.of(Set.of("1", "3"))))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void verifiesTwoLevelFactsContextScopeWithoutConflatingTenantAndOrganization() {
        ObjectNode validMultiOrgContext =
                JSON.createObjectNode()
                        .put("tenantId", "rio")
                        .put("organizationId", "2")
                        .put("companyId", "2");

        // 2-arg tenant check does not falsely fail when organizationId ("2") != tenantId ("rio")
        assertThatCode(
                        () ->
                                TenantScopeResolver.verifyFactsContextScope(
                                        validMultiOrgContext, "rio"))
                .doesNotThrowAnyException();

        // 3-arg check validates both tenantId == "rio" and organizationId/companyId in {"1", "2"}
        assertThatCode(
                        () ->
                                TenantScopeResolver.verifyFactsContextScope(
                                        validMultiOrgContext, "rio", Set.of("1", "2")))
                .doesNotThrowAnyException();

        // Rejects cross-tenant spoofing
        assertThatThrownBy(
                        () ->
                                TenantScopeResolver.verifyFactsContextScope(
                                        validMultiOrgContext, "rj", Set.of("1", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");

        // Rejects unauthorized intra-tenant companyId
        ObjectNode unauthorizedCompanyContext =
                JSON.createObjectNode().put("tenantId", "rio").put("companyId", "4");
        assertThatThrownBy(
                        () ->
                                TenantScopeResolver.verifyFactsContextScope(
                                        unauthorizedCompanyContext, "rio", Set.of("1", "2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("companyId");
    }
}
