package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

/** Config persistence reader: captures the head once and expands only immutable members of that release. */
public class JpaUiLayoutCompositionReleaseReader implements UiLayoutCompositionReleaseReader {
  private final UiLayoutReleaseHeadRepository heads;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutAssignmentRevisionRepository assignments;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutDefinitionRepository definitions;
  private final CanonicalJsonHashService hashes;

  public JpaUiLayoutCompositionReleaseReader(UiLayoutReleaseHeadRepository heads, UiLayoutReleaseRepository releases,
      UiLayoutReleaseMemberRepository members, UiLayoutAssignmentRevisionRepository assignments,
      UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions, ObjectMapper objectMapper,
      CanonicalJsonHashService hashes) {
    this.heads = heads; this.releases = releases; this.members = members; this.assignments = assignments;
    this.revisions = revisions; this.definitions = definitions; this.hashes = hashes;
  }

  @Override
  public UiLayoutCompositionReleaseSnapshot read(String tenant, String environment, UiLayoutTarget rootTarget) {
    try {
      return readPinned(tenant, environment, rootTarget);
    } catch (UiLayoutResolutionException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable("The active composition release could not be read safely.");
    }
  }

  private UiLayoutCompositionReleaseSnapshot readPinned(String tenant, String environment, UiLayoutTarget rootTarget) {
    var head = heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
        tenant, environment, rootTarget.componentType(), rootTarget.componentId());
    if (head.isEmpty() || head.orElseThrow().getActiveReleaseId() == null) return UiLayoutCompositionReleaseSnapshot.withoutActiveRelease();
    UiLayoutRelease release = releases.findByIdAndTenantIdAndEnvironment(head.orElseThrow().getActiveReleaseId(), tenant, environment)
        .filter(value -> rootTarget.componentType().equals(value.getRootComponentType()) && rootTarget.componentId().equals(value.getRootComponentId()))
        .orElseThrow(() -> unavailable("The active composition release is unavailable."));
    List<UiLayoutReleaseMember> pinnedMembers = members.findByReleaseIdOrderByMemberOrder(release.getId());
    if (pinnedMembers == null || pinnedMembers.isEmpty()) throw unavailable("An active composition release cannot be empty.");
    List<UiLayoutCompositionContribution> result = new ArrayList<>();
    Set<String> contributionKeys = new HashSet<>();
    boolean rootPresent = false;
    for (int index = 0; index < pinnedMembers.size(); index++) {
      UiLayoutReleaseMember member = pinnedMembers.get(index);
      if (member == null || member.getMemberOrder() != index) throw unavailable("A release member order is invalid.");
      UiLayoutAssignmentRevision assignment = assignments.findById(member.getAssignmentRevisionId())
          .orElseThrow(() -> unavailable("A pinned assignment revision is unavailable."));
      UiLayoutRevision revision = revisions.findById(member.getContentRevisionId())
          .orElseThrow(() -> unavailable("A pinned content revision is unavailable."));
      UiLayoutDefinition definition = definitions.findById(revision.getDefinitionId())
          .orElseThrow(() -> unavailable("A pinned layout definition is unavailable."));
      UiLayoutTarget target = new UiLayoutTarget(member.getComponentType(), member.getComponentId());
      JsonNode patch = patch(revision.getPatchDocument());
      if (!hashes.sha256Exact(patch).equals(revision.getContentHash())) throw unavailable("A pinned content hash is invalid.");
      UiLayoutAudienceSelector selector = selector(assignment.getSelectorDocument(), tenant);
      if (!tenant.equals(assignment.getTenantId()) || !environment.equals(assignment.getEnvironment())
          || !tenant.equals(definition.getTenantId()) || !environment.equals(definition.getEnvironment())
          || !java.util.Objects.equals(assignment.getRevisionId(), revision.getId())
          || !target.componentType().equals(definition.getComponentType()) || !target.componentId().equals(definition.getComponentId())
          || !java.util.Objects.equals(member.getContributionKey(), assignment.getContributionKey())) {
        throw unavailable("A pinned composition member failed integrity validation.");
      }
      String key = target.componentType() + "|" + target.componentId() + "|" + member.getContributionKey();
      if (!contributionKeys.add(key)) throw unavailable("A release cannot contain duplicate contribution keys for one target.");
      rootPresent |= rootTarget.equals(target);
      result.add(new UiLayoutCompositionContribution(member.getMemberOrder(), target, member.getContributionKey(),
          assignment.getId().toString(), revision.getId().toString(), revision.getContentHash(),
          new UiLayoutResolutionCandidate(target, revision.getId().toString(), revision.getContentHash(), layer(assignment.getLayerClass()), assignment.getPriority(),
              selector, patch, null)));
    }
    if (!rootPresent) throw unavailable("An active composition release must include its registered root target.");
    return new UiLayoutCompositionReleaseSnapshot(release.getId().toString(), result);
  }

  private UiLayoutAudienceSelector selector(String document, String tenant) {
    JsonNode node = parse(document, "selector");
    if (!node.isObject()) throw unavailable("A pinned selector is not an object.");
    Set<String> allowed = Set.of("tenant", "organization", "sector", "group", "profile", "user");
    node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw unavailable("A pinned selector contains an unknown field."); });
    String selectorTenant = text(node, "tenant", true);
    if (!tenant.equals(selectorTenant)) throw unavailable("A pinned selector tenant does not match the active tenant.");
    return new UiLayoutAudienceSelector(selectorTenant, text(node, "organization", false),
        text(node, "sector", false), text(node, "group", false), text(node, "profile", false), text(node, "user", false));
  }

  private JsonNode patch(String document) {
    JsonNode node = parse(document, "patch");
    if (!node.isObject()) throw unavailable("A pinned content revision is not an object patch.");
    return node;
  }

  private JsonNode parse(String value, String name) {
    try { return UiLayoutRevisionJsonInput.readDocument(value, () -> {}); }
    catch (Exception exception) { throw unavailable("A pinned " + name + " document is unreadable."); }
  }

  private String text(JsonNode node, String name, boolean required) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) {
      if (required) throw unavailable("A pinned selector is missing " + name + ".");
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) throw unavailable("A pinned selector field is invalid.");
    return value.textValue();
  }

  private UiLayoutResolutionCandidate.LayerClass layer(String value) {
    try { return UiLayoutResolutionCandidate.LayerClass.valueOf(value); }
    catch (RuntimeException exception) { throw unavailable("A pinned assignment layer is invalid."); }
  }

  private UiLayoutResolutionException unavailable(String message) {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY, message, null);
  }
}
