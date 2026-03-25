package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "ui_user_config",
    uniqueConstraints = {
      @UniqueConstraint(
          columnNames = {"tenant_id", "user_id", "component_type", "component_id", "environment"})
    })
/**
 * Entidade persistida que representa configuraÃ§Ã£o de UI por componente e escopo.
 *
 * <p>
 * Cada registro Ã© Ãºnico por {@code tenantId}, {@code userId}, {@code componentType},
 * {@code componentId} e {@code environment}. O domÃ­nio mantÃ©m {@code version} monotÃ´nica e
 * {@code etag} renovado a cada atualizaÃ§Ã£o para suportar cache condicional no endpoint pÃºblico.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiUserConfig {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "component_type", nullable = false, length = 64)
  private String componentType;

  @Column(name = "component_id", nullable = false, length = 255)
  private String componentId;

  @Column(name = "environment", length = 64)
  private String environment;

  @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
  @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
  private String payload;

  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "etag", nullable = false)
  private UUID etag;

  @Column(name = "tags", columnDefinition = "jsonb")
  @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
  private String tags;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private String updatedBy;

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
  }

  @PreUpdate
  public void onUpdate() {
    this.updatedAt = Instant.now();
  }
}

