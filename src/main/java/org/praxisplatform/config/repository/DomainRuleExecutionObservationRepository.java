package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleExecutionObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only persistence access for redacted RuleSet execution evidence. */
public interface DomainRuleExecutionObservationRepository
    extends JpaRepository<DomainRuleExecutionObservation, UUID> {

  @Modifying
  @Query(value = """
      INSERT INTO domain_rule_execution_observation (
          observation_id, tenant_id, environment, rule_set_key, snapshot_id, snapshot_key,
          snapshot_content_hash, rule_set_version, activation_revision, outcome,
          duration_micros, observed_at, host_actor_ref, received_at)
      VALUES (
          :observationId, :tenantId, :environment, :ruleSetKey, :snapshotId, :snapshotKey,
          :snapshotContentHash, :ruleSetVersion, :activationRevision, :outcome,
          :durationMicros, :observedAt, :hostActorRef, :receivedAt)
      ON CONFLICT (observation_id) DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(
      @Param("observationId") UUID observationId,
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey,
      @Param("snapshotId") UUID snapshotId,
      @Param("snapshotKey") String snapshotKey,
      @Param("snapshotContentHash") String snapshotContentHash,
      @Param("ruleSetVersion") int ruleSetVersion,
      @Param("activationRevision") long activationRevision,
      @Param("outcome") String outcome,
      @Param("durationMicros") long durationMicros,
      @Param("observedAt") Instant observedAt,
      @Param("hostActorRef") String hostActorRef,
      @Param("receivedAt") Instant receivedAt);

  long countByTenantIdAndEnvironmentAndSnapshotKey(
      String tenantId, String environment, String snapshotKey);

  @Query("""
      select count(distinct observation.hostActorRef)
      from DomainRuleExecutionObservation observation
      where observation.tenantId = :tenantId
        and observation.environment = :environment
        and observation.snapshotKey = :snapshotKey
      """)
  long countDistinctHosts(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("snapshotKey") String snapshotKey);

  @Query("""
      select observation.outcome as outcome, count(observation) as total
      from DomainRuleExecutionObservation observation
      where observation.tenantId = :tenantId
        and observation.environment = :environment
        and observation.snapshotKey = :snapshotKey
      group by observation.outcome
      order by observation.outcome
      """)
  List<OutcomeCount> countOutcomes(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("snapshotKey") String snapshotKey);

  @Query("""
      select min(observation.observedAt) as firstObservedAt,
             max(observation.observedAt) as lastObservedAt
      from DomainRuleExecutionObservation observation
      where observation.tenantId = :tenantId
        and observation.environment = :environment
        and observation.snapshotKey = :snapshotKey
      """)
  ObservationWindow observationWindow(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("snapshotKey") String snapshotKey);

  interface OutcomeCount {
    String getOutcome();
    long getTotal();
  }

  interface ObservationWindow {
    Instant getFirstObservedAt();
    Instant getLastObservedAt();
  }
}
