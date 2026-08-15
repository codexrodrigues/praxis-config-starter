package org.praxisplatform.config.service;

import java.util.List;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;

/** Resolves the immutable reviewed Test Run provenance of a promoted Definition. */
public class DomainRuleDefinitionEvidenceGateService {
  private final DomainRuleChangeWorkspaceRepository workspaces;
  private final DomainRuleTestRunRepository runs;
  private final DomainRuleTestRunResultRepository results;
  private final DomainRuleTestEvidencePolicyService policy;

  public DomainRuleDefinitionEvidenceGateService(
      DomainRuleChangeWorkspaceRepository workspaces,
      DomainRuleTestRunRepository runs,
      DomainRuleTestRunResultRepository results,
      DomainRuleTestEvidencePolicyService policy) {
    this.workspaces = workspaces;
    this.runs = runs;
    this.results = results;
    this.policy = policy;
  }

  public List<DomainRuleWorkspaceBlocker> blockers(
      String stage, DomainRuleDefinition definition, DomainRuleGovernancePrincipal principal) {
    if (definition == null || principal == null) {
      throw new IllegalArgumentException("definition and principal are required");
    }
    List<DomainRuleChangeWorkspace> sources = workspaces
        .findByTenantIdAndEnvironmentAndPromotedDefinitionId(
            principal.tenantId(), principal.environment(), definition.getId());
    if (sources.size() > 1) {
      return List.of(new DomainRuleWorkspaceBlocker(
          "REVIEWED_TEST_RUN_PROVENANCE_AMBIGUOUS", stage,
          "The promoted Definition has more than one workspace provenance source"));
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
    return policy.blockers(stage, definition, run, evidence);
  }
}
