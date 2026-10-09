package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

/** Shared pinned-selection scope checks; historical recovery also verifies stored patch bytes. */
class UiLayoutWorkspaceSelectionVerifier {
  private final UiLayoutDefinitionRepository definitions;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutAssignmentRevisionRepository assignments;
  private final ObjectMapper mapper;
  private final CanonicalJsonHashService hashes;

  UiLayoutWorkspaceSelectionVerifier(UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper, CanonicalJsonHashService hashes) {
    this.definitions = definitions; this.revisions = revisions; this.assignments = assignments;
    this.mapper = mapper; this.hashes = hashes;
  }

  void verify(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceDocument.TargetState state) {
    verify(invocation, state, false);
  }

  JsonNode verifyHistorical(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceDocument.TargetState state) {
    return verify(invocation, state, true);
  }

  private JsonNode verify(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceDocument.TargetState state, boolean historical) {
    UiLayoutDraftWorkspaceDocument.RevisionSelection selectedRevision = state.selectedRevision();
    if (selectedRevision == null) {
      if (state.selectedAssignment() != null) throw invalid("Workspace assignment has no selected revision.");
      return null;
    }
    UiLayoutRevision revision = revisions.findById(selectedRevision.revisionRef())
        .orElseThrow(() -> invalid("Workspace selected revision is unavailable."));
    UiLayoutDefinition definition = definitions.findById(revision.getDefinitionId())
        .orElseThrow(() -> invalid("Workspace selected definition is unavailable."));
    if (historical && (!selectedRevision.revisionRef().equals(revision.getId())
        || !revision.getDefinitionId().equals(definition.getId()))) {
      throw invalid("Workspace selected record identity is invalid.");
    }
    if (!invocation.tenantId().equals(definition.getTenantId())
        || !invocation.environment().equals(definition.getEnvironment())
        || !state.target().componentType().equals(definition.getComponentType())
        || !state.target().componentId().equals(definition.getComponentId())
        || !state.patch().schemaVersion().equals(definition.getSchemaVersion())
        || revision.getRevisionNumber() != selectedRevision.revisionNumber()
        || !revision.getContentHash().equals(selectedRevision.contentHash())
        || !revision.getSchemaVersion().equals(selectedRevision.schemaVersion())
        || !state.patch().schemaVersion().equals(selectedRevision.schemaVersion())) {
      throw invalid("Workspace selected revision is inconsistent with its target and pinned patch contract.");
    }
    JsonNode patch = historical ? parsePatch(revision.getPatchDocument()) : null;
    if (historical && !hashes.sha256Exact(patch).equals(revision.getContentHash())) {
      throw invalid("Workspace selected patch hash is invalid.");
    }
    UiLayoutDraftWorkspaceDocument.AssignmentSelection selectedAssignment = state.selectedAssignment();
    if (selectedAssignment == null) return patch;
    UiLayoutAssignmentRevision assignment = assignments.findById(selectedAssignment.assignmentRevisionRef())
        .orElseThrow(() -> invalid("Workspace selected assignment is unavailable."));
    if (historical && (!selectedAssignment.assignmentRevisionRef().equals(assignment.getId())
        || !invocation.tenantId().equals(selectedAssignment.selector().tenant()))) {
      throw invalid("Workspace selected assignment identity or tenant is invalid.");
    }
    JsonNode persistedSelector = parseObject(assignment.getSelectorDocument());
    JsonNode selectedSelector = mapper.valueToTree(selectedAssignment.selector());
    if (!invocation.tenantId().equals(assignment.getTenantId())
        || !invocation.environment().equals(assignment.getEnvironment())
        || !assignment.getRevisionId().equals(selectedRevision.revisionRef())
        || !assignment.getRevisionId().equals(selectedAssignment.revisionRef())
        || !assignment.getLayerClass().equals(selectedAssignment.layerClass())
        || assignment.getPriority() != selectedAssignment.priority()
        || !assignment.getContributionKey().equals(selectedAssignment.contributionKey())
        || !persistedSelector.equals(selectedSelector)) {
      throw invalid("Workspace selected assignment is inconsistent with its immutable record.");
    }
    return patch;
  }

  private JsonNode parsePatch(String value) {
    try {
      if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES) {
        throw invalid("Persisted patch is unavailable or too large.");
      }
      JsonNode result = UiLayoutRevisionJsonInput.readDocument(value, () -> {});
      if (result == null || !result.isObject()) throw invalid("Persisted patch is malformed.");
      return result;
    } catch (Exception exception) { throw invalid("Persisted patch is malformed."); }
  }

  private JsonNode parseObject(String value) {
    try {
      JsonNode result = mapper.readTree(value);
      if (result == null || !result.isObject()) throw invalid("Persisted assignment selector is malformed.");
      return result;
    } catch (UiLayoutLifecycleException exception) { throw exception; }
    catch (Exception exception) { throw invalid("Persisted assignment selector is malformed."); }
  }

  private UiLayoutLifecycleException invalid(String message) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, message);
  }
}
