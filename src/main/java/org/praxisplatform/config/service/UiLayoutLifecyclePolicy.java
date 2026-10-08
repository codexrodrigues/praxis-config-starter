package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Host-owned admission seam. There is deliberately no permissive default. */
@FunctionalInterface
public interface UiLayoutLifecyclePolicy {
  boolean allows(UiLayoutLifecycleOperation operation, String actor, String tenantId, String administrativeUnit,
      String environment, UiLayoutTarget rootTarget);

  static UiLayoutLifecyclePolicy denyAll() { return (operation, actor, tenant, unit, environment, target) -> false; }
}
