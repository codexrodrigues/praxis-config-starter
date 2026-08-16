package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceCreateRequest;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceUpdateRequest;
import org.praxisplatform.config.dto.DomainRuleTestScenarioRequest;
import org.praxisplatform.config.dto.DomainRuleWorkspaceReviewRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleWorkspaceReviewRepository;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DomainRuleChangeWorkspaceServiceTest {
  private static final DomainRuleGovernancePrincipal PRINCIPAL =
      new DomainRuleGovernancePrincipal("tenant-a", "author-a", "dev");
  private final ObjectMapper objectMapper = new ObjectMapper();
  private DomainRuleChangeWorkspaceRepository workspaces;
  private DomainRuleTestScenarioRepository scenarios;
  private DomainRuleDefinitionRepository definitions;
  private DomainRuleDefinitionFingerprint fingerprint;
  private DomainRuleChangeWorkspaceService service;
  private DomainRuleTestRunRepository runs;
  private DomainRuleTestRunResultRepository runResults;
  private DomainRuleWorkspaceReviewRepository reviews;
  private DomainRuleService domainRules;
  private DomainRuleTestEvidencePolicyService evidencePolicies;

  @BeforeEach
  void setUp() {
    workspaces = mock(DomainRuleChangeWorkspaceRepository.class);
    scenarios = mock(DomainRuleTestScenarioRepository.class);
    definitions = mock(DomainRuleDefinitionRepository.class);
    fingerprint = mock(DomainRuleDefinitionFingerprint.class);
    runs = mock(DomainRuleTestRunRepository.class);
    runResults = mock(DomainRuleTestRunResultRepository.class);
    reviews = mock(DomainRuleWorkspaceReviewRepository.class);
    domainRules = mock(DomainRuleService.class);
    evidencePolicies = new DomainRuleTestEvidencePolicyService(objectMapper);
    service = new DomainRuleChangeWorkspaceService(
        workspaces, scenarios, definitions, fingerprint, objectMapper, runs, runResults, reviews,
        domainRules, evidencePolicies);
    when(workspaces.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scenarios.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(reviews.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(definitions.findById(any())).thenAnswer(invocation -> Optional.of(definition("tenant-a", "dev")));
  }

  @Test
  void createsWorkspaceAnchoredToCanonicalDefinitionFingerprint() throws Exception {
    DomainRuleDefinition base = definition("tenant-a", "dev");
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("A".repeat(64));

    var response = service.create(
        new DomainRuleChangeWorkspaceCreateRequest(base.getId(), "Alterar elegibilidade"), PRINCIPAL);

    assertThat(response.baseDefinitionHash()).isEqualTo("A".repeat(64));
    assertThat(response.condition()).isEqualTo(objectMapper.readTree("{\"==\":[1,1]}"));
    assertThat(response.revision()).isEqualTo(1);
    assertThat(response.createdBy()).isEqualTo("author-a");
  }

  @Test
  void rejectsCrossScopeWorkspaceAsNotFound() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace foreign = workspace(id, "tenant-b", "dev");
    when(workspaces.findById(id)).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.get(id, PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404 NOT_FOUND");
  }

  @Test
  void requiresStrongCurrentEtagAndRotatesItOnDraftSave() throws Exception {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    DomainRuleDefinition base = definition("tenant-a", "dev");
    workspace.setBaseDefinitionId(base.getId());
    workspace.setBaseDefinitionHash("B".repeat(64));
    UUID priorEtag = workspace.getEtag();
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));

    assertThatThrownBy(() -> service.updateDraft(
        id,
        new DomainRuleChangeWorkspaceUpdateRequest(
            objectMapper.readTree("{\">\":[1,0]}"), objectMapper.createObjectNode(), "reason"),
        "W/\"" + priorEtag + "\"", PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("412 PRECONDITION_FAILED");

    var response = service.updateDraft(
        id,
        new DomainRuleChangeWorkspaceUpdateRequest(
            objectMapper.readTree("{\">\":[1,0]}"), objectMapper.createObjectNode(), "reason"),
        "\"" + priorEtag + "\"", PRINCIPAL);

    assertThat(response.etag()).isNotEqualTo(priorEtag.toString());
    assertThat(response.revision()).isEqualTo(2);
    assertThat(response.rationale()).isEqualTo("reason");
  }

  @Test
  void persistsCanonicalFiveStateScenarioAndRejectsUnknownOutcome() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(workspaceId, "tenant-a", "dev");
    when(workspaces.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(workspace));
    when(scenarios.findByWorkspaceIdAndScenarioKey(workspaceId, "boundary-null"))
        .thenReturn(Optional.empty());

    var response = service.createScenario(
        workspaceId,
        new DomainRuleTestScenarioRequest(
            "boundary-null", "Null guard", objectMapper.readTree("{\"amount\":null}"),
            "INCONCLUSIVE", null, List.of(), List.of(), null),
        PRINCIPAL);

    assertThat(response.expectedDecision()).isEqualTo("INCONCLUSIVE");
    assertThat(response.status()).isEqualTo("ACTIVE");
    ArgumentCaptor<DomainRuleTestScenario> saved = ArgumentCaptor.forClass(DomainRuleTestScenario.class);
    verify(scenarios).save(saved.capture());
    assertThat(saved.getValue().getFacts()).isEqualTo("{\"amount\":null}");
    assertThat(workspace.getRevision()).isEqualTo(2L);
    verify(workspaces).save(workspace);

    assertThatThrownBy(() -> service.createScenario(
        workspaceId,
        new DomainRuleTestScenarioRequest(
            "bad", "Bad", objectMapper.createObjectNode(), "PASS", null, List.of(), List.of(), "ACTIVE"),
        PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("five-state");
  }

  @Test
  void updatesEveryScenarioAssertionAndInvalidatesEarlierEvidence() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(workspaceId, "tenant-a", "dev");
    UUID scenarioEtag = UUID.randomUUID();
    DomainRuleTestScenario scenario = DomainRuleTestScenario.builder()
        .id(scenarioId).workspaceId(workspaceId).tenantId("tenant-a").environment("dev")
        .scenarioKey("boundary").name("Old").facts("{}")
        .expectedDecision("ALLOW").expectedReasonCodes("[]").expectedEffectIntents("[]")
        .status("ACTIVE").revision(1L).etag(scenarioEtag).build();
    when(workspaces.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(workspace));
    when(scenarios.findById(scenarioId)).thenReturn(Optional.of(scenario));
    when(scenarios.findByWorkspaceIdAndScenarioKey(workspaceId, "boundary"))
        .thenReturn(Optional.of(scenario));

    var response = service.updateScenario(
        workspaceId, scenarioId,
        new DomainRuleTestScenarioRequest(
            "boundary", "Updated", objectMapper.readTree("{\"amount\":500}"), "DENY", null,
            List.of("LIMIT_EXCEEDED"), List.of("DO_NOT_REGISTER"), "ACTIVE"),
        "\"" + scenarioEtag + "\"", PRINCIPAL);

    assertThat(response.expectedDecision()).isEqualTo("DENY");
    assertThat(response.expectedReasonCodes()).containsExactly("LIMIT_EXCEEDED");
    assertThat(response.expectedEffectIntents()).containsExactly("DO_NOT_REGISTER");
    assertThat(workspace.getRevision()).isEqualTo(2L);
    verify(workspaces).save(workspace);
  }

  @Test
  void submitsOnlyWhenLatestRunProvesCurrentRevisionWithoutTechnicalEvidence() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    UUID runId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    when(workspaces.findById(id)).thenReturn(Optional.of(workspace));
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(scenarios.findByWorkspaceIdOrderByScenarioKey(id)).thenReturn(List.of(
        DomainRuleTestScenario.builder().id(scenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build()));
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc("tenant-a", "dev", id))
        .thenReturn(Optional.of(DomainRuleTestRun.builder().id(runId).workspaceRevision(1L)
            .baseDefinitionHash("B".repeat(64)).build()));
    when(runResults.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of(
        DomainRuleTestRunResult.builder().scenarioId(scenarioId).candidateMatchesExpected(true)
            .candidateOutputMatchesExpected(true).candidateReasonCodesMatchExpected(true)
            .candidateEffectsMatchExpected(true)
            .candidateDecision("ALLOW").comparison("MATCH").build()));

    var submitted = service.submit(id, "\"" + workspace.getEtag() + "\"", PRINCIPAL);

    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(submitted.revision()).isEqualTo(2);
    assertThat(submitted.submittedTestRunId()).isEqualTo(runId);
  }

  @Test
  void allowsFirstPublicationSubmissionWhenCandidatePassesAndActiveSnapshotIsUnavailable() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    UUID runId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    when(workspaces.findById(id)).thenReturn(Optional.of(workspace));
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(scenarios.findByWorkspaceIdOrderByScenarioKey(id)).thenReturn(List.of(
        DomainRuleTestScenario.builder().id(scenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build()));
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc("tenant-a", "dev", id))
        .thenReturn(Optional.of(DomainRuleTestRun.builder().id(runId).workspaceRevision(1L)
            .baseDefinitionHash("B".repeat(64)).build()));
    when(runResults.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of(
        DomainRuleTestRunResult.builder().scenarioId(scenarioId).candidateMatchesExpected(true)
            .candidateOutputMatchesExpected(true).candidateReasonCodesMatchExpected(true)
            .candidateEffectsMatchExpected(true)
            .candidateDecision("ALLOW")
            .comparison("TECHNICAL_ERROR").build()));

    var submitted = service.submit(id, "\"" + workspace.getEtag() + "\"", PRINCIPAL);

    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(submitted.submittedTestRunId()).isEqualTo(runId);
  }

  @Test
  void rejectsSubmissionWhenCandidateEvidenceIsTechnical() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    UUID runId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    when(workspaces.findById(id)).thenReturn(Optional.of(workspace));
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(scenarios.findByWorkspaceIdOrderByScenarioKey(id)).thenReturn(List.of(
        DomainRuleTestScenario.builder().id(scenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build()));
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc("tenant-a", "dev", id))
        .thenReturn(Optional.of(DomainRuleTestRun.builder().id(runId).workspaceRevision(1L)
            .baseDefinitionHash("B".repeat(64)).build()));
    when(runResults.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of(
        DomainRuleTestRunResult.builder().scenarioId(scenarioId).candidateMatchesExpected(true)
            .candidateOutputMatchesExpected(true).candidateReasonCodesMatchExpected(true)
            .candidateEffectsMatchExpected(true)
            .candidateDecision("TECHNICAL_ERROR")
            .comparison("TECHNICAL_ERROR").build()));

    assertThatThrownBy(() -> service.submit(id, "\"" + workspace.getEtag() + "\"", PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("every active scenario");
  }

  @Test
  void rejectsSubmissionWhenLatestRunDoesNotCoverEveryActiveScenario() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    UUID runId = UUID.randomUUID();
    UUID coveredScenarioId = UUID.randomUUID();
    UUID missingScenarioId = UUID.randomUUID();
    when(workspaces.findById(id)).thenReturn(Optional.of(workspace));
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(scenarios.findByWorkspaceIdOrderByScenarioKey(id)).thenReturn(List.of(
        DomainRuleTestScenario.builder().id(coveredScenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build(),
        DomainRuleTestScenario.builder().id(missingScenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build()));
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc("tenant-a", "dev", id))
        .thenReturn(Optional.of(DomainRuleTestRun.builder().id(runId).workspaceRevision(1L)
            .baseDefinitionHash("B".repeat(64)).build()));
    when(runResults.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of(
        DomainRuleTestRunResult.builder().scenarioId(coveredScenarioId).candidateMatchesExpected(true)
            .candidateOutputMatchesExpected(true).candidateReasonCodesMatchExpected(true)
            .candidateEffectsMatchExpected(true)
            .comparison("MATCH").build()));

    assertThatThrownBy(() -> service.submit(id, "\"" + workspace.getEtag() + "\"", PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("every active scenario");
  }

  @Test
  void publishesSubmitOnlyWhenServerOwnedTestRunGatePasses() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    when(workspaces.findById(id)).thenReturn(Optional.of(workspace));
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(
        "tenant-a", "dev", id)).thenReturn(Optional.empty());

    var blocked = service.capabilities(id, PRINCIPAL, true, false);

    assertThat(blocked.availableActions())
        .containsExactly("VIEW", "UPDATE_DRAFT", "MANAGE_SCENARIOS", "RECORD_TEST_RUN")
        .doesNotContain("SUBMIT");
    assertThat(blocked.blockers()).extracting("code")
        .containsExactly("CURRENT_PASSING_TEST_RUN_REQUIRED");

    UUID runId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    when(runs.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(
        "tenant-a", "dev", id)).thenReturn(Optional.of(
            DomainRuleTestRun.builder().id(runId).workspaceRevision(1L)
                .baseDefinitionHash("B".repeat(64)).build()));
    when(scenarios.findByWorkspaceIdOrderByScenarioKey(id)).thenReturn(List.of(
        DomainRuleTestScenario.builder().id(scenarioId).tenantId("tenant-a").environment("dev")
            .status("ACTIVE").build()));
    when(runResults.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of(
        DomainRuleTestRunResult.builder().scenarioId(scenarioId).candidateMatchesExpected(true)
            .candidateOutputMatchesExpected(true).candidateReasonCodesMatchExpected(true)
            .candidateEffectsMatchExpected(true)
            .candidateDecision("ALLOW").comparison("TECHNICAL_ERROR").build()));

    var ready = service.capabilities(id, PRINCIPAL, true, false);

    assertThat(ready.availableActions()).contains("SUBMIT");
    assertThat(ready.blockers()).isEmpty();
  }

  @Test
  void publishesReviewOnlyToDifferentApproverAndPromoteOnlyToAuthor() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace submitted = workspace(id, "tenant-a", "dev");
    submitted.setStatus("SUBMITTED");
    when(workspaces.findById(id)).thenReturn(Optional.of(submitted));

    var authorApprover = service.capabilities(id, PRINCIPAL, true, true);
    assertThat(authorApprover.availableActions()).containsExactly("VIEW");
    assertThat(authorApprover.blockers()).extracting("code")
        .containsExactly("REVIEWER_MUST_DIFFER_FROM_AUTHOR");

    var independentApprover = service.capabilities(
        id, new DomainRuleGovernancePrincipal("tenant-a", "reviewer-b", "dev"), false, true);
    assertThat(independentApprover.availableActions()).containsExactly("VIEW", "REVIEW");

    submitted.setStatus("APPROVED");
    var author = service.capabilities(id, PRINCIPAL, true, false);
    assertThat(author.availableActions()).containsExactly("VIEW", "PROMOTE");
  }

  @Test
  void withholdsPromotionWhenTheCanonicalDefinitionRequiresBoundOperationalEvidence() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace approved = workspace(id, "tenant-a", "dev");
    approved.setStatus("APPROVED");
    DomainRuleDefinition governed = definition("tenant-a", "dev");
    governed.setGovernance("""
        {"testEvidencePolicy":{"stages":{"PROMOTE":{
          "baselineAuthorityType":"LEGACY_ORACLE",
          "baselineEligibility":"ELIGIBLE",
          "requiredOperationModes":["CREATE","UPDATE"],
          "requiredDecisions":["ALLOW","DENY"],
          "requireCleanupVerified":true,
          "requireBaselineMatch":true
        }}}}
        """);
    approved.setBaseDefinitionId(governed.getId());
    when(workspaces.findById(id)).thenReturn(Optional.of(approved));
    when(definitions.findById(governed.getId())).thenReturn(Optional.of(governed));

    var capability = service.capabilities(id, PRINCIPAL, true, false);

    assertThat(capability.availableActions()).containsExactly("VIEW");
    assertThat(capability.blockers()).extracting("code")
        .containsExactly("BOUND_TEST_RUN_REQUIRED");
  }

  @Test
  void recordsAppendOnlyApprovalByAReviewerDifferentFromTheAuthor() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    workspace.setStatus("SUBMITTED");
    UUID etag = workspace.getEtag();
    DomainRuleDefinition base = definition("tenant-a", "dev");
    workspace.setBaseDefinitionId(base.getId());
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));

    var review = service.review(id,
        new DomainRuleWorkspaceReviewRequest("approve", "Candidate passed governed scenarios"),
        "\"" + etag + "\"",
        new DomainRuleGovernancePrincipal("tenant-a", "reviewer-a", "dev"));

    assertThat(review.decision()).isEqualTo("APPROVE");
    assertThat(review.reviewerRef()).isEqualTo("reviewer-a");
    assertThat(review.workspaceRevision()).isEqualTo(1L);
    assertThat(workspace.getStatus()).isEqualTo("APPROVED");
    assertThat(workspace.getRevision()).isEqualTo(2L);
    verify(reviews).save(any());
    verify(workspaces).findByIdForUpdate(id);
    verify(domainRules).validateDefinitionApprovalAuthority(
        base.getId(), new DomainRuleGovernancePrincipal("tenant-a", "reviewer-a", "dev"));
  }

  @Test
  void blocksWorkspaceAuthorFromReviewingOwnSubmission() {
    UUID id = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    workspace.setStatus("SUBMITTED");
    DomainRuleDefinition base = definition("tenant-a", "dev");
    workspace.setBaseDefinitionId(base.getId());
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));

    assertThatThrownBy(() -> service.review(id,
        new DomainRuleWorkspaceReviewRequest("APPROVE", "self review"),
        "\"" + workspace.getEtag() + "\"", PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("must be different from its author");
  }

  @Test
  void promotesApprovedWorkspaceAsNextApprovedCanonicalDefinitionVersion() {
    UUID id = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    DomainRuleChangeWorkspace workspace = workspace(id, "tenant-a", "dev");
    workspace.setStatus("APPROVED");
    workspace.setRevision(2L);
    DomainRuleDefinition base = definition("tenant-a", "dev");
    workspace.setBaseDefinitionId(base.getId());
    workspace.setRuleKey(base.getRuleKey());
    when(workspaces.findByIdForUpdate(id)).thenReturn(Optional.of(workspace));
    when(reviews.findByTenantIdAndEnvironmentAndWorkspaceIdOrderByReviewedAtDesc("tenant-a", "dev", id))
        .thenReturn(List.of(org.praxisplatform.config.domain.DomainRuleWorkspaceReview.builder()
            .workspaceRevision(1L).baseDefinitionHash("B".repeat(64)).decision("APPROVE")
            .reviewerRef("reviewer-a").build()));
    when(definitions.findAllByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
        "tenant-a", "dev", base.getRuleKey())).thenReturn(List.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));
    when(domainRules.createDefinition(any(), any())).thenReturn(new DomainRuleDefinitionResponse(
        definitionId, "tenant-a", "dev", base.getRuleKey(), 4, base.getRuleType(), "proposed",
        null, null, null, null, null, null, null, objectMapper.createObjectNode(),
        objectMapper.createObjectNode(), null, objectMapper.createObjectNode(), null,
        "authenticated", "author-a", null, null, null, null, null));

    var promoted = service.promote(id, "\"" + workspace.getEtag() + "\"", PRINCIPAL);

    assertThat(promoted.status()).isEqualTo("PROMOTED");
    assertThat(promoted.promotedDefinitionId()).isEqualTo(definitionId);
    verify(domainRules).transitionDefinitionStatus(
        org.mockito.ArgumentMatchers.eq(definitionId), any(),
        org.mockito.ArgumentMatchers.eq(new DomainRuleGovernancePrincipal("tenant-a", "reviewer-a", "dev")));
  }

  private DomainRuleDefinition definition(String tenant, String environment) {
    return DomainRuleDefinition.builder()
        .id(UUID.randomUUID()).tenantId(tenant).environment(environment)
        .ruleKey("program.eligibility").version(3).ruleType("JSON_LOGIC").status("draft")
        .definition("{}").parameters("{\"mode\":\"FAIL_CLOSED\"}")
        .condition("{\"==\":[1,1]}").governance("{}").createdByType("HUMAN")
        .build();
  }

  private DomainRuleChangeWorkspace workspace(UUID id, String tenant, String environment) {
    return DomainRuleChangeWorkspace.builder()
        .id(id).tenantId(tenant).environment(environment).ruleKey("program.eligibility")
        .baseDefinitionId(UUID.randomUUID()).baseDefinitionVersion(3).baseDefinitionHash("B".repeat(64))
        .title("Workspace").status("OPEN").draftParameters("{}").etag(UUID.randomUUID()).revision(1L)
        .createdBy("author-a").updatedBy("author-a").build();
  }
}
