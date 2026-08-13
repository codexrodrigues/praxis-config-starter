package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestRunResult;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.praxisplatform.config.dto.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.dto.DomainRuleTestRunResultRequest;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DomainRuleTestRunServiceTest {
  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID SCENARIO = UUID.randomUUID();
  private static final DomainRuleGovernancePrincipal PRINCIPAL = new DomainRuleGovernancePrincipal("tenant-a","host-a","dev");
  private DomainRuleTestRunRepository runs;
  private DomainRuleTestRunResultRepository results;
  private DomainRuleChangeWorkspaceRepository workspaces;
  private DomainRuleTestScenarioRepository scenarios;
  private DomainRuleTestRunService service;

  @BeforeEach void setup() {
    runs=mock(DomainRuleTestRunRepository.class); results=mock(DomainRuleTestRunResultRepository.class);
    workspaces=mock(DomainRuleChangeWorkspaceRepository.class); scenarios=mock(DomainRuleTestScenarioRepository.class);
    service=new DomainRuleTestRunService(runs,results,workspaces,scenarios,new ObjectMapper());
    when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-a",2L)));
    when(scenarios.findById(SCENARIO)).thenReturn(Optional.of(DomainRuleTestScenario.builder()
        .id(SCENARIO).workspaceId(WORKSPACE).scenarioKey("happy").expectedDecision("ALLOW")
        .expectedOutput("{\"amount\":500}").expectedReasonCodes("[]")
        .expectedEffectIntents("[\"REGISTER_EXTRAORDINARY_GRANT\"]").build()));
  }

  @Test void recordsOnlySafeImmutableEvidence() {
    var response=service.record(WORKSPACE,request(2L,"A".repeat(64)),PRINCIPAL);
    assertThat(response.results()).singleElement().satisfies(item -> {
      assertThat(item.candidateDecision()).isEqualTo("ALLOW");
      assertThat(item.factsDigest()).isEqualTo("C".repeat(64));
      assertThat(item.candidateOutputMatchesExpected()).isTrue();
      assertThat(item.candidateReasonCodesMatchExpected()).isTrue();
      assertThat(item.candidateEffectsMatchExpected()).isTrue();
    });
    ArgumentCaptor<DomainRuleTestRun> run=ArgumentCaptor.forClass(DomainRuleTestRun.class);
    verify(runs).save(run.capture());
    assertThat(run.getValue().getResultSummary()).contains("scenarioCount").doesNotContain("facts");
    ArgumentCaptor<List<DomainRuleTestRunResult>> saved=ArgumentCaptor.forClass(List.class);
    verify(results).saveAll(saved.capture());
    assertThat(saved.getValue()).singleElement().extracting(DomainRuleTestRunResult::getFactsDigest).isEqualTo("C".repeat(64));
  }

  @Test void rejectsStaleWorkspaceAndCrossScopeWithoutWriting() {
    assertThatThrownBy(() -> service.record(WORKSPACE,request(1L,"A".repeat(64)),PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("CONFLICT");
    when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace("tenant-b",2L)));
    assertThatThrownBy(() -> service.record(WORKSPACE,request(2L,"A".repeat(64)),PRINCIPAL))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("NOT_FOUND");
  }

  @Test void recomputesSemanticMatchesFromPersistedScenarioExpectations() {
    when(scenarios.findById(SCENARIO)).thenReturn(Optional.of(DomainRuleTestScenario.builder()
        .id(SCENARIO).workspaceId(WORKSPACE).scenarioKey("happy").expectedDecision("ALLOW")
        .expectedOutput("{\"amount\":600}").expectedReasonCodes("[\"APPROVED\"]")
        .expectedEffectIntents("[\"REGISTER_EXTRAORDINARY_GRANT\"]").build()));

    var result = service.record(WORKSPACE, request(2L, "A".repeat(64)), PRINCIPAL)
        .results().getFirst();

    assertThat(result.candidateMatchesExpected()).isTrue();
    assertThat(result.candidateOutputMatchesExpected()).isFalse();
    assertThat(result.candidateReasonCodesMatchExpected()).isFalse();
    assertThat(result.candidateEffectsMatchExpected()).isTrue();
  }

  private DomainRuleTestRunRecordRequest request(long revision,String hash) {
    return new DomainRuleTestRunRecordRequest(revision,hash,Instant.parse("2026-08-13T12:00:00Z"),"UTC",null,null,0,
        List.of(new DomainRuleTestRunResultRequest(SCENARIO,"happy","ALLOW","TECHNICAL_ERROR",
            new ObjectMapper().createObjectNode().put("amount",500),null,
            List.of(),List.of("ACTIVE_SNAPSHOT_UNAVAILABLE"),
            List.of("REGISTER_EXTRAORDINARY_GRANT"),List.of(),
            "B".repeat(64),"0".repeat(64),"C".repeat(64))));
  }
  private DomainRuleChangeWorkspace workspace(String tenant,long revision){return DomainRuleChangeWorkspace.builder()
      .id(WORKSPACE).tenantId(tenant).environment("dev").revision(revision).baseDefinitionHash("A".repeat(64)).build();}
}
