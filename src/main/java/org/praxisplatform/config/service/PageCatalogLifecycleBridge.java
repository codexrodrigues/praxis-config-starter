package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.dto.PageSourceReference;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Canonical bridge from immutable page creation evidence into existing lifecycle seams. */
final class PageCatalogLifecycleBridge {
  static final String PRODUCER_SERVICE = "praxis-config-page-creation";
  static final int MAX_CHAIN = 16;
  static final long MAX_CHAIN_BYTES = 8L * 1024 * 1024;
  private static final UiLayoutPatchDocumentDescriptor PATCH =
      new UiLayoutPatchDocumentDescriptor("praxis:page-copy:patch", "praxis.ui-layout/v1");
  private final PageCreationHost host;
  private final PageCatalogStore store;
  private final PageCreationCodec codec;
  private final CanonicalJsonHashService hashes;
  private final UiLayoutDraftWorkspaceSource nativeSource;
  private final UiLayoutBaselineMetadataAdmission metadata;
  private final UiLayoutLifecycleStructureValidator structure;
  private final UiLayoutFrozenWorkspaceResolver frozen;
  private final UiLayoutMetadataCapturePersistence captures;
  private final UiLayoutMetadataCaptureCodec captureCodec;
  private final UiLayoutValidationBudgetPolicy budgets;

  PageCatalogLifecycleBridge(
      PageCreationHost host,
      PageCatalogStore store,
      PageCreationCodec codec,
      CanonicalJsonHashService hashes,
      UiLayoutDraftWorkspaceSource nativeSource,
      UiLayoutBaselineMetadataAdmission metadata,
      UiLayoutLifecycleStructureValidator structure,
      UiLayoutFrozenWorkspaceResolver frozen,
      UiLayoutMetadataCapturePersistence captures,
      ObjectMapper mapper,
      UiLayoutValidationBudgetPolicy budgets) {
    this.host = host;
    this.store = store;
    this.codec = codec;
    this.hashes = hashes;
    this.nativeSource = nativeSource;
    this.metadata = metadata;
    this.structure = new UiLayoutBoundedStructureValidator(structure);
    this.frozen = frozen;
    this.captures = captures;
    this.captureCodec = new UiLayoutMetadataCaptureCodec(mapper, hashes);
    this.budgets = budgets;
  }

