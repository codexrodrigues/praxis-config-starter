package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.dto.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.dto.DomainRuleTestRunResponse;
import org.praxisplatform.config.dto.DomainRuleTestRunResultRequest;
import org.praxisplatform.config.dto.DomainRuleTestRunResultResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Append-only safe evidence store for host-executed policy tests. */
@RequiredArgsConstructor
public class DomainRuleTestRunService {
  private static final Set<String> DECISIONS = Set.of("ALLOW","DENY","NOT_APPLICABLE","INCONCLUSIVE","TECHNICAL_ERROR");
  private static final Set<String> COMPARISONS = Set.of("MATCH","MISMATCH","INCONCLUSIVE","TECHNICAL_ERROR");
  private final DomainRuleTestRunRepository runs;
  private final DomainRuleTestRunResultRepository results;
  private final DomainRuleChangeWorkspaceRepository workspaces;
  private final DomainRuleTestScenarioRepository scenarios;
  private final ObjectMapper objectMapper;

  @Transactional
  public DomainRuleTestRunResponse record(UUID workspaceId, DomainRuleTestRunRecordRequest request,
                                          DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(workspaceId, principal);
    validateRequest(workspace, request);
    UUID runId = UUID.randomUUID();
    Instant recordedAt = Instant.now();
    String actor = text(principal.actorRef(), "actor", 255);
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("scenarioCount", request.results().size());
    summary.put("matchCount", request.results().stream().filter(r -> "MATCH".equals(r.comparison())).count());
    summary.put("mismatchCount", request.results().stream().filter(r -> "MISMATCH".equals(r.comparison())).count());
    summary.put("inconclusiveCount", request.results().stream().filter(r -> "INCONCLUSIVE".equals(r.comparison())).count());
    summary.put("technicalErrorCount", request.results().stream().filter(r -> "TECHNICAL_ERROR".equals(r.comparison())).count());
    runs.save(DomainRuleTestRun.builder()
        .id(runId).workspaceId(workspaceId).tenantId(principal.tenantId()).environment(principal.environment())
        .workspaceRevision(request.workspaceRevision()).baseDefinitionHash(request.baseDefinitionHash())
        .evaluatedAt(request.evaluatedAtUtc()).userTimeZone(ZoneId.of(request.userTimeZone()).getId())
        .activeSnapshotKey(blankToNull(request.activeSnapshotKey()))
        .activeSnapshotContentHash(blankToNull(request.activeSnapshotContentHash()))
        .activeActivationRevision(request.activeActivationRevision()).resultSummary(json(summary))
        .recordedBy(actor).recordedAt(recordedAt).build());
    List<DomainRuleTestRunResult> persisted = request.results().stream().map(item -> entity(runId, item)).toList();
    results.saveAll(persisted);
    return response(runId, workspaceId, request, persisted, actor, recordedAt);
  }

  @Transactional(readOnly = true)
  public List<DomainRuleTestRunResponse> list(UUID workspaceId, DomainRuleGovernancePrincipal principal) {
    scopedWorkspace(workspaceId, principal);
    return runs.findByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(
        principal.tenantId(), principal.environment(), workspaceId).stream().map(run -> {
          List<DomainRuleTestRunResult> items = results.findByTestRunIdOrderByScenarioKey(run.getId());
          DomainRuleTestRunRecordRequest request = new DomainRuleTestRunRecordRequest(
              run.getWorkspaceRevision(), run.getBaseDefinitionHash(), run.getEvaluatedAt(), run.getUserTimeZone(),
              run.getActiveSnapshotKey(), run.getActiveSnapshotContentHash(), run.getActiveActivationRevision(), List.of());
          return response(run.getId(), workspaceId, request, items, run.getRecordedBy(), run.getRecordedAt());
        }).toList();
  }

