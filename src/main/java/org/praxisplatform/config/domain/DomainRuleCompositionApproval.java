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

/** Append-only IAM-bound approval of one exact canonical RuleSet composition. */
@Entity
@Table(name = "domain_rule_composition_approval")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleCompositionApproval {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "composition_digest", nullable = false, length = 64)
  private String compositionDigest;

  @Column(name = "actor_ref", nullable = false, length = 255)
  private String actorRef;

  @Column(nullable = false, length = 64)
  private String role;

  @Column(nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb")
  private String manifest;

  @Column(name = "approved_at", nullable = false)
  private Instant approvedAt;
}
