package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The sole mutable pointer for one published composition scope. */
@Entity
@Table(name = "ui_layout_release_head")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutReleaseHead {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "root_component_type", nullable = false, length = 64) private String rootComponentType;
  @Column(name = "root_component_id", nullable = false, length = 255) private String rootComponentId;
  @Column(name = "active_release_id") private UUID activeReleaseId;
  @Column(name = "head_etag", nullable = false) private UUID headEtag;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
