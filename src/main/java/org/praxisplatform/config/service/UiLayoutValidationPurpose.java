package org.praxisplatform.config.service;

/** Validation/visibility purpose, distinct from the initiating command and its grants. */
public enum UiLayoutValidationPurpose {
  /** Initial capture and mutable authoring validation; operation admission remains independent. */
  AUTHORING,
  /** Current workspace projection and lifecycle state reads; does not admit native execution. */
  CURRENT_WORKSPACE_READ,
  /** Exact immutable evidence, including targets absent from current composition. */
  HISTORICAL_EVIDENCE_READ,
  /** Current native observation for advisory evolution diagnosis, without authoring permission. */
  EVOLUTION_CURRENT_READ,
  /** Complete frozen validation under publish/rollback authority. */
  FROZEN_RELEASE
}
