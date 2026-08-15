package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.praxisplatform.config.contract.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResultRequest;
import org.praxisplatform.config.contract.DomainRuleTestBaselineEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineResult;
import org.praxisplatform.config.contract.DomainRuleOperationalTestEvidence;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DomainRuleTestRunServiceTest {
  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID SCENARIO = UUID.randomUUID();
  private static final DomainRuleGovernancePrincipal PRINCIPAL = new DomainRuleGovernancePrincipal("tenant-a","host-a","dev");
  private DomainRuleTestRunRepository runs;
  private DomainRuleTestRunResultRepository results;
  private DomainRuleChangeWorkspaceRepository workspaces;
  private DomainRuleTestScenarioRepository scenarios;
  private DomainRuleTestRunService service;

  @BeforeEach void setup() {
    runs=mock(DomainRuleTestRunRepository.class); results=mock(DomainRuleTestRunResultRepository.class);
    workspaces=mock(DomainRuleChangeWorkspaceRepository.class); scenarios=mock(DomainRuleTestScenarioRepository.class);
    service=new DomainRuleTestRunService(runs,results,workspaces,scenarios,new ObjectMapper().findAndRegisterModules());
    when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-a",2L)));
    when(workspaces.findByIdForUpdate(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-a",2L)));
    when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scenarios.findById(SCENARIO)).thenReturn(Optional.of(DomainRuleTestScenario.builder()
        .id(SCENARIO).workspaceId(WORKSPACE).scenarioKey("happy").expectedDecision("ALLOW")
        .expectedOutput("{\"amount\":500}").expectedReasonCodes("[]")
        .expectedEffectIntents("[\"REGISTER_EXTRAORDINARY_GRANT\"]").build()));
  }

  @Test void recordsOnlySafeImmutableEvidence() {
    var response=service.record(WORKSPACE,request(2L,"A".repeat(64)),PRINCIPAL);
    assertThat(response.results()).singleElement().satisfies(item -> {
      assertThat(item.candidateDecision()).isEqualTo("ALLOW");
      assertThat(item.factsDigest()).isEqualTo("C".repeat(64));
      assertThat(item.candidateOutputMatchesExpected()).isTrue();
      assertThat(item.candidateReasonCodesMatchExpected()).isTrue();
      assertThat(item.candidateEffectsMatchExpected()).isTrue();
    });
    ArgumentCaptor<DomainRuleTestRun> run=ArgumentCaptor.forClass(DomainRuleTestRun.class);
    verify(runs).save(run.capture());
    assertThat(run.getValue().getResultSummary()).contains("scenarioCount").doesNotContain("facts");
    ArgumentCaptor<List<DomainRuleTestRunResult>> saved=ArgumentCaptor.forClass(List.class);
    verify(results).saveAll(saved.capture());
    assertThat(saved.getValue()).singleElement().extracting(DomainRuleTestRunResult::getFactsDigest).isEqualTo("C".repeat(64));
  }

  @Test void rejectsStaleWorkspaceAndCrossScopeWithoutWriting() {
    assertThatThrownBy(() -> service.record(WORKSPACE,request(1L,"A".repeat(64)),PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("CONFLICT");
    when(workspaces.findByIdForUpdate(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-b",2L)));
    assertThatThrownBy(() -> service.record(WORKSPACE,request(2L,"A".repeat(64)),PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("NOT_FOUND");
  }

  @Test void rejectsRecordingEvidenceAfterTheWorkspaceLeavesOpenAuthoring() {
    var submitted = workspace("tenant-a", 2L);
    submitted.setStatus("SUBMITTED");
    when(workspaces.findByIdForUpdate(WORKSPACE)).thenReturn(Optional.of(submitted));

    assertThatThrownBy(() -> service.record(WORKSPACE, request(2L, "A".repeat(64)), PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("only while the change workspace is OPEN");
  }

  @Test void recomputesSemanticMatchesFromPersistedScenarioExpectations() {
    when(scenarios.findById(SCENARIO)).thenReturn(Optional.of(DomainRuleTestScenario.builder()
        .id(SCENARIO).workspaceId(WORKSPACE).scenarioKey("happy").expectedDecision("ALLOW")
        .expectedOutput("{\"amount\":600}").expectedReasonCodes("[\"APPROVED\"]")
        .expectedEffectIntents("[\"REGISTER_EXTRAORDINARY_GRANT\"]").build()));

    var result = service.record(WORKSPACE, request(2L, "A".repeat(64)), PRINCIPAL)
        .results().getFirst();

    assertThat(result.candidateMatchesExpected()).isTrue();
    assertThat(result.candidateOutputMatchesExpected()).isFalse();
    assertThat(result.candidateReasonCodesMatchExpected()).isFalse();
    assertThat(result.candidateEffectsMatchExpected()).isTrue();
  }

  @Test void recordsSanitizedBaselineAndOperationalProvenance() {
    var baseline = new DomainRuleTestBaselineEvidence("LEGACY_ORACLE", "ergon:r013:matrix:case-01",
        "D".repeat(64), Instant.parse("2026-08-13T11:55:00Z"), "ELIGIBLE");
    var operational = new DomainRuleOperationalTestEvidence("UPDATE", "E".repeat(64),
        "F".repeat(64), true, false, true, "1".repeat(64), 1);
    var baselineResult = new DomainRuleTestBaselineResult("ALLOW",
        new ObjectMapper().createObjectNode().put("amount", 500), List.of(),
        List.of("REGISTER_EXTRAORDINARY_GRANT"), "2".repeat(64), "3".repeat(64), null);
    var base = request(2L, "A".repeat(64));
    var item = base.results().getFirst();
    var request = new DomainRuleTestRunRecordRequest(base.idempotencyKey(), base.workspaceRevision(), base.baseDefinitionHash(),
        base.evaluatedAtUtc(), base.userTimeZone(), base.activeSnapshotKey(),
        base.activeSnapshotContentHash(), base.activeActivationRevision(), baseline,
        List.of(new DomainRuleTestRunResultRequest(item.scenarioId(), item.scenarioKey(),
            item.candidateDecision(), item.activeDecision(), item.candidateOutput(), item.activeOutput(),
            item.candidateReasonCodes(), item.activeReasonCodes(), item.candidateEffectIntents(),
            item.activeEffectIntents(), item.candidatePlanDigest(), item.activePlanDigest(),
            item.factsDigest(), baselineResult, operational)));

    var response = service.record(WORKSPACE, request, PRINCIPAL);

    assertThat(response.baselineEvidence()).isEqualTo(baseline);
    assertThat(response.results().getFirst().baselineResult()).isEqualTo(baselineResult);
    assertThat(response.results().getFirst().candidateBaselineComparison()).isEqualTo("MATCH");
    assertThat(response.results().getFirst().operationalEvidence()).isEqualTo(operational);
    ArgumentCaptor<DomainRuleTestRun> run = ArgumentCaptor.forClass(DomainRuleTestRun.class);
    verify(runs).save(run.capture());
    assertThat(run.getValue().getBaselineEvidence()).contains("LEGACY_ORACLE").doesNotContain("facts");
  }

  @Test void rejectsContradictoryOperationalEvidence() {
    var base = request(2L, "A".repeat(64));
    var item = base.results().getFirst();
    var contradictory = new DomainRuleOperationalTestEvidence("CREATE", null, "F".repeat(64),
        true, true, false, null, 0);
    var request = new DomainRuleTestRunRecordRequest(base.idempotencyKey(), base.workspaceRevision(), base.baseDefinitionHash(),
        base.evaluatedAtUtc(), base.userTimeZone(), base.activeSnapshotKey(),
        base.activeSnapshotContentHash(), base.activeActivationRevision(), null,
        List.of(new DomainRuleTestRunResultRequest(item.scenarioId(), item.scenarioKey(),
            item.candidateDecision(), item.activeDecision(), item.candidateOutput(), item.activeOutput(),
            item.candidateReasonCodes(), item.activeReasonCodes(), item.candidateEffectIntents(),
            item.activeEffectIntents(), item.candidatePlanDigest(), item.activePlanDigest(),
            item.factsDigest(), null, contradictory)));

    assertThatThrownBy(() -> service.record(WORKSPACE, request, PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot report mutation and no-mutation together");
  }

  @Test void replaysTheSameSemanticCommandWithoutAppendingAnotherRun() {
    var request = request(2L, "A".repeat(64));

    var first = service.record(WORKSPACE, request, PRINCIPAL);
    ArgumentCaptor<DomainRuleTestRun> storedRun = ArgumentCaptor.forClass(DomainRuleTestRun.class);
    ArgumentCaptor<List<DomainRuleTestRunResult>> storedResults = ArgumentCaptor.forClass(List.class);
    verify(runs).save(storedRun.capture());
    verify(results).saveAll(storedResults.capture());
    when(runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        "tenant-a", "dev", WORKSPACE, request.idempotencyKey()))
        .thenReturn(Optional.of(storedRun.getValue()));
    when(results.findByTestRunIdOrderByScenarioKey(storedRun.getValue().getId()))
        .thenReturn(storedResults.getValue());

    var replay = service.record(WORKSPACE, request, PRINCIPAL);

    assertThat(replay.runId()).isEqualTo(first.runId());
    assertThat(replay.requestHash()).isEqualTo(first.requestHash());
    verify(runs, times(1)).save(any());
    verify(results, times(1)).saveAll(any());
  }

  @Test void normalizesTheIdempotencyKeyBeforeLookupAndPersistence() {
    var base = request(2L, "A".repeat(64));
    var padded = new DomainRuleTestRunRecordRequest(
        "  policy-studio:test-run:happy  ", base.workspaceRevision(), base.baseDefinitionHash(),
        base.evaluatedAtUtc(), base.userTimeZone(), base.activeSnapshotKey(),
        base.activeSnapshotContentHash(), base.activeActivationRevision(),
        base.baselineEvidence(), base.results());

    service.record(WORKSPACE, padded, PRINCIPAL);

    verify(runs).findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        "tenant-a", "dev", WORKSPACE, "policy-studio:test-run:happy");
    ArgumentCaptor<DomainRuleTestRun> storedRun = ArgumentCaptor.forClass(DomainRuleTestRun.class);
    verify(runs).save(storedRun.capture());
    assertThat(storedRun.getValue().getIdempotencyKey())
        .isEqualTo("policy-studio:test-run:happy");
  }

  @Test void resolvesAnExistingRetryReceiptOnlyInsideTheGovernedScope() {
    var persisted = DomainRuleTestRun.builder()
        .id(UUID.randomUUID()).workspaceId(WORKSPACE).tenantId("tenant-a").environment("dev")
        .idempotencyKey("policy-studio:test-run:happy").requestHash("F".repeat(64))
        .workspaceRevision(2L).baseDefinitionHash("A".repeat(64))
        .evaluatedAt(Instant.parse("2026-08-13T12:00:00Z")).userTimeZone("UTC")
        .activeActivationRevision(0L).resultSummary("{}").recordedBy("host-a")
        .recordedAt(Instant.parse("2026-08-13T12:00:01Z")).build();
    when(runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        "tenant-a", "dev", WORKSPACE, "policy-studio:test-run:happy"))
        .thenReturn(Optional.of(persisted));
    when(results.findByTestRunIdOrderByScenarioKey(persisted.getId())).thenReturn(List.of());

    assertThat(service.findByIdempotencyKey(
        WORKSPACE, " policy-studio:test-run:happy ", PRINCIPAL))
        .get().extracting(response -> response.runId()).isEqualTo(persisted.getId());

    when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-b", 2L)));
    assertThatThrownBy(() -> service.findByIdempotencyKey(
        WORKSPACE, "policy-studio:test-run:happy", PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("NOT_FOUND");
  }

  @Test void rejectsReusingAnIdempotencyKeyForDifferentEvidence() {
    var persisted = DomainRuleTestRun.builder()
        .id(UUID.randomUUID()).workspaceId(WORKSPACE).tenantId("tenant-a").environment("dev")
        .idempotencyKey("policy-studio:test-run:happy").requestHash("F".repeat(64)).build();
    when(runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        "tenant-a", "dev", WORKSPACE, "policy-studio:test-run:happy"))
        .thenReturn(Optional.of(persisted));

    assertThatThrownBy(() -> service.record(WORKSPACE, request(2L, "A".repeat(64)), PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("idempotencyKey was already used");
  }

  @Test void treatsAssertionOrderingAsTheSameSemanticIdempotentCommand() {
    var firstRequest = withCandidateReasons(request(2L, "A".repeat(64)), List.of("Z_REASON", "A_REASON"));
    var first = service.record(WORKSPACE, firstRequest, PRINCIPAL);
    ArgumentCaptor<DomainRuleTestRun> storedRun = ArgumentCaptor.forClass(DomainRuleTestRun.class);
    ArgumentCaptor<List<DomainRuleTestRunResult>> storedResults = ArgumentCaptor.forClass(List.class);
    verify(runs).save(storedRun.capture());
    verify(results).saveAll(storedResults.capture());
    when(runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        "tenant-a", "dev", WORKSPACE, firstRequest.idempotencyKey()))
        .thenReturn(Optional.of(storedRun.getValue()));
    when(results.findByTestRunIdOrderByScenarioKey(storedRun.getValue().getId()))
        .thenReturn(storedResults.getValue());

    var replay = service.record(WORKSPACE,
        withCandidateReasons(firstRequest, List.of("A_REASON", "Z_REASON")), PRINCIPAL);

    assertThat(replay.runId()).isEqualTo(first.runId());
    verify(runs, times(1)).save(any());
  }

  @Test void requiresAnIndependentBaselineLaneForEveryEligibleScenario() {
    var base = request(2L, "A".repeat(64));
    var eligible = new DomainRuleTestBaselineEvidence("LEGACY_ORACLE", "ergon:r013:matrix",
        "D".repeat(64), Instant.parse("2026-08-13T11:55:00Z"), "ELIGIBLE");
    var request = new DomainRuleTestRunRecordRequest(base.idempotencyKey(), base.workspaceRevision(),
        base.baseDefinitionHash(), base.evaluatedAtUtc(), base.userTimeZone(), base.activeSnapshotKey(),
        base.activeSnapshotContentHash(), base.activeActivationRevision(), eligible, base.results());

    assertThatThrownBy(() -> service.record(WORKSPACE, request, PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("baselineResult is required for every scenario");
  }

  @Test void boundsTheRunAndActiveSnapshotIdentityBeforePersistence() {
    var base = request(2L, "A".repeat(64));
    var oversized = new DomainRuleTestRunRecordRequest(
        base.idempotencyKey(), base.workspaceRevision(), base.baseDefinitionHash(),
        base.evaluatedAtUtc(), base.userTimeZone(), null, null, 0, null,
        java.util.Collections.nCopies(1_001, base.results().getFirst()));
    assertThatThrownBy(() -> service.record(WORKSPACE, oversized, PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("results exceeds 1000 scenarios");

    var longSnapshotKey = new DomainRuleTestRunRecordRequest(
        base.idempotencyKey(), base.workspaceRevision(), base.baseDefinitionHash(),
        base.evaluatedAtUtc(), base.userTimeZone(), "S".repeat(129), null, 0, null,
        base.results());
    assertThatThrownBy(() -> service.record(WORKSPACE, longSnapshotKey, PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("activeSnapshotKey is invalid");
  }

  private DomainRuleTestRunRecordRequest request(long revision,String hash) {
    return new DomainRuleTestRunRecordRequest("policy-studio:test-run:happy", revision,hash,
        Instant.parse("2026-08-13T12:00:00Z"),"UTC",null,null,0,null,
        List.of(new DomainRuleTestRunResultRequest(SCENARIO,"happy","ALLOW","TECHNICAL_ERROR",
            new ObjectMapper().createObjectNode().put("amount",500),null,
            List.of(),List.of("ACTIVE_SNAPSHOT_UNAVAILABLE"),
            List.of("REGISTER_EXTRAORDINARY_GRANT"),List.of(),
            "B".repeat(64),"0".repeat(64),"C".repeat(64),null,null)));
  }
  private DomainRuleTestRunRecordRequest withCandidateReasons(
      DomainRuleTestRunRecordRequest request, List<String> reasonCodes) {
    var item = request.results().getFirst();
    return new DomainRuleTestRunRecordRequest(
        request.idempotencyKey(), request.workspaceRevision(), request.baseDefinitionHash(),
        request.evaluatedAtUtc(), request.userTimeZone(), request.activeSnapshotKey(),
        request.activeSnapshotContentHash(), request.activeActivationRevision(),
        request.baselineEvidence(), List.of(new DomainRuleTestRunResultRequest(
            item.scenarioId(), item.scenarioKey(), item.candidateDecision(), item.activeDecision(),
            item.candidateOutput(), item.activeOutput(), reasonCodes, item.activeReasonCodes(),
            item.candidateEffectIntents(), item.activeEffectIntents(), item.candidatePlanDigest(),
            item.activePlanDigest(), item.factsDigest(), item.baselineResult(), item.operationalEvidence())));
  }
  private DomainRuleChangeWorkspace workspace(String tenant,long revision){return DomainRuleChangeWorkspace.builder()
      .id(WORKSPACE).tenantId(tenant).environment("dev").status("OPEN")
      .revision(revision).baseDefinitionHash("A".repeat(64)).build();}
}
