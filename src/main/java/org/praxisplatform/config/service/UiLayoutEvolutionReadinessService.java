package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutEvolutionReadinessReceipt;
import org.praxisplatform.config.dto.UiLayoutEvolutionReadinessReceipt.Code;
import org.praxisplatform.config.dto.UiLayoutEvolutionReadinessReceipt.Diagnostic;
import org.praxisplatform.config.dto.UiLayoutEvolutionReadinessReceipt.TargetEvidence;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Advisory evidence orchestration; leaves native intent/compatibility to their canonical owners. */
public class UiLayoutEvolutionReadinessService {
  private final UiLayoutValidationBudgetPolicy budgetPolicy;
  private final UiLayoutHistoricalEvidenceService history;
  private final UiLayoutLifecycleInvocationProvider invocations;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutDraftWorkspaceSource source;
  private final UiLayoutCurrentEvolutionEvidenceAccess access;
  private final UiLayoutLifecycleStructureValidator structure;
  private final boolean accessConfigured;
  private final UiLayoutDraftWorkspaceCodec codec;

  public UiLayoutEvolutionReadinessService(UiLayoutHistoricalEvidenceService history,
      UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
      UiLayoutDraftWorkspaceSource source, UiLayoutCurrentEvolutionEvidenceAccess access,
      UiLayoutLifecycleStructureValidator structure, ObjectMapper mapper, CanonicalJsonHashService hashes, UiLayoutValidationBudgetPolicy budgetPolicy) {
    this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "Validation budget policy is required.");
    this.history = history; this.invocations = invocations; this.source = source;
    this.admission = admission == null ? UiLayoutLifecycleAdmission.denyAll() : admission;
    this.access = access == null ? UiLayoutCurrentEvolutionEvidenceAccess.denyAll() : access;
    this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.accessConfigured = access != null; this.codec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
  }

  /** Read an advisory snapshot. Never modifies a workspace, revision, assignment or release head. */
  public UiLayoutEvolutionReadinessReceipt diagnose(Principal principal, String contextVersion,
      UiLayoutTarget root, UUID releaseId) {
    if (principal == null) throw failure(UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    if (root == null || releaseId == null || contextVersion == null || contextVersion.isBlank()) {
      throw failure(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    }
    try {
      var invocation = invocations.resolve(principal, contextVersion.trim(), root);
      if (invocation == null || !contextVersion.trim().equals(invocation.contextVersion())
          || !root.equals(invocation.rootTarget())) throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      admission.require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation);
      var validation = UiLayoutValidationContext.begin(budgetPolicy, invocation,
          UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, UiLayoutValidationPurpose.EVOLUTION_CURRENT_READ);
      try (var attempt = validation.attempt()) {
        if (!accessConfigured) throw failure(UiLayoutLifecycleException.Code.DENIED);
        var historical = validation.call(invocation, () -> history.recoverWithin(principal, contextVersion.trim(), root, releaseId, validation.forPurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ)));
        if (!releaseId.equals(historical.releaseRef())) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        var seed = validation.call(invocation, () -> source.capture(invocation, validation));
        var current = codec.fromSeed(invocation, seed);
        for (var target : seed.targets()) validation.run(invocation, () -> access.require(invocation, target, validation));
        validateCurrent(invocation, current, validation);
        var currentByTarget = new LinkedHashMap<UiLayoutTarget, UiLayoutDraftWorkspaceDocument.TargetState>();
        current.targets().forEach(target -> currentByTarget.put(target.target(), target));
        var diagnostics = new ArrayList<Diagnostic>();
        var evidence = new ArrayList<TargetEvidence>();
        for (var old : historical.targets()) {
          var now = currentByTarget.remove(old.target());
          evidence.add(new TargetEvidence(old.target(), old.baseline().contentHash(), old.working().contentHash(),
              now == null ? null : now.baseline().contentHash()));
          if (now == null) {
            diagnostics.add(new Diagnostic(Code.TARGET_REMOVED, old.target()));
          } else if (!Objects.equals(old.authoringDocumentType(), now.authoring().documentType())
              || !Objects.equals(old.authoringSchemaRef(), now.authoring().schemaRef())
              || !Objects.equals(old.authoringSchemaVersion(), now.authoring().schemaVersion())
              || !Objects.equals(old.patchSchemaRef(), now.patch().schemaRef())
              || !Objects.equals(old.patchSchemaVersion(), now.patch().schemaVersion())) {
            diagnostics.add(new Diagnostic(Code.NATIVE_CONTRACT_CHANGED, old.target()));
          }
          diagnoseHistoricalTableRelation(old, diagnostics);
          diagnostics.add(new Diagnostic(Code.INTENT_PROVENANCE_UNVERIFIED, old.target()));
        }
        for (var added : currentByTarget.values()) {
          evidence.add(new TargetEvidence(added.target(), null, null, added.baseline().contentHash()));
          diagnostics.add(new Diagnostic(Code.TARGET_ADDED, added.target()));
        }
        diagnostics.add(new Diagnostic(Code.SOURCE_FENCING_UNAVAILABLE, null));
        if (!invocation.equals(validation.call(invocation, () -> invocations.resolve(principal, contextVersion.trim(), root)))) {
          throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
        }
        validation.run(invocation, () -> admission.require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation));
        for (var target : seed.targets()) validation.run(invocation, () -> access.require(invocation, target, validation));
        validateCurrent(invocation, current, validation);
        // Recheck historical and current visibility after the last native extension, retaining the same attempt.
        if (!historical.equals(validation.call(invocation, () -> history.recoverWithin(principal, contextVersion.trim(), root, releaseId, validation.forPurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ))))) {
          throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        }
        for (var target : seed.targets()) validation.run(invocation, () -> access.require(invocation, target, validation));
        if (!invocation.equals(validation.call(invocation, () -> invocations.resolve(principal, contextVersion.trim(), root)))) {
          throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
        }
        validation.run(invocation, () -> admission.require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation));
        validation.require(invocation);
        return new UiLayoutEvolutionReadinessReceipt(releaseId, historical.sourceDraftEtag(),
            invocation.contextVersion(), evidence, diagnostics, false);
      }
    } catch (UiLayoutLifecycleException exception) {
      throw failure(exception.getCode());
    } catch (RuntimeException exception) {
      throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
  }

  /** Historical document correlation only; never interpret old fields using today's schema. */
  private void diagnoseHistoricalTableRelation(
      org.praxisplatform.config.dto.UiLayoutHistoricalEvidenceReceipt.Target old,
      ArrayList<Diagnostic> diagnostics) {
    if (!"praxis.table.editor".equals(old.authoringDocumentType())) return;
    try {
      UiLayoutTableRevisionReproduction.require(old.target(),
          new UiLayoutAuthoringDocumentDescriptor(old.authoringDocumentType(), old.authoringSchemaRef(), old.authoringSchemaVersion()),
          old.baseline().document(), old.working().document(),
          new UiLayoutPatchDocumentDescriptor(old.patchSchemaRef(), old.patchSchemaVersion()), old.revisionPatch().document());
      diagnostics.add(new Diagnostic(Code.NATIVE_DOCUMENT_RELATION_REPRODUCED, old.target()));
    } catch (UiLayoutLifecycleException exception) {
      if (exception.getCode() != UiLayoutLifecycleException.Code.VALIDATION_FAILED
          && exception.getCode() != UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE) throw exception;
      diagnostics.add(new Diagnostic(Code.NATIVE_DOCUMENT_RELATION_UNVERIFIABLE, old.target()));
    }
  }

  private void validateCurrent(UiLayoutLifecycleInvocation invocation, UiLayoutDraftWorkspaceDocument current, UiLayoutValidationContext validation) {
    for (var target : current.targets()) {
      structure.validateTarget(invocation, target.target(), validation);
      structure.validateAuthoringBaseline(invocation, target.target(), target.authoring(), target.baseline().document(), validation);
    }
    structure.validateReleaseTargets(invocation, current.targets().stream().map(UiLayoutDraftWorkspaceDocument.TargetState::target).toList(), validation);
  }

  private UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Evolution readiness evidence is unavailable.");
  }
}
