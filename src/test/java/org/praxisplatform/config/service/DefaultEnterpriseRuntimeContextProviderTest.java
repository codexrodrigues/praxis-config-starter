package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DefaultEnterpriseRuntimeContextProviderTest {
    private static final EnterpriseRuntimeContextRequest SESSION = new EnterpriseRuntimeContextRequest(
            () -> "synthetic-session", "pt-BR", "UTC", "procurement");

    @Test
    void defaultsFailClosedForAuthenticatedSessionWithoutHostAuthority() {
        assertUnavailable(() -> new DefaultEnterpriseRuntimeContextProvider().getContext(SESSION));
        assertUnavailable(() -> new DefaultEnterpriseRuntimeContextSwitchProvider().switchContext(
                SESSION, new EnterpriseRuntimeContextSwitchCommand("choice", "selection")));
        assertUnavailable(() -> new DefaultEnterpriseRuntimeContextChoiceProvider().getChoices(SESSION, null, 50));
        assertUnavailable(() -> new DefaultEnterpriseRuntimeTenantProvider().getTenants(SESSION));
        assertUnavailable(() -> new DefaultEnterpriseRuntimeNavigationProvider().getNavigation(SESSION));
        assertUnavailable(() -> new DefaultEnterpriseRuntimeSecurityEventProvider().getSecurityEvents(SESSION));
    }

    @Test
    void defaultContextRequiresAServletPrincipal() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> new DefaultEnterpriseRuntimeContextProvider().getContext(null))
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(call)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
