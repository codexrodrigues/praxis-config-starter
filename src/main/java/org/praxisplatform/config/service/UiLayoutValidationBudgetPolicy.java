package org.praxisplatform.config.service;

import java.time.Duration;

/** Host-admitted, local bounded budget selection. No default bean or HTTP-controlled budget. */
@FunctionalInterface
public interface UiLayoutValidationBudgetPolicy {
  /**
   * Select an explicitly admitted positive, nanosecond-representable budget for this server
   * invocation. This local lookup precedes the attempt and must itself be bounded. It must
   * not consult client-selected execution controls or renew an existing attempt.
   */
  Duration budget(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation);
}
