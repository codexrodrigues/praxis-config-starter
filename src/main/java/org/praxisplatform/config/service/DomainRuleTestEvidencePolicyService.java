package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.praxisplatform.config.contract.DomainRuleOperationalTestEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineEvidence;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;

/** Evaluates server-owned, stage-specific evidence requirements declared by a rule definition. */
public class DomainRuleTestEvidencePolicyService {
  private static final Set<String> STAGE_FIELDS = Set.of(
      "baselineAuthorityType", "baselineEligibility", "requiredOperationModes",
      "requiredDecisions", "requireCleanupVerified", "requireBaselineMatch");
  private static final Set<String> POLICY_FIELDS = Set.of("stages");
  private static final Set<String> SUPPORTED_STAGES = Set.of("SUBMIT", "PROMOTE", "PUBLISH");
  private static final Set<String> AUTHORITIES = Set.of(
      "SYNTHETIC_EXPECTED", "ACTIVE_SNAPSHOT", "LEGACY_ORACLE");
  private static final Set<String> ELIGIBILITIES = Set.of("ELIGIBLE", "INELIGIBLE", "PENDING");
  private static final Set<String> OPERATIONS = Set.of("CREATE", "UPDATE");
  private static final Set<String> DECISIONS = Set.of(
      "ALLOW", "DENY", "NOT_APPLICABLE", "INCONCLUSIVE", "TECHNICAL_ERROR");

  private final ObjectMapper objectMapper;

