package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared Config rules: equal authored values remain pinned; reset can only remove an existing property. */
final class UiLayoutAuthoredPresence {
  private UiLayoutAuthoredPresence() {}
  static void require(JsonNode candidate, JsonNode patch) {
    if (candidate.isObject()) {
      if (!patch.isObject()) invalid();
      candidate.properties().forEach(entry -> {
        if (!patch.has(entry.getKey())) invalid();
        require(entry.getValue(), patch.get(entry.getKey()));
      });
    } else if (!candidate.equals(patch)) invalid();
  }
  static void requireKnownRemovals(JsonNode baseline, JsonNode patch) {
    if (!patch.isObject()) return;
    patch.properties().forEach(entry -> {
      JsonNode previous = baseline != null && baseline.isObject() ? baseline.get(entry.getKey()) : null;
      if (entry.getValue().isNull() && previous == null) invalid();
      if (entry.getValue().isObject()) requireKnownRemovals(previous, entry.getValue());
    });
  }
  private static void invalid() {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Native revision does not preserve authored presence or a reset targets an absent property.");
  }
}
