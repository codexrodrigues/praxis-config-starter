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

/**
 * Immutable publication envelope for one accepted federated domain snapshot.
 */
@Entity
@Table(name = "domain_federation_release")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainFederationRelease {

  @Id
  private UUID id;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "release_key", nullable = false, length = 512)
  private String releaseKey;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "source_release_ids", columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String sourceReleaseIds;

  @Column(name = "validation_report", columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String validationReport;

  @Column(name = "payload_hash", length = 128)
  private String payloadHash;

  @Column(name = "created_by", length = 255)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @PrePersist
  public void onInsert() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (status == null || status.isBlank()) {
      status = "candidate";
    }
    if (sourceReleaseIds == null || sourceReleaseIds.isBlank()) {
      sourceReleaseIds = "[]";
    }
    if (validationReport == null || validationReport.isBlank()) {
      validationReport = "{}";
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
