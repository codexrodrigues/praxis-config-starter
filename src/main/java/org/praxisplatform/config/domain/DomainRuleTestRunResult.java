package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/** Immutable per-scenario result without raw facts or executable snapshot content. */
@Entity @Table(name = "domain_rule_test_run_result") @Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DomainRuleTestRunResult {
  @Id private UUID id;
  @Column(name="test_run_id", nullable=false) private UUID testRunId;
  @Column(name="scenario_id", nullable=false) private UUID scenarioId;
  @Column(name="scenario_key", nullable=false, length=255) private String scenarioKey;
  @Column(name="expected_decision", nullable=false, length=32) private String expectedDecision;
  @Column(name="candidate_decision", nullable=false, length=32) private String candidateDecision;
  @Column(name="active_decision", nullable=false, length=32) private String activeDecision;
  @Column(nullable=false, length=32) private String comparison;
  @Column(name="candidate_matches_expected", nullable=false) private Boolean candidateMatchesExpected;
  @Column(name="active_matches_expected", nullable=false) private Boolean activeMatchesExpected;
  @Column(name="candidate_reason_codes", nullable=false, columnDefinition="jsonb") @ColumnTransformer(write="?::jsonb") private String candidateReasonCodes;
  @Column(name="active_reason_codes", nullable=false, columnDefinition="jsonb") @ColumnTransformer(write="?::jsonb") private String activeReasonCodes;
  @Column(name="candidate_plan_digest", nullable=false, length=64) private String candidatePlanDigest;
  @Column(name="active_plan_digest", nullable=false, length=64) private String activePlanDigest;
  @Column(name="facts_digest", nullable=false, length=64) private String factsDigest;
}
