package org.praxisplatform.config.service;

/** Host-owned visibility of each complete current native baseline used for advisory diagnosis. */
@FunctionalInterface
public interface UiLayoutCurrentEvolutionEvidenceAccess {
  /** Authorize the current target and its native content; do not mutate, log or retain denied evidence. */
  void require(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceSeed.TargetSeed evidence, UiLayoutValidationContext validation);

  static UiLayoutCurrentEvolutionEvidenceAccess denyAll() {
    return (invocation, evidence, validation) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,
          "Current evolution evidence access is denied.");
    };
  }
}
