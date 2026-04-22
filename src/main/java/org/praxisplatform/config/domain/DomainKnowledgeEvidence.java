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

@Entity
@Table(name = "domain_knowledge_evidence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeEvidence {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "evidence_key", nullable = false, length = 512)
  private String evidenceKey;

  @Column(name = "subject_type", nullable = false, length = 64)
  private String subjectType;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "evidence_type", nullable = false, length = 64)
  private String evidenceType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_release_id")
  private DomainCatalogRelease sourceRelease;

  @Column(name = "source_uri", length = 1024)
  private String sourceUri;

  @Column(name = "source_pointer", length = 1024)
  private String sourcePointer;

  private Double confidence;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void onInsert() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (payload == null || payload.isBlank()) {
      payload = "{}";
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
