package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;
import static org.praxisplatform.config.service.UiLayoutValidationPurpose.*;

/** Checks every extension entry/return without changing native or authority semantics. */
public final class UiLayoutBoundedStructureValidator implements UiLayoutLifecycleStructureValidator {
  private final UiLayoutLifecycleStructureValidator delegate;
  public UiLayoutBoundedStructureValidator(UiLayoutLifecycleStructureValidator delegate) {
    this.delegate = delegate == null ? UiLayoutLifecycleStructureValidator.denyAll() : delegate;
  }
  public void validateTarget(UiLayoutLifecycleInvocation inv, UiLayoutTarget target, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, CURRENT_WORKSPACE_READ, EVOLUTION_CURRENT_READ, FROZEN_RELEASE);
    ctx.run(inv, () -> delegate.validateTarget(inv, target, ctx));
  }
  public void validateAuthoringBaseline(UiLayoutLifecycleInvocation inv,
      UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode document, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, EVOLUTION_CURRENT_READ, FROZEN_RELEASE);
    ctx.run(inv, () -> delegate.validateAuthoringBaseline(inv, target, descriptor, document, ctx));
  }
  public void validateAuthoringRevision(UiLayoutLifecycleInvocation inv, UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baseline, UiLayoutAuthoringDocumentDescriptor candidateDescriptor,
      JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, FROZEN_RELEASE);
    ctx.run(inv, () -> delegate.validateAuthoringRevision(inv, target, baselineDescriptor, baseline, candidateDescriptor, candidate, patchDescriptor, patch, ctx));
  }
  public void validatePatch(UiLayoutLifecycleInvocation inv, UiLayoutTarget target, JsonNode patch, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, FROZEN_RELEASE); ctx.run(inv, () -> delegate.validatePatch(inv, target, patch, ctx));
  }
  public void validateAssignment(UiLayoutLifecycleInvocation inv, UiLayoutTarget target,
      UiLayoutResolutionCandidate.LayerClass layer, UiLayoutAudienceSelector selector, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, FROZEN_RELEASE); ctx.run(inv, () -> delegate.validateAssignment(inv, target, layer, selector, ctx));
  }
  public void validateReleaseTargets(UiLayoutLifecycleInvocation inv, List<UiLayoutTarget> targets, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(AUTHORING, EVOLUTION_CURRENT_READ, FROZEN_RELEASE);
    ctx.run(inv, () -> delegate.validateReleaseTargets(inv, targets, ctx));
  }
  public void validateFrozenRelease(UiLayoutLifecycleInvocation inv, List<UiLayoutLifecycleFrozenContribution> items, UiLayoutValidationContext ctx) {
    ctx.requirePurpose(FROZEN_RELEASE); ctx.run(inv, () -> delegate.validateFrozenRelease(inv, items, ctx));
  }
}
