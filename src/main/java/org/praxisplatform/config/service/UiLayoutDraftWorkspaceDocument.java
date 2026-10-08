package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutAudienceSelectorCommand;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Closed persisted envelope. Entity state, timestamps, ETags and release identity stay authoritative elsewhere. */
public record UiLayoutDraftWorkspaceDocument(String schemaVersion, List<TargetState> targets, UUID freezeCommandRef) {
  public static final String SCHEMA_VERSION = "praxis.ui-layout-draft-workspace/v1";

  public UiLayoutDraftWorkspaceDocument {
    if (!SCHEMA_VERSION.equals(schemaVersion)) throw invalid("Workspace schemaVersion is unsupported.");
    if (targets == null || targets.isEmpty()) throw invalid("Workspace targets are required.");
    targets = List.copyOf(targets);
  }

  public record TargetState(
      UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor authoring,
      UiLayoutPatchDocumentDescriptor patch,
      BaselineDocument baseline,
      WorkingDocument working,
      RevisionSelection selectedRevision,
      AssignmentSelection selectedAssignment) {
    public TargetState {
      if (target == null || authoring == null || patch == null || baseline == null || working == null) {
        throw invalid("Workspace target state is incomplete.");
      }
    }
  }

  public record BaselineDocument(String sourceRef, String contentHash, JsonNode document) {
    public BaselineDocument {
      sourceRef = required(sourceRef, "baseline.sourceRef");
      contentHash = hash(contentHash, "baseline.contentHash");
      document = object(document, "baseline.document");
    }
    @Override public JsonNode document() { return document.deepCopy(); }
  }

  public record WorkingDocument(String contentHash, JsonNode document) {
    public WorkingDocument {
      contentHash = hash(contentHash, "working.contentHash");
      document = object(document, "working.document");
    }
    @Override public JsonNode document() { return document.deepCopy(); }
  }

  public record RevisionSelection(
      UUID revisionRef,
      long revisionNumber,
      String contentHash,
      String schemaVersion,
      UUID acceptedCommandRef) {
    public RevisionSelection {
      if (revisionRef == null || acceptedCommandRef == null || revisionNumber < 1) throw invalid("Revision selection is invalid.");
      contentHash = hash(contentHash, "selectedRevision.contentHash");
      schemaVersion = required(schemaVersion, "selectedRevision.schemaVersion");
    }
  }

  public record AssignmentSelection(
      UUID assignmentRevisionRef,
      UUID revisionRef,
      String layerClass,
      UiLayoutAudienceSelectorCommand selector,
      short priority,
      String contributionKey,
      UUID acceptedCommandRef) {
    public AssignmentSelection {
      if (assignmentRevisionRef == null || revisionRef == null || selector == null || acceptedCommandRef == null) {
        throw invalid("Assignment selection is invalid.");
      }
      layerClass = required(layerClass, "selectedAssignment.layerClass");
      contributionKey = required(contributionKey, "selectedAssignment.contributionKey");
    }
  }

  private static JsonNode object(JsonNode value, String name) {
    if (value == null || !value.isObject()) throw invalid(name + " must be an object.");
    return value.deepCopy();
  }

  private static String hash(String value, String name) {
    String normalized = required(value, name);
    if (!normalized.matches("[0-9a-f]{64}")) throw invalid(name + " must be a lowercase SHA-256 hash.");
    return normalized;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw invalid(name + " is required.");
    return value.trim();
  }

  private static UiLayoutLifecycleException invalid(String message) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, message);
  }
}
