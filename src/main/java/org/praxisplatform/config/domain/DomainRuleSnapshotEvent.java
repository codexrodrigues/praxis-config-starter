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

/** Append-only audit event for snapshot publication and head activation. */
@Entity
@Table(name = "domain_rule_snapshot_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleSnapshotEvent {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "rule_set_key", nullable = false, length = 512)
  private String ruleSetKey;

  @Column(name = "event_type", nullable = false, length = 32)
  private String eventType;

  @Column(name = "from_snapshot_id")
  private UUID fromSnapshotId;

  @Column(name = "to_snapshot_id", nullable = false)
  private UUID toSnapshotId;

  @Column(name = "activation_revision", nullable = false)
  private Long activationRevision;

  @Column(name = "head_etag", nullable = false)
  private UUID headEtag;

  @Column(nullable = false, length = 255)
  private String actor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
