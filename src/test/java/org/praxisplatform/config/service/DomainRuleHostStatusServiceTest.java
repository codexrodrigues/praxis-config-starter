package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    when(statusRepository.upsertIfNewer(
        any(), eq("tenant-a"), eq("dev"), eq("benefit.eligibility"), eq("service:host-a"),
        eq("snap-2"), eq(HASH), eq(7L), eq(true), eq("quickstart/1.0"),
        eq(COMPATIBILITY.engineContractVersion()), eq(COMPATIBILITY.jsonLogicDialectVersion()),
        eq(COMPATIBILITY.jsonLogicCorpusSha256()), eq("B".repeat(64)),
        eq(null), eq(NOW), eq(NOW)))
        .thenReturn(1);

    var response = service.ingest(
        new DomainRuleHostStatusRequest(
            "benefit.eligibility", "snap-2", HASH, 7L, true, "quickstart/1.0",
            COMPATIBILITY.engineContractVersion(), COMPATIBILITY.jsonLogicDialectVersion(),
            COMPATIBILITY.jsonLogicCorpusSha256(), "B".repeat(64), null, NOW), principal);

    assertThat(response.updated()).isTrue();
    assertThat(response.observedAtUtc()).isEqualTo(NOW);
  }

  @Test
  void rejectsReadyStatusWithoutCompleteLoadedIdentity() {
    assertThatThrownBy(() -> service.ingest(
        new DomainRuleHostStatusRequest(
            "benefit.eligibility", null, null, null, true, "quickstart/1.0",
            null, null, null, null, null, NOW), principal))
        .isInstanceOf(ConfigurationIngestionException.class)
        .hasMessageContaining("ready host requires");
  }

  @Test
  void drillsMultipleHostsAcrossAlignmentSnapshotDriftRuntimeIncompatibilityUnavailabilityAndStaleness() {
    UUID snapshotId = UUID.randomUUID();
    DomainRuleSnapshotHead head = DomainRuleSnapshotHead.builder()
        .id(UUID.randomUUID()).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").activeSnapshotId(snapshotId)
        .activationRevision(7L).headEtag(UUID.randomUUID()).updatedAt(NOW).rowVersion(0L).build();
    DomainRuleSnapshot snapshot = DomainRuleSnapshot.builder()
        .id(snapshotId).tenantId("tenant-a").environment("dev")
        .ruleSetKey("benefit.eligibility").snapshotKey("snap-2").contentHash(HASH)
        .compositionManifest("{\"implementationCatalogDigest\":\"" + "B".repeat(64) + "\"}").build();
    PublishedRuleSnapshot envelope = publishedSnapshot();
    when(headRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(head));
    when(snapshotRepository.findByIdAndTenantIdAndEnvironmentAndRuleSetKey(
        snapshotId, "tenant-a", "dev", "benefit.eligibility")).thenReturn(Optional.of(snapshot));
    when(snapshotService.findSnapshot("tenant-a", "dev", "snap-2"))
        .thenReturn(Optional.of(new DomainRuleSnapshotStoredResponse(envelope, HASH)));
    when(statusRepository.findByTenantIdAndEnvironmentAndRuleSetKey(
        "tenant-a", "dev", "benefit.eligibility")).thenReturn(List.of(
            status(true, "snap-2", HASH, 7L, true, NOW.minusSeconds(10)),
            status(true, "snap-1", HASH, 6L, true, NOW.minusSeconds(20)),
            status(true, "snap-2", HASH, 7L, false, NOW.minusSeconds(25)),
            status(false, null, null, null, true, NOW.minusSeconds(30)),
            status(true, "snap-2", HASH, 7L, true, NOW.minusSeconds(121))));

    var summary = service.summarizeHead("benefit.eligibility", principal);

    assertThat(summary.totalHosts()).isEqualTo(5);
    assertThat(summary.alignedHosts()).isEqualTo(1);
    assertThat(summary.snapshotDriftedHosts()).isEqualTo(1);
    assertThat(summary.incompatibleHosts()).isEqualTo(1);
    assertThat(summary.unavailableHosts()).isEqualTo(1);
    assertThat(summary.staleHosts()).isEqualTo(1);
    assertThat(summary.expectedActivationRevision()).isEqualTo(7);
  }

  private DomainRuleHostStatus status(
      boolean ready, String snapshotKey, String hash, Long revision,
      boolean compatible, Instant observedAt) {
    return DomainRuleHostStatus.builder().id(UUID.randomUUID()).tenantId("tenant-a")
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
            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT)),
        List.of(new DecisionBinding(
            "eligibility", "eligibility", DecisionSource.PRODUCT, null,
            RuleExecutorRef.jsonLogic(expression), List.of(), 10, true,
            RuleDecision.DENY, "NOT_ELIGIBLE", List.of("request.eligible"))),
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
