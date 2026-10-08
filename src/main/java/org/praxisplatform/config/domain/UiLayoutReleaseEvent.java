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

/** Append-only lifecycle evidence; it is not a second source for effective resolution. */
@Entity
@Table(name = "ui_layout_release_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutReleaseEvent {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "root_component_type", nullable = false, length = 64) private String rootComponentType;
  @Column(name = "root_component_id", nullable = false, length = 255) private String rootComponentId;
  @Column(name = "event_type", nullable = false, length = 32) private String eventType;
  @Column(name = "from_release_id") private UUID fromReleaseId;
  @Column(name = "to_release_id") private UUID toReleaseId;
  /** Present only for an event that moved the mutable release head. */
  @Column(name = "head_etag") private UUID headEtag;
  @Column(nullable = false, length = 255) private String actor;
  @Column(nullable = false, length = 1024) private String reason;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
