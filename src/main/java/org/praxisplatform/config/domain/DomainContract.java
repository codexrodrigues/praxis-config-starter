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
 * Integration contract that explains how two domain contexts connect.
 */
@Entity
@Table(name = "domain_contract")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainContract {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "federation_release_id", nullable = false)
  private DomainFederationRelease federationRelease;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "contract_key", nullable = false, length = 512)
  private String contractKey;

  @Column(name = "contract_type", nullable = false, length = 64)
  private String contractType;

  @Column(name = "provider_source_key", nullable = false, length = 512)
  private String providerSourceKey;

  @Column(name = "provider_context_key", nullable = false, length = 512)
  private String providerContextKey;

  @Column(name = "consumer_context_key", length = 512)
  private String consumerContextKey;

  @Column(name = "resource_key", length = 255)
  private String resourceKey;

  @Column(name = "operation_key", length = 512)
  private String operationKey;

  @Column(name = "schema_ref", length = 1024)
  private String schemaRef;

  @Column(nullable = false, length = 64)
  private String compatibility;

  @Column(nullable = false, length = 64)
  private String visibility;

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
    if (compatibility == null || compatibility.isBlank()) {
      compatibility = "experimental";
    }
    if (visibility == null || visibility.isBlank()) {
      visibility = "internal";
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
