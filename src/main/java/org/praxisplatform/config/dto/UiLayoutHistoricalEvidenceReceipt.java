package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

/** Read-only, host-authorized evidence; excludes audience selectors and private grants. */
@Schema(description = "Native B0, C0 and the exact revision patch recovered from one immutable release. "
    + "Integrity and visibility are verified; this is neither compatibility with B1 nor permission to apply an upgrade.")
public record UiLayoutHistoricalEvidenceReceipt(
    @Schema(description = "Exact immutable release whose membership was verified against the source workspace.") UUID releaseRef,
    @Schema(description = "Retained RELEASED workspace that captured the original native baseline and accepted customization.") UUID sourceDraftRef,
    @Schema(description = "Validator of the retained source workspace, not a version of the current base or a write authorization.") UUID sourceDraftEtag,
    @Schema(description = "Pinned freeze correlation from the source workspace; not an idempotency key.") UUID freezeCommandRef,
    @Schema(description = "All historical targets in frozen membership order, including removed targets explicitly authorized by the host.") List<Target> targets) {
  public UiLayoutHistoricalEvidenceReceipt { targets = List.copyOf(targets); }

  public record Target(
      @Schema(description = "Historical canonical component identity, independent of today's composition membership.") UiLayoutTarget target,
      @Schema(description = "Native authoring document discriminator retained when the source draft was created.") String authoringDocumentType,
      @Schema(description = "Pinned native document schema reference; does not assert compatibility with the current schema.") String authoringSchemaRef,
      @Schema(description = "Pinned version used to interpret the native B0 and C0 documents.") String authoringSchemaVersion,
      @Schema(description = "Pinned public patch schema reference associated with the frozen content revision.") String patchSchemaRef,
      @Schema(description = "Pinned public patch contract version, separately verified against the revision and definition.") String patchSchemaVersion,
      @Schema(description = "Original native base B0 with verified stored hash and opaque host provenance.") UiLayoutDraftWorkspaceReceipt.AuthoringDocument baseline,
      @Schema(description = "Accepted native customization C0 with verified stored hash; equality with B0 alone does not establish intent.") UiLayoutDraftWorkspaceReceipt.AuthoringDocument working,
      @Schema(description = "Frozen content revision checked against membership and its persisted patch hash.") UUID revisionRef,
      @Schema(description = "Frozen audience-assignment reference; its private selector is not exposed by this receipt.") UUID assignmentRevisionRef,
      @Schema(description = "Exact patch of the frozen revision, separately authorized and hash-verified. Its native schema determines interpretation; presence alone does not attest authored intent.") PatchDocument revisionPatch) {
    public Target { java.util.Objects.requireNonNull(revisionPatch, "revisionPatch"); }
  }

  public record PatchDocument(
      @Schema(description = "Canonical SHA-256 of the persisted patch, verified against the frozen revision selection.") String contentHash,
      @Schema(description = "Complete immutable revision patch without redaction or rewriting; not an inferred authoring delta or an apply command.") JsonNode document) {
    public PatchDocument {
      if (contentHash == null || !contentHash.matches("[0-9a-f]{64}") || document == null || !document.isObject()) {
        throw new IllegalArgumentException("Complete verified patch evidence is required.");
      }
      document = document.deepCopy();
    }
    @Override public JsonNode document() { return document.deepCopy(); }
  }
}
