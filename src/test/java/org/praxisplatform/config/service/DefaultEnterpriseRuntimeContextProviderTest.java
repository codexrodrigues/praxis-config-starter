package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;

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
}
