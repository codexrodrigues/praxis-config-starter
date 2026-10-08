package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Config-owned reader that captures one aggregate head and immutable release membership. */
@FunctionalInterface
public interface UiLayoutCompositionReleaseReader {
  UiLayoutCompositionReleaseSnapshot read(String tenant, String environment, UiLayoutTarget rootTarget);
}
