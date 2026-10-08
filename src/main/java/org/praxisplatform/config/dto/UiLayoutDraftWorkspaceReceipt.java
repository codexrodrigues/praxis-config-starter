package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Authorized reopen representation of one persisted native-authoring workspace. */
@Schema(description = "Closed persisted UI layout authoring workspace with pinned native baselines and authoritative selections.")
public record UiLayoutDraftWorkspaceReceipt(
    @Schema(description = "Version of the closed Config workspace envelope, independent from native authoring and public patch schema versions.", requiredMode = Schema.RequiredMode.REQUIRED) String schemaVersion,
    @Schema(description = "Mutable draft identity and validator projected from the authoritative draft entity.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutDraftReceipt draft,
    @Schema(description = "Every registered composition target in exact registry order.", requiredMode = Schema.RequiredMode.REQUIRED) List<Target> targets,
    @Schema(description = "Immutable release produced from this draft, derived from release.sourceDraftId; null before freeze.") String frozenReleaseRef,
    @Schema(description = "Correlation of the accepted freeze command persisted in the workspace; null while the draft is mutable. It is not an idempotency key.") UUID freezeCommandRef) {
  public UiLayoutDraftWorkspaceReceipt { targets = List.copyOf(targets); }

  public record Target(
      @Schema(description = "Exact registered composition target.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget target,
      @Schema(description = "Stable native-document discriminator used by the host adapter.", requiredMode = Schema.RequiredMode.REQUIRED) String authoringDocumentType,
      @Schema(description = "Host-attested schema reference for the complete native authoring document.", requiredMode = Schema.RequiredMode.REQUIRED) String authoringSchemaRef,
      @Schema(description = "Pinned version of the complete native authoring document contract.", requiredMode = Schema.RequiredMode.REQUIRED) String authoringSchemaVersion,
      @Schema(description = "Host-attested schema reference for the public patch document.", requiredMode = Schema.RequiredMode.REQUIRED) String patchSchemaRef,
      @Schema(description = "Pinned public patch schema version; it is not inferred from authoringSchemaVersion.", requiredMode = Schema.RequiredMode.REQUIRED) String patchSchemaVersion,
      @Schema(description = "Immutable native authoring baseline captured when the draft was first created.", requiredMode = Schema.RequiredMode.REQUIRED) AuthoringDocument baseline,
      @Schema(description = "Latest accepted complete native authoring document.", requiredMode = Schema.RequiredMode.REQUIRED) AuthoringDocument working,
      @Schema(description = "Latest selected immutable patch revision, or null before authoring.") RevisionSelection selectedRevision,
      @Schema(description = "Latest selected immutable audience assignment, or null before assignment.") AssignmentSelection selectedAssignment) {}

  public record AuthoringDocument(
      @Schema(description = "Opaque host provenance for a baseline; null for a working document.") String sourceRef,
      @Schema(description = "Canonical SHA-256 of the exact complete document.", requiredMode = Schema.RequiredMode.REQUIRED) String contentHash,
      @Schema(description = "Complete native authoring document preserved for deterministic reopen.", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode document) {
    public AuthoringDocument { document = document == null ? null : document.deepCopy(); }
    @Override public JsonNode document() { return document == null ? null : document.deepCopy(); }
  }

  public record RevisionSelection(
      @Schema(description = "Selected immutable patch revision.", requiredMode = Schema.RequiredMode.REQUIRED) UUID revisionRef,
      @Schema(description = "Monotonic revision number within the target definition.", requiredMode = Schema.RequiredMode.REQUIRED) long revisionNumber,
      @Schema(description = "Canonical hash of the selected public patch.", requiredMode = Schema.RequiredMode.REQUIRED) String contentHash,
      @Schema(description = "Pinned public patch schema version.", requiredMode = Schema.RequiredMode.REQUIRED) String patchSchemaVersion,
      @Schema(description = "Correlation of the last accepted revision selection; not an idempotency key.", requiredMode = Schema.RequiredMode.REQUIRED) UUID acceptedCommandRef) {}

  public record AssignmentSelection(
      @Schema(description = "Selected immutable assignment revision.", requiredMode = Schema.RequiredMode.REQUIRED) UUID assignmentRevisionRef,
      @Schema(description = "Content revision derived from the workspace selection.", requiredMode = Schema.RequiredMode.REQUIRED) UUID revisionRef,
      @Schema(description = "Closed audience layer class.", requiredMode = Schema.RequiredMode.REQUIRED) String layerClass,
      @Schema(description = "Authorized selector visible only through the draft read operation.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutAudienceSelectorCommand selector,
      @Schema(description = "Deterministic precedence within the layer.", requiredMode = Schema.RequiredMode.REQUIRED) short priority,
      @Schema(description = "Stable logical contribution identity.", requiredMode = Schema.RequiredMode.REQUIRED) String contributionKey,
      @Schema(description = "Correlation of the last accepted assignment selection; not an idempotency key.", requiredMode = Schema.RequiredMode.REQUIRED) UUID acceptedCommandRef) {}
}
