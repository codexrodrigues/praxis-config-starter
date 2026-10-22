package org.praxisplatform.config.service;

import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Server-only application/namespace attestation around the existing authenticated lifecycle
 * context. The context root is registered by the host; it never grants authority over a new page
 * implicitly.
 */
public record PageCreationContext(
    String applicationId, String routeNamespace, UiLayoutLifecycleInvocation invocation) {
  public PageCreationContext {
    if (applicationId == null
        || applicationId.isBlank()
        || applicationId.length() > 255
        || !applicationId.equals(applicationId.trim())
        || applicationId.chars().anyMatch(Character::isISOControl)
        || routeNamespace == null
        || !routeNamespace.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
        || routeNamespace.length() > 512
        || invocation == null)
      throw new IllegalArgumentException("Page creation context is invalid.");
  }

  UiLayoutLifecycleInvocation forTarget(UiLayoutTarget target) {
    return new UiLayoutLifecycleInvocation(
        invocation.actorRef(),
        invocation.tenantId(),
        invocation.administrativeUnit(),
        invocation.environment(),
        invocation.contextVersion(),
        new UiLayoutCompositionRegistration(target, List.of(target)));
  }

  @Override
  public String toString() {
    return "PageCreationContext[redacted]";
  }
}
