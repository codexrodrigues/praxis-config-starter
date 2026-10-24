package org.praxisplatform.config.service;

/**
 * Host-owned producer of complete native B0 documents and their genuine operation or native-document inputs.
 * Capture is one correlated observation for the registered composition, not B0 followed by a latest-schema lookup.
 * Every target requires admitted metadata. Unsupported sources must deny rather than invent operation identities.
 */
@FunctionalInterface
public interface UiLayoutDraftWorkspaceSource {
  UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation);
  /** Resolve precisely the requested registered revision under current server authority; never consume a client seed. */
  default UiLayoutPageChildSeed capturePageChild(UiLayoutLifecycleInvocation invocation,
      org.praxisplatform.config.dto.UiLayoutTarget page,
      org.praxisplatform.config.dto.UiLayoutPageChildSource request, UiLayoutValidationContext validation) {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Page child source is unavailable.");
  }

  /** Jointly re-admit the exact registered child source, document, reproduction and complete metadata
   * under one current-authority decision window, including restored/frozen children. No latest lookup.
   * The host must bind seed.request() (kind/sourceId/revision) to the exact admitted source, document and
   * reproduction and seed.metadata(), including its source and input; captureText and metadata observation record history and never grant present authority. */
  default void requirePageChildEvidence(UiLayoutLifecycleInvocation invocation,
      org.praxisplatform.config.dto.UiLayoutTarget page, UiLayoutPageChildSeed seed, UiLayoutValidationContext validation) {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Page child source admission is unavailable.");
  }
}
