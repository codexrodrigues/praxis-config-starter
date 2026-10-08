package org.praxisplatform.config.service;

/** One sanitized failure policy for initial admission and current access to pinned metadata. */
final class UiLayoutMetadataAdmissionFailure {
  private UiLayoutMetadataAdmissionFailure() {}

  static UiLayoutLifecycleException sanitized(Exception failure, String message) {
    UiLayoutLifecycleException.Code code = UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE;
    if (failure instanceof UiLayoutLifecycleException declared && declared.getCode() != null) {
      code = switch (declared.getCode()) {
        case DENIED, CONTEXT_STALE, SOURCE_UNAVAILABLE, VALIDATION_FAILED -> declared.getCode();
        default -> UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE;
      };
    }
    return new UiLayoutLifecycleException(code, message);
  }
}
