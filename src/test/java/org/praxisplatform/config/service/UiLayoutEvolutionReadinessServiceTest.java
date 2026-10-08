package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.dto.UiLayoutEvolutionReadinessReceipt.Code;

@Tag("unit")
class UiLayoutEvolutionReadinessServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutHistoricalEvidenceService history = mock(UiLayoutHistoricalEvidenceService.class);
  private final UiLayoutLifecycleInvocationProvider invocations = mock(UiLayoutLifecycleInvocationProvider.class);
  private final UiLayoutLifecycleAdmission admission = mock(UiLayoutLifecycleAdmission.class);
  private final UiLayoutDraftWorkspaceSource source = mock(UiLayoutDraftWorkspaceSource.class);
  private final UiLayoutCurrentEvolutionEvidenceAccess access = mock(UiLayoutCurrentEvolutionEvidenceAccess.class);
  private final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);
  private final Principal principal = () -> "author";
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final UiLayoutTarget removed = new UiLayoutTarget("praxis-dynamic-form", "old-form");
  private final UiLayoutTarget added = new UiLayoutTarget("praxis-dynamic-form", "new-form");
  private final UUID releaseId = UUID.randomUUID();
  private UiLayoutLifecycleInvocation invocation;
  private UiLayoutEvolutionReadinessService service;
  private UiLayoutHistoricalEvidenceReceipt historical;

  private void changeAfterFinalNativeCallback(java.util.concurrent.atomic.AtomicBoolean changed) {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> { if (calls.incrementAndGet() == 2) changed.set(true); return null; })
        .when(structure).validateReleaseTargets(eq(invocation), anyList(), any(UiLayoutValidationContext.class));
  }

  @Test void operationRevokedAfterFinalNativeCallbackWithholdsDiagnosis() {
    var revoked = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalNativeCallback(revoked);
    doAnswer(call -> {
      if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private-revocation");
      return null;
    }).when(admission).require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation);
    fails(UiLayoutLifecycleException.Code.DENIED);
    verify(source, times(1)).capture(eq(invocation), any());
    verify(admission, never()).require(eq(UiLayoutLifecycleOperation.CREATE_DRAFT), any());
  }

  @Test void contentRevokedAfterFinalNativeCallbackWithholdsDiagnosis() {
    var revoked = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalNativeCallback(revoked);
    doAnswer(call -> {
      if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private-content");
      return null;
    }).when(access).require(eq(invocation), any(), any(UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test void actorChangedAfterFinalNativeCallbackWithholdsDiagnosisWithSameContextVersion() {
    var changed = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalNativeCallback(changed);
    var other = new UiLayoutLifecycleInvocation("another-actor", invocation.tenantId(), invocation.administrativeUnit(),
        invocation.environment(), invocation.contextVersion(), invocation.composition());
    when(invocations.resolve(principal, "ctx", root)).thenAnswer(call -> changed.get() ? other : invocation);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }

  @Test void finalAuthorityLookupCannotAcceptAnExpiredAttempt() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var captured = new java.util.concurrent.atomic.AtomicReference<UiLayoutValidationContext>();
    doAnswer(call -> { captured.set(call.getArgument(2)); return null; })
        .when(access).require(eq(invocation), any(), any(UiLayoutValidationContext.class));
    when(invocations.resolve(principal, "ctx", root)).thenAnswer(call -> {
      if (calls.incrementAndGet() == 3) captured.get().attempt().close();
      return invocation;
    });
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(captured.get().attempt().remainingBudget()).isZero();
  }

  @Test void finalOperationTechnicalFailureIsSanitizedAndClosesTheAttempt() {
    var revoked = new java.util.concurrent.atomic.AtomicBoolean(); changeAfterFinalNativeCallback(revoked);
    var captured = new java.util.concurrent.atomic.AtomicReference<UiLayoutValidationContext>();
    doAnswer(call -> { captured.set(call.getArgument(2)); return null; })
        .when(access).require(eq(invocation), any(), any(UiLayoutValidationContext.class));
    doAnswer(call -> { if (revoked.get()) throw new IllegalStateException("private-credential"); return null; })
        .when(admission).require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation);
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(captured.get().attempt().remainingBudget()).isZero();
  }

  @Test void currentCaptureAndBothHistoricalReadsShareOneAttemptAndBudgetSelection() {
    var policies = new java.util.concurrent.atomic.AtomicInteger();
    service = new UiLayoutEvolutionReadinessService(history, invocations, admission, source, access, structure, mapper, hashes,
        (operation, inv) -> { policies.incrementAndGet(); return java.time.Duration.ofMinutes(1); });
    diagnose();
    var oldContexts = org.mockito.ArgumentCaptor.forClass(UiLayoutValidationContext.class);
    verify(history, times(2)).recoverWithin(eq(principal), eq("ctx"), eq(root), eq(releaseId), oldContexts.capture());
    var currentContext = org.mockito.ArgumentCaptor.forClass(UiLayoutValidationContext.class);
    verify(source).capture(eq(invocation), currentContext.capture());
    var current = currentContext.getValue();
    assertThat(current.purpose()).isEqualTo(UiLayoutValidationPurpose.EVOLUTION_CURRENT_READ);
    for (var old : oldContexts.getAllValues()) {
      assertThat(old.purpose()).isEqualTo(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
      assertThat(old.attempt()).isSameAs(current.attempt());
      assertThat(old.attempt().operation()).isEqualTo(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION);
    }
    assertThat(policies).hasValue(1);
    assertThat(current.attempt().remainingBudget()).isZero();
    verify(structure, never()).validateTarget(eq(invocation), eq(removed), any());
    verify(admission, never()).require(eq(UiLayoutLifecycleOperation.CREATE_DRAFT), any());
  }

  @Test void invalidatedHistoricalReadPreventsCurrentCapture() {
    when(history.recoverWithin(eq(principal), eq("ctx"), eq(root), eq(releaseId), any()))
        .thenAnswer(call -> { ((UiLayoutValidationContext) call.getArgument(4)).attempt().close(); return historical; });
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(source, structure, access);
  }

  @Test void lateCurrentAccessStopsBeforeNativeInspectionOfTheNextTarget() {
    doAnswer(call -> { ((UiLayoutValidationContext) call.getArgument(2)).attempt().close(); return null; })
        .when(access).require(eq(invocation), any(), any());
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verify(access, times(1)).require(eq(invocation), any(), any());
    verifyNoInteractions(structure);
  }

  @BeforeEach
  void setUp() throws Exception {
    invocation = invocation(List.of(root, added));
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation);
    historical = new UiLayoutHistoricalEvidenceReceipt(releaseId, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), List.of(old(root), old(removed)));
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(seed(root, "1"), seed(added, "1"))));
    service = service(admission, access);
  }

  private UiLayoutLifecycleInvocation invocation(List<UiLayoutTarget> targets) {
    return new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", new UiLayoutCompositionRegistration(root, targets));
  }

  private UiLayoutHistoricalEvidenceReceipt.Target old(UiLayoutTarget target) throws Exception {
    var doc = mapper.readTree("{\"kind\":\"native\",\"config\":{\"header\":\"same-value-pin-possible\"}}");
    var evidence = new UiLayoutDraftWorkspaceReceipt.AuthoringDocument("private-source-ref", hashes.sha256Exact(doc), doc);
    return new UiLayoutHistoricalEvidenceReceipt.Target(target, "native", "urn:native", "1", "urn:patch", "1",
        evidence, evidence, UUID.randomUUID(), UUID.randomUUID(),
        new UiLayoutHistoricalEvidenceReceipt.PatchDocument(hashes.sha256Exact(doc), doc));
  }

  private UiLayoutDraftWorkspaceSeed.TargetSeed seed(UiLayoutTarget target, String version) throws Exception {
    return new UiLayoutDraftWorkspaceSeed.TargetSeed(target, new UiLayoutAuthoringDocumentDescriptor("native", "urn:native", version),
        new UiLayoutPatchDocumentDescriptor("urn:patch", "1"), "private-current-ref",
        mapper.readTree("{\"kind\":\"native\",\"config\":{\"header\":\"current-secret-value\"}}"), UiLayoutMetadataTestFixtures.metadata());
  }

  private UiLayoutEvolutionReadinessService service(UiLayoutLifecycleAdmission gate, UiLayoutCurrentEvolutionEvidenceAccess content) {
    return new UiLayoutEvolutionReadinessService(history, invocations, gate, source, content, structure, mapper, hashes, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
  }

  private UiLayoutEvolutionReadinessReceipt diagnose() { return service.diagnose(principal, "ctx", root, releaseId); }

  private com.fasterxml.jackson.databind.JsonNode nativeFixture(int index) throws Exception {
    return mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
        "docs/ai/contracts/table-native-revision-conformance.v1.json"))).path("cases").get(index);
  }

  private void nativeHistory(com.fasterxml.jackson.databind.JsonNode item, String version) {
    var b0 = item.path("baseline");
    var c0 = item.path("command").path("authoringDocument");
    var patch = item.path("expectedPatch");
    var old = new UiLayoutHistoricalEvidenceReceipt.Target(root, "praxis.table.editor", "fixture.table", version,
        "fixture.patch", "praxis.ui-layout/v1",
        new UiLayoutDraftWorkspaceReceipt.AuthoringDocument("private-native-source", hashes.sha256Exact(b0), b0),
        new UiLayoutDraftWorkspaceReceipt.AuthoringDocument(null, hashes.sha256Exact(c0), c0), UUID.randomUUID(), UUID.randomUUID(),
        new UiLayoutHistoricalEvidenceReceipt.PatchDocument(hashes.sha256Exact(patch), patch));
    historical = new UiLayoutHistoricalEvidenceReceipt(releaseId, historical.sourceDraftRef(), historical.sourceDraftEtag(),
        historical.freezeCommandRef(), List.of(old));
    invocation = invocation(List.of(root));
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation);
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "fixture.table", version),
        new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1"), "private-current-source", b0, UiLayoutMetadataTestFixtures.metadata()))));
  }

  @Test
  void diagnosesAllTypeScriptFixtureDocumentRelationsWithoutAttestingProvenanceOrApply() throws Exception {
    for (int i = 0; i < 4; i++) {
      nativeHistory(nativeFixture(i), "1");
      var result = diagnose();
      assertThat(result.diagnostics()).extracting(UiLayoutEvolutionReadinessReceipt.Diagnostic::code)
          .containsExactly(Code.NATIVE_DOCUMENT_RELATION_REPRODUCED, Code.INTENT_PROVENANCE_UNVERIFIED, Code.SOURCE_FENCING_UNAVAILABLE);
      assertThat(result.applyAllowed()).isFalse();
      assertThat(mapper.writeValueAsString(result)).doesNotContain("private-native-source", "private-current-source", "resourcePath", "240px", "columnProjection");
    }
  }

  @Test
  void equalDocumentsWithMissingAuthoredPinAreUnverifiableEvenWithCorrectHashes() throws Exception {
    var item = nativeFixture(0);
    ((com.fasterxml.jackson.databind.node.ObjectNode)item.path("expectedPatch")
        .path("columnProjection").path("overrides").path("a")).remove("header");
    nativeHistory(item, "1");
    assertThat(historical.targets().getFirst().baseline().contentHash()).isEqualTo(historical.targets().getFirst().working().contentHash());
    assertThat(diagnose().diagnostics()).extracting(UiLayoutEvolutionReadinessReceipt.Diagnostic::code)
        .contains(Code.NATIVE_DOCUMENT_RELATION_UNVERIFIABLE, Code.INTENT_PROVENANCE_UNVERIFIED)
        .doesNotContain(Code.NATIVE_DOCUMENT_RELATION_REPRODUCED);
  }

  @Test
  void correctlyHashedButDivergentPatchRequiresReviewWithoutExposingValues() throws Exception {
    var item = nativeFixture(0);
    ((com.fasterxml.jackson.databind.node.ObjectNode)item.path("expectedPatch")).put("private-invalid-field", "secret-value");
    nativeHistory(item, "1");
    var result = diagnose();
    assertThat(result.diagnostics()).contains(new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.NATIVE_DOCUMENT_RELATION_UNVERIFIABLE, root));
    assertThat(mapper.writeValueAsString(result)).doesNotContain("private-invalid-field", "secret-value");
    assertThat(result.applyAllowed()).isFalse();
  }

  @Test
  void unsupportedHistoricalTableVersionHasNoFallbackOrPositiveRelationCode() throws Exception {
    nativeHistory(nativeFixture(0), "2");
    assertThat(diagnose().diagnostics()).extracting(UiLayoutEvolutionReadinessReceipt.Diagnostic::code)
        .contains(Code.NATIVE_DOCUMENT_RELATION_UNVERIFIABLE).doesNotContain(Code.NATIVE_DOCUMENT_RELATION_REPRODUCED);
  }

  @Test
  void currentDescriptorChangeDoesNotReplaceTheHistoricalInterpreterOrProveCompatibility() throws Exception {
    nativeHistory(nativeFixture(0), "1");
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "fixture.table", "2"),
        new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1"), "current", nativeFixture(0).path("baseline"), UiLayoutMetadataTestFixtures.metadata()))));
    var result = diagnose();
    assertThat(result.diagnostics()).extracting(UiLayoutEvolutionReadinessReceipt.Diagnostic::code)
        .contains(Code.NATIVE_CONTRACT_CHANGED, Code.NATIVE_DOCUMENT_RELATION_REPRODUCED, Code.INTENT_PROVENANCE_UNVERIFIED);
    assertThat(result.applyAllowed()).isFalse();
  }

  @Test
  void removedHistoricalTableKeepsItsRelationDiagnosisWithoutCurrentAdmission() throws Exception {
    nativeHistory(nativeFixture(0), "1");
    var previous = historical.targets().getFirst();
    var retired = new UiLayoutTarget("praxis-table", "retired-table");
    var retiredEvidence = new UiLayoutHistoricalEvidenceReceipt.Target(retired, previous.authoringDocumentType(),
        previous.authoringSchemaRef(), previous.authoringSchemaVersion(), previous.patchSchemaRef(), previous.patchSchemaVersion(),
        previous.baseline(), previous.working(), UUID.randomUUID(), UUID.randomUUID(), previous.revisionPatch());
    historical = new UiLayoutHistoricalEvidenceReceipt(releaseId, historical.sourceDraftRef(), historical.sourceDraftEtag(),
        historical.freezeCommandRef(), List.of(previous, retiredEvidence));
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical);
    var result = diagnose();
    assertThat(result.diagnostics()).contains(new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.TARGET_REMOVED, retired),
        new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.NATIVE_DOCUMENT_RELATION_REPRODUCED, retired));
    verify(structure, never()).validateTarget(any(), eq(retired), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    assertThat(result.applyAllowed()).isFalse();
  }

  @Test
  void finalHistoricalAccessRevocationWithholdsEvenAReproducedNativeRelation() throws Exception {
    nativeHistory(nativeFixture(0), "1");
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical)
        .thenThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private revoked reason"));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  private void fails(UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(this::diagnose).isInstanceOf(UiLayoutLifecycleException.class)
        .hasMessage("Evolution readiness evidence is unavailable.")
        .extracting(e -> ((UiLayoutLifecycleException) e).getCode()).isEqualTo(code);
  }

  @Test
  void reportsOrderedUnionAndCompositionChangesWithoutCandidateOrValues() throws Exception {
    var result = diagnose();
    assertThat(result.targets()).extracting(UiLayoutEvolutionReadinessReceipt.TargetEvidence::target).containsExactly(root, removed, added);
    assertThat(result.diagnostics()).contains(new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.TARGET_REMOVED, removed),
        new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.TARGET_ADDED, added));
    assertThat(result.targets().get(1).currentHash()).isNull();
    assertThat(result.targets().get(2).baselineHash()).isNull();
    assertThat(result.applyAllowed()).isFalse();
    assertThat(mapper.writeValueAsString(result)).doesNotContain("current-secret-value", "same-value-pin-possible", "private-source-ref", "private-current-ref", "candidate", "document");
    verify(admission, times(3)).require(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION, invocation);
    verify(history, times(2)).recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }

  @Test
  void equalNativeDocumentsNeverProveNoIntentOrAllowApply() {
    invocation = invocation(List.of(root));
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation);
    historical = new UiLayoutHistoricalEvidenceReceipt(releaseId, historical.sourceDraftRef(), historical.sourceDraftEtag(),
        historical.freezeCommandRef(), List.of(historical.targets().getFirst()));
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical);
    var old = historical.targets().getFirst();
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("native", "urn:native", "1"), new UiLayoutPatchDocumentDescriptor("urn:patch", "1"), "b1", old.baseline().document(), UiLayoutMetadataTestFixtures.metadata()))));
    var result = diagnose();
    assertThat(result.targets().getFirst().currentHash()).isEqualTo(old.baseline().contentHash());
    assertThat(result.diagnostics()).extracting(UiLayoutEvolutionReadinessReceipt.Diagnostic::code)
        .containsExactly(Code.INTENT_PROVENANCE_UNVERIFIED, Code.SOURCE_FENCING_UNAVAILABLE);
    assertThat(result.applyAllowed()).isFalse();
  }

  @Test
  void changedNativeDescriptorRequiresReview() throws Exception {
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(seed(root, "2"), seed(added, "1"))));
    assertThat(diagnose().diagnostics()).contains(new UiLayoutEvolutionReadinessReceipt.Diagnostic(Code.NATIVE_CONTRACT_CHANGED, root));
  }

  @Test
  void missingAdmissionOrCurrentAccessDeniesBeforeSources() {
    service = service(null, access); fails(UiLayoutLifecycleException.Code.DENIED);
    service = service(admission, null); fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(history, source, access);
  }

  @Test
  void missingSessionOrStaleContextDoesNotReadEvidence() {
    assertThatThrownBy(() -> service.diagnose(null, "ctx", root, releaseId)).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(e -> ((UiLayoutLifecycleException) e).getCode()).isEqualTo(UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    when(invocations.resolve(principal, "ctx", root)).thenReturn(null);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    verifyNoInteractions(history, source, admission);
  }

  @Test
  void historyDenialPreventsCurrentSourceAccessAndHidesReason() {
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private policy"));
    fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(source, access);
  }

  @Test
  void incompleteOrReorderedCurrentSourceFailsClosed() throws Exception {
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(seed(root, "1"))));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(new UiLayoutDraftWorkspaceSeed(List.of(seed(added, "1"), seed(root, "1"))));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(access);
  }

  @Test
  void deniedCurrentChildWithholdsEntireResult() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private membership")).when(access).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test
  void sourceFailureDoesNotFabricateCurrentBase() {
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenThrow(new IllegalStateException("private connection details"));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    doReturn(null).when(source).capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }

  @Test
  void contextChangedDuringCaptureDiscardsResult() {
    when(invocations.resolve(principal, "ctx", root)).thenReturn(invocation, null);
    fails(UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }

  @Test
  void historicalAccessRevocationBeforeReturnDiscardsResult() {
    when(history.recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(historical)
        .thenThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked"));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test
  void receiptCannotBeUsedToDeclareApplyAuthorized() {
    assertThatThrownBy(() -> new UiLayoutEvolutionReadinessReceipt(releaseId, UUID.randomUUID(), "ctx", List.of(), List.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesOnlyCurrentTargetsAndNativeBaselinesInRegistrationOrderTwice() {
    diagnose();
    var ordered = inOrder(structure);
    for (int pass = 0; pass < 2; pass++) {
      ordered.verify(structure).validateTarget(org.mockito.ArgumentMatchers.eq(invocation), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
      ordered.verify(structure).validateAuthoringBaseline(eq(invocation), eq(root), eq(new UiLayoutAuthoringDocumentDescriptor("native", "urn:native", "1")), argThat(doc -> "current-secret-value".equals(doc.path("config").path("header").asText())), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
      ordered.verify(structure).validateTarget(org.mockito.ArgumentMatchers.eq(invocation), org.mockito.ArgumentMatchers.eq(added), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
      ordered.verify(structure).validateAuthoringBaseline(eq(invocation), eq(added), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
      ordered.verify(structure).validateReleaseTargets(org.mockito.ArgumentMatchers.eq(invocation), org.mockito.ArgumentMatchers.eq(List.of(root, added)), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    }
    verifyNoMoreInteractions(structure);
  }

  @Test
  void absentNativeValidatorWithholdsReceipt() {
    service = new UiLayoutEvolutionReadinessService(history, invocations, admission, source, access, null, mapper, hashes, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
    fails(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }

  @Test
  void invalidCurrentNativeChildWithholdsWholeReceiptAndSanitizesReason() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_REQUEST, "private field identity"))
        .when(structure).validateAuthoringBaseline(eq(invocation), eq(added), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    verify(structure, never()).validateReleaseTargets(any(), anyList(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    verify(history).recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }

  @Test
  void compositionPolicyRejectionWithholdsResult() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private composition policy"))
        .when(structure).validateReleaseTargets(org.mockito.ArgumentMatchers.eq(invocation), org.mockito.ArgumentMatchers.eq(List.of(root, added)), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
  }

  @Test
  void policyRevokedDuringFinalNativeValidationDiscardsResult() {
    doNothing().doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked field access"))
        .when(structure).validateAuthoringBaseline(eq(invocation), eq(added), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
    verify(history, times(1)).recoverWithin(org.mockito.ArgumentMatchers.eq(principal), org.mockito.ArgumentMatchers.eq("ctx"), org.mockito.ArgumentMatchers.eq(root), org.mockito.ArgumentMatchers.eq(releaseId), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
  }

  @Test
  void validatorCannotMutateCapturedDocumentOrReturnedHash() throws Exception {
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(seed(root, "1"), seed(added, "1")));
    when(source.capture(any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class))).thenReturn(seed);
    var expected = hashes.sha256Exact(seed.targets().getFirst().document());
    doAnswer(call -> {
      ((com.fasterxml.jackson.databind.node.ObjectNode) call.getArgument(3)).remove("config");
      return null;
    }).when(structure).validateAuthoringBaseline(any(), any(), any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    assertThat(diagnose().targets().getFirst().currentHash()).isEqualTo(expected);
    assertThat(seed.targets().getFirst().document().has("config")).isTrue();
  }

  @Test
  void contentAuthorizationPrecedesNativeInspection() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private"))
        .when(access).require(any(), any(), org.mockito.ArgumentMatchers.any(org.praxisplatform.config.service.UiLayoutValidationContext.class));
    fails(UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(structure);
  }
}