  public DomainRuleTestEvidencePolicyService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<DomainRuleWorkspaceBlocker> blockers(
      String stage,
      DomainRuleDefinition definition,
      DomainRuleTestRun run,
      List<DomainRuleTestRunResult> results) {
    String normalizedStage = stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT);
    try {
      JsonNode policy = stagePolicy(definition, normalizedStage);
      if (policy == null) return List.of();
      StageRequirements requirements = requirements(policy);
      if (run == null) {
        return List.of(blocker("BOUND_TEST_RUN_REQUIRED", normalizedStage,
            "The governed stage requires the Test Run bound at submission"));
      }
      List<DomainRuleTestRunResult> evidence = results == null ? List.of() : results;
      DomainRuleTestBaselineEvidence baseline = value(
          run.getBaselineEvidence(), DomainRuleTestBaselineEvidence.class);
      List<DomainRuleWorkspaceBlocker> blockers = new ArrayList<>();

      if (requirements.baselineAuthorityType() != null
          && (baseline == null || !requirements.baselineAuthorityType().equals(baseline.authorityType()))) {
        blockers.add(blocker("REQUIRED_BASELINE_AUTHORITY_MISSING", normalizedStage,
            "The bound Test Run does not use the required baseline authority"));
      }
      if (requirements.baselineEligibility() != null
          && (baseline == null || !requirements.baselineEligibility().equals(baseline.eligibility()))) {
        blockers.add(blocker("REQUIRED_BASELINE_ELIGIBILITY_MISSING", normalizedStage,
            "The bound Test Run baseline is not eligible for this governed stage"));
      }
      if (requirements.requireCleanupVerified() && evidence.stream().anyMatch(item -> {
        DomainRuleOperationalTestEvidence operational = value(
            item.getOperationalEvidence(), DomainRuleOperationalTestEvidence.class);
        return operational == null || !operational.cleanupVerified();
      })) {
        blockers.add(blocker("CLEANUP_EVIDENCE_INCOMPLETE", normalizedStage,
            "Every scenario must prove operational cleanup before this governed stage"));
      }
      if (requirements.requireBaselineMatch() && evidence.stream().anyMatch(item ->
          item.getBaselineResult() == null
              || !"MATCH".equals(item.getCandidateBaselineComparison())
              || !Boolean.TRUE.equals(item.getBaselineMatchesExpected())
              || !Boolean.TRUE.equals(item.getBaselineOutputMatchesExpected())
              || !Boolean.TRUE.equals(item.getBaselineReasonCodesMatchExpected())
              || !Boolean.TRUE.equals(item.getBaselineEffectsMatchExpected()))) {
        blockers.add(blocker("BASELINE_PARITY_INCOMPLETE", normalizedStage,
            "Every scenario must match the independent baseline lane before this governed stage"));
      }
      if (!requirements.requiredOperationModes().isEmpty()) {
        Set<OperationDecision> observed = new HashSet<>();
        for (DomainRuleTestRunResult item : evidence) {
          DomainRuleOperationalTestEvidence operational = value(
              item.getOperationalEvidence(), DomainRuleOperationalTestEvidence.class);
          if (operational != null) {
            observed.add(new OperationDecision(operational.operationMode(), item.getCandidateDecision()));
          }
        }
        boolean incomplete = requirements.requiredOperationModes().stream().anyMatch(operation ->
            requirements.requiredDecisions().stream().anyMatch(decision ->
                !observed.contains(new OperationDecision(operation, decision))));
        if (incomplete) {
          blockers.add(blocker("OPERATION_DECISION_MATRIX_INCOMPLETE", normalizedStage,
              "The bound Test Run does not cover every required operation and decision pair"));
        }
      }
      return List.copyOf(blockers);
    } catch (IllegalArgumentException exception) {
      return List.of(blocker("TEST_EVIDENCE_POLICY_INVALID", normalizedStage,
          "The canonical testEvidencePolicy is invalid: " + exception.getMessage()));
    }
  }

  private JsonNode stagePolicy(DomainRuleDefinition definition, String stage) {
    if (definition == null || definition.getGovernance() == null || definition.getGovernance().isBlank()) {
      return null;
    }
    JsonNode governance;
    try {
      governance = objectMapper.readTree(definition.getGovernance());
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("governance is not valid JSON", exception);
    }
    JsonNode policy = governance.get("testEvidencePolicy");
    if (policy == null || policy.isNull()) return null;
    if (!policy.isObject()) throw new IllegalArgumentException("testEvidencePolicy must be an object");
    policy.fieldNames().forEachRemaining(field -> {
      if (!POLICY_FIELDS.contains(field)) {
        throw new IllegalArgumentException("unknown testEvidencePolicy field " + field);
      }
    });
    JsonNode stages = policy.get("stages");
    if (stages == null || !stages.isObject()) {
      throw new IllegalArgumentException("testEvidencePolicy.stages must be an object");
    }
    stages.fieldNames().forEachRemaining(configuredStage -> {
      if (!SUPPORTED_STAGES.contains(configuredStage)) {
        throw new IllegalArgumentException("unsupported test evidence stage " + configuredStage);
      }
    });
    if (!SUPPORTED_STAGES.contains(stage)) {
      throw new IllegalArgumentException("unsupported evaluated stage " + stage);
    }
    JsonNode selected = stages.get(stage);
    if (selected == null || selected.isNull()) return null;
    if (!selected.isObject()) throw new IllegalArgumentException("stage policy must be an object");
    selected.fieldNames().forEachRemaining(field -> {
      if (!STAGE_FIELDS.contains(field)) throw new IllegalArgumentException("unknown stage field " + field);
    });
    return selected;
  }

  private StageRequirements requirements(JsonNode node) {
    String authority = optionalEnum(node, "baselineAuthorityType", AUTHORITIES);
    String eligibility = optionalEnum(node, "baselineEligibility", ELIGIBILITIES);
    Set<String> operations = enumSet(node, "requiredOperationModes", OPERATIONS);
    Set<String> decisions = enumSet(node, "requiredDecisions", DECISIONS);
    if (operations.isEmpty() != decisions.isEmpty()) {
      throw new IllegalArgumentException(
          "requiredOperationModes and requiredDecisions must be declared together");
    }
    return new StageRequirements(authority, eligibility, operations, decisions,
        booleanValue(node, "requireCleanupVerified"), booleanValue(node, "requireBaselineMatch"));
  }

  private String optionalEnum(JsonNode node, String field, Set<String> allowed) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
    String normalized = value.textValue().trim().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is invalid");
    return normalized;
  }

  private Set<String> enumSet(JsonNode node, String field, Set<String> allowed) {
    JsonNode values = node.get(field);
    if (values == null || values.isNull()) return Set.of();
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalArgumentException(field + " must be a non-empty array");
    }
    Set<String> normalized = new HashSet<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) throw new IllegalArgumentException(field + " entries must be strings");
      String item = value.textValue().trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(item)) throw new IllegalArgumentException(field + " contains an invalid value");
      normalized.add(item);
    }
    return Set.copyOf(normalized);
  }

  private boolean booleanValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return false;
    if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
    return value.booleanValue();
  }

  private <T> T value(String json, Class<T> type) {
    if (json == null) return null;
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("persisted Test Run evidence is invalid", exception);
    }
  }

  private DomainRuleWorkspaceBlocker blocker(String code, String action, String message) {
    return new DomainRuleWorkspaceBlocker(code, action, message);
  }

  private record StageRequirements(
      String baselineAuthorityType,
      String baselineEligibility,
      Set<String> requiredOperationModes,
      Set<String> requiredDecisions,
      boolean requireCleanupVerified,
      boolean requireBaselineMatch) {}

  private record OperationDecision(String operation, String decision) {}
}
