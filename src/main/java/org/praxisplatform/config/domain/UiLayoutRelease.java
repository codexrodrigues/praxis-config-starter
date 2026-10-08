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

/** Immutable aggregate publication. Lifecycle state belongs to review, head and append-only events. */
@Entity
@Table(name = "ui_layout_release")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutRelease {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "root_component_type", nullable = false, length = 64) private String rootComponentType;
  @Column(name = "root_component_id", nullable = false, length = 255) private String rootComponentId;
  @Column(name = "source_draft_id") private UUID sourceDraftId;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
