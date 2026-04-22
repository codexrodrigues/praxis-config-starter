package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "domain_knowledge_change_set")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainKnowledgeChangeSet {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "change_set_key", nullable = false, length = 512)
  private String changeSetKey;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "author_type", nullable = false, length = 32)
  private String authorType;

  @Column(name = "author_id", length = 255)
  private String authorId;

  @Column(name = "reviewer_id", length = 255)
  private String reviewerId;

  @Column(length = 255)
  private String intent;

  @Column(columnDefinition = "TEXT")
  private String reason;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String patch;

  @Column(name = "validation_result", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String validationResult;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "applied_at")
  private Instant appliedAt;

  @PrePersist
  public void onInsert() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (status == null || status.isBlank()) {
      status = "draft";
    }
    if (patch == null || patch.isBlank()) {
      patch = "[]";
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
