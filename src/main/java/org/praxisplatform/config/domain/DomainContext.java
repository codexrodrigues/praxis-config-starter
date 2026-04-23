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
 * Bounded context or capability surface published into a federated domain map.
 */
@Entity
@Table(name = "domain_context")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainContext {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "federation_release_id", nullable = false)
  private DomainFederationRelease federationRelease;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "context_key", nullable = false, length = 512)
  private String contextKey;

  @Column(name = "source_key", nullable = false, length = 512)
  private String sourceKey;

  @Column(name = "context_type", nullable = false, length = 64)
  private String contextType;

  @Column(length = 512)
  private String label;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "semantic_owner", length = 255)
  private String semanticOwner;

  @Column(name = "technical_owner", length = 255)
  private String technicalOwner;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "latest_release_key", length = 512)
  private String latestReleaseKey;

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
    if (contextType == null || contextType.isBlank()) {
      contextType = "bounded_context";
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
