package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Config native data/relation guard: host admission and frontend renderer semantics remain separate. */
final class UiLayoutFormRevisionReproduction {
  private UiLayoutFormRevisionReproduction() {}
  static void require(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch) {
    if (!"praxis.dynamic-form.editor".equals(descriptor.documentType())) return;
    requireNative(target, descriptor, baseline, candidate, patchDescriptor);
    if (patch == null || !patch.isObject()) invalid();
    UiLayoutRevisionRelation.requireReproduction((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"), (ObjectNode) patch);
  }
  static void requireNative(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor) {
    if (!"praxis.dynamic-form.editor".equals(descriptor.documentType())
        || !"praxis-dynamic-form".equals(target.componentType()) || !"1".equals(descriptor.schemaVersion())
        || !"praxis.ui-layout/v1".equals(patchDescriptor.schemaVersion())) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Form native revision descriptor is unsupported.");
    }
    document(baseline); document(candidate);
    UiLayoutRevisionRelation.requireUnchangedEnvelope((ObjectNode) baseline, (ObjectNode) candidate);
    JsonNode config = candidate.get("config");
    if (hasMergedObjectNull(config)) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
          "Literal null in a merged Form object cannot be represented by the current resolution patch.");
    }
  }
  private static boolean hasMergedObjectNull(JsonNode node) {
    // Arrays replace atomically: all nested values, including null layout inheritance, survive.
    if (!node.isObject()) return false;
    for (JsonNode child : node) {
      if (child.isNull() || hasMergedObjectNull(child)) return true;
    }
    return false;
  }
  private static void document(JsonNode document) {
    if (document == null || !document.isObject() || !"praxis.dynamic-form.editor".equals(document.path("kind").asText())
        || !document.path("version").isIntegralNumber() || !document.path("version").canConvertToInt()
        || document.path("version").asInt() != 1 || !document.path("config").isObject()) invalid();
  }
  private static void invalid() {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Form native revision does not preserve its envelope or reproduce its candidate.");
  }
}
