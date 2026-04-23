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
@Table(name = "domain_knowledge_binding")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeBinding {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "concept_id", nullable = false)
  private DomainKnowledgeConcept concept;

  @Column(name = "binding_type", nullable = false, length = 64)
  private String bindingType;

  @Column(name = "binding_key", nullable = false, length = 768)
  private String bindingKey;

  @Column(name = "resource_key", length = 255)
  private String resourceKey;

  @Column(name = "api_path", length = 768)
  private String apiPath;

  @Column(name = "api_method", length = 16)
  private String apiMethod;

  @Column(name = "schema_pointer", length = 768)
  private String schemaPointer;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_release_id")
  private DomainCatalogRelease sourceRelease;

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
