package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared data relation for admitted native documents. Format, null policy and authority are
 * checked by the caller; this does not normalize documents or attest their provenance.
 */
final class UiLayoutRevisionRelation {
  private UiLayoutRevisionRelation() {}

  static void requireUnchangedEnvelope(ObjectNode baseline, ObjectNode candidate) {
    ObjectNode originalEnvelope = baseline.deepCopy();
    ObjectNode candidateEnvelope = candidate.deepCopy();
    originalEnvelope.remove("config");
    candidateEnvelope.remove("config");
    if (!originalEnvelope.equals(candidateEnvelope)) invalid();
  }

  static void requireReproduction(ObjectNode baselineConfig, ObjectNode candidateConfig, ObjectNode patch) {
    UiLayoutAuthoredPresence.require(candidateConfig, patch);
    UiLayoutAuthoredPresence.requireKnownRemovals(baselineConfig, patch);
    ObjectNode reproduced = baselineConfig.deepCopy();
    UiLayoutMergePatch.apply(reproduced, patch);
    if (!reproduced.equals(candidateConfig)) invalid();
  }

  private static void invalid() {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Native revision changes its envelope or does not reproduce its authored candidate.");
  }
}
