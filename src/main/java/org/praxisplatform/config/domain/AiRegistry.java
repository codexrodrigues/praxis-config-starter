package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "ai_registry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRegistry {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "registry_type", nullable = false, length = 64)
  private String registryType;

  @Column(name = "registry_key", nullable = false, length = 255)
  private String registryKey;

  @Column(name = "component_type", length = 64)
  private String componentType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Scope scope;

  @Column(name = "scope_key", nullable = false, length = 255)
  private String scopeKey;

  @Column(columnDefinition = "jsonb", nullable = false)
  @ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(nullable = false)
  private long version;

  @Column(nullable = false)
  private UUID etag;

  @Column(columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String tags;

  @Column(length = 64)
  private String source;

  @Column(name = "source_ref", length = 255)
  private String sourceRef;

  @Column(length = 32, nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "updated_by")
  private String updatedBy;

  @Column(columnDefinition = "vector")
  @ColumnTransformer(write = "?::vector")
  @Convert(converter = VectorConverter.class)
  private List<Float> embedding;

  @PrePersist
  public void onInsert() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.version <= 0) {
      this.version = 1L;
    }
    if (this.etag == null) {
      this.etag = UUID.randomUUID();
    }
    if (this.status == null || this.status.isBlank()) {
      this.status = "active";
    }
  }

  @PreUpdate
  public void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
