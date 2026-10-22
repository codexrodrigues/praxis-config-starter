package org.praxisplatform.config.service;

import java.security.Principal;
import org.praxisplatform.config.dto.PageSourceReference;

/** Host authority for application registration and exact native origins. No permissive default exists.
 * All callbacks run again under current authority; captured historical observations are never grants.
 * Existing lifecycle admission, structure validation and metadata/child admission remain required. */
public interface PageCreationHost {
  PageCreationContext resolve(Principal principal, String contextVersion);
  void require(PageCreationOperation operation, PageCreationContext context, PageSourceReference source);
  UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(PageCreationContext context, PageSourceReference.Native source);
  /** Bind the complete old capture to the exact selector; do not fetch latest or rewrite its observation. */
  void requireNative(PageCreationContext context, PageSourceReference.Native source,
      UiLayoutDraftWorkspaceSeed.TargetSeed captured);
}
