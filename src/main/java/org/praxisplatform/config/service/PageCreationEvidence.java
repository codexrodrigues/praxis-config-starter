package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.PageCreationCommand;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Append-only origin/transform evidence, not current page authorship or a published head. */
record PageCreationEvidence(
    UUID pageId,
    UUID draftId,
    PageCreationContext observation,
    PageCreationCommand command,
    SourceSnapshot original,
    String sourceHash,
    String outputHash,
    String transformRef,
    List<PageCopyTransformation.Identity> identityMap,
    Instant createdAt) {
  PageCreationEvidence {
    identityMap = List.copyOf(identityMap);
  }

  UiLayoutTarget target() {
    return new UiLayoutTarget(UiLayoutPageRevisionReproduction.TYPE, pageId.toString());
  }

  String sourceRef() {
    return "praxis:page-creation:" + pageId;
  }

  @Override
  public String toString() {
    return "PageCreationEvidence[redacted]";
  }

  record SourceSnapshot(
      UiLayoutDraftWorkspaceSeed.TargetSeed baseline, JsonNode document, UUID sourceDraftId) {
    SourceSnapshot {
      document = document.deepCopy();
    }

    @Override
    public JsonNode document() {
      return document.deepCopy();
    }

    @Override
    public String toString() {
      return "PageSourceSnapshot[redacted]";
    }
  }
}
