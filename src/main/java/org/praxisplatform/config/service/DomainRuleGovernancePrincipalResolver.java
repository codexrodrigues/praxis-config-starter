package org.praxisplatform.config.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Resolves governed actors from server authentication and enforces their IAM role. */
public class DomainRuleGovernancePrincipalResolver {
  private final AiPrincipalContextResolver principalContextResolver;
  private final boolean corporateMode;

  public DomainRuleGovernancePrincipalResolver(
      AiPrincipalContextResolver principalContextResolver, boolean corporateMode) {
    this.principalContextResolver = principalContextResolver;
    this.corporateMode = corporateMode;
  }

  public DomainRuleGovernancePrincipal resolve(
      HttpServletRequest request,
      String tenantHint,
      String environmentHint,
      String requiredRole) {
    AiPrincipalContext context = principalContextResolver.resolve(
        request, tenantHint, null, environmentHint);
    if (corporateMode && (request == null || !request.isUserInRole(requiredRole))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Authenticated principal requires IAM role " + requiredRole + ".");
    }
    return new DomainRuleGovernancePrincipal(
        context.tenantId(), context.userId(), context.environment());
  }
}
