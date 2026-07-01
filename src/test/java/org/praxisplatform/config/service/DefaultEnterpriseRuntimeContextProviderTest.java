package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;
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
    void shouldMaterializeSafeProfileAndModuleSwitchWithoutChangingTenantByDefault() {
        EnterpriseRuntimeContextSwitchResponse response = new DefaultEnterpriseRuntimeContextSwitchProvider()
                .switchContext(
                        new EnterpriseRuntimeContextRequest(
                                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                                "en-US",
                                "UTC",
                                "manager",
                                "benefits"),
                        new EnterpriseRuntimeContextSwitchCommand(
                                "tenant-a",
                                "operator",
                                "payroll",
                                "pt-BR",
                                "America/Sao_Paulo",
                                "demo switch"));

        assertThat(response.schemaVersion()).isEqualTo("praxis-enterprise-runtime-context-switch.v1");
        assertThat(response.accepted()).isTrue();
        assertThat(response.effectiveContext().activeTenant().tenantId()).isEqualTo("tenant-a");
        assertThat(response.effectiveContext().activeProfileId()).isEqualTo("operator");
        assertThat(response.effectiveContext().activeModuleKey()).isEqualTo("payroll");
        assertThat(response.effectiveContext().locale()).isEqualTo("pt-BR");
        assertThat(response.effectiveContext().timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(response.propagationHeaders())
                .containsEntry("X-Tenant-ID", "tenant-a")
                .containsEntry("X-Env", "prod")
                .containsEntry("X-Praxis-Profile-ID", "operator")
                .containsEntry("X-Praxis-Module-Key", "payroll")
                .containsEntry("X-Timezone", "America/Sao_Paulo");
        assertThat(response.capabilities())
                .containsExactly("runtime.context.switch", "runtime.context.switch.default-provider");
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    void shouldDenyTenantSwitchByDefaultWithoutHostOwnedAuthorization() {
        EnterpriseRuntimeContextSwitchResponse response = new DefaultEnterpriseRuntimeContextSwitchProvider()
                .switchContext(
                        new EnterpriseRuntimeContextRequest(
                                new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                                "pt-BR",
                                "America/Sao_Paulo",
                                "manager",
                                "payroll"),
                        new EnterpriseRuntimeContextSwitchCommand(
                                "tenant-b",
                                "operator",
                                "benefits",
                                null,
                                null,
                                "tenant switch"));

        assertThat(response.accepted()).isFalse();
        assertThat(response.message()).contains("host-owned");
        assertThat(response.effectiveContext().activeTenant().tenantId()).isEqualTo("tenant-a");
        assertThat(response.effectiveContext().activeProfileId()).isEqualTo("operator");
        assertThat(response.effectiveContext().activeModuleKey()).isEqualTo("benefits");
        assertThat(response.propagationHeaders())
                .containsEntry("X-Tenant-ID", "tenant-a")
                .doesNotContainEntry("X-Tenant-ID", "tenant-b");
        assertThat(response.capabilities())
                .containsExactly("runtime.context.switch.denied", "runtime.context.switch.default-provider");
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

    @Test
    void shouldReturnSafeEmptySecurityEventsByDefault() {
        EnterpriseRuntimeSecurityEventsResponse response = new DefaultEnterpriseRuntimeSecurityEventProvider()
                .getSecurityEvents(new EnterpriseRuntimeContextRequest(
                        new AiPrincipalContext("tenant-a", "user-a", "prod", true),
                        "pt-BR",
                        "America/Sao_Paulo",
                        "operator",
                        "payroll"));

        assertThat(response.schemaVersion()).isEqualTo("praxis-enterprise-runtime-security-events.v1");
        assertThat(response.events()).isEmpty();
        assertThat(response.capabilities()).containsExactly("runtime.security-events.read");
        assertThat(response.resolvedAt()).isNotNull();
    }
}
