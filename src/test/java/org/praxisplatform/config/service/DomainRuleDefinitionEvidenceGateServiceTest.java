package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;

@Tag("unit")
class DomainRuleDefinitionEvidenceGateServiceTest {
  private final DomainRuleChangeWorkspaceRepository workspaces = mock(DomainRuleChangeWorkspaceRepository.class);
  private final DomainRuleTestRunRepository runs = mock(DomainRuleTestRunRepository.class);
  private final DomainRuleTestRunResultRepository results = mock(DomainRuleTestRunResultRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final DomainRuleDefinitionEvidenceGateService service =
      new DomainRuleDefinitionEvidenceGateService(workspaces, runs, results,
          new DomainRuleTestEvidencePolicyService(objectMapper), objectMapper);
  private final DomainRuleGovernancePrincipal principal =
      new DomainRuleGovernancePrincipal("tenant-a", "publisher", "prod");

  @Test
  void resolvesTheSubmittedRunBoundToThePromotedDefinition() {
    UUID definitionId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    DomainRuleDefinition definition = DomainRuleDefinition.builder()
        .id(definitionId)
        .governance("{\"testEvidencePolicy\":{\"stages\":{\"PUBLISH\":{\"baselineEligibility\":\"ELIGIBLE\"}}}}")
        .build();
    DomainRuleChangeWorkspace workspace = DomainRuleChangeWorkspace.builder()
        .id(workspaceId).promotedDefinitionId(definitionId).submittedTestRunId(runId).build();
    DomainRuleTestRun run = DomainRuleTestRun.builder()
        .id(runId).workspaceId(workspaceId).tenantId("tenant-a").environment("prod")
        .baselineEvidence("{\"authorityType\":\"LEGACY_ORACLE\",\"artifactRef\":\"ergon:r013\",\"artifactDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"observedAtUtc\":\"2026-08-15T12:00:00Z\",\"eligibility\":\"ELIGIBLE\"}")
        .build();
    when(workspaces.findByTenantIdAndEnvironmentAndPromotedDefinitionId("tenant-a", "prod", definitionId))
        .thenReturn(List.of(workspace));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(results.findByTestRunIdOrderByScenarioKey(runId)).thenReturn(List.of());

    assertThat(service.blockers("PUBLISH", definition, principal)).isEmpty();
  }

  @Test
  void failsClosedWhenPromotedDefinitionProvenanceIsAmbiguous() {
    UUID definitionId = UUID.randomUUID();
    DomainRuleDefinition definition = DomainRuleDefinition.builder().id(definitionId).governance("{}").build();
    when(workspaces.findByTenantIdAndEnvironmentAndPromotedDefinitionId("tenant-a", "prod", definitionId))
        .thenReturn(List.of(
            DomainRuleChangeWorkspace.builder().id(UUID.randomUUID()).build(),
            DomainRuleChangeWorkspace.builder().id(UUID.randomUUID()).build()));

    assertThat(service.blockers("PUBLISH", definition, principal))
        .extracting("code").containsExactly("REVIEWED_TEST_RUN_PROVENANCE_AMBIGUOUS");
  }
}
