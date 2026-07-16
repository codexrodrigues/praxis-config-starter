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

/** Append-only IAM-bound approval of one exact governed rule definition. */
@Entity
@Table(name = "domain_rule_definition_approval")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleDefinitionApproval {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false, length = 128)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String environment;

  @Column(name = "definition_id", nullable = false)
  private UUID definitionId;

  @Column(name = "definition_hash", nullable = false, length = 64)
  private String definitionHash;

  @Column(name = "actor_ref", nullable = false, length = 255)
  private String actorRef;

  @Column(nullable = false, length = 64)
  private String role;

  @Column(name = "approved_at", nullable = false)
  private Instant approvedAt;
}
