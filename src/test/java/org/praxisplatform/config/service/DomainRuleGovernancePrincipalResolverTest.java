package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DomainRuleGovernancePrincipalResolverTest {
  private final AiPrincipalContextResolver contextResolver = mock(AiPrincipalContextResolver.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  @Test
  void corporateMutationUsesServerPrincipalAndRequiresIamRole() {
    when(contextResolver.resolve(request, "caller-tenant", null, "caller-env"))
        .thenReturn(new AiPrincipalContext("trusted-tenant", "iam-user-42", "prod", true));
    when(request.isUserInRole("RULE_COMPOSITION_APPROVER")).thenReturn(true);

    DomainRuleGovernancePrincipal principal = new DomainRuleGovernancePrincipalResolver(
        contextResolver, true).resolve(
            request, "caller-tenant", "caller-env", "RULE_COMPOSITION_APPROVER");

    assertThat(principal).isEqualTo(
        new DomainRuleGovernancePrincipal("trusted-tenant", "iam-user-42", "prod"));
  }

  @Test
  void corporateMutationFailsClosedWhenIamRoleIsAbsent() {
    when(contextResolver.resolve(request, null, null, null))
        .thenReturn(new AiPrincipalContext("tenant", "authenticated-user", "prod", true));
    when(request.isUserInRole("RULE_SNAPSHOT_PUBLISHER")).thenReturn(false);

    assertThatThrownBy(() -> new DomainRuleGovernancePrincipalResolver(contextResolver, true)
        .resolve(request, null, null, "RULE_SNAPSHOT_PUBLISHER"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("RULE_SNAPSHOT_PUBLISHER");
  }

  @Test
  void corporateSimulationAcceptsAnApproverWithoutConflatingItWithTheAuthor() {
    when(contextResolver.resolve(request, "caller-tenant", null, "caller-env"))
        .thenReturn(new AiPrincipalContext("trusted-tenant", "iam-approver-7", "prod", true));
    when(request.isUserInRole("RULE_DEFINITION_AUTHOR")).thenReturn(false);
    when(request.isUserInRole("RULE_DEFINITION_APPROVER")).thenReturn(true);

    DomainRuleGovernancePrincipal principal = new DomainRuleGovernancePrincipalResolver(
        contextResolver, true).resolveAnyRole(
            request,
            "caller-tenant",
            "caller-env",
            List.of("RULE_DEFINITION_AUTHOR", "RULE_DEFINITION_APPROVER"));

    assertThat(principal).isEqualTo(
        new DomainRuleGovernancePrincipal("trusted-tenant", "iam-approver-7", "prod"));
  }

  @Test
  void corporateSimulationRejectsAReaderWithoutAuthorOrApproverRole() {
    when(contextResolver.resolve(request, "caller-tenant", null, "caller-env"))
        .thenReturn(new AiPrincipalContext("trusted-tenant", "iam-reader-9", "prod", true));
    when(request.isUserInRole("RULE_DEFINITION_READER")).thenReturn(true);

    assertThatThrownBy(() -> new DomainRuleGovernancePrincipalResolver(contextResolver, true)
        .resolveAnyRole(
            request,
            "caller-tenant",
            "caller-env",
            List.of("RULE_DEFINITION_AUTHOR", "RULE_DEFINITION_APPROVER")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("RULE_DEFINITION_AUTHOR")
        .hasMessageContaining("RULE_DEFINITION_APPROVER");
  }

  @Test
  void capabilitiesReflectOnlyServerAuthenticatedRolesInCorporateMode() {
    when(request.isUserInRole("RULE_DEFINITION_AUTHOR")).thenReturn(true);
    DomainRuleGovernancePrincipalResolver resolver =
        new DomainRuleGovernancePrincipalResolver(contextResolver, true);

    assertThat(resolver.hasRole(request, "RULE_DEFINITION_AUTHOR")).isTrue();
    assertThat(resolver.hasRole(request, "RULE_DEFINITION_APPROVER")).isFalse();
    assertThat(resolver.hasRole(null, "RULE_DEFINITION_AUTHOR")).isFalse();
  }

  @Test
  void developmentModeExposesTheSameActionsThatItsMutationEndpointsAllow() {
    DomainRuleGovernancePrincipalResolver resolver =
        new DomainRuleGovernancePrincipalResolver(contextResolver, false);
    assertThat(resolver.hasRole(request, "RULE_DEFINITION_AUTHOR")).isTrue();
  }
}
