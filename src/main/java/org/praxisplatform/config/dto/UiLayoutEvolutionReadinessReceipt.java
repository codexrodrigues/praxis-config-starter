package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Advisory composition and native-contract readiness of B0/C0/B1. "
    + "Does not infer authored intent, produce a candidate or authorize upgrade application.")
public record UiLayoutEvolutionReadinessReceipt(
    @Schema(description = "Exact frozen release whose authorized historical evidence was recovered.") UUID releaseRef,
    @Schema(description = "Retained source workspace revision observed by the historical evidence service.") UUID sourceDraftEtag,
    @Schema(description = "Host-confirmed context checked before and after reading the inputs; not a commit fence.") String contextVersion,
    @Schema(description = "Ordered union of historical and newly registered targets, with verified document hashes only; no native values are exposed.") List<TargetEvidence> targets,
    @Schema(description = "Composition/contract changes, supported historical Table document reproduction and missing provenance guarantees. Reproduction does not attest origin or current-schema compatibility; this is not field-level semantic conflict analysis.") List<Diagnostic> diagnostics,
    @Schema(description = "Always false for this advisory readiness boundary. Hashes and repeated context reads do not permit apply.", allowableValues = {"false"}) boolean applyAllowed) {
  public UiLayoutEvolutionReadinessReceipt {
    targets = List.copyOf(targets); diagnostics = List.copyOf(diagnostics);
    if (applyAllowed) throw new IllegalArgumentException("Readiness cannot authorize apply.");
  }

  public record TargetEvidence(
      @Schema(description = "Exact canonical target identity; targets removed from current composition remain observable as conflicts.") UiLayoutTarget target,
      @Schema(description = "Verified stored B0 hash; null for a target newly introduced by B1.") String baselineHash,
      @Schema(description = "Verified stored C0 hash; equality with B0 does not prove absence of explicit pins.") String customizedHash,
      @Schema(description = "Hash of the current server-attested B1 document captured for this target; null when the target was removed.") String currentHash) {}

  public record Diagnostic(
      @Schema(description = "Stable readiness reason, distinct from native editor validation and lifecycle admission failures.") Code code,
      @Schema(description = "Affected target; null for a composition-wide missing guarantee.") UiLayoutTarget target) {}

  public enum Code {
    TARGET_REMOVED, TARGET_ADDED, NATIVE_CONTRACT_CHANGED,
    @Schema(description = "The supported historical Table patch preserves authored presence and reproduces C0 from B0. Does not attest provenance, current schema compatibility or apply authority.")
    NATIVE_DOCUMENT_RELATION_REPRODUCED,
    @Schema(description = "The historical native Table descriptor or B0/patch/C0 relation cannot be verified by the supported owner gate. Requires review; no fallback or native values are disclosed.")
    NATIVE_DOCUMENT_RELATION_UNVERIFIABLE,
    INTENT_PROVENANCE_UNVERIFIED, SOURCE_FENCING_UNAVAILABLE
  }
}
