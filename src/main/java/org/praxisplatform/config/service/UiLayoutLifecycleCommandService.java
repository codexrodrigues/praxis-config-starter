package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.TransactionStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.dto.UiLayoutAssignmentRevisionReceipt;
import org.praxisplatform.config.dto.UiLayoutAudienceSelectorCommand;
import org.praxisplatform.config.dto.UiLayoutDraftWorkspaceReceipt;
import org.praxisplatform.config.dto.UiLayoutReleaseHeadReceipt;
import org.praxisplatform.config.dto.UiLayoutReleaseReceipt;
import org.praxisplatform.config.dto.UiLayoutReleaseReviewReceipt;
import org.praxisplatform.config.dto.UiLayoutRevisionReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseEventRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseApprovalRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public lifecycle writer; all callers enter host invocation, admission and native target validation here. */
public class UiLayoutLifecycleCommandService {
  private final UiLayoutValidationBudgetPolicy budgetPolicy;
  private final UiLayoutLifecycleService core;
  private final UiLayoutLifecycleInvocationProvider invocations;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutLifecycleStructureValidator structure;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseReviewRepository reviews;
  private final UiLayoutReleaseHeadRepository heads;
  private final ObjectMapper objectMapper;
  private final TransactionOperations transactions;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutReleaseMemberRepository members;
  private final UiLayoutAssignmentRevisionRepository assignments;
  private final UiLayoutRevisionRepository revisions;
  private final UiLayoutDefinitionRepository definitions;
  private final CanonicalJsonHashService hashes;
  private final UiLayoutDraftWorkspaceSource workspaceSource;
  private final UiLayoutDraftCreationLock creationLock;
  private final UiLayoutDraftWorkspaceCodec workspaceCodec;
  private final UiLayoutDraftWorkspaceProjector workspaceProjector;
  private final UiLayoutMetadataCapturePersistence metadataStore;
  private final UiLayoutBaselineMetadataAdmission metadataAdmission;
  private final UiLayoutMetadataCaptureCodec metadataCodec;
  private final UiLayoutFrozenWorkspaceResolver frozenWorkspace;

  UiLayoutLifecycleCommandService(UiLayoutLifecycleService core, UiLayoutLifecycleInvocationProvider invocations,
      UiLayoutLifecycleAdmission admission, UiLayoutLifecycleStructureValidator structure, UiLayoutDraftRepository drafts,
      UiLayoutReleaseReviewRepository reviews, UiLayoutReleaseHeadRepository heads, ObjectMapper objectMapper,
      TransactionOperations transactions, UiLayoutReleaseRepository releases, UiLayoutReleaseMemberRepository members,
      UiLayoutAssignmentRevisionRepository assignments, UiLayoutRevisionRepository revisions,
      UiLayoutDefinitionRepository definitions, CanonicalJsonHashService hashes,
      UiLayoutDraftWorkspaceSource workspaceSource, UiLayoutDraftCreationLock creationLock,
      UiLayoutMetadataCapturePersistence metadataStore, UiLayoutBaselineMetadataAdmission metadataAdmission, UiLayoutValidationBudgetPolicy budgetPolicy) {
    this.budgetPolicy = java.util.Objects.requireNonNull(budgetPolicy, "Validation budget policy is required.");
    this.core = core; this.invocations = invocations; this.admission = admission == null ? UiLayoutLifecycleAdmission.denyAll() : admission;
    this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.drafts = drafts; this.reviews = reviews; this.heads = heads; this.objectMapper = objectMapper; this.transactions = transactions;
    this.releases = releases; this.members = members; this.assignments = assignments; this.revisions = revisions; this.definitions = definitions; this.hashes = hashes;
    this.workspaceSource = workspaceSource; this.creationLock = creationLock;
    this.metadataStore = metadataStore;
    this.metadataAdmission = metadataAdmission == null ? UiLayoutBaselineMetadataAdmission.denyAll() : metadataAdmission;
    this.metadataCodec = new UiLayoutMetadataCaptureCodec(objectMapper, hashes);
    this.workspaceCodec = new UiLayoutDraftWorkspaceCodec(objectMapper, hashes);
    this.frozenWorkspace = new UiLayoutFrozenWorkspaceResolver(releases, drafts, members, definitions, revisions, assignments, objectMapper, hashes);
    this.workspaceProjector = new UiLayoutDraftWorkspaceProjector(workspaceCodec, definitions, revisions, assignments,
        releases, objectMapper, this.structure);
  }

