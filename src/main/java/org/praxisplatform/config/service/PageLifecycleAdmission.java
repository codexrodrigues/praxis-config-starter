package org.praxisplatform.config.service;

/**
 * The configured lifecycle admission also owns current application/namespace attestation for pages.
 * Page composition requires this same bean; there is no separate permissive provider or fallback.
 * Implementations must apply every operation/domain policy of {@link #require} and the exact current
 * invocation/registration policy in one authority window, with fresh observation, expiry and coherence
 * checks before and after the pure policies. No Config I/O, authority retention or latest-source lookup
 * belongs in that window. Revocation serializes against the joint decision, not two independent calls.
 */
public interface PageLifecycleAdmission extends UiLayoutLifecycleAdmission {
  /**
   * Admit any page invocation, including a UUID not yet in the catalog, and return its exact current
   * application/namespace context. The returned context is correlation only, never a reusable grant.
   * Config still checks catalog ownership, observes authority after I/O and calls again at later boundaries.
   */
  PageCreationContext requirePage(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation);
}
