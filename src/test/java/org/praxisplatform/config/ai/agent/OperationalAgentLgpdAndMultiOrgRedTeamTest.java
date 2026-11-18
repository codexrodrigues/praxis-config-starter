package org.praxisplatform.config.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.bridge.AgentCalculationMemoryEvaluator;
import org.praxisplatform.config.ai.agent.bridge.DefaultMetadataGovernanceAgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.AgentProposalExternalCompletionRequest;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService.ConfirmHttpResult;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamRequest;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop.ConversationHistoryEntry;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.CompositeAgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.domain.AiAgentActionProposal;
import org.praxisplatform.config.domain.AiAgentActionProposalStatus;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.repository.AiAgentActionProposalRepository;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class OperationalAgentLgpdAndMultiOrgRedTeamTest {

    static class PresetGovernedEmployeeDto {
        public Long id = 10L;
        public String fullName = "Mariana Souza";

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        public String taxId = "52998224725";

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_CONTACT)
        public String corporateEmail = "mariana.souza@workforce.gov";

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_SENSITIVE_DATA)
        public String medicalConditionCode = "CID-Z99";

        @DomainGovernance(preset = DomainGovernancePreset.CONFIDENTIAL_FINANCIAL)
        public String bankAccount = "0001-998877-6";

        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL)
        public String apiSecretToken = "sec_live_987654";

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_SENSITIVE_DATA)
        public Boolean hasDisabilityAccommodation = Boolean.TRUE;
    }

    @Test
    void enforcesDomainGovernancePresetsWithoutExplicitAiUsagePolicy() {
        DefaultMetadataGovernanceAgentAuthorizationBridge bridge =
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of());
        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "user-1", "prod", "user-1", Set.of("ROLE_HR"),
                "/api/workforce/employees", "employees", Map.of(), Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 10L);
        raw.put("fullName", "Mariana Souza");
        raw.put("taxId", "52998224725");
        raw.put("corporateEmail", "mariana.souza@workforce.gov");
        raw.put("medicalConditionCode", "CID-Z99");
        raw.put("bankAccount", "0001-998877-6");
        raw.put("apiSecretToken", "sec_live_987654");
        raw.put("hasDisabilityAccommodation", Boolean.TRUE);

        DualProjectedPayload projected = bridge.projectRecord(
                ctx, "/api/workforce/employees", PresetGovernedEmployeeDto.class, raw);

        assertThat(projected.uiRecord()).containsEntry("taxId", "52998224725");
        assertThat(projected.uiRecord()).containsEntry("medicalConditionCode", "CID-Z99");
        assertThat(projected.uiRecord()).containsEntry("apiSecretToken", "sec_live_987654");

        assertThat(projected.modelSafeRecord())
                .containsEntry("id", 10L)
                .containsEntry("fullName", "Mariana Souza")
                .doesNotContainKey("medicalConditionCode")
                .doesNotContainKey("hasDisabilityAccommodation")
                .doesNotContainKey("apiSecretToken");
        assertThat(String.valueOf(projected.modelSafeRecord().get("taxId")))
                .doesNotContain("52998224725");
        assertThat(String.valueOf(projected.modelSafeRecord().get("corporateEmail")))
                .doesNotContain("mariana.souza@workforce.gov");
        assertThat(String.valueOf(projected.modelSafeRecord().get("bankAccount")))
                .doesNotContain("0001-998877-6");
    }

    @Test
    void preventsRawCpfEmailLeakViaSubtitleFieldAlertsOrMultiTurnHistory() throws Exception {
        String rawCpfTurn1 = "52998224725";
        String rawCpfTurn2 = "11144477735";
        String rawEmail = "ana.clara@workforce.gov";

        AgentEntityDescriptor descriptor = new AgentEntityDescriptor(
                "employee", "workforce.employees", "/api/workforce/employees",
                "Servidores", "Cadastro", "badge", "id",
                "fullName", "taxId", List.of("taxId", "corporateEmail"),
                "taxId", "fullName", "id", "/employees/{id}", "people", 10, null, true);

        Map<String, Object> uiRecord = Map.of(
                "id", "10",
                "fullName", "Ana Clara",
                "taxId", "529.982.247-25",
                "corporateEmail", rawEmail);
        Map<String, Object> modelSafeRecord = Map.of(
                "id", "10",
                "fullName", "Ana Clara",
                "taxId", "***.982.247-**");

        AgentEntityMatch match = new AgentEntityMatch(
                "employee", "workforce.employees", "/api/workforce/employees",
                "Servidores", "10", "Ana Clara", "529.982.247-25",
                "taxId", 0.99d, uiRecord, modelSafeRecord, true);

        AgentEntityDossier dossier = new AgentEntityDossier(
                descriptor, "10", "Ana Clara", "529.982.247-25",
                uiRecord, modelSafeRecord, List.of(), List.of("taxId", "corporateEmail"),
                List.of(),
                List.of(new AgentProactiveAlert(
                        "ALERT_TEST", "warning",
                        "Alerta CPF 529.982.247-25",
                        "Notificar " + rawEmail + " sobre CPF " + rawCpfTurn2,
                        "employee", "/api/workforce/employees", "10", null, null, Map.of())),
                List.of(), List.of(), List.of(), null);

        AgentResourceExecutionBridge bridge = mock(AgentResourceExecutionBridge.class);
        when(bridge.searchEntities(any(), any(AgentEntitySearchRequest.class))).thenReturn(List.of(match));
        when(bridge.loadEntityDossier(any(), any(), any(), any())).thenReturn(Optional.of(dossier));

        List<AgentModelTurnRequest> capturedModelRequests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        AiToolCallingProvider spyProvider = new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return "gemini";
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    AgentModelTurnRequest req, AgentModelStreamListener listener) {
                capturedModelRequests.add(req);
                if (round.getAndIncrement() == 0) {
                    return new AgentModelTurnResponse(
                            "gemini", "gemini-2.5-flash", "",
                            List.of(new AgentToolCall("call-1", "search_and_resolve_entities",
                                    Map.of("query", "[TAX_ID_2]"), null, "{}")),
                            AgentTokenUsage.ZERO, "STOP", "resp-1", Map.of());
                }
                return new AgentModelTurnResponse(
                        "gemini", "gemini-2.5-flash", "Cadastro consultado para [TAX_ID_1].",
                        List.of(), AgentTokenUsage.ZERO, "STOP", "resp-2", Map.of());
            }
        };

        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                spyProvider,
                List.of(new SearchAndResolveEntitiesTool(bridge), new GetEntity360DossierTool(bridge)),
                new OperationalAssistantBlockComposer(new ObjectMapper()),
                4,
                bridge);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "user-1", "prod", "user-1", Set.of(),
                null, null, Map.of(), Map.of());
        String turn2Prompt = "Consultar tambem " + rawCpfTurn2 + " depois de " + rawCpfTurn1;
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize(turn2Prompt);
        // Add an extra CPF only present in earlier conversation history to guarantee distinct token index
        String extraHistoryCpf = "98765432100";
        loop.execute(
                ctx,
                turn2Prompt,
                vault,
                List.of(new ConversationHistoryEntry("user", "Historico com " + extraHistoryCpf + " e " + rawEmail)),
                AiCallConfig.builder().provider("gemini").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);

        String serializedRequests = new ObjectMapper().writeValueAsString(capturedModelRequests);
        assertThat(serializedRequests)
                .doesNotContain(rawCpfTurn1)
                .doesNotContain("529.982.247-25")
                .doesNotContain(rawCpfTurn2)
                .doesNotContain(rawEmail);
        assertThat(vault.tokens()).contains("[TAX_ID_1]", "[TAX_ID_2]");
        // Validating extraHistoryCpf did not collide with [TAX_ID_1]
        assertThat(vault.tokens()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void preventsVisibleWhenSideChannelLeakInCalculationMemoryModelSafeTrails() {
        AgentEntityDescriptor descriptor = new AgentEntityDescriptor(
                "payroll", "payroll.slips", "/api/payroll/slips",
                "Contracheque", "", "calculate", "id",
                "fullName", null, List.of(), null, null, null, null, "payroll", 1, null, true);

        Map<String, Object> calcMeta = Map.of(
                "title", "Demonstrativo",
                "trails", List.of(Map.of(
                        "id", "medical-grant",
                        "title", "Beneficio medico sigiloso",
                        "visibleWhen", "medicalProtectedFlag == true",
                        "resultExpr", "baseAmount",
                        "steps", List.of())));

        DualProjectedPayload projected = new DualProjectedPayload(
                Map.of("medicalProtectedFlag", true, "baseAmount", 1000),
                Map.of("baseAmount", 1000),
                List.of(),
                List.of("medicalProtectedFlag"));

        AgentEvaluatedCalculationMemory evaluated = AgentCalculationMemoryEvaluator.evaluate(
                descriptor, "1", "Servidor", calcMeta, projected);

        assertThat(evaluated).isNotNull();
        assertThat(evaluated.uiTrails()).hasSize(1);
        assertThat(evaluated.modelSafeTrails()).isEmpty();
    }

    @Test
    void completeViaExternalFormRejectsExpiredStaleAndReadOnlyConsolidation() {
        AiAgentActionProposalRepository repo = mock(AiAgentActionProposalRepository.class);
        AgentResourceExecutionBridge bridge = mock(AgentResourceExecutionBridge.class);
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "prod", true, Set.of("ROLE_USER"));

        UUID expiredId = UUID.randomUUID();
        AiAgentActionProposal expiredProposal = AiAgentActionProposal.builder()
                .proposalId(expiredId)
                .tenantId("tenant-a")
                .userId("user-1")
                .environment("prod")
                .status(AiAgentActionProposalStatus.PENDING)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(repo.findByProposalIdAndTenantIdAndUserIdAndEnvironment(expiredId, "tenant-a", "user-1", "prod"))
                .thenReturn(Optional.of(expiredProposal));
        when(repo.findById(expiredId)).thenReturn(Optional.of(expiredProposal));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentExecutionContextCustomizer readOnlyCustomizer = (ctx, req) -> new AgentExecutionContext(
                ctx.threadId(), ctx.turnId(), ctx.streamId(), ctx.tenantId(), ctx.userId(), ctx.environment(),
                ctx.principalName(), ctx.authorities(), ctx.activeScreenResourcePath(), ctx.activeTopicKey(),
                ctx.activeSelectionHints(), Map.of("readOnlyConsolidation", true, "primaryOrganization", "2"));

        OperationalAgentProposalService service = new OperationalAgentProposalService(
                repo, bridge, List.of(readOnlyCustomizer), null, new ObjectMapper(), 900L);

        ConfirmHttpResult expiredResult = service.completeViaExternalForm(
                expiredId, principal, new MockHttpServletRequest(),
                new AgentProposalExternalCompletionRequest(null, null, null));
        assertThat(expiredResult.httpStatus()).isEqualTo(409);
        assertThat(expiredResult.response().outcomeCode()).isEqualTo("PROPOSAL_EXPIRED");

        UUID staleId = UUID.randomUUID();
        AiAgentActionProposal staleProposal = AiAgentActionProposal.builder()
                .proposalId(staleId)
                .tenantId("tenant-a")
                .userId("user-1")
                .environment("prod")
                .status(AiAgentActionProposalStatus.STALE)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(repo.findByProposalIdAndTenantIdAndUserIdAndEnvironment(staleId, "tenant-a", "user-1", "prod"))
                .thenReturn(Optional.of(staleProposal));

        ConfirmHttpResult staleResult = service.completeViaExternalForm(
                staleId, principal, new MockHttpServletRequest(),
                new AgentProposalExternalCompletionRequest(null, null, null));
        assertThat(staleResult.httpStatus()).isEqualTo(412);
        assertThat(staleResult.response().outcomeCode()).isEqualTo("STALE_RESOURCE_VERSION");

        UUID roId = UUID.randomUUID();
        AiAgentActionProposal roProposal = AiAgentActionProposal.builder()
                .proposalId(roId)
                .tenantId("tenant-a")
                .userId("user-1")
                .environment("prod")
                .status(AiAgentActionProposalStatus.PENDING)
                .organizationId("2")
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(repo.findByProposalIdAndTenantIdAndUserIdAndEnvironment(roId, "tenant-a", "user-1", "prod"))
                .thenReturn(Optional.of(roProposal));

        ConfirmHttpResult roResult = service.completeViaExternalForm(
                roId, principal, new MockHttpServletRequest(),
                new AgentProposalExternalCompletionRequest(null, null, null));
        assertThat(roResult.httpStatus()).isEqualTo(409);
        assertThat(roResult.response().outcomeCode()).isEqualTo("READ_ONLY_CONSOLIDATION");
    }

    @Test
    void separatesStreamRequestHashByOrganizationClaimsAndStripsClientApiKeyInCorporateMode() throws InterruptedException {
        List<AiCallConfig> observedCallConfigs = new ArrayList<>();
        java.util.concurrent.CountDownLatch providerInvoked = new java.util.concurrent.CountDownLatch(1);
        AiToolCallingProvider provider = new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return "gemini";
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    AgentModelTurnRequest req, AgentModelStreamListener listener) {
                observedCallConfigs.add(req.callConfig());
                providerInvoked.countDown();
                return new AgentModelTurnResponse(
                        "gemini", "gemini-2.5-flash", "Resposta concluida.",
                        List.of(), AgentTokenUsage.ZERO, "STOP", "r1", Map.of());
            }
        };
        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                provider, List.of(), new OperationalAssistantBlockComposer(new ObjectMapper()));

        AiThreadService threadService = mock(AiThreadService.class);
        UUID fixedThreadId = UUID.randomUUID();
        when(threadService.resolveThread(any(), any(), any(), any(), any()))
                .thenReturn(AiThread.builder().threadId(fixedThreadId).build());

        AiTurnService turnService = mock(AiTurnService.class);
        AiTurnEventService turnEventService = mock(AiTurnEventService.class);
        List<String> recordedHashes = new ArrayList<>();
        java.util.concurrent.atomic.AtomicLong eventSeq = new java.util.concurrent.atomic.AtomicLong();
        when(turnEventService.appendEvent(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> org.praxisplatform.config.dto.AiTurnEventEnvelope.builder()
                        .seq(eventSeq.incrementAndGet())
                        .streamId(inv.getArgument(1))
                        .threadId(inv.getArgument(2))
                        .turnId(inv.getArgument(3))
                        .type(inv.getArgument(4))
                        .build());
        when(turnEventService.findStartMetadata(any(), any())).thenAnswer(inv -> {
            if (recordedHashes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new AiTurnEventService.StreamStartMetadata(
                    UUID.randomUUID(), fixedThreadId, UUID.randomUUID(),
                    Instant.now(), Instant.now().plusSeconds(300),
                    recordedHashes.get(0), "progress", null));
        });
        when(turnEventService.appendStartEventIfAbsent(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Map<?, ?> meta = inv.getArgument(4);
                    recordedHashes.add(String.valueOf(meta.get("requestHash")));
                    return new AiTurnEventService.StreamStartAppendResult(null, true);
                });

        AiStreamAccessTokenService tokenService = mock(AiStreamAccessTokenService.class);
        when(tokenService.resolveAuthMode()).thenReturn("header");

        AgentExecutionContextCustomizer headerOrgCustomizer = (ctx, req) -> {
            String org = req != null ? req.getHeader("X-Org-Id") : "1";
            return new AgentExecutionContext(
                    ctx.threadId(), ctx.turnId(), ctx.streamId(), ctx.tenantId(), ctx.userId(), ctx.environment(),
                    ctx.principalName(), ctx.authorities(), ctx.activeScreenResourcePath(), ctx.activeTopicKey(),
                    ctx.activeSelectionHints(), Map.of("organizationId", org));
        };

        OperationalAgentTurnStreamService streamService = new OperationalAgentTurnStreamService(
                loop, threadService, turnService, turnEventService, tokenService,
                null, new CompositeAgentThreadContextPropagator(List.of()),
                List.of(headerOrgCustomizer), new ObjectMapper());
        streamService.setCorporateMode(true);

        OperationalAgentTurnStreamRequest req = new OperationalAgentTurnStreamRequest(
                "Consultar quadro", fixedThreadId.toString(), "turn-org-test",
                "/api/workforce/employees", "employees", Map.of(), List.of(),
                "gemini", "gemini-2.5-flash", "rogue-client-api-key", null);
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "prod", true, Set.of());

        MockHttpServletRequest org1Req = new MockHttpServletRequest();
        org1Req.addHeader("X-Org-Id", "1");
        AgenticAuthoringTurnStreamStartResponse first = streamService.start(req, org1Req, "", principal).response();
        assertThat(first.getStreamId()).isNotNull();

        MockHttpServletRequest org2Req = new MockHttpServletRequest();
        org2Req.addHeader("X-Org-Id", "2");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streamService.start(req, org2Req, "", principal))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("operational-agent-idempotency-conflict");

        assertThat(providerInvoked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(observedCallConfigs).isNotEmpty();
        assertThat(observedCallConfigs.get(0).getApiKey()).isNull();
    }
}
