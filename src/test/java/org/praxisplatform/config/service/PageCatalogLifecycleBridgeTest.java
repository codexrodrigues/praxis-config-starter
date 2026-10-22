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
            (operation, invocation) -> {},
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
                hashes.sha256Exact(mapper.createObjectNode()),
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
            List.of(mapper.createObjectNode())));
    return new Published(
        new PageSourceReference.Published(creation.pageId(), releaseId, revisionId),
        new PageCreationEvidence.SourceSnapshot(seed, seed.document(), creation.draftId()));
  }

  private record Published(
      PageSourceReference.Published reference, PageCreationEvidence.SourceSnapshot snapshot) {}

  @Test
  void forgedProducerPrefixWithoutDurableRegistrationNeverDelegatesToPermissiveHost()
      throws Exception {
    var value = nativeCreation();
    var seed = bridge.outputSeed(value);
    assertThatThrownBy(
            () ->
                bridge
                    .metadataAdmission()
                    .require(
                        context.forTarget(value.target()),
                        seed.target(),
                        seed.authoring(),
                        seed.sourceRef(),
                        seed.document(),
                        seed.metadata()))
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
      assertThatThrownBy(() -> bridge.verify(context, corrupt))
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
    assertThatThrownBy(() -> bridge.verify(context, cyclicFirst))
        .isInstanceOf(UiLayoutLifecycleException.class);
    records.clear();
    snapshots.clear();
    captured.clear();
    var current = nativeCreation();
    for (int depth = 1; depth < 16; depth++) {
      var published = publish(current);
      current = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    }
    bridge.verify(context, current); // Exact boundary succeeds without truncation.
    var published = publish(current);
    var tooDeep = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    assertThatThrownBy(() -> bridge.verify(context, tooDeep))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void aggregateLedgerBytesAreBoundedAcrossOtherwiseValidSources() throws Exception {
    var current = nativeCreation(true);
    bridge.verify(context, current);
    for (int depth = 1; depth < 16; depth++) {
      var published = publish(current);
      current = creation(published.reference(), published.snapshot(), UUID.randomUUID(), null);
    }
    var oversized = current;
    assertThatThrownBy(() -> bridge.verify(context, oversized))
        .isInstanceOfSatisfying(
            UiLayoutLifecycleException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
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
