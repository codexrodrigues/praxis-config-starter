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

/** Immutable persisted publication of one governed RuleSet snapshot. */
@Entity
@Table(name = "domain_rule_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleSnapshot {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "snapshot_key", nullable = false, length = 128)
  private String snapshotKey;

  @Column(name = "rule_set_key", nullable = false, length = 512)
  private String ruleSetKey;

  @Column(name = "rule_set_version", nullable = false)
  private Integer ruleSetVersion;

  @Column(name = "publication_revision", nullable = false)
  private Integer publicationRevision;

  @Column(name = "snapshot_payload", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String snapshotPayload;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "supersedes_snapshot_id")
  private UUID supersedesSnapshotId;

  @Column(name = "published_by", nullable = false, length = 255)
  private String publishedBy;

  @Column(name = "published_at", nullable = false)
  private Instant publishedAt;
}
