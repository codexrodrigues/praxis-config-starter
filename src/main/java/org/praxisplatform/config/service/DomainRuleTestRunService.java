package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.contract.DomainRuleTestBaselineEvidence;
import org.praxisplatform.config.contract.DomainRuleTestBaselineResult;
import org.praxisplatform.config.contract.DomainRuleOperationalTestEvidence;
import org.praxisplatform.config.contract.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResponse;
import org.praxisplatform.config.contract.DomainRuleTestRunResultRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResultResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Append-only safe evidence store for host-executed policy tests. */
@RequiredArgsConstructor
public class DomainRuleTestRunService {
  private static final Set<String> DECISIONS = Set.of("ALLOW","DENY","NOT_APPLICABLE","INCONCLUSIVE","TECHNICAL_ERROR");
  private static final int MAX_RESULTS_PER_RUN = 1_000;
  private final DomainRuleTestRunRepository runs;
  private final DomainRuleTestRunResultRepository results;
  private final DomainRuleChangeWorkspaceRepository workspaces;
  private final DomainRuleTestScenarioRepository scenarios;
  private final ObjectMapper objectMapper;

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleTestRunResponse record(UUID workspaceId, DomainRuleTestRunRecordRequest request,
                                          DomainRuleGovernancePrincipal principal) {
    if (request == null) throw bad("request is required");
    DomainRuleChangeWorkspace workspace = scopedWorkspaceForUpdate(workspaceId, principal);
    String idempotencyKey = text(request.idempotencyKey(), "idempotencyKey", 180);
    String requestHash = requestHash(request);
    var replay = runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
        principal.tenantId(), principal.environment(), workspaceId, idempotencyKey);
    if (replay.isPresent()) {
      if (!requestHash.equals(replay.get().getRequestHash())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "idempotencyKey was already used with a different Test Run payload");
      }
      return response(replay.get(), results.findByTestRunIdOrderByScenarioKey(replay.get().getId()));
    }
    validateRequest(workspace, request);
    UUID runId = UUID.randomUUID();
    Instant recordedAt = Instant.now();
    String actor = text(principal.actorRef(), "actor", 255);
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("scenarioCount", request.results().size());
    summary.put("matchCount", request.results().stream().filter(r -> "MATCH".equals(comparison(r.candidateDecision(), r.activeDecision()))).count());
    summary.put("mismatchCount", request.results().stream().filter(r -> "MISMATCH".equals(comparison(r.candidateDecision(), r.activeDecision()))).count());
    summary.put("inconclusiveCount", request.results().stream().filter(r -> "INCONCLUSIVE".equals(comparison(r.candidateDecision(), r.activeDecision()))).count());
    summary.put("technicalErrorCount", request.results().stream().filter(r -> "TECHNICAL_ERROR".equals(comparison(r.candidateDecision(), r.activeDecision()))).count());
    summary.put("baselineScenarioCount", request.results().stream().filter(r -> r.baselineResult() != null).count());
    summary.put("candidateBaselineMismatchCount", request.results().stream()
        .filter(r -> r.baselineResult() != null)
        .filter(r -> "MISMATCH".equals(comparison(r.candidateDecision(), r.baselineResult().decision())))
        .count());
    DomainRuleTestRun persistedRun = runs.save(DomainRuleTestRun.builder()
        .id(runId).workspaceId(workspaceId).tenantId(principal.tenantId()).environment(principal.environment())
        .idempotencyKey(idempotencyKey).requestHash(requestHash)
        .workspaceRevision(request.workspaceRevision()).baseDefinitionHash(request.baseDefinitionHash())
        .evaluatedAt(request.evaluatedAtUtc()).userTimeZone(ZoneId.of(request.userTimeZone()).getId())
        .activeSnapshotKey(blankToNull(request.activeSnapshotKey()))
        .activeSnapshotContentHash(blankToNull(request.activeSnapshotContentHash()))
        .activeActivationRevision(request.activeActivationRevision())
        .baselineEvidence(nullableJson(request.baselineEvidence())).resultSummary(json(summary))
        .recordedBy(actor).recordedAt(recordedAt).build());
    List<DomainRuleTestRunResult> persisted = request.results().stream()
        .map(item -> entity(runId, item, validatedScenario(workspace.getId(), item))).toList();
    results.saveAll(persisted);
    return response(persistedRun, persisted);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleTestRunResponse> list(UUID workspaceId, DomainRuleGovernancePrincipal principal) {
    scopedWorkspace(workspaceId, principal);
    return runs.findByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(
        principal.tenantId(), principal.environment(), workspaceId).stream().map(run -> {
          List<DomainRuleTestRunResult> items = results.findByTestRunIdOrderByScenarioKey(run.getId());
          return response(run, items);
        }).toList();
  }

  /** Returns the immutable receipt for an exact host retry without exposing a cross-scope lookup. */
  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<DomainRuleTestRunResponse> findByIdempotencyKey(
      UUID workspaceId, String idempotencyKey, DomainRuleGovernancePrincipal principal) {
    scopedWorkspace(workspaceId, principal);
    String normalizedKey = text(idempotencyKey, "idempotencyKey", 180);
    return runs.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
            principal.tenantId(), principal.environment(), workspaceId, normalizedKey)
        .map(run -> response(run, results.findByTestRunIdOrderByScenarioKey(run.getId())));
  }

  private void validateRequest(DomainRuleChangeWorkspace workspace, DomainRuleTestRunRecordRequest request) {
    if (request == null || request.results() == null || request.results().isEmpty()) throw bad("results are required");
    if (request.results().size() > MAX_RESULTS_PER_RUN) {
      throw bad("results exceeds " + MAX_RESULTS_PER_RUN + " scenarios");
    }
    if (!"OPEN".equals(workspace.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Test Runs can be recorded only while the change workspace is OPEN");
    }
    if (request.workspaceRevision() != workspace.getRevision() || !workspace.getBaseDefinitionHash().equals(request.baseDefinitionHash()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace revision or base fingerprint changed before evidence was recorded");
    if (request.evaluatedAtUtc() == null) throw bad("evaluatedAtUtc is required");
    try { ZoneId.of(text(request.userTimeZone(), "userTimeZone", 128)); } catch (RuntimeException e) { throw bad("userTimeZone is invalid"); }
    if (request.activeActivationRevision() < 0) throw bad("activeActivationRevision cannot be negative");
    if (request.activeSnapshotContentHash() != null && !request.activeSnapshotContentHash().isBlank()) {
      digest(request.activeSnapshotContentHash(), "activeSnapshotContentHash");
    }
    if (request.activeSnapshotKey() != null && !request.activeSnapshotKey().isBlank()) {
      text(request.activeSnapshotKey(), "activeSnapshotKey", 128);
    }
    validateBaseline(request.baselineEvidence());
    Set<UUID> seen = new HashSet<>();
    for (DomainRuleTestRunResultRequest item : request.results()) {
      validateResult(workspace.getId(), item, request.baselineEvidence(), seen);
    }
  }

  private void validateResult(UUID workspaceId, DomainRuleTestRunResultRequest item,
                              DomainRuleTestBaselineEvidence baselineEvidence, Set<UUID> seen) {
    if (item == null || item.scenarioId() == null || !seen.add(item.scenarioId())) throw bad("scenarioId must be unique");
    var scenario = validatedScenario(workspaceId, item);
    if (!scenario.getScenarioKey().equals(item.scenarioKey())) throw bad("scenarioKey does not match persisted scenario");
    if (!DECISIONS.contains(item.candidateDecision()) || !DECISIONS.contains(item.activeDecision()))
      throw bad("test run decisions must use the canonical five states");
    digest(item.candidatePlanDigest(), "candidatePlanDigest"); digest(item.activePlanDigest(), "activePlanDigest"); digest(item.factsDigest(), "factsDigest");
    assertions(item.candidateReasonCodes(), "candidateReasonCodes");
    assertions(item.activeReasonCodes(), "activeReasonCodes");
    assertions(item.candidateEffectIntents(), "candidateEffectIntents");
    assertions(item.activeEffectIntents(), "activeEffectIntents");
    safeOutput(item.candidateOutput(), "candidateOutput");
    safeOutput(item.activeOutput(), "activeOutput");
    validateBaselineResult(baselineEvidence, item.baselineResult());
    validateOperational(item.operationalEvidence());
  }

  private org.praxisplatform.config.domain.DomainRuleTestScenario validatedScenario(
      UUID workspaceId, DomainRuleTestRunResultRequest item) {
    return scenarios.findById(item.scenarioId()).filter(s -> workspaceId.equals(s.getWorkspaceId()))
        .orElseThrow(() -> bad("scenario does not belong to the workspace"));
  }

  private DomainRuleTestRunResult entity(
      UUID runId, DomainRuleTestRunResultRequest item,
      org.praxisplatform.config.domain.DomainRuleTestScenario scenario) {
    JsonNode expectedOutput = tree(scenario.getExpectedOutput());
    List<String> expectedReasons = strings(scenario.getExpectedReasonCodes());
    List<String> expectedEffects = strings(scenario.getExpectedEffectIntents());
    List<String> candidateReasons = assertions(item.candidateReasonCodes(), "candidateReasonCodes");
    List<String> activeReasons = assertions(item.activeReasonCodes(), "activeReasonCodes");
    List<String> candidateEffects = assertions(item.candidateEffectIntents(), "candidateEffectIntents");
    List<String> activeEffects = assertions(item.activeEffectIntents(), "activeEffectIntents");
    DomainRuleTestBaselineResult baseline = item.baselineResult();
    List<String> baselineReasons = baseline == null ? List.of()
        : assertions(baseline.reasonCodes(), "baselineResult.reasonCodes");
    List<String> baselineEffects = baseline == null ? List.of()
        : assertions(baseline.effectIntents(), "baselineResult.effectIntents");
    boolean candidateOutputMatch = expectedOutput == null || Objects.equals(expectedOutput, item.candidateOutput());
    boolean activeOutputMatch = expectedOutput == null || Objects.equals(expectedOutput, item.activeOutput());
    boolean baselineOutputMatch = baseline != null
        && (expectedOutput == null || Objects.equals(expectedOutput, baseline.output()));
    return DomainRuleTestRunResult.builder().id(UUID.randomUUID()).testRunId(runId).scenarioId(item.scenarioId())
        .scenarioKey(item.scenarioKey()).expectedDecision(scenario.getExpectedDecision()).candidateDecision(item.candidateDecision())
        .activeDecision(item.activeDecision()).comparison(comparison(item.candidateDecision(), item.activeDecision()))
        .candidateMatchesExpected(item.candidateDecision().equals(scenario.getExpectedDecision()))
        .activeMatchesExpected(item.activeDecision().equals(scenario.getExpectedDecision()))
        .expectedOutput(nullableJson(expectedOutput)).candidateOutput(nullableJson(item.candidateOutput()))
        .activeOutput(nullableJson(item.activeOutput())).candidateOutputMatchesExpected(candidateOutputMatch)
        .activeOutputMatchesExpected(activeOutputMatch).expectedReasonCodes(json(expectedReasons))
        .candidateReasonCodes(json(candidateReasons)).activeReasonCodes(json(activeReasons))
        .candidateReasonCodesMatchExpected(candidateReasons.equals(expectedReasons))
        .activeReasonCodesMatchExpected(activeReasons.equals(expectedReasons))
        .expectedEffectIntents(json(expectedEffects)).candidateEffectIntents(json(candidateEffects))
        .activeEffectIntents(json(activeEffects)).candidateEffectsMatchExpected(candidateEffects.equals(expectedEffects))
        .activeEffectsMatchExpected(activeEffects.equals(expectedEffects))
        .candidatePlanDigest(item.candidatePlanDigest()).activePlanDigest(item.activePlanDigest())
        .factsDigest(item.factsDigest()).baselineResult(nullableJson(baseline))
        .candidateBaselineComparison(baseline == null ? null
            : comparison(item.candidateDecision(), baseline.decision()))
        .baselineMatchesExpected(baseline != null
            && baseline.decision().equals(scenario.getExpectedDecision()))
        .baselineOutputMatchesExpected(baselineOutputMatch)
        .baselineReasonCodesMatchExpected(baseline != null && baselineReasons.equals(expectedReasons))
        .baselineEffectsMatchExpected(baseline != null && baselineEffects.equals(expectedEffects))
        .operationalEvidence(nullableJson(item.operationalEvidence())).build();
  }

  private DomainRuleTestRunResponse response(
      DomainRuleTestRun run, List<DomainRuleTestRunResult> items) {
    return new DomainRuleTestRunResponse(
        run.getId(), run.getWorkspaceId(), run.getIdempotencyKey(), run.getRequestHash(),
        run.getWorkspaceRevision(), run.getBaseDefinitionHash(), run.getEvaluatedAt(), run.getUserTimeZone(),
        run.getActiveSnapshotKey(), run.getActiveSnapshotContentHash(), run.getActiveActivationRevision(),
        value(run.getBaselineEvidence(), DomainRuleTestBaselineEvidence.class),
        items.stream().map(this::resultResponse).toList(), run.getRecordedBy(), run.getRecordedAt());
  }
  private DomainRuleTestRunResultResponse resultResponse(DomainRuleTestRunResult e) {
    return new DomainRuleTestRunResultResponse(e.getScenarioId(),e.getScenarioKey(),e.getExpectedDecision(),e.getCandidateDecision(),
        e.getActiveDecision(),e.getComparison(),e.getCandidateMatchesExpected(),e.getActiveMatchesExpected(),
        tree(e.getExpectedOutput()),tree(e.getCandidateOutput()),tree(e.getActiveOutput()),
        e.getCandidateOutputMatchesExpected(),e.getActiveOutputMatchesExpected(),strings(e.getExpectedReasonCodes()),
        strings(e.getCandidateReasonCodes()),strings(e.getActiveReasonCodes()),e.getCandidateReasonCodesMatchExpected(),
        e.getActiveReasonCodesMatchExpected(),strings(e.getExpectedEffectIntents()),strings(e.getCandidateEffectIntents()),
        strings(e.getActiveEffectIntents()),e.getCandidateEffectsMatchExpected(),e.getActiveEffectsMatchExpected(),
        e.getCandidatePlanDigest(),e.getActivePlanDigest(),e.getFactsDigest(),
        value(e.getBaselineResult(), DomainRuleTestBaselineResult.class),
        e.getCandidateBaselineComparison(), Boolean.TRUE.equals(e.getBaselineMatchesExpected()),
        Boolean.TRUE.equals(e.getBaselineOutputMatchesExpected()),
        Boolean.TRUE.equals(e.getBaselineReasonCodesMatchExpected()),
        Boolean.TRUE.equals(e.getBaselineEffectsMatchExpected()),
        value(e.getOperationalEvidence(), DomainRuleOperationalTestEvidence.class));
  }
  private void validateBaseline(DomainRuleTestBaselineEvidence evidence) {
    if (evidence == null) return;
    if (!Set.of("SYNTHETIC_EXPECTED", "ACTIVE_SNAPSHOT", "LEGACY_ORACLE").contains(evidence.authorityType()))
      throw bad("baselineEvidence.authorityType is invalid");
    text(evidence.artifactRef(), "baselineEvidence.artifactRef", 512);
    digest(evidence.artifactDigest(), "baselineEvidence.artifactDigest");
    if (evidence.observedAtUtc() == null) throw bad("baselineEvidence.observedAtUtc is required");
    if (!Set.of("ELIGIBLE", "INELIGIBLE", "PENDING").contains(evidence.eligibility()))
      throw bad("baselineEvidence.eligibility is invalid");
  }
  private void validateBaselineResult(
      DomainRuleTestBaselineEvidence evidence, DomainRuleTestBaselineResult result) {
    if (result == null) {
      if (evidence != null && "ELIGIBLE".equals(evidence.eligibility())) {
        throw bad("baselineResult is required for every scenario when baseline evidence is ELIGIBLE");
      }
      return;
    }
    if (evidence == null) throw bad("baselineEvidence is required when baselineResult is present");
    if (!DECISIONS.contains(result.decision())) {
      throw bad("baselineResult.decision must use the canonical five states");
    }
    safeOutput(result.output(), "baselineResult.output");
    assertions(result.reasonCodes(), "baselineResult.reasonCodes");
    assertions(result.effectIntents(), "baselineResult.effectIntents");
    digest(result.planDigest(), "baselineResult.planDigest");
    optionalDigest(result.traceDigest(), "baselineResult.traceDigest");
    if ("TECHNICAL_ERROR".equals(result.decision())) {
      text(result.errorCode(), "baselineResult.errorCode", 255);
    } else if (result.errorCode() != null && !result.errorCode().isBlank()) {
      throw bad("baselineResult.errorCode is only valid for TECHNICAL_ERROR");
    }
  }
  private void validateOperational(DomainRuleOperationalTestEvidence evidence) {
    if (evidence == null) return;
    if (!Set.of("CREATE", "UPDATE").contains(evidence.operationMode()))
      throw bad("operationalEvidence.operationMode is invalid");
    if ("UPDATE".equals(evidence.operationMode())) digest(evidence.beforeStateDigest(), "operationalEvidence.beforeStateDigest");
    else optionalDigest(evidence.beforeStateDigest(), "operationalEvidence.beforeStateDigest");
    if (evidence.mutationObserved()) digest(evidence.afterStateDigest(), "operationalEvidence.afterStateDigest");
    else optionalDigest(evidence.afterStateDigest(), "operationalEvidence.afterStateDigest");
    optionalDigest(evidence.effectLedgerDigest(), "operationalEvidence.effectLedgerDigest");
    if (evidence.mutationObserved() && evidence.noMutationVerified())
      throw bad("operationalEvidence cannot report mutation and no-mutation together");
    if (!evidence.mutationObserved() && !evidence.noMutationVerified())
      throw bad("operationalEvidence must verify no-mutation when no mutation was observed");
    if (evidence.baselineCallCount() < 0) throw bad("operationalEvidence.baselineCallCount cannot be negative");
  }
  private DomainRuleChangeWorkspace scopedWorkspace(UUID id, DomainRuleGovernancePrincipal p) {
    return workspaces.findById(id).filter(w -> p.tenantId().equals(w.getTenantId()) && p.environment().equals(w.getEnvironment()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Change workspace not found"));
  }
  private DomainRuleChangeWorkspace scopedWorkspaceForUpdate(UUID id, DomainRuleGovernancePrincipal p) {
    return workspaces.findByIdForUpdate(id)
        .filter(w -> p.tenantId().equals(w.getTenantId()) && p.environment().equals(w.getEnvironment()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Change workspace not found"));
  }
  private String requestHash(DomainRuleTestRunRecordRequest request) {
    ObjectNode payload = objectMapper.valueToTree(request);
    payload.remove("idempotencyKey");
    JsonNode resultNode = payload.get("results");
    if (resultNode instanceof ArrayNode resultArray) {
      List<JsonNode> ordered = new ArrayList<>();
      resultArray.forEach(item -> {
        if (item instanceof ObjectNode result) {
          sortTextArray(result, "candidateReasonCodes");
          sortTextArray(result, "activeReasonCodes");
          sortTextArray(result, "candidateEffectIntents");
          sortTextArray(result, "activeEffectIntents");
          if (result.get("baselineResult") instanceof ObjectNode baseline) {
            sortTextArray(baseline, "reasonCodes");
            sortTextArray(baseline, "effectIntents");
          }
        }
        ordered.add(item);
      });
      ordered.sort(Comparator
          .comparing((JsonNode item) -> item.path("scenarioId").asText())
          .thenComparing(item -> item.path("scenarioKey").asText()));
      resultArray.removeAll();
      resultArray.addAll(ordered);
    }
    return PraxisCanonicalJson.sha256(payload);
  }
  private void sortTextArray(ObjectNode parent, String field) {
    if (!(parent.get(field) instanceof ArrayNode values)) return;
    Set<String> ordered = new java.util.TreeSet<>();
    values.forEach(value -> ordered.add(value.asText()));
    values.removeAll();
    ordered.forEach(values::add);
  }
  private void digest(String value,String field){ if(value==null||!value.matches("[A-Fa-f0-9]{64}")) throw bad(field+" must be SHA-256"); }
  private void optionalDigest(String value,String field){if(value!=null&&!value.isBlank())digest(value,field);}
  private String text(String v,String field,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw bad(field+" is invalid");return v.trim();}
  private String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
  private String json(Object v){try{return objectMapper.writeValueAsString(v);}catch(JsonProcessingException e){throw bad("result evidence is invalid");}}
  private String nullableJson(JsonNode value){return value==null||value.isNull()?null:json(value);}
  private String nullableJson(Object value){return value==null?null:json(value);}
  private JsonNode tree(String value){try{return value==null?null:objectMapper.readTree(value);}catch(JsonProcessingException e){throw new IllegalStateException("Persisted result output is invalid",e);}}
  private List<String> strings(String v){try{return v==null?List.of():objectMapper.readerForListOf(String.class).readValue(v);}catch(JsonProcessingException e){throw new IllegalStateException("Persisted result evidence is invalid",e);}}
  private <T> T value(String json,Class<T> type){try{return json==null?null:objectMapper.readValue(json,type);}catch(JsonProcessingException e){throw new IllegalStateException("Persisted test evidence is invalid",e);}}
  private List<String> assertions(List<String> values,String field){if(values==null)return List.of();if(values.size()>100)throw bad(field+" exceeds 100 entries");return values.stream().map(v->text(v,field,255)).distinct().sorted().toList();}
  private void safeOutput(JsonNode value,String field){if(value!=null&&!value.isNull()&&value.toString().length()>16384)throw bad(field+" exceeds 16384 characters");}
  private String comparison(String candidate,String active){if("TECHNICAL_ERROR".equals(candidate)||"TECHNICAL_ERROR".equals(active))return "TECHNICAL_ERROR";if("INCONCLUSIVE".equals(candidate)||"INCONCLUSIVE".equals(active))return "INCONCLUSIVE";return candidate.equals(active)?"MATCH":"MISMATCH";}
  private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
