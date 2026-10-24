package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;

@Tag("unit")
class PageCatalogLifecycleBridgeTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final PageCreationCodec codec = new PageCreationCodec();
  private final PageCatalogStore store =
      mock(PageCatalogStore.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  private final UiLayoutFrozenWorkspaceResolver frozen =
      mock(
          UiLayoutFrozenWorkspaceResolver.class,
          withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  private final UiLayoutMetadataCapturePersistence captures =
      mock(UiLayoutMetadataCapturePersistence.class);
  private final PageCreationHost host = mock(PageCreationHost.class);
  private final UiLayoutDraftWorkspaceSource nativeSource =
      mock(UiLayoutDraftWorkspaceSource.class);
  private final UiLayoutBaselineMetadataAdmission metadata =
      mock(UiLayoutBaselineMetadataAdmission.class);
  private final Map<UUID, PageCreationEvidence> records = new HashMap<>();
  private final Map<UUID, UiLayoutFrozenWorkspaceResolver.Snapshot> snapshots = new HashMap<>();
  private final Map<UUID, UiLayoutMetadataCapture> captured = new HashMap<>();
  private final PageCreationContext context =
      new PageCreationContext(
          "app",
          "/pages",
          new UiLayoutLifecycleInvocation(
              "alice",
              "tenant",
              "unit",
              "test",
              "c1",
              new UiLayoutCompositionRegistration(
                  UiLayoutPageRevisionReproductionTest.PAGE,
                  List.of(UiLayoutPageRevisionReproductionTest.PAGE))));
  private PageCatalogLifecycleBridge bridge;

  @BeforeEach
  void setup() {
    when(host.resolveLifecycle(any()))
        .thenAnswer(call -> new PageCreationContext("app", "/pages", call.getArgument(0)));
    when(store.byPage(any(), any()))
        .thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(1))));
    when(store.knownPage(any())).thenAnswer(call -> records.containsKey(call.getArgument(0)));
    when(store.immutableRead(any(), any()))
        .thenAnswer(
            call -> ((java.util.function.Supplier<Optional<String>>) call.getArgument(1)).get());
    when(store.wasPublished(any(), any()))
        .thenAnswer(call -> snapshots.containsKey(call.getArgument(1)));
    when(frozen.resolve(any(), any(), any()))
        .thenAnswer(call -> snapshots.get(call.getArgument(1)));
    when(captures.read(any(), any(), any()))
        .thenAnswer(
            call ->
                captured.get(
                    ((UiLayoutMetadataCapture.Binding) call.getArgument(1)).sourceDraftRef()));
    bridge =
        new PageCatalogLifecycleBridge(
            host,
            store,
            codec,
            hashes,
            nativeSource,
            metadata,
            mock(UiLayoutLifecycleStructureValidator.class),
            frozen,
            captures,
            mapper,
            (operation, invocation) -> Duration.ofSeconds(30));
  }

  private PageCreationEvidence nativeCreation() throws Exception {
    return nativeCreation(false);
  }

  private PageCreationEvidence nativeCreation(boolean large) throws Exception {
    var document = UiLayoutPageRevisionReproductionTest.page();
    if (large)
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              document.at("/config/definition/widgets/0/definition/inputs/config"))
          .put("title", "x".repeat(140_000));
    var children = new ArrayList<UiLayoutPageChildSeed>();
    UiLayoutPageRevisionReproduction.children(document)
        .forEach(
            (key, child) ->
                children.add(
                    new UiLayoutPageChildSeed(
                        new UiLayoutPageChildSource(
                            key,
                            new UiLayoutPageChildSource.NativeSource(
                                "registered-native", "template:" + key, "r1")),
                        new UiLayoutAuthoringDocumentDescriptor(
                            child.path("kind").asText(), "fixture:child", "1"),
                        UiLayoutPageRevisionReproductionTest.PATCH,
                        "template:" + key,
                        child,
                        UiLayoutMetadataTestFixtures.nativeMetadata(
                            child, "alice", "unit", "c1"))));
    var seed =
        new UiLayoutDraftWorkspaceSeed.TargetSeed(
            UiLayoutPageRevisionReproductionTest.PAGE,
            UiLayoutPageRevisionReproductionTest.DESCRIPTOR,
            UiLayoutPageRevisionReproductionTest.PATCH,
            "template:root",
            document,
            UiLayoutMetadataTestFixtures.nativeMetadata(document, "alice", "unit", "c1"),
            children);
    return creation(
        new PageSourceReference.Native("template:root", "r1"),
        new PageCreationEvidence.SourceSnapshot(seed, document, null),
        UUID.randomUUID(),
        null);
  }

  private PageCreationEvidence creation(
      PageSourceReference source,
      PageCreationEvidence.SourceSnapshot snapshot,
      UUID pageId,
      List<PageCopyTransformation.Identity> mapping) {
    var transformation = new PageCopyTransformation(hashes);
    var output =
        mapping == null
            ? transformation.allocate(
                snapshot.document(),
                UUID.fromString(snapshot.baseline().target().componentId()),
                pageId,
                "Copy")
            : transformation.reproduce(
                snapshot.document(),
                snapshot.baseline().target().componentId(),
                pageId.toString(),
                "Copy",
                mapping);
    return new PageCreationEvidence(
        pageId,
        UUID.randomUUID(),
        context,
        new PageCreationCommand(UUID.randomUUID(), source, "Copy"),
        snapshot,
        output.sourceHash(),
        output.outputHash(),
        PageCopyTransformation.TRANSFORM,
        output.identityMap(),
        Instant.EPOCH);
  }

  private Published publish(PageCreationEvidence creation) {
    records.put(creation.pageId(), creation);
    var seed = bridge.outputSeed(creation);
    var persistedPatch =
        UiLayoutAuthoringProjection.project(
                seed.target(),
                seed.authoring(),
                seed.document(),
                seed.document(),
                seed.patch(),
                () -> {})
            .compilePatch();
    var invocation = context.forTarget(seed.target());
    var validation =
        UiLayoutValidationContext.begin(
            (op, inv) -> Duration.ofSeconds(30),
            invocation,
            UiLayoutLifecycleOperation.CREATE_DRAFT,
            UiLayoutValidationPurpose.AUTHORING);
    var children =
        new UiLayoutPageChildCaptures(hashes)
            .initial(invocation, creation.draftId(), seed, nativeSource, metadata, validation);
    var binding =
        new UiLayoutMetadataCapture.Binding(
            creation.draftId(),
            new UiLayoutMetadataCapture.Scope(
                invocation.tenantId(), invocation.environment(), seed.target(), seed.target()),
            seed.sourceRef(),
            seed.authoring(),
            hashes.sha256Exact(seed.document()));
    var origin = seed.metadata();
    captured.put(
        creation.draftId(),
        new UiLayoutMetadataCaptureCodec(mapper, hashes)
            .seal(
                UUID.randomUUID(),
                binding,
                seed.document(),
                origin.source(),
                origin.reproduction(),
                origin.observation(),
                origin.rawInputText()));
    UUID revisionId = UUID.randomUUID(), releaseId = UUID.randomUUID();
    var state =
        new UiLayoutDraftWorkspaceDocument.TargetState(
            seed.target(),
            seed.authoring(),
            seed.patch(),
            new UiLayoutDraftWorkspaceDocument.BaselineDocument(
                seed.sourceRef(), hashes.sha256Exact(seed.document()), seed.document()),
            new UiLayoutDraftWorkspaceDocument.WorkingDocument(
                hashes.sha256Exact(seed.document()), seed.document()),
            new UiLayoutDraftWorkspaceDocument.RevisionSelection(
                revisionId,
                1,
                hashes.sha256Exact(persistedPatch),
                "praxis.ui-layout/v1",
                UUID.randomUUID()),
            null,
            children);
    snapshots.put(
        releaseId,
        new UiLayoutFrozenWorkspaceResolver.Snapshot(
            releaseId,
            creation.draftId(),
            UUID.randomUUID(),
            new UiLayoutDraftWorkspaceDocument(
                UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(state), UUID.randomUUID()),
            List.of(persistedPatch)));
    return new Published(
        new PageSourceReference.Published(creation.pageId(), releaseId, revisionId),
        new PageCreationEvidence.SourceSnapshot(seed, seed.document(), creation.draftId()));
  }

  private record Published(
      PageSourceReference.Published reference, PageCreationEvidence.SourceSnapshot snapshot) {}

  /** Direct SPI tests model the lexical scope supplied by the initiating Config operation. */
  private void withOwner(UiLayoutLifecycleInvocation invocation, Runnable callback) {
    withOwner(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, callback);
  }

  private void withOwner(UiLayoutLifecycleInvocation invocation,
      UiLayoutLifecycleOperation operation, Runnable callback) {
    try (var owner = UiLayoutValidationAttempt.start(
            invocation, operation, Duration.ofSeconds(30));
        var scope = UiLayoutValidationScope.bind(owner)) {
      callback.run();
    }
  }

  private void verifyInOwner(PageCreationEvidence creation) {
    withOwner(context.invocation(), () -> bridge.verify(context, creation));
  }

  @Test
  void immutablePublishedBytesAreReusedButCurrentNativeAdmissionIsNot() throws Exception {
    var original = nativeCreation();
    var published = publish(original);
    var copy = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    var guard =
        mock(
            ConfigJdbcTransactionGuard.class,
            withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    var holder =
        new org.springframework.jdbc.datasource.ConnectionHolder(mock(java.sql.Connection.class));
    when(guard.admitImmutableReadTransaction()).thenReturn(holder);
    when(guard.requireImmutableReadTransaction(any(), any())).thenReturn(holder);
    when(guard.mayReuseImmutableReads()).thenReturn(true);
    doAnswer(
            call ->
                ConfigTransactionReadSet.read(
                    store, guard, call.getArgument(0), call.getArgument(1)))
        .when(store)
        .immutableRead(any(), any());
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      verifyInOwner(copy);
      verifyInOwner(copy);
      verify(frozen, times(1)).resolve(any(), eq(published.reference().releaseId()), any());
      doThrow(
              new UiLayoutLifecycleException(
                  UiLayoutLifecycleException.Code.DENIED, "Revoked native producer"))
          .when(host)
          .requireNative(any(), any(), any());
      assertThatThrownBy(() -> verifyInOwner(copy))
          .isInstanceOfSatisfying(
              UiLayoutLifecycleException.class,
              failure ->
                  assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
      verify(frozen, times(1)).resolve(any(), eq(published.reference().releaseId()), any());
      verify(host, times(3)).requireNative(any(), any(), any());
    } finally {
      for (var synchronization :
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations())
        synchronization.afterCompletion(
            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void publishedCandidateMustReproduceTheSelectedImmutablePatch() throws Exception {
    var original = nativeCreation();
    var published = publish(original);
    var frozenValue = snapshots.get(published.reference().releaseId());
    var state = frozenValue.document().targets().getFirst();
    var altered = state.working().document().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) altered.at("/config/presentation"))
        .put("title", "Tampered after release");
    UiLayoutPageRevisionReproduction.requireNative(
        state.target(), state.authoring(), state.baseline().document(), altered, state.patch());
    var alteredState =
        new UiLayoutDraftWorkspaceDocument.TargetState(
            state.target(),
            state.authoring(),
            state.patch(),
            state.baseline(),
            new UiLayoutDraftWorkspaceDocument.WorkingDocument(
                hashes.sha256Exact(altered), altered),
            state.selectedRevision(),
            state.selectedAssignment(),
            state.pageChildren());
    snapshots.put(
        frozenValue.releaseId(),
        new UiLayoutFrozenWorkspaceResolver.Snapshot(
            frozenValue.releaseId(),
            frozenValue.draftId(),
            frozenValue.draftEtag(),
            new UiLayoutDraftWorkspaceDocument(
                UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
                List.of(alteredState),
                frozenValue.document().freezeCommandRef()),
            frozenValue.patches()));
    var validation =
        UiLayoutValidationContext.begin(
            (op, inv) -> Duration.ofSeconds(30),
            context.invocation(),
            UiLayoutLifecycleOperation.CREATE_DRAFT,
            UiLayoutValidationPurpose.AUTHORING);
    try (var attempt = validation.attempt()) {
      assertThatThrownBy(() -> bridge.capture(context, published.reference(), attempt))
          .isInstanceOf(UiLayoutLifecycleException.class);
    }
  }

  @Test
  void prefetchedRawLedgerDefersIntegrityUntilReadHeadAllowsAndPreservesOriginalCorrelation() throws Exception {
    var creation = nativeCreation();
    var columns = mapper.createObjectNode();
    columns.put("page_id", creation.pageId().toString()).put("draft_id", creation.draftId().toString())
        .put("creation_key", creation.command().creationKey().toString()).put("application_id", "app")
        .put("route_namespace", "/pages").put("tenant_id", "tenant").put("environment", "test")
        .put("actor_ref", "alice").put("command_hash", hashes.sha256Exact(codec.tree(creation.command())))
        .put("evidence_hash", hashes.sha256Exact(codec.tree(creation))).put("evidence_text", codec.write(creation));
    var raw = new java.util.concurrent.atomic.AtomicReference<>(columns.toString());
    var connection = mock(java.sql.Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    var statement = mock(java.sql.PreparedStatement.class);
    var result = mock(java.sql.ResultSet.class);
    when(connection.prepareStatement("SELECT current_setting('transaction_isolation')")).thenReturn(statement);
    when(statement.executeQuery()).thenAnswer(call -> { when(result.next()).thenReturn(true, false); return result; });
    when(result.getString(1)).thenReturn("read committed");
    var dataSource = mock(javax.sql.DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    var transactions = new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    var jdbc = mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    when(jdbc.getJdbcTemplate()).thenReturn(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
        any(org.springframework.jdbc.core.RowMapper.class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("AS weight")) return List.of(Map.entry(0, (long) raw.get().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
      if (sql.contains("requested.ordinal")) return List.of(Map.entry(0, raw.get()));
      return List.of(raw.get());
    });
    var actualStore = new PageCatalogStore(jdbc, transactions, codec, hashes);
    var actualBridge = new PageCatalogLifecycleBridge(host, actualStore, codec, hashes,
        nativeSource, metadata, mock(UiLayoutLifecycleStructureValidator.class), frozen, captures,
        mapper, (operation, invocation) -> Duration.ofSeconds(30));
    var allowed = new java.util.concurrent.atomic.AtomicBoolean();
    var admission = actualBridge.admission((operation, invocation) -> {
      if (!allowed.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private policy");
    });
    transactions.executeWithoutResult(status -> {
      assertThat(actualStore.prefetch(context, List.of(creation.pageId()))).isEqualTo(1);
      assertThat(actualStore.byPage(context, creation.pageId())).contains(creation);
    });
    columns.put("evidence_hash", "0".repeat(64));
    raw.set(columns.toString());
    transactions.executeWithoutResult(status -> {
      assertThat(actualStore.prefetch(context, List.of(creation.pageId()))).isEqualTo(1);
      assertThatThrownBy(() -> withOwner(context.forTarget(creation.target()), UiLayoutLifecycleOperation.READ_HEAD,
              () -> admission.require(UiLayoutLifecycleOperation.READ_HEAD, context.forTarget(creation.target()))))
          .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.DENIED);
      allowed.set(true);
      assertThatThrownBy(() -> withOwner(context.forTarget(creation.target()), UiLayoutLifecycleOperation.READ_HEAD,
              () -> admission.require(UiLayoutLifecycleOperation.READ_HEAD, context.forTarget(creation.target()))))
          .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);
    });
    verifyNoInteractions(metadata, nativeSource);
  }

  @Test
  void crossScopeKnownPageAndUnknownDeniedRootHaveTheSameNonDisclosingOutcome() {
    UUID knownElsewhere = UUID.randomUUID(), unknown = UUID.randomUUID();
    when(host.resolve(any(), eq("c1"))).thenReturn(context);
    when(store.knownPage(knownElsewhere)).thenReturn(true);
    var delegate = mock(UiLayoutLifecycleInvocationProvider.class);
    when(delegate.resolve(any(), any(), any()))
        .thenThrow(
            new UiLayoutLifecycleException(
                UiLayoutLifecycleException.Code.DENIED, "Private registry"));
    var provider = bridge.invocations(delegate);
    for (UUID id : List.of(knownElsewhere, unknown)) {
      assertThatThrownBy(
              () ->
                  provider.resolve(
                      () -> "alice",
                      "c1",
                      new UiLayoutTarget("praxis-dynamic-page", id.toString())))
          .isInstanceOfSatisfying(
              UiLayoutLifecycleException.class,
              failure ->
                  assertThat(failure.getCode())
                      .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND));
    }
    verify(delegate, never())
        .resolve(
            any(), any(), eq(new UiLayoutTarget("praxis-dynamic-page", knownElsewhere.toString())));
    verify(delegate)
        .resolve(any(), any(), eq(new UiLayoutTarget("praxis-dynamic-page", unknown.toString())));
  }

  @Test
  void lastProvenanceExtensionCannotRevokeLifecycleGrantAndStillCommit() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var revoked = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            call -> {
              revoked.set(true);
              return null;
            })
        .when(metadata)
        .require(any(), any(), any(), any(), any(), any());
    var admission =
        bridge.admission(
            (op, invocation) -> {
              if (revoked.get())
                throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.DENIED, "Revoked during provenance");
            });
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  withOwner(context.forTarget(creation.target()), UiLayoutLifecycleOperation.EDIT_DRAFT, () -> admission.require(
                      UiLayoutLifecycleOperation.EDIT_DRAFT, context.forTarget(creation.target()))))
          .isInstanceOfSatisfying(
              UiLayoutLifecycleException.class,
              failure ->
                  assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
      assertThat(revoked).isTrue();
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void lifecycleContextMustBeIndependentlyCurrentBeforeAnyCatalogContentRead() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var stale =
        new UiLayoutLifecycleInvocation(
            invocation.actorRef(),
            invocation.tenantId(),
            invocation.administrativeUnit(),
            invocation.environment(),
            "old-context",
            invocation.composition());
    when(host.resolveLifecycle(invocation))
        .thenReturn(new PageCreationContext("app", "/pages", stale));
    assertThatThrownBy(
            () ->
                withOwner(invocation, UiLayoutLifecycleOperation.READ_DRAFT, () -> bridge
                    .admission((op, inv) -> {})
                    .require(UiLayoutLifecycleOperation.READ_DRAFT, invocation)))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
    verify(store, never()).byPage(any(), any());
  }

  @Test
  void forgedProducerPrefixWithoutDurableRegistrationNeverDelegatesToPermissiveHost()
      throws Exception {
    var value = nativeCreation();
    var seed = bridge.outputSeed(value);
    assertThatThrownBy(
            () ->
                withOwner(context.forTarget(value.target()), () -> bridge
                    .metadataAdmission()
                    .require(
                        context.forTarget(value.target()),
                        seed.target(),
                        seed.authoring(),
                        seed.sourceRef(),
                        seed.document(),
                        seed.metadata())))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(
            () ->
                bridge
                    .workspaceSource()
                    .requirePageChild(
                        context.forTarget(value.target()),
                        seed.target(),
                        seed.pageChildren().getFirst(),
                        UiLayoutValidationContext.begin(
                            (op, inv) -> Duration.ofSeconds(5),
                            context.forTarget(value.target()),
                            UiLayoutLifecycleOperation.CREATE_DRAFT,
                            UiLayoutValidationPurpose.AUTHORING)))
        .isInstanceOf(UiLayoutLifecycleException.class);
    verifyNoInteractions(metadata, nativeSource);
  }

  @Test
  void ledgerHashMapAndTransformTamperingAreRejectedBeforeProducerAdmission() throws Exception {
    var valid = nativeCreation();
    for (var corrupt :
        List.of(
            new PageCreationEvidence(
                valid.pageId(),
                valid.draftId(),
                context,
                valid.command(),
                valid.original(),
                "0".repeat(64),
                valid.outputHash(),
                valid.transformRef(),
                valid.identityMap(),
                valid.createdAt()),
            new PageCreationEvidence(
                valid.pageId(),
                valid.draftId(),
                context,
                valid.command(),
                valid.original(),
                valid.sourceHash(),
                "0".repeat(64),
                valid.transformRef(),
                valid.identityMap(),
                valid.createdAt()),
            new PageCreationEvidence(
                valid.pageId(),
                valid.draftId(),
                context,
                valid.command(),
                valid.original(),
                valid.sourceHash(),
                valid.outputHash(),
                "forged-transform",
                valid.identityMap(),
                valid.createdAt()),
            new PageCreationEvidence(
                valid.pageId(),
                valid.draftId(),
                context,
                valid.command(),
                valid.original(),
                valid.sourceHash(),
                valid.outputHash(),
                valid.transformRef(),
                List.of(),
                valid.createdAt()))) {
      assertThatThrownBy(() -> verifyInOwner(corrupt))
          .isInstanceOf(UiLayoutLifecycleException.class);
    }
    verify(host, never()).requireNative(any(), any(), any());
  }

  @Test
  void completeLineageIsBoundedAndCyclesCannotHideBehindValidHashes() throws Exception {
    var first = nativeCreation();
    var publishedFirst = publish(first);
    var second =
        creation(publishedFirst.reference(), publishedFirst.snapshot(), UUID.randomUUID(), null);
    var publishedSecond = publish(second);
    var inverse =
        second.identityMap().stream()
            .map(
                entry ->
                    new PageCopyTransformation.Identity(entry.kind(), entry.to(), entry.from()))
            .toList();
    var cyclicFirst =
        creation(publishedSecond.reference(), publishedSecond.snapshot(), first.pageId(), inverse);
    records.put(first.pageId(), cyclicFirst);
    assertThatThrownBy(() -> verifyInOwner(cyclicFirst))
        .isInstanceOf(UiLayoutLifecycleException.class);
    records.clear();
    snapshots.clear();
    captured.clear();
    var current = nativeCreation();
    for (int depth = 1; depth < 16; depth++) {
      var published = publish(current);
      current = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    }
    verifyInOwner(current); // Exact boundary succeeds without truncation.
    var published = publish(current);
    var tooDeep = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    assertThatThrownBy(() -> verifyInOwner(tooDeep))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void aggregateLedgerBytesAreBoundedAcrossOtherwiseValidSources() throws Exception {
    var current = nativeCreation(true);
    verifyInOwner(current);
    for (int depth = 1; depth < 16; depth++) {
      var published = publish(current);
      current = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    }
    var oversized = current;
    assertThatThrownBy(() -> verifyInOwner(oversized))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
  }

  @Test
  void verificationWithoutAnOwningScopeFailsBeforeProducerOrCatalogAccess() throws Exception {
    var creation = nativeCreation();
    assertThatThrownBy(() -> bridge.verify(context, creation))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    verifyNoInteractions(host, metadata, nativeSource, store);
  }

  @Test
  void admissionAndGeneratedMetadataConsumeTheirOwnersClockWithoutReopeningIt() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var seed = bridge.outputSeed(creation);
    var admission = bridge.admission((operation, current) -> {});
    var generatedMetadata = bridge.metadataAdmission();
    // Both SPI entry points must borrow their initiating operation, including across source roots.
    for (boolean throughAdmission : List.of(true, false)) {
      var clock = new AtomicLong();
      var nativeCalls = new AtomicLong();
      var metadataCalls = new AtomicLong();
      var childCalls = new AtomicLong();
      clearInvocations(host, metadata, nativeSource);
      doAnswer(call -> { nativeCalls.incrementAndGet(); clock.addAndGet(Duration.ofSeconds(60).toNanos()); return null; })
          .when(host).requireNative(any(), any(), any());
      doAnswer(call -> { metadataCalls.incrementAndGet(); clock.addAndGet(Duration.ofSeconds(60).toNanos()); return null; })
          .when(metadata).require(any(), any(), any(), any(), any(), any());
      doAnswer(call -> { childCalls.incrementAndGet(); return null; })
          .when(nativeSource).requirePageChild(any(), any(), any(), any());
      Runnable callback = throughAdmission
          ? () -> admission.require(UiLayoutLifecycleOperation.EDIT_DRAFT, invocation)
          : () -> generatedMetadata.require(invocation, seed.target(), seed.authoring(),
              seed.sourceRef(), seed.document(), seed.metadata());
      org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
      try (var owner = new UiLayoutValidationAttempt(invocation, UiLayoutLifecycleOperation.EDIT_DRAFT,
              Duration.ofSeconds(100), clock::get);
          var scope = UiLayoutValidationScope.bind(owner)) {
        assertThatThrownBy(callback::run)
            .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
        assertThat(owner.remainingBudget()).isZero();
        long observedNativeCalls = nativeCalls.get();
        long observedMetadataCalls = metadataCalls.get();
        long observedChildCalls = childCalls.get();
        assertThat(observedNativeCalls).isPositive();
        assertThat(observedMetadataCalls).isPositive();
        assertThat(observedChildCalls).as("Root metadata exhausts the owner before child admission").isZero();
        // Native admission and root metadata consume 60 seconds each from the same 100.
        // No child, repeated check or later SPI invocation may execute after that expiry.
        for (int repeated = 0; repeated < 2; repeated++) {
          assertThatThrownBy(callback::run)
              .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                  failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
          assertThat(nativeCalls.get()).isEqualTo(observedNativeCalls);
          assertThat(metadataCalls.get()).isEqualTo(observedMetadataCalls);
          assertThat(childCalls.get()).isEqualTo(observedChildCalls);
          assertThat(owner.remainingBudget()).isZero();
        }
      } finally {
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
      }
    }
  }

  @Test
  void allNestedRootsConsumeTheOriginalDeadlineAndNeverReopenAnExpiredAttempt() throws Exception {
    var original = nativeCreation();
    var published = publish(original);
    var copied = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    var clock = new AtomicLong();
    var attempt =
        new UiLayoutValidationAttempt(
            context.invocation(),
            UiLayoutLifecycleOperation.CREATE_DRAFT,
            Duration.ofSeconds(100),
            clock::get);
    doAnswer(
            call -> {
              clock.addAndGet(Duration.ofSeconds(60).toNanos());
              return null;
            })
        .when(host)
        .requireNative(any(), any(), any());
    // One ancestral callback consumes 60. The next extension pushes the aggregate over 100.
    doAnswer(
            call -> {
              clock.addAndGet(Duration.ofSeconds(60).toNanos());
              return null;
            })
        .when(metadata)
        .require(any(), any(), any(), any(), any(), any());
    assertThatThrownBy(() -> bridge.verify(context, copied, attempt))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThat(attempt.remainingBudget()).isZero();
    assertThatThrownBy(() -> bridge.verify(context, copied, attempt))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }
}
