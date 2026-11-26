package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleHostStatus;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.dto.DomainRuleHostStatusRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainRuleHostStatusRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;

@Tag("unit")
class DomainRuleHostStatusServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final String HASH = "A".repeat(64);
  private static final RuleRuntimeCompatibility COMPATIBILITY = RuleRuntimeCompatibility.current();
  private final DomainRuleHostStatusRepository statusRepository = mock(DomainRuleHostStatusRepository.class);
  private final DomainRuleSnapshotHeadRepository headRepository = mock(DomainRuleSnapshotHeadRepository.class);
  private final DomainRuleSnapshotRepository snapshotRepository = mock(DomainRuleSnapshotRepository.class);
  private final DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);
  private DomainRuleHostStatusService service;
  private DomainRuleGovernancePrincipal principal;

  @BeforeEach
  void setUp() {
    service = new DomainRuleHostStatusService(
        statusRepository, headRepository, snapshotRepository, snapshotService, new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2));
    principal = new DomainRuleGovernancePrincipal("tenant-a", "service:host-a", "dev");
  }

  @Test
  void ingestsStatusUsingOnlyServerOwnedScopeAndActor() {
    when(statusRepository.upsertIfNewer(any(), eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), org.mockito.ArgumentMatchers.eq("evaluate"), eq("service:host-a"), eq("snap-2"), eq(HASH), eq(7L), eq(true), eq("quickstart/1.0"), eq(COMPATIBILITY.engineContractVersion()), eq(COMPATIBILITY.jsonLogicDialectVersion()), eq(COMPATIBILITY.jsonLogicCorpusSha256()), eq("B".repeat(64)), eq(null), eq(NOW), eq(NOW)))
        .thenReturn(1);

    var response = service.ingest(
        new DomainRuleHostStatusRequest("benefit.eligibility", "evaluate", "snap-2", HASH, 7L, true, "quickstart/1.0", COMPATIBILITY.engineContractVersion(), COMPATIBILITY.jsonLogicDialectVersion(), COMPATIBILITY.jsonLogicCorpusSha256(), "B".repeat(64), null, NOW), principal);

    assertThat(response.updated()).isTrue();
    assertThat(response.observedAtUtc()).isEqualTo(NOW);
  }

  @Test
  void rejectsReadyStatusWithoutCompleteLoadedIdentity() {
    assertThatThrownBy(() -> service.ingest(
        new DomainRuleHostStatusRequest("benefit.eligibility", "evaluate", null, null, null, true, "quickstart/1.0", null, null, null, null, null, NOW), principal))
        .isInstanceOf(ConfigurationIngestionException.class)
        .hasMessageContaining("ready host requires");
  }

  @Test
  void deduplicatesRepeatedUnprovisionedHeadHeartbeatsUntilStateChanges() {
    when(statusRepository.upsertIfNewer(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    var firstNotFound = unreadyRequest("HEAD_NOT_FOUND", NOW.minusSeconds(120));
    var secondNotFound = unreadyRequest("HEAD_NOT_FOUND", NOW.minusSeconds(60));
    var thirdNotFoundLater = unreadyRequest("HEAD_NOT_FOUND", NOW.plusSeconds(180));
    var changedToNotLoaded = unreadyRequest("HEAD_NOT_LOADED", NOW.plusSeconds(210));
    var repeatedNotLoaded = unreadyRequest("HEAD_NOT_LOADED", NOW.plusSeconds(240));
    var changedToReady = readyRequest(NOW.plusSeconds(270));

    assertThat(service.ingest(firstNotFound, principal).updated()).isTrue();
    assertThat(service.ingest(secondNotFound, principal).updated()).isFalse();
    assertThat(service.ingest(thirdNotFoundLater, principal).updated()).isFalse();
    assertThat(service.ingest(changedToNotLoaded, principal).updated()).isTrue();
    assertThat(service.ingest(repeatedNotLoaded, principal).updated()).isFalse();
    assertThat(service.ingest(changedToReady, principal).updated()).isTrue();

    verify(statusRepository, times(3)).upsertIfNewer(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
        any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void deduplicatesUnchangedReadyHeartbeatsWithinHalfStaleWindowAndRefreshesAfterward() {
    when(statusRepository.upsertIfNewer(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
            any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    var first = readyRequest(NOW.minusSeconds(90));
    var withinWindow = readyRequest(NOW.minusSeconds(60));
    var afterHalfStaleWindow = readyRequest(NOW.minusSeconds(30));

    assertThat(service.ingest(first, principal).updated()).isTrue();
    assertThat(service.ingest(withinWindow, principal).updated()).isFalse();
    assertThat(service.ingest(afterHalfStaleWindow, principal).updated()).isTrue();

    verify(statusRepository, times(2)).upsertIfNewer(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
        any(), any(), any(), any(), any(), any(), any(), any());
  }

  private DomainRuleHostStatusRequest unreadyRequest(String failureCode, Instant observedAt) {
    return new DomainRuleHostStatusRequest(
        "benefit.eligibility", "evaluate", null, null, null, false,
        "quickstart/1.0", COMPATIBILITY.engineContractVersion(),
        COMPATIBILITY.jsonLogicDialectVersion(), COMPATIBILITY.jsonLogicCorpusSha256(),
        "B".repeat(64), failureCode, observedAt);
  }

  private DomainRuleHostStatusRequest readyRequest(Instant observedAt) {
    return new DomainRuleHostStatusRequest(
        "benefit.eligibility", "evaluate", "snap-2", HASH, 7L, true,
        "quickstart/1.0", COMPATIBILITY.engineContractVersion(),
        COMPATIBILITY.jsonLogicDialectVersion(), COMPATIBILITY.jsonLogicCorpusSha256(),
        "B".repeat(64), null, observedAt);
  }

  @Test
  void drillsMultipleHostsAcrossAlignmentSnapshotDriftRuntimeIncompatibilityUnavailabilityAndStaleness() {
    UUID snapshotId = UUID.randomUUID();
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder().operationKey("evaluate")
        .id(UUID.randomUUID()).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").activeSnapshotId(snapshotId)
        .activationRevision(7L).headEtag(UUID.randomUUID()).updatedAt(NOW).rowVersion(0L).build();
    DomainRuleSnapshot snapshot = DomainRuleSnapshot.builder().operationKey("evaluate")
        .id(snapshotId).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").snapshotKey("snap-2").contentHash(HASH)
        .compositionManifest("{\"implementationCatalogDigest\":\"" + "B".repeat(64) + "\"}").build();
    PublishedRuleSnapshot envelope = publishedSnapshot();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("tenant-a", "dev", "benefit.eligibility", "evaluate")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(snapshotId, "tenant-a", "dev", "benefit.eligibility", "evaluate")).thenReturn(Optional.of(snapshot));
    when(snapshotService.findSnapshot("tenant-a", "dev", "snap-2"))
        .thenReturn(Optional.of(new DomainRuleSnapshotStoredResponse(envelope, HASH)));
    when(statusRepository.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("tenant-a", "dev", "benefit.eligibility", "evaluate")).thenReturn(List.of(
            status(true, "snap-2", HASH, 7L, true, NOW.minusSeconds(10)),
            status(true, "snap-1", HASH, 6L, true, NOW.minusSeconds(20)),
            status(true, "snap-2", HASH, 7L, false, NOW.minusSeconds(25)),
            status(false, null, null, null, true, NOW.minusSeconds(30)),
            status(true, "snap-2", HASH, 7L, true, NOW.minusSeconds(121))));

    var summary = service.summarizeHead("benefit.eligibility", "evaluate", principal);

    assertThat(summary.totalHosts()).isEqualTo(5);
    assertThat(summary.alignedHosts()).isEqualTo(1);
    assertThat(summary.snapshotDriftedHosts()).isEqualTo(1);
    assertThat(summary.incompatibleHosts()).isEqualTo(1);
    assertThat(summary.unavailableHosts()).isEqualTo(1);
    assertThat(summary.staleHosts()).isEqualTo(1);
    assertThat(summary.expectedActivationRevision()).isEqualTo(7);
  }

  @Test
  void rejectsSummarizeHeadWhenOperationHeadIsWithdrawn() {
    DomainRuleSnapshotHead withdrawnHead = DomainRuleSnapshotHead.builder().operationKey("evaluate")
        .id(UUID.randomUUID()).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").activeSnapshotId(UUID.randomUUID())
        .activationRevision(8L).headEtag(UUID.randomUUID()).withdrawn(true).updatedAt(NOW).rowVersion(1L).build();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("tenant-a", "dev", "benefit.eligibility", "evaluate"))
        .thenReturn(Optional.of(withdrawnHead));

    assertThatThrownBy(() -> service.summarizeHead("benefit.eligibility", "evaluate", principal))
        .isInstanceOf(ConfigurationIngestionException.class)
        .hasMessageContaining("RuleSet head was not found");
  }

  @Test
  void alignsOnePointEightHostWithOnePointSevenSnapshotWhileFlaggingLegacyHostOnOnePointEightSnapshot() {
    assertThat(DomainRuleHostStatusService.isEngineContractCompatible("1.7", "1.8")).isTrue();
    assertThat(DomainRuleHostStatusService.isEngineContractCompatible("1.8", "1.8")).isTrue();
    assertThat(DomainRuleHostStatusService.isEngineContractCompatible("1.8", "1.7")).isFalse();
    assertThat(DomainRuleHostStatusService.isEngineContractCompatible("1.4", "1.8")).isFalse();

    UUID snapshotId = UUID.randomUUID();
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder().operationKey("evaluate")
        .id(UUID.randomUUID()).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").activeSnapshotId(snapshotId)
        .activationRevision(7L).headEtag(UUID.randomUUID()).updatedAt(NOW).rowVersion(0L).build();
    DomainRuleSnapshot snapshot = DomainRuleSnapshot.builder().operationKey("evaluate")
        .id(snapshotId).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").snapshotKey("snap-2").contentHash(HASH)
        .compositionManifest("{\"implementationCatalogDigest\":\"" + "B".repeat(64) + "\"}").build();
    PublishedRuleSnapshot baseEnvelope = publishedSnapshot();
    RuleRuntimeCompatibility compat17 = new RuleRuntimeCompatibility(
        "1.7", COMPATIBILITY.jsonLogicDialectVersion(), COMPATIBILITY.jsonLogicCorpusSha256());
    RuleSetDefinition ruleSet17 = new RuleSetDefinition(
        baseEnvelope.ruleSet().ref(), baseEnvelope.ruleSet().availableRoots(),
        baseEnvelope.ruleSet().slots(), baseEnvelope.ruleSet().bindings(),
        compat17, baseEnvelope.ruleSet().failPolicy(), baseEnvelope.ruleSet().errorDescriptors());
    PublishedRuleSnapshot envelope17 = new PublishedRuleSnapshot(
        baseEnvelope.snapshotContractVersion(), baseEnvelope.snapshotKey(),
        baseEnvelope.tenantId(), baseEnvelope.environment(), baseEnvelope.ownerServiceKey(),
        baseEnvelope.publicationRevision(), baseEnvelope.publishedAtUtc(), baseEnvelope.supersedesSnapshotKey(),
        baseEnvelope.requiredHostContractVersion(), baseEnvelope.validFromUtc(), baseEnvelope.validUntilUtc(),
        baseEnvelope.sources(), baseEnvelope.approvals(), ruleSet17);
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("tenant-a", "dev", "benefit.eligibility", "evaluate"))
        .thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(snapshotId, "tenant-a", "dev", "benefit.eligibility", "evaluate"))
        .thenReturn(Optional.of(snapshot));
    when(snapshotService.findSnapshot("tenant-a", "dev", "snap-2"))
        .thenReturn(Optional.of(new DomainRuleSnapshotStoredResponse(envelope17, HASH)));
    when(statusRepository.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("tenant-a", "dev", "benefit.eligibility", "evaluate"))
        .thenReturn(List.of(status(true, "snap-2", HASH, 7L, true, NOW.minusSeconds(10))));

    var summary = service.summarizeHead("benefit.eligibility", "evaluate", principal);
    assertThat(summary.alignedHosts()).isEqualTo(1);
    assertThat(summary.incompatibleHosts()).isEqualTo(0);
  }

  private DomainRuleHostStatus status(
      boolean ready, String snapshotKey, String hash, Long revision,
      boolean compatible, Instant observedAt) {
    return DomainRuleHostStatus.builder().operationKey("evaluate").id(UUID.randomUUID()).tenantId("tenant-a")
        .environment("dev").ruleSetKey("benefit.eligibility").hostActorRef(UUID.randomUUID().toString())
        .loadedSnapshotKey(snapshotKey).loadedSnapshotContentHash(hash).activationRevision(revision)
        .ready(ready).hostContractVersion("quickstart/1.0")
        .engineContractVersion(compatible ? COMPATIBILITY.engineContractVersion() : "incompatible")
        .jsonLogicDialectVersion(COMPATIBILITY.jsonLogicDialectVersion())
        .jsonLogicCorpusSha256(COMPATIBILITY.jsonLogicCorpusSha256())
        .implementationCatalogDigest("B".repeat(64))
        .observedAt(observedAt).receivedAt(NOW).build();
  }

  private PublishedRuleSnapshot publishedSnapshot() {
    var expression = new ObjectMapper().createObjectNode().put("var", "request.eligible");
    var ruleSet = new RuleSetDefinition(
        new RuleSetRef("benefits", "eligibility", "benefit.eligibility", "evaluate", 1),
        List.of("request"),
        List.of(new DecisionSlot(
            "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())),
        List.of(new DecisionBinding(
            "eligibility", "eligibility", DecisionSource.PRODUCT, null,
            RuleExecutorRef.jsonLogic(expression), List.of(), 10, true,
            RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"), null)),
        COMPATIBILITY, RuleFailPolicy.FAIL_CLOSED);
    return new PublishedRuleSnapshot(
        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION, "snap-2", "tenant-a", "dev",
        "quickstart", 1, NOW.toString(), null, "quickstart/1.0", NOW.toString(), null,
        List.of(new RuleSnapshotSource("definition-1", "eligibility", 1, "D".repeat(64))),
        List.of(new RuleSnapshotApproval(
            "approval-1", "RULE_DEFINITION_APPROVER", "reviewer", NOW.toString(), "E".repeat(64))),
        ruleSet);
  }
}
