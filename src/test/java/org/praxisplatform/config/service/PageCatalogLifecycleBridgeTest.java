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
  private final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);
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
    doAnswer(call -> {
      UiLayoutLifecycleInvocation current = call.getArgument(0);
      UiLayoutPageChildSeed child = call.getArgument(2);
      var target = new UiLayoutTarget(child.document().path("kind").asText().equals("praxis.dynamic-form.editor")
          ? "praxis-dynamic-form" : "praxis-table", child.request().widgetKey());
      metadata.require(current, target, child.authoring(), child.sourceRef(), child.document(), child.metadata());
      return null;
    }).when(nativeSource).requirePageChildEvidence(any(), any(), any(), any());
    doAnswer(call -> {
      PageCreationContext current = call.getArgument(0);
      PageSourceReference.Native origin = call.getArgument(1);
      UiLayoutDraftWorkspaceSeed.TargetSeed captured = call.getArgument(2);
      PageNativeProvenanceTestPolicies.require(current, captured,
          () -> host.requireNative(current, origin, captured), nativeSource, metadata);
      return host.resolveLifecycle(((PageCreationContext) call.getArgument(0)).invocation());
    }).when(host).requireNativeProvenance(any(), any(), any());
    when(host.requireCreation(any(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
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
    when(captures.readVerified(any(), any(), any()))
        .thenAnswer(
            call -> {
              UiLayoutMetadataCapture.Binding binding = call.getArgument(1);
              return new UiLayoutMetadataCaptureCodec(mapper, hashes).verifyAndRead(call.getArgument(0), binding,
                  captured.get(binding.sourceDraftRef()), call.getArgument(2));
            });
    bridge =
        new PageCatalogLifecycleBridge(
            host,
            store,
            codec,
            hashes,
            nativeSource,
            metadata,
            structure,
            frozen,
            captures,
            mapper,
            (operation, invocation) -> Duration.ofSeconds(30));
  }

  private PageLifecycleAdmission joint(UiLayoutLifecycleAdmission policy) {
    return new PageLifecycleAdmission() {
      public void require(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
        policy.require(operation, invocation);
      }
      public PageCreationContext requirePage(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
        policy.require(operation, invocation);
        return host.resolveLifecycle(invocation);
      }
    };
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
            .initial(invocation, creation.draftId(), seed, nativeSource, validation);
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
  void generatedChildJointAdmissionRequiresExactPageSourceAndMetadataBeforeTraversal() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var output = bridge.outputSeed(creation);
    var child = output.pageChildren().getFirst();
    var invocation = context.forTarget(creation.target());
    java.util.function.BiConsumer<UiLayoutTarget, UiLayoutPageChildSeed> admit = (page, candidate) -> {
      try (var attempt = UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(30));
          var scope = UiLayoutValidationScope.bind(attempt)) {
        bridge.workspaceSource().requirePageChildEvidence(invocation, page, candidate,
            new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING));
      }
    };
    admit.accept(output.target(), child);
    clearInvocations(host);
    var metadata = child.metadata();
    var changedMetadata = new UiLayoutBaselineMetadataSeed(metadata.source(), metadata.reproduction(),
        metadata.observation(), metadata.rawInputText() + " ");
    var wrongSource = new UiLayoutPageChildSeed(child.request(), child.authoring(), child.patch(),
        "another-source", child.document(), metadata);
    var wrongMetadata = new UiLayoutPageChildSeed(child.request(), child.authoring(), child.patch(),
        child.sourceRef(), child.document(), changedMetadata);
    var wrongDescriptor = new UiLayoutPageChildSeed(child.request(),
        new UiLayoutAuthoringDocumentDescriptor(child.authoring().documentType(), "another-schema", "1"),
        child.patch(), child.sourceRef(), child.document(), metadata);
    var changedDocument = child.document();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedDocument).put("another", true);
    var wrongDocument = new UiLayoutPageChildSeed(child.request(), child.authoring(), child.patch(),
        child.sourceRef(), changedDocument, metadata);
    for (var invalid : List.of(wrongSource, wrongMetadata, wrongDescriptor, wrongDocument)) {
      assertThatThrownBy(() -> admit.accept(output.target(), invalid))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
    }
    assertThatThrownBy(() -> admit.accept(context.invocation().rootTarget(), child))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
    verify(host, never()).requireNativeProvenance(any(), any(), any());
  }

  @Test
  void jointLifecycleRejectsPlainDelegateAndKeepsNonPagePolicy() {
    assertThatThrownBy(() -> bridge.admission((op, inv) -> {})).isInstanceOf(IllegalArgumentException.class);
    var policy = mock(UiLayoutLifecycleAdmission.class);
    var nonPage = context.forTarget(new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString()));
    bridge.admission(joint(policy)).require(UiLayoutLifecycleOperation.READ_DRAFT, nonPage);
    verify(policy).require(UiLayoutLifecycleOperation.READ_DRAFT, nonPage);
    verifyNoInteractions(store, host);
  }

  @Test
  void unknownPageStillAdmitsCustomOperationBeforeCatalogLookup() {
    var invocation = context.forTarget(new UiLayoutTarget("praxis-dynamic-page", UUID.randomUUID().toString()));
    var order = new ArrayList<String>();
    var denied = new java.util.concurrent.atomic.AtomicBoolean(true);
    var admission = bridge.admission(joint((op, inv) -> {
      order.add("operation");
      if (denied.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "custom domain policy");
    }));
    when(store.byPage(any(), any())).thenAnswer(call -> { order.add("catalog"); return Optional.empty(); });
    assertThatThrownBy(() -> admission.require(UiLayoutLifecycleOperation.READ_DRAFT, invocation))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    assertThat(order).containsExactly("operation");
    verifyNoInteractions(store);
    denied.set(false); order.clear();
    admission.require(UiLayoutLifecycleOperation.READ_DRAFT, invocation);
    assertThat(order).containsExactly("operation", "catalog");
  }

  @Test
  void jointLifecycleRejectsMissingOrMismatchedContextBeforeContentAndForeignNamespaceAfterLookup() throws Exception {
    var creation = nativeCreation(); records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var policy = mock(PageLifecycleAdmission.class);
    var admission = bridge.admission(policy);
    for (var mismatch : java.util.Arrays.asList(null, context)) {
      when(policy.requirePage(any(), any())).thenReturn(mismatch);
      assertThatThrownBy(() -> admission.require(UiLayoutLifecycleOperation.READ_DRAFT, invocation))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
      verifyNoInteractions(store);
    }
    for (var foreign : List.of(new PageCreationContext("other-app", "/pages", invocation),
        new PageCreationContext("app", "/other", invocation))) {
      when(policy.requirePage(any(), any())).thenReturn(foreign);
      assertThatThrownBy(() -> admission.require(UiLayoutLifecycleOperation.READ_DRAFT, invocation))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    }
    verify(host, never()).requireNativeProvenance(any(), any(), any());
  }

  @Test
  void jointLifecycleKeepsFinalOperationDenialAfterOriginTraversal() throws Exception {
    var creation = nativeCreation(); records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var admission = bridge.admission(joint((op, inv) -> {
      if (calls.incrementAndGet() == 2)
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "final domain revocation");
    }));
    org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> withOwner(invocation, UiLayoutLifecycleOperation.EDIT_DRAFT,
          () -> admission.require(UiLayoutLifecycleOperation.EDIT_DRAFT, invocation)))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
      assertThat(calls.get()).isEqualTo(2);
      verify(host, times(2)).requireNativeProvenance(any(), any(), any());
    } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
  }

  @Test
  void creationJointPreservesExplicitCredentialsAndRejectsEveryReturnedContextDifference() {
    java.security.Principal principal = () -> "explicit-principal";
    var source = new PageSourceReference.Native("fixture:page", "r1");
    for (var returned : java.util.Arrays.asList(null,
        new PageCreationContext("foreign", "/pages", context.invocation()),
        new PageCreationContext("app", "/foreign", context.invocation()),
        new PageCreationContext("app", "/pages", context.forTarget(new UiLayoutTarget("praxis-dynamic-page", UUID.randomUUID().toString()))))) {
      when(host.requireCreation(principal, "c1", context, source)).thenReturn(returned);
      assertThatThrownBy(() -> bridge.requireCreation(principal, "c1", context, source))
          .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    }
    when(host.requireCreation(principal, "c1", context, source)).thenReturn(context);
    bridge.requireCreation(principal, "c1", context, source);
    verify(host, times(5)).requireCreation(same(principal), eq("c1"), eq(context), eq(source));
    verify(host, never()).resolve(any(), any());
  }

  @Test
  void postCatalogJointRegistrationDenialBlocksEveryExtensionAndFurtherIo() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var events = new ArrayList<String>();
    var registrationRevoked = new java.util.concurrent.atomic.AtomicBoolean();
    when(host.resolveLifecycle(invocation)).thenAnswer(call -> {
      events.add("registration");
      if (registrationRevoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "registration revoked");
      return new PageCreationContext("app", "/pages", invocation);
    });
    when(store.byPage(any(), eq(creation.pageId()))).thenAnswer(call -> {
      events.add("catalog"); registrationRevoked.set(true); return Optional.of(creation);
    });
    // Provenance bytes remain acceptable. The joint callback must also run the full registration
    // policy, which now denies; a permissive provenance-only policy could not satisfy this SPI.
    doAnswer(call -> {
      events.add("joint"); return host.resolveLifecycle(((PageCreationContext) call.getArgument(0)).invocation());
    }).when(host).requireNativeProvenance(any(), any(), any());
    assertThatThrownBy(() -> captureRegistered(invocation)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    assertThat(events).containsExactly("registration", "catalog", "joint", "registration");
    verify(store, times(1)).byPage(any(), any());
    verifyNoMoreInteractions(store);
    verifyNoInteractions(structure, nativeSource, metadata, captures, frozen);
  }

  @Test
  void postCatalogJointRejectsNullAndEveryRegistrationIdentityDifferenceBeforeExtensions() throws Exception {
    var creation = nativeCreation();
    records.put(creation.pageId(), creation);
    var invocation = context.forTarget(creation.target());
    var foreignComposition = new UiLayoutLifecycleInvocation(invocation.actorRef(), invocation.tenantId(),
        invocation.administrativeUnit(), invocation.environment(), invocation.contextVersion(),
        new UiLayoutCompositionRegistration(UiLayoutPageRevisionReproductionTest.PAGE, List.of(UiLayoutPageRevisionReproductionTest.PAGE)));
    var foreignVersion = new UiLayoutLifecycleInvocation(invocation.actorRef(), invocation.tenantId(),
        invocation.administrativeUnit(), invocation.environment(), "changed", invocation.composition());
    for (var returned : java.util.Arrays.asList(null,
        new PageCreationContext("foreign", "/pages", invocation),
        new PageCreationContext("app", "/foreign", invocation),
        new PageCreationContext("app", "/pages", foreignComposition),
        new PageCreationContext("app", "/pages", foreignVersion))) {
      clearInvocations(structure, nativeSource, metadata, captures, frozen);
      doReturn(returned).when(host).requireNativeProvenance(any(), any(), any());
      assertThatThrownBy(() -> captureRegistered(invocation)).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
      verifyNoInteractions(structure, nativeSource, metadata, captures, frozen);
    }
  }

  @Test
  void finalProvenanceContextDriftIsRejectedAfterStructuralExtensions() throws Exception {
    var creation = nativeCreation();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> calls.incrementAndGet() == 1 ? call.getArgument(0)
        : new PageCreationContext("app", "/changed", ((PageCreationContext) call.getArgument(0)).invocation()))
        .when(host).requireNativeProvenance(any(), any(), any());
    assertThatThrownBy(() -> verifyInOwner(creation)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertThat(calls.get()).isEqualTo(2);
    verify(structure).validateAuthoringRevision(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void registrationPolicyDenialBeforeCatalogStillPreventsAllContentReads() throws Exception {
    var creation = nativeCreation();
    var invocation = context.forTarget(creation.target());
    when(host.resolveLifecycle(invocation)).thenThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "registration denied"));
    assertThatThrownBy(() -> captureRegistered(invocation)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(store, structure, nativeSource, metadata);
    verify(host, never()).requireNativeProvenance(any(), any(), any());
  }

  private void captureRegistered(UiLayoutLifecycleInvocation invocation) {
    var validation = UiLayoutValidationContext.begin((op, inv) -> Duration.ofSeconds(30), invocation,
        UiLayoutLifecycleOperation.CREATE_DRAFT, UiLayoutValidationPurpose.AUTHORING);
    try (var attempt = validation.attempt(); var scope = UiLayoutValidationScope.bind(attempt)) {
      bridge.workspaceSource().capture(invocation, validation);
    }
  }

  @Test
  void pureNativeJointProvenanceBracketsEveryStructuralExtension() throws Exception {
    var creation = nativeCreation();
    var order = new ArrayList<String>();
    doAnswer(call -> { order.add("provenance"); return host.resolveLifecycle(((PageCreationContext) call.getArgument(0)).invocation()); }).when(host).requireNativeProvenance(any(), any(), any());
    doAnswer(call -> { order.add("target"); return null; }).when(structure).validateTarget(any(), any(), any());
    doAnswer(call -> { order.add("baseline"); return null; }).when(structure).validateAuthoringBaseline(any(), any(), any(), any(), any());
    doAnswer(call -> { order.add("revision"); return null; }).when(structure).validateAuthoringRevision(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verifyInOwner(creation);
    assertThat(order).containsExactly("provenance", "target", "baseline", "revision", "provenance");
    verifyNoInteractions(metadata, nativeSource);
    verify(host, never()).requireNative(any(), any(), any());
  }

  @Test
  void revocationByAnyStructuralExtensionIsObservedByFinalJointAdmission() throws Exception {
    var creation = nativeCreation();
    for (int boundary = 0; boundary < 3; boundary++) {
      reset(structure);
      clearInvocations(host);
      var revoked = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(call -> {
        if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked by extension");
        return host.resolveLifecycle(((PageCreationContext) call.getArgument(0)).invocation());
      }).when(host).requireNativeProvenance(any(), any(), any());
      org.mockito.stubbing.Answer<Void> revoke = call -> { revoked.set(true); return null; };
      if (boundary == 0) doAnswer(revoke).when(structure).validateTarget(any(), any(), any());
      if (boundary == 1) doAnswer(revoke).when(structure).validateAuthoringBaseline(any(), any(), any(), any(), any());
      if (boundary == 2) doAnswer(revoke).when(structure).validateAuthoringRevision(any(), any(), any(), any(), any(), any(), any(), any(), any());
      assertThatThrownBy(() -> verifyInOwner(creation)).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
          failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
      verify(host, times(2)).requireNativeProvenance(any(), any(), any());
    }
  }

  @Test
  void nativeAggregateChecksTheOriginalBudgetAtEntryAndAfterEachAdmission() throws Exception {
    var creation = nativeCreation();
    for (int expireAt : List.of(0, 1, 2)) {
      clearInvocations(host, structure);
      var clock = new AtomicLong();
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(call -> {
        if (calls.incrementAndGet() == expireAt) clock.set(Duration.ofSeconds(2).toNanos());
        return host.resolveLifecycle(((PageCreationContext) call.getArgument(0)).invocation());
      }).when(host).requireNativeProvenance(any(), any(), any());
      try (var attempt = new UiLayoutValidationAttempt(context.invocation(),
          UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(1), clock::get);
          var scope = UiLayoutValidationScope.bind(attempt)) {
        if (expireAt == 0) clock.set(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> bridge.verify(context, creation, attempt))
            .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
        assertThat(calls.get()).isEqualTo(expireAt);
        assertThat(attempt.remainingBudget()).isZero();
        if (expireAt < 2) verifyNoInteractions(structure);
      }
    }
  }

  @Test
  void generatedRootInNativeWrapperKeepsConfigRecursionAndIndependentAdmissions() throws Exception {
    var original = nativeCreation();
    var published = publish(original);
    var wrapper = new PageSourceReference.Native("fixture:mixed-root", "r1");
    var mixed = creation(wrapper, new PageCreationEvidence.SourceSnapshot(
        published.snapshot().baseline(), published.snapshot().document(), null), UUID.randomUUID(), null);
    verifyInOwner(mixed);
    verify(host).requireNative(eq(context), eq(wrapper), any());
    verify(host, never()).requireNativeProvenance(any(), eq(wrapper), any());
    verify(store, atLeastOnce()).byPage(context, original.pageId());
    verify(host, times(2)).requireNativeProvenance(eq(context), eq((PageSourceReference.Native) original.command().source()), any());
  }

  @Test
  void generatedChildCannotUseNativeAggregateToBypassMissingConfigProvenance() throws Exception {
    var original = nativeCreation();
    var old = original.original().baseline();
    var child = old.pageChildren().getFirst();
    var generated = bridge.outputSeed(original).pageChildren().getFirst().metadata();
    var metadata = new UiLayoutBaselineMetadataSeed(generated.source(), generated.reproduction(),
        generated.observation(), child.document().toString());
    var changedChild = new UiLayoutPageChildSeed(child.request(), child.authoring(), child.patch(), child.sourceRef(), child.document(), metadata);
    var seed = new UiLayoutDraftWorkspaceSeed.TargetSeed(old.target(), old.authoring(), old.patch(), old.sourceRef(),
        old.document(), old.metadata(), List.of(changedChild));
    var wrapper = new PageSourceReference.Native("fixture:mixed-child", "r1");
    var mixed = creation(wrapper, new PageCreationEvidence.SourceSnapshot(seed, seed.document(), null), UUID.randomUUID(), null);
    assertThatThrownBy(() -> verifyInOwner(mixed)).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE));
    verify(host).requireNative(eq(context), eq(wrapper), any());
    verify(host, never()).requireNativeProvenance(any(), any(), any());
    verify(store).byPage(context, UUID.fromString(seed.target().componentId()));
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
      verify(host, times(5)).requireNative(any(), any(), any());
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
      assertThatThrownBy(() -> bridge.capture(() -> "alice", "c1", context, published.reference(), attempt))
          .isInstanceOf(UiLayoutLifecycleException.class);
    }
  }

  @Test
  void captureJointWindowRetainsDeadlineChecksBeforeAndAfterAuthority() {
    for (boolean closeDuringAdmission : List.of(false, true)) {
      clearInvocations(host, store, frozen, captures, structure);
      var attempt = UiLayoutValidationAttempt.start(context.invocation(),
          UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(40));
      java.security.Principal principal = () -> "alice";
      var source = new PageSourceReference.Published(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
      when(host.requireCreation(same(principal), eq("c1"), eq(context), eq(source))).thenAnswer(call -> {
        attempt.close(); return context;
      });
      if (!closeDuringAdmission) attempt.close();
      try (attempt) {
        assertThatThrownBy(() -> bridge.capture(principal, "c1", context, source, attempt))
            .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      }
      verify(host, times(closeDuringAdmission ? 1 : 0)).requireCreation(same(principal), eq("c1"), eq(context), eq(source));
      verifyNoInteractions(store, frozen, captures, structure);
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
    var admission = actualBridge.admission(joint((operation, invocation) -> {
      if (!allowed.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private policy");
    }));
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
        bridge.admission(joint(
            (op, invocation) -> {
              if (revoked.get())
                throw new UiLayoutLifecycleException(
                    UiLayoutLifecycleException.Code.DENIED, "Revoked during provenance");
            }));
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
                    .admission(joint((op, inv) -> {}))
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
                    .requirePageChildEvidence(
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
    var admission = bridge.admission(joint((operation, current) -> {}));
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
          .when(nativeSource).requirePageChildEvidence(any(), any(), any(), any());
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
    try (attempt; var scope = UiLayoutValidationScope.bind(attempt)) {
      assertThatThrownBy(() -> bridge.verify(context, copied, attempt))
          .isInstanceOf(UiLayoutLifecycleException.class);
      assertThat(attempt.remainingBudget()).isZero();
      assertThatThrownBy(() -> bridge.verify(context, copied, attempt))
          .isInstanceOf(UiLayoutLifecycleException.class);
    }
  }
}
