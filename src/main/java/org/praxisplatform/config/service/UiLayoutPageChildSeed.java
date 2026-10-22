package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;

/** Server-produced child B0 and its genuine inputs. Never deserialize this SPI from a revision request. */
public record UiLayoutPageChildSeed(UiLayoutPageChildSource request,
    UiLayoutAuthoringDocumentDescriptor authoring, UiLayoutPatchDocumentDescriptor patch,
    String sourceRef, JsonNode document, UiLayoutBaselineMetadataSeed metadata) {
  public UiLayoutPageChildSeed {
    if (request == null || authoring == null || patch == null || sourceRef == null || sourceRef.isBlank()
        || document == null || !document.isObject() || metadata == null) throw new IllegalArgumentException("Child seed is incomplete.");
    document = document.deepCopy();
  }
  @Override public JsonNode document() { return document.deepCopy(); }
  @Override public String toString() { return "UiLayoutPageChildSeed[content redacted]"; }
}
