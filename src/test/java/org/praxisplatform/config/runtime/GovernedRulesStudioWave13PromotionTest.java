package org.praxisplatform.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.autoconfig.GovernedRulesStudioAutoConfiguration;
import org.praxisplatform.config.contract.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResponse;
import org.praxisplatform.config.contract.DomainRuleTestRunResultResponse;
import org.praxisplatform.config.controller.GovernedRulesStudioSandboxController;
import org.praxisplatform.config.domain.DomainRuleChangeWorkspace;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;
import org.praxisplatform.config.domain.DomainRuleTestRun;
import org.praxisplatform.config.domain.DomainRuleTestScenario;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRulePendingRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyMutationResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutReadinessResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.dto.DomainRuleTestScenarioResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunRepository;
import org.praxisplatform.config.repository.DomainRuleTestRunResultRepository;
import org.praxisplatform.config.repository.DomainRuleTestScenarioRepository;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.GovernedMultiTenantSnapshotBootstrapper.BootstrapReport;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactContractException;
import org.praxisplatform.rules.facts.RuleFactSchema;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class GovernedRulesStudioWave13PromotionTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    enum LeaveOp {
        CREATE("CREATE");
        final String key;

        LeaveOp(String key) {
            this.key = key;
        }
    }

    enum MultiOp {
        DELETE("DELETE"),
        CREATE("CREATE");
        final String key;

        MultiOp(String key) {
            this.key = key;
        }
    }

    static class LeaveRequestDraftDto {
        @Schema(description = "Requested duration in calendar days")
        @UISchema(label = "Requested Days")
        private Integer requestedDays;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        @Schema(description = "Applicant taxpayer identifier")
        @UISchema(label = "Tax ID")
        private String applicantTaxId;

        @Schema(description = "Whether medical clearance was attached")
        @UISchema(label = "Medical Clearance")
        private Boolean clearanceAttached;
    }

    @Test
    void dtoMetadataFactContractFactoryGeneratesValidCachedContractsAndEnrichedCatalog()
            throws Exception {
        MetadataDtoRuleFactContractFactory factory = new MetadataDtoRuleFactContractFactory(JSON);
        Map<String, Class<?>> roots = Map.of("draft", LeaveRequestDraftDto.class);
        List<String> requiredPaths =
                List.of("draft.requestedDays", "draft.applicantTaxId", "context.tenantId");

        RuleFactContract first =
                factory.buildOrGetContract(
                        "host/1.0", "CREATE", "provider/1.0", roots, requiredPaths);
        RuleFactContract second =
                factory.buildOrGetContract(
                        "host/1.0", "CREATE", "provider/1.0", roots, requiredPaths);

        assertThat(second).isSameAs(first);
        assertThat(factory.cacheSize()).isEqualTo(1);
        assertThat(first.schema().typeAt("draft.requestedDays")).isEqualTo("number");
        assertThat(first.schema().typeAt("draft.clearanceAttached")).isEqualTo("boolean");
        assertThat(first.schema().typeAt("context.tenantId")).isEqualTo("string");

        ObjectNode validFacts = JSON.createObjectNode();
        validFacts
                .putObject("draft")
                .put("requestedDays", 5)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        validFacts.putObject("context").put("tenantId", "tenant-a");
        first.schema().validate(validFacts);

        ObjectNode badFacts = validFacts.deepCopy();
        ((ObjectNode) badFacts.get("draft")).put("requestedDays", "not-a-number");
        assertThatThrownBy(() -> first.schema().validate(badFacts))
                .isInstanceOf(RuleFactContractException.class);

        Optional<ObjectNode> enriched =
                factory.buildEnrichedFactCatalog(
                        "workforce.leave-requests",
                        "hr-service",
                        "workforce",
                        "CREATE",
                        requiredPaths,
                        roots,
                        null);
        assertThat(enriched).isPresent();
        JsonNode factsArray = enriched.get().path("facts");
        assertThat(factsArray.isArray()).isTrue();
        assertThat(factsArray.toString()).contains("PERSONAL");
        assertThat(factsArray.toString()).contains("Requested Days");

        GovernedRuleFamilyDescriptor<LeaveOp> descriptor = sampleFamily();
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(descriptor));
        DomainRuleService ruleService = mock(DomainRuleService.class);
        when(ruleService.definitions(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        GovernedRulesStudioDefinitionSeeder seeder =
                new GovernedRulesStudioDefinitionSeeder(
                        registry, ruleService, JSON, List.of("tenant-a"), "local", factory);
        int seeded = seeder.seedScope("tenant-a", "local");
        assertThat(seeded).isEqualTo(2);

        var captor = org.mockito.ArgumentCaptor.forClass(DomainRuleDefinitionRequest.class);
        verify(ruleService, org.mockito.Mockito.atLeastOnce())
                .createDefinition(captor.capture(), any());
        assertThat(captor.getAllValues().getFirst().definition().path("factCatalog").path("facts"))
                .isNotEmpty();
    }

    @Test
    void sandboxServiceAndControllerEvaluateWorkspaceDraftsAndOperationalProofs()
            throws Exception {
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(sampleFamily()));
        DomainRuleService ruleService = mock(DomainRuleService.class);
        DomainRuleChangeWorkspaceService workspaceService =
                mock(DomainRuleChangeWorkspaceService.class);
        DomainRuleTestRunService testRunService = mock(DomainRuleTestRunService.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);

        UUID workspaceId = UUID.randomUUID();
        UUID baseDefId = UUID.randomUUID();
        UUID scenarioAllowId = UUID.randomUUID();
        UUID scenarioDenyId = UUID.randomUUID();
        String workspaceEtag = "\"ws-rev-1\"";

        ObjectNode defNode = JSON.createObjectNode();
        defNode.put("ruleSetKey", "workforce.leave-requests");
        defNode.put("slotKey", "slot.max-days");
        ObjectNode wsParams = JSON.createObjectNode();
        wsParams.putObject("parameterValues").put("maxDays", 5);

        DomainRuleDefinitionResponse baseDef =
                new DomainRuleDefinitionResponse(
                        baseDefId,
                        "tenant-a",
                        "local",
                        "leave.max-days",
                        1,
                        "selection_eligibility",
                        "active",
                        "workforce",
                        "workforce.leave-requests",
                        "hr-service",
                        "owner",
                        "steward",
                        null,
                        null,
                        defNode,
                        JSON.createObjectNode(),
                        null,
                        null,
                        null,
                        "USER",
                        "alice",
                        null,
                        NOW,
                        NOW,
                        null,
                        NOW);
        DomainRuleChangeWorkspaceResponse workspace =
                new DomainRuleChangeWorkspaceResponse(
                        workspaceId,
                        "leave.max-days",
                        baseDefId,
                        1,
                        "HASH-1",
                        null,
                        null,
                        "Extend threshold to 5 days",
                        "OPEN",
                        null,
                        wsParams,
                        null,
                        1L,
                        workspaceEtag,
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        ObjectNode allowFacts = JSON.createObjectNode();
        allowFacts
                .putObject("draft")
                .put("requestedDays", 4)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        allowFacts.putObject("context").put("tenantId", "tenant-a");

        ObjectNode denyFacts = JSON.createObjectNode();
        denyFacts
                .putObject("draft")
                .put("requestedDays", 7)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        denyFacts.putObject("context").put("tenantId", "tenant-a");

        DomainRuleTestScenarioResponse allowScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioAllowId,
                        workspaceId,
                        "WITHIN_NEW_LIMIT",
                        "4 days allowed",
                        allowFacts,
                        "ALLOW",
                        null,
                        List.of(),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-1",
                        "alice",
                        "alice",
                        NOW,
                        NOW);
        DomainRuleTestScenarioResponse denyScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioDenyId,
                        workspaceId,
                        "ABOVE_NEW_LIMIT",
                        "7 days denied",
                        denyFacts,
                        "DENY",
                        null,
                        List.of("WF-00001"),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-2",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        when(workspaceService.get(eq(workspaceId), any())).thenReturn(workspace);
        when(workspaceService.scenarios(eq(workspaceId), any()))
                .thenReturn(List.of(allowScenario, denyScenario));
        when(ruleService.definition(eq(baseDefId), any())).thenReturn(baseDef);
        when(snapshotService.findActive(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(testRunService.findByIdempotencyKey(eq(workspaceId), any(), any()))
                .thenReturn(Optional.empty());
        when(testRunService.record(eq(workspaceId), any(), any()))
                .thenAnswer(
                        inv -> {
                            DomainRuleTestRunRecordRequest req = inv.getArgument(1);
                            List<DomainRuleTestRunResultResponse> runResults =
                                    req.results().stream()
                                            .map(
                                                    r ->
                                                            new DomainRuleTestRunResultResponse(
                                                                    r.scenarioId(),
                                                                    r.scenarioKey(),
                                                                    r.baselineResult().decision(),
                                                                    r.candidateDecision(),
                                                                    r.activeDecision(),
                                                                    "MISMATCH",
                                                                    true,
                                                                    false,
                                                                    r.baselineResult().output(),
                                                                    r.candidateOutput(),
                                                                    r.activeOutput(),
                                                                    true,
                                                                    true,
                                                                    r.baselineResult().reasonCodes(),
                                                                    r.candidateReasonCodes(),
                                                                    r.activeReasonCodes(),
                                                                    true,
                                                                    true,
                                                                    r.baselineResult().effectIntents(),
                                                                    r.candidateEffectIntents(),
                                                                    r.activeEffectIntents(),
                                                                    true,
                                                                    true,
                                                                    r.candidatePlanDigest(),
                                                                    r.activePlanDigest(),
                                                                    r.factsDigest(),
                                                                    r.baselineResult(),
                                                                    "MATCH",
                                                                    true,
                                                                    true,
                                                                    true,
                                                                    true,
                                                                    r.operationalEvidence()))
                                            .toList();
                            return new DomainRuleTestRunResponse(
                                    UUID.randomUUID(),
                                    workspaceId,
                                    req.idempotencyKey(),
                                    "REQ-HASH",
                                    req.workspaceRevision(),
                                    req.baseDefinitionHash(),
                                    req.evaluatedAtUtc(),
                                    req.userTimeZone(),
                                    req.activeSnapshotKey(),
                                    req.activeSnapshotContentHash(),
                                    req.activeActivationRevision(),
                                    req.baselineEvidence(),
                                    runResults,
                                    "alice",
                                    NOW);
                        });

        GovernedRulesStudioSandboxService sandboxService =
                new GovernedRulesStudioSandboxService(
                        registry,
                        ruleService,
                        workspaceService,
                        testRunService,
                        snapshotService,
                        JSON,
                        Clock.fixed(NOW, ZoneId.of("UTC")));
        DomainRuleGovernancePrincipalResolver principalResolver =
                new DomainRuleGovernancePrincipalResolver(
                        new AiPrincipalContextResolver(
                                false, true, "tenant-a", "local", "alice", "RULE_DEFINITION_AUTHOR"),
                        false);
        GovernedRulesStudioSandboxController controller =
                new GovernedRulesStudioSandboxController(sandboxService, principalResolver);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        String sandboxBody =
                JSON.writeValueAsString(
                        new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                workspaceId,
                                List.of(scenarioAllowId, scenarioDenyId),
                                "UTC",
                                "idem-sandbox-1",
                                NOW));
        mockMvc.perform(
                        post("/api/praxis/policy-studio/sandbox/runs")
                                .header("X-Tenant-ID", "tenant-a")
                                .header("X-Env", "local")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sandboxBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.results[0].candidateDecision").value("ALLOW"))
                .andExpect(jsonPath("$.results[0].activeDecision").value("DENY"))
                .andExpect(jsonPath("$.results[0].comparison").value("MISMATCH"))
                .andExpect(jsonPath("$.results[1].candidateDecision").value("DENY"));

        String opBody =
                JSON.writeValueAsString(
                        new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                workspaceId,
                                List.of(
                                        new GovernedRulesStudioSandboxService
                                                .OperationalScenarioSelection(
                                                scenarioAllowId, "CREATE"),
                                        new GovernedRulesStudioSandboxService
                                                .OperationalScenarioSelection(
                                                scenarioDenyId, "UPDATE")),
                                "UTC",
                                NOW));

        mockMvc.perform(
                        post("/api/praxis/rules-studio/actions/run-rules-studio-operational-test")
                                .header("If-Match", workspaceEtag)
                                .header("Idempotency-Key", "idem-op-1")
                                .header("X-Tenant-ID", "tenant-a")
                                .header("X-Env", "local")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(opBody))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", workspaceEtag))
                .andExpect(jsonPath("$.results[0].operationalEvidence.operationMode").value("CREATE"))
                .andExpect(jsonPath("$.results[1].operationalEvidence.operationMode").value("UPDATE"));

        // Missing If-Match -> 428 Precondition Required
        mockMvc.perform(
                        post("/api/praxis/rules-studio/actions/run-rules-studio-operational-test")
                                .header("Idempotency-Key", "idem-op-missing-etag")
                                .header("X-Tenant-ID", "tenant-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(opBody))
                .andExpect(status().isPreconditionRequired());

        // Candidate expectation mismatch -> 422 OPERATIONAL_PROOF_MISMATCH
        DomainRuleTestScenarioResponse mismatchScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioAllowId,
                        workspaceId,
                        "MISMATCH_CASE",
                        "Expect deny on 4 days",
                        allowFacts,
                        "DENY",
                        null,
                        List.of("WF-00001"),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-3",
                        "alice",
                        "alice",
                        NOW,
                        NOW);
        when(workspaceService.scenarios(eq(workspaceId), any()))
                .thenReturn(List.of(mismatchScenario));

        String mismatchBody =
                JSON.writeValueAsString(
                        new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                workspaceId,
                                List.of(
                                        new GovernedRulesStudioSandboxService
                                                .OperationalScenarioSelection(
                                                scenarioAllowId, "CREATE")),
                                "UTC",
                                NOW));
        mockMvc.perform(
                        post("/api/praxis/rules-studio/actions/run-rules-studio-operational-test")
                                .header("If-Match", workspaceEtag)
                                .header("Idempotency-Key", "idem-op-mismatch")
                                .header("X-Tenant-ID", "tenant-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mismatchBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("OPERATIONAL_PROOF_MISMATCH"));
    }

    @Test
    void sandboxAndOperationalTestResolveActiveSnapshotSha256InsteadOfHeadEtag()
            throws Exception {
        RealTestRunSandboxHarness harness = createRealTestRunSandboxHarness();
        String activeSnapshotKey = "snap-active-v3";
        String headEtagUuid = UUID.randomUUID().toString();
        long activationRevision = 3L;
        PublishedRuleSnapshot activeSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        activeSnapshotKey,
                        "tenant-a",
                        "local",
                        "hr-service",
                        3,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "src-1",
                                        "workforce.leave-requests",
                                        1,
                                        "B".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "app-1",
                                        "RULE_COMPOSITION_APPROVER",
                                        "approver-1",
                                        NOW.toString(),
                                        "B".repeat(64))),
                        sampleFamily().productProfiles().getFirst().ruleSet());
        String activeSnapshotContentHash =
                PraxisCanonicalJson.sha256(JSON.valueToTree(activeSnapshot));

        when(harness.snapshotService()
                        .findActive("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotActivationResponse(
                                        activeSnapshot,
                                        activeSnapshotContentHash,
                                        headEtagUuid,
                                        activationRevision,
                                        "ACTIVE")));

        var sandboxResponse =
                harness.sandboxService()
                        .run(
                                new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                        harness.workspaceId(),
                                        List.of(harness.scenarioAllowId()),
                                        "UTC",
                                        "idem-real-active-sandbox",
                                        NOW),
                                harness.principal());

        assertThat(sandboxResponse.activeSnapshotKey()).isEqualTo(activeSnapshotKey);
        assertThat(sandboxResponse.activeSnapshotContentHash())
                .isEqualTo(activeSnapshotContentHash)
                .matches("[A-Fa-f0-9]{64}")
                .isNotEqualTo(headEtagUuid);
        assertThat(sandboxResponse.activeActivationRevision()).isEqualTo(activationRevision);

        var opResult =
                harness.sandboxService()
                        .runOperationalTest(
                                new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                        harness.workspaceId(),
                                        List.of(
                                                new GovernedRulesStudioSandboxService
                                                        .OperationalScenarioSelection(
                                                        harness.scenarioAllowId(), "CREATE")),
                                        "UTC",
                                        NOW),
                                harness.workspaceEtag(),
                                "idem-real-active-op",
                                "corr-real-active-op",
                                harness.principal());

        assertThat(opResult.run().activeSnapshotKey()).isEqualTo(activeSnapshotKey);
        assertThat(opResult.run().activeSnapshotContentHash())
                .isEqualTo(activeSnapshotContentHash)
                .matches("[A-Fa-f0-9]{64}")
                .isNotEqualTo(headEtagUuid);
        assertThat(opResult.run().activeActivationRevision()).isEqualTo(activationRevision);

        ArgumentCaptor<DomainRuleTestRun> savedRuns =
                ArgumentCaptor.forClass(DomainRuleTestRun.class);
        verify(harness.runsRepo(), times(2)).save(savedRuns.capture());
        assertThat(savedRuns.getAllValues())
                .allSatisfy(
                        persisted -> {
                            assertThat(persisted.getActiveSnapshotKey())
                                    .isEqualTo(activeSnapshotKey);
                            assertThat(persisted.getActiveSnapshotContentHash())
                                    .isEqualTo(activeSnapshotContentHash)
                                    .matches("[A-Fa-f0-9]{64}")
                                    .isNotEqualTo(headEtagUuid);
                            assertThat(persisted.getActiveActivationRevision())
                                    .isEqualTo(activationRevision);
                        });
        verify(harness.snapshotService(), never()).findHeadStatus(any(), any(), any(), any());
    }

    @Test
    void sandboxAndOperationalTestFallBackToProductPlanDigestWhenNoActiveHeadExists()
            throws Exception {
        RealTestRunSandboxHarness harness = createRealTestRunSandboxHarness();
        when(harness.snapshotService()
                        .findActive("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(Optional.empty());

        var sandboxResponse =
                harness.sandboxService()
                        .run(
                                new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                        harness.workspaceId(),
                                        List.of(harness.scenarioAllowId()),
                                        "UTC",
                                        "idem-real-empty-sandbox",
                                        NOW),
                                harness.principal());

        String expectedPlanDigest = sandboxResponse.results().getFirst().activePlanDigest();
        assertThat(sandboxResponse.activeSnapshotKey())
                .isEqualTo("product:workforce.leave-requests:v1");
        assertThat(sandboxResponse.activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest);
        assertThat(sandboxResponse.activeActivationRevision()).isEqualTo(1L);

        var opResult =
                harness.sandboxService()
                        .runOperationalTest(
                                new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                        harness.workspaceId(),
                                        List.of(
                                                new GovernedRulesStudioSandboxService
                                                        .OperationalScenarioSelection(
                                                        harness.scenarioAllowId(), "CREATE")),
                                        "UTC",
                                        NOW),
                                harness.workspaceEtag(),
                                "idem-real-empty-op",
                                "corr-real-empty-op",
                                harness.principal());

        assertThat(opResult.run().activeSnapshotKey())
                .isEqualTo("product:workforce.leave-requests:v1");
        assertThat(opResult.run().activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest);
        assertThat(opResult.run().activeActivationRevision()).isEqualTo(1L);

        ArgumentCaptor<DomainRuleTestRun> savedRuns =
                ArgumentCaptor.forClass(DomainRuleTestRun.class);
        verify(harness.runsRepo(), times(2)).save(savedRuns.capture());
        assertThat(savedRuns.getAllValues())
                .allSatisfy(
                        persisted -> {
                            assertThat(persisted.getActiveSnapshotKey())
                                    .isEqualTo("product:workforce.leave-requests:v1");
                            assertThat(persisted.getActiveSnapshotContentHash())
                                    .isEqualTo(expectedPlanDigest);
                            assertThat(persisted.getActiveActivationRevision()).isEqualTo(1L);
                        });
    }

    @Test
    void sandboxAndOperationalTestFallBackToProductPlanDigestWhenActiveHeadIsNonReadyOrHashMalformed()
            throws Exception {
        RealTestRunSandboxHarness harness = createRealTestRunSandboxHarness();
        String withdrawnHeadEtag = UUID.randomUUID().toString();
        when(harness.snapshotService()
                        .findHeadStatus("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotHeadStatusResponse(
                                        "workforce.leave-requests",
                                        "CREATE",
                                        "snap-withdrawn-v2",
                                        1,
                                        2,
                                        5L,
                                        withdrawnHeadEtag,
                                        false,
                                        "WITHDRAWN")));

        // Case A: withdrawn/non-ready head -> findActive returns Optional.empty()
        when(harness.snapshotService()
                        .findActive("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(Optional.empty());

        var withdrawnSandbox =
                harness.sandboxService()
                        .run(
                                new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                        harness.workspaceId(),
                                        List.of(harness.scenarioAllowId()),
                                        "UTC",
                                        "idem-withdrawn-sandbox",
                                        NOW),
                                harness.principal());
        String expectedPlanDigest = withdrawnSandbox.results().getFirst().activePlanDigest();
        assertThat(withdrawnSandbox.activeSnapshotKey())
                .isEqualTo("product:workforce.leave-requests:v1");
        assertThat(withdrawnSandbox.activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest)
                .isNotEqualTo(withdrawnHeadEtag);
        assertThat(withdrawnSandbox.activeActivationRevision()).isEqualTo(1L);

        var withdrawnOp =
                harness.sandboxService()
                        .runOperationalTest(
                                new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                        harness.workspaceId(),
                                        List.of(
                                                new GovernedRulesStudioSandboxService
                                                        .OperationalScenarioSelection(
                                                        harness.scenarioAllowId(), "CREATE")),
                                        "UTC",
                                        NOW),
                                harness.workspaceEtag(),
                                "idem-withdrawn-op",
                                "corr-withdrawn-op",
                                harness.principal());
        assertThat(withdrawnOp.run().activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest)
                .isNotEqualTo(withdrawnHeadEtag);

        // Case B: invalid/corrupt active snapshot -> findActive throws IllegalStateException
        when(harness.snapshotService()
                        .findActive("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenThrow(new IllegalStateException("corrupt active snapshot"));

        var invalidSandbox =
                harness.sandboxService()
                        .run(
                                new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                        harness.workspaceId(),
                                        List.of(harness.scenarioAllowId()),
                                        "UTC",
                                        "idem-invalid-sandbox",
                                        NOW),
                                harness.principal());
        assertThat(invalidSandbox.activeSnapshotKey())
                .isEqualTo("product:workforce.leave-requests:v1");
        assertThat(invalidSandbox.activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest);
        assertThat(invalidSandbox.activeActivationRevision()).isEqualTo(1L);

        var invalidOp =
                harness.sandboxService()
                        .runOperationalTest(
                                new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                        harness.workspaceId(),
                                        List.of(
                                                new GovernedRulesStudioSandboxService
                                                        .OperationalScenarioSelection(
                                                        harness.scenarioAllowId(), "CREATE")),
                                        "UTC",
                                        NOW),
                                harness.workspaceEtag(),
                                "idem-invalid-op",
                                "corr-invalid-op",
                                harness.principal());
        assertThat(invalidOp.run().activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest);

        // Case C: malformed/blank snapshotContentHash in activation response -> falls back to
        // planDigest (never passes UUID/malformed string to DomainRuleTestRunService)
        PublishedRuleSnapshot partialSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-partial-v4",
                        "tenant-a",
                        "local",
                        "hr-service",
                        4,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "src-1",
                                        "workforce.leave-requests",
                                        1,
                                        "B".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "app-1",
                                        "RULE_COMPOSITION_APPROVER",
                                        "approver-1",
                                        NOW.toString(),
                                        "B".repeat(64))),
                        sampleFamily().productProfiles().getFirst().ruleSet());
        String malformedUuidHash = UUID.randomUUID().toString();
        org.mockito.Mockito.doReturn(
                        Optional.of(
                                new DomainRuleSnapshotActivationResponse(
                                        partialSnapshot,
                                        malformedUuidHash,
                                        withdrawnHeadEtag,
                                        4L,
                                        "ACTIVE")),
                        Optional.of(
                                new DomainRuleSnapshotActivationResponse(
                                        partialSnapshot,
                                        "   ",
                                        withdrawnHeadEtag,
                                        4L,
                                        "ACTIVE")))
                .when(harness.snapshotService())
                .findActive("tenant-a", "local", "workforce.leave-requests", "CREATE");

        var malformedSandbox =
                harness.sandboxService()
                        .run(
                                new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                        harness.workspaceId(),
                                        List.of(harness.scenarioAllowId()),
                                        "UTC",
                                        "idem-malformed-sandbox",
                                        NOW),
                                harness.principal());
        assertThat(malformedSandbox.activeSnapshotKey()).isEqualTo("snap-partial-v4");
        assertThat(malformedSandbox.activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest)
                .isNotEqualTo(malformedUuidHash);
        assertThat(malformedSandbox.activeActivationRevision()).isEqualTo(4L);

        var blankOp =
                harness.sandboxService()
                        .runOperationalTest(
                                new GovernedRulesStudioSandboxService.OperationalTestRunRequest(
                                        harness.workspaceId(),
                                        List.of(
                                                new GovernedRulesStudioSandboxService
                                                        .OperationalScenarioSelection(
                                                        harness.scenarioAllowId(), "CREATE")),
                                        "UTC",
                                        NOW),
                                harness.workspaceEtag(),
                                "idem-blank-op",
                                "corr-blank-op",
                                harness.principal());
        assertThat(blankOp.run().activeSnapshotKey()).isEqualTo("snap-partial-v4");
        assertThat(blankOp.run().activeSnapshotContentHash())
                .matches("[A-Fa-f0-9]{64}")
                .isEqualTo(expectedPlanDigest);
        assertThat(blankOp.run().activeActivationRevision()).isEqualTo(4L);
    }

    @Test
    void sandboxSelectsOperationContainingTargetSlotWhenOperationHintIsLegacyAliasInMultiOpFamily()
            throws Exception {
        GovernedRuleFamilyDescriptor<LeaveOp> baseSample = sampleFamily();
        RuleSetDefinition createDef = baseSample.operationSpecs().get(LeaveOp.CREATE).productRuleSet();
        RuleFactContract createContract = baseSample.operationSpecs().get(LeaveOp.CREATE).factContract();
        RuleSetRef deleteRef =
                new RuleSetRef("workforce", "hr", "workforce.leave-requests", "DELETE", 1);
        RuleSetDefinition deleteDef =
                new RuleSetDefinition(
                        deleteRef,
                        List.of("draft", "context"),
                        List.of(createDef.slots().getFirst()),
                        List.of(createDef.bindings().getFirst()),
                        createDef.compatibility(),
                        createDef.failPolicy(),
                        createDef.errorDescriptors());
        GovernedHostRuleBundleRuntime<MultiOp> runtime =
                new GovernedHostRuleBundleRuntime<>(
                        MultiOp.class,
                        "hr-service",
                        "workforce.leave-requests",
                        "host/1.0",
                        "provider/1.0",
                        List.of(
                                new OperationSpec<>(MultiOp.DELETE, "DELETE", deleteDef, createContract),
                                new OperationSpec<>(MultiOp.CREATE, "CREATE", createDef, createContract)),
                        requested -> Optional.empty(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        RuleErrorCatalog.of(
                                Map.of(
                                        "WF-00000",
                                        new RuleErrorDescriptor(
                                                "WF-00000",
                                                "draft.clearanceAttached",
                                                "Missing medical clearance",
                                                "leave.guard",
                                                "ELIGIBILITY"),
                                        "WF-00001",
                                        new RuleErrorDescriptor(
                                                "WF-00001",
                                                "draft.requestedDays",
                                                "Leave limit exceeded",
                                                "leave.max-days",
                                                "LIMIT"))));
        GovernedRuleFamilyDescriptor<MultiOp> multiOpFamily =
                new GovernedRuleFamilyDescriptor<>(
                        runtime,
                        null,
                        Map.of("draft", LeaveRequestDraftDto.class),
                        "workforce.leave-requests",
                        "Workforce Leave Requests",
                        "Workforce leave eligibility",
                        Map.of());

        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(multiOpFamily));
        DomainRuleService ruleService = mock(DomainRuleService.class);
        DomainRuleChangeWorkspaceService workspaceService =
                mock(DomainRuleChangeWorkspaceService.class);
        DomainRuleTestRunService testRunService = mock(DomainRuleTestRunService.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);

        UUID workspaceId = UUID.randomUUID();
        UUID baseDefId = UUID.randomUUID();
        UUID scenarioAllowId = UUID.randomUUID();
        UUID scenarioDenyId = UUID.randomUUID();

        ObjectNode defNode = JSON.createObjectNode();
        defNode.put("ruleSetKey", "workforce.leave-requests");
        defNode.put("slotKey", "slot.max-days");
        ObjectNode defParams = JSON.createObjectNode();
        defParams.putArray("operationKeys").add("validate");
        ObjectNode wsParams = JSON.createObjectNode();
        wsParams.putObject("parameterValues").put("maxDays", 5);

        DomainRuleDefinitionResponse baseDef =
                new DomainRuleDefinitionResponse(
                        baseDefId,
                        "tenant-a",
                        "local",
                        "leave.max-days",
                        1,
                        "selection_eligibility",
                        "active",
                        "workforce",
                        "workforce.leave-requests",
                        "hr-service",
                        "owner",
                        "steward",
                        null,
                        null,
                        defNode,
                        defParams,
                        null,
                        null,
                        null,
                        "USER",
                        "alice",
                        null,
                        NOW,
                        NOW,
                        null,
                        NOW);
        DomainRuleChangeWorkspaceResponse workspace =
                new DomainRuleChangeWorkspaceResponse(
                        workspaceId,
                        "leave.max-days",
                        baseDefId,
                        1,
                        "HASH-MULTI-1",
                        null,
                        null,
                        "Legacy validate alias on multi-op family",
                        "OPEN",
                        null,
                        wsParams,
                        null,
                        1L,
                        "\"ws-multi-1\"",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        ObjectNode allowFacts = JSON.createObjectNode();
        allowFacts
                .putObject("draft")
                .put("requestedDays", 4)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        allowFacts.putObject("context").put("tenantId", "tenant-a");
        ObjectNode denyFacts = JSON.createObjectNode();
        denyFacts
                .putObject("draft")
                .put("requestedDays", 7)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        denyFacts.putObject("context").put("tenantId", "tenant-a");

        DomainRuleTestScenarioResponse allowScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioAllowId,
                        workspaceId,
                        "WITHIN_LIMIT",
                        "4 days allowed",
                        allowFacts,
                        "ALLOW",
                        null,
                        List.of(),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-allow",
                        "alice",
                        "alice",
                        NOW,
                        NOW);
        DomainRuleTestScenarioResponse denyScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioDenyId,
                        workspaceId,
                        "ABOVE_LIMIT",
                        "7 days denied",
                        denyFacts,
                        "DENY",
                        null,
                        List.of("WF-00001"),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-deny",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        when(workspaceService.get(eq(workspaceId), any())).thenReturn(workspace);
        when(workspaceService.scenarios(eq(workspaceId), any()))
                .thenReturn(List.of(allowScenario, denyScenario));
        when(ruleService.definition(eq(baseDefId), any())).thenReturn(baseDef);
        when(snapshotService.findActive(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(testRunService.findByIdempotencyKey(eq(workspaceId), any(), any()))
                .thenReturn(Optional.empty());
        when(testRunService.record(eq(workspaceId), any(), any()))
                .thenAnswer(
                        inv -> {
                            DomainRuleTestRunRecordRequest req = inv.getArgument(1);
                            List<DomainRuleTestRunResultResponse> runResults =
                                    req.results().stream()
                                            .map(
                                                    r ->
                                                            new DomainRuleTestRunResultResponse(
                                                                    r.scenarioId(),
                                                                    r.scenarioKey(),
                                                                    r.baselineResult().decision(),
                                                                    r.candidateDecision(),
                                                                    r.activeDecision(),
                                                                    "MISMATCH",
                                                                    true,
                                                                    false,
                                                                    r.baselineResult().output(),
                                                                    r.candidateOutput(),
                                                                    r.activeOutput(),
                                                                    true,
                                                                    true,
                                                                    r.baselineResult().reasonCodes(),
                                                                    r.candidateReasonCodes(),
                                                                    r.activeReasonCodes(),
                                                                    true,
                                                                    true,
                                                                    r.baselineResult().effectIntents(),
                                                                    r.candidateEffectIntents(),
                                                                    r.activeEffectIntents(),
                                                                    true,
                                                                    true,
                                                                    r.candidatePlanDigest(),
                                                                    r.activePlanDigest(),
                                                                    r.factsDigest(),
                                                                    r.baselineResult(),
                                                                    "MATCH",
                                                                    true,
                                                                    true,
                                                                    true,
                                                                    true,
                                                                    r.operationalEvidence()))
                                            .toList();
                            return new DomainRuleTestRunResponse(
                                    UUID.randomUUID(),
                                    workspaceId,
                                    req.idempotencyKey(),
                                    "req-hash",
                                    req.workspaceRevision(),
                                    req.baseDefinitionHash(),
                                    req.evaluatedAtUtc(),
                                    req.userTimeZone(),
                                    req.activeSnapshotKey(),
                                    req.activeSnapshotContentHash(),
                                    req.activeActivationRevision(),
                                    req.baselineEvidence(),
                                    runResults,
                                    "alice",
                                    NOW);
                        });

        GovernedRulesStudioSandboxService sandboxService =
                new GovernedRulesStudioSandboxService(
                        registry,
                        ruleService,
                        workspaceService,
                        testRunService,
                        snapshotService,
                        JSON,
                        Clock.fixed(NOW, ZoneId.of("UTC")));
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("tenant-a", "alice", "local");

        var response =
                sandboxService.run(
                        new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                workspaceId,
                                List.of(scenarioAllowId, scenarioDenyId),
                                "UTC",
                                "idem-multi-op-alias",
                                NOW),
                        principal);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).candidateDecision()).isEqualTo("ALLOW");
        assertThat(response.results().get(0).activeDecision()).isEqualTo("DENY");
        assertThat(response.results().get(1).candidateDecision()).isEqualTo("DENY");
        verify(snapshotService).findActive("tenant-a", "local", "workforce.leave-requests", "CREATE");
    }

    private record RealTestRunSandboxHarness(
            GovernedRulesStudioSandboxService sandboxService,
            DomainRuleSnapshotService snapshotService,
            DomainRuleTestRunRepository runsRepo,
            DomainRuleGovernancePrincipal principal,
            UUID workspaceId,
            UUID scenarioAllowId,
            String workspaceEtag) {}

    private static RealTestRunSandboxHarness createRealTestRunSandboxHarness() throws Exception {
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(sampleFamily()));
        DomainRuleService ruleService = mock(DomainRuleService.class);
        DomainRuleChangeWorkspaceService workspaceService =
                mock(DomainRuleChangeWorkspaceService.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);

        DomainRuleTestRunRepository runsRepo = mock(DomainRuleTestRunRepository.class);
        DomainRuleTestRunResultRepository resultsRepo =
                mock(DomainRuleTestRunResultRepository.class);
        DomainRuleChangeWorkspaceRepository workspacesRepo =
                mock(DomainRuleChangeWorkspaceRepository.class);
        DomainRuleTestScenarioRepository scenariosRepo =
                mock(DomainRuleTestScenarioRepository.class);
        DomainRuleTestRunService realTestRunService =
                new DomainRuleTestRunService(
                        runsRepo, resultsRepo, workspacesRepo, scenariosRepo, JSON);

        UUID workspaceId = UUID.randomUUID();
        UUID baseDefId = UUID.randomUUID();
        UUID scenarioAllowId = UUID.randomUUID();
        String workspaceEtag = "\"ws-rev-1\"";
        String baseDefHash = "A".repeat(64);

        ObjectNode defNode = JSON.createObjectNode();
        defNode.put("ruleSetKey", "workforce.leave-requests");
        defNode.put("slotKey", "slot.max-days");
        ObjectNode wsParams = JSON.createObjectNode();
        wsParams.putObject("parameterValues").put("maxDays", 5);

        DomainRuleDefinitionResponse baseDef =
                new DomainRuleDefinitionResponse(
                        baseDefId,
                        "tenant-a",
                        "local",
                        "leave.max-days",
                        1,
                        "selection_eligibility",
                        "active",
                        "workforce",
                        "workforce.leave-requests",
                        "hr-service",
                        "owner",
                        "steward",
                        null,
                        null,
                        defNode,
                        JSON.createObjectNode(),
                        null,
                        null,
                        null,
                        "USER",
                        "alice",
                        null,
                        NOW,
                        NOW,
                        null,
                        NOW);
        DomainRuleChangeWorkspaceResponse workspace =
                new DomainRuleChangeWorkspaceResponse(
                        workspaceId,
                        "leave.max-days",
                        baseDefId,
                        1,
                        baseDefHash,
                        null,
                        null,
                        "Extend threshold to 5 days",
                        "OPEN",
                        null,
                        wsParams,
                        null,
                        1L,
                        workspaceEtag,
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        ObjectNode allowFacts = JSON.createObjectNode();
        allowFacts
                .putObject("draft")
                .put("requestedDays", 4)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        allowFacts.putObject("context").put("tenantId", "tenant-a");

        DomainRuleTestScenarioResponse allowScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioAllowId,
                        workspaceId,
                        "WITHIN_NEW_LIMIT",
                        "4 days allowed",
                        allowFacts,
                        "ALLOW",
                        null,
                        List.of(),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-1",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        DomainRuleChangeWorkspace wsEntity =
                DomainRuleChangeWorkspace.builder()
                        .id(workspaceId)
                        .tenantId("tenant-a")
                        .environment("local")
                        .status("OPEN")
                        .revision(1L)
                        .baseDefinitionHash(baseDefHash)
                        .build();
        DomainRuleTestScenario scenarioEntity =
                DomainRuleTestScenario.builder()
                        .id(scenarioAllowId)
                        .workspaceId(workspaceId)
                        .scenarioKey("WITHIN_NEW_LIMIT")
                        .expectedDecision("ALLOW")
                        .expectedOutput(null)
                        .expectedReasonCodes("[]")
                        .expectedEffectIntents("[]")
                        .build();

        when(workspaceService.get(eq(workspaceId), any())).thenReturn(workspace);
        when(workspaceService.scenarios(eq(workspaceId), any()))
                .thenReturn(List.of(allowScenario));
        when(ruleService.definition(eq(baseDefId), any())).thenReturn(baseDef);
        when(workspacesRepo.findById(workspaceId)).thenReturn(Optional.of(wsEntity));
        when(workspacesRepo.findByIdForUpdate(workspaceId)).thenReturn(Optional.of(wsEntity));
        when(runsRepo.findByTenantIdAndEnvironmentAndWorkspaceIdAndIdempotencyKey(
                        eq("tenant-a"), eq("local"), eq(workspaceId), any()))
                .thenReturn(Optional.empty());
        when(runsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resultsRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scenariosRepo.findById(scenarioAllowId)).thenReturn(Optional.of(scenarioEntity));

        GovernedRulesStudioSandboxService sandboxService =
                new GovernedRulesStudioSandboxService(
                        registry,
                        ruleService,
                        workspaceService,
                        realTestRunService,
                        snapshotService,
                        JSON,
                        Clock.fixed(NOW, ZoneId.of("UTC")));
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("tenant-a", "alice", "local");
        return new RealTestRunSandboxHarness(
                sandboxService,
                snapshotService,
                runsRepo,
                principal,
                workspaceId,
                scenarioAllowId,
                workspaceEtag);
    }

    @Test
    void actionRegistryPostProcessorPublishesOperationalProofActionOncePerGovernedResource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withUserConfiguration(ActionRegistryTestConfig.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            ActionDefinitionRegistry registry =
                                    context.getBean(ActionDefinitionRegistry.class);

                            List<ActionDefinition> leaveActions =
                                    registry.findByResourceKey("workforce.leave-requests");
                            assertThat(leaveActions)
                                    .extracting(ActionDefinition::id)
                                    .containsExactly(
                                            "approve-leave",
                                            GovernedRulesStudioSandboxController
                                                    .OPERATIONAL_TEST_ACTION_ID);
                            ActionDefinition promoted = leaveActions.get(1);
                            assertThat(promoted.resourcePath())
                                    .isEqualTo("/api/workforce/leave-requests");
                            assertThat(promoted.group()).isEqualTo("workforce");
                            assertThat(promoted.operation().path())
                                    .isEqualTo(
                                            GovernedRulesStudioSandboxController
                                                    .OPERATIONAL_TEST_ACTION_PATH);

                            assertThat(registry.findByResourceKey("workforce.already-configured"))
                                    .hasSize(1);
                            assertThat(registry.findByResourceKey("unrelated.resource")).isEmpty();
                        });
    }

    @Test
    void multiTenantSnapshotBootstrapperConvergesScopesAndRespectsWithdrawnHeads()
            throws Exception {
        GovernedRuleFamilyDescriptor<LeaveOp> family = sampleFamily();
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(family));
        DomainRuleSnapshotHeadRepository headRepo = mock(DomainRuleSnapshotHeadRepository.class);
        DomainRuleSnapshotRepository snapRepo = mock(DomainRuleSnapshotRepository.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);
        DomainRuleRolloutPolicyService policyService = mock(DomainRuleRolloutPolicyService.class);
        DomainRuleRolloutService rolloutService = mock(DomainRuleRolloutService.class);
        DomainRuleService ruleService = mock(DomainRuleService.class);
        when(ruleService.definitions(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        GovernedRulesStudioDefinitionSeeder seeder =
                new GovernedRulesStudioDefinitionSeeder(
                        registry,
                        ruleService,
                        JSON,
                        List.of("tenant-a", "tenant-b"),
                        "local");

        DomainRuleSnapshotHead withdrawnHead =
                DomainRuleSnapshotHead.builder()
                        .id(UUID.randomUUID())
                        .tenantId("tenant-b")
                        .environment("local")
                        .ruleSetKey("workforce.leave-requests")
                        .operationKey("CREATE")
                        .withdrawn(true)
                        .build();
        when(headRepo.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
                        "tenant-b", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(Optional.of(withdrawnHead));
        when(headRepo.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(
                        "tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(Optional.empty());
        when(snapRepo.findMaximumPublicationRevision(
                        "tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(null);

        UUID policyId = UUID.randomUUID();
        UUID policyHeadEtag = UUID.randomUUID();
        when(policyService.catalog(
                        eq("workforce.leave-requests"), any(), eq(true), eq(true), eq(true)))
                .thenReturn(
                        new DomainRuleRolloutPolicyCatalogResponse(
                                "workforce.leave-requests", 0L, null, null, List.of(), List.of()));
        DomainRuleRolloutPolicyResponse policyResponse =
                new DomainRuleRolloutPolicyResponse(
                        policyId,
                        "workforce.leave-requests",
                        ProductBaselineGenesisInitializer.GENESIS_POLICY_KEY,
                        1,
                        "DRAFT",
                        "REQUIRED",
                        1,
                        BigDecimal.ONE,
                        true,
                        300L,
                        3600L,
                        ProductBaselineGenesisInitializer.ACTOR_POLICY_AUTHOR,
                        NOW,
                        null,
                        null,
                        null,
                        null,
                        List.of());
        when(policyService.create(any(), any()))
                .thenReturn(
                        new DomainRuleRolloutPolicyMutationResponse(
                                policyResponse, 0L, policyHeadEtag.toString()));
        when(policyService.approve(eq(policyId), any()))
                .thenReturn(
                        new DomainRuleRolloutPolicyMutationResponse(
                                policyResponse, 0L, policyHeadEtag.toString()));
        when(snapshotService.prepareCompositionManifest(any(), eq("tenant-a"), eq("local")))
                .thenReturn(
                        new DomainRuleCompositionManifestResponse(
                                "praxis.domain-rule-composition.v1",
                                "A".repeat(64),
                                "B".repeat(64),
                                JSON.createObjectNode()));

        RuleSetDefinition canonicalDef = family.productProfiles().getFirst().ruleSet();
        PublishedRuleSnapshot publishedSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-boot-v1",
                        "tenant-a",
                        "local",
                        "hr-service",
                        1,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        "2026-01-01T00:00:00Z",
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "src-1", "workforce.leave-requests", 1, "C".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "a1",
                                        "RULE_COMPOSITION_APPROVER",
                                        ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_1,
                                        NOW.toString(),
                                        "C".repeat(64)),
                                new RuleSnapshotApproval(
                                        "a2",
                                        "RULE_COMPOSITION_APPROVER",
                                        ProductBaselineGenesisInitializer.ACTOR_COMP_APPROVER_2,
                                        NOW.toString(),
                                        "C".repeat(64))),
                        canonicalDef);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(publishedSnapshot));
        when(snapshotService.publish(any(), eq("tenant-a"), eq("local"), any(), any(), any()))
                .thenReturn(new DomainRuleSnapshotStoredResponse(publishedSnapshot, contentHash));
        when(snapshotService.findSnapshot("tenant-a", "local", "snap-boot-v1"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotStoredResponse(
                                        publishedSnapshot, contentHash)));

        UUID rolloutId = UUID.randomUUID();
        when(rolloutService.create(any(), any(), any(), any()))
                .thenReturn(
                        new DomainRuleRolloutResponse(
                                rolloutId,
                                "workforce.leave-requests",
                                "CREATE",
                                "snap-boot-v1",
                                contentHash,
                                null,
                                null,
                                ProductBaselineGenesisInitializer.GENESIS_POLICY_KEY,
                                1,
                                "REQUIRED",
                                "PREPARING",
                                NOW,
                                NOW.plusSeconds(3600)));
        when(rolloutService.pending(eq("workforce.leave-requests"), eq("CREATE"), any()))
                .thenReturn(
                        Optional.of(
                                new DomainRulePendingRolloutResponse(
                                        rolloutId,
                                        "workforce.leave-requests",
                                        "CREATE",
                                        "snap-boot-v1",
                                        contentHash,
                                        "PREPARING",
                                        NOW.plusSeconds(3600))));
        when(rolloutService.probe(eq(rolloutId), any(), any()))
                .thenReturn(new DomainRuleCandidateProbeResponse(true, NOW));
        when(rolloutService.readiness(eq(rolloutId), any()))
                .thenReturn(
                        new DomainRuleRolloutReadinessResponse(
                                rolloutId,
                                "workforce.leave-requests",
                                "snap-boot-v1",
                                "READY",
                                "REQUIRED",
                                1,
                                1.0,
                                1,
                                1,
                                0,
                                0,
                                0,
                                true,
                                NOW.minusSeconds(300)));
        when(snapshotService.activatePublished(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new DomainRuleSnapshotActivationResponse(
                                publishedSnapshot, contentHash, "\"head-1\"", 1L, "ACTIVATE"));

        GovernedMultiTenantSnapshotBootstrapper bootstrapper =
                new GovernedMultiTenantSnapshotBootstrapper(
                        registry,
                        headRepo,
                        snapRepo,
                        snapshotService,
                        policyService,
                        rolloutService,
                        DomainRuleImplementationCatalog.denyAll(),
                        seeder,
                        JSON,
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        List.of("tenant-a", "tenant-b", "tenant-a"),
                        List.of("local"),
                        "2026-01-01T00:00:00Z",
                        true,
                        true);

        BootstrapReport report = bootstrapper.bootstrapAll();
        assertThat(report.scopesProcessed()).isEqualTo(2);
        assertThat(report.totalActivatedOperations()).isEqualTo(1);
        assertThat(report.totalSkippedOperations()).isEqualTo(1);
        assertThat(report.totalDefinitionsSeeded()).isEqualTo(4);
        verify(snapshotService, never())
                .publish(any(), eq("tenant-b"), eq("local"), any(), any(), any());
    }

    @Test
    void sandboxAndActionRegistryStartCleanlyWithoutMetadataStarterOnClasspath() {
        new ApplicationContextRunner()
                .withClassLoader(
                        new FilteredClassLoader(
                                ActionDefinitionRegistry.class,
                                org.praxisplatform.uischema.authoring
                                        .DtoMetadataFactCatalogEnricher.class,
                                org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider
                                        .class))
                .withConfiguration(AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withUserConfiguration(MinimalNoMetadataHostConfig.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(GovernedRulesStudioSandboxService.class);
                            assertThat(context)
                                    .hasSingleBean(GovernedRulesStudioSandboxController.class);
                            assertThat(context).doesNotHaveBean(DtoRuleFactContractFactory.class);
                            assertThat(context)
                                    .doesNotHaveBean("governedRulesStudioActionRegistryPostProcessor");
                        });
    }

    @Test
    void resolveActiveSnapshotPrefersHigherActivationRevisionOnHintedRollback() throws Exception {
        GovernedRuleFamilyDescriptor<LeaveOp> family = sampleFamily();
        GovernedRuleFamilyRegistry registry = new GovernedRuleFamilyRegistry(List.of(family));
        DomainRuleService ruleService = mock(DomainRuleService.class);
        DomainRuleChangeWorkspaceService workspaceService =
                mock(DomainRuleChangeWorkspaceService.class);
        DomainRuleTestRunService testRunService = mock(DomainRuleTestRunService.class);
        DomainRuleSnapshotService snapshotService = mock(DomainRuleSnapshotService.class);

        UUID workspaceId = UUID.randomUUID();
        UUID baseDefId = UUID.randomUUID();
        UUID scenarioAllowId = UUID.randomUUID();

        ObjectNode defNode = JSON.createObjectNode();
        defNode.put("ruleSetKey", "workforce.leave-requests");
        defNode.put("slotKey", "slot.max-days");
        ObjectNode baseParams = JSON.createObjectNode();
        baseParams.putArray("operationKeys").add("validate");
        ObjectNode wsParams = JSON.createObjectNode();
        wsParams.putObject("parameterValues").put("maxDays", 5);

        DomainRuleDefinitionResponse baseDef =
                new DomainRuleDefinitionResponse(
                        baseDefId,
                        "tenant-a",
                        "local",
                        "leave.max-days",
                        1,
                        "selection_eligibility",
                        "active",
                        "workforce",
                        "workforce.leave-requests",
                        "hr-service",
                        "owner",
                        "steward",
                        null,
                        null,
                        defNode,
                        baseParams,
                        null,
                        null,
                        null,
                        "USER",
                        "alice",
                        null,
                        NOW,
                        NOW,
                        null,
                        NOW);
        DomainRuleChangeWorkspaceResponse workspace =
                new DomainRuleChangeWorkspaceResponse(
                        workspaceId,
                        "leave.max-days",
                        baseDefId,
                        1,
                        "HASH-1",
                        null,
                        null,
                        "Rollback comparison",
                        "OPEN",
                        null,
                        wsParams,
                        null,
                        1L,
                        "\"ws-rev-1\"",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        ObjectNode allowFacts = JSON.createObjectNode();
        allowFacts
                .putObject("draft")
                .put("requestedDays", 4)
                .put("applicantTaxId", "12345678901")
                .put("clearanceAttached", true);
        allowFacts.putObject("context").put("tenantId", "tenant-a");

        DomainRuleTestScenarioResponse allowScenario =
                new DomainRuleTestScenarioResponse(
                        scenarioAllowId,
                        workspaceId,
                        "WITHIN_LIMIT",
                        "4 days",
                        allowFacts,
                        "ALLOW",
                        null,
                        List.of(),
                        List.of(),
                        "ACTIVE",
                        1L,
                        "s-1",
                        "alice",
                        "alice",
                        NOW,
                        NOW);

        RuleSetDefinition v1CreateDef = family.operationSpecs().get(LeaveOp.CREATE).productRuleSet();
        RuleSetDefinition v2CreateDef =
                new RuleSetDefinition(
                        new RuleSetRef(
                                "workforce", "hr", "workforce.leave-requests", "CREATE", 2),
                        v1CreateDef.availableRoots(),
                        v1CreateDef.slots(),
                        v1CreateDef.bindings(),
                        v1CreateDef.compatibility(),
                        v1CreateDef.failPolicy(),
                        v1CreateDef.errorDescriptors());
        RuleSetDefinition v1ValidateRollbackDef =
                new RuleSetDefinition(
                        new RuleSetRef(
                                "workforce", "hr", "workforce.leave-requests", "validate", 1),
                        v1CreateDef.availableRoots(),
                        v1CreateDef.slots(),
                        v1CreateDef.bindings(),
                        v1CreateDef.compatibility(),
                        v1CreateDef.failPolicy(),
                        v1CreateDef.errorDescriptors());

        PublishedRuleSnapshot createV2Snapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-create-v2",
                        "tenant-a",
                        "local",
                        "hr-service",
                        2,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        NOW.minusSeconds(60).toString(),
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "def-c2", "workforce.leave-requests", 2, "A".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "ap-c2",
                                        "APPROVER",
                                        "alice",
                                        NOW.toString(),
                                        "B".repeat(64))),
                        v2CreateDef);
        PublishedRuleSnapshot validateRollbackSnapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-validate-v1-rb",
                        "tenant-a",
                        "local",
                        "hr-service",
                        3,
                        NOW.toString(),
                        "snap-validate-v2",
                        "host/1.0",
                        NOW.minusSeconds(60).toString(),
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        "def-v1", "workforce.leave-requests", 1, "C".repeat(64))),
                        List.of(
                                new RuleSnapshotApproval(
                                        "ap-v1",
                                        "APPROVER",
                                        "alice",
                                        NOW.toString(),
                                        "D".repeat(64))),
                        v1ValidateRollbackDef);

        when(workspaceService.get(eq(workspaceId), any())).thenReturn(workspace);
        when(workspaceService.scenarios(eq(workspaceId), any())).thenReturn(List.of(allowScenario));
        when(ruleService.definition(eq(baseDefId), any())).thenReturn(baseDef);
        when(snapshotService.findActive("tenant-a", "local", "workforce.leave-requests", "CREATE"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotActivationResponse(
                                        createV2Snapshot, "HASH-C2", "\"etag-c2\"", 1L, "ACTIVE")));
        when(snapshotService.findActive("tenant-a", "local", "workforce.leave-requests", "validate"))
                .thenReturn(
                        Optional.of(
                                new DomainRuleSnapshotActivationResponse(
                                        validateRollbackSnapshot,
                                        "HASH-V1-RB",
                                        "\"etag-v1-rb\"",
                                        3L,
                                        "ROLLED_BACK")));
        when(testRunService.findByIdempotencyKey(eq(workspaceId), any(), any()))
                .thenReturn(Optional.empty());
        when(testRunService.record(eq(workspaceId), any(), any()))
                .thenAnswer(
                        inv -> {
                            DomainRuleTestRunRecordRequest req = inv.getArgument(1);
                            return new DomainRuleTestRunResponse(
                                    UUID.randomUUID(),
                                    workspaceId,
                                    req.idempotencyKey(),
                                    "REQ-HASH",
                                    req.workspaceRevision(),
                                    req.baseDefinitionHash(),
                                    req.evaluatedAtUtc(),
                                    req.userTimeZone(),
                                    req.activeSnapshotKey(),
                                    req.activeSnapshotContentHash(),
                                    req.activeActivationRevision(),
                                    req.baselineEvidence(),
                                    List.of(),
                                    "alice",
                                    NOW);
                        });

        GovernedRulesStudioSandboxService sandboxService =
                new GovernedRulesStudioSandboxService(
                        registry,
                        ruleService,
                        workspaceService,
                        testRunService,
                        snapshotService,
                        JSON,
                        Clock.fixed(NOW, ZoneId.of("UTC")));
        DomainRuleGovernancePrincipal principal =
                new DomainRuleGovernancePrincipal("tenant-a", "alice", "local");

        GovernedRulesStudioSandboxService.SandboxRunResponse response =
                sandboxService.run(
                        new GovernedRulesStudioSandboxService.SandboxRunRequest(
                                workspaceId,
                                List.of(scenarioAllowId),
                                "UTC",
                                "idem-rb-1",
                                NOW),
                        principal);

        assertThat(response).isNotNull();
        ArgumentCaptor<DomainRuleTestRunRecordRequest> reqCaptor =
                ArgumentCaptor.forClass(DomainRuleTestRunRecordRequest.class);
        verify(testRunService).record(eq(workspaceId), reqCaptor.capture(), eq(principal));
        assertThat(reqCaptor.getValue().activeSnapshotKey()).isEqualTo("snap-validate-v1-rb");
        assertThat(reqCaptor.getValue().activeActivationRevision()).isEqualTo(3L);
    }

    private static GovernedRuleFamilyDescriptor<LeaveOp> sampleFamily() throws Exception {
        RuleSetRef ref =
                new RuleSetRef("workforce", "hr", "workforce.leave-requests", "CREATE", 1);
        JsonNode expr = JSON.readTree("{\"<=\":[{\"var\":\"draft.requestedDays\"},2]}");
        RuleParameterTemplate template =
                new RuleParameterTemplate(
                        "workforce:leave.max-days.tpl",
                        "1.0",
                        RuleExecutorRef.jsonLogic(
                                JSON.readTree(
                                        "{\"<=\":[{\"var\":\"draft.requestedDays\"},2]}")),
                        RuleDecision.DENY,
                        "WF-00001",
                        List.of("draft.requestedDays"),
                        List.of(
                                new RuleParameterDefinition(
                                        "maxDays",
                                        RuleParameterType.INTEGER,
                                        true,
                                        false,
                                        false,
                                        "days",
                                        JSON.readTree("2"),
                                        BigDecimal.ONE,
                                        BigDecimal.valueOf(30),
                                        List.of(),
                                        List.of("/<=/1"))));
        DecisionSlot guardSlot =
                new DecisionSlot(
                        "slot.guard",
                        DecisionStage.PROTECTED_GUARD,
                        SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        null,
                        List.of());
        DecisionSlot maxDaysSlot =
                new DecisionSlot(
                        "slot.max-days",
                        DecisionStage.DOMAIN_DECISION,
                        SlotCardinality.SINGLE,
                        OverridePolicy.PARAMETERIZABLE,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        template,
                        List.of());

        DecisionBinding guardBinding =
                new DecisionBinding(
                        "leave.guard",
                        "slot.guard",
                        DecisionSource.PRODUCT,
                        null,
                        RuleExecutorRef.jsonLogic(
                                JSON.readTree("{\"==\":[{\"var\":\"draft.clearanceAttached\"},true]}")),
                        List.of(),
                        10,
                        true,
                        RuleDecision.DENY,
                        "WF-00000",
                        List.of("draft.clearanceAttached"),
                        null);
        DecisionBinding maxDaysBinding =
                new DecisionBinding(
                        "leave.max-days",
                        "slot.max-days",
                        DecisionSource.PRODUCT,
                        null,
                        RuleExecutorRef.jsonLogic(expr),
                        List.of(),
                        20,
                        true,
                        RuleDecision.DENY,
                        "WF-00001",
                        List.of("draft.requestedDays"),
                        null);

        RuleSetDefinition def =
                new RuleSetDefinition(
                        ref,
                        List.of("draft", "context"),
                        List.of(guardSlot, maxDaysSlot),
                        List.of(guardBinding, maxDaysBinding),
                        RuleRuntimeCompatibility.current(),
                        RuleFailPolicy.FAIL_CLOSED,
                        Map.of());

        String schemaJson =
                """
                {
                  "type": "object",
                  "required": ["draft", "context"],
                  "additionalProperties": false,
                  "maxProperties": 2,
                  "properties": {
                    "draft": {
                      "type": "object",
                      "required": ["requestedDays", "clearanceAttached"],
                      "additionalProperties": false,
                      "maxProperties": 4,
                      "properties": {
                        "requestedDays": { "type": "integer" },
                        "applicantTaxId": { "type": ["string", "null"], "maxLength": 32 },
                        "clearanceAttached": { "type": "boolean" }
                      }
                    },
                    "context": {
                      "type": "object",
                      "required": ["tenantId"],
                      "additionalProperties": false,
                      "maxProperties": 2,
                      "properties": {
                        "tenantId": { "type": "string", "maxLength": 64 }
                      }
                    }
                  }
                }
                """;
        RuleFactContract contract =
                new RuleFactContract(
                        "host/1.0",
                        "CREATE",
                        "provider/1.0",
                        RuleFactSchema.compile(JSON.readTree(schemaJson)),
                        List.of());
        GovernedHostRuleBundleRuntime<LeaveOp> runtime =
                new GovernedHostRuleBundleRuntime<>(
                        LeaveOp.class,
                        "hr-service",
                        "workforce.leave-requests",
                        "host/1.0",
                        "provider/1.0",
                        List.of(new OperationSpec<>(LeaveOp.CREATE, "CREATE", def, contract)),
                        requested -> Optional.empty(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        RuleErrorCatalog.of(
                                Map.of(
                                        "WF-00000",
                                        new RuleErrorDescriptor(
                                                "WF-00000",
                                                "draft.clearanceAttached",
                                                "Missing medical clearance",
                                                "leave.guard",
                                                "ELIGIBILITY"),
                                        "WF-00001",
                                        new RuleErrorDescriptor(
                                                "WF-00001",
                                                "draft.requestedDays",
                                                "Leave limit exceeded",
                                                "leave.max-days",
                                                "LIMIT"))));
        return new GovernedRuleFamilyDescriptor<>(
                runtime,
                null,
                Map.of("draft", LeaveRequestDraftDto.class),
                "workforce.leave-requests",
                "Workforce Leave Requests",
                "Workforce leave eligibility",
                Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    static class ActionRegistryTestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return JSON;
        }

        @Bean
        GovernedRuleFamilyRegistry governedRuleFamilyRegistry() throws Exception {
            return new GovernedRuleFamilyRegistry(List.of(sampleFamily()));
        }

        @Bean
        ActionDefinitionRegistry actionDefinitionRegistry() {
            CanonicalOperationRef approveOp =
                    new CanonicalOperationRef(
                            "workforce", "approveLeave", "/api/workforce/leave-requests/approve", "POST");
            ActionDefinition approveAction =
                    new ActionDefinition(
                            "approve-leave",
                            "workforce.leave-requests",
                            "/api/workforce/leave-requests",
                            "workforce",
                            ActionScope.ITEM,
                            "Approve Leave",
                            "Approves a leave request",
                            approveOp,
                            new CanonicalSchemaRef("sch-req", "request", "/schemas/filtered"),
                            new CanonicalSchemaRef("sch-res", "response", "/schemas/filtered"),
                            10,
                            "Approved",
                            List.of("LEAVE_APPROVER"),
                            List.of("PENDING"),
                            List.of("workflow"),
                            ActionExecutionContract.defaults(ActionScope.ITEM));
            ActionDefinition existingOpAction =
                    new ActionDefinition(
                            GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_ID,
                            "workforce.already-configured",
                            "/api/workforce/already-configured",
                            "workforce",
                            ActionScope.COLLECTION,
                            "Run Operational Proof",
                            "Existing operational proof",
                            new CanonicalOperationRef(
                                    "rules-studio",
                                    "runRulesStudioOperationalTest",
                                    GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_PATH,
                                    "POST"),
                            new CanonicalSchemaRef("sch-req", "request", "/schemas/filtered"),
                            new CanonicalSchemaRef("sch-res", "response", "/schemas/filtered"),
                            40,
                            "Done",
                            List.of(),
                            List.of(),
                            List.of("operational-proof"),
                            ActionExecutionContract.defaults(ActionScope.COLLECTION));

            return new ActionDefinitionRegistry() {
                @Override
                public List<ActionDefinition> findByResourceKey(String resourceKey) {
                    if ("workforce.leave-requests".equals(resourceKey)) {
                        return List.of(approveAction);
                    }
                    if ("workforce.already-configured".equals(resourceKey)) {
                        return List.of(existingOpAction);
                    }
                    return List.of();
                }

                @Override
                public List<ActionDefinition> findByGroup(String group) {
                    return List.of();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MinimalNoMetadataHostConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JSON;
        }

        @Bean
        GovernedRuleFamilyRegistry governedRuleFamilyRegistry() throws Exception {
            return new GovernedRuleFamilyRegistry(List.of(sampleFamily()));
        }

        @Bean
        DomainRuleService domainRuleService() {
            return mock(DomainRuleService.class);
        }

        @Bean
        DomainRuleSnapshotService domainRuleSnapshotService() {
            return mock(DomainRuleSnapshotService.class);
        }

        @Bean
        DomainRuleChangeWorkspaceService domainRuleChangeWorkspaceService() {
            return mock(DomainRuleChangeWorkspaceService.class);
        }

        @Bean
        DomainRuleTestRunService domainRuleTestRunService() {
            return mock(DomainRuleTestRunService.class);
        }

        @Bean
        DomainRuleGovernancePrincipalResolver domainRuleGovernancePrincipalResolver() {
            return new DomainRuleGovernancePrincipalResolver(
                    new AiPrincipalContextResolver(
                            false, true, "tenant-a", "local", "alice", "RULE_DEFINITION_AUTHOR"),
                    false);
        }
    }
}
