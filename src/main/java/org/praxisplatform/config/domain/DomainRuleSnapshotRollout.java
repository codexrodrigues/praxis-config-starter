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

/** Mutable lifecycle record that binds one immutable candidate to one expected head. */
@Entity
@Table(name = "domain_rule_snapshot_rollout")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleSnapshotRollout {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_set_key", nullable = false, length = 512) private String ruleSetKey;
  @Column(name = "candidate_snapshot_id", nullable = false) private UUID candidateSnapshotId;
  @Column(name = "expected_active_snapshot_id", nullable = false) private UUID expectedActiveSnapshotId;
  @Column(name = "expected_head_etag", nullable = false) private UUID expectedHeadEtag;
  @Column(name = "policy_id", nullable = false) private UUID policyId;
  @Column(nullable = false, length = 32) private String status;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "expires_at") private Instant expiresAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
