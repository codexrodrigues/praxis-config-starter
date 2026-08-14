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

/** Reusable facts and expected outcome belonging to a governed change workspace. */
@Entity
@Table(name = "domain_rule_test_scenario")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainRuleTestScenario {
  @Id private UUID id;
  @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
  @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
  @Column(nullable = false, length = 128) private String environment;
  @Column(name = "scenario_key", nullable = false, length = 255) private String scenarioKey;
  @Column(nullable = false, length = 255) private String name;
  @Column(nullable = false, columnDefinition = "jsonb") @ColumnTransformer(write = "?::jsonb") private String facts;
  @Column(name = "expected_decision", nullable = false, length = 32) private String expectedDecision;
  @Column(name = "expected_output", columnDefinition = "jsonb") @ColumnTransformer(write = "?::jsonb") private String expectedOutput;
  @Column(name = "expected_reason_codes", nullable = false, columnDefinition = "jsonb") @ColumnTransformer(write = "?::jsonb") private String expectedReasonCodes;
  @Column(name = "expected_effect_intents", nullable = false, columnDefinition = "jsonb") @ColumnTransformer(write = "?::jsonb") private String expectedEffectIntents;
  @Column(nullable = false, length = 32) private String status;
  @Column(nullable = false) private UUID etag;
  @Column(nullable = false) private Long revision;
  @Column(name = "created_by", nullable = false, length = 255) private String createdBy;
  @Column(name = "updated_by", nullable = false, length = 255) private String updatedBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
