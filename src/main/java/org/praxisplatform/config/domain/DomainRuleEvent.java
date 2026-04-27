package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Append-only safe event source for governed domain-rule observability.
 */
@Entity
@Table(name = "domain_rule_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleEvent {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_definition_id", nullable = false)
  private DomainRuleDefinition ruleDefinition;

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "actor_type", length = 32)
  private String actorType;

  @Column(length = 255)
  private String actor;

  @Column(nullable = false, length = 512)
  private String summary;

  @Column(length = 32)
  private String status;

  @Column(name = "target_layer", length = 64)
  private String targetLayer;

  @Column(name = "target_artifact_type", length = 64)
  private String targetArtifactType;

  @Column(name = "target_artifact_key", length = 768)
  private String targetArtifactKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "materialization_id")
  private DomainRuleMaterialization materialization;

  @Column(name = "materialization_key", length = 512)
  private String materializationKey;

  @Column(name = "source_hash", length = 128)
  private String sourceHash;

  @Column(nullable = false, length = 32)
  private String visibility;

  @Column(name = "safe_metadata", columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String safeMetadata;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void onInsert() {
    Instant now = Instant.now();
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (occurredAt == null) {
      occurredAt = now;
    }
    if (visibility == null || visibility.isBlank()) {
      visibility = "safe";
    }
    if (safeMetadata == null || safeMetadata.isBlank()) {
      safeMetadata = "{}";
    }
    if (createdAt == null) {
      createdAt = now;
    }
  }
}
