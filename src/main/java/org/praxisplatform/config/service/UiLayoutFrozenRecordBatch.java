package org.praxisplatform.config.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.repository.*;

/** Bounded sealed records plus correlated definition identities. No heads or authority decisions. */
record UiLayoutFrozenRecordBatch(
    Map<UUID, UiLayoutRelease> releases,
    Map<UUID, UiLayoutDraft> drafts,
    Map<UUID, List<UiLayoutReleaseMember>> members,
    Map<UUID, UiLayoutRevision> revisions,
    Map<UUID, UiLayoutDefinition> definitions,
    Map<UUID, UiLayoutAssignmentRevision> assignments) {

  static UiLayoutFrozenRecordBatch load(
      Collection<UUID> releaseIds,
      UiLayoutReleaseRepository releaseRepository,
      UiLayoutDraftRepository draftRepository,
      UiLayoutReleaseMemberRepository memberRepository,
      UiLayoutRevisionRepository revisionRepository,
      UiLayoutDefinitionRepository definitionRepository,
      UiLayoutAssignmentRevisionRepository assignmentRepository) {
    var releases = index(releaseRepository.findAllById(releaseIds), UiLayoutRelease::getId);
    var drafts = index(draftRepository.findAllById(releases.values().stream()
        .map(UiLayoutRelease::getSourceDraftId).filter(java.util.Objects::nonNull).toList()), UiLayoutDraft::getId);
    var members = memberRepository.findByReleaseIdInOrderByReleaseIdAscMemberOrderAsc(releaseIds);
    var revisions = index(revisionRepository.findAllById(members.stream()
        .map(UiLayoutReleaseMember::getContentRevisionId).toList()), UiLayoutRevision::getId);
    var definitions = index(definitionRepository.findAllById(revisions.values().stream()
        .map(UiLayoutRevision::getDefinitionId).toList()), UiLayoutDefinition::getId);
    var assignments = index(assignmentRepository.findAllById(members.stream()
        .map(UiLayoutReleaseMember::getAssignmentRevisionId).toList()), UiLayoutAssignmentRevision::getId);
    return new UiLayoutFrozenRecordBatch(releases, drafts,
        members.stream().collect(Collectors.groupingBy(UiLayoutReleaseMember::getReleaseId)),
        revisions, definitions, assignments);
  }

  UiLayoutRelease sourceRelease(UUID draftId) {
    // V65 makes source_draft_id unique. This is correlation, not publication or authority.
    var matches = releases.values().stream().filter(release -> draftId.equals(release.getSourceDraftId())).toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static <T> Map<UUID, T> index(List<T> values, Function<T, UUID> identity) {
    return values.stream().collect(Collectors.toUnmodifiableMap(identity, Function.identity()));
  }
}
