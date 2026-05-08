package org.praxisplatform.config.ai.authoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.Completion;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringTurnEngineTest {

    @Mock
    private AgenticAuthoringIntentResolverService intentResolverService;
    @Mock
    private AgenticAuthoringPreviewService previewService;

    @TempDir
    private Path tempDir;

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
                        "thought.step",
                        "result");
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("component_authoring");
        verify(intentResolverService).resolve(any(), eq("tenant"), eq("user"), eq("local"));
        verify(previewService).preview(any(), eq("tenant"), eq("user"), eq("local"));
    }

    @Test
    void exposesDecisionDiagnosticsOnTerminalResult() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        com.fasterxml.jackson.databind.node.ObjectNode llmDiagnostics = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode telemetry = llmDiagnostics.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", true);
        telemetry.put("llmResolved", false);
        telemetry.put("fallbackPolicy", "fail-safe");
        telemetry.put("keywordFallbackApplied", true);
        telemetry.put("selectedCandidateUsesLexicalFallback", true);
        telemetry.put("selectedCandidateUsesDomainAnchor", false);
        telemetry.put("candidateSetContainsLexicalFallback", true);
        telemetry.put("candidateSetContainsDomainAnchor", false);
        AgenticAuthoringIntentResolutionResult intent = intentWithDiagnostics(
                new AgenticAuthoringCandidate(
                        "/api/acme/orders",
                        "post",
                        "/schemas/filtered?path=/api/acme/orders&operation=post&schemaType=request",
                        "/api/acme/orders",
                        "POST",
                        0.61,
                        "lexical fail-safe candidate",
                        List.of("api-metadata", "lexical-fallback")),
                llmDiagnostics);

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(intent);
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

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Crie um dashboard de pedidos"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.types.get(sink.types.size() - 1)).isEqualTo("result");
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean())
                .isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("schemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-decision-diagnostics.v1");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticDecisionSchemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-semantic-decision.v1");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticDecisionReviewRequired").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("retrievalSource").asText())
                .isEqualTo("lexical_fallback");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("llmResolutionAttempted").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("keywordFallbackApplied").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("selectedCandidateUsesLexicalFallback").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("selectedResourcePath").asText())
                .isEqualTo("/api/acme/orders");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("keyword-fallback-fail-safe");
        org.assertj.core.api.Assertions.assertThat(result.path("intentResolution").path("semanticDecision").path("selectedResource").path("resourcePath").asText())
                .isEqualTo("/api/acme/orders");
    }

    @Test
    void requiresReviewWhenSelectedCandidateUsesLexicalFallbackWithoutKeywordFallback() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        com.fasterxml.jackson.databind.node.ObjectNode llmDiagnostics = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode telemetry = llmDiagnostics.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", true);
        telemetry.put("llmResolved", true);
        telemetry.put("fallbackPolicy", "");
        telemetry.put("keywordFallbackApplied", false);
        telemetry.put("selectedCandidateUsesLexicalFallback", true);
        telemetry.put("selectedCandidateUsesDomainAnchor", false);
        telemetry.put("candidateSetContainsLexicalFallback", true);
        telemetry.put("candidateSetContainsDomainAnchor", false);
        AgenticAuthoringIntentResolutionResult intent = intentWithDiagnostics(
                new AgenticAuthoringCandidate(
                        "/api/acme/orders",
                        "post",
                        "/schemas/filtered?path=/api/acme/orders&operation=post&schemaType=request",
                        "/api/acme/orders",
                        "POST",
                        0.61,
                        "lexical candidate",
                        List.of("api-metadata", "lexical-fallback")),
                llmDiagnostics);

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(intent);
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

        engine().execute(
                request("Crie um dashboard de pedidos"),
                principalContext,
                sink);

        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean())
                .isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("keywordFallbackApplied").asBoolean())
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("selectedCandidateUsesLexicalFallback").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("weak-lexical-evidence");
    }

    @Test
    void allowsLexicalCandidateAfterPreviewRegroundsResourceWithFilteredSchema() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        com.fasterxml.jackson.databind.node.ObjectNode llmDiagnostics = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode telemetry = llmDiagnostics.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", true);
        telemetry.put("llmResolved", true);
        telemetry.put("fallbackPolicy", "");
        telemetry.put("keywordFallbackApplied", false);
        telemetry.put("selectedCandidateUsesLexicalFallback", true);
        telemetry.put("selectedCandidateUsesDomainAnchor", false);
        telemetry.put("candidateSetContainsLexicalFallback", true);
        telemetry.put("candidateSetContainsDomainAnchor", false);
        AgenticAuthoringIntentResolutionResult intent = intentWithDiagnostics(
                new AgenticAuthoringCandidate(
                        "/api/human-resources/folhas-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/folhas-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/folhas-pagamento/filter/cursor",
                        "POST",
                        0.74,
                        "lexical candidate regrounded by preview schema",
                        List.of("api-metadata", "lexical-fallback")),
                llmDiagnostics);

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(intent);
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        uiCompositionPlanWithResourceSchemaGrounding(),
                        "Preview ready."));

        engine().execute(
                request("Crie uma tabela operacional de folhas de pagamento"),
                principalContext,
                sink);

        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean())
                .isTrue();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("previewResourceSchemaVerified").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("selectedCandidateUsesLexicalFallback").asBoolean())
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticDecisionReviewGroundedByPreview").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean())
                .isFalse();
    }

    @Test
    void blocksAutomaticApplyWhenPreviewComesFromHardcodedReferenceProvider() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:quickstart-payroll-table"),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        "Preview ready."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Prefiro graficos"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("uiCompositionPlanUsesReferenceProvider").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("uiCompositionPlanUsesHardcodedAnchor").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("ui-composition-hardcoded-reference-provider");
    }

    @Test
    void exposesVerifiedSemanticAxesInDecisionDiagnostics() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        uiCompositionPlanWithSemanticAxis(true, "verified"),
                        "Preview ready."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Crie um dashboard por gravidade"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean()).isTrue();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisVerifiedCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisPendingCount").asInt()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxesSchemaVerified").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxes").path(0).path("field").asText())
                .isEqualTo("gravidade");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxes").path(0).path("schemaVerified").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean()).isFalse();
    }

    @Test
    void blocksAutomaticApplyWhenSemanticAxesAreUnverified() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of("semantic-axis-schema-verification-pending"),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        uiCompositionPlanWithSemanticAxis(false, "pending"),
                        "Preview ready."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Crie um dashboard por gravidade"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("uiCompositionPlanHasUnverifiedSemanticAxes").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisVerifiedCount").asInt()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxisPendingCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("semanticAxesSchemaVerified").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("ui-composition-semantic-axis-schema-verification-pending");
    }

    @Test
    void blocksAutomaticApplyWhenPreviewIsTechnicallyValidButContradictsDecision() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of("semantic-preview-chart-required"),
                        List.of("semantic-preview-materialization-mismatch"),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        tableOnlyUiCompositionPlan(),
                        "Preview ready, but the materialization does not satisfy the decision."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("Crie um dashboard com graficos"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("preview").path("valid").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("previewTechnicallyValid").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("decisionValid").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("semantic-preview-materialization-mismatch");
    }

    @Test
    void blocksAutomaticApplyWhenGovernedToolLoopFails() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
        AgenticAuthoringOrchestrator failingOrchestrator = new AgenticAuthoringOrchestrator(
                new AgenticAuthoringToolLoopExecutor(
                        registry,
                        context -> "proposeDecision".equals(context.phase())
                                ? Optional.of(new AgenticAuthoringToolCall(
                                AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                                context.routeClass(),
                                new AgenticAuthoringResourceCandidatesRequest("orders", null, "dashboard", 5)))
                                : Optional.empty(),
                        3));
        AgenticAuthoringTurnEngine engine = new AgenticAuthoringTurnEngine(
                intentResolverService,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                registry,
                null,
                null,
                failingOrchestrator);

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(previewService.preview(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPreviewResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        null,
                        uiCompositionPlanWithSemanticAxis(true, "verified"),
                        "Preview ready."));

        AgenticAuthoringTurnOutcome outcome = engine.execute(
                request("Crie um dashboard por gravidade"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        com.fasterxml.jackson.databind.JsonNode result = objectMapper.valueToTree(sink.payloads.get(sink.payloads.size() - 1));
        org.assertj.core.api.Assertions.assertThat(result.path("preview").path("valid").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.path("canApply").asBoolean()).isFalse();
        com.fasterxml.jackson.databind.JsonNode diagnostics = result.path("decisionDiagnostics");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("toolLoopCompleted").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("toolLoopTerminalReason").asText())
                .isEqualTo("tool-phase-not-allowed");
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("requiresReview").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostics.path("reviewReason").asText())
                .isEqualTo("agentic-tool-loop-tool-phase-not-allowed");
        org.assertj.core.api.Assertions.assertThat(result.path("toolLoopTrace").toString())
                .contains("tool-phase-not-allowed")
                .doesNotContain("apiKey");
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
    void injectsGovernedProjectKnowledgeIntoPreviewContextWithSafeDiagnostics() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService = Mockito.mock(
                AgenticAuthoringProjectKnowledgeService.class);
        AgenticAuthoringProjectKnowledgeProjection projection = new AgenticAuthoringProjectKnowledgeProjection(
                "knowledge-1",
                "human-resources.funcionarios.preference.identity-card",
                "project_preference",
                new AgenticAuthoringProjectKnowledgeProjection.Scope(
                        "tenant",
                        "local",
                        "human-resources",
                        "human-resources.funcionarios"),
                new AgenticAuthoringProjectKnowledgeProjection.Status("active", "approved"),
                "allow",
                "accepted authoring turn",
                "layout_preference",
                "Prefer compact identity cards.",
                List.of("domain-knowledge:concept:human-resources.funcionarios.preference.identity-card"));

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(validIntentWithSelectedCandidate());
        when(projectKnowledgeService.retrieve(any())).thenReturn(List.of(projection));
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

        AgenticAuthoringTurnOutcome outcome = engine(null, projectKnowledgeService)
                .execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        ArgumentCaptor<AgenticAuthoringProjectKnowledgeQuery> knowledgeQuery =
                ArgumentCaptor.forClass(AgenticAuthoringProjectKnowledgeQuery.class);
        verify(projectKnowledgeService).retrieve(knowledgeQuery.capture());
        org.assertj.core.api.Assertions.assertThat(knowledgeQuery.getValue().tenantId()).isEqualTo("tenant");
        org.assertj.core.api.Assertions.assertThat(knowledgeQuery.getValue().environment()).isEqualTo("local");
        org.assertj.core.api.Assertions.assertThat(knowledgeQuery.getValue().contextKey()).isEqualTo("human-resources");
        org.assertj.core.api.Assertions.assertThat(knowledgeQuery.getValue().resourceKey())
                .isEqualTo("human-resources.funcionarios");
        org.assertj.core.api.Assertions.assertThat(knowledgeQuery.getValue().kinds())
                .contains("project_preference", "governance_constraint");

        ArgumentCaptor<AgenticAuthoringPlanRequest> planRequest =
                ArgumentCaptor.forClass(AgenticAuthoringPlanRequest.class);
        verify(previewService).preview(planRequest.capture(), eq("tenant"), eq("user"), eq("local"));
        com.fasterxml.jackson.databind.JsonNode projectKnowledge = planRequest.getValue()
                .contextHints()
                .path("projectKnowledge");
        org.assertj.core.api.Assertions.assertThat(projectKnowledge.path("source").asText())
                .isEqualTo("domain_knowledge_concept");
        org.assertj.core.api.Assertions.assertThat(projectKnowledge.path("entries").path(0).path("summary").asText())
                .isEqualTo("Prefer compact identity cards.");
        org.assertj.core.api.Assertions.assertThat(projectKnowledge.toString()).doesNotContain("payload");

        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("projectKnowledge.retrieve");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("influenceCount").asInt())
                            .isEqualTo(1);
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("conceptKeys").toString())
                            .contains("human-resources.funcionarios.preference.identity-card");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("sourceSummaries").toString())
                            .contains("accepted authoring turn");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("influences").toString())
                            .contains("layout_preference");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("payload")).isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").has("summary")).isFalse();
                });
    }

    @Test
    void skipsGovernedProjectKnowledgeRetrievalWhenIntentHasNoSemanticScope() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService = Mockito.mock(
                AgenticAuthoringProjectKnowledgeService.class);

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

        AgenticAuthoringTurnOutcome outcome = engine(null, projectKnowledgeService)
                .execute(request("Crie uma pagina com abas e componentes"), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        verify(projectKnowledgeService, never()).retrieve(any());
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .noneSatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("projectKnowledge.retrieve");
                });
    }

    @Test
    void runsProjectKnowledgeThroughEnginePreviewPlannerAndCompiler() throws Exception {
        writeAuthoringArtifacts();
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringProjectKnowledgeService projectKnowledgeService = Mockito.mock(
                AgenticAuthoringProjectKnowledgeService.class);
        AiProviderManagementService providerManagementService = Mockito.mock(AiProviderManagementService.class);
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        properties.setArtifactsDir(tempDir);
        AgenticAuthoringPlanService realPlanService = new AgenticAuthoringPlanService(
                providerManagementService,
                properties,
                objectMapper);
        AgenticAuthoringPreviewService realPreviewService = new AgenticAuthoringPreviewService(
                realPlanService,
                new AgenticAuthoringPatchCompilerService(properties, objectMapper),
                objectMapper);
        AgenticAuthoringProjectKnowledgeProjection projection = new AgenticAuthoringProjectKnowledgeProjection(
                "knowledge-1",
                "human-resources.colaboradores.preference.identity-card",
                "project_preference",
                new AgenticAuthoringProjectKnowledgeProjection.Scope(
                        "tenant",
                        "local",
                        "human-resources",
                        "human-resources.colaboradores"),
                new AgenticAuthoringProjectKnowledgeProjection.Status("active", "approved"),
                "allow",
                "accepted authoring turn",
                "layout_preference",
                "Prefer compact identity cards.",
                List.of("domain-knowledge:concept:human-resources.colaboradores.preference.identity-card"));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(colaboradoresFormIntent());
        when(projectKnowledgeService.retrieve(any())).thenReturn(List.of(projection));
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(colaboradoresMinimalPlan());

        AgenticAuthoringTurnOutcome outcome = new AgenticAuthoringTurnEngine(
                intentResolverService,
                realPreviewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(null, objectMapper)),
                projectKnowledgeService)
                .execute(request("Crie um formulario de colaboradores"), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(promptCaptor.getValue())
                .contains("Governed project knowledge:")
                .contains("\"conceptKey\":\"human-resources.colaboradores.preference.identity-card\"")
                .contains("\"summary\":\"Prefer compact identity cards.\"")
                .doesNotContain("rawPayload");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("projectKnowledge.retrieve");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("influenceCount").asInt())
                            .isEqualTo(1);
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("preview").path("valid").asBoolean()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("preview")
                                    .path("compiledFormPatch")
                                    .path("sourceRefs")
                                    .toString())
                            .contains("projectKnowledge:knowledge-1");
                    com.fasterxml.jackson.databind.JsonNode audit = node.path("preview")
                            .path("diagnostics")
                            .path("projectKnowledgeAudit");
                    org.assertj.core.api.Assertions.assertThat(audit.path("schemaVersion").asText())
                            .isEqualTo("praxis-agentic-authoring-project-knowledge-audit.v1");
                    org.assertj.core.api.Assertions.assertThat(audit.path("citedCount").asInt()).isEqualTo(1);
                    org.assertj.core.api.Assertions.assertThat(audit.path("entries").path(0).path("cited").asBoolean())
                            .isTrue();
                    org.assertj.core.api.Assertions.assertThat(audit.toString())
                            .doesNotContain("Prefer compact identity cards");
                });
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
    void stopsAfterSingleRepairAttemptWhenPreviewRemainsInvalid() throws Exception {
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
                                false,
                                List.of("fields must not be empty"),
                                List.of("compile-skipped-invalid-minimal-form-plan"),
                                objectMapper.createObjectNode(),
                                objectMapper.createObjectNode(),
                                null,
                                null,
                                "Preview still needs review."));

        AgenticAuthoringTurnOutcome outcome = engine().execute(request(), principalContext, sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        verify(previewService, Mockito.times(2)).preview(any(), eq("tenant"), eq("user"), eq("local"));
        long repairAttempts = sink.payloads.stream()
                .map(payload -> objectMapper.valueToTree(payload).path("phase").asText())
                .filter("repair.attempt"::equals)
                .count();
        org.assertj.core.api.Assertions.assertThat(repairAttempts).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("phase").asText())
                            .isEqualTo("preview.compile");
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("valid").asBoolean())
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairAttempted").asBoolean())
                            .isTrue();
                    org.assertj.core.api.Assertions.assertThat(node.path("diagnostics").path("repairClassification").asText())
                            .isEqualTo("retryable");
                })
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean())
                            .isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .isEqualTo("Preview still needs review.");
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
    void advisoryExplorationRouteSkipsPreviewUntilUserConfirmsMaterialization() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();

        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(advisoryDashboardIntent());

        AgenticAuthoringTurnOutcome outcome = engine().execute(
                request("quero ver quem recebe mais e comparar por area"),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("advisory_authoring");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("assistantMessage").asText())
                            .contains("preparar um dashboard");
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("quickReplies").isArray()).isTrue();
                });
        verify(previewService, never()).preview(any(), any(), any(), any());
    }

    @Test
    void advisoryBusinessCatalogQuestionUsesSemanticCatalogAnswer() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null),
                new ApiMetadata(
                        "/api/operations/incidentes",
                        "GET",
                        "operations,incidentes",
                        "Incidentes operacionais",
                        "Incidentes por status",
                        "listIncidentes",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        when(intentResolverService.resolve(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(advisoryDashboardIntent());

        AgenticAuthoringTurnOutcome outcome = engine(repository).execute(
                request("Sou gestor de negocio e quero descobrir quais dados existem para graficos e indicadores antes de criar dashboard."),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    String message = node.path("assistantMessage").asText();
                    org.assertj.core.api.Assertions.assertThat(message)
                            .contains("Pessoas e folha")
                            .contains("Operacoes")
                            .contains("Indicadores:")
                            .doesNotContain("/api/");
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(node.path("quickReplies"))
                            .extracting(reply -> reply.path("id").asText())
                            .contains("resource-api-human-resources-vw-analytics-folha-pagamento")
                            .doesNotContain("resource-api-operations-incidentes");
                    org.assertj.core.api.Assertions.assertThat(node.path("quickReplies").get(0)
                                    .path("contextHints")
                                    .path("presentation")
                                    .path("bestFor")
                                    .asText())
                            .contains("dashboards executivos");
                    org.assertj.core.api.Assertions.assertThat(node.path("quickReplies").get(0)
                                    .path("contextHints")
                                    .path("presentation")
                                    .path("returns")
                                    .asText())
                            .contains("KPIs");
                    org.assertj.core.api.Assertions.assertThat(node.path("quickReplies").get(0)
                                    .path("contextHints")
                                    .path("presentation")
                                    .path("nextStep")
                                    .asText())
                            .contains("Clique");
                });
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

    @Test
    void materializesLocalTabbedCrudRefinementThroughRealResolverAndPreviewProvider() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        when(llmIntentResolver.resolve(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("tenant"),
                        eq("user"),
                        eq("local")))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "compose",
                        "table",
                        "add_column",
                        null,
                        null,
                        "new_instruction",
                        "Preview applied to the page.",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService realIntentResolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        AgenticAuthoringPreviewService realPreviewService = new AgenticAuthoringPreviewService(
                Mockito.mock(AgenticAuthoringPlanService.class),
                Mockito.mock(AgenticAuthoringPatchCompilerService.class),
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        AgenticAuthoringTurnEngine realEngine = new AgenticAuthoringTurnEngine(
                realIntentResolver,
                realPreviewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(null, objectMapper)));

        AgenticAuthoringTurnOutcome outcome = realEngine.execute(
                request("Agora refine a página existente mantendo as três abas. Na aba Registros, adicione uma coluna Categoria no CRUD e preserve as ações Criar, Editar e Excluir. Na aba Relacionamentos, deixe os cards claramente relacionados às solicitações pelo título e inclua um campo Status do comentário. Não use API real nem schema externo; continue como conteúdo local/editorial."),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(outcome.state().routeClass()).isEqualTo("component_authoring");
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isTrue();
                    com.fasterxml.jackson.databind.JsonNode intentResolution = node.path("intentResolution");
                    org.assertj.core.api.Assertions.assertThat(intentResolution.path("operationKind").asText())
                            .isEqualTo("modify");
                    org.assertj.core.api.Assertions.assertThat(intentResolution.path("artifactKind").asText())
                            .isEqualTo("page");
                    org.assertj.core.api.Assertions.assertThat(intentResolution.path("warnings").toString())
                            .contains("explicit-local-ui-composition-resource-selection-bypassed")
                            .contains("explicit-local-page-composition-normalized");
                    com.fasterxml.jackson.databind.JsonNode preview = node.path("preview");
                    org.assertj.core.api.Assertions.assertThat(preview.path("valid").asBoolean()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(preview.path("warnings").toString())
                            .contains("ui-composition-plan-provider:local-editorial-tabbed-workspace")
                            .contains("compiled-form-patch-materialized-by-page-builder");
                    org.assertj.core.api.Assertions.assertThat(preview.path("uiCompositionPlan").path("layoutPreset").asText())
                            .isEqualTo("local-editorial-tabbed-workspace");
                    org.assertj.core.api.Assertions.assertThat(preview.path("uiCompositionPlan").toString())
                            .contains("\"componentId\":\"praxis-tabs\"")
                            .contains("\"id\":\"praxis-crud\"")
                            .contains("\"header\":\"Categoria\"")
                            .contains("Status do comentário");
                    org.assertj.core.api.Assertions.assertThat(node.path("intentResolution")
                                    .path("selectedCandidate")
                                    .isMissingNode()
                            || node.path("intentResolution").path("selectedCandidate").isNull())
                            .isTrue();
                });
    }

    @Test
    void materializesExplicitTrackingSlaCardsThroughStreamingTurnEngine() throws Exception {
        AiPrincipalContext principalContext = new AiPrincipalContext("tenant", "user", "local", true);
        CapturingSink sink = new CapturingSink();
        AgenticAuthoringLlmIntentResolverService llmIntentResolver =
                Mockito.mock(AgenticAuthoringLlmIntentResolverService.class);
        when(llmIntentResolver.resolve(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("tenant"),
                        eq("user"),
                        eq("local")))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "page",
                        "compose_page",
                        null,
                        null,
                        "new_instruction",
                        "Vou montar uma página editorial/local com abas.",
                        List.of(),
                        List.of(),
                        List.of("llm-intent-resolution-used"))));
        AgenticAuthoringIntentResolverService realIntentResolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                null,
                llmIntentResolver,
                new AgenticAuthoringComponentCapabilitiesService());
        AgenticAuthoringPreviewService realPreviewService = new AgenticAuthoringPreviewService(
                Mockito.mock(AgenticAuthoringPlanService.class),
                Mockito.mock(AgenticAuthoringPatchCompilerService.class),
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        AgenticAuthoringTurnEngine realEngine = new AgenticAuthoringTurnEngine(
                realIntentResolver,
                realPreviewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(null, objectMapper)));

        AgenticAuthoringTurnOutcome outcome = realEngine.execute(
                request("Crie uma tela local/editorial com abas. Na aba Cadastro coloque um formulário com Título, Responsável, Prioridade e Prazo. Na aba Registros coloque um componente CRUD ou lista local com colunas Item, Status, SLA e Responsável e ações Criar, Editar e Excluir. Na aba Acompanhamento adicione cards locais de SLA com Abertos, Em risco e Resolvidos e um histórico local em cards. Não descubra fonte de dados, não conecte API real e não use schema externo."),
                principalContext,
                sink);

        org.assertj.core.api.Assertions.assertThat(outcome.completion()).isEqualTo(Completion.COMPLETE);
        org.assertj.core.api.Assertions.assertThat(sink.payloads)
                .anySatisfy(payload -> {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
                    org.assertj.core.api.Assertions.assertThat(node.path("canApply").asBoolean()).isTrue();
                    com.fasterxml.jackson.databind.JsonNode tabs = node.path("preview")
                            .path("uiCompositionPlan")
                            .path("widgets")
                            .path(0)
                            .path("inputs")
                            .path("config")
                            .path("tabs");
                    org.assertj.core.api.Assertions.assertThat(tabs)
                            .extracting(tab -> tab.path("textLabel").asText())
                            .containsExactly("Cadastro", "Registros", "Acompanhamento");
                    org.assertj.core.api.Assertions.assertThat(tabs.path(2).path("id").asText())
                            .isEqualTo("acompanhamento");
                    org.assertj.core.api.Assertions.assertThat(tabs.path(2)
                                    .path("widgets")
                                    .path(0)
                                    .path("inputs")
                                    .path("config")
                                    .path("dataSource")
                                    .path("data"))
                            .extracting(item -> item.path("title").asText())
                            .containsExactly("Abertos", "Em risco", "Resolvidos");
                });
    }

    private AgenticAuthoringTurnEngine engine() {
        return engine(null);
    }

    private AgenticAuthoringTurnEngine engine(ApiMetadataRepository repository) {
        return engine(repository, null);
    }

    private AgenticAuthoringTurnEngine engine(
            ApiMetadataRepository repository,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService) {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(
                repository != null ? new AgenticAuthoringApiMetadataCandidateCatalog(repository) : null,
                objectMapper));
        return new AgenticAuthoringTurnEngine(
                intentResolverService,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                registry,
                projectKnowledgeService,
                repository != null
                        ? new AgenticAuthoringApiCatalogConversationService(objectMapper, repository)
                        : null,
                new AgenticAuthoringOrchestrator(new AgenticAuthoringToolLoopExecutor(
                        registry,
                        new AgenticAuthoringDefaultToolLoopPlanner())));
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

    private com.fasterxml.jackson.databind.node.ObjectNode uiCompositionPlanWithSemanticAxis(
            boolean schemaVerified,
            String schemaProbeStatus) {
        com.fasterxml.jackson.databind.node.ObjectNode uiCompositionPlan = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode diagnostics = uiCompositionPlan.putObject("diagnostics");
        com.fasterxml.jackson.databind.node.ObjectNode axis = diagnostics.putArray("semanticAxes").addObject();
        axis.put("concept", "severity");
        axis.put("field", "gravidade");
        axis.put("label", "Gravidade");
        axis.put("schemaVerified", schemaVerified);
        axis.put("schemaProbeStatus", schemaProbeStatus);
        axis.put("provenance", "user-prompt-semantic-axis");
        return uiCompositionPlan;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode uiCompositionPlanWithResourceSchemaGrounding() {
        com.fasterxml.jackson.databind.node.ObjectNode uiCompositionPlan = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode diagnostics = uiCompositionPlan.putObject("diagnostics");
        diagnostics.putObject("resourceSchemaGrounding")
                .put("verified", true)
                .put("source", "schemas.filtered")
                .put("endpointUrl", "http://localhost/schemas/filtered")
                .put("fieldCount", 7);
        com.fasterxml.jackson.databind.node.ObjectNode widget = uiCompositionPlan.putArray("widgets").addObject();
        widget.put("key", "folhas-pagamento-table");
        widget.put("componentId", "praxis-table");
        widget.putObject("inputs").put("resourcePath", "/api/human-resources/folhas-pagamento");
        return uiCompositionPlan;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode tableOnlyUiCompositionPlan() {
        com.fasterxml.jackson.databind.node.ObjectNode uiCompositionPlan = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode widget = uiCompositionPlan.putArray("widgets").addObject();
        widget.put("id", "employee-table");
        com.fasterxml.jackson.databind.node.ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        definition.putObject("inputs")
                .put("resourcePath", "/api/human-resources/funcionarios");
        return uiCompositionPlan;
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

    private AgenticAuthoringIntentResolutionResult intentWithDiagnostics(
            AgenticAuthoringCandidate selectedCandidate,
            com.fasterxml.jackson.databind.JsonNode llmDiagnostics) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                selectedCandidate,
                selectedCandidate == null ? List.of() : List.of(selectedCandidate),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                null,
                "Preview ready.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                llmDiagnostics);
    }

    private AgenticAuthoringIntentResolutionResult validIntentWithSelectedCandidate() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
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
                        "selected employee resource",
                        List.of("semantic-retrieval")),
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

    private AgenticAuthoringIntentResolutionResult colaboradoresFormIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "form",
                "create_minimal_form",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/colaboradores",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/colaboradores&operation=post&schemaType=request",
                        "/api/human-resources/colaboradores",
                        "POST",
                        0.95,
                        "matched colaboradores",
                        List.of("semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode colaboradoresMinimalPlan() {
        com.fasterxml.jackson.databind.node.ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-ui-angular");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "intent-resolution:/api/human-resources/colaboradores");
        plan.put("fieldSelectionPlanRef", "/schemas/filtered?path=/api/human-resources/colaboradores&operation=post&schemaType=request");
        plan.put("submitActionRef", "POST /api/human-resources/colaboradores");
        com.fasterxml.jackson.databind.node.ArrayNode fields = plan.putArray("fields");
        fields.addObject()
                .put("name", "nome")
                .put("label", "Nome")
                .put("controlType", "text")
                .put("required", true);
        plan.putObject("clarificationNeed")
                .put("needed", false)
                .put("code", "none");
        plan.putArray("sourceRefs")
                .add("intent-resolution")
                .add("/schemas/filtered?path=/api/human-resources/colaboradores&operation=post&schemaType=request")
                .add("projectKnowledge:knowledge-1");
        return plan;
    }

    private void writeAuthoringArtifacts() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        com.fasterxml.jackson.databind.node.ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("profileId", "create-minimal-form");
        catalog.put("targetComponent", "praxis-dynamic-page-builder");
        catalog.put("catalogReleaseId", "catalog-release-test");
        com.fasterxml.jackson.databind.node.ObjectNode form = catalog.putArray("allowedWidgets").addObject();
        form.put("id", "praxis-dynamic-form");
        form.put("eligible", true);
        com.fasterxml.jackson.databind.node.ObjectNode evidence = catalog.putObject("evidence");
        evidence.putObject("schemaRefs")
                .put("request", "/schemas/request")
                .put("response", "/schemas/response");
        evidence.putObject("operationRef")
                .put("method", "post")
                .put("path", "/api/human-resources/colaboradores");
        Files.writeString(tempDir.resolve("page-create-catalog.v0.json"), objectMapper.writeValueAsString(catalog));
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

    private AgenticAuthoringIntentResolutionResult advisoryDashboardIntent() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "get",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento&operation=get&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento",
                "GET",
                0.95,
                "analytics resource selected from governed context",
                List.of("domain-catalog-context"));
        AgenticAuthoringQuickReply quickReply = new AgenticAuthoringQuickReply(
                "confirm-dashboard",
                "confirmation",
                "Gerar previa governada",
                "Confirmed: criar dashboard com analytics folha pagamento",
                "Cria uma pre-visualizacao governada antes de salvar ou materializar.",
                "dashboard",
                "primary",
                objectMapper.createObjectNode());
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "explore",
                "dashboard",
                "recommend_dashboard_visualization",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                "quero ver quem recebe mais e comparar por area",
                "Posso preparar um dashboard com ranking e comparacao por area.",
                List.of(quickReply),
                List.of(),
                List.of(),
                List.of(),
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
