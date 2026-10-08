package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tenant-bound logical owner of immutable revisions for one exact UI target. */
@Entity
@Table(name = "ui_layout_definition")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutDefinition {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "component_type", nullable = false, length = 64) private String componentType;
  @Column(name = "component_id", nullable = false, length = 255) private String componentId;
  @Column(name = "schema_version", nullable = false, length = 128) private String schemaVersion;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
