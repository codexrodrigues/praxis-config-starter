package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutPageChildSource;

/** Internal workspace evidence, never page authorship or a public receipt. Capture text preserves native input order through JSONB. */
public record UiLayoutPageChildEvidence(UiLayoutPageChildSource request,
    UiLayoutPatchDocumentDescriptor patch, String captureText) {
  public UiLayoutPageChildEvidence {
    if (request == null || patch == null || captureText == null || captureText.isBlank()
        || captureText.length() > 4 * UiLayoutMetadataCaptureCodec.MAX_COMPOSITION_BODY_BYTES)
      throw new IllegalArgumentException("Child evidence is incomplete or too large.");
  }
  @Override public String toString() { return "UiLayoutPageChildEvidence[content redacted]"; }
}
