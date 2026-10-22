package org.praxisplatform.config.service;

import java.security.Principal;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Host seam that resolves current authenticated context and native composition registration.
 * For navigation groups, the host owns one stable app-shell UUID mapping per tenant/application/
 * environment/route namespace. Resolve that existing mapping from authenticated host context and
 * reject every requested alternative root, even if it is a syntactically valid UUID. Neither the
 * requested root nor a stored release grants registration; do not allocate roots during resolution.
 */
@FunctionalInterface
public interface UiLayoutLifecycleInvocationProvider {
  UiLayoutLifecycleInvocation resolve(Principal principal, String contextVersion, UiLayoutTarget requestedRoot);
}
