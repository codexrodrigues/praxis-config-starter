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

/** Mutable, governed authoring workspace anchored to one immutable definition fingerprint. */
@Entity
@Table(name = "domain_rule_change_workspace")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleChangeWorkspace {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "rule_key", nullable = false, length = 512) private String ruleKey;
  @Column(name = "base_definition_id", nullable = false) private UUID baseDefinitionId;
  @Column(name = "base_definition_version", nullable = false) private Integer baseDefinitionVersion;
  @Column(name = "base_definition_hash", nullable = false, length = 64) private String baseDefinitionHash;
  @Column(name = "promoted_definition_id") private UUID promotedDefinitionId;
  @Column(name = "submitted_test_run_id") private UUID submittedTestRunId;
  @Column(nullable = false, length = 255) private String title;
  @Column(nullable = false, length = 32) private String status;
  @Column(name = "draft_condition", columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb") private String draftCondition;
  @Column(name = "draft_parameters", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb") private String draftParameters;
  @Column(columnDefinition = "text") private String rationale;
  @Column(nullable = false) private UUID etag;
  @Column(nullable = false) private Long revision;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "updated_by", nullable = false, length = 255) private String updatedBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
