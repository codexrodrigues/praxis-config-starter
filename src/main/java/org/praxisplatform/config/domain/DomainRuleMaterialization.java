package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Concrete runtime projection of a shared domain rule into a FormConfig, backend rule or other artifact.
 */
@Entity
@Table(name = "domain_rule_materialization")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleMaterialization {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_definition_id", nullable = false)
  private DomainRuleDefinition ruleDefinition;

  @Column(name = "materialization_key", nullable = false, length = 512)
  private String materializationKey;

  @Column(name = "target_layer", nullable = false, length = 64)
  private String targetLayer;

  @Column(name = "target_artifact_type", nullable = false, length = 64)
  private String targetArtifactType;

  @Column(name = "target_artifact_key", nullable = false, length = 768)
  private String targetArtifactKey;

  @Column(name = "target_pointer", length = 768)
  private String targetPointer;

  @Column(name = "target_release_key", length = 255)
  private String targetReleaseKey;

  @Column(name = "materialized_rule_id", length = 512)
  private String materializedRuleId;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "materialized_payload", columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String materializedPayload;

  @Column(name = "source_hash", length = 128)
  private String sourceHash;

  @Column(name = "validation_result", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String validationResult;

  @Column(name = "applied_by_type", length = 32)
  private String appliedByType;

  @Column(name = "applied_by", length = 255)
  private String appliedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "applied_at")
  private Instant appliedAt;

  @PrePersist
  public void onInsert() {
    Instant now = Instant.now();
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (status == null || status.isBlank()) {
      status = "draft";
    }
    if (materializedPayload == null || materializedPayload.isBlank()) {
      materializedPayload = "{}";
    }
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
  }

  @PreUpdate
  public void onUpdate() {
    updatedAt = Instant.now();
    if (materializedPayload == null || materializedPayload.isBlank()) {
      materializedPayload = "{}";
    }
  }
}
