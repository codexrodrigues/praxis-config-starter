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

/** Latest redacted runtime status for one server-owned host identity and RuleSet scope. */
@Entity
@Table(name = "domain_rule_host_status")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleHostStatus {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "rule_set_key", nullable = false, length = 512)
  private String ruleSetKey;

  @Column(name = "host_actor_ref", nullable = false, length = 255)
  private String hostActorRef;

  @Column(name = "loaded_snapshot_key", length = 128)
  private String loadedSnapshotKey;

  @Column(name = "loaded_snapshot_content_hash", length = 64)
  private String loadedSnapshotContentHash;

  @Column(name = "activation_revision")
  private Long activationRevision;

  @Column(nullable = false)
  private Boolean ready;

  @Column(name = "host_contract_version", nullable = false, length = 64)
  private String hostContractVersion;

  @Column(name = "engine_contract_version", length = 64)
  private String engineContractVersion;

  @Column(name = "json_logic_dialect_version", length = 64)
  private String jsonLogicDialectVersion;

  @Column(name = "json_logic_corpus_sha256", length = 64)
  private String jsonLogicCorpusSha256;

  @Column(name = "implementation_catalog_digest", length = 64)
  private String implementationCatalogDigest;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;
}
