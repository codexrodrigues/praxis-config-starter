package org.praxisplatform.config.service;

import java.util.LinkedHashSet;
import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Host-owned native composition registration; it is independent of any release head. */
public record UiLayoutCompositionRegistration(UiLayoutTarget rootTarget, List<UiLayoutTarget> targets) {
  public UiLayoutCompositionRegistration {
    if (rootTarget == null || targets == null || targets.isEmpty() || !rootTarget.equals(targets.getFirst())) {
      throw new IllegalArgumentException("A composition registration requires rootTarget as its first target.");
    }
    targets = List.copyOf(targets);
    if (("praxis-dynamic-page".equals(rootTarget.componentType()) || "praxis-app-shell".equals(rootTarget.componentType()))
        && (targets.size() != 1 || !rootTarget.componentId().matches(UiLayoutPageRevisionReproduction.UUID_PATTERN))) {
      throw new IllegalArgumentException("A page or app-shell requires one stable UUID root target.");
    }
    if (new LinkedHashSet<>(targets).size() != targets.size()) {
      throw new IllegalArgumentException("A composition registration cannot repeat a target.");
    }
  }
}
