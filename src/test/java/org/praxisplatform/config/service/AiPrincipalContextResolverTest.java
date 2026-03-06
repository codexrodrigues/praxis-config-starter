package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AiPrincipalContextResolverTest {

    @Test
    void shouldResolveCorporateIdentityFromServerContext() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                true,
                false,
                "demo",
                "demo",
                "local",
                "prod");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("tenantId", "tenant-a");
        request.setAttribute("env", "corp");
        request.setUserPrincipal(new StaticPrincipal("user-a"));

        AiPrincipalContext context = resolver.resolve(request, "header-tenant", "header-user", "header-env");

        assertThat(context.tenantId()).isEqualTo("tenant-a");
        assertThat(context.userId()).isEqualTo("user-a");
        assertThat(context.environment()).isEqualTo("corp");
        assertThat(context.resolvedFromServerPrincipal()).isTrue();
    }

    @Test
    void shouldRejectCorporateRequestWithoutServerIdentity() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                true,
                true,
                "demo",
                "demo",
                "local",
                "prod");
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> resolver.resolve(request, "tenant", "user", "local"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void shouldAllowHeaderFallbackOnlyWhenLocalFlagEnabled() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                false,
                true,
                "demo",
                "demo",
                "local",
                "prod");
        AiPrincipalContext context = resolver.resolve(
                new MockHttpServletRequest(),
                "tenant-h",
                "user-h",
                "dev");

        assertThat(context.tenantId()).isEqualTo("tenant-h");
        assertThat(context.userId()).isEqualTo("user-h");
        assertThat(context.environment()).isEqualTo("dev");
    }

    @Test
    void shouldIgnoreAnonymousPrincipalAndUseHeaderIdentityInLocalMode() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                false,
                true,
                "demo",
                "demo",
                "local",
                "prod");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new StaticPrincipal("anonymousUser"));

        AiPrincipalContext context = resolver.resolve(request, "tenant-h", "user-h", "dev");

        assertThat(context.tenantId()).isEqualTo("tenant-h");
        assertThat(context.userId()).isEqualTo("user-h");
        assertThat(context.environment()).isEqualTo("dev");
        assertThat(context.resolvedFromServerPrincipal()).isFalse();
    }

    @Test
    void shouldRejectAnonymousPrincipalInCorporateMode() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                true,
                false,
                "demo",
                "demo",
                "local",
                "prod");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("tenantId", "tenant-a");
        request.setUserPrincipal(new StaticPrincipal("anonymousUser"));

        assertThatThrownBy(() -> resolver.resolve(request, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void shouldResolveCorporateIdentityFromNestedPrincipalClaims() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                true,
                false,
                "demo",
                "demo",
                "local",
                "prod");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new PrincipalWithNestedClaims(Map.of(
                "tenantId", "tenant-claims",
                "userId", "user-claims",
                "environment", "corp")));

        AiPrincipalContext context = resolver.resolve(request, null, null, null);

        assertThat(context.tenantId()).isEqualTo("tenant-claims");
        assertThat(context.userId()).isEqualTo("user-claims");
        assertThat(context.environment()).isEqualTo("corp");
    }

    @Test
    void shouldAllowCorporateDefaultTenantWhenInfrastructureProvidesOnlyUser() {
        AiPrincipalContextResolver resolver = new AiPrincipalContextResolver(
                true,
                false,
                "demo",
                "demo",
                "local",
                "prod");
        ReflectionTestUtils.setField(resolver, "allowDefaultTenantInCorporate", true);
        ReflectionTestUtils.setField(resolver, "serverDefaultTenant", "corp-tenant");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new StaticPrincipal("user-only"));

        AiPrincipalContext context = resolver.resolve(request, null, null, null);

        assertThat(context.tenantId()).isEqualTo("corp-tenant");
        assertThat(context.userId()).isEqualTo("user-only");
        assertThat(context.environment()).isEqualTo("prod");
    }

    private static final class StaticPrincipal implements Principal {
        private final String name;

        private StaticPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final class PrincipalWithNestedClaims implements Principal {
        private final Map<String, String> claims;

        private PrincipalWithNestedClaims(Map<String, String> claims) {
            this.claims = claims;
        }

        @Override
        public String getName() {
            return null;
        }

        @SuppressWarnings("unused")
        public Map<String, String> getPrincipal() {
            return claims;
        }
    }
}
