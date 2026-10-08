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

/** Mutable review state kept separate from immutable release content. */
@Entity
@Table(name = "ui_layout_release_review")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutReleaseReview {
  @Id private UUID id;
  @Column(name = "release_id", nullable = false, unique = true) private UUID releaseId;
  @Column(nullable = false, length = 32) private String state;
  @Column(name = "review_etag", nullable = false) private UUID reviewEtag;
  @Column(name = "submitted_by", length = 255) private String submittedBy;
  @Column(name = "submitted_at") private Instant submittedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
