package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Resolves the immutable reviewed Test Run provenance of a promoted Definition. */
public class DomainRuleDefinitionEvidenceGateService {
  private final DomainRuleChangeWorkspaceRepository workspaces;
  private final DomainRuleTestRunRepository runs;
  private final DomainRuleTestRunResultRepository results;
  private final DomainRuleTestEvidencePolicyService policy;
  private final ObjectMapper objectMapper;

  public DomainRuleDefinitionEvidenceGateService(
      DomainRuleChangeWorkspaceRepository workspaces,
      DomainRuleTestRunRepository runs,
      DomainRuleTestRunResultRepository results,
      DomainRuleTestEvidencePolicyService policy,
      ObjectMapper objectMapper) {
    this.workspaces = workspaces;
    this.runs = runs;
    this.results = results;
    this.policy = policy;
    this.objectMapper = objectMapper;
  }

  public List<DomainRuleWorkspaceBlocker> blockers(
      String stage, DomainRuleDefinition definition, DomainRuleGovernancePrincipal principal) {
    return decision(stage, definition, principal).blockers();
  }

  public DomainRuleDefinitionEvidenceDecision decision(
      String stage, DomainRuleDefinition definition, DomainRuleGovernancePrincipal principal) {
    if (definition == null || principal == null) {
      throw new IllegalArgumentException("definition and principal are required");
    }
    List<DomainRuleChangeWorkspace> sources = workspaces
        .findByTenantIdAndEnvironmentAndPromotedDefinitionId(
            principal.tenantId(), principal.environment(), definition.getId());
    if (sources.size() > 1) {
      return new DomainRuleDefinitionEvidenceDecision(
          definition.getId(), stage, requiresStage(stage, definition), null, null, null, null, null,
          List.of(new DomainRuleWorkspaceBlocker(
              "REVIEWED_TEST_RUN_PROVENANCE_AMBIGUOUS", stage,
              "The promoted Definition has more than one workspace provenance source")));
    }
    DomainRuleChangeWorkspace source = sources.isEmpty() ? null : sources.getFirst();
    DomainRuleTestRun run = source == null || source.getSubmittedTestRunId() == null ? null
        : runs.findById(source.getSubmittedTestRunId())
            .filter(item -> source.getId().equals(item.getWorkspaceId()))
            .filter(item -> principal.tenantId().equals(item.getTenantId()))
            .filter(item -> principal.environment().equals(item.getEnvironment()))
            .orElse(null);
    List<DomainRuleTestRunResult> evidence = run == null ? List.of()
        : results.findByTestRunIdOrderByScenarioKey(run.getId());
    ObjectNode digestDocument = objectMapper.createObjectNode();
    if (run == null) {
      digestDocument.putNull("run");
    } else {
      digestDocument.set("run", objectMapper.valueToTree(run));
    }
    digestDocument.set("results", objectMapper.valueToTree(evidence));
    return new DomainRuleDefinitionEvidenceDecision(
        definition.getId(), stage, requiresStage(stage, definition),
        source == null ? null : source.getId(), run == null ? null : run.getId(),
        run == null ? null : run.getRequestHash(), run == null ? null : run.getWorkspaceRevision(),
        run == null ? null : PraxisCanonicalJson.sha256(digestDocument),
        policy.blockers(stage, definition, run, evidence));
  }

  private boolean requiresStage(String stage, DomainRuleDefinition definition) {
    try {
      return policy.hasStage(stage, definition);
    } catch (IllegalArgumentException invalidPolicy) {
      // Invalid declared governance must remain a blocking stage, never degrade to opt-out.
      return true;
    }
  }
}
