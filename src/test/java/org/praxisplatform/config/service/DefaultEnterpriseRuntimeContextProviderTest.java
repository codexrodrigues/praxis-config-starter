package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;

@Tag("unit")
class DefaultEnterpriseRuntimeContextProviderTest {

    private final DefaultEnterpriseRuntimeContextProvider provider =
            new DefaultEnterpriseRuntimeContextProvider();

    @Test
    void shouldProjectSafeRuntimeContextWithoutPrivateAuthDetails() {
        EnterpriseRuntimeContextResponse response = provider.getContext(
                new EnterpriseRuntimeContextRequest(
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                        "pt-BR",
                        "America/Sao_Paulo",
                        "manager",
                        "payroll"));

        assertThat(response.schemaVersion()).isEqualTo("praxis-enterprise-runtime-context.v1");
        assertThat(response.user().userId()).isEqualTo("user-a");
        assertThat(response.user().displayName()).isNull();
        assertThat(response.user().resolvedFromServerPrincipal()).isTrue();
        assertThat(response.activeTenant().tenantId()).isEqualTo("tenant-a");
        assertThat(response.activeTenant().label()).isEqualTo("tenant-a");
        assertThat(response.activeTenant().active()).isTrue();
        assertThat(response.environment()).isEqualTo("prod");
        assertThat(response.locale()).isEqualTo("pt-BR");
        assertThat(response.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(response.activeProfileId()).isEqualTo("manager");
        assertThat(response.activeModuleKey()).isEqualTo("payroll");
        assertThat(response.capabilities()).containsExactly("runtime.context.read");
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    void shouldProjectActiveTenantAsDefaultTenantChoiceWithoutPrivateEntitlements() {
        EnterpriseRuntimeTenantsResponse response = new DefaultEnterpriseRuntimeTenantProvider().getTenants(
                new EnterpriseRuntimeContextRequest(
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                        "pt-BR",
                        "America/Sao_Paulo",
                        "manager",
                        "payroll"));

        assertThat(response.schemaVersion()).isEqualTo("praxis-enterprise-runtime-tenants.v1");
        assertThat(response.activeTenant().tenantId()).isEqualTo("tenant-a");
        assertThat(response.activeTenant().label()).isEqualTo("tenant-a");
        assertThat(response.activeTenant().active()).isTrue();
        assertThat(response.tenants()).containsExactly(response.activeTenant());
        assertThat(response.capabilities()).containsExactly("runtime.tenants.read");
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    void shouldReturnSafeEmptyNavigationByDefault() {
        EnterpriseRuntimeNavigationResponse response = new DefaultEnterpriseRuntimeNavigationProvider()
                .getNavigation(new EnterpriseRuntimeContextRequest(
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                        "pt-BR",
                        "America/Sao_Paulo",
                        "operator",
                        "payroll"));

        assertThat(response.schemaVersion()).isEqualTo("praxis-enterprise-runtime-navigation.v1");
        assertThat(response.nodes()).isEmpty();
        assertThat(response.capabilities()).containsExactly("runtime.navigation.read");
        assertThat(response.resolvedAt()).isNotNull();
    }
}
