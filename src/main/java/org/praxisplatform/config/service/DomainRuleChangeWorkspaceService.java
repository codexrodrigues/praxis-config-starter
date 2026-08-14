package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.praxisplatform.config.domain.DomainRuleWorkspaceReview;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceCreateRequest;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceResponse;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;
import org.praxisplatform.config.dto.DomainRuleWorkspaceCapabilityResponse;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceUpdateRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleTestScenarioRequest;
import org.praxisplatform.config.dto.DomainRuleTestScenarioResponse;
import org.praxisplatform.config.dto.DomainRuleWorkspaceReviewRequest;
import org.praxisplatform.config.dto.DomainRuleWorkspaceReviewResponse;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleWorkspaceReviewRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Governed persistence for collaborative rule drafts and reusable outcome scenarios. */
@RequiredArgsConstructor
public class DomainRuleChangeWorkspaceService {
  private static final Set<String> DECISIONS = Set.of(
      "ALLOW", "DENY", "NOT_APPLICABLE", "INCONCLUSIVE", "TECHNICAL_ERROR");
  private static final Set<String> SCENARIO_STATUSES = Set.of("ACTIVE", "DISABLED");

  private final DomainRuleChangeWorkspaceRepository workspaceRepository;
  private final DomainRuleTestScenarioRepository scenarioRepository;
  private final DomainRuleDefinitionRepository definitionRepository;
  private final DomainRuleDefinitionFingerprint fingerprint;
  private final ObjectMapper objectMapper;
  private final DomainRuleTestRunRepository runRepository;
  private final DomainRuleTestRunResultRepository runResultRepository;
  private final DomainRuleWorkspaceReviewRepository reviewRepository;
  private final DomainRuleService domainRuleService;

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleChangeWorkspaceResponse create(
      DomainRuleChangeWorkspaceCreateRequest request,
      DomainRuleGovernancePrincipal principal) {
    if (request == null || request.baseDefinitionId() == null) {
      throw badRequest("baseDefinitionId is required");
    }
    DomainRuleDefinition base = scopedDefinition(request.baseDefinitionId(), principal);
    Instant now = Instant.now();
    DomainRuleChangeWorkspace workspace = DomainRuleChangeWorkspace.builder()
        .id(UUID.randomUUID())
        .tenantId(principal.tenantId())
        .environment(principal.environment())
        .ruleKey(base.getRuleKey())
        .baseDefinitionId(base.getId())
        .baseDefinitionVersion(base.getVersion())
        .baseDefinitionHash(fingerprint.sha256(base))
        .title(requireText(request.title(), "title", 255))
        .status("OPEN")
        .draftCondition(base.getCondition())
        .draftParameters(base.getParameters())
        .etag(UUID.randomUUID())
        .revision(1L)
        .createdBy(requireActor(principal))
        .updatedBy(requireActor(principal))
        .createdAt(now)
        .updatedAt(now)
        .build();
    return response(workspaceRepository.save(workspace));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleChangeWorkspaceResponse get(UUID id, DomainRuleGovernancePrincipal principal) {
    return response(scopedWorkspace(id, principal));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleChangeWorkspaceResponse> list(DomainRuleGovernancePrincipal principal) {
    return workspaceRepository.findByTenantIdAndEnvironmentOrderByUpdatedAtDesc(
            principal.tenantId(), principal.environment()).stream()
        .map(this::response)
        .toList();
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public DomainRuleWorkspaceCapabilityResponse capabilities(
      UUID id,
      DomainRuleGovernancePrincipal principal,
      boolean canAuthor,
      boolean canApprove) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(id, principal);
    List<String> actions = new java.util.ArrayList<>();
    List<DomainRuleWorkspaceBlocker> blockers = new java.util.ArrayList<>();
    actions.add("VIEW");
    switch (workspace.getStatus()) {
      case "OPEN" -> {
        if (canAuthor) {
          actions.add("UPDATE_DRAFT");
          actions.add("MANAGE_SCENARIOS");
          actions.add("RECORD_TEST_RUN");
          blockers.addAll(submissionBlockers(workspace, principal));
          if (blockers.isEmpty()) actions.add("SUBMIT");
        }
      }
      case "SUBMITTED" -> {
        if (canApprove && !requireActor(principal).equals(workspace.getCreatedBy())) {
          actions.add("REVIEW");
        } else if (canApprove) {
          blockers.add(new DomainRuleWorkspaceBlocker(
              "REVIEWER_MUST_DIFFER_FROM_AUTHOR", "REVIEW",
              "Workspace reviewer must be different from its author"));
        }
      }
      case "APPROVED" -> {
        if (canAuthor) actions.add("PROMOTE");
      }
      default -> { }
    }
    return new DomainRuleWorkspaceCapabilityResponse(
        workspace.getId(), workspace.getRuleKey(), workspace.getStatus(), workspace.getRevision(),
        workspace.getEtag().toString(), actions, blockers);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleChangeWorkspaceResponse updateDraft(
      UUID id,
      DomainRuleChangeWorkspaceUpdateRequest request,
      String ifMatch,
      DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(id, principal);
    requireOpen(workspace);
    requireStrongMatch(ifMatch, workspace.getEtag().toString());
    DomainRuleDefinition base = scopedDefinition(workspace.getBaseDefinitionId(), principal);
    if (!workspace.getBaseDefinitionHash().equals(fingerprint.sha256(base))) {
      throw conflict("The base definition changed; create or rebase the workspace before saving");
    }
    if (request == null || request.parameters() == null || !request.parameters().isObject()) {
      throw badRequest("parameters must be a JSON object");
    }
    workspace.setDraftCondition(json(request.condition()));
    workspace.setDraftParameters(json(request.parameters()));
    workspace.setRationale(optionalText(request.rationale(), 4000));
    rotate(workspace, principal);
    return response(workspaceRepository.save(workspace));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleTestScenarioResponse createScenario(
      UUID workspaceId,
      DomainRuleTestScenarioRequest request,
      DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(workspaceId, principal);
    requireOpen(workspace);
    ValidScenario valid = validateScenario(request);
    if (scenarioRepository.findByWorkspaceIdAndScenarioKey(workspaceId, valid.key()).isPresent()) {
      throw conflict("scenarioKey already exists in this workspace");
    }
    Instant now = Instant.now();
    DomainRuleTestScenario scenario = DomainRuleTestScenario.builder()
        .id(UUID.randomUUID())
        .workspaceId(workspaceId)
        .tenantId(principal.tenantId())
        .environment(principal.environment())
        .scenarioKey(valid.key())
        .name(valid.name())
        .facts(json(valid.facts()))
        .expectedDecision(valid.expectedDecision())
        .expectedOutput(json(valid.expectedOutput()))
        .expectedReasonCodes(jsonValue(valid.expectedReasonCodes()))
        .expectedEffectIntents(jsonValue(valid.expectedEffectIntents()))
        .status(valid.status())
        .etag(UUID.randomUUID())
        .revision(1L)
        .createdBy(requireActor(principal))
        .updatedBy(requireActor(principal))
        .createdAt(now)
        .updatedAt(now)
        .build();
    return scenarioResponse(scenarioRepository.save(scenario));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleTestScenarioResponse> scenarios(
      UUID workspaceId, DomainRuleGovernancePrincipal principal) {
    scopedWorkspace(workspaceId, principal);
    return scenarioRepository.findByWorkspaceIdOrderByScenarioKey(workspaceId).stream()
        .filter(item -> sameScope(item.getTenantId(), item.getEnvironment(), principal))
        .map(this::scenarioResponse)
        .toList();
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleTestScenarioResponse updateScenario(
      UUID workspaceId,
      UUID scenarioId,
      DomainRuleTestScenarioRequest request,
      String ifMatch,
      DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(workspaceId, principal);
    requireOpen(workspace);
    DomainRuleTestScenario scenario = scenarioRepository.findById(scenarioId)
        .filter(item -> item.getWorkspaceId().equals(workspaceId))
        .filter(item -> sameScope(item.getTenantId(), item.getEnvironment(), principal))
        .orElseThrow(() -> notFound("Scenario not found"));
    requireStrongMatch(ifMatch, scenario.getEtag().toString());
    ValidScenario valid = validateScenario(request);
    scenarioRepository.findByWorkspaceIdAndScenarioKey(workspaceId, valid.key())
        .filter(item -> !item.getId().equals(scenarioId))
        .ifPresent(item -> { throw conflict("scenarioKey already exists in this workspace"); });
    scenario.setScenarioKey(valid.key());
    scenario.setName(valid.name());
    scenario.setFacts(json(valid.facts()));
    scenario.setExpectedDecision(valid.expectedDecision());
    scenario.setExpectedOutput(json(valid.expectedOutput()));
    scenario.setStatus(valid.status());
    scenario.setEtag(UUID.randomUUID());
    scenario.setRevision(scenario.getRevision() + 1);
    scenario.setUpdatedBy(requireActor(principal));
    scenario.setUpdatedAt(Instant.now());
    return scenarioResponse(scenarioRepository.save(scenario));
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleChangeWorkspaceResponse submit(
      UUID id, String ifMatch, DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(id, principal);
    requireOpen(workspace);
    requireStrongMatch(ifMatch, workspace.getEtag().toString());
    List<DomainRuleWorkspaceBlocker> blockers = submissionBlockers(workspace, principal);
    if (!blockers.isEmpty()) throw conflict(blockers.getFirst().message());
    workspace.setStatus("SUBMITTED");
    rotate(workspace, principal);
    return response(workspaceRepository.save(workspace));
  }

  private List<DomainRuleWorkspaceBlocker> submissionBlockers(
      DomainRuleChangeWorkspace workspace, DomainRuleGovernancePrincipal principal) {
    var run = runRepository.findFirstByTenantIdAndEnvironmentAndWorkspaceIdOrderByRecordedAtDesc(
        principal.tenantId(), principal.environment(), workspace.getId());
    if (run.isEmpty()) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "CURRENT_PASSING_TEST_RUN_REQUIRED", "SUBMIT",
          "A current passing Test Run is required before submission"));
    }
    if (!workspace.getRevision().equals(run.get().getWorkspaceRevision())
        || !workspace.getBaseDefinitionHash().equals(run.get().getBaseDefinitionHash())) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "TEST_RUN_DOES_NOT_PROVE_CURRENT_REVISION", "SUBMIT",
          "The latest Test Run does not prove the current workspace revision"));
    }
    var evidence = runResultRepository.findByTestRunIdOrderByScenarioKey(run.get().getId());
    Set<UUID> activeScenarioIds = scenarioRepository.findByWorkspaceIdOrderByScenarioKey(workspace.getId()).stream()
        .filter(item -> sameScope(item.getTenantId(), item.getEnvironment(), principal))
        .filter(item -> "ACTIVE".equals(item.getStatus()))
        .map(DomainRuleTestScenario::getId)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> evidencedScenarioIds = evidence.stream()
        .map(item -> item.getScenarioId())
        .collect(java.util.stream.Collectors.toSet());
    if (activeScenarioIds.isEmpty()) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "ACTIVE_SCENARIO_REQUIRED", "SUBMIT",
          "At least one active scenario is required before submission"));
    }
    if (!activeScenarioIds.equals(evidencedScenarioIds)) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "ACTIVE_SCENARIO_COVERAGE_INCOMPLETE", "SUBMIT",
          "The latest Test Run must cover every active scenario"));
    }
    if (evidence.stream().anyMatch(item -> !Boolean.TRUE.equals(item.getCandidateMatchesExpected())
        || !Boolean.TRUE.equals(item.getCandidateOutputMatchesExpected())
        || !Boolean.TRUE.equals(item.getCandidateReasonCodesMatchExpected())
        || !Boolean.TRUE.equals(item.getCandidateEffectsMatchExpected())
        || "INCONCLUSIVE".equals(item.getComparison())
        || "TECHNICAL_ERROR".equals(item.getComparison()))) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "TEST_RUN_NOT_PASSING", "SUBMIT",
          "The latest Test Run must pass every active scenario without inconclusive or technical results"));
    }
    return List.of();
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleWorkspaceReviewResponse review(
      UUID id, DomainRuleWorkspaceReviewRequest request, String ifMatch,
      DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(id, principal);
    if (!"SUBMITTED".equals(workspace.getStatus())) {
      throw conflict("Only a submitted workspace can be reviewed");
    }
    requireStrongMatch(ifMatch, workspace.getEtag().toString());
    String reviewer = requireActor(principal);
    if (reviewer.equals(workspace.getCreatedBy())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Workspace reviewer must be different from its author");
    }
    DomainRuleDefinition base = scopedDefinition(workspace.getBaseDefinitionId(), principal);
    if (!workspace.getBaseDefinitionHash().equals(fingerprint.sha256(base))) {
      throw conflict("The base definition changed before review");
    }
    domainRuleService.validateDefinitionApprovalAuthority(workspace.getBaseDefinitionId(), principal);
    String decision = requireReviewDecision(request == null ? null : request.decision());
    String rationale = requireText(request == null ? null : request.rationale(), "rationale", 4000);
    Instant now = Instant.now();
    DomainRuleWorkspaceReview review = reviewRepository.save(DomainRuleWorkspaceReview.builder()
        .id(UUID.randomUUID()).workspaceId(id).tenantId(principal.tenantId())
        .environment(principal.environment()).workspaceRevision(workspace.getRevision())
        .baseDefinitionHash(workspace.getBaseDefinitionHash()).decision(decision)
        .rationale(rationale).reviewerRef(reviewer).reviewedAt(now).build());
    workspace.setStatus("APPROVE".equals(decision) ? "APPROVED" : "REJECTED");
    rotate(workspace, principal);
    workspaceRepository.save(workspace);
    return reviewResponse(review);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<DomainRuleWorkspaceReviewResponse> reviews(
      UUID id, DomainRuleGovernancePrincipal principal) {
    scopedWorkspace(id, principal);
    return reviewRepository.findByTenantIdAndEnvironmentAndWorkspaceIdOrderByReviewedAtDesc(
            principal.tenantId(), principal.environment(), id).stream()
        .map(this::reviewResponse)
        .toList();
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public DomainRuleChangeWorkspaceResponse promote(
      UUID id, String ifMatch, DomainRuleGovernancePrincipal principal) {
    DomainRuleChangeWorkspace workspace = scopedWorkspace(id, principal);
    if ("PROMOTED".equals(workspace.getStatus()) && workspace.getPromotedDefinitionId() != null) {
      return response(workspace);
    }
    if (!"APPROVED".equals(workspace.getStatus())) {
      throw conflict("Only an approved workspace can be promoted");
    }
    requireStrongMatch(ifMatch, workspace.getEtag().toString());
    DomainRuleWorkspaceReview approval = reviewRepository
        .findByTenantIdAndEnvironmentAndWorkspaceIdOrderByReviewedAtDesc(
            principal.tenantId(), principal.environment(), id).stream()
        .filter(item -> "APPROVE".equals(item.getDecision()))
        .findFirst()
        .orElseThrow(() -> conflict("Authenticated approval evidence is required before promotion"));
    if (!workspace.getBaseDefinitionHash().equals(approval.getBaseDefinitionHash())
        || approval.getWorkspaceRevision() + 1 != workspace.getRevision()) {
      throw conflict("Approval evidence does not match the current approved workspace state");
    }

    List<DomainRuleDefinition> versions = definitionRepository
        .findAllByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
            principal.tenantId(), principal.environment(), workspace.getRuleKey());
    DomainRuleDefinition base = versions.stream()
        .filter(item -> workspace.getBaseDefinitionId().equals(item.getId()))
        .findFirst()
        .orElseThrow(() -> conflict("The base definition is no longer available for promotion"));
    if (!workspace.getBaseDefinitionHash().equals(fingerprint.sha256(base))) {
      throw conflict("The base definition changed before promotion");
    }
    int nextVersion = versions.stream().map(DomainRuleDefinition::getVersion)
        .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    DomainRuleGovernancePrincipal author = new DomainRuleGovernancePrincipal(
        principal.tenantId(), workspace.getCreatedBy(), principal.environment());
    var proposed = domainRuleService.createDefinition(new DomainRuleDefinitionRequest(
        base.getRuleKey(), nextVersion, base.getRuleType(), "proposed", base.getContextKey(),
        base.getResourceKey(), base.getServiceKey(), base.getSemanticOwner(), base.getSteward(),
        base.getSourceRelease() == null ? null : base.getSourceRelease().getId(),
        base.getSourceChangeSet() == null ? null : base.getSourceChangeSet().getId(),
        tree(base.getDefinition()), tree(workspace.getDraftParameters()), tree(workspace.getDraftCondition()),
        tree(base.getGovernance()), null), author);
    var validation = objectMapper.createObjectNode();
    validation.put("review", "approved");
    validation.put("workspaceId", workspace.getId().toString());
    validation.put("workspaceRevision", approval.getWorkspaceRevision());
    domainRuleService.transitionDefinitionStatus(
        proposed.id(), new DomainRuleDefinitionStatusTransitionRequest("approved", validation),
        new DomainRuleGovernancePrincipal(
            principal.tenantId(), approval.getReviewerRef(), principal.environment()));
    workspace.setPromotedDefinitionId(proposed.id());
    workspace.setStatus("PROMOTED");
    rotate(workspace, principal);
    return response(workspaceRepository.save(workspace));
  }

  private String requireReviewDecision(String value) {
    String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("APPROVE", "REJECT").contains(normalized)) {
      throw badRequest("decision must be APPROVE or REJECT");
    }
    return normalized;
  }

  private DomainRuleWorkspaceReviewResponse reviewResponse(DomainRuleWorkspaceReview review) {
    return new DomainRuleWorkspaceReviewResponse(
        review.getId(), review.getWorkspaceId(), review.getWorkspaceRevision(),
        review.getBaseDefinitionHash(), review.getDecision(), review.getRationale(),
        review.getReviewerRef(), review.getReviewedAt());
  }

  private DomainRuleDefinition scopedDefinition(UUID id, DomainRuleGovernancePrincipal principal) {
    return definitionRepository.findById(id)
        .filter(item -> sameScope(item.getTenantId(), item.getEnvironment(), principal))
        .orElseThrow(() -> notFound("Base definition not found"));
  }

  private DomainRuleChangeWorkspace scopedWorkspace(UUID id, DomainRuleGovernancePrincipal principal) {
    return workspaceRepository.findById(id)
        .filter(item -> sameScope(item.getTenantId(), item.getEnvironment(), principal))
        .orElseThrow(() -> notFound("Change workspace not found"));
  }

  private boolean sameScope(String tenant, String environment, DomainRuleGovernancePrincipal principal) {
    return principal.tenantId().equals(tenant) && principal.environment().equals(environment);
  }

  private void requireOpen(DomainRuleChangeWorkspace workspace) {
    if (!"OPEN".equals(workspace.getStatus())) {
      throw conflict("Only an OPEN workspace can be changed");
    }
  }

  private void requireStrongMatch(String ifMatch, String current) {
    HttpEntityTagCondition condition;
    try {
      condition = HttpEntityTagCondition.parse(ifMatch);
    } catch (IllegalArgumentException exception) {
      throw badRequest(exception.getMessage());
    }
    if (condition.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required");
    }
    if (condition.wildcard() || !condition.matchesStrong(current)) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Workspace changed; reload before retrying");
    }
  }

  private ValidScenario validateScenario(DomainRuleTestScenarioRequest request) {
    if (request == null || request.facts() == null || !request.facts().isObject()) {
      throw badRequest("facts must be a JSON object");
    }
    String decision = requireText(request.expectedDecision(), "expectedDecision", 32).toUpperCase(Locale.ROOT);
    if (!DECISIONS.contains(decision)) {
      throw badRequest("expectedDecision must be a canonical five-state decision");
    }
    String status = request.status() == null ? "ACTIVE" : request.status().trim().toUpperCase(Locale.ROOT);
    if (!SCENARIO_STATUSES.contains(status)) {
      throw badRequest("status must be ACTIVE or DISABLED");
    }
    if (request.expectedOutput() != null && request.expectedOutput().toString().length() > 16384) {
      throw badRequest("expectedOutput exceeds 16384 characters");
    }
    return new ValidScenario(
        requireText(request.scenarioKey(), "scenarioKey", 255),
        requireText(request.name(), "name", 255),
        request.facts(), decision, request.expectedOutput(),
        normalizedAssertions(request.expectedReasonCodes(), "expectedReasonCodes"),
        normalizedAssertions(request.expectedEffectIntents(), "expectedEffectIntents"), status);
  }

  private void rotate(DomainRuleChangeWorkspace workspace, DomainRuleGovernancePrincipal principal) {
    workspace.setEtag(UUID.randomUUID());
    workspace.setRevision(workspace.getRevision() + 1);
    workspace.setUpdatedBy(requireActor(principal));
    workspace.setUpdatedAt(Instant.now());
  }

  private DomainRuleChangeWorkspaceResponse response(DomainRuleChangeWorkspace source) {
    return new DomainRuleChangeWorkspaceResponse(
        source.getId(), source.getRuleKey(), source.getBaseDefinitionId(), source.getBaseDefinitionVersion(),
        source.getBaseDefinitionHash(), source.getPromotedDefinitionId(), source.getTitle(), source.getStatus(), tree(source.getDraftCondition()),
        tree(source.getDraftParameters()), source.getRationale(), source.getRevision(), source.getEtag().toString(),
        source.getCreatedBy(), source.getUpdatedBy(), source.getCreatedAt(), source.getUpdatedAt());
  }

  private DomainRuleTestScenarioResponse scenarioResponse(DomainRuleTestScenario source) {
    return new DomainRuleTestScenarioResponse(
        source.getId(), source.getWorkspaceId(), source.getScenarioKey(), source.getName(), tree(source.getFacts()),
        source.getExpectedDecision(), tree(source.getExpectedOutput()), strings(source.getExpectedReasonCodes()),
        strings(source.getExpectedEffectIntents()), source.getStatus(), source.getRevision(),
        source.getEtag().toString(), source.getCreatedBy(), source.getUpdatedBy(), source.getCreatedAt(), source.getUpdatedAt());
  }

  private String json(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw badRequest("JSON payload is invalid");
    }
  }

  private String jsonValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw badRequest("JSON payload is invalid");
    }
  }

  private JsonNode tree(String value) {
    if (value == null) return null;
    try {
      return objectMapper.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted governed JSON is invalid", exception);
    }
  }

  private List<String> normalizedAssertions(List<String> values, String field) {
    if (values == null) return List.of();
    if (values.size() > 100) throw badRequest(field + " exceeds 100 entries");
    return values.stream().map(value -> requireText(value, field, 255))
        .distinct().sorted().toList();
  }

  private List<String> strings(String value) {
    if (value == null) return List.of();
    try {
      return objectMapper.readerForListOf(String.class).readValue(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Persisted scenario assertions are invalid", exception);
    }
  }

  private String requireActor(DomainRuleGovernancePrincipal principal) {
    return requireText(principal.actorRef(), "actor", 255);
  }

  private String requireText(String value, String field, int max) {
    if (value == null || value.isBlank()) throw badRequest(field + " is required");
    String normalized = value.trim();
    if (normalized.length() > max) throw badRequest(field + " exceeds " + max + " characters");
    return normalized;
  }

  private String optionalText(String value, int max) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > max) throw badRequest("rationale exceeds " + max + " characters");
    return normalized;
  }

  private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
  private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
  private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

  private record ValidScenario(
      String key, String name, JsonNode facts, String expectedDecision, JsonNode expectedOutput,
      List<String> expectedReasonCodes, List<String> expectedEffectIntents, String status) {}
}
