package org.praxisplatform.config.service;

/** One host-owned operation admission seam for lifecycle reads and writes. */
@FunctionalInterface
public interface UiLayoutLifecycleAdmission {
  void require(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation);

  static UiLayoutLifecycleAdmission denyAll() {
    return (operation, invocation) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,
          "Host lifecycle admission denied the requested operation.");
    };
  }
}
