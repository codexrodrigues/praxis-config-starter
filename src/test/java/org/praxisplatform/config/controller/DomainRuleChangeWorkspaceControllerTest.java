package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.repository.DomainRuleWorkspaceReviewRepository;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleTestEvidencePolicyService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class DomainRuleChangeWorkspaceControllerTest {

  private static final DomainRuleGovernancePrincipal PRINCIPAL =
      new DomainRuleGovernancePrincipal("tenant-a", "author-a", "dev");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private DomainRuleChangeWorkspaceRepository workspaces;
  private DomainRuleDefinitionRepository definitions;
  private DomainRuleDefinitionFingerprint fingerprint;
  private DomainRuleChangeWorkspaceService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    workspaces = mock(DomainRuleChangeWorkspaceRepository.class);
    definitions = mock(DomainRuleDefinitionRepository.class);
    fingerprint = mock(DomainRuleDefinitionFingerprint.class);
    service = new DomainRuleChangeWorkspaceService(
        workspaces,
        mock(DomainRuleTestScenarioRepository.class),
        definitions,
        fingerprint,
        objectMapper,
        mock(DomainRuleTestRunRepository.class),
        mock(DomainRuleTestRunResultRepository.class),
        mock(DomainRuleWorkspaceReviewRepository.class),
        mock(DomainRuleService.class),
        new DomainRuleTestEvidencePolicyService(objectMapper));
    DomainRuleGovernancePrincipalResolver principalResolver =
        mock(DomainRuleGovernancePrincipalResolver.class);
    when(principalResolver.resolve(
            any(HttpServletRequest.class), any(), any(), any()))
        .thenReturn(PRINCIPAL);
    DomainRuleChangeWorkspaceController controller =
        new DomainRuleChangeWorkspaceController(
            service, principalResolver, mock(DomainRuleTestRunService.class));
    // Standalone setup intentionally omits ConfigGlobalExceptionHandler to verify controller-local 400 mapping.
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void updateDraftRejectsUnsafeLegalBasisUrlWithHttp400() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    DomainRuleDefinition base = baseDefinition();
    DomainRuleChangeWorkspace workspace = openWorkspace(workspaceId, base.getId());
    when(workspaces.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(workspace));
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));

    String payload = """
        {
          "parameters": {"parameterValues": {"limit": 20}},
          "documentation": {
            "title": "Limite de afastamento",
            "legalBasis": [
              {
                "code": "STAT-123",
                "title": "Estatuto",
                "url": "javascript:alert(1)"
              }
            ]
          }
        }
        """;

    mockMvc.perform(
            put("/api/praxis/config/domain-rules/workspaces/{id}/draft", workspaceId)
                .header("If-Match", "\"" + workspace.getEtag() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateDraftRejectsNonTextualLegalBasisUrlWithHttp400() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    DomainRuleDefinition base = baseDefinition();
    DomainRuleChangeWorkspace workspace = openWorkspace(workspaceId, base.getId());
    when(workspaces.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(workspace));
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));

    String payload = """
        {
          "parameters": {"parameterValues": {"limit": 20}},
          "documentation": {
            "title": "Limite de afastamento",
            "legalBasis": [
              {
                "code": "STAT-123",
                "url": 123
              }
            ]
          }
        }
        """;

    mockMvc.perform(
            put("/api/praxis/config/domain-rules/workspaces/{id}/draft", workspaceId)
                .header("If-Match", "\"" + workspace.getEtag() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateDraftAcceptsValidHttpsLegalBasisUrlAndTrimsWhitespace() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    DomainRuleDefinition base = baseDefinition();
    DomainRuleChangeWorkspace workspace = openWorkspace(workspaceId, base.getId());
    when(workspaces.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(workspace));
    when(definitions.findById(base.getId())).thenReturn(Optional.of(base));
    when(fingerprint.sha256(base)).thenReturn("B".repeat(64));
    when(workspaces.save(any(DomainRuleChangeWorkspace.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    String payload = """
        {
          "parameters": {"parameterValues": {"limit": 25}},
          "documentation": {
            "title": "Limite de afastamento",
            "legalBasis": [
              {
                "code": "STAT-123",
                "title": "Estatuto",
                "url": "  https://example.org/statutes/123#art-5  "
              }
            ]
          }
        }
        """;

    mockMvc.perform(
            put("/api/praxis/config/domain-rules/workspaces/{id}/draft", workspaceId)
                .header("If-Match", "\"" + workspace.getEtag() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentation.legalBasis[0].url")
            .value("https://example.org/statutes/123#art-5"));
  }

  @Test
  void localIllegalArgumentExceptionHandlerReturnsHttp400() throws Exception {
    DomainRuleChangeWorkspaceService throwingService = mock(DomainRuleChangeWorkspaceService.class);
    DomainRuleGovernancePrincipalResolver principalResolver =
        mock(DomainRuleGovernancePrincipalResolver.class);
    when(principalResolver.resolve(any(HttpServletRequest.class), any(), any(), any()))
        .thenReturn(PRINCIPAL);
    UUID workspaceId = UUID.randomUUID();
    when(throwingService.updateDraft(any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("documentation.legalBasis.url must use http or https scheme"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new DomainRuleChangeWorkspaceController(
                throwingService, principalResolver, mock(DomainRuleTestRunService.class)))
        .build();

    var result = mvc.perform(
            put("/api/praxis/config/domain-rules/workspaces/{id}/draft", workspaceId)
                .header("If-Match", "\"etag-1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parameters\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WORKSPACE_REQUEST"))
        .andExpect(jsonPath("$.message").value("documentation.legalBasis.url must use http or https scheme"))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
  }

  private DomainRuleDefinition baseDefinition() throws Exception {
    RuleParameterTemplate template = new RuleParameterTemplate(
        "workforce.leaves:max-days",
        "1",
        RuleExecutorRef.jsonLogic(objectMapper.readTree("{\"<=\":[{\"var\":\"request.days\"},100]}")),
        RuleDecision.DENY,
        "LIMIT",
        List.of("request.days"),
        List.of(
            new RuleParameterDefinition(
                "limit",
                RuleParameterType.NUMBER,
                true,
                false,
                false,
                "days",
                objectMapper.getNodeFactory().numberNode(100),
                BigDecimal.ZERO,
                BigDecimal.valueOf(200),
                List.of(),
                List.of("/<=/1"))));
    String defJson = objectMapper.createObjectNode()
        .set("parameterTemplate", objectMapper.valueToTree(template))
        .toString();
    return DomainRuleDefinition.builder()
        .id(UUID.randomUUID())
        .tenantId("tenant-a")
        .environment("dev")
        .ruleKey("workforce.leaves.max-days")
        .version(1)
        .ruleType("JSON_LOGIC")
        .status("draft")
        .definition(defJson)
        .parameters("{\"parameterValues\":{\"limit\":100}}")
        .condition("{\"==\":[1,1]}")
        .governance("{}")
        .createdByType("HUMAN")
        .build();
  }

  private DomainRuleChangeWorkspace openWorkspace(UUID id, UUID baseDefId) {
    return DomainRuleChangeWorkspace.builder()
        .id(id)
        .tenantId("tenant-a")
        .environment("dev")
        .ruleKey("workforce.leaves.max-days")
        .baseDefinitionId(baseDefId)
        .baseDefinitionVersion(1)
        .baseDefinitionHash("B".repeat(64))
        .title("Workspace")
        .status("OPEN")
        .draftParameters("{\"parameterValues\":{\"limit\":100}}")
        .etag(UUID.randomUUID())
        .revision(1L)
        .createdBy("author-a")
        .updatedBy("author-a")
        .build();
  }
}
