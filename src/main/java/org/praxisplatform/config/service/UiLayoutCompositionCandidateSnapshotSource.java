package org.praxisplatform.config.service;

/** Host SPI for the current permitted baseline/overlays; Config owns combination with a fixed release. */
@FunctionalInterface
public interface UiLayoutCompositionCandidateSnapshotSource {
  UiLayoutCompositionCandidateSnapshot snapshot(EffectiveUiAudience audience, UiLayoutCompositionRegistration registration);
}
