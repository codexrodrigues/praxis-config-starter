package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

/**
 * Compiles authored presence against pinned baseline config, not a minimal value diff.
 * Callers admit JSON, size/depth, native format, envelope and component null policy first.
 * Arrays replace atomically; their nested nulls are literal values. This has no lifecycle side effects.
 */
final class UiLayoutAuthoredPatchCompiler {
  private UiLayoutAuthoredPatchCompiler() {}

  static ObjectNode compile(ObjectNode baselineConfig, ObjectNode candidateConfig) {
    return compile(baselineConfig, candidateConfig, () -> {});
  }

  static ObjectNode compile(ObjectNode baselineConfig, ObjectNode candidateConfig, Runnable checkpoint) {
    if (baselineConfig == null || candidateConfig == null) throw invalid();
    UiLayoutJsonBounds.requireDocument(baselineConfig, Code.INVALID_STATE, checkpoint);
    UiLayoutJsonBounds.requireDocument(candidateConfig, Code.INVALID_REQUEST, checkpoint);
    projectedSize(baselineConfig, candidateConfig, checkpoint);
    ObjectNode patch = compileObject(baselineConfig, candidateConfig, checkpoint);
    UiLayoutJsonBounds.requireDocument(patch, Code.INVALID_REQUEST, checkpoint);
    UiLayoutRevisionRelation.requireReproduction(baselineConfig, candidateConfig, patch);
    checkpoint.run();
    return patch;
  }

  /** Exact compact size of the derived patch, before allocating its tree. */
  private static int projectedSize(JsonNode baseline, ObjectNode candidate, Runnable checkpoint) {
    long size = 2;
    int fields = 0;
    for (var entry : candidate.properties()) {
      checkpoint.run();
      JsonNode value = entry.getValue();
      if (value.isNull()) throw invalid();
      JsonNode previous = baseline != null && baseline.isObject() ? baseline.get(entry.getKey()) : null;
      size += UiLayoutJsonBounds.measure(TextNode.valueOf(entry.getKey()), Code.INVALID_REQUEST, checkpoint) + 1L
          + (value.isObject() ? projectedSize(previous, (ObjectNode) value, checkpoint)
              : UiLayoutJsonBounds.measure(value, Code.INVALID_REQUEST, checkpoint)) + (fields++ == 0 ? 0 : 1);
      requireSize(size);
    }
    if (baseline != null && baseline.isObject()) {
      for (var entry : baseline.properties()) {
        checkpoint.run();
        if (!candidate.has(entry.getKey())) {
          size += UiLayoutJsonBounds.measure(TextNode.valueOf(entry.getKey()), Code.INVALID_REQUEST, checkpoint) + 5L
              + (fields++ == 0 ? 0 : 1);
          requireSize(size);
        }
      }
    }
    return (int) size;
  }

  private static void requireSize(long size) {
    if (size > UiLayoutJsonBounds.MAX_DOCUMENT_BYTES) throw new UiLayoutLifecycleException(Code.INVALID_REQUEST,
        "Derived authoring patch exceeds its JSON budget.");
  }

  private static ObjectNode compileObject(JsonNode baseline, ObjectNode candidate, Runnable checkpoint) {
    ObjectNode patch = candidate.objectNode();
    candidate.properties().forEach(entry -> {
      checkpoint.run();
      JsonNode value = entry.getValue();
      if (value.isNull()) throw invalid();
      JsonNode previous = baseline != null && baseline.isObject() ? baseline.get(entry.getKey()) : null;
      patch.set(entry.getKey(), value.isObject()
          ? compileObject(previous, (ObjectNode) value, checkpoint) : value.deepCopy());
    });
    if (baseline != null && baseline.isObject()) {
      baseline.properties().forEach(entry -> {
        checkpoint.run();
        if (!candidate.has(entry.getKey())) patch.putNull(entry.getKey());
      });
    }
    return patch;
  }

  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Authored patch requires config objects without literal null in merged objects.");
  }
}
