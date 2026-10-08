package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Serializes allocation of the first and later immutable revision for one exact definition. */
@FunctionalInterface
public interface UiLayoutRevisionAllocationLock {
  void lock(String tenantId, String environment, UiLayoutTarget target);
}
