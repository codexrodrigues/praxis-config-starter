package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutHistoricalEvidenceReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Host-owned access to exact historical native documents, including removed targets.
 * Must authorize the target and verify that B0, C0, the immutable patch and provenance are safe
 * for this actor. Reject protected content; do not redact or rewrite evidence under its old hash.
 * Operation admission alone never establishes content visibility. Must not retain the candidate.
 */
@FunctionalInterface
public interface UiLayoutHistoricalEvidenceAccess {
  /** Explicit opt-in to recovery that now includes patch content; never inherited from document visibility. */
  default void requireTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutValidationContext validation) {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,
        "Historical target and patch access is denied.");
  }

  void require(UiLayoutLifecycleInvocation invocation, UiLayoutHistoricalEvidenceReceipt.Target evidence, UiLayoutValidationContext validation);

  static UiLayoutHistoricalEvidenceAccess denyAll() {
    return (invocation, evidence, validation) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,
          "Historical evidence access is denied.");
    };
  }
}
