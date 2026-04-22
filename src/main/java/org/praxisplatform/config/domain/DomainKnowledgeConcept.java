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
 * Curated or generated semantic concept used by the Domain Knowledge Layer.
 */
@Entity
@Table(name = "domain_knowledge_concept")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeConcept {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "concept_key", nullable = false, length = 512)
  private String conceptKey;

  @Column(name = "context_key", length = 255)
  private String contextKey;

  @Column(name = "resource_key", length = 255)
  private String resourceKey;

  @Column(name = "node_type", nullable = false, length = 64)
  private String nodeType;

  @Column(length = 512)
  private String label;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(length = 32)
  private String locale;

  @Column(name = "semantic_owner", length = 255)
  private String semanticOwner;

  @Column(length = 255)
  private String steward;

  @Column(nullable = false, length = 32)
  private String lifecycle;

  @Column(name = "curation_status", nullable = false, length = 32)
  private String curationStatus;

  @Column(name = "ai_visibility", nullable = false, length = 32)
  private String aiVisibility;

  @Column(name = "data_category", length = 64)
  private String dataCategory;

  @Column(length = 64)
  private String classification;

  @Column(name = "compliance_tags", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String complianceTags;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_release_id")
  private DomainCatalogRelease sourceRelease;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  public void onInsert() {
    Instant now = Instant.now();
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (lifecycle == null || lifecycle.isBlank()) {
      lifecycle = "candidate";
    }
    if (curationStatus == null || curationStatus.isBlank()) {
      curationStatus = "generated";
    }
    if (aiVisibility == null || aiVisibility.isBlank()) {
      aiVisibility = "allow";
    }
    if (payload == null || payload.isBlank()) {
      payload = "{}";
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
    if (payload == null || payload.isBlank()) {
      payload = "{}";
    }
  }
}
