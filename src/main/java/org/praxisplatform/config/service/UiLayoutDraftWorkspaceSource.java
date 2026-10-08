package org.praxisplatform.config.service;

/**
 * Host-owned producer of complete native B0 documents and their genuine operation or native-document inputs.
 * Capture is one correlated observation for the registered composition, not B0 followed by a latest-schema lookup.
 * Every target requires admitted metadata. Unsupported sources must deny rather than invent operation identities.
 */
@FunctionalInterface
public interface UiLayoutDraftWorkspaceSource {
  UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation);
}
