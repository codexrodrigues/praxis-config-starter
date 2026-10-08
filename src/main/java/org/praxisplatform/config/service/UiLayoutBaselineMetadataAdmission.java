package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Host-owned admission of genuine operation or native-document origin/publication, raw input, native B0 correlation,
 * reproduction artifacts and effective policy under the current authenticated invocation.
 * Runs before capture persistence and again at return; historical observation never grants current access.
 * Implementations must throw UiLayoutLifecycleException with DENIED for policy refusal,
 * CONTEXT_STALE for obsolete context, SOURCE_UNAVAILABLE for technical unavailability, or
 * VALIDATION_FAILED for invalid admitted evidence. Other exceptions/codes are treated as
 * SOURCE_UNAVAILABLE, never as an inferred policy denial. Messages and causes are redacted.
 * Implementations must not fetch latest metadata to replace the supplied observation.
 */
@FunctionalInterface
public interface UiLayoutBaselineMetadataAdmission {
  void require(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor authoring, String baselineSourceRef, JsonNode baselineDocument,
      UiLayoutBaselineMetadataSeed metadata);

  static UiLayoutBaselineMetadataAdmission denyAll() {
    return (invocation, target, authoring, source, baseline, metadata) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Metadata admission is unavailable.");
    };
  }
}
