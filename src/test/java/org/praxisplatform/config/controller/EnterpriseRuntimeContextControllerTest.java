package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextChoice;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextChoicesResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeEffectiveContext;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;
import org.praxisplatform.config.service.EnterpriseRuntimeContextChoiceProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextSwitchProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeSecurityEventProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** MVC contract with host authority represented by provider mocks; not an IAM or database proof. */
@ExtendWith(MockitoExtension.class)
@Tag("unit")
class EnterpriseRuntimeContextControllerTest {
    private static final Principal PRINCIPAL = () -> "synthetic-session-subject";
    private static final Instant NOW = Instant.parse("2026-09-10T16:00:00Z");

    @Mock private EnterpriseRuntimeContextProvider contextProvider;
    @Mock private EnterpriseRuntimeContextSwitchProvider switchProvider;
    @Mock private EnterpriseRuntimeContextChoiceProvider choiceProvider;
    @Mock private EnterpriseRuntimeTenantProvider tenantProvider;
    @Mock private EnterpriseRuntimeNavigationProvider navigationProvider;
    @Mock private EnterpriseRuntimeSecurityEventProvider eventProvider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EnterpriseRuntimeContextController(
                contextProvider, switchProvider, choiceProvider, tenantProvider, navigationProvider, eventProvider)).build();
    }

    @Test
    void firstLoginWithoutTenantReachesHostAndReturnsSelectionRequired() throws Exception {
        when(contextProvider.getContext(any())).thenReturn(nonReady("selection-required", null));

        mvc.perform(get("/api/praxis/runtime/context").principal(PRINCIPAL)
                        .header("X-Tenant-ID", "forged-tenant")
                        .header("X-User-ID", "forged-user")
                        .header("Accept-Language", "pt-BR,pt;q=0.9")
                        .header("X-Timezone", " UTC "))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("selection-required"))
                .andExpect(jsonPath("$.effectiveContext").isEmpty());

        var captor = ArgumentCaptor.forClass(EnterpriseRuntimeContextRequest.class);
        verify(contextProvider).getContext(captor.capture());
        assertThat(captor.getValue().principal()).isSameAs(PRINCIPAL);
        assertThat(captor.getValue().locale()).isEqualTo("pt-BR");
        assertThat(captor.getValue().timezone()).isEqualTo("UTC");
        assertThat(captor.getValue().toString()).doesNotContain("synthetic", "forged");
    }

    @Test
    void selectionUsesOnlyOpaqueCommandAndReturnsCanonicalReadyState() throws Exception {
        when(switchProvider.switchContext(any(), any())).thenReturn(ready());

        mvc.perform(put("/api/praxis/runtime/context").principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choiceRef\":\"opaque-choice\",\"expectedSelectionVersion\":\"opaque-selection\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.effectiveContext.contextVersion").value("opaque-context"))
                .andExpect(jsonPath("$.effectiveContext.activeOrganizationId").value("safe-organization"))
                .andExpect(jsonPath("$.effectiveContext.authorities[0]").value("field.edit"))
                .andExpect(jsonPath("$.accepted").doesNotExist())
                .andExpect(jsonPath("$.propagationHeaders").doesNotExist());

        verify(switchProvider).switchContext(any(), eq(new EnterpriseRuntimeContextSwitchCommand(
                "opaque-choice", "opaque-selection")));
        verifyNoInteractions(contextProvider, choiceProvider, tenantProvider, navigationProvider, eventProvider);
    }

    @Test
    void choicesAreBoundedAndAvailableBeforeSelection() throws Exception {
        when(choiceProvider.getChoices(any(), isNull(), eq(50))).thenReturn(new EnterpriseRuntimeContextChoicesResponse(
                List.of(new EnterpriseRuntimeContextChoice("opaque-choice", "Purchasing", Map.of("unit", "Operations"))),
                "opaque-next"));

        mvc.perform(get("/api/praxis/runtime/context/choices").principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].choiceRef").value("opaque-choice"))
                .andExpect(jsonPath("$.nextCursor").value("opaque-next"));
        verify(choiceProvider).getChoices(any(), isNull(), eq(50));
    }

    @Test
    void malformedOrDimensionalCommandsFailBeforeHostMutation() throws Exception {
        var result = mvc.perform(put("/api/praxis/runtime/context").principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choiceRef\":\"opaque\",\"expectedSelectionVersion\":\"v\",\"targetTenantId\":\"forged\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_CONTEXT_COMMAND"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("forged");
        verifyNoInteractions(switchProvider);
    }

    @Test
    void sessionIsRequiredEvenWhenIdentityHeadersAreForged() throws Exception {
        mvc.perform(get("/api/praxis/runtime/context")
                        .header("X-Tenant-ID", "forged-tenant")
                        .header("X-User-ID", "forged-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        verifyNoInteractions(contextProvider, switchProvider, choiceProvider, tenantProvider, navigationProvider, eventProvider);
    }

    @Test
    void providerConflictAndUnavailableStateAreSanitized() throws Exception {
        when(switchProvider.switchContext(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "CONTEXT_SELECTION_CONFLICT"));
        mvc.perform(put("/api/praxis/runtime/context").principal(PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choiceRef\":\"opaque\",\"expectedSelectionVersion\":\"old\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_CONFLICT"));

        when(choiceProvider.getChoices(any(), isNull(), eq(50)))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private-source"));
        var result = mvc.perform(get("/api/praxis/runtime/context/choices").principal(PRINCIPAL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUNTIME_SOURCE_UNAVAILABLE"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-source");
    }

    @Test
    void tenantDiscoveryRemainsDistinctFromOpaqueSelection() throws Exception {
        when(tenantProvider.getTenants(any())).thenReturn(new EnterpriseRuntimeTenantsResponse(
                "tenant-discovery", null, List.of(), List.of(), NOW));

        mvc.perform(get("/api/praxis/runtime/tenants").principal(PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(tenantProvider).getTenants(any());
        verifyNoInteractions(contextProvider, switchProvider, choiceProvider);
    }

    private EnterpriseRuntimeContextResponse nonReady(String state, String reason) {
        return new EnterpriseRuntimeContextResponse("praxis-enterprise-runtime-context.v1", state,
                "opaque-selection", new EnterpriseRuntimeUser("safe-user", "Reference user", true), null, reason, NOW);
    }

    private EnterpriseRuntimeContextResponse ready() {
        var effective = new EnterpriseRuntimeEffectiveContext("opaque-context",
                new EnterpriseRuntimeTenant("safe-tenant", "Reference tenant", true), "safe-organization",
                "test", "pt-BR", "UTC", "buyer", "procurement", List.of("field.edit"),
                List.of("runtime.context.read"));
        return new EnterpriseRuntimeContextResponse("praxis-enterprise-runtime-context.v1", "ready",
                "opaque-selection-next", new EnterpriseRuntimeUser("safe-user", "Reference user", true),
                effective, null, NOW);
    }
}
