package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Complete host-produced baseline/input observation; Config validates and requires separate host admission. */
public record UiLayoutDraftWorkspaceSeed(List<TargetSeed> targets) {
  public UiLayoutDraftWorkspaceSeed {
    if (targets == null || targets.isEmpty()) throw new IllegalArgumentException("targets are required.");
    targets = List.copyOf(targets);
  }

  /** Evidence must be the actual operation/native input used for this document; a later mutable query is not evidence. */
  public record TargetSeed(
      UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor authoring,
      UiLayoutPatchDocumentDescriptor patch,
      String sourceRef,
      JsonNode document,
      UiLayoutBaselineMetadataSeed metadata) {
    public TargetSeed {
      if (target == null) throw new IllegalArgumentException("target is required.");
      if (authoring == null) throw new IllegalArgumentException("authoring is required.");
      if (patch == null) throw new IllegalArgumentException("patch is required.");
      if (sourceRef == null || sourceRef.isBlank()) throw new IllegalArgumentException("sourceRef is required.");
      sourceRef = sourceRef.trim();
      if (sourceRef.length() > 1024) throw new IllegalArgumentException("sourceRef is too long.");
      if (document == null || !document.isObject()) throw new IllegalArgumentException("document must be an object.");
      if (metadata == null) throw new IllegalArgumentException("Correlated baseline metadata is required.");
      document = document.deepCopy();
    }

    @Override public JsonNode document() { return document.deepCopy(); }
    @Override public String toString() { return "TargetSeed[content redacted]"; }
  }
}
