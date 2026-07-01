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
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationNode;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEvent;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextSwitchProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeSecurityEventProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class EnterpriseRuntimeContextControllerTest {

    @Mock private AiPrincipalContextResolver principalContextResolver;
    @Mock private EnterpriseRuntimeContextProvider runtimeContextProvider;
    @Mock private EnterpriseRuntimeContextSwitchProvider runtimeContextSwitchProvider;
    @Mock private EnterpriseRuntimeTenantProvider runtimeTenantProvider;
    @Mock private EnterpriseRuntimeNavigationProvider runtimeNavigationProvider;
    @Mock private EnterpriseRuntimeSecurityEventProvider runtimeSecurityEventProvider;

    private EnterpriseRuntimeContextController controller;

    @BeforeEach
    void setUp() {
        controller = new EnterpriseRuntimeContextController(
                principalContextResolver,
                runtimeContextProvider,
                runtimeContextSwitchProvider,
                runtimeTenantProvider,
                runtimeNavigationProvider,
                runtimeSecurityEventProvider);
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
    void shouldResolveRuntimeContextSwitchFromServerPrincipalSafeHeadersAndCommand() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        EnterpriseRuntimeContextSwitchCommand command = new EnterpriseRuntimeContextSwitchCommand(
                "tenant-b",
                "operator",
                "payroll",
                "pt-BR",
                "America/Sao_Paulo",
                "user selected tenant");
        EnterpriseRuntimeContextResponse effectiveContext = new EnterpriseRuntimeContextResponse(
                "praxis-enterprise-runtime-context.v1",
                new EnterpriseRuntimeUser("user-a", "User A", true),
                new EnterpriseRuntimeTenant("tenant-b", "Tenant B", true),
                "prod",
                "pt-BR",
                "America/Sao_Paulo",
                "operator",
                "payroll",
                List.of("runtime.context.read"),
                Instant.parse("2026-07-01T12:00:00Z"));
        EnterpriseRuntimeContextSwitchResponse providerResponse = new EnterpriseRuntimeContextSwitchResponse(
                "praxis-enterprise-runtime-context-switch.v1",
                true,
                "accepted",
                effectiveContext,
                java.util.Map.of("X-Tenant-ID", "tenant-b"),
                List.of("runtime.context.switch"),
                Instant.parse("2026-07-01T12:00:01Z"));

        when(principalContextResolver.resolve(request, "tenant-a", "user-hint", "dev"))
                .thenReturn(principalContext);
        when(runtimeContextSwitchProvider.switchContext(
                any(EnterpriseRuntimeContextRequest.class),
                any(EnterpriseRuntimeContextSwitchCommand.class)))
                .thenReturn(providerResponse);

        ResponseEntity<EnterpriseRuntimeContextSwitchResponse> response = controller.switchContext(
                request,
                "tenant-a",
                "user-hint",
                "dev",
                "pt-BR,pt;q=0.9",
                "America/Sao_Paulo",
                "manager",
                "benefits",
                command);

        assertThat(response.getBody()).isSameAs(providerResponse);
        verify(principalContextResolver).resolve(request, "tenant-a", "user-hint", "dev");

        ArgumentCaptor<EnterpriseRuntimeContextRequest> requestCaptor =
                ArgumentCaptor.forClass(EnterpriseRuntimeContextRequest.class);
        ArgumentCaptor<EnterpriseRuntimeContextSwitchCommand> commandCaptor =
                ArgumentCaptor.forClass(EnterpriseRuntimeContextSwitchCommand.class);
        verify(runtimeContextSwitchProvider).switchContext(requestCaptor.capture(), commandCaptor.capture());

        EnterpriseRuntimeContextRequest runtimeRequest = requestCaptor.getValue();
        assertThat(runtimeRequest.principalContext()).isSameAs(principalContext);
        assertThat(runtimeRequest.locale()).isEqualTo("pt-BR");
        assertThat(runtimeRequest.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(runtimeRequest.activeProfileId()).isEqualTo("manager");
        assertThat(runtimeRequest.activeModuleKey()).isEqualTo("benefits");
        assertThat(commandCaptor.getValue()).isSameAs(command);
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

    @Test
    void shouldResolveRuntimeNavigationFromServerPrincipalAndSafeHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        EnterpriseRuntimeNavigationResponse providerResponse = new EnterpriseRuntimeNavigationResponse(
                "praxis-enterprise-runtime-navigation.v1",
                List.of(new EnterpriseRuntimeNavigationNode(
                        "payroll",
                        "Payroll",
                        "resource",
                        "/api/human-resources/folhas-pagamento",
                        "/payroll",
                        "payroll",
                        "human-resources.folhas-pagamento",
                        "table",
                        "mark-paid",
                        "resource.read",
                        List.of())),
                List.of("runtime.navigation.read"),
                Instant.parse("2026-07-01T12:00:00Z"));

        when(principalContextResolver.resolve(request, "tenant-hint", "user-hint", "dev"))
                .thenReturn(principalContext);
        when(runtimeNavigationProvider.getNavigation(any(EnterpriseRuntimeContextRequest.class)))
                .thenReturn(providerResponse);

        ResponseEntity<EnterpriseRuntimeNavigationResponse> response = controller.getNavigation(
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
        verify(runtimeNavigationProvider).getNavigation(captor.capture());
        EnterpriseRuntimeContextRequest runtimeRequest = captor.getValue();
        assertThat(runtimeRequest.principalContext()).isSameAs(principalContext);
        assertThat(runtimeRequest.locale()).isEqualTo("pt-BR");
        assertThat(runtimeRequest.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(runtimeRequest.activeProfileId()).isEqualTo("manager");
        assertThat(runtimeRequest.activeModuleKey()).isEqualTo("payroll");
    }

    @Test
    void shouldResolveRuntimeSecurityEventsFromServerPrincipalAndSafeHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        EnterpriseRuntimeSecurityEventsResponse providerResponse = new EnterpriseRuntimeSecurityEventsResponse(
                "praxis-enterprise-runtime-security-events.v1",
                List.of(new EnterpriseRuntimeSecurityEvent(
                        "session-refresh-required",
                        "session.refresh_required",
                        "info",
                        "Session refresh is recommended.",
                        "tenant-a",
                        "prod",
                        Instant.parse("2026-07-01T12:00:00Z"),
                        java.util.Map.of("refreshRecommended", "true"))),
                List.of("runtime.security-events.read"),
                Instant.parse("2026-07-01T12:00:01Z"));

        when(principalContextResolver.resolve(request, "tenant-hint", "user-hint", "dev"))
                .thenReturn(principalContext);
        when(runtimeSecurityEventProvider.getSecurityEvents(any(EnterpriseRuntimeContextRequest.class)))
                .thenReturn(providerResponse);

        ResponseEntity<EnterpriseRuntimeSecurityEventsResponse> response = controller.getSecurityEvents(
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
        verify(runtimeSecurityEventProvider).getSecurityEvents(captor.capture());
        EnterpriseRuntimeContextRequest runtimeRequest = captor.getValue();
        assertThat(runtimeRequest.principalContext()).isSameAs(principalContext);
        assertThat(runtimeRequest.locale()).isEqualTo("pt-BR");
        assertThat(runtimeRequest.timezone()).isEqualTo("America/Sao_Paulo");
        assertThat(runtimeRequest.activeProfileId()).isEqualTo("manager");
        assertThat(runtimeRequest.activeModuleKey()).isEqualTo("payroll");
    }
}
