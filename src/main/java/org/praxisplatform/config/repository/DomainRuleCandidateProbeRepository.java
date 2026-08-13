package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleCandidateProbe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Monotonic persistence for redacted candidate-preload probes. */
public interface DomainRuleCandidateProbeRepository
    extends JpaRepository<DomainRuleCandidateProbe, UUID> {
  List<DomainRuleCandidateProbe> findByRolloutId(UUID rolloutId);

  @Modifying
  @Query(value = """
      INSERT INTO domain_rule_candidate_probe (
        id, rollout_id, tenant_id, environment, rule_set_key, host_actor_ref,
        candidate_snapshot_id, candidate_snapshot_key, candidate_content_hash, preload_ready,
        host_contract_version, engine_contract_version, json_logic_dialect_version,
        json_logic_corpus_sha256, implementation_catalog_digest, failure_code,
        observed_at, received_at
      ) VALUES (
        :id, :rolloutId, :tenantId, :environment, :ruleSetKey, :hostActorRef,
        :candidateSnapshotId, :candidateSnapshotKey, :candidateContentHash, :preloadReady,
        :hostContractVersion, :engineContractVersion, :jsonLogicDialectVersion,
        :jsonLogicCorpusSha256, :implementationCatalogDigest, :failureCode,
        :observedAt, :receivedAt
      )
      ON CONFLICT (rollout_id, host_actor_ref)
      DO UPDATE SET
        candidate_snapshot_id = EXCLUDED.candidate_snapshot_id,
        candidate_snapshot_key = EXCLUDED.candidate_snapshot_key,
        candidate_content_hash = EXCLUDED.candidate_content_hash,
        preload_ready = EXCLUDED.preload_ready,
        host_contract_version = EXCLUDED.host_contract_version,
        engine_contract_version = EXCLUDED.engine_contract_version,
        json_logic_dialect_version = EXCLUDED.json_logic_dialect_version,
        json_logic_corpus_sha256 = EXCLUDED.json_logic_corpus_sha256,
        implementation_catalog_digest = EXCLUDED.implementation_catalog_digest,
        failure_code = EXCLUDED.failure_code,
        observed_at = EXCLUDED.observed_at,
        received_at = EXCLUDED.received_at
      WHERE domain_rule_candidate_probe.observed_at < EXCLUDED.observed_at
      """, nativeQuery = true)
  int upsertIfNewer(
      @Param("id") UUID id,
      @Param("rolloutId") UUID rolloutId,
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey,
      @Param("hostActorRef") String hostActorRef,
      @Param("candidateSnapshotId") UUID candidateSnapshotId,
      @Param("candidateSnapshotKey") String candidateSnapshotKey,
      @Param("candidateContentHash") String candidateContentHash,
      @Param("preloadReady") boolean preloadReady,
      @Param("hostContractVersion") String hostContractVersion,
      @Param("engineContractVersion") String engineContractVersion,
      @Param("jsonLogicDialectVersion") String jsonLogicDialectVersion,
      @Param("jsonLogicCorpusSha256") String jsonLogicCorpusSha256,
      @Param("implementationCatalogDigest") String implementationCatalogDigest,
      @Param("failureCode") String failureCode,
      @Param("observedAt") Instant observedAt,
      @Param("receivedAt") Instant receivedAt);
}
