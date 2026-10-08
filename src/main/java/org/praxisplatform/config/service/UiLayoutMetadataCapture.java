package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataSeed.Source;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataSeed.Reproduction;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataSeed.Observation;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Internal value only: integrity and declared identity do not attest origin or durable storage. */
record UiLayoutMetadataCapture(
    UUID captureRef, Binding binding, JsonNode baselineDocument, Source source,
    Reproduction reproduction, Observation observation, String rawInputText, String rawInputHash, String assemblyInputHash) {
  UiLayoutMetadataCapture {
    if (captureRef == null || binding == null || baselineDocument == null || !baselineDocument.isObject()
        || source == null || reproduction == null || observation == null || rawInputText == null) throw invalid();
    rawInputHash = hash(rawInputHash);
    if (reproduction instanceof UiLayoutBaselineMetadataSeed.SchemaProjection) assemblyInputHash = hash(assemblyInputHash);
    else if (assemblyInputHash != null) throw invalid();
    new UiLayoutBaselineMetadataSeed(source, reproduction, observation, rawInputText);
    baselineDocument = baselineDocument.deepCopy();
  }
  @Override public JsonNode baselineDocument() { return baselineDocument.deepCopy(); }
  @Override public String toString() { return "UiLayoutMetadataCapture[content redacted]"; }

  /** Exact Config ownership; target is opaque, not inferred from resource or widget labels. */
  record Scope(String tenantId, String environment, UiLayoutTarget root, UiLayoutTarget target) {
    Scope {
      tenantId = required(tenantId, 255); environment = required(environment, 64);
      if (root == null || target == null) throw invalid();
      required(root.componentType(), 64); required(root.componentId(), 255);
      required(target.componentType(), 64); required(target.componentId(), 255);
    }
    @Override public String toString() { return "Scope[redacted]"; }
  }

  /** Pins one baseline document in one source draft, independently of mutable heads or latest schema. */
  record Binding(UUID sourceDraftRef, Scope scope, String baselineSourceRef,
      UiLayoutAuthoringDocumentDescriptor authoring, String baselineContentHash) {
    Binding {
      if (sourceDraftRef == null || scope == null || authoring == null) throw invalid();
      baselineSourceRef = required(baselineSourceRef, 1024);
      required(authoring.documentType(), 255); required(authoring.schemaRef(), 1024);
      required(authoring.schemaVersion(), 128);
      baselineContentHash = hash(baselineContentHash);
    }
    @Override public String toString() { return "Binding[redacted]"; }
  }

  private static String required(String value, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) throw invalid();
    // Identifiers are exact; do not silently normalize an invalid stored binding.
    if (!value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
      } else if (Character.isLowSurrogate(character)) throw invalid();
    }
    return value;
  }
  private static String hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
    return value;
  }
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Metadata capture identity is invalid.");
  }
}
