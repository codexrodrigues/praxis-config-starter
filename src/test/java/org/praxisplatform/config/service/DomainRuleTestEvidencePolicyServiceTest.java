package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.DomainRuleOperationalTestEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineEvidence;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;

@Tag("unit")
class DomainRuleTestEvidencePolicyServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final DomainRuleTestEvidencePolicyService service =
      new DomainRuleTestEvidencePolicyService(objectMapper);

  @Test
  void acceptsTheCompleteCreateUpdateAllowDenyBaselineMatrix() throws Exception {
    List<DomainRuleTestRunResult> results = new ArrayList<>();
    for (String operation : List.of("CREATE", "UPDATE")) {
      for (String decision : List.of("ALLOW", "DENY")) {
        results.add(result(operation, decision, true, true));
      }
    }

    var blockers = service.blockers("PROMOTE", definition(policy()), run("ELIGIBLE"), results);

    assertThat(blockers).isEmpty();
  }

  @Test
  void appliesTheSameGovernedMatrixAtPublication() throws Exception {
    List<DomainRuleTestRunResult> results = new ArrayList<>();
    for (String operation : List.of("CREATE", "UPDATE")) {
      for (String decision : List.of("ALLOW", "DENY")) {
        results.add(result(operation, decision, true, true));
      }
    }

    var blockers = service.blockers(
        "PUBLISH", definition(policy().replace("PROMOTE", "PUBLISH")),
        run("ELIGIBLE"), results);

    assertThat(blockers).isEmpty();
  }

  @Test
  void failsClosedWhenAuthorityEligibilityCleanupParityOrMatrixIsIncomplete() throws Exception {
    var blockers = service.blockers("PROMOTE", definition(policy()), run("PENDING"),
        List.of(result("CREATE", "ALLOW", false, false)));

    assertThat(blockers).extracting("code").containsExactly(
        "REQUIRED_BASELINE_ELIGIBILITY_MISSING",
        "CLEANUP_EVIDENCE_INCOMPLETE",
        "BASELINE_PARITY_INCOMPLETE",
        "OPERATION_DECISION_MATRIX_INCOMPLETE");
  }

  @Test
  void ignoresStagesThatTheCanonicalDefinitionDoesNotGovern() {
    assertThat(service.blockers("PROMOTE", definition("{}"), null, List.of())).isEmpty();
  }

  @Test
  void rejectsUnknownPolicyFieldsInsteadOfSilentlyWeakeningTheGate() {
    String malformed = """
        {"testEvidencePolicy":{"stages":{"PROMOTE":{"cleanupProbably":true}}}}
        """;

    assertThat(service.blockers("PROMOTE", definition(malformed), null, List.of()))
        .extracting("code").containsExactly("TEST_EVIDENCE_POLICY_INVALID");
  }

  @Test
  void rejectsUnknownStagesInsteadOfSilentlySkippingTheirPolicy() {
    String typoed = """
        {"testEvidencePolicy":{"stages":{"PROMT":{"requireCleanupVerified":true}}}}
        """;
    assertThat(service.blockers("PROMOTE", definition(typoed), null, List.of()))
        .extracting("code").containsExactly("TEST_EVIDENCE_POLICY_INVALID");
  }

  @Test
  void recognizesSnapshotAndActivationAsGovernedEvidenceStages() {
    String lifecycle = """
        {"testEvidencePolicy":{"stages":{
          "SNAPSHOT":{"baselineEligibility":"ELIGIBLE"},
          "ACTIVATE":{"requireCleanupVerified":true}
        }}}
        """;

    assertThat(service.hasStage("SNAPSHOT", definition(lifecycle))).isTrue();
    assertThat(service.hasStage("ACTIVATE", definition(lifecycle))).isTrue();
  }

  private String policy() {
    return """
        {"testEvidencePolicy":{"stages":{"PROMOTE":{
          "baselineAuthorityType":"LEGACY_ORACLE",
          "baselineEligibility":"ELIGIBLE",
          "requiredOperationModes":["CREATE","UPDATE"],
          "requiredDecisions":["ALLOW","DENY"],
          "requireCleanupVerified":true,
          "requireBaselineMatch":true
        }}}}
        """;
  }

  private DomainRuleDefinition definition(String governance) {
    return DomainRuleDefinition.builder().governance(governance).build();
  }

  private DomainRuleTestRun run(String eligibility) throws Exception {
    return DomainRuleTestRun.builder().id(UUID.randomUUID())
        .baselineEvidence(objectMapper.writeValueAsString(new DomainRuleTestBaselineEvidence(
            "LEGACY_ORACLE", "ergon:r013:matrix", "A".repeat(64),
            Instant.parse("2026-08-14T12:00:00Z"), eligibility)))
        .build();
  }

  private DomainRuleTestRunResult result(
      String operation, String decision, boolean cleanupVerified, boolean parity) throws Exception {
    var operational = new DomainRuleOperationalTestEvidence(operation,
        "UPDATE".equals(operation) ? "B".repeat(64) : null,
        "ALLOW".equals(decision) ? "C".repeat(64) : null,
        "ALLOW".equals(decision), "DENY".equals(decision), cleanupVerified,
        "D".repeat(64), 1);
    return DomainRuleTestRunResult.builder()
        .candidateDecision(decision)
        .baselineResult(parity ? "{}" : null)
        .candidateBaselineComparison(parity ? "MATCH" : null)
        .baselineMatchesExpected(parity)
        .baselineOutputMatchesExpected(parity)
        .baselineReasonCodesMatchExpected(parity)
        .baselineEffectsMatchExpected(parity)
        .operationalEvidence(objectMapper.writeValueAsString(operational))
        .build();
  }
}
