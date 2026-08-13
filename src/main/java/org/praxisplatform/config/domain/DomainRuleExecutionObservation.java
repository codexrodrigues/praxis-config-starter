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

/** Append-only, redacted evidence that one governed snapshot was evaluated by a host. */
@Entity
@Table(name = "domain_rule_execution_observation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleExecutionObservation {
  @Id
  @Column(name = "observation_id")
  private UUID observationId;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "rule_set_key", nullable = false, length = 512)
  private String ruleSetKey;

  @Column(name = "snapshot_id", nullable = false)
  private UUID snapshotId;

  @Column(name = "snapshot_key", nullable = false, length = 128)
  private String snapshotKey;

  @Column(name = "snapshot_content_hash", nullable = false, length = 64)
  private String snapshotContentHash;

  @Column(name = "rule_set_version", nullable = false)
  private Integer ruleSetVersion;

  @Column(name = "activation_revision", nullable = false)
  private Long activationRevision;

  @Column(nullable = false, length = 32)
  private String outcome;

  @Column(name = "duration_micros", nullable = false)
  private Long durationMicros;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "host_actor_ref", nullable = false, length = 255)
  private String hostActorRef;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;
}
