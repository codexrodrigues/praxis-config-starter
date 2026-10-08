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
import org.hibernate.annotations.ColumnTransformer;

/** Immutable audience contribution; it never changes a revision or the release that references it. */
@Entity
@Table(name = "ui_layout_assignment_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutAssignmentRevision {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "revision_id", nullable = false) private UUID revisionId;
  @Column(name = "layer_class", nullable = false, length = 32) private String layerClass;
  @Column(name = "selector_document", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String selectorDocument;
  @Column(nullable = false) private short priority;
  /** Stable logical identity across assignment revisions; it is never derived from mutable selector data. */
  @Column(name = "contribution_key", nullable = false, length = 255) private String contributionKey;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
