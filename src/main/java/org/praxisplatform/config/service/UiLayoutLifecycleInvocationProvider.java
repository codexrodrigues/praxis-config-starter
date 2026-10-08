package org.praxisplatform.config.service;

import java.security.Principal;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Host seam that resolves current authenticated context and native composition registration. */
@FunctionalInterface
public interface UiLayoutLifecycleInvocationProvider {
  UiLayoutLifecycleInvocation resolve(Principal principal, String contextVersion, UiLayoutTarget requestedRoot);
}
