package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Frozen contribution resolved by Config from the exact release/source workspace under current authority.
 * This Java validation SPI carries private native context, not a new HTTP response or execution grant.
 */
public record UiLayoutLifecycleFrozenContribution(UiLayoutTarget target, String contributionKey,
    UiLayoutResolutionCandidate candidate, NativeAuthoring authoring) {
  public UiLayoutLifecycleFrozenContribution {
    Objects.requireNonNull(target); Objects.requireNonNull(contributionKey);
    Objects.requireNonNull(candidate); Objects.requireNonNull(authoring);
  }

  /** Server-correlated original/candidate and descriptors. Hashes pin content; host admission remains mandatory. */
  public record NativeAuthoring(UUID releaseId, UUID sourceDraftId, UUID freezeCommandRef,
      UiLayoutAuthoringDocumentDescriptor descriptor, UiLayoutPatchDocumentDescriptor patchDescriptor,
      String baselineSourceRef, String baselineHash, JsonNode baselineDocument,
      String candidateHash, JsonNode candidateDocument) {
    public NativeAuthoring {
      Objects.requireNonNull(releaseId); Objects.requireNonNull(sourceDraftId); Objects.requireNonNull(freezeCommandRef);
      Objects.requireNonNull(descriptor); Objects.requireNonNull(patchDescriptor);
      Objects.requireNonNull(baselineSourceRef); Objects.requireNonNull(baselineHash); Objects.requireNonNull(candidateHash);
      if (baselineDocument == null || !baselineDocument.isObject() || candidateDocument == null || !candidateDocument.isObject()) {
        throw new IllegalArgumentException("Frozen authoring documents must be objects.");
      }
      baselineDocument = baselineDocument.deepCopy(); candidateDocument = candidateDocument.deepCopy();
    }
    @Override public JsonNode baselineDocument() { return baselineDocument.deepCopy(); }
    @Override public JsonNode candidateDocument() { return candidateDocument.deepCopy(); }
  }
}