  PageCreationContext resolve(Principal principal, String version) {
    if (principal == null) throw failure(UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    if (version == null || version.isBlank())
      throw failure(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    try {
      var currentContext = host.resolve(principal, version);
      if (currentContext == null || !version.equals(currentContext.invocation().contextVersion()))
        throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      return currentContext;
    } catch (UiLayoutLifecycleException e) {
      throw failure(e.getCode());
    } catch (RuntimeException e) {
      throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
  }

  void require(
      PageCreationOperation operation, PageCreationContext context, PageSourceReference source) {
    try {
      host.require(operation, context, source);
    } catch (RuntimeException e) {
      throw UiLayoutMetadataAdmissionFailure.sanitized(e, "Page authority is unavailable.");
    }
  }

  void requireCreation(
      Principal principal,
      String version,
      PageCreationContext expected,
      PageSourceReference source) {
    try {
      var current = host.requireCreation(principal, version, expected, source);
      if (!expected.equals(current)) throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    } catch (RuntimeException e) {
      throw UiLayoutMetadataAdmissionFailure.sanitized(
          e, "Page creation authority is unavailable.");
    }
  }

  UiLayoutLifecycleInvocationProvider invocations(UiLayoutLifecycleInvocationProvider delegate) {
    return (principal, version, root) -> {
      if (!isPage(root)) return delegate.resolve(principal, version, root);
      var context = resolve(principal, version);
      var record = store.byPage(context, UUID.fromString(root.componentId()));
      if (record.isEmpty()) {
        if (store.knownPage(UUID.fromString(root.componentId()))) {
          throw failure(UiLayoutLifecycleException.Code.NOT_FOUND);
        }
        try {
          return delegate.resolve(principal, version, root);
        } catch (UiLayoutLifecycleException denied) {
          if (denied.getCode() == UiLayoutLifecycleException.Code.DENIED
              || denied.getCode() == UiLayoutLifecycleException.Code.NOT_FOUND) {
            throw failure(UiLayoutLifecycleException.Code.NOT_FOUND);
          }
          throw denied;
        }
      }
      sameNamespace(context, record.orElseThrow());
      return context.forTarget(root);
    };
  }

  UiLayoutLifecycleAdmission admission(UiLayoutLifecycleAdmission delegate) {
    if (!(delegate instanceof PageLifecycleAdmission pages))
      throw new IllegalArgumentException(
          "Page composition requires the configured PageLifecycleAdmission bean.");
    return (operation, invocation) -> {
      if (!isPage(invocation.rootTarget())) {
        delegate.require(operation, invocation);
        return;
      }
      var admitted = admittedPageContext(pages, operation, invocation);
      var record = registered(invocation, admitted);
      if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()
          && record.isPresent()
          && switch (operation) {
            case READ_DRAFT, READ_REVIEW, READ_HEAD, READ_HISTORICAL_EVIDENCE, DIAGNOSE_EVOLUTION ->
                false;
            default -> true;
          }) {
        var creation = record.orElseThrow();
        if (verifyWithOutcome(verificationContext(invocation, creation, admitted), creation)) {
          sameNamespace(admittedPageContext(pages, operation, invocation), creation);
        }
      }
    };
  }

  UiLayoutDraftWorkspaceSource workspaceSource() {
    return new UiLayoutDraftWorkspaceSource() {
      public UiLayoutDraftWorkspaceSeed capture(
          UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
        if (!isPage(invocation.rootTarget())) return nativeSource.capture(invocation, validation);
        var current = currentOrVerifiedContext(invocation, validation.attempt());
        var record = registered(invocation, current);
        if (record.isEmpty()) return nativeSource.capture(invocation, validation);
        var value = record.orElseThrow();
        verify(verificationContext(invocation, value, current), value, validation.attempt());
        return new UiLayoutDraftWorkspaceSeed(List.of(outputSeed(value, invocation)));
      }

      public UiLayoutPageChildSeed capturePageChild(
          UiLayoutLifecycleInvocation invocation,
          UiLayoutTarget page,
          UiLayoutPageChildSource request,
          UiLayoutValidationContext validation) {
        // New children continue through the existing host exact-source admission.
        return nativeSource.capturePageChild(invocation, page, request, validation);
      }

      public void requirePageChildEvidence(
          UiLayoutLifecycleInvocation invocation,
          UiLayoutTarget page,
          UiLayoutPageChildSeed seed,
          UiLayoutValidationContext validation) {
        var current =
            isPage(invocation.rootTarget())
                ? currentOrVerifiedContext(invocation, validation.attempt())
                : null;
        var record =
            current == null
                ? Optional.<PageCreationEvidence>empty()
                : registered(invocation, current);
        if (isGenerated(seed.metadata())) {
          var value =
              record.orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
          if (!page.equals(value.target()))
            throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
          var expected =
              outputSeed(value).pageChildren().stream()
                  .filter(child -> child.request().equals(seed.request()))
                  .findFirst()
                  .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
          if (!sameSeedOrigin(expected, seed))
            throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
          verify(verificationContext(invocation, value, current), value, validation.attempt());
        } else nativeSource.requirePageChildEvidence(invocation, page, seed, validation);
      }
    };
  }

  UiLayoutBaselineMetadataAdmission metadataAdmission() {
    return (invocation, target, descriptor, sourceRef, baseline, seed) -> {
      var current =
          isPage(invocation.rootTarget()) ? currentOrScopedVerifiedContext(invocation) : null;
      var record =
          current == null
              ? Optional.<PageCreationEvidence>empty()
              : registered(invocation, current);
      if (isGenerated(seed)) {
        var value =
            record.orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
        var output = outputSeed(value);
        boolean match =
            target.equals(output.target())
                && descriptor.equals(output.authoring())
                && sourceRef.equals(output.sourceRef())
                && same(baseline, output.document())
                && sameMetadataOrigin(seed, output.metadata());
        if (!match)
          for (var child : output.pageChildren()) {
            if (target
                    .componentType()
                    .equals(
                        child.authoring().documentType().equals("praxis.dynamic-form.editor")
                            ? "praxis-dynamic-form"
                            : "praxis-table")
                && target.componentId().equals(child.request().widgetKey())
                && descriptor.equals(child.authoring())
                && sourceRef.equals(child.sourceRef())
                && same(baseline, child.document())
                && sameMetadataOrigin(seed, child.metadata())) {
              match = true;
              break;
            }
          }
        if (!match) throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
        verify(verificationContext(invocation, value, current), value);
      } else metadata.require(invocation, target, descriptor, sourceRef, baseline, seed);
    };
  }

  PageCreationEvidence.SourceSnapshot capture(
      Principal principal,
      String version,
      PageCreationContext context,
      PageSourceReference source,
      UiLayoutValidationAttempt outer) {
    var chain = new Chain(outer);
    chain.check();
    // Fresh joint authority after creation-key lookup, before any source I/O or extension.
    // Preserve the caller credential; expected context is correlation, never authority.
    requireCreation(principal, version, context, source);
    chain.check();
    PageCreationEvidence.SourceSnapshot snapshot;
    if (source instanceof PageSourceReference.Native origin) {
      UiLayoutDraftWorkspaceSeed.TargetSeed seed;
      try {
        seed = host.captureNative(context, origin);
      } catch (RuntimeException e) {
        throw UiLayoutMetadataAdmissionFailure.sanitized(e, "Native page source is unavailable.");
      }
      if (seed == null) throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      snapshot = new PageCreationEvidence.SourceSnapshot(seed, seed.document(), null);
      verifySource(context, source, snapshot, chain, false);
    } else if (source instanceof PageSourceReference.Published published) {
      snapshot = published(context, published, chain);
      verifySource(context, source, snapshot, chain, true);
    } else throw failure(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    chain.check();
    outer.markVerified(
        sourceKey(context, source, snapshot, outer.inBeforeCommit()),
        new VerifiedLineage(chain.maxDepth, chain.bytes, Set.copyOf(chain.verified), context));
    return snapshot;
  }

  private record PublishedKey(
      String application,
      String tenant,
      String environment,
      String namespace,
      UUID page,
      UUID release,
      UUID revision) {}

  private record ContextCorrelationKey(
      String applicationId,
      String routeNamespace,
      String actorRef,
      String tenantId,
      String administrativeUnit,
      String environment,
      String contextVersion) {
    static ContextCorrelationKey of(PageCreationContext context) {
      var inv = context.invocation();
      return new ContextCorrelationKey(
          context.applicationId(),
          context.routeNamespace(),
          inv.actorRef(),
          inv.tenantId(),
          inv.administrativeUnit(),
          inv.environment(),
          inv.contextVersion());
    }
  }

  private record VerifiedSourceKey(
      ContextCorrelationKey context,
      PageSourceReference source,
      String baselineHash,
      String documentHash,
      UUID sourceDraftId,
      boolean beforeCommit) {}

  private record VerifiedEvidenceKey(
      ContextCorrelationKey context,
      UUID pageId,
      String sourceHash,
      String outputHash,
      boolean beforeCommit) {}

  private record VerifiedPageContextKey(
      UUID pageId,
      String actorRef,
      String tenantId,
      String administrativeUnit,
      String environment,
      String contextVersion,
      boolean beforeCommit) {
    static VerifiedPageContextKey of(
        UUID pageId, UiLayoutLifecycleInvocation invocation, boolean beforeCommit) {
      return new VerifiedPageContextKey(
          pageId,
          invocation.actorRef(),
          invocation.tenantId(),
          invocation.administrativeUnit(),
          invocation.environment(),
          invocation.contextVersion(),
          beforeCommit);
    }
  }

  private record VerifiedLineage(
      int depth, long bytes, Set<UUID> lineageIds, PageCreationContext context) {}

  private VerifiedSourceKey sourceKey(
      PageCreationContext context,
      PageSourceReference source,
      PageCreationEvidence.SourceSnapshot snapshot,
      boolean beforeCommit) {
    return new VerifiedSourceKey(
        ContextCorrelationKey.of(context),
        source,
        hashes.sha256Exact(codec.tree(snapshot.baseline())),
        hashes.sha256Exact(snapshot.document()),
        snapshot.sourceDraftId(),
        beforeCommit);
  }

  private VerifiedEvidenceKey evidenceKey(
      PageCreationContext context, PageCreationEvidence value, boolean beforeCommit) {
    return new VerifiedEvidenceKey(
        ContextCorrelationKey.of(context),
        value.pageId(),
        value.sourceHash(),
        value.outputHash(),
        beforeCommit);
  }

  private PageCreationEvidence.SourceSnapshot published(
      PageCreationContext context, PageSourceReference.Published source, Chain chain) {
    chain.check();
    var key =
        new PublishedKey(
            context.applicationId(),
            context.invocation().tenantId(),
            context.invocation().environment(),
            context.routeNamespace(),
            source.pageId(),
            source.releaseId(),
            source.revisionId());
    var snapshot =
        codec.snapshot(
            store
                .immutableRead(
                    key,
                    () -> Optional.of(codec.write(loadPublished(context, source, chain))))
                .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE)));
    // This callback is current policy, so it executes even when immutable bytes were reused.
    var sourceInvocation = context.forTarget(snapshot.baseline().target());
    var validation =
        chain.nested(sourceInvocation, UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE);
    try (var attempt = validation.attempt()) {
      validation.run(
          sourceInvocation,
          () ->
              structure.validateTarget(sourceInvocation, snapshot.baseline().target(), validation));
    }
    chain.check();
    return snapshot;
  }

  private PageCreationEvidence.SourceSnapshot loadPublished(
      PageCreationContext context, PageSourceReference.Published source, Chain chain) {
    chain.check();
    var catalog =
        store
            .byPage(context, source.pageId())
            .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND));
    sameNamespace(context, catalog);
    var invocation = context.forTarget(catalog.target());
    if (!store.wasPublished(invocation, source.releaseId()))
      throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    var validation = chain.nested(invocation, UiLayoutLifecycleOperation.READ_HISTORICAL_EVIDENCE);
    try (var attempt = validation.attempt()) {
      var value =
          frozen.resolve(invocation, source.releaseId(), target -> validation.require(invocation));
      if (value.document().targets().size() != 1)
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      var state = value.document().targets().getFirst();
      if (!source.revisionId().equals(state.selectedRevision().revisionRef()))
        throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
      var binding =
          new UiLayoutMetadataCapture.Binding(
              value.draftId(),
              new UiLayoutMetadataCapture.Scope(
                  invocation.tenantId(),
                  invocation.environment(),
                  invocation.rootTarget(),
                  state.target()),
              state.baseline().sourceRef(),
              state.authoring(),
              state.baseline().contentHash());
      // Storage verifies immutable historical correlation; authority/reproduction is checked below
      // with the entire chain.
      var capture =
          captures.readVerified(invocation, binding, (observedInvocation, historicalCapture) -> {});
      if (value.patches().size() != 1) {
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      }
      UiLayoutPageRevisionReproduction.require(
          state.target(),
          state.authoring(),
          capture.baselineDocument(),
          state.working().document(),
          state.patch(),
          value.patches().getFirst());
      // The capture preserves exact input property order; the frozen JSONB workspace does not.
      // Its binding/hash was already correlated to this frozen baseline by the capture store.
      var rootMetadata =
          new UiLayoutBaselineMetadataSeed(
              capture.source(),
              capture.reproduction(),
              capture.observation(),
              capture.rawInputText());
      var keys = UiLayoutPageRevisionReproduction.children(state.working().document()).keySet();
      var children =
          state.pageChildren().stream()
              .filter(child -> keys.contains(child.request().widgetKey()))
              .map(UiLayoutPageChildCaptures::capturedSeed)
              .toList();
      return new PageCreationEvidence.SourceSnapshot(
          new UiLayoutDraftWorkspaceSeed.TargetSeed(
              state.target(),
              state.authoring(),
              state.patch(),
              state.baseline().sourceRef(),
              capture.baselineDocument(),
              rootMetadata,
              children),
          state.working().document(),
          value.draftId());
    }
  }

  void verify(PageCreationContext context, PageCreationEvidence value) {
    verifyWithOutcome(context, value);
  }

  private boolean verifyWithOutcome(PageCreationContext context, PageCreationEvidence value) {
    try (var attempt = UiLayoutValidationScope.borrow(context.invocation())) {
      return verifyWithOutcome(context, value, attempt);
    }
  }

  void verify(
      PageCreationContext context, PageCreationEvidence value, UiLayoutValidationAttempt outer) {
    verifyWithOutcome(context, value, outer);
  }

  private boolean verifyWithOutcome(
      PageCreationContext context, PageCreationEvidence value, UiLayoutValidationAttempt outer) {
    var chain = new Chain(outer);
    boolean executed = verify(context, value, chain);
    chain.check();
    return executed;
  }

  private boolean verify(PageCreationContext context, PageCreationEvidence value, Chain chain) {
    chain.check();
    sameNamespace(context, value);
    if (chain.verified.contains(value.pageId())) return false;
    var evKey = evidenceKey(context, value, chain.outer.inBeforeCommit());
    VerifiedLineage cached = chain.outer.verified(evKey);
    if (cached != null) {
      if (!Collections.disjoint(chain.path, cached.lineageIds())
          || chain.path.size() + cached.depth() > MAX_CHAIN
          || chain.bytes + cached.bytes() > MAX_CHAIN_BYTES) {
        throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
      }
      chain.maxDepth = Math.max(chain.maxDepth, chain.path.size() + cached.depth());
      chain.bytes += cached.bytes();
      chain.verified.addAll(cached.lineageIds());
      return false;
    }
    if (chain.path.size() >= MAX_CHAIN || !chain.path.add(value.pageId()))
      throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    chain.maxDepth = Math.max(chain.maxDepth, chain.path.size());
    long bytesBefore = chain.bytes;
    int depthBefore = chain.path.size() - 1;
    Set<UUID> verifiedBefore = new HashSet<>(chain.verified);
    chain.bytes += codec.write(value).getBytes(StandardCharsets.UTF_8).length;
    if (chain.bytes > MAX_CHAIN_BYTES)
      throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    boolean executedExtensions = true;
    try {
      if (!PageCopyTransformation.TRANSFORM.equals(value.transformRef()))
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      var result = output(value);
      if (!result.sourceHash().equals(value.sourceHash())
          || !result.outputHash().equals(value.outputHash()))
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      var srcKey =
          sourceKey(
              context, value.command().source(), value.original(), chain.outer.inBeforeCommit());
      VerifiedLineage cachedSource = chain.outer.verified(srcKey);
      if (cachedSource != null) {
        if (!Collections.disjoint(chain.path, cachedSource.lineageIds())
            || chain.path.size() + cachedSource.depth() > MAX_CHAIN
            || chain.bytes + cachedSource.bytes() > MAX_CHAIN_BYTES) {
          throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
        }
        chain.maxDepth = Math.max(chain.maxDepth, chain.path.size() + cachedSource.depth());
        chain.bytes += cachedSource.bytes();
        chain.verified.addAll(cachedSource.lineageIds());
        executedExtensions = false;
      } else {
        verifySource(context, value.command().source(), value.original(), chain, false);
      }
      chain.verified.add(value.pageId());
      int subtreeDepth = Math.max(1, chain.maxDepth - depthBefore);
      long subtreeBytes = chain.bytes - bytesBefore;
      Set<UUID> subtreeIds = new HashSet<>(chain.verified);
      subtreeIds.removeAll(verifiedBefore);
      subtreeIds.add(value.pageId());
      var pageContext =
          new PageCreationContext(
              context.applicationId(), context.routeNamespace(), context.forTarget(value.target()));
      var lineage =
          new VerifiedLineage(subtreeDepth, subtreeBytes, Set.copyOf(subtreeIds), pageContext);
      chain.outer.markVerified(evKey, lineage);
      chain.outer.markVerified(
          VerifiedPageContextKey.of(
              value.pageId(), pageContext.invocation(), chain.outer.inBeforeCommit()),
          lineage);
      return executedExtensions;
    } finally {
      chain.path.remove(value.pageId());
      chain.check();
    }
  }

  private void verifySource(
      PageCreationContext context,
      PageSourceReference source,
      PageCreationEvidence.SourceSnapshot snapshot,
      Chain chain,
      boolean publishedSnapshotAlreadyLoaded) {
    chain.check();

    var seed = snapshot.baseline();
    if (!isPage(seed.target())) throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    var invocation = context.forTarget(seed.target());
    boolean jointNative = whollyNative(source, snapshot);
    if (source instanceof PageSourceReference.Native origin) {
      if (snapshot.sourceDraftId() != null || !same(seed.document(), snapshot.document()))
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      try {
        chain.check();
        if (jointNative) requireNativeProvenance(context, origin, seed);
        else host.requireNative(context, origin, seed);
        chain.check();
      } catch (RuntimeException e) {
        throw UiLayoutMetadataAdmissionFailure.sanitized(
            e, "Native page source admission is unavailable.");
      }
    } else if (source instanceof PageSourceReference.Published origin) {
      if (!publishedSnapshotAlreadyLoaded) {
        var actual = published(context, origin, chain);
        if (!exact(actual, snapshot)) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      }
      var sourceCreation =
          store
              .byPage(context, origin.pageId())
              .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.NOT_FOUND));
      verify(context, sourceCreation, chain);
    } else throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
    UiLayoutPageRevisionReproduction.requireNative(
        seed.target(), seed.authoring(), seed.document(), snapshot.document(), seed.patch());
    captureCodec.requireSeedSyntax(seed.metadata(), seed.document());
    var validation = chain.nested(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT);
    try (var attempt = validation.attempt()) {
      structure.validateTarget(invocation, seed.target(), validation);
      structure.validateAuthoringBaseline(
          invocation, seed.target(), seed.authoring(), seed.document(), validation);
      var projection =
          UiLayoutAuthoringProjection.project(
              seed.target(),
              seed.authoring(),
              seed.document(),
              snapshot.document(),
              seed.patch(),
              () -> validation.require(invocation));
      structure.validateAuthoringRevision(
          invocation,
          seed.target(),
          seed.authoring(),
          seed.document(),
          seed.authoring(),
          snapshot.document(),
          seed.patch(),
          projection.compilePatch(),
          validation);
      if (!jointNative)
        validation.run(
            invocation,
            () ->
                requireOriginalMetadata(
                    context,
                    invocation,
                    seed.target(),
                    seed.authoring(),
                    seed.sourceRef(),
                    seed.document(),
                    seed.metadata(),
                    chain));
      var children = UiLayoutPageRevisionReproduction.children(snapshot.document());
      var keys = new HashSet<String>();
      for (var child : seed.pageChildren()) {
        String key = child.request().widgetKey();
        if (!keys.add(key) || !children.containsKey(key))
          throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        UiLayoutPageRevisionReproduction.requireChild(child.document(), children.get(key), key);
        captureCodec.requireSeedSyntax(child.metadata(), child.document());
        if (isGenerated(child.metadata())) {
          var record =
              store
                  .byPage(context, UUID.fromString(seed.target().componentId()))
                  .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
          var expected =
              outputSeed(record).pageChildren().stream()
                  .filter(c -> c.request().equals(child.request()))
                  .findFirst()
                  .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
          if (!sameSeedOrigin(expected, child))
            throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
          // The source page creation was already traversed above for a published source.
          if (!(source instanceof PageSourceReference.Published)) verify(context, record, chain);
        } else if (!jointNative) {
          validation.run(
              invocation,
              () ->
                  nativeSource.requirePageChildEvidence(
                      invocation, seed.target(), child, validation));
        }
      }
      if (!keys.equals(children.keySet()))
        throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      if (jointNative) {
        try {
          validation.run(
              invocation,
              () -> requireNativeProvenance(context, (PageSourceReference.Native) source, seed));
        } catch (RuntimeException e) {
          throw UiLayoutMetadataAdmissionFailure.sanitized(
              e, "Native page provenance admission is unavailable.");
        }
      }
    }
  }

  private static boolean whollyNative(
      PageSourceReference source, PageCreationEvidence.SourceSnapshot snapshot) {
    return source instanceof PageSourceReference.Native
        && !isGenerated(snapshot.baseline().metadata())
        && snapshot.baseline().pageChildren().stream()
            .noneMatch(child -> isGenerated(child.metadata()));
  }

  private void requireNativeProvenance(
      PageCreationContext expected,
      PageSourceReference.Native source,
      UiLayoutDraftWorkspaceSeed.TargetSeed seed) {
    var current = host.requireNativeProvenance(expected, source, seed);
    if (!expected.equals(current)) throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }

  private void requireOriginalMetadata(
      PageCreationContext context,
      UiLayoutLifecycleInvocation invocation,
      UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor descriptor,
      String sourceRef,
      JsonNode document,
      UiLayoutBaselineMetadataSeed seed,
      Chain chain) {
    if (!isGenerated(seed)) {
      metadata.require(invocation, target, descriptor, sourceRef, document, seed);
      return;
    }
    var record =
        store
            .byPage(context, UUID.fromString(target.componentId()))
            .orElseThrow(() -> failure(UiLayoutLifecycleException.Code.INVALID_STATE));
    var expected = outputSeed(record);
    if (!descriptor.equals(expected.authoring())
        || !sourceRef.equals(expected.sourceRef())
        || !same(document, expected.document())
        || !sameMetadataOrigin(seed, expected.metadata()))
      throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
    // Existing source traversal owns admission; this check correlates the native B0 to its actual
    // producer.
    if (!chain.verified.contains(record.pageId())) {
      verify(context, record, chain);
      chain.verified.add(record.pageId());
    }
  }

  private PageCopyTransformation.Result output(PageCreationEvidence value) {
    return new PageCopyTransformation(hashes)
        .reproduce(
            value.original().document(),
            value.original().baseline().target().componentId(),
            value.pageId().toString(),
            value.command().title(),
            value.identityMap());
  }

  UiLayoutDraftWorkspaceSeed.TargetSeed outputSeed(PageCreationEvidence value) {
    return outputSeed(value, null);
  }

  UiLayoutDraftWorkspaceSeed.TargetSeed outputSeed(
      PageCreationEvidence value, UiLayoutLifecycleInvocation current) {
    var document = output(value).document();
    var children = new ArrayList<UiLayoutPageChildSeed>();
    UiLayoutPageRevisionReproduction.children(document)
        .forEach(
            (key, child) -> {
              String ref = value.sourceRef() + ":widget:" + key;
              children.add(
                  new UiLayoutPageChildSeed(
                      new UiLayoutPageChildSource(
                          key,
                          new UiLayoutPageChildSource.NativeSource(
                              "registered-native", ref, value.outputHash())),
                      new UiLayoutAuthoringDocumentDescriptor(
                          child.path("kind").asText(), "praxis:page-copy:child", "1"),
                      PATCH,
                      ref,
                      child,
                      generatedMetadata(value, ref, child, current)));
            });
    return new UiLayoutDraftWorkspaceSeed.TargetSeed(
        value.target(),
        new UiLayoutAuthoringDocumentDescriptor(
            UiLayoutPageRevisionReproduction.KIND, "praxis:page-copy:page", "1"),
        PATCH,
        value.sourceRef(),
        document,
        generatedMetadata(value, value.sourceRef(), document, current),
        children);
  }

  private UiLayoutBaselineMetadataSeed generatedMetadata(
      PageCreationEvidence value,
      String ref,
      JsonNode document,
      UiLayoutLifecycleInvocation current) {
    var invocation = current == null ? value.observation().invocation() : current;
    return new UiLayoutBaselineMetadataSeed(
        new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
            PRODUCER_SERVICE,
            ref,
            value.outputHash(),
            value.transformRef(),
            value.command().creationKey().toString()),
        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
        new UiLayoutBaselineMetadataSeed.Observation(
            invocation.actorRef(),
            invocation.administrativeUnit(),
            invocation.contextVersion(),
            "praxis:page-copy-admission",
            "1",
            current == null ? value.createdAt() : java.time.Instant.now()),
        UiLayoutRevisionJsonInput.writeDocument(document, () -> {}));
  }

  private Optional<PageCreationEvidence> registered(
      UiLayoutLifecycleInvocation invocation, PageCreationContext current) {
    var result = store.byPage(current, UUID.fromString(invocation.rootTarget().componentId()));
    if (result.isEmpty() && store.knownPage(UUID.fromString(invocation.rootTarget().componentId())))
      throw failure(UiLayoutLifecycleException.Code.NOT_FOUND);
    result.ifPresent(value -> sameNamespace(current, value));
    return result;
  }

  private PageCreationContext verificationContext(
      UiLayoutLifecycleInvocation invocation,
      PageCreationEvidence value,
      PageCreationContext current) {
    if (whollyNative(value.command().source(), value.original())) {
      // Comparison values only. verifySource must freshly attest FULL lifecycle registration
      // together with provenance before any extension or I/O, and again after extensions.
      return new PageCreationContext(
          value.observation().applicationId(), value.observation().routeNamespace(), invocation);
    }
    sameNamespace(current, value);
    return current;
  }

  private PageCreationContext admittedPageContext(
      PageLifecycleAdmission admission,
      UiLayoutLifecycleOperation operation,
      UiLayoutLifecycleInvocation invocation) {
    try {
      var current = admission.requirePage(operation, invocation);
      if (current == null || !invocation.equals(current.invocation()))
        throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      return current;
    } catch (RuntimeException error) {
      throw UiLayoutMetadataAdmissionFailure.sanitized(
          error, "Page lifecycle authority is unavailable.");
    }
  }

  private PageCreationContext currentOrVerifiedContext(
      UiLayoutLifecycleInvocation invocation, UiLayoutValidationAttempt attempt) {
    UUID pageId = UUID.fromString(invocation.rootTarget().componentId());
    VerifiedLineage cached =
        attempt.verified(VerifiedPageContextKey.of(pageId, invocation, attempt.inBeforeCommit()));
    if (cached != null && invocation.equals(cached.context().invocation())) {
      return cached.context();
    }
    return currentContext(invocation);
  }

  private PageCreationContext currentOrScopedVerifiedContext(
      UiLayoutLifecycleInvocation invocation) {
    try (var attempt = UiLayoutValidationScope.borrow(invocation)) {
      return currentOrVerifiedContext(invocation, attempt);
    } catch (UiLayoutLifecycleException ignored) {
      return currentContext(invocation);
    }
  }

  private PageCreationContext currentContext(UiLayoutLifecycleInvocation invocation) {
    try {
      var current = host.resolveLifecycle(invocation);
      if (current == null || !invocation.equals(current.invocation()))
        throw failure(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      return current;
    } catch (UiLayoutLifecycleException e) {
      throw failure(e.getCode());
    } catch (RuntimeException e) {
      throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
  }

  private void sameNamespace(PageCreationContext context, PageCreationEvidence value) {
    var original = value.observation();
    if (!context.applicationId().equals(original.applicationId())
        || !context.routeNamespace().equals(original.routeNamespace())
        || !context.invocation().tenantId().equals(original.invocation().tenantId())
        || !context.invocation().environment().equals(original.invocation().environment()))
      throw failure(UiLayoutLifecycleException.Code.DENIED);
  }

  private boolean sameMetadataOrigin(
      UiLayoutBaselineMetadataSeed expected, UiLayoutBaselineMetadataSeed actual) {
    return expected.source().equals(actual.source())
        && expected.reproduction().equals(actual.reproduction())
        && expected.rawInputText().equals(actual.rawInputText())
        && "praxis:page-copy-admission".equals(actual.observation().policyRef())
        && "1".equals(actual.observation().policyRevision());
  }

  private boolean sameSeedOrigin(UiLayoutPageChildSeed a, UiLayoutPageChildSeed b) {
    return a.request().equals(b.request())
        && a.authoring().equals(b.authoring())
        && a.patch().equals(b.patch())
        && a.sourceRef().equals(b.sourceRef())
        && same(a.document(), b.document())
        && sameMetadataOrigin(a.metadata(), b.metadata());
  }

  private boolean exact(Object a, Object b) {
    return hashes.sha256Exact(codec.tree(a)).equals(hashes.sha256Exact(codec.tree(b)));
  }

  private boolean same(JsonNode a, JsonNode b) {
    return hashes.sha256Exact(a).equals(hashes.sha256Exact(b));
  }

  private static boolean isGenerated(UiLayoutBaselineMetadataSeed seed) {
    return seed.source() instanceof UiLayoutBaselineMetadataSeed.NativeDocumentSource source
        && PRODUCER_SERVICE.equals(source.serviceKey());
  }

  private static boolean isPage(UiLayoutTarget root) {
    return root != null
        && UiLayoutPageRevisionReproduction.TYPE.equals(root.componentType())
        && root.componentId().matches(UiLayoutPageRevisionReproduction.UUID_PATTERN);
  }

  private static UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Page source or authority is unavailable.");
  }

  private static final class Chain {
    final Set<UUID> path = new HashSet<>();
    final Set<UUID> verified = new HashSet<>();
    int maxDepth;
    long bytes;
    private final UiLayoutValidationAttempt outer;
    private int checkpoints;

    Chain(UiLayoutValidationAttempt outer) {
      this.outer = outer;
    }

    void check() {
      outer.requireActive();
      if (++checkpoints > 2048) throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }

    UiLayoutValidationContext nested(
        UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation) {
      check();
      return new UiLayoutValidationContext(
          outer.correlate(invocation), UiLayoutValidationPurpose.AUTHORING);
    }
  }
}
