package org.praxisplatform.config.service;

import java.security.Principal;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.praxisplatform.config.dto.UiLayoutDraftWorkspaceReceipt;
import org.praxisplatform.config.dto.UiLayoutDraftReceipt;
import org.praxisplatform.config.dto.UiLayoutReleaseHeadReceipt;
import org.praxisplatform.config.dto.UiLayoutReleaseReviewReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;

/** Scoped read-model owner for lifecycle state; it never exposes lifecycle entities. */
public class UiLayoutLifecycleReadService {
  private final UiLayoutValidationBudgetPolicy budgetPolicy;
  private final UiLayoutLifecycleInvocationProvider invocations;
  private final UiLayoutLifecycleAdmission admission;
  private final UiLayoutDraftRepository drafts;
  private final UiLayoutReleaseRepository releases;
  private final UiLayoutReleaseReviewRepository reviews;
  private final UiLayoutReleaseHeadRepository heads;
  private final UiLayoutDraftWorkspaceProjector workspaceProjector;

  public UiLayoutLifecycleReadService(UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
      UiLayoutDraftRepository drafts, UiLayoutReleaseRepository releases, UiLayoutReleaseReviewRepository reviews,
      UiLayoutReleaseHeadRepository heads, UiLayoutDefinitionRepository definitions,
      UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments,
      ObjectMapper mapper, CanonicalJsonHashService hashes, UiLayoutLifecycleStructureValidator structure, UiLayoutValidationBudgetPolicy budgetPolicy) {
    this.budgetPolicy = java.util.Objects.requireNonNull(budgetPolicy, "Validation budget policy is required.");
    this.invocations = invocations; this.admission = admission == null ? UiLayoutLifecycleAdmission.denyAll() : admission;
    this.drafts = drafts; this.releases = releases; this.reviews = reviews; this.heads = heads;
    this.workspaceProjector = new UiLayoutDraftWorkspaceProjector(new UiLayoutDraftWorkspaceCodec(mapper, hashes),
        definitions, revisions, assignments, releases, mapper, structure);
  }

  /** Observes an existing creation association; absence does not establish a failed command. */
  public UiLayoutDraftReceipt draftByCreationKey(Principal principal, String contextVersion,
      UiLayoutTarget root, String creationKey) {
    return draftByCreationKey(principal, contextVersion, root, creationKey, null);
  }

