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
 * Explicit relationship between bounded contexts in one federation release.
 */
@Entity
@Table(name = "domain_context_relationship")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainContextRelationship {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "federation_release_id", nullable = false)
  private DomainFederationRelease federationRelease;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "relationship_key", nullable = false, length = 768)
  private String relationshipKey;

  @Column(name = "source_context_key", nullable = false, length = 512)
  private String sourceContextKey;

  @Column(name = "target_context_key", nullable = false, length = 512)
  private String targetContextKey;

  @Column(name = "relationship_type", nullable = false, length = 64)
  private String relationshipType;

  @Column(name = "contract_key", length = 512)
  private String contractKey;

  @Column(nullable = false, length = 32)
  private String direction;

  @Column(nullable = false, length = 32)
  private String ownership;

  private Double confidence;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String evidence;

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
    if (direction == null || direction.isBlank()) {
      direction = "source_to_target";
    }
    if (ownership == null || ownership.isBlank()) {
      ownership = "unknown";
    }
    if (status == null || status.isBlank()) {
      status = "candidate";
    }
    if (evidence == null || evidence.isBlank()) {
      evidence = "{}";
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
    if (evidence == null || evidence.isBlank()) {
      evidence = "{}";
    }
  }
}
