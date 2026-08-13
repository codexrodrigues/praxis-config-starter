package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleHostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for replaceable, monotonic host heartbeats. */
public interface DomainRuleHostStatusRepository extends JpaRepository<DomainRuleHostStatus, UUID> {
  List<DomainRuleHostStatus> findByTenantIdAndEnvironmentAndRuleSetKey(
      String tenantId, String environment, String ruleSetKey);

  @Modifying
  @Query(value = """
      INSERT INTO domain_rule_host_status (
        id, tenant_id, environment, rule_set_key, host_actor_ref,
        loaded_snapshot_key, loaded_snapshot_content_hash, activation_revision,
        ready, host_contract_version, engine_contract_version, json_logic_dialect_version,
        json_logic_corpus_sha256, implementation_catalog_digest,
        failure_code, observed_at, received_at
      ) VALUES (
        :id, :tenantId, :environment, :ruleSetKey, :hostActorRef,
        :snapshotKey, :contentHash, :activationRevision,
        :ready, :contractVersion, :engineContractVersion, :dialectVersion,
        :corpusSha256, :catalogDigest, :failureCode, :observedAt, :receivedAt
      )
      ON CONFLICT (tenant_id, environment, rule_set_key, host_actor_ref)
      DO UPDATE SET
        loaded_snapshot_key = EXCLUDED.loaded_snapshot_key,
        loaded_snapshot_content_hash = EXCLUDED.loaded_snapshot_content_hash,
        activation_revision = EXCLUDED.activation_revision,
        ready = EXCLUDED.ready,
        host_contract_version = EXCLUDED.host_contract_version,
        engine_contract_version = EXCLUDED.engine_contract_version,
        json_logic_dialect_version = EXCLUDED.json_logic_dialect_version,
        json_logic_corpus_sha256 = EXCLUDED.json_logic_corpus_sha256,
        implementation_catalog_digest = EXCLUDED.implementation_catalog_digest,
        failure_code = EXCLUDED.failure_code,
        observed_at = EXCLUDED.observed_at,
        received_at = EXCLUDED.received_at
      WHERE domain_rule_host_status.observed_at < EXCLUDED.observed_at
      """, nativeQuery = true)
  int upsertIfNewer(
      @Param("id") UUID id,
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("ruleSetKey") String ruleSetKey,
      @Param("hostActorRef") String hostActorRef,
      @Param("snapshotKey") String snapshotKey,
      @Param("contentHash") String contentHash,
      @Param("activationRevision") Long activationRevision,
      @Param("ready") boolean ready,
      @Param("contractVersion") String contractVersion,
      @Param("engineContractVersion") String engineContractVersion,
      @Param("dialectVersion") String dialectVersion,
      @Param("corpusSha256") String corpusSha256,
      @Param("catalogDigest") String catalogDigest,
      @Param("failureCode") String failureCode,
      @Param("observedAt") Instant observedAt,
      @Param("receivedAt") Instant receivedAt);
}
