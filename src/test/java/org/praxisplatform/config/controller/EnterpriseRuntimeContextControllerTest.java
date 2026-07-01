package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class EnterpriseRuntimeContextControllerTest {

    @Mock private AiPrincipalContextResolver principalContextResolver;
    @Mock private EnterpriseRuntimeContextProvider runtimeContextProvider;
    @Mock private EnterpriseRuntimeTenantProvider runtimeTenantProvider;

    private EnterpriseRuntimeContextController controller;

    @BeforeEach
    void setUp() {
        controller = new EnterpriseRuntimeContextController(
                principalContextResolver,
                runtimeContextProvider,
                runtimeTenantProvider);
    }

    @Test
    void shouldResolveRuntimeContextFromServerPrincipalAndSafeHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        EnterpriseRuntimeContextResponse providerResponse = new EnterpriseRuntimeContextResponse(
                "praxis-enterprise-runtime-context.v1",
                new EnterpriseRuntimeUser("user-a", "User A", true),
                new EnterpriseRuntimeTenant("tenant-a", "Tenant A", true),
                "prod",
                "pt-BR",
                "America/Sao_Paulo",
                "manager",
                "payroll",
                List.of("runtime.context.read"),
                Instant.parse("2026-07-01T12:00:00Z"));

        when(principalContextResolver.resolve(request, "tenant-hint", "user-hint", "dev"))
                .thenReturn(principalContext);
        when(runtimeContextProvider.getContext(any(EnterpriseRuntimeContextRequest.class)))
                .thenReturn(providerResponse);

        ResponseEntity<EnterpriseRuntimeContextResponse> response = controller.getContext(
                request,
                "tenant-hint",
                "user-hint",
                "dev",
                "pt-BR,pt;q=0.9",
                "America/Sao_Paulo",
                "manager",
                "payroll");

        assertThat(response.getBody()).isSameAs(providerResponse);
        verify(principalContextResolver).resolve(request, "tenant-hint", "user-hint", "dev");

        ArgumentCaptor<EnterpriseRuntimeContextRequest> captor =
                ArgumentCaptor.forClass(EnterpriseRuntimeContextRequest.class);
        verify(runtimeContextProvider).getContext(captor.capture());
        EnterpriseRuntimeContextRequest runtimeRequest = captor.getValue();
        assertThat(runtimeRequest.principalContext()).isSameAs(principalContext);
        assertThat(runtimeRequest.locale()).isEqualTo("pt-BR");
        assertThat(runtimeRequest.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(runtimeRequest.activeProfileId()).isEqualTo("manager");
        assertThat(runtimeRequest.activeModuleKey()).isEqualTo("payroll");
    }

    @Test
    void shouldResolveRuntimeTenantsFromServerPrincipalAndSafeHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        EnterpriseRuntimeTenantsResponse providerResponse = new EnterpriseRuntimeTenantsResponse(
                "praxis-enterprise-runtime-tenants.v1",
                new EnterpriseRuntimeTenant("tenant-a", "Tenant A", true),
                List.of(
                        new EnterpriseRuntimeTenant("tenant-a", "Tenant A", true),
                        new EnterpriseRuntimeTenant("tenant-b", "Tenant B", false)),
                List.of("runtime.tenants.read"),
                Instant.parse("2026-07-01T12:00:00Z"));

        when(principalContextResolver.resolve(request, "tenant-hint", "user-hint", "dev"))
                .thenReturn(principalContext);
        when(runtimeTenantProvider.getTenants(any(EnterpriseRuntimeContextRequest.class)))
                .thenReturn(providerResponse);

        ResponseEntity<EnterpriseRuntimeTenantsResponse> response = controller.getTenants(
                request,
                "tenant-hint",
                "user-hint",
                "dev",
                "pt-BR,pt;q=0.9",
                "America/Sao_Paulo",
                "manager",
                "payroll");

        assertThat(response.getBody()).isSameAs(providerResponse);
        verify(principalContextResolver).resolve(request, "tenant-hint", "user-hint", "dev");

        ArgumentCaptor<EnterpriseRuntimeContextRequest> captor =
                ArgumentCaptor.forClass(EnterpriseRuntimeContextRequest.class);
        verify(runtimeTenantProvider).getTenants(captor.capture());
        EnterpriseRuntimeContextRequest runtimeRequest = captor.getValue();
        assertThat(runtimeRequest.principalContext()).isSameAs(principalContext);
        assertThat(runtimeRequest.locale()).isEqualTo("pt-BR");
        assertThat(runtimeRequest.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(runtimeRequest.activeProfileId()).isEqualTo("manager");
        assertThat(runtimeRequest.activeModuleKey()).isEqualTo("payroll");
    }
}
