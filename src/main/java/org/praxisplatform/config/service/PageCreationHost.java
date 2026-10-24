package org.praxisplatform.config.service;

import java.security.Principal;
import org.praxisplatform.config.dto.PageSourceReference;
import org.praxisplatform.config.dto.UiLayoutTarget;

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

  /**
   * Resolve the stable navigation-groups root registered for the current tenant, application,
   * environment and namespace. This mapping grants no access. Its physical tenant/environment/root
   * identity must not be shared with another application or namespace. resolveLifecycle must attest
   * the inverse mapping independently for the returned root; caller-supplied context is not a
   * credential. Deny when the host cannot prove the current registration.
   */
  UiLayoutTarget navigationGroupsRoot(PageCreationContext currentContext);

  void require(
      PageCreationOperation operation, PageCreationContext context, PageSourceReference source);

  /**
   * Admit one creation decision: CREATE_PAGE, READ_SOURCE and COPY_SOURCE must all hold for the
   * exact current context and source. This is one joint current-authority observation window,
   * not three independently reusable grants. A host may evaluate its three pure policies under
   * the same locked authority snapshot, with fresh observation/expiry checks before and after
   * that policy. Revocation racing the joint decision serializes at this window's completion.
   * Never retain the decision across calls, execute Config work in this callback, or omit a policy.
   * Include the FULL policy of resolve(principal, contextVersion), using the supplied Principal
   * as the credential input rather than substituting the expected actor or thread identity.
   * Return the freshly observed context only after all policies and final observation succeed.
   * Expected is correlation only: Config rejects null or any difference from it, without changing
   * scope. Resolution and creation now linearize together; do not implement as two transactions.
   * Config calls again at every creation checkpoint, including before actual commit.
   */
  PageCreationContext requireCreation(
      Principal principal, String contextVersion, PageCreationContext expected,
      PageSourceReference source);

  UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(
      PageCreationContext context, PageSourceReference.Native source);

  /**
   * Joint admission of a wholly host-native page: current producer/domain dependencies, root
   * metadata, every child's exact source and every child's metadata must all be authorized for
   * this complete historical capture. Config selects this seam only for Native origins whose
   * root and all child metadata are not Config-generated; it owns that classification.
   * The host evaluates all policies in one current-authority window, with fresh observation and
   * expiry/coherence checks around them. No grant is retained between calls. Config invokes this
   * before and after structural extensions and still checks exact reproduction and child identity.
   * Include the FULL resolveLifecycle(context.invocation()) policy, including custom registration
   * restrictions, in this same window and return its freshly attested context after final checks.
   * The supplied context may contain ledger-derived application/namespace comparison values;
   * it is never authority. Config rejects null or any identity/registration difference. Resolution
   * and provenance linearize together, and no Config I/O or extension executes in this callback.
   * This does not grant access to published or Config-generated origins. Mixed captures retain
   * requireNative plus the independent metadata/child admissions and Config provenance checks.
   */
  PageCreationContext requireNativeProvenance(
      PageCreationContext context,
      PageSourceReference.Native source,
      UiLayoutDraftWorkspaceSeed.TargetSeed captured);

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
