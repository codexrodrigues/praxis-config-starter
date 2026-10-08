package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseApprovalRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseEventRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("unit")
class UiLayoutLifecycleCommandServiceTest {
  @Test void directRevisionInvalidInputFailsBeforeRepositoryMutation() {
    var structure = mock(UiLayoutLifecycleStructureValidator.class);
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    var service = service((operation, invocation) -> {}, structure, source,
        (tenant, environment, target, actor, key) -> {});
    for (String document : new String[] {"{} trailing", "{\"v\":1,\"v\":2}", " ".repeat(1048577)}) {
      assertThatThrownBy(() -> service.createRevision(principal, "ctx-1", root, java.util.UUID.randomUUID(),
          "\"00000000-0000-4000-8000-000000000001\"",
          new UiLayoutLifecycleCommandService.RevisionInput(java.util.UUID.randomUUID(), root, document, "test")))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST));
    }
    verify(drafts, never()).save(any()); verify(releases, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(structure, source);
  }
  private final ObjectMapper mapper = new ObjectMapper();
  private final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  private final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final Principal principal = () -> "author";
  private UiLayoutMetadataCapturePersistence metadataStore = new UiLayoutMetadataTestFixtures.MemoryStore();
  private UiLayoutBaselineMetadataAdmission metadataGate = (invocation, target, authoring, source, baseline, metadata) -> {};

  @Test void sourceNativeValidationAndResponseProjectionShareOneAttempt() {
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    var structure = mock(UiLayoutLifecycleStructureValidator.class);
    when(source.capture(any(), any())).thenReturn(seed(root, mapper.createObjectNode()));
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var service = service((operation, inv) -> {}, structure, source, (tenant, environment, target, actor, key) -> {});
    service.createDraft(principal, "ctx-1", root, "shared-attempt");
    var sourceContext = org.mockito.ArgumentCaptor.forClass(UiLayoutValidationContext.class);
    verify(source).capture(any(), sourceContext.capture());
    var targetContexts = org.mockito.ArgumentCaptor.forClass(UiLayoutValidationContext.class);
    verify(structure, times(2)).validateTarget(any(), any(), targetContexts.capture());
    var original = sourceContext.getValue();
    assertThat(original.purpose()).isEqualTo(UiLayoutValidationPurpose.AUTHORING);
    assertThat(targetContexts.getAllValues()).extracting(UiLayoutValidationContext::purpose)
        .containsExactly(UiLayoutValidationPurpose.AUTHORING, UiLayoutValidationPurpose.CURRENT_WORKSPACE_READ);
    for (var ctx : targetContexts.getAllValues()) {
      assertThat(ctx.attempt()).isSameAs(original.attempt());
      assertThat(ctx.attempt().operation()).isEqualTo(UiLayoutLifecycleOperation.CREATE_DRAFT);
    }
    assertThat(original.attempt().remainingBudget()).isZero();
  }

  @Test void invalidatedCaptureCannotProceedToNativeValidationOrPersistence() {
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    var structure = mock(UiLayoutLifecycleStructureValidator.class);
    when(source.capture(any(), any())).thenAnswer(call -> {
      ((UiLayoutValidationContext) call.getArgument(1)).attempt().close();
      return seed(root, mapper.createObjectNode());
    });
    var service = service((operation, inv) -> {}, structure, source, (tenant, environment, target, actor, key) -> {});
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "late-capture"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    org.mockito.Mockito.verifyNoInteractions(structure);
    verify(drafts, never()).save(any());
  }

  @Test
  void deniedCreateStopsBeforeLockReplaySourceAndPersistence() {
    UiLayoutDraftCreationLock lock = mock(UiLayoutDraftCreationLock.class);
    UiLayoutDraftWorkspaceSource source = mock(UiLayoutDraftWorkspaceSource.class);
    UiLayoutLifecycleCommandService service = service((operation, invocation) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "denied");
    }, validator(), source, lock);

    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "create-1"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    verify(lock, never()).lock(any(), any(), any(), any(), any());
    verify(drafts, never()).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        any(), any(), any(), any(), any(), any());
    verify(source, never()).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(drafts, never()).save(any());
  }

  @Test
  void repeatedCreateLocksAndReturnsCurrentWorkspaceWithoutRecapturingBaseline() {
    AtomicInteger captures = new AtomicInteger();
    UiLayoutDraftWorkspaceSource source = mock(UiLayoutDraftWorkspaceSource.class);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenAnswer(call -> {
      captures.incrementAndGet();
      return seed(root, mapper.createObjectNode().put("density", "comfortable"));
    });
    UiLayoutDraftCreationLock lock = mock(UiLayoutDraftCreationLock.class);
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    UiLayoutLifecycleCommandService service = service((operation, invocation) -> {}, validator(), source, lock);

    var created = service.createDraft(principal, "ctx-1", root, "create-1");
    var draftCaptor = org.mockito.ArgumentCaptor.forClass(UiLayoutDraft.class);
    verify(drafts).save(draftCaptor.capture());
    UiLayoutDraft persisted = draftCaptor.getValue();
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant-a", "lab", root.componentType(), root.componentId(), "author", "create-1"))
        .thenReturn(Optional.of(persisted));

    var replay = service.createDraft(principal, "ctx-1", root, "create-1");

    assertThat(replay).isEqualTo(created);
    assertThat(replay.schemaVersion()).isEqualTo("praxis.ui-layout-draft-workspace/v1");
    assertThat(replay.targets().getFirst().authoringDocumentType()).isEqualTo("praxis.table.authoring-document");
    assertThat(replay.targets().getFirst().authoringSchemaVersion()).isEqualTo("table-authoring/v3");
    assertThat(replay.targets().getFirst().patchSchemaVersion()).isEqualTo("table-layout-patch/v1");
    assertThat(captures).hasValue(1);
    verify(lock, times(2)).lock("tenant-a", "lab", root, "author", "create-1");
    verify(drafts, times(1)).save(any());
    var order = org.mockito.Mockito.inOrder(lock, drafts, source);
    order.verify(lock).lock("tenant-a", "lab", root, "author", "create-1");
    order.verify(drafts).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant-a", "lab", root.componentType(), root.componentId(), "author", "create-1");
    order.verify(source).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    order.verify(drafts).save(any());
    order.verify(lock).lock("tenant-a", "lab", root, "author", "create-1");
    order.verify(drafts).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant-a", "lab", root.componentType(), root.componentId(), "author", "create-1");
  }

  @Test
  void invalidBaselineAbortsBeforeAnyDraftRowIsPersisted() {
    UiLayoutLifecycleStructureValidator rejecting = new UiLayoutLifecycleStructureValidator() {
      @Override public void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baselineDocument, org.praxisplatform.config.service.UiLayoutValidationContext validation) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "invalid baseline");
      }
      @Override public void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutResolutionCandidate.LayerClass layerClass, UiLayoutAudienceSelector selector, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
    };
    UiLayoutLifecycleCommandService service = service((operation, invocation) -> {}, rejecting,
        (invocation, validation) -> seed(root, mapper.createObjectNode()), (tenant, environment, target, actor, key) -> {});

    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "create-invalid"))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    verify(drafts, never()).save(any());
  }

  @Test
  void sourceAndPersistenceRunInsideTheConfigTransactionAfterInvocationResolution() {
    Object operationalDataSource = new Object();
    Object configDataSource = new Object();
    UiLayoutLifecycleInvocationProvider provider = (current, context, requested) -> {
      if (TransactionSynchronizationManager.hasResource(configDataSource)) return invocation();
      assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isFalse();
      TransactionSynchronizationManager.bindResource(operationalDataSource, new Object());
      try { return invocation(); }
      finally { TransactionSynchronizationManager.unbindResource(operationalDataSource); }
    };
    UiLayoutDraftWorkspaceSource source = (invocation, validation) -> {
      assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isTrue();
      assertThat(TransactionSynchronizationManager.hasResource(operationalDataSource)).isFalse();
      return seed(root, mapper.createObjectNode());
    };
    when(drafts.save(any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isTrue();
      return call.getArgument(0);
    });
    UiLayoutLifecycleCommandService service = service(provider, (operation, invocation) -> {}, validator(), source,
        (tenant, environment, target, actor, key) -> assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isTrue(),
        new TransactionTemplate(new ResourceTransactionManager(configDataSource)));

    service.createDraft(principal, "ctx-1", root, "create-1");

    verify(drafts).save(any());
    assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isFalse();
  }

  private UiLayoutLifecycleCommandService service(UiLayoutLifecycleAdmission admission,
      UiLayoutLifecycleStructureValidator structure, UiLayoutDraftWorkspaceSource source,
      UiLayoutDraftCreationLock lock) {
    return service((current, context, requested) -> invocation(), admission, structure, source, lock,
        new TransactionTemplate(new ResourceTransactionManager(new Object())));
  }

  @Test void producerTechnicalFailureIsRedactedAndStopsBeforePersistence() {
    metadataGate = (invocation, target, authoring, source, baseline, metadata) -> {
      throw new IllegalStateException("private-producer-credential");
    };
    var service = service((operation, invocation) -> {}, validator(), (current, validation) -> seed(root, mapper.createObjectNode()),
        (tenant, environment, target, actor, key) -> {});
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "denied-origin"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
          assertThat(exception.getMessage()).doesNotContain("private-producer");
          assertThat(exception.getCause()).isNull();
        });
    verify(drafts, never()).save(any());
  }

  @Test void sourceLifecycleFailurePreservesCodeAndRedactsPrivateContent() {
    var service = service((operation, invocation) -> {}, validator(), (current, validation) -> {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private-schema-body");
    }, (tenant, environment, target, actor, key) -> {});
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "private-source"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED);
          assertThat(exception.getMessage()).doesNotContain("private-schema");
          assertThat(exception.getCause()).isNull();
        });
    verify(drafts, never()).save(any());
  }

  @Test void staleObservationIsRejectedBeforePersistence() {
    var original = seed(root, mapper.createObjectNode()).targets().getFirst();
    var stale = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        original.authoring(), original.patch(), original.sourceRef(), original.document(),
        UiLayoutMetadataTestFixtures.metadata("author", "procurement", "old-context"))));
    var service = service((operation, invocation) -> {}, validator(), (current, validation) -> stale,
        (tenant, environment, target, actor, key) -> {});
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "old-observation"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
    verify(drafts, never()).save(any());
    assertThat(stale.toString()).doesNotContain("properties", "fixture-policy", "old-context");
  }

  @Test void currentProducerRevocationDeniesReplayWithoutRecapture() {
    var revoked = new java.util.concurrent.atomic.AtomicBoolean();
    metadataGate = (invocation, target, authoring, source, baseline, metadata) -> {
      if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private revocation");
    };
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenAnswer(call -> seed(root, mapper.createObjectNode()));
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var store = (UiLayoutMetadataTestFixtures.MemoryStore) metadataStore;
    var service = service((operation, invocation) -> {}, validator(), source, (tenant, environment, target, actor, key) -> {});
    service.createDraft(principal, "ctx-1", root, "create");
    var captor = org.mockito.ArgumentCaptor.forClass(UiLayoutDraft.class);
    verify(drafts).save(captor.capture());
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(captor.getValue()));
    revoked.set(true);
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "create"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    verify(source, times(1)).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    assertThat(store.appends).isEqualTo(1);
  }

  @Test void contextChangeDuringCreationStopsBeforeSourceCapture() {
    var resolutions = new AtomicInteger();
    UiLayoutLifecycleInvocationProvider provider = (current, context, target) -> resolutions.incrementAndGet() == 1
        ? invocation() : new UiLayoutLifecycleInvocation("author", "tenant-a", "changed-unit", "lab", "ctx-1", invocation().composition());
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    var service = service(provider, (operation, invocation) -> {}, validator(), source,
        (tenant, environment, target, actor, key) -> {}, new TransactionTemplate(new ResourceTransactionManager(new Object())));
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "stale"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
    verify(source, never()).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(drafts, never()).save(any());
  }

  @Test void flushPrecedesCaptureAppendAndStoredEvidenceMatchesTheSameDraft() {
    var delegate = new UiLayoutMetadataTestFixtures.MemoryStore();
    metadataStore = org.mockito.Mockito.spy(delegate);
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var service = service((operation, invocation) -> {}, validator(), (current, validation) -> seed(root, mapper.createObjectNode()),
        (tenant, environment, target, actor, key) -> {});
    var receipt = service.createDraft(principal, "ctx-1", root, "correlated");
    var order = org.mockito.Mockito.inOrder(drafts, metadataStore);
    order.verify(drafts).save(any());
    order.verify(drafts).flush();
    order.verify(metadataStore).append(any(), any(), any(), any(), any());
    assertThat(delegate.rows.values()).singleElement().satisfies(capture -> {
      assertThat(capture.binding().sourceDraftRef().toString()).isEqualTo(receipt.draft().draftRef());
      assertThat(capture.binding().baselineContentHash()).isEqualTo(receipt.targets().getFirst().baseline().contentHash());
    });
    assertThat(mapper.copy().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .valueToTree(receipt).toString()).doesNotContain("fixture-producer", "properties", "rawSchema");
  }

  @Test void missingHistoricalCaptureDeniesReplayWithoutSourceOrAppend() {
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenAnswer(call -> seed(root, mapper.createObjectNode()));
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var store = (UiLayoutMetadataTestFixtures.MemoryStore) metadataStore;
    var service = service((operation, invocation) -> {}, validator(), source, (tenant, environment, target, actor, key) -> {});
    service.createDraft(principal, "ctx-1", root, "create");
    var captor = org.mockito.ArgumentCaptor.forClass(UiLayoutDraft.class);
    verify(drafts).save(captor.capture());
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(captor.getValue()));
    store.rows.clear();
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "create"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE));
    verify(source, times(1)).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    assertThat(store.appends).isEqualTo(1);
  }

  @Test void finalContextChangeRequestsRollbackAfterAppend() {
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    metadataGate = (invocation, target, authoring, source, baseline, metadata) -> {
      if (((UiLayoutMetadataTestFixtures.MemoryStore) metadataStore).reads > 0) changed.set(true);
    };
    UiLayoutLifecycleInvocationProvider provider = (current, context, target) -> changed.get()
        ? new UiLayoutLifecycleInvocation("author", "tenant-a", "changed-unit", "lab", "ctx-1", invocation().composition()) : invocation();
    var manager = new ResourceTransactionManager(new Object());
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var service = service(provider, (operation, invocation) -> {}, validator(), (current, validation) -> seed(root, mapper.createObjectNode()),
        (tenant, environment, target, actor, key) -> {}, new TransactionTemplate(manager));
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "late-stale"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
    assertThat(manager.rollbacks).hasValue(1);
    assertThat(manager.commits).hasValue(0);
  }

  @Test void rejectsForeignBaselineBeforeGetterExecutionAndPersistence() {
    var getters = new AtomicInteger();
    Object foreign = new Object() { public String getPrivateContent() { getters.incrementAndGet(); return "private"; } };
    var baseline = mapper.createObjectNode().putPOJO("foreign", foreign);
    var service = service((operation, invocation) -> {}, validator(), (current, validation) -> seed(root, baseline),
        (tenant, environment, target, actor, key) -> {});
    assertThatThrownBy(() -> service.createDraft(principal, "ctx-1", root, "foreign"))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
    assertThat(getters).hasValue(0);
    verify(drafts, never()).save(any());
  }

  @Test void createsAndReplaysMixedNativePageAndResourceTableWithoutRecapture() {
    var page = new UiLayoutTarget("praxis-page-builder", "page");
    var mixed = new UiLayoutLifecycleInvocation("author", "tenant-a", "procurement", "lab", "ctx-1",
        new UiLayoutCompositionRegistration(page, List.of(page, root)));
    var nativeDocument = mapper.createObjectNode().put("kind", "praxis.page.editor");
    nativeDocument.putArray("widgets");
    var nativeSeed = new UiLayoutDraftWorkspaceSeed.TargetSeed(page,
        new UiLayoutAuthoringDocumentDescriptor("praxis.page.editor", "urn:page", "1"),
        new UiLayoutPatchDocumentDescriptor("urn:page:patch", "1"), "page-source", nativeDocument,
        UiLayoutMetadataTestFixtures.nativeMetadata(nativeDocument, "author", "procurement", "ctx-1"));
    var source = mock(UiLayoutDraftWorkspaceSource.class);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(nativeSeed,
        seed(root, mapper.createObjectNode().put("density", "comfortable")).targets().getFirst())));
    when(drafts.save(any())).thenAnswer(call -> call.getArgument(0));
    var service = service((current, context, target) -> mixed, (operation, current) -> {}, validator(), source,
        (tenant, environment, target, actor, key) -> {}, new TransactionTemplate(new ResourceTransactionManager(new Object())));
    var created = service.createDraft(principal, "ctx-1", page, "mixed");
    var persisted = org.mockito.ArgumentCaptor.forClass(UiLayoutDraft.class);
    verify(drafts).save(persisted.capture());
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant-a", "lab", page.componentType(), page.componentId(), "author", "mixed")).thenReturn(Optional.of(persisted.getValue()));
    assertThat(service.createDraft(principal, "ctx-1", page, "mixed")).isEqualTo(created);
    verify(source, times(1)).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    assertThat(((UiLayoutMetadataTestFixtures.MemoryStore) metadataStore).rows.values().stream().map(UiLayoutMetadataCapture::source).toList())
        .anyMatch(origin -> origin instanceof UiLayoutBaselineMetadataSeed.NativeDocumentSource)
        .anyMatch(origin -> origin instanceof UiLayoutBaselineMetadataSeed.OperationSource);
  }

  private UiLayoutLifecycleCommandService service(UiLayoutLifecycleInvocationProvider provider,
      UiLayoutLifecycleAdmission admission, UiLayoutLifecycleStructureValidator structure,
      UiLayoutDraftWorkspaceSource source, UiLayoutDraftCreationLock lock, TransactionTemplate transactions) {
    var definitions = mock(UiLayoutDefinitionRepository.class);
    var revisions = mock(UiLayoutRevisionRepository.class);
    var assignments = mock(UiLayoutAssignmentRevisionRepository.class);
    var hashes = new CanonicalJsonHashService(mapper);
    return UiLayoutLifecycleCommandService.assemble(definitions, revisions, assignments, drafts, releases,
        mock(UiLayoutReleaseMemberRepository.class), mock(UiLayoutReleaseReviewRepository.class),
        mock(UiLayoutReleaseApprovalRepository.class), mock(UiLayoutReleaseHeadRepository.class),
        mock(UiLayoutReleaseEventRepository.class), (tenantId, environment, target) -> {}, hashes, mapper,
        (release, members) -> {}, provider, admission, structure, source, lock, metadataStore, metadataGate, transactions, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
  }

  @Test void formLiteralNullStopsBeforeRevisionAndDraftWritesEvenWithPermissiveHost() throws Exception {
    var form = new UiLayoutTarget("praxis-dynamic-form", "test-form");
    var invocation = new UiLayoutLifecycleInvocation("author", "tenant-a", "procurement", "lab", "ctx-1",
        new UiLayoutCompositionRegistration(form, List.of(form)));
    var hashes = new CanonicalJsonHashService(mapper);
    var baseline = mapper.readTree("{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{\"title\":\"Original\"}}");
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(form,
        new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "test.form", "1"),
        new UiLayoutPatchDocumentDescriptor("test.patch", "praxis.ui-layout/v1"), "test-only:form", baseline,
        UiLayoutMetadataTestFixtures.metadata("author", "procurement", "ctx-1"))));
    var codec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
    var draft = UiLayoutDraft.builder().id(java.util.UUID.randomUUID()).tenantId("tenant-a").environment("lab")
        .rootComponentType(form.componentType()).rootComponentId(form.componentId()).state("DRAFT")
        .draftEtag(java.util.UUID.randomUUID()).draftDocument(codec.encode(codec.fromSeed(invocation, seed))).build();
    when(drafts.findForUpdateById(draft.getId())).thenReturn(Optional.of(draft));
    var revisions = mock(UiLayoutRevisionRepository.class);
    var manager = new ResourceTransactionManager(new Object());
    var service = UiLayoutLifecycleCommandService.assemble(mock(UiLayoutDefinitionRepository.class), revisions,
        mock(UiLayoutAssignmentRevisionRepository.class), drafts, releases, mock(UiLayoutReleaseMemberRepository.class),
        mock(UiLayoutReleaseReviewRepository.class), mock(UiLayoutReleaseApprovalRepository.class), mock(UiLayoutReleaseHeadRepository.class),
        mock(UiLayoutReleaseEventRepository.class), (t,e,target) -> {}, hashes, mapper, (r,m) -> {},
        (p,c,r) -> invocation, (o,i) -> {}, validator(), (i, validation) -> seed, (t,e,r,a,k) -> {}, metadataStore, metadataGate,
        new TransactionTemplate(manager), (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
    assertThatThrownBy(() -> service.createRevision(principal, "ctx-1", form, draft.getId(), draft.getDraftEtag().toString(),
        new UiLayoutLifecycleCommandService.RevisionInput(java.util.UUID.randomUUID(), form, "{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{\"title\":null}}", "test-only")))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
    verify(revisions, never()).save(any()); verify(drafts, never()).save(any());
    assertThat(manager.commits).hasValue(0); assertThat(manager.rollbacks).hasValue(1);
  }

  private UiLayoutLifecycleInvocation invocation() {
    return new UiLayoutLifecycleInvocation("author", "tenant-a", "procurement", "lab", "ctx-1",
        new UiLayoutCompositionRegistration(root, List.of(root)));
  }

  private UiLayoutDraftWorkspaceSeed seed(UiLayoutTarget target, JsonNode document) {
    return new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.authoring-document", "urn:praxis:table:authoring", "table-authoring/v3"),
        new UiLayoutPatchDocumentDescriptor("urn:praxis:table:layout-patch", "table-layout-patch/v1"),
        "table-source:orders:42", document, UiLayoutMetadataTestFixtures.metadata("author", "procurement", "ctx-1"))));
  }

  private UiLayoutLifecycleStructureValidator validator() {
    return new UiLayoutLifecycleStructureValidator() {
      @Override public void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baselineDocument, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateAuthoringRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baselineDocument, UiLayoutAuthoringDocumentDescriptor candidateDescriptor, JsonNode candidateDocument, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patchDocument, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutResolutionCandidate.LayerClass layerClass, UiLayoutAudienceSelector selector, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
      @Override public void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, org.praxisplatform.config.service.UiLayoutValidationContext validation) {}
    };
  }

  private static final class ResourceTransactionManager extends AbstractPlatformTransactionManager {
    private final Object resourceKey;
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger rollbacks = new AtomicInteger();
    private ResourceTransactionManager(Object resourceKey) { this.resourceKey = resourceKey; }
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
      TransactionSynchronizationManager.bindResource(resourceKey, new Object());
    }
    @Override protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); }
    @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks.incrementAndGet(); }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
      if (TransactionSynchronizationManager.hasResource(resourceKey)) TransactionSynchronizationManager.unbindResource(resourceKey);
    }
  }
}
