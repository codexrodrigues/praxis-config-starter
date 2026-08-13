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

/** Mutable anti-ABA pointer to the active rollout policy for one scoped RuleSet. */
@Entity
@Table(name = "domain_rule_rollout_policy_head")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleRolloutPolicyHead {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_set_key", nullable = false, length = 512) private String ruleSetKey;
  @Column(name = "active_policy_id") private UUID activePolicyId;
  @Column(name = "activation_revision", nullable = false) private Long activationRevision;
  @Column(name = "head_etag", nullable = false) private UUID headEtag;
  @Column(name = "updated_by", nullable = false, length = 255) private String updatedBy;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
