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
import org.hibernate.annotations.ColumnTransformer;

/** Mutable workspace. Its ETag is independent from immutable revision and release identities. */
@Entity
@Table(name = "ui_layout_draft")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiLayoutDraft {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 255) private String tenantId;
  @Column(nullable = false, length = 64) private String environment;
  @Column(name = "root_component_type", nullable = false, length = 64) private String rootComponentType;
  @Column(name = "root_component_id", nullable = false, length = 255) private String rootComponentId;
  @Column(name = "draft_document", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String draftDocument;
  @Column(name = "draft_etag", nullable = false) private UUID draftEtag;
  @Column(nullable = false, length = 32) private String state;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "creation_idempotency_key", length = 180) private String creationIdempotencyKey;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
