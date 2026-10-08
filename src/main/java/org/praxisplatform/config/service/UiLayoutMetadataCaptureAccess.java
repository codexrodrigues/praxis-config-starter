package org.praxisplatform.config.service;

/** Internal seam for current permission to read complete metadata, not just the native UI document. */
@FunctionalInterface
interface UiLayoutMetadataCaptureAccess {
  void require(UiLayoutLifecycleInvocation invocation, UiLayoutMetadataCapture capture);

  static UiLayoutMetadataCaptureAccess denyAll() {
    return (invocation, capture) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,
          "Metadata capture access is denied.");
    };
  }
}
