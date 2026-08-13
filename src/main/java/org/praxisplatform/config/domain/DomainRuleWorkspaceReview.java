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

/** Append-only IAM-bound review of one exact submitted workspace revision. */
@Entity
@Table(name = "domain_rule_workspace_review")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleWorkspaceReview {
  @Id private UUID id;
  @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "workspace_revision", nullable = false) private Long workspaceRevision;
  @Column(name = "base_definition_hash", nullable = false, length = 64) private String baseDefinitionHash;
  @Column(nullable = false, length = 16) private String decision;
  @Column(nullable = false, columnDefinition = "text") private String rationale;
  @Column(name = "reviewer_ref", nullable = false, length = 255) private String reviewerRef;
  @Column(name = "reviewed_at", nullable = false) private Instant reviewedAt;
}
