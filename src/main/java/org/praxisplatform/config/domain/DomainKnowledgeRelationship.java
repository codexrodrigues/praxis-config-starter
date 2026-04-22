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

@Entity
@Table(name = "domain_knowledge_relationship")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeRelationship {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "source_concept_id", nullable = false)
  private DomainKnowledgeConcept sourceConcept;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "target_concept_id", nullable = false)
  private DomainKnowledgeConcept targetConcept;

  @Column(name = "relationship_type", nullable = false, length = 64)
  private String relationshipType;

  @Column(name = "cross_context", nullable = false)
  private Boolean crossContext;

  @Column(name = "source_context_key", length = 255)
  private String sourceContextKey;

  @Column(name = "target_context_key", length = 255)
  private String targetContextKey;

  @Column(name = "contract_key", length = 512)
  private String contractKey;

  private Double confidence;

  @Column(name = "curation_status", nullable = false, length = 32)
  private String curationStatus;

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
    if (crossContext == null) {
      crossContext = false;
    }
    if (curationStatus == null || curationStatus.isBlank()) {
      curationStatus = "generated";
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
