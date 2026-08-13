package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable version of the server-owned candidate rollout quorum policy. */
@Entity
@Table(name = "domain_rule_rollout_policy")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleRolloutPolicy {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_set_key", nullable = false, length = 512) private String ruleSetKey;
  @Column(name = "policy_key", nullable = false, length = 128) private String policyKey;
  @Column(name = "policy_version", nullable = false) private Integer policyVersion;
  @Column(name = "enforcement_mode", nullable = false, length = 32) private String enforcementMode;
  @Column(name = "minimum_fresh_probes", nullable = false) private Integer minimumFreshProbes;
  @Column(name = "minimum_ready_ratio", nullable = false, precision = 5, scale = 4)
  private BigDecimal minimumReadyRatio;
  @Column(name = "block_on_incompatible", nullable = false) private Boolean blockOnIncompatible;
  @Column(name = "stale_after_seconds", nullable = false) private Long staleAfterSeconds;
  @Column(name = "maximum_rollout_age_seconds") private Long maximumRolloutAgeSeconds;
  @Column(nullable = false) private Boolean active;
  @Column(nullable = false, length = 32) private String status;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "approved_by", length = 255) private String approvedBy;
  @Column(name = "approved_at") private Instant approvedAt;
  @Column(name = "activated_by", length = 255) private String activatedBy;
  @Column(name = "activated_at") private Instant activatedAt;
}
