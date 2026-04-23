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
 * Shared, governed rule definition that may later be materialized into one or more runtime artifacts.
 */
@Entity
@Table(name = "domain_rule_definition")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleDefinition {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "rule_key", nullable = false, length = 512)
  private String ruleKey;

  @Column(nullable = false)
  private Integer version;

  @Column(name = "rule_type", nullable = false, length = 64)
  private String ruleType;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "context_key", length = 255)
  private String contextKey;

  @Column(name = "resource_key", length = 255)
  private String resourceKey;

  @Column(name = "service_key", length = 255)
  private String serviceKey;

  @Column(name = "semantic_owner", length = 255)
  private String semanticOwner;

  @Column(length = 255)
  private String steward;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_release_id")
  private DomainCatalogRelease sourceRelease;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_change_set_id")
  private DomainKnowledgeChangeSet sourceChangeSet;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String definition;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String parameters;

  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String condition;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String governance;

  @Column(name = "validation_result", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String validationResult;

  @Column(name = "created_by_type", nullable = false, length = 32)
  private String createdByType;

  @Column(name = "created_by", length = 255)
  private String createdBy;

  @Column(name = "approved_by", length = 255)
  private String approvedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @PrePersist
  public void onInsert() {
    Instant now = Instant.now();
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (version == null || version < 1) {
      version = 1;
    }
    if (status == null || status.isBlank()) {
      status = "draft";
    }
    if (createdByType == null || createdByType.isBlank()) {
      createdByType = "system";
    }
    if (definition == null || definition.isBlank()) {
      definition = "{}";
    }
    if (parameters == null || parameters.isBlank()) {
      parameters = "{}";
    }
    if (governance == null || governance.isBlank()) {
      governance = "{}";
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
    if (definition == null || definition.isBlank()) {
      definition = "{}";
    }
    if (parameters == null || parameters.isBlank()) {
      parameters = "{}";
    }
    if (governance == null || governance.isBlank()) {
      governance = "{}";
    }
  }
}
