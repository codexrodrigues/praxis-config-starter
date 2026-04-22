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

@Entity
@Table(name = "domain_knowledge_alias")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeAlias {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "concept_id", nullable = false)
  private DomainKnowledgeConcept concept;

  @Column(nullable = false, length = 512)
  private String alias;

  @Column(name = "normalized_alias", nullable = false, length = 512)
  private String normalizedAlias;

  @Column(length = 32)
  private String locale;

  @Column(length = 64)
  private String region;

  @Column(name = "business_unit", length = 128)
  private String businessUnit;

  @Column(name = "alias_type", nullable = false, length = 64)
  private String aliasType;

  @Column(nullable = false)
  private Double weight;

  @Column(nullable = false, length = 64)
  private String source;

  @Column(name = "curation_status", nullable = false, length = 32)
  private String curationStatus;

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
    if (aliasType == null || aliasType.isBlank()) {
      aliasType = "synonym";
    }
    if (weight == null) {
      weight = 1.0;
    }
    if (source == null || source.isBlank()) {
      source = "generated";
    }
    if (curationStatus == null || curationStatus.isBlank()) {
      curationStatus = "generated";
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
  }
}
