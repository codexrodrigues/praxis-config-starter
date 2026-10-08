package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Host-owned validation of native composition membership and target-specific presentation policy.
 * The server supplies one context for the initiating operation, including all target checks and
 * final projection. Implementations retain the same attempt and may not renew its budget.
 * Shared native data checks are available through {@link UiLayoutNativeStructureChecks};
 * host admission and renderer validation remain separate responsibilities. No UI execution is
 * required by this SPI. Purpose describes the validation being performed; it never grants access.
 */
public interface UiLayoutLifecycleStructureValidator {
  void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutValidationContext validation);
  default void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baselineDocument, UiLayoutValidationContext validation) {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Native authoring baseline validator is unavailable.");
  }
  default void validateAuthoringRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baselineDocument,
      UiLayoutAuthoringDocumentDescriptor candidateDescriptor, JsonNode candidateDocument,
      UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patchDocument, UiLayoutValidationContext validation) {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Native authoring revision validator is unavailable.");
  }
  void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, UiLayoutValidationContext validation);
  void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
      UiLayoutResolutionCandidate.LayerClass layerClass, UiLayoutAudienceSelector selector, UiLayoutValidationContext validation);
  void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, UiLayoutValidationContext validation);
  default void validateFrozenRelease(UiLayoutLifecycleInvocation invocation, List<UiLayoutLifecycleFrozenContribution> contributions, UiLayoutValidationContext validation) {
    validation.requirePurpose(UiLayoutValidationPurpose.FROZEN_RELEASE);
    validation.require(invocation);
    for (UiLayoutLifecycleFrozenContribution contribution : contributions) {
      validation.run(invocation, () -> validateTarget(invocation, contribution.target(), validation));
      var nativeContext = contribution.authoring();
      validation.run(invocation, () -> validateAuthoringBaseline(invocation, contribution.target(), nativeContext.descriptor(), nativeContext.baselineDocument(), validation));
      validation.run(invocation, () -> validateAuthoringRevision(invocation, contribution.target(), nativeContext.descriptor(), nativeContext.baselineDocument(),
          nativeContext.descriptor(), nativeContext.candidateDocument(), nativeContext.patchDescriptor(), contribution.candidate().patch(), validation));
      validation.run(invocation, () -> validatePatch(invocation, contribution.target(), contribution.candidate().patch(), validation));
      validation.run(invocation, () -> validateAssignment(invocation, contribution.target(), contribution.candidate().layerClass(), contribution.candidate().selector(), validation));
    }
    validation.require(invocation);
  }

  static UiLayoutLifecycleStructureValidator denyAll() {
    return new UiLayoutLifecycleStructureValidator() {
      @Override public void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutValidationContext validation) { unavailable(); }
      @Override public void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baselineDocument, UiLayoutValidationContext validation) { unavailable(); }
      @Override public void validateAuthoringRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baselineDocument, UiLayoutAuthoringDocumentDescriptor candidateDescriptor, JsonNode candidateDocument, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patchDocument, UiLayoutValidationContext validation) { unavailable(); }
      @Override public void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, UiLayoutValidationContext validation) { unavailable(); }
      @Override public void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutResolutionCandidate.LayerClass layerClass, UiLayoutAudienceSelector selector, UiLayoutValidationContext validation) { unavailable(); }
      @Override public void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, UiLayoutValidationContext validation) { unavailable(); }
      private void unavailable() { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Lifecycle structure validator is unavailable."); }
    };
  }
}
