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

/** Immutable safe evidence for one host-executed policy sandbox run. */
@Entity @Table(name = "domain_rule_test_run") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleTestRun {
  @Id private UUID id;
  @Column(name="workspace_id", nullable=false) private UUID workspaceId;
  @Column(name="tenant_id", nullable=false, length=128) private String tenantId;
  @Column(nullable=false, length=128) private String environment;
  @Column(name="workspace_revision", nullable=false) private Long workspaceRevision;
  @Column(name="base_definition_hash", nullable=false, length=64) private String baseDefinitionHash;
  @Column(name="evaluated_at", nullable=false) private Instant evaluatedAt;
  @Column(name="user_time_zone", nullable=false, length=128) private String userTimeZone;
  @Column(name="active_snapshot_key", length=128) private String activeSnapshotKey;
  @Column(name="active_snapshot_content_hash", length=64) private String activeSnapshotContentHash;
  @Column(name="active_activation_revision", nullable=false) private Long activeActivationRevision;
  @Column(name="baseline_evidence", columnDefinition="jsonb") @ColumnTransformer(write="?::jsonb") private String baselineEvidence;
  @Column(name="result_summary", nullable=false, columnDefinition="jsonb") @ColumnTransformer(write="?::jsonb") private String resultSummary;
  @Column(name="recorded_by", nullable=false, length=255) private String recordedBy;
  @Column(name="recorded_at", nullable=false) private Instant recordedAt;
}