  /** Creates the package-private persistence core only when all host-owned B1c seams are present. */
  public static UiLayoutLifecycleCommandService create(UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments, UiLayoutDraftRepository drafts, UiLayoutReleaseRepository releases,
      UiLayoutReleaseMemberRepository members, UiLayoutReleaseReviewRepository reviews, UiLayoutReleaseApprovalRepository approvals,
      UiLayoutReleaseHeadRepository heads, UiLayoutReleaseEventRepository events, UiLayoutRevisionAllocationLock allocationLock,
      CanonicalJsonHashService hashes, ObjectMapper mapper, UiLayoutReleaseValidator releaseValidator,
      UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
      UiLayoutLifecycleStructureValidator structure, UiLayoutDraftWorkspaceSource workspaceSource,
      UiLayoutDraftCreationLock creationLock, NamedParameterJdbcTemplate configJdbc,
      UiLayoutBaselineMetadataAdmission metadataAdmission, TransactionOperations transactions, UiLayoutValidationBudgetPolicy budgetPolicy) {
    if (configJdbc == null || metadataAdmission == null) throw new IllegalArgumentException("Config metadata storage and admission are required.");
    return assemble(definitions, revisions, assignments, drafts, releases, members, reviews, approvals, heads, events,
        allocationLock, hashes, mapper, releaseValidator, invocations, admission, structure, workspaceSource, creationLock,
        new JdbcUiLayoutMetadataCaptureStore(configJdbc, mapper, hashes), metadataAdmission, transactions, budgetPolicy);
  }

  /** Internal composition seam; production factory always installs the canonical Config JDBC store. */
  static UiLayoutLifecycleCommandService assemble(UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
      UiLayoutAssignmentRevisionRepository assignments, UiLayoutDraftRepository drafts, UiLayoutReleaseRepository releases,
      UiLayoutReleaseMemberRepository members, UiLayoutReleaseReviewRepository reviews, UiLayoutReleaseApprovalRepository approvals,
      UiLayoutReleaseHeadRepository heads, UiLayoutReleaseEventRepository events, UiLayoutRevisionAllocationLock allocationLock,
      CanonicalJsonHashService hashes, ObjectMapper mapper, UiLayoutReleaseValidator releaseValidator,
      UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
      UiLayoutLifecycleStructureValidator structure, UiLayoutDraftWorkspaceSource workspaceSource,
      UiLayoutDraftCreationLock creationLock, UiLayoutMetadataCapturePersistence metadataStore,
      UiLayoutBaselineMetadataAdmission metadataAdmission, TransactionOperations transactions, UiLayoutValidationBudgetPolicy budgetPolicy) {
    UiLayoutLifecycleService core = new UiLayoutLifecycleService(definitions, revisions, assignments, drafts, releases, members,
        reviews, approvals, heads, events, allocationLock, hashes, mapper, (operation, actor, tenant, unit, environment, target) -> true,
        releaseValidator, java.time.Clock.systemUTC());
    return new UiLayoutLifecycleCommandService(core, invocations, admission, structure, drafts, reviews, heads, mapper, transactions,
        releases, members, assignments, revisions, definitions, hashes, workspaceSource, creationLock, metadataStore, metadataAdmission, budgetPolicy);
  }

