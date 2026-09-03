package org.praxisplatform.config.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

  /**
   * Resolves a server-authenticated principal that owns at least one of the supplied IAM roles.
   * Use this only when the canonical operation is intentionally shared by distinct governance
   * personas, such as a structural simulation executed by either its author or an approver.
   */
  public DomainRuleGovernancePrincipal resolveAnyRole(
      HttpServletRequest request,
      String tenantHint,
      String environmentHint,
      List<String> requiredRoles) {
    if (requiredRoles == null || requiredRoles.isEmpty()) {
      throw new IllegalArgumentException("requiredRoles must not be empty");
    }
    AiPrincipalContext context = principalContextResolver.resolve(
        request, tenantHint, null, environmentHint);
    boolean authorized = request != null
        && requiredRoles.stream().anyMatch(request::isUserInRole);
    if (corporateMode && !authorized) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Authenticated principal requires one of IAM roles " + requiredRoles + ".");
    }
    return new DomainRuleGovernancePrincipal(
        context.tenantId(), context.userId(), context.environment());
  }

  /** Returns whether the server-authenticated principal owns the requested IAM role. */
  public boolean hasRole(HttpServletRequest request, String role) {
    return !corporateMode || (request != null && request.isUserInRole(role));
  }
}
