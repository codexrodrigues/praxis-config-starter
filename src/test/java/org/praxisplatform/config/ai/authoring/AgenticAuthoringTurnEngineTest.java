package org.praxisplatform.config.ai.authoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.Completion;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringTurnEngineTest {

    @Mock
    private AgenticAuthoringIntentResolverService intentResolverService;
    @Mock
    private AgenticAuthoringPreviewService previewService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesCurrentLinearFlowThroughEventSink() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        AgenticAuthoringTurnStreamRequest request = request();
        AgenticAuthoringIntentResolutionResult intent = validIntent();
        AgenticAuthoringPreviewResult preview = new AgenticAuthoringPreviewResult(
                true,
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null,
                null,
                "Preview ready.");
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(intent);
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(preview);

        AgenticAuthoringTurnOutcome outcome = engine().execute(request, principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.types)
                .containsExactly(
                        "thought.step",
                        "thought.step",
                        "thought.step",
                        "thought.step",
                        "result");
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("component_authoring");
        verify(intentResolverService).resolve(any(), eq("tenant"), eq("user"), eq("local"));
        verify(previewService).preview(any(), eq("tenant"), eq("user"), eq("local"));
    }

    @Test
    void skipsPreviewWhenTerminalReachedAfterIntentResolution() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        sink.terminalReached = true;

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.NONE);
        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void emitsErrorOutcomeWithoutDependingOnStreamServiceInternals() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenThrow(new IllegalStateException("provider quota exhausted"));

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.EXPIRE);
        org.assertj.core.api.Assertions.assertThat(sink.types).contains("error");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("code").asText())
                            .isEqualTo("agentic-authoring-processing-failed");
                });
    }

    @Test
    void passesIntentAndPreviewRequestsWithOriginalContext() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Preview ready."));

        engine().execute(request(), principalContext, new CapturingSink());

        ArgumentCaptor<AgenticAuthoringIntentResolutionRequest> intentRequest =
                ArgumentCaptor.forClass(AgenticAuthoringIntentResolutionRequest.class);
        verify(intentResolverService).resolve(intentRequest.capture(), eq("tenant"), eq("user"), eq("local"));
        org.assertj.core.api.Assertions.assertThat(intentRequest.getValue().userPrompt()).isEqualTo("Crie um painel");
        org.assertj.core.api.Assertions.assertThat(intentRequest.getValue().currentRoute()).isEqualTo("/page-builder-ia");

        ArgumentCaptor<AgenticAuthoringPlanRequest> planRequest =
                ArgumentCaptor.forClass(AgenticAuthoringPlanRequest.class);
        verify(previewService).preview(planRequest.capture(), eq("tenant"), eq("user"), eq("local"));
        org.assertj.core.api.Assertions.assertThat(planRequest.getValue().userPrompt()).isEqualTo("Crie um painel");
        org.assertj.core.api.Assertions.assertThat(planRequest.getValue().intentResolution()).isNotNull();
    }

    @Test
    void emitsSafeRepairClassificationWhenPreviewFails() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        false,
                        List.of("fields must not be empty"),
                        List.of("compile-skipped-invalid-minimal-form-plan"),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Preview needs repair."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("preview.compile");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("valid").asBoolean())
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairClassification").asText())
                            .isEqualTo("retryable");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairAttempted").asBoolean())
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("minimalFormPlan"))
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("compiledFormPatch"))
                            .isFalse();
                });
    }

    @Test
    void retriesRecoverablePreviewFailureOnceWithSafeRepairContext() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(
                        new AgenticAuthoringPreviewResult(
                                false,
                                List.of("fields must not be empty"),
                                List.of("compile-skipped-invalid-minimal-form-plan"),
                                objectMapper.createObjectNode(),
                                objectMapper.createObjectNode(),
                                null,
                                null,
                                "Preview needs repair."),
                        new AgenticAuthoringPreviewResult(
                                true,
                                List.of(),
                                List.of(),
                                objectMapper.createObjectNode(),
                                objectMapper.createObjectNode(),
                                null,
                                null,
                                "Preview repaired."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        ArgumentCaptor<AgenticAuthoringPlanRequest> planRequest =
                ArgumentCaptor.forClass(AgenticAuthoringPlanRequest.class);
        verify(previewService, Mockito.times(2)).preview(planRequest.capture(), eq("tenant"), eq("user"), eq("local"));
        org.assertj.core.api.Assertions.assertThat(planRequest.getAllValues().get(0).contextHints())
                .isNull();
        org.assertj.core.api.Assertions.assertThat(planRequest.getAllValues().get(1)
                        .contextHints()
                        .path("repair")
                        .path("classification")
                        .asText())
                .isEqualTo("retryable");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("repair.attempt");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairClassification").asText())
                            .isEqualTo("retryable");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("minimalFormPlan"))
                            .isFalse();
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("preview.compile");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("valid").asBoolean())
                            .isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairAttempted").asBoolean())
                            .isTrue();
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean())
                            .isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .isEqualTo("Preview repaired.");
                });
    }

    @Test
    void doesNotRepairPreviewFailureThatRequiresUserClarification() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntent());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        false,
                        List.of("intent-resolution-selected-candidate-required"),
                        List.of("preview-skipped-invalid-intent-resolution"),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Ainda preciso que voce escolha a fonte de dados."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        verify(previewService).preview(any(), eq("tenant"), eq("user"), eq("local"));
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .noneSatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("repair.attempt");
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("preview.compile");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairClassification").asText())
                            .isEqualTo("user_clarification_required");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairAttempted").asBoolean())
                            .isFalse();
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean())
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .isEqualTo("Ainda preciso que voce escolha a fonte de dados.");
                });
    }

    @Test
    void routeRequiredSharedRuleHandoffSkipsPreviewAndReturnsExistingIntentPayload() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringIntentResolutionResult routeRequiredIntent = sharedRuleRouteIntent(false, "form");

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(routeRequiredIntent);

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Regra LGPD para CPF"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("mixed");
        verify(previewService, never()).preview(any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThat(sink.types)
                .containsExactly("thought.step", "thought.step", "result");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("preview").isObject()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("intentResolution").path("gate").path("status").asText())
                            .isEqualTo("route_required");
                    org.assertj.core.api.Assertions.assertThat(node.path("intentResolution").path("failureCodes").toString())
                            .contains("shared-rule-authoring-required");
                });
    }

    @Test
    void routeClassifierBlocksPreviewEvenIfSharedRuleIntentIsMarkedValid() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(sharedRuleRouteIntent(true, "api_catalog"));

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Regra LGPD para CPF"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("shared_rule_authoring");
        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void clarificationRequiredRouteSkipsPreviewExplicitly() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(clarificationRequiredIntent());

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("needs_clarification");
        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void invokesResourceDiscoveryToolThroughEngineAndResolvesIntentWithCandidates() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringTurnStreamRequest request = request("Crie dashboard de folha de pagamento");
        AgenticAuthoringIntentResolutionResult firstIntent = clarificationRequiredIntent();
        AgenticAuthoringIntentResolutionResult secondIntent = validIntentWithToolCandidate();
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(firstIntent, secondIntent);
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Preview ready."));

        AgenticAuthoringTurnOutcome outcome = engine(repository).execute(request, principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText()).isEqualTo("tool.start");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("tool").asText())
                            .isEqualTo("searchApiResources");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("maxCallsPerTurn").asInt())
                            .isEqualTo(1);
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText()).isEqualTo("tool.result");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("candidateCount").asInt())
                            .isEqualTo(1);
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("retrievalSource").asText())
                            .isEqualTo("lexical_fallback");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("payload")).isFalse();
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText()).isEqualTo("resource.discovery");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("retrievalSource").asText())
                            .isEqualTo("context_hint");
                });

        ArgumentCaptor<AgenticAuthoringIntentResolutionRequest> intentRequest =
                ArgumentCaptor.forClass(AgenticAuthoringIntentResolutionRequest.class);
        verify(intentResolverService, org.mockito.Mockito.times(2))
                .resolve(intentRequest.capture(), eq("tenant"), eq("user"), eq("local"));
        org.assertj.core.api.Assertions.assertThat(intentRequest.getAllValues().get(1)
                        .contextHints()
                        .path("resourceDiscovery")
                        .path("candidates")
                        .path(0)
                        .path("resourcePath")
                        .asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        verify(previewService).preview(any(), eq("tenant"), eq("user"), eq("local"));
    }

    private AgenticAuthoringTurnEngine engine() {
        return engine(null);
    }

    private AgenticAuthoringTurnEngine engine(ApiMetadataRepository repository) {
        return new AgenticAuthoringTurnEngine(
                intentResolverService,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(
                        repository != null ? new AgenticAuthoringApiMetadataCandidateCatalog(repository) : null,
                        objectMapper)));
    }

    private AgenticAuthoringTurnStreamRequest request() {
        return request("Crie um painel");
    }

    private AgenticAuthoringTurnStreamRequest request(String userPrompt) {
        return new AgenticAuthoringTurnStreamRequest(
                userPrompt,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                null,
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                null,
                null);
    }

    private AgenticAuthoringIntentResolutionResult validIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult sharedRuleRouteIntent(boolean valid, String artifactKind) {
        return new AgenticAuthoringIntentResolutionResult(
                valid,
                "create",
                artifactKind,
                "create_artifact",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.99,
                        "selected resource for shared rule grounding",
                        List.of("quick-reply-context")),
                List.of(),
                new AgenticAuthoringGateResult(
                        "candidate-eligibility@0.1.0",
                        "route_required",
                        List.of("shared-rule-authoring-required")),
                "Crie uma regra LGPD para CPF",
                "Esse pedido deve seguir pela trilha governada de regra compartilhada.",
                List.of(),
                List.of(),
                List.of("keyword-fallback-applied"),
                List.of("shared-rule-authoring-required"),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult clarificationRequiredIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                false,
                "create",
                "dashboard",
                "clarify_resource",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult(
                        "candidate-eligibility@0.1.0",
                        "clarification_required",
                        List.of("resource-candidate-required")),
                "Crie uma tela",
                "Ainda preciso escolher o recurso.",
                List.of(),
                List.of(),
                List.of(),
                List.of("resource-candidate-required"),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult validIntentWithToolCandidate() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "get",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento",
                "GET",
                0.95,
                "resource discovered by backend tool",
                List.of("api-metadata", "tool-search-api-resources"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private static final class CapturingSink implements AgenticAuthoringTurnEventSink {
        private final List<String> types = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private boolean terminalReached;

        @Override
        public AgenticAuthoringTurnEventAppendResult append(String type, Object payload) {
            types.add(type);
            payloads.add(payload);
            return new AgenticAuthoringTurnEventAppendResult(type, true);
        }

        @Override
        public boolean terminalReached() {
            return terminalReached;
        }
    }
}