  UiLayoutDraftReceipt draftByCreationKey(Principal principal, String contextVersion,
      UiLayoutTarget root, String creationKey, UiLayoutValidationContext owner) {
    if (creationKey == null || creationKey.isBlank() || creationKey.trim().isEmpty() || creationKey.trim().length() > 180) {
      throw new IllegalArgumentException("Idempotency-Key must contain 1 to 180 characters after trimming.");
    }
    var validation = creationEntry(principal, contextVersion, root, owner);
    UiLayoutLifecycleInvocation invocation = validation.attempt().invocation();
    try (var attempt = validation.attempt(); var scope = UiLayoutValidationScope.bind(attempt)) {
      var result = validation.call(invocation, () -> {
        final java.util.Optional<UiLayoutDraft> observed;
        try {
          observed = drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
              invocation.tenantId(), invocation.environment(), invocation.rootTarget().componentType(),
              invocation.rootTarget().componentId(), invocation.actorRef(), creationKey.trim());
        } catch (RuntimeException exception) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
              "Draft association source is unavailable.");
        }
        if (!invocation.equals(lookupInvocation(principal, contextVersion, root))) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE,
              "Lifecycle context changed during the lookup.");
        }
        UiLayoutDraft value = observed.orElseThrow(() -> missing(""));
        try {
          return workspaceProjector.draft(value);
        } catch (RuntimeException exception) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
              "Draft association source is unavailable.");
        }
      });
      validation.run(invocation, () -> {
        if (!invocation.equals(invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_DRAFT))) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed during the read.");
        }
      });
      return result;
    }
  }

  private UiLayoutLifecycleInvocation lookupInvocation(Principal principal, String contextVersion, UiLayoutTarget root) {
    try {
      return invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_DRAFT);
    } catch (UiLayoutLifecycleException exception) {
      var code = switch (exception.getCode()) {
        case SESSION_REQUIRED, CONTEXT_STALE, DENIED, SOURCE_UNAVAILABLE -> exception.getCode();
        default -> UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE;
      };
      throw new UiLayoutLifecycleException(code, "Draft association access is unavailable.");
    }
  }

  public UiLayoutDraftWorkspaceReceipt draft(Principal principal, String contextVersion, UiLayoutTarget root, UUID draftId) {
    var validation = entry(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_DRAFT, null);
    UiLayoutLifecycleInvocation invocation = validation.attempt().invocation();
    try (var attempt = validation.attempt(); var scope = UiLayoutValidationScope.bind(attempt)) {
      var result = validation.call(invocation, () -> {
        UiLayoutDraft value = drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(draftId,
            invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId())
            .orElseThrow(() -> missing("Draft is unavailable in the authorized lifecycle scope."));
        return workspaceProjector.project(invocation, value, validation);
      });
      validation.run(invocation, () -> {
        if (!invocation.equals(invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_DRAFT))) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed during the read.");
        }
      });
      return result;
    }
  }

  public UiLayoutReleaseReviewReceipt review(Principal principal, String contextVersion, UiLayoutTarget root, UUID releaseId) {
    var validation = entry(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_REVIEW, null);
    UiLayoutLifecycleInvocation invocation = validation.attempt().invocation();
    try (var attempt = validation.attempt(); var scope = UiLayoutValidationScope.bind(attempt)) {
      var result = validation.call(invocation, () -> {
        release(invocation, root, releaseId);
        UiLayoutReleaseReview value = reviews.findByReleaseId(releaseId)
            .orElseThrow(() -> missing("Review is unavailable in the authorized lifecycle scope."));
        return new UiLayoutReleaseReviewReceipt(releaseId.toString(), root, value.getState(), value.getReviewEtag().toString(), value.getSubmittedAt());
      });
      validation.run(invocation, () -> {
        if (!invocation.equals(invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_REVIEW))) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed during the read.");
        }
      });
      return result;
    }
  }

  public UiLayoutReleaseHeadReceipt head(Principal principal, String contextVersion, UiLayoutTarget root) {
    var validation = entry(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_HEAD, null);
    UiLayoutLifecycleInvocation invocation = validation.attempt().invocation();
    try (var attempt = validation.attempt(); var scope = UiLayoutValidationScope.bind(attempt)) {
      var result = validation.call(invocation, () -> {
        UiLayoutReleaseHead value = heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
            invocation.tenantId(), invocation.environment(), root.componentType(), root.componentId())
            .orElseThrow(() -> missing("Release head is unavailable in the authorized lifecycle scope."));
        return new UiLayoutReleaseHeadReceipt(root, value.getActiveReleaseId() == null ? null : value.getActiveReleaseId().toString(),
            value.getHeadEtag().toString(), value.getUpdatedAt());
      });
      validation.run(invocation, () -> {
        if (!invocation.equals(invoke(principal, contextVersion, root, UiLayoutLifecycleOperation.READ_HEAD))) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed during the read.");
        }
      });
      return result;
    }
  }

  private UiLayoutRelease release(UiLayoutLifecycleInvocation invocation, UiLayoutTarget root, UUID releaseId) {
    return releases.findByIdAndTenantIdAndEnvironment(releaseId, invocation.tenantId(), invocation.environment())
        .filter(value -> root.componentType().equals(value.getRootComponentType()) && root.componentId().equals(value.getRootComponentId()))
        .orElseThrow(() -> missing("Release is unavailable in the authorized lifecycle scope."));
  }

  private UiLayoutValidationContext creationEntry(Principal principal, String version, UiLayoutTarget root,
      UiLayoutValidationContext owner) {
    try { return entry(principal, version, root, UiLayoutLifecycleOperation.READ_DRAFT, owner); }
    catch (UiLayoutLifecycleException exception) {
      var code = switch (exception.getCode()) {
        case SESSION_REQUIRED, CONTEXT_STALE, DENIED, SOURCE_UNAVAILABLE -> exception.getCode();
        default -> UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE;
      };
      throw new UiLayoutLifecycleException(code, "Draft association access is unavailable.");
    }
  }

  private UiLayoutValidationContext entry(Principal principal, String version, UiLayoutTarget root,
      UiLayoutLifecycleOperation operation, UiLayoutValidationContext owner) {
    var invocation = owner == null ? resolveInvocation(principal, version, root)
        : owner.call(owner.attempt().invocation(), () -> resolveInvocation(principal, version, root));
    var validation = owner == null ? UiLayoutValidationContext.begin(budgetPolicy, invocation, operation,
        UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ) : owner.nested(invocation, UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ);
    try {
      validation.run(invocation, () -> requireAdmission(operation, invocation));
      return validation;
    } catch (RuntimeException | Error failure) {
      validation.attempt().close();
      throw failure;
    }
  }

  private UiLayoutLifecycleInvocation invoke(Principal principal, String version, UiLayoutTarget root,
      UiLayoutLifecycleOperation operation) {
    var invocation = resolveInvocation(principal, version, root);
    requireAdmission(operation, invocation);
    return invocation;
  }

  private void requireAdmission(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
    try { admission.require(operation, invocation); }
    catch (UiLayoutLifecycleException known) { throw known; }
    catch (RuntimeException failure) { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Lifecycle context source is unavailable."); }
  }

  private UiLayoutLifecycleInvocation resolveInvocation(Principal principal, String contextVersion, UiLayoutTarget root) {
    if (principal == null) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SESSION_REQUIRED, "Authenticated host principal is required.");
    if (contextVersion == null || contextVersion.isBlank()) throw new IllegalArgumentException("X-Praxis-Context-Version is required.");
    try {
      UiLayoutLifecycleInvocation invocation = invocations.resolve(principal, contextVersion.trim(), root);
      if (invocation == null || !contextVersion.trim().equals(invocation.contextVersion()) || !root.equals(invocation.rootTarget())) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE, "Lifecycle context changed before the operation.");
      }
      return invocation;
    } catch (UiLayoutLifecycleException exception) { throw exception; }
    catch (RuntimeException exception) { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Lifecycle context source is unavailable."); }
  }

  private UiLayoutLifecycleException missing(String ignored) {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.NOT_FOUND, "Lifecycle state is unavailable.");
  }
}
