package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Latest redacted candidate-preload result for one authenticated host and rollout. */
@Entity
@Table(name = "domain_rule_candidate_probe")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleCandidateProbe {
  @Id private UUID id;
  @Column(name = "rollout_id", nullable = false) private UUID rolloutId;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_set_key", nullable = false, length = 512) private String ruleSetKey;
  @Column(name = "host_actor_ref", nullable = false, length = 255) private String hostActorRef;
  @Column(name = "candidate_snapshot_id", nullable = false) private UUID candidateSnapshotId;
  @Column(name = "candidate_snapshot_key", nullable = false, length = 128) private String candidateSnapshotKey;
  @Column(name = "candidate_content_hash", nullable = false, length = 64) private String candidateContentHash;
  @Column(name = "preload_ready", nullable = false) private Boolean preloadReady;
  @Column(name = "host_contract_version", nullable = false, length = 64) private String hostContractVersion;
  @Column(name = "engine_contract_version", length = 64) private String engineContractVersion;
  @Column(name = "json_logic_dialect_version", length = 64) private String jsonLogicDialectVersion;
  @Column(name = "json_logic_corpus_sha256", length = 64) private String jsonLogicCorpusSha256;
  @Column(name = "implementation_catalog_digest", length = 64) private String implementationCatalogDigest;
  @Column(name = "failure_code", length = 64) private String failureCode;
  @Column(name = "observed_at", nullable = false) private Instant observedAt;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
}
