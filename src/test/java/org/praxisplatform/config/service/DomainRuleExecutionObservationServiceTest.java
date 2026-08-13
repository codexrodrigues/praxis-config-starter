package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleExecutionObservation;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchRequest;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainRuleExecutionObservationRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotEventRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;

@Tag("unit")
class DomainRuleExecutionObservationServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-13T15:00:00Z");
  private static final String HASH = "A".repeat(64);
  private static final DomainRuleGovernancePrincipal PRINCIPAL =
      new DomainRuleGovernancePrincipal("tenant-a", "service:quickstart", "prod");

  private DomainRuleSnapshotRepository snapshotRepository;
  private DomainRuleSnapshotEventRepository eventRepository;
  private DomainRuleExecutionObservationRepository observationRepository;
  private DomainRuleExecutionObservationService service;
  private DomainRuleSnapshot snapshot;

  @BeforeEach
  void setUp() {
    snapshotRepository = mock(DomainRuleSnapshotRepository.class);
    eventRepository = mock(DomainRuleSnapshotEventRepository.class);
    observationRepository = mock(DomainRuleExecutionObservationRepository.class);
    service = new DomainRuleExecutionObservationService(
        snapshotRepository,
        eventRepository,
        observationRepository,
        Clock.fixed(NOW, ZoneOffset.UTC));
    snapshot = DomainRuleSnapshot.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("prod")
        .snapshotKey("snapshot-7")
        .ruleSetKey("grant-rules")
        .ruleSetVersion(7)
        .contentHash(HASH)
        .build();
    when(snapshotRepository.findByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", "snapshot-7")).thenReturn(Optional.of(snapshot));
    when(eventRepository.existsByTenantIdAndEnvironmentAndToSnapshotIdAndActivationRevision(
        "tenant-a", "prod", snapshot.getId(), 9L)).thenReturn(true);
  }

  @Test
  void ingestsExactSnapshotEvidenceWithoutBusinessPayload() {
    when(observationRepository.insertIfAbsent(
        any(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyInt(),
        anyLong(), anyString(), anyLong(), any(), anyString(), any())).thenReturn(1);

    var response = service.ingest(batch(observation(UUID.randomUUID())), PRINCIPAL);

    assertThat(response.acceptedCount()).isEqualTo(1);
    assertThat(response.duplicateCount()).isZero();
  }

  @Test
  void treatsAnExactRetryAsDuplicateAndRejectsConflictingReuse() {
    UUID id = UUID.randomUUID();
    DomainRuleExecutionObservationRequest request = observation(id);
    when(observationRepository.insertIfAbsent(
        any(), anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyInt(),
        anyLong(), anyString(), anyLong(), any(), anyString(), any())).thenReturn(0);
    DomainRuleExecutionObservation existing = persisted(request);
    when(observationRepository.findById(id)).thenReturn(Optional.of(existing));

    assertThat(service.ingest(batch(request), PRINCIPAL).duplicateCount()).isEqualTo(1);

    existing.setOutcome("ALLOW");
    assertThatThrownBy(() -> service.ingest(batch(request), PRINCIPAL))
        .isInstanceOf(ConfigurationIngestionException.class)
        .hasMessageContaining("different execution evidence");
  }

  @Test
  void rejectsUnknownActivationHashFutureTimeAndOversizedBatch() {
    DomainRuleExecutionObservationRequest base = observation(UUID.randomUUID());
    when(eventRepository.existsByTenantIdAndEnvironmentAndToSnapshotIdAndActivationRevision(
        "tenant-a", "prod", snapshot.getId(), 9L)).thenReturn(false);
    assertThatThrownBy(() -> service.ingest(batch(base), PRINCIPAL))
        .hasMessageContaining("activationRevision");

    var wrongHash = new DomainRuleExecutionObservationRequest(
        UUID.randomUUID(), "snapshot-7", "B".repeat(64), 9, "ALLOW", 10, NOW);
    assertThatThrownBy(() -> service.ingest(batch(wrongHash), PRINCIPAL))
        .hasMessageContaining("snapshotContentHash");

    var future = new DomainRuleExecutionObservationRequest(
        UUID.randomUUID(), "snapshot-7", HASH, 9, "ALLOW", 10, NOW.plusSeconds(301));
    assertThatThrownBy(() -> service.ingest(batch(future), PRINCIPAL))
        .hasMessageContaining("future");

    List<DomainRuleExecutionObservationRequest> tooMany = java.util.stream.IntStream.range(0, 101)
        .mapToObj(ignored -> observation(UUID.randomUUID()))
        .toList();
    assertThatThrownBy(() -> service.ingest(
        new DomainRuleExecutionObservationBatchRequest(tooMany), PRINCIPAL))
        .hasMessageContaining("between 1 and 100");
  }

  @Test
  void projectsOnlySafeAggregatesForOneScopedSnapshot() {
    var deny = mock(DomainRuleExecutionObservationRepository.OutcomeCount.class);
    when(deny.getOutcome()).thenReturn("DENY");
    when(deny.getTotal()).thenReturn(3L);
    var window = mock(DomainRuleExecutionObservationRepository.ObservationWindow.class);
    when(window.getFirstObservedAt()).thenReturn(NOW.minusSeconds(60));
    when(window.getLastObservedAt()).thenReturn(NOW);
    when(observationRepository.countOutcomes("tenant-a", "prod", "snapshot-7"))
        .thenReturn(List.of(deny));
    when(observationRepository.observationWindow("tenant-a", "prod", "snapshot-7"))
        .thenReturn(window);
    when(observationRepository.countByTenantIdAndEnvironmentAndSnapshotKey(
        "tenant-a", "prod", "snapshot-7")).thenReturn(3L);
    when(observationRepository.countDistinctHosts("tenant-a", "prod", "snapshot-7"))
        .thenReturn(2L);

    var summary = service.summary("snapshot-7", PRINCIPAL);

    assertThat(summary.totalObservations()).isEqualTo(3);
    assertThat(summary.distinctHosts()).isEqualTo(2);
    assertThat(summary.outcomeCounts()).containsEntry("DENY", 3L).containsEntry("ALLOW", 0L);
    assertThat(summary.toString()).doesNotContain("service:quickstart", "facts", "reasonCodes");
  }

  private DomainRuleExecutionObservationBatchRequest batch(
      DomainRuleExecutionObservationRequest observation) {
    return new DomainRuleExecutionObservationBatchRequest(List.of(observation));
  }

  private DomainRuleExecutionObservationRequest observation(UUID id) {
    return new DomainRuleExecutionObservationRequest(
        id, "snapshot-7", HASH, 9, "DENY", 1200, NOW.minusSeconds(1));
  }

  private DomainRuleExecutionObservation persisted(DomainRuleExecutionObservationRequest request) {
    return DomainRuleExecutionObservation.builder()
        .observationId(request.observationId())
        .tenantId(PRINCIPAL.tenantId())
        .environment(PRINCIPAL.environment())
        .ruleSetKey(snapshot.getRuleSetKey())
        .snapshotId(snapshot.getId())
        .snapshotKey(snapshot.getSnapshotKey())
        .snapshotContentHash(snapshot.getContentHash())
        .ruleSetVersion(snapshot.getRuleSetVersion())
        .activationRevision(request.activationRevision())
        .outcome(request.outcome())
        .durationMicros(request.durationMicros())
        .observedAt(request.observedAtUtc())
        .hostActorRef(PRINCIPAL.actorRef())
        .receivedAt(NOW)
        .build();
  }
}
