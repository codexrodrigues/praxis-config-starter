package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

/** Bounded native data projection, never a host authorization/provenance receipt. */
final class UiLayoutAuthoringProjection {
  private final ObjectNode baseline;
  private final ObjectNode candidate;
  private final Runnable checkpoint;

  private UiLayoutAuthoringProjection(ObjectNode baseline, ObjectNode candidate, Runnable checkpoint) {
    this.baseline = baseline.deepCopy(); this.candidate = candidate.deepCopy(); this.checkpoint = checkpoint;
  }

  static UiLayoutAuthoringProjection project(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, Runnable checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint is required");
    checkpoint.run();
    if (target == null || descriptor == null || patchDescriptor == null) throw unavailable();
    UiLayoutJsonBounds.requireDocument(baseline, Code.INVALID_STATE, checkpoint);
    UiLayoutJsonBounds.requireDocument(candidate, Code.INVALID_REQUEST, checkpoint);
    switch (descriptor.documentType()) {
      case "praxis.table.editor" -> UiLayoutTableRevisionReproduction.requireNative(target, descriptor, baseline, candidate, patchDescriptor);
      case "praxis.dynamic-form.editor" -> UiLayoutFormRevisionReproduction.requireNative(target, descriptor, baseline, candidate, patchDescriptor);
      default -> throw unavailable();
    }
    checkpoint.run();
    var projection = new UiLayoutAuthoringProjection((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"), checkpoint);
    checkpoint.run();
    return projection;
  }

  ObjectNode baselineConfig() { checkpoint.run(); var copy = baseline.deepCopy(); checkpoint.run(); return copy; }
  ObjectNode candidateConfig() { checkpoint.run(); var copy = candidate.deepCopy(); checkpoint.run(); return copy; }
  ObjectNode compilePatch() { return UiLayoutAuthoredPatchCompiler.compile(baseline, candidate, checkpoint); }

  private static UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(Code.SOURCE_UNAVAILABLE, "Native authoring projection is unsupported.");
  }
}
