package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainRuleExplanationEvidenceProjection;
import org.praxisplatform.config.service.DomainRuleExplanationProjectionService;

@Tag("unit")
class AgenticAuthoringDomainDecisionExplanationFlowTest {

    private static final UUID DEFINITION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000382");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void inspectionToolReconcilesExactSelectionUnderGovernedPrincipal() throws Exception {
        DomainRuleExplanationProjectionService projectionService =
                mock(DomainRuleExplanationProjectionService.class);
        DomainRuleExplanationEvidenceProjection projection = projection("full");
        when(projectionService.project(
                        eq(DEFINITION_ID),
                        eq("ERG-08382"),
                        eq(3),
                        any()))
                .thenReturn(projection);
        AgenticAuthoringToolRegistry registry = registry(projectionService);
        AiPrincipalContext principal = new AiPrincipalContext("acme", "reader", "dev", true);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.INSPECT_DOMAIN_DECISION,
                        "advisory_authoring",
                        objectMapper.readTree("""
                                {"schemaVersion":"praxis.ai.context-hints.domain-decision/v1",
                                 "definitionId":"00000000-0000-0000-0000-000000000382",
                                 "ruleKey":"ERG-08382","version":3,
                                 "source":"policy-studio-selection"}
                                """)),
                principal,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.payload()).isSameAs(projection);
        assertThat(result.safeDiagnostics())
                .containsEntry("exposureMode", "full")
                .containsEntry("exactVersion", true);
        verify(projectionService).project(
                eq(DEFINITION_ID), eq("ERG-08382"), eq(3), any());
    }

    @Test
    void inspectionToolRejectsNonCanonicalSelectionContractBeforeGovernedRead() throws Exception {
        DomainRuleExplanationProjectionService projectionService =
                mock(DomainRuleExplanationProjectionService.class);
        AgenticAuthoringToolRegistry registry = registry(projectionService);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.INSPECT_DOMAIN_DECISION,
                        "advisory_authoring",
                        objectMapper.readTree("""
                                {"schemaVersion":"local-policy-ref/v1",
                                 "definitionId":"00000000-0000-0000-0000-000000000382",
                                 "ruleKey":"ERG-08382","version":3,
                                 "source":"browser-local-state"}
                                """)),
                new AiPrincipalContext("acme", "reader", "dev", true),
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("decision-ref-contract-invalid");
        verify(projectionService, never()).project(any(), anyString(), any(), any());
    }

    @Test
    void consultativeExplanationUsesOnlyGovernedProjectionAndNeverMaterializes() throws Exception {
        AiProviderManagementService provider = mock(AiProviderManagementService.class);
        AgenticAuthoringToolRegistry registry = mock(AgenticAuthoringToolRegistry.class);
        when(registry.execute(any(), any(), eq("retrieveEvidence")))
                .thenReturn(AgenticAuthoringToolResult.success(
                        AgenticAuthoringToolRegistry.INSPECT_DOMAIN_DECISION,
                        projection("full"),
                        Map.of()));
        when(provider.generateText(
                        anyString(),
                        any(AiCallConfig.class),
                        eq("acme"),
                        eq("reader"),
                        eq("dev")))
                .thenReturn("A decisão compara os limites mínimo e máximo informados.");
        AgenticAuthoringConsultativeAnswerService service =
                new AgenticAuthoringConsultativeAnswerService(provider, objectMapper, null, registry);

        AgenticAuthoringConsultativeAnswer answer = service.answer(
                        request(), null, "acme", "reader", "dev")
                .orElseThrow();

        assertThat(answer.category()).isEqualTo("domain_decision");
        assertThat(answer.changeKind()).isEqualTo("explain_domain_decision");
        assertThat(answer.assistantMessage()).contains("compara os limites");
        assertThat(answer.evidenceBundle().path("domainDecision").path("decisionRef").path("ruleKey").asText())
                .isEqualTo("ERG-08382");
        assertThat(answer.warnings()).contains("domain-decision-explanation-grounded");
        verify(provider).generateText(
                anyString(), any(AiCallConfig.class), eq("acme"), eq("reader"), eq("dev"));
    }

    @Test
    void deniedAiUsageReturnsDeterministicAnswerWithoutCallingProvider() throws Exception {
        AiProviderManagementService provider = mock(AiProviderManagementService.class);
        AgenticAuthoringToolRegistry registry = mock(AgenticAuthoringToolRegistry.class);
        when(registry.execute(any(), any(), eq("retrieveEvidence")))
                .thenReturn(AgenticAuthoringToolResult.success(
                        AgenticAuthoringToolRegistry.INSPECT_DOMAIN_DECISION,
                        projection("denied"),
                        Map.of()));
        AgenticAuthoringConsultativeAnswerService service =
                new AgenticAuthoringConsultativeAnswerService(provider, objectMapper, null, registry);

        AgenticAuthoringConsultativeAnswer answer = service.answer(
                        request(), null, "acme", "reader", "dev")
                .orElseThrow();

        assertThat(answer.assistantMessage()).contains("não autoriza");
        assertThat(answer.evidenceBundle().path("domainDecision").path("conditionEvidence")
                        .path("conditionAst").isMissingNode())
                .isTrue();
        verify(provider, never()).generateText(
                anyString(), any(AiCallConfig.class), anyString(), anyString(), anyString());
    }

    private AgenticAuthoringToolRegistry registry(
            DomainRuleExplanationProjectionService projectionService) {
        return new AgenticAuthoringToolRegistry(
                mock(AgenticAuthoringResourceDiscoveryService.class),
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                null,
                null,
                null,
                "praxis-service",
                null,
                projectionService);
    }

    private AgenticAuthoringTurnStreamRequest request() throws Exception {
        return new AgenticAuthoringTurnStreamRequest(
                "Explique esta regra",
                "praxis-policy-studio",
                null,
                "/policy-studio",
                null,
                null,
                "openai",
                "gpt-test",
                null,
                "session",
                "turn",
                List.of(),
                null,
                List.of(),
                objectMapper.readTree("""
                        {
                          "selectedDomainDecisionRef": {
                            "schemaVersion": "praxis.ai.context-hints.domain-decision/v1",
                            "definitionId": "00000000-0000-0000-0000-000000000382",
                            "ruleKey": "ERG-08382",
                            "version": 3,
                            "source": "policy-studio-selection"
                          },
                          "resolvedIntent": {
                            "operationKind": "explain",
                            "artifactKind": "domain_decision",
                            "changeKind": "explain_domain_decision",
                            "routeClass": "advisory_authoring"
                          }
                        }
                        """),
                null,
                null);
    }

    private DomainRuleExplanationEvidenceProjection projection(String exposureMode) {
        return new DomainRuleExplanationEvidenceProjection(
                DomainRuleExplanationEvidenceProjection.SCHEMA_VERSION,
                new DomainRuleExplanationEvidenceProjection.DecisionRef(
                        DEFINITION_ID, "ERG-08382", 3, "definition-hash", "condition-hash"),
                new DomainRuleExplanationEvidenceProjection.ScopeAttestation(true, true),
                new DomainRuleExplanationEvidenceProjection.SemanticContext(
                        "JSON_LOGIC_DECISION", "approved", "workforce", "frequency", "ergon", "policy-owner"),
                new DomainRuleExplanationEvidenceProjection.ConditionEvidence(
                        exposureMode,
                        "full".equals(exposureMode)
                                ? objectMapper.createObjectNode().putArray("<=").add(1).add(2)
                                : null,
                        "denied".equals(exposureMode) ? List.of() : List.of("<=", "var"),
                        "full".equals(exposureMode)
                                ? List.of("quantidadeMinimaDias", "quantidadeMaximaDias")
                                : List.of(),
                        true,
                        List.of()),
                new DomainRuleExplanationEvidenceProjection.LifecycleEvidence("approved", List.of()),
                List.of(),
                List.of("domain-rule-definition:" + DEFINITION_ID + "@v3#definition-hash"),
                new DomainRuleExplanationEvidenceProjection.RedactionEvidence(
                        "domain-decision-explanation-redaction.v1",
                        exposureMode,
                        List.of("tenantId", "timeline.actor", "materializedPayload"),
                        false,
                        false,
                        false,
                        false,
                        false),
                new DomainRuleExplanationEvidenceProjection.VersionAttestation(3, 3, true));
    }
}
