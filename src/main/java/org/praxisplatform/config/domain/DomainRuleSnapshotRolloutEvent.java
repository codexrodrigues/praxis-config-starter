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
import org.hibernate.annotations.ColumnTransformer;

/** Append-only safe lifecycle evidence for a staged snapshot rollout. */
@Entity
@Table(name = "domain_rule_snapshot_rollout_event")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleSnapshotRolloutEvent {
  @Id private UUID id;
  @Column(name = "rollout_id", nullable = false) private UUID rolloutId;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_set_key", nullable = false, length = 512) private String ruleSetKey;
  @Column(name = "event_type", nullable = false, length = 32) private String eventType;
  @Column(name = "actor_ref", nullable = false, length = 255) private String actorRef;
  @Column(name = "safe_metadata", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb") private String safeMetadata;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
