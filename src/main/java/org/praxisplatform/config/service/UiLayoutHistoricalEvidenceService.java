package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.dto.UiLayoutDraftWorkspaceReceipt;
import org.praxisplatform.config.dto.UiLayoutHistoricalEvidenceReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;

/** Read-only historical evidence owner. Does not admit a target into current authoring or perform rebase. */
public class UiLayoutHistoricalEvidenceService {
  private final UiLayoutValidationBudgetPolicy budgetPolicy;
  private final UiLayoutLifecycleInvocationProvider invocations;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutHistoricalEvidenceAccess access;
  private final boolean accessConfigured;
  private final UiLayoutFrozenWorkspaceResolver resolver;

  public UiLayoutHistoricalEvidenceService(UiLayoutLifecycleInvocationProvider invocations,
      UiLayoutLifecycleAdmission admission, UiLayoutHistoricalEvidenceAccess access,
      UiLayoutReleaseRepository releases, UiLayoutDraftRepository drafts, UiLayoutReleaseMemberRepository members,
      UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper, CanonicalJsonHashService hashes, UiLayoutValidationBudgetPolicy budgetPolicy) {
    this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "Validation budget policy is required.");
    this.invocations = invocations;
    this.admission = admission == null ? UiLayoutLifecycleAdmission.denyAll() : admission;
    this.access = access == null ? UiLayoutHistoricalEvidenceAccess.denyAll() : access;
    this.accessConfigured = access != null;
    this.resolver = new UiLayoutFrozenWorkspaceResolver(releases, drafts, members, definitions, revisions,
        assignments, mapper, hashes);
  }

  /** Recover one exact release in a currently host-confirmed root scope; never resolve via current head. */
  public UiLayoutHistoricalEvidenceReceipt recover(Principal principal, String contextVersion,
      UiLayoutTarget root, UUID releaseId) {
    var invocation = resolve(principal, contextVersion, root, releaseId);
    var validation = UiLayoutValidationContext.begin(budgetPolicy, invocation,
        UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
    try (var attempt = validation.attempt()) {
      return recoverAdmitted(principal, contextVersion, root, releaseId, invocation, validation);
    }
  }

  /** Nested evidence recovery keeps the caller's deadline and still requires independent historical grants. */
  UiLayoutHistoricalEvidenceReceipt recoverWithin(Principal principal, String contextVersion,
      UiLayoutTarget root, UUID releaseId, UiLayoutValidationContext validation) {
    validation.requirePurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
    var invocation = validation.call(validation.attempt().invocation(),
        () -> resolve(principal, contextVersion, root, releaseId));
    return recoverAdmitted(principal, contextVersion, root, releaseId, invocation, validation);
  }

  private UiLayoutHistoricalEvidenceReceipt recoverAdmitted(Principal principal, String contextVersion,
      UiLayoutTarget root, UUID releaseId, UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
    try {
      validation.require(invocation);
      if (!accessConfigured) throw failure(UiLayoutLifecycleException.Code.DENIED);
      var frozen = validation.call(invocation, () -> resolver.resolve(invocation, releaseId,
          target -> validation.run(invocation, () -> access.requireTarget(invocation, target, validation))));
      var document = frozen.document();
      List<UiLayoutHistoricalEvidenceReceipt.Target> result = new ArrayList<>();
      for (int i = 0; i < document.targets().size(); i++) {
        var evidence = target(document.targets().get(i), frozen.patches().get(i));
        validation.run(invocation, () -> access.require(invocation, evidence, validation));
        result.add(evidence);
      }
      if (!invocation.equals(validation.call(invocation, () -> resolve(principal, contextVersion, root, releaseId)))) {
        throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      }
      for (var evidence : result) {
        validation.run(invocation, () -> access.requireTarget(invocation, evidence.target(), validation));
        validation.run(invocation, () -> access.require(invocation, evidence, validation));
      }
      // Access extensions may change current authority. The captured attempt is not a fresh grant.
      if (!invocation.equals(validation.call(invocation, () -> resolve(principal, contextVersion, root, releaseId)))) {
        throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      }
      validation.require(invocation);
      return new UiLayoutHistoricalEvidenceReceipt(releaseId, frozen.draftId(), frozen.draftEtag(),
          document.freezeCommandRef(), result);
    } catch (UiLayoutLifecycleException exception) { throw failure(exception.getCode()); }
    catch (RuntimeException exception) { throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE); }
  }

  private UiLayoutLifecycleInvocation resolve(Principal principal, String contextVersion, UiLayoutTarget root, UUID releaseId) {
    if (principal == null) throw failure(UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    if (root == null || releaseId == null || contextVersion == null || contextVersion.isBlank()) {
      throw failure(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    }
    try {
      var invocation = invocations.resolve(principal, contextVersion.trim(), root);
      if (invocation == null || !contextVersion.trim().equals(invocation.contextVersion())
          || !root.equals(invocation.rootTarget())) throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      admission.require(UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE, invocation);
      return invocation;
    } catch (UiLayoutLifecycleException exception) { throw failure(exception.getCode()); }
    catch (RuntimeException exception) { throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE); }
  }

  private UiLayoutHistoricalEvidenceReceipt.Target target(UiLayoutDraftWorkspaceDocument.TargetState state,
      com.fasterxml.jackson.databind.JsonNode patch) {
    return new UiLayoutHistoricalEvidenceReceipt.Target(state.target(), state.authoring().documentType(),
        state.authoring().schemaRef(), state.authoring().schemaVersion(), state.patch().schemaRef(), state.patch().schemaVersion(),
        new UiLayoutDraftWorkspaceReceipt.AuthoringDocument(state.baseline().sourceRef(), state.baseline().contentHash(), state.baseline().document()),
        new UiLayoutDraftWorkspaceReceipt.AuthoringDocument(null, state.working().contentHash(), state.working().document()),
        state.selectedRevision().revisionRef(), state.selectedAssignment().assignmentRevisionRef(),
        new UiLayoutHistoricalEvidenceReceipt.PatchDocument(state.selectedRevision().contentHash(), patch));
  }

  private UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Historical layout evidence is unavailable.");
  }
}
