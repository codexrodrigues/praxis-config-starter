package org.praxisplatform.config.service;

/** Host SPI for native composition registration and root/target read admission. */
@FunctionalInterface
public interface UiLayoutCompositionRegistry {
  UiLayoutCompositionRegistration resolve(EffectiveUiAudience audience, org.praxisplatform.config.dto.UiLayoutTarget rootTarget);
}
