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

/** Append-only approval evidence, separate from the mutable review workflow. */
@Entity
@Table(name = "ui_layout_release_approval")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutReleaseApproval {
  @Id private UUID id;
  @Column(name = "release_id", nullable = false) private UUID releaseId;
  @Column(nullable = false, length = 255) private String actor;
  @Column(nullable = false, length = 1024) private String reason;
  @Column(name = "approved_at", nullable = false) private Instant approvedAt;
}
