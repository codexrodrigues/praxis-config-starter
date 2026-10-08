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

/** Append-only, server-attested presentation patch for a layout definition. */
@Entity
@Table(name = "ui_layout_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutRevision {
  @Id private UUID id;
  @Column(name = "definition_id", nullable = false) private UUID definitionId;
  @Column(name = "revision_number", nullable = false) private Long revisionNumber;
  @Column(name = "patch_document", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String patchDocument;
  @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
  @Column(name = "schema_version", nullable = false, length = 128) private String schemaVersion;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_reason", nullable = false, length = 1024) private String createdReason;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
}