  private void validateRequest(DomainRuleChangeWorkspace workspace, DomainRuleTestRunRecordRequest request) {
    if (request == null || request.results() == null || request.results().isEmpty()) throw bad("results are required");
    if (request.workspaceRevision() != workspace.getRevision() || !workspace.getBaseDefinitionHash().equals(request.baseDefinitionHash()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace revision or base fingerprint changed before evidence was recorded");
    if (request.evaluatedAtUtc() == null) throw bad("evaluatedAtUtc is required");
    try { ZoneId.of(text(request.userTimeZone(), "userTimeZone", 128)); } catch (RuntimeException e) { throw bad("userTimeZone is invalid"); }
    if (request.activeActivationRevision() < 0) throw bad("activeActivationRevision cannot be negative");
    if (request.activeSnapshotContentHash() != null && !request.activeSnapshotContentHash().isBlank()) {
      digest(request.activeSnapshotContentHash(), "activeSnapshotContentHash");
    }
    Set<UUID> seen = new HashSet<>();
    for (DomainRuleTestRunResultRequest item : request.results()) {
      validateResult(workspace.getId(), item, seen);
    }
  }

  private void validateResult(UUID workspaceId, DomainRuleTestRunResultRequest item, Set<UUID> seen) {
    if (item == null || item.scenarioId() == null || !seen.add(item.scenarioId())) throw bad("scenarioId must be unique");
    var scenario = scenarios.findById(item.scenarioId()).filter(s -> workspaceId.equals(s.getWorkspaceId()))
        .orElseThrow(() -> bad("scenario does not belong to the workspace"));
    if (!scenario.getScenarioKey().equals(item.scenarioKey())) throw bad("scenarioKey does not match persisted scenario");
    if (!DECISIONS.contains(item.expectedDecision()) || !DECISIONS.contains(item.candidateDecision()) || !DECISIONS.contains(item.activeDecision()))
      throw bad("test run decisions must use the canonical five states");
    if (!COMPARISONS.contains(item.comparison())) throw bad("comparison is invalid");
    digest(item.candidatePlanDigest(), "candidatePlanDigest"); digest(item.activePlanDigest(), "activePlanDigest"); digest(item.factsDigest(), "factsDigest");
  }

  private DomainRuleTestRunResult entity(UUID runId, DomainRuleTestRunResultRequest item) {
    return DomainRuleTestRunResult.builder().id(UUID.randomUUID()).testRunId(runId).scenarioId(item.scenarioId())
        .scenarioKey(item.scenarioKey()).expectedDecision(item.expectedDecision()).candidateDecision(item.candidateDecision())
        .activeDecision(item.activeDecision()).comparison(item.comparison()).candidateMatchesExpected(item.candidateMatchesExpected())
        .activeMatchesExpected(item.activeMatchesExpected()).candidateReasonCodes(json(item.candidateReasonCodes() == null ? List.of() : item.candidateReasonCodes()))
        .activeReasonCodes(json(item.activeReasonCodes() == null ? List.of() : item.activeReasonCodes()))
        .candidatePlanDigest(item.candidatePlanDigest()).activePlanDigest(item.activePlanDigest()).factsDigest(item.factsDigest()).build();
  }

  private DomainRuleTestRunResponse response(UUID runId, UUID workspaceId, DomainRuleTestRunRecordRequest request,
                                             List<DomainRuleTestRunResult> items, String actor, Instant recordedAt) {
    return new DomainRuleTestRunResponse(runId, workspaceId, request.workspaceRevision(), request.baseDefinitionHash(),
        request.evaluatedAtUtc(), request.userTimeZone(), request.activeSnapshotKey(), request.activeSnapshotContentHash(),
        request.activeActivationRevision(), items.stream().map(this::resultResponse).toList(), actor, recordedAt);
  }
  private DomainRuleTestRunResultResponse resultResponse(DomainRuleTestRunResult e) {
    return new DomainRuleTestRunResultResponse(e.getScenarioId(),e.getScenarioKey(),e.getExpectedDecision(),e.getCandidateDecision(),
        e.getActiveDecision(),e.getComparison(),e.getCandidateMatchesExpected(),e.getActiveMatchesExpected(),
        strings(e.getCandidateReasonCodes()),strings(e.getActiveReasonCodes()),e.getCandidatePlanDigest(),e.getActivePlanDigest(),e.getFactsDigest());
  }
  private DomainRuleChangeWorkspace scopedWorkspace(UUID id, DomainRuleGovernancePrincipal p) {
    return workspaces.findById(id).filter(w -> p.tenantId().equals(w.getTenantId()) && p.environment().equals(w.getEnvironment()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Change workspace not found"));
  }
  private void digest(String value,String field){ if(value==null||!value.matches("[A-Fa-f0-9]{64}")) throw bad(field+" must be SHA-256"); }
  private String text(String v,String field,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw bad(field+" is invalid");return v.trim();}
  private String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
  private String json(Object v){try{return objectMapper.writeValueAsString(v);}catch(JsonProcessingException e){throw bad("result evidence is invalid");}}
  private List<String> strings(String v){try{return objectMapper.readerForListOf(String.class).readValue(v);}catch(JsonProcessingException e){throw new IllegalStateException("Persisted result evidence is invalid",e);}}
  private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
