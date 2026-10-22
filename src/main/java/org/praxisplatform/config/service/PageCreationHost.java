package org.praxisplatform.config.service;

import java.security.Principal;
import org.praxisplatform.config.dto.PageSourceReference;

/**
 * Host authority for application registration and exact native origins. No permissive default
 * exists. All callbacks run again under current authority; captured historical observations are
 * never grants. Existing lifecycle admission, structure validation and metadata/child admission
 * remain required.
 */
public interface PageCreationHost {
  PageCreationContext resolve(Principal principal, String contextVersion);

  /**
   * Re-attest current application and namespace independently for lifecycle callbacks without a
   * Principal. The supplied invocation is a correlation, not a credential. Deny if the host cannot
   * re-attest it.
   */
  PageCreationContext resolveLifecycle(UiLayoutLifecycleInvocation invocation);

  void require(
      PageCreationOperation operation, PageCreationContext context, PageSourceReference source);

  UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(
      PageCreationContext context, PageSourceReference.Native source);

  /**
   * Admit the actual producer and current domain dependencies of the complete old capture. This is
   * not READ_SOURCE/COPY_SOURCE over ancestral templates. It returns no content to the caller. Bind
   * the exact selector; never fetch latest or rewrite the historical observation.
   */
  void requireNative(
      PageCreationContext context,
      PageSourceReference.Native source,
      UiLayoutDraftWorkspaceSeed.TargetSeed captured);
}
