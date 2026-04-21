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
 * Release imutavel de um catalogo semantico publicado por {@code /schemas/domain}.
 */
@Entity
@Table(name = "domain_catalog_release")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainCatalogRelease {

  @Id
  private UUID id;

  @Column(name = "release_key", nullable = false, unique = true, length = 255)
  private String releaseKey;

  @Column(name = "schema_version", nullable = false, length = 64)
  private String schemaVersion;

  @Column(name = "service_key", length = 255)
  private String serviceKey;

  @Column(name = "service_name", length = 255)
  private String serviceName;

  @Column(name = "service_version", length = 64)
  private String serviceVersion;

  @Column(name = "generated_at")
  private Instant generatedAt;

  @Column(name = "source_hash", length = 128)
  private String sourceHash;

  @Column(name = "tenant_id", length = 128)
  private String tenantId;

  @Column(length = 128)
  private String environment;

  @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String rawPayload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  public void onInsert() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }
}