  public UiLayoutDraftWorkspaceReceipt createDraft(Principal principal, String contextVersion, UiLayoutTarget root,
      String idempotencyKey) {
    String key = bounded(idempotencyKey, "Idempotency-Key", 180);
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.CREATE_DRAFT);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.CREATE_DRAFT, validation, status -> {
      if (metadataStore == null) throw unavailable("Metadata storage is unavailable.");
      if (creationLock == null) throw unavailable("Draft creation lock is unavailable.");
      validation.run(invocation, () -> creationLock.lock(invocation.tenantId(), invocation.environment(), root, invocation.actorRef(), key));
      var replay = drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
          invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId(), invocation.actorRef(), key);
      if (replay.isPresent()) {
        revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.READ_DRAFT, validation);
        var draft = replay.orElseThrow();
        var receipt = workspaceProjector.project(invocation, draft, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ));
        requireMetadataEvidence(invocation, draft, null, validation);
        revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.READ_DRAFT, validation);
        requireMetadataEvidence(invocation, draft, null, validation);
        revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.READ_DRAFT, validation);
        return receipt;
      }
      revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, validation);
      UiLayoutDraftWorkspaceSeed seed;
      try { seed = workspaceSource == null ? null : validation.call(invocation, () -> workspaceSource.capture(invocation, validation)); }
      catch (UiLayoutLifecycleException exception) {
        throw new UiLayoutLifecycleException(exception.getCode(), "Workspace source is unavailable.");
      }
      catch (RuntimeException exception) { throw unavailable("Workspace source is unavailable."); }
      if (seed == null) throw unavailable("Workspace source returned no seed.");
      for (var target : seed.targets()) metadataCodec.requireSeedSyntax(target.metadata(), target.document());
      UiLayoutDraftWorkspaceDocument document = workspaceCodec.fromSeed(invocation, seed);
      for (UiLayoutDraftWorkspaceDocument.TargetState target : document.targets()) {
        structure.validateTarget(invocation, target.target(), validation);
        structure.validateAuthoringBaseline(invocation, target.target(), target.authoring(), target.baseline().document(), validation);
      }
      for (var target : seed.targets()) {
        var observation = target.metadata().observation();
        if (!invocation.actorRef().equals(observation.actorRef())
            || !invocation.administrativeUnit().equals(observation.administrativeUnit())
            || !invocation.contextVersion().equals(observation.contextVersion())) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Metadata observation context differs from the current invocation.");
        }
        requireMetadata(invocation, target.target(), target.authoring(), target.sourceRef(), target.document(), target.metadata(), validation);
      }
      revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, validation);
      UiLayoutDraft draft = validation.call(invocation, () -> core.createDraft(scope(invocation), workspaceCodec.encode(document), invocation.actorRef(), key));
      // The Config JDBC store must see the new JPA draft inside this same transaction.
      validation.run(invocation, () -> drafts.flush());
      List<UiLayoutMetadataCapture> captured = new ArrayList<>();
      for (int index = 0; index < document.targets().size(); index++) {
        var target = document.targets().get(index);
        var metadata = seed.targets().get(index).metadata();
        captured.add(metadataCodec.seal(UUID.randomUUID(), metadataBinding(invocation, draft.getId(), target),
            target.baseline().document(), metadata.source(), metadata.reproduction(), metadata.observation(), metadata.rawInputText()));
      }
      validation.run(invocation, () -> metadataStore.append(status, invocation, draft.getId(), captured, (current, capture) -> requireMetadataAccess(current, capture, validation)));
      var receipt = workspaceProjector.project(invocation, draft, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ));
      revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, validation);
      requireMetadataEvidence(invocation, draft, captured, validation);
      revalidate(principal, contextVersion, root, invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, validation);
      return receipt;
    });
  }

  private void revalidate(Principal principal, String version, UiLayoutTarget root,
      UiLayoutLifecycleInvocation expected, UiLayoutLifecycleOperation operation, UiLayoutValidationContext validation) {
    if (!expected.equals(validation.call(expected, () -> invoke(principal, version, root, operation)))) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed during the operation.");
    }
  }

  private UiLayoutMetadataCapture.Binding metadataBinding(UiLayoutLifecycleInvocation invocation, UUID draft,
      UiLayoutDraftWorkspaceDocument.TargetState target) {
    return new UiLayoutMetadataCapture.Binding(draft,
        new UiLayoutMetadataCapture.Scope(invocation.tenantId(), invocation.environment(), invocation.rootTarget(), target.target()),
        target.baseline().sourceRef(), target.authoring(), target.baseline().contentHash());
  }

  private void requireMetadataEvidence(UiLayoutLifecycleInvocation invocation, UiLayoutDraft draft,
      List<UiLayoutMetadataCapture> expectedCaptures, UiLayoutValidationContext validation) {
    var document = workspaceProjector.decode(invocation, draft);
    var storedCaptures = new ArrayList<UiLayoutMetadataCapture>();
    for (int index = 0; index < document.targets().size(); index++) {
      var expected = metadataBinding(invocation, draft.getId(), document.targets().get(index));
      var stored = validation.call(invocation, () -> metadataStore.read(invocation, expected, (current, capture) -> requireMetadataAccess(current, capture, validation)));
      metadataCodec.verifyAndRead(invocation, expected, stored, (current, capture) -> requireMetadataAccess(current, capture, validation));
      if (expectedCaptures != null && !expectedCaptures.get(index).equals(stored)) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, "Metadata evidence changed before return.");
      }
      storedCaptures.add(stored);
    }
    metadataCodec.requireCompositionBudget(storedCaptures);
  }

  private void requireMetadataAccess(UiLayoutLifecycleInvocation invocation, UiLayoutMetadataCapture capture, UiLayoutValidationContext validation) {
    var binding = capture.binding();
    requireMetadata(invocation, binding.scope().target(), binding.authoring(), binding.baselineSourceRef(),
        capture.baselineDocument(), new UiLayoutBaselineMetadataSeed(capture.source(), capture.reproduction(),
            capture.observation(), capture.rawInputText()), validation);
  }

  private void requireMetadata(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor authoring, String source, JsonNode baseline, UiLayoutBaselineMetadataSeed metadata, UiLayoutValidationContext validation) {
    try { validation.run(invocation, () -> metadataAdmission.require(invocation, target, authoring, source, baseline.deepCopy(), metadata)); }
    catch (RuntimeException exception) {
      throw UiLayoutMetadataAdmissionFailure.sanitized(exception, "Metadata admission is unavailable.");
    }
  }

  private <T> T inConfigTransactionWithStatus(Function<TransactionStatus, T> work) {
    T result = transactions.execute(status -> {
      try { return work.apply(status); }
      catch (RuntimeException exception) { status.setRollbackOnly(); throw exception; }
    });
    if (result == null) throw unavailable("Lifecycle Config transaction returned no result.");
    return result;
  }

  public RevisionResult createRevision(Principal principal, String contextVersion, UiLayoutTarget root, UUID draftId,
      String draftEtag, RevisionInput input) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.EDIT_DRAFT);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.EDIT_DRAFT, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.EDIT_DRAFT, validation, status -> {
      requireCommandRef(input.commandRef());
      JsonNode candidate = UiLayoutRevisionJsonInput.readDocument(input.authoringDocument(), () -> validation.require(invocation));
      workspaceCodec.requireSize(candidate, "authoringDocument");
      UiLayoutDraft current = validation.call(invocation, () -> core.requireEditableDraft(scope(invocation), draftId, uuid(draftEtag, "If-Match"), invocation.actorRef()));
      workspaceProjector.project(invocation, current, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ));
      UiLayoutDraftWorkspaceDocument workspace = workspaceProjector.decode(invocation, current);
      int index = targetIndex(workspace, input.target());
      UiLayoutDraftWorkspaceDocument.TargetState target = workspace.targets().get(index);
      UiLayoutJsonBounds.requireDocument(target.baseline().document(), UiLayoutLifecycleException.Code.INVALID_STATE,
          () -> validation.require(invocation));
      structure.validateTarget(invocation, input.target(), validation);
      JsonNode patch = UiLayoutAuthoringProjection.project(input.target(), target.authoring(),
          target.baseline().document(), candidate, target.patch(), () -> validation.require(invocation)).compilePatch();
      workspaceCodec.requireSize(patch, "patchDocument");
      structure.validateAuthoringRevision(invocation, input.target(), target.authoring(), target.baseline().document(),
          target.authoring(), candidate, target.patch(), patch, validation);
      structure.validatePatch(invocation, input.target(), patch, validation);
      UiLayoutTableRevisionReproduction.require(input.target(), target.authoring(),
          target.baseline().document(), candidate, target.patch(), patch);
      UiLayoutFormRevisionReproduction.require(input.target(), target.authoring(),
          target.baseline().document(), candidate, target.patch(), patch);
      UiLayoutRevision revision = validation.call(invocation, () -> core.createRevision(new UiLayoutLifecycleService.RevisionCommand(scope(invocation), draftId,
          uuid(draftEtag, "If-Match"), input.target(), patch.toString(), target.patch().schemaVersion(), input.reason()), invocation.actorRef()));
      var nextTarget = new UiLayoutDraftWorkspaceDocument.TargetState(target.target(), target.authoring(), target.patch(),
          target.baseline(), new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(candidate), candidate),
          new UiLayoutDraftWorkspaceDocument.RevisionSelection(revision.getId(), revision.getRevisionNumber(),
              revision.getContentHash(), revision.getSchemaVersion(), input.commandRef()), null);
      UiLayoutDraft updated = validation.call(invocation, () -> core.updateDraftWorkspace(scope(invocation), draftId, uuid(draftEtag, "If-Match"),
          workspaceCodec.encode(replaceTarget(workspace, index, nextTarget)), invocation.actorRef()));
      return new RevisionResult(new UiLayoutRevisionReceipt(revision.getId().toString(), input.target(), revision.getContentHash(),
          revision.getSchemaVersion(), revision.getRevisionNumber()), workspaceProjector.project(invocation, updated, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ)));
    });
  }

  public AssignmentResult createAssignment(Principal principal, String contextVersion, UiLayoutTarget root, UUID draftId,
      String draftEtag, AssignmentInput input) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.EDIT_DRAFT);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.EDIT_DRAFT, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.EDIT_DRAFT, validation, status -> {
      requireCommandRef(input.commandRef());
      JsonNode selectorNode = objectMapper.valueToTree(input.selector());
      if (selectorNode == null || !selectorNode.isObject()) throw invalid("selector is required.");
      structure.validateTarget(invocation, input.target(), validation);
      UiLayoutResolutionCandidate.LayerClass layer = layer(input.layerClass());
      UiLayoutAudienceSelector selector = selector(selectorNode, invocation.tenantId());
      structure.validateAssignment(invocation, input.target(), layer, selector, validation);
      UiLayoutDraft current = validation.call(invocation, () -> core.requireEditableDraft(scope(invocation), draftId, uuid(draftEtag, "If-Match"), invocation.actorRef()));
      workspaceProjector.project(invocation, current, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ));
      UiLayoutDraftWorkspaceDocument workspace = workspaceProjector.decode(invocation, current);
      int index = targetIndex(workspace, input.target());
      UiLayoutDraftWorkspaceDocument.TargetState target = workspace.targets().get(index);
      if (target.selectedRevision() == null) throw invalid("A selected revision is required before assignment.");
      UiLayoutAssignmentRevision assignment = validation.call(invocation, () -> core.createAssignment(new UiLayoutLifecycleService.AssignmentCommand(scope(invocation), draftId,
          uuid(draftEtag, "If-Match"), target.selectedRevision().revisionRef(), input.target(), layer.name(), selectorNode.toString(), input.priority(), input.contributionKey()),
          invocation.actorRef()));
      var nextTarget = new UiLayoutDraftWorkspaceDocument.TargetState(target.target(), target.authoring(), target.patch(),
          target.baseline(), target.working(), target.selectedRevision(),
          new UiLayoutDraftWorkspaceDocument.AssignmentSelection(assignment.getId(), assignment.getRevisionId(),
              assignment.getLayerClass(), input.selector(), assignment.getPriority(), assignment.getContributionKey(), input.commandRef()));
      UiLayoutDraft updated = validation.call(invocation, () -> core.updateDraftWorkspace(scope(invocation), draftId, uuid(draftEtag, "If-Match"),
          workspaceCodec.encode(replaceTarget(workspace, index, nextTarget)), invocation.actorRef()));
      return new AssignmentResult(new UiLayoutAssignmentRevisionReceipt(assignment.getId().toString(), assignment.getRevisionId().toString(),
          input.target(), assignment.getContributionKey(), assignment.getLayerClass(), assignment.getPriority()), workspaceProjector.project(invocation, updated, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ)));
    });
  }

  public ReleaseResult freezeRelease(Principal principal, String contextVersion, UiLayoutTarget root, UUID draftId,
      String draftEtag, UUID commandRef) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.SUBMIT_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.SUBMIT_RELEASE, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.SUBMIT_RELEASE, validation, status -> {
      requireCommandRef(commandRef);
      UiLayoutDraft current = validation.call(invocation, () -> core.requireEditableDraft(scope(invocation), draftId, uuid(draftEtag, "If-Match"), invocation.actorRef()));
      workspaceProjector.project(invocation, current, validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ));
      UiLayoutDraftWorkspaceDocument workspace = workspaceProjector.decode(invocation, current);
      List<UiLayoutLifecycleService.MemberCommand> derivedMembers = new ArrayList<>();
      for (int index = 0; index < workspace.targets().size(); index++) {
        UiLayoutDraftWorkspaceDocument.TargetState target = workspace.targets().get(index);
        if (target.selectedRevision() == null || target.selectedAssignment() == null) {
          throw invalid("Every registered target requires a selected revision and assignment before freeze.");
        }
        derivedMembers.add(new UiLayoutLifecycleService.MemberCommand(index, target.target(),
            target.selectedAssignment().assignmentRevisionRef(), target.selectedRevision().revisionRef()));
      }
      structure.validateReleaseTargets(invocation, workspace.targets().stream().map(UiLayoutDraftWorkspaceDocument.TargetState::target).toList(), validation);
      UiLayoutDraftWorkspaceDocument frozenWorkspace = new UiLayoutDraftWorkspaceDocument(
          workspace.schemaVersion(), workspace.targets(), commandRef);
      UiLayoutRelease release = validation.call(invocation, () -> core.createRelease(new UiLayoutLifecycleService.ReleaseCommand(scope(invocation), draftId,
          uuid(draftEtag, "If-Match"), derivedMembers, workspaceCodec.encode(frozenWorkspace)), invocation.actorRef()));
      UiLayoutReleaseReview review = reviews.findByReleaseId(release.getId())
          .orElseThrow(() -> unavailable("Release review could not be read after freezing."));
      return new ReleaseResult(new UiLayoutReleaseReceipt(release.getId().toString(), root, derivedMembers.size()),
          workspaceProjector.project(invocation, currentDraft(invocation, root, draftId), validation.forPurpose(UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ)), review(release, review));
    });
  }

  public UiLayoutReleaseReviewReceipt submit(Principal principal, String contextVersion, UiLayoutTarget root, UUID releaseId, String reviewEtag) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.SUBMIT_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.SUBMIT_RELEASE, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.SUBMIT_RELEASE, validation, status -> { validation.run(invocation, () -> core.submit(scope(invocation), releaseId, uuid(reviewEtag, "If-Match"), invocation.actorRef())); return review(releaseId, root); });
  }

  public UiLayoutReleaseReviewReceipt approve(Principal principal, String contextVersion, UiLayoutTarget root, UUID releaseId, String reviewEtag, String reason) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.APPROVE_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.APPROVE_RELEASE, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.APPROVE_RELEASE, validation, status -> { validation.run(invocation, () -> core.approve(scope(invocation), releaseId, uuid(reviewEtag, "If-Match"), invocation.actorRef(), required(reason, "reason"))); return review(releaseId, root); });
  }

  public UiLayoutReleaseHeadReceipt publish(Principal principal, String contextVersion, UiLayoutTarget root, HeadWriteCondition condition,
      UUID releaseId, String reason) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.PUBLISH_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.PUBLISH_RELEASE, UiLayoutValidationPurpose.FROZEN_RELEASE);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.PUBLISH_RELEASE, validation, status -> { UUID expected = validateHeadCondition(invocation, root, condition, true); structure.validateFrozenRelease(invocation, frozenRelease(invocation, root, releaseId, validation), validation);
      UiLayoutReleaseHead head = validation.call(invocation, () -> core.publish(scope(invocation), releaseId, expected, invocation.actorRef(), required(reason, "reason"))); return head(root, head); });
  }

  public UiLayoutReleaseHeadReceipt withdraw(Principal principal, String contextVersion, UiLayoutTarget root, HeadWriteCondition condition, String reason) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.WITHDRAW_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.WITHDRAW_RELEASE, UiLayoutValidationPurpose.AUTHORING);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.WITHDRAW_RELEASE, validation, status -> { UiLayoutReleaseHead head = validation.call(invocation, () -> core.withdraw(scope(invocation), validateHeadCondition(invocation, root, condition, false), invocation.actorRef(), required(reason, "reason"))); return head(root, head); });
  }

  public UiLayoutReleaseHeadReceipt rollback(Principal principal, String contextVersion, UiLayoutTarget root, HeadWriteCondition condition,
      UUID releaseId, String reason) {
    UiLayoutLifecycleInvocation invocation = invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.ROLLBACK_RELEASE);
    UiLayoutValidationContext validation = beginValidation(invocation, UiLayoutLifecycleOperation.ROLLBACK_RELEASE, UiLayoutValidationPurpose.FROZEN_RELEASE);
    return inAuthorizedConfigTransaction(principal, contextVersion, root, invocation,
        UiLayoutLifecycleOperation.ROLLBACK_RELEASE, validation, status -> { structure.validateFrozenRelease(invocation, frozenRelease(invocation, root, releaseId, validation), validation); UiLayoutReleaseHead head = validation.call(invocation, () -> core.rollback(scope(invocation), releaseId, validateHeadCondition(invocation, root, condition, false), invocation.actorRef(), required(reason, "reason"))); return head(root, head); });
  }

  /** Internal construction seam; the public factory always uses mandatory server budget policy. */
  UiLayoutValidationContext beginValidation(UiLayoutLifecycleInvocation invocation,
      UiLayoutLifecycleOperation operation, UiLayoutValidationPurpose purpose) {
    return UiLayoutValidationContext.begin(budgetPolicy, invocation, operation, purpose);
  }

  private UiLayoutLifecycleInvocation invoke(Principal principal, String contextVersion, UiLayoutTarget root, UiLayoutLifecycleOperation operation) {
    if (principal == null) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SESSION_REQUIRED, "Authenticated host principal is required.");
    if (contextVersion == null || contextVersion.isBlank()) throw new IllegalArgumentException("X-Praxis-Context-Version is required.");
    try {
      UiLayoutLifecycleInvocation invocation = invocations.resolve(principal, contextVersion.trim(), root);
      if (invocation == null || !contextVersion.trim().equals(invocation.contextVersion()) || !root.equals(invocation.rootTarget())) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed before the operation.");
      }
      admission.require(operation, invocation);
      return invocation;
    } catch (UiLayoutLifecycleException exception) {
      throw new UiLayoutLifecycleException(exception.getCode(), "Lifecycle context or admission is unavailable.");
    }
    catch (RuntimeException exception) { throw unavailable("Lifecycle context source is unavailable."); }
  }

  /** Rechecks the host-owned authority at transaction entry, after work and before actual commit. */
  private <T> T inAuthorizedConfigTransaction(Principal principal, String contextVersion, UiLayoutTarget root,
      UiLayoutLifecycleInvocation expected, UiLayoutLifecycleOperation operation, UiLayoutValidationContext validation,
      Function<TransactionStatus, T> work) {
    var registered = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      return inConfigTransactionWithStatus(status -> {
        validation.require(expected);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
          throw unavailable("Lifecycle writes require an active writable Config transaction with synchronization.");
        }
        validation.run(expected, () -> revalidate(principal, contextVersion, root, expected, operation, validation));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override public void beforeCommit(boolean readOnly) {
            validation.run(expected, () -> revalidate(principal, contextVersion, root, expected, operation, validation));
          }
          @Override public void afterCompletion(int completionStatus) { validation.attempt().close(); }
        });
        registered.set(true);
        T result = validation.call(expected, () -> work.apply(status));
        if (result == null) throw unavailable("Lifecycle Config transaction returned no result.");
        validation.run(expected, () -> revalidate(principal, contextVersion, root, expected, operation, validation));
        return result;
      });
    } catch (RuntimeException | Error exception) {
      validation.attempt().close();
      throw exception;
    } finally {
      // A successful participating command retains its attempt until the outer commit/completion.
      if (!registered.get()) validation.attempt().close();
    }
  }

  private UUID validateHeadCondition(UiLayoutLifecycleInvocation invocation, UiLayoutTarget root, HeadWriteCondition condition, boolean allowCreate) {
    if (condition == null) throw precondition();
    var existing = heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId());
    if (existing.isEmpty()) {
      if (allowCreate && condition.createOnly()) return null;
      throw precondition();
    }
    if (condition.createOnly() || condition.etag() == null || !condition.etag().equals(existing.orElseThrow().getHeadEtag().toString())) throw precondition();
    return existing.orElseThrow().getHeadEtag();
  }

  private UiLayoutDraft currentDraft(UiLayoutLifecycleInvocation invocation, UiLayoutTarget root, UUID draftId) {
    return drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(draftId, invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId())
        .orElseThrow(() -> unavailable("Draft could not be read after mutation."));
  }

  /**
   * Reconstructs the frozen aggregate from its immutable members before a head move. This does
   * not reinterpret lifecycle policy: it supplies the exact pinned target, patch and selector to
   * the host-owned validator under the current invocation.
   */
  private List<UiLayoutLifecycleFrozenContribution> frozenRelease(UiLayoutLifecycleInvocation invocation,
      UiLayoutTarget root, UUID releaseId, UiLayoutValidationContext validation) {
    var frozen = frozenWorkspace.resolve(invocation, releaseId, target -> structure.validateTarget(invocation, target, validation));
    var targets = frozen.document().targets().stream().map(UiLayoutDraftWorkspaceDocument.TargetState::target).toList();
    if (!targets.equals(invocation.composition().targets())) throw invalid("Frozen release differs from the current registered composition.");
    structure.validateReleaseTargets(invocation, targets, validation);
    List<UiLayoutLifecycleFrozenContribution> result = new ArrayList<>();
    for (int index = 0; index < frozen.document().targets().size(); index++) {
      var state = frozen.document().targets().get(index);
      var assignment = state.selectedAssignment(); var revision = state.selectedRevision();
      var patch = frozen.patches().get(index);
      UiLayoutTableRevisionReproduction.require(state.target(), state.authoring(), state.baseline().document(),
          state.working().document(), state.patch(), patch);
      UiLayoutFormRevisionReproduction.require(state.target(), state.authoring(), state.baseline().document(),
          state.working().document(), state.patch(), patch);
      result.add(new UiLayoutLifecycleFrozenContribution(state.target(), assignment.contributionKey(),
          new UiLayoutResolutionCandidate(state.target(), revision.revisionRef().toString(), revision.contentHash(),
              layer(assignment.layerClass()), assignment.priority(), selector(objectMapper.valueToTree(assignment.selector()), invocation.tenantId()), patch, null),
          new UiLayoutLifecycleFrozenContribution.NativeAuthoring(frozen.releaseId(), frozen.draftId(), frozen.document().freezeCommandRef(),
              state.authoring(), state.patch(), state.baseline().sourceRef(), state.baseline().contentHash(), state.baseline().document(),
              state.working().contentHash(), state.working().document())));
    }
    return List.copyOf(result);
  }

  private UiLayoutReleaseReviewReceipt review(UUID releaseId, UiLayoutTarget root) {
    UiLayoutReleaseReview review = reviews.findByReleaseId(releaseId).orElseThrow(() -> unavailable("Review could not be read after mutation."));
    return new UiLayoutReleaseReviewReceipt(releaseId.toString(), root, review.getState(), review.getReviewEtag().toString(), review.getSubmittedAt());
  }
  private UiLayoutReleaseReviewReceipt review(UiLayoutRelease release, UiLayoutReleaseReview review) {
    return new UiLayoutReleaseReviewReceipt(release.getId().toString(), new UiLayoutTarget(release.getRootComponentType(), release.getRootComponentId()),
        review.getState(), review.getReviewEtag().toString(), review.getSubmittedAt());
  }
  private UiLayoutReleaseHeadReceipt head(UiLayoutTarget root, UiLayoutReleaseHead value) { return new UiLayoutReleaseHeadReceipt(root, value.getActiveReleaseId() == null ? null : value.getActiveReleaseId().toString(), value.getHeadEtag().toString(), value.getUpdatedAt()); }
  private UiLayoutLifecycleService.Scope scope(UiLayoutLifecycleInvocation value) { return new UiLayoutLifecycleService.Scope(value.tenantId(), value.administrativeUnit(), value.environment(), value.rootTarget()); }
  private UiLayoutAudienceSelector selector(JsonNode node, String tenant) { java.util.Set<String> fields = java.util.Set.of("tenant", "organization", "sector", "group", "profile", "user"); node.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw invalid("selectorDocument has an unknown field."); }); String selectorTenant = text(node, "tenant", true); if (!tenant.equals(selectorTenant)) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Selector tenant is not authorized."); return new UiLayoutAudienceSelector(selectorTenant, text(node, "organization", false), text(node, "sector", false), text(node, "group", false), text(node, "profile", false), text(node, "user", false)); }
  private String text(JsonNode node, String name, boolean required) { JsonNode value = node.get(name); if (value == null || value.isNull()) { if (required) throw invalid("selectorDocument requires " + name + "."); return null; } if (!value.isTextual() || value.textValue().isBlank()) throw invalid("selectorDocument has an invalid field."); return value.textValue(); }
  private UiLayoutResolutionCandidate.LayerClass layer(String value) { try { return UiLayoutResolutionCandidate.LayerClass.valueOf(required(value, "layerClass")); } catch (IllegalArgumentException exception) { throw invalid("layerClass is invalid."); } }
  private UUID uuid(String value, String name) { try { return UUID.fromString(required(value, name)); } catch (IllegalArgumentException exception) { throw precondition(); } }
  private String required(String value, String name) { if (value == null || value.isBlank()) throw invalid(name + " is required."); return value.trim(); }
  private String bounded(String value, String name, int maximumLength) { String result = required(value, name); if (result.length() > maximumLength) throw invalid(name + " exceeds " + maximumLength + " characters."); return result; }
  private void requireCommandRef(UUID commandRef) { if (commandRef == null) throw invalid("commandRef is required."); }
  private int targetIndex(UiLayoutDraftWorkspaceDocument workspace, UiLayoutTarget target) {
    for (int index = 0; index < workspace.targets().size(); index++) if (workspace.targets().get(index).target().equals(target)) return index;
    throw invalid("target is not part of the persisted workspace.");
  }
  private UiLayoutDraftWorkspaceDocument replaceTarget(UiLayoutDraftWorkspaceDocument workspace, int index,
      UiLayoutDraftWorkspaceDocument.TargetState replacement) {
    List<UiLayoutDraftWorkspaceDocument.TargetState> targets = new ArrayList<>(workspace.targets());
    targets.set(index, replacement);
    return new UiLayoutDraftWorkspaceDocument(workspace.schemaVersion(), targets, workspace.freezeCommandRef());
  }
  private UiLayoutLifecycleException invalid(String message) { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_RELEASE, message); }
  private UiLayoutLifecycleException unavailable(String message) { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, message); }
  private UiLayoutLifecycleException precondition() { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.PRECONDITION_FAILED, "Lifecycle precondition failed."); }

  public record RevisionInput(UUID commandRef, UiLayoutTarget target, String authoringDocument, String reason) {}
  public record AssignmentInput(UUID commandRef, UiLayoutTarget target, String layerClass, UiLayoutAudienceSelectorCommand selector, short priority, String contributionKey) {}
  @io.swagger.v3.oas.annotations.media.Schema(description = "Result of creating one immutable revision and rotating its source draft validator.")
  public record RevisionResult(
      @io.swagger.v3.oas.annotations.media.Schema(description = "New immutable revision identity and integrity metadata.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutRevisionReceipt revision,
      @io.swagger.v3.oas.annotations.media.Schema(description = "Current workspace with its accepted command selection and rotated draft validator.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutDraftWorkspaceReceipt nextDraft) {}
  @io.swagger.v3.oas.annotations.media.Schema(description = "Result of creating one immutable assignment and rotating its source draft validator.")
  public record AssignmentResult(
      @io.swagger.v3.oas.annotations.media.Schema(description = "New immutable assignment identity without its private selector.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutAssignmentRevisionReceipt assignment,
      @io.swagger.v3.oas.annotations.media.Schema(description = "Current workspace with its accepted command selection and rotated draft validator.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutDraftWorkspaceReceipt nextDraft) {}
  @io.swagger.v3.oas.annotations.media.Schema(description = "Result of freezing an immutable release and exposing the next draft and review validators.")
  public record ReleaseResult(
      @io.swagger.v3.oas.annotations.media.Schema(description = "New immutable aggregate release identity.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutReleaseReceipt release,
      @io.swagger.v3.oas.annotations.media.Schema(description = "Source workspace now marked RELEASED with its authoritative frozenReleaseRef.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutDraftWorkspaceReceipt nextDraft,
      @io.swagger.v3.oas.annotations.media.Schema(description = "New mutable review receipt in DRAFT state; its validator drives submit/approve.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UiLayoutReleaseReviewReceipt review) {}
  public record HeadWriteCondition(String etag, boolean createOnly) { public static HeadWriteCondition firstPublish() { return new HeadWriteCondition(null, true); } public static HeadWriteCondition match(String etag) { return new HeadWriteCondition(etag, false); } }
}
