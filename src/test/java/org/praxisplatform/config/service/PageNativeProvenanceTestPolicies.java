package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Reuses the existing fixture's actual negative-policy hooks in the joint host seam. No DB proof. */
final class PageNativeProvenanceTestPolicies {
  static void require(PageCreationContext context, UiLayoutDraftWorkspaceSeed.TargetSeed captured,
      Runnable producer, UiLayoutDraftWorkspaceSource children, UiLayoutBaselineMetadataAdmission metadata) {
    var invocation = context.forTarget(captured.target());
    try (var attempt = UiLayoutValidationScope.borrow(invocation)) {
      var validation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
      validation.run(invocation, producer);
      validation.run(invocation, () -> metadata.require(invocation, captured.target(), captured.authoring(),
          captured.sourceRef(), captured.document(), captured.metadata()));
      for (var child : captured.pageChildren()) {
        validation.run(invocation, () -> children.requirePageChild(invocation, captured.target(), child, validation));
        var target = new UiLayoutTarget(child.document().path("kind").asText().equals("praxis.dynamic-form.editor")
            ? "praxis-dynamic-form" : "praxis-table", child.request().widgetKey());
        validation.run(invocation, () -> metadata.require(invocation, target, child.authoring(), child.sourceRef(),
            child.document(), child.metadata()));
      }
    }
  }
}
