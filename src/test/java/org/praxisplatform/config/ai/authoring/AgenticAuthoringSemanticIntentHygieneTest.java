package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Gate arquitetural automatizado que garante a governanca semantica de intencao
 * (Praxis AI-Native / fail-closed) no pacote {@code org.praxisplatform.config.ai.authoring},
 * impedindo a reintroducao de classificadores lexicais de intencao do usuario,
 * resolvers de fallback por keywords ou mocks permissivos em testes.
 */
@Tag("unit")
class AgenticAuthoringSemanticIntentHygieneTest {

    private static final Path MAIN_AUTHORING_DIR = Path.of(
            "src", "main", "java", "org", "praxisplatform", "config", "ai", "authoring");
    private static final Path TEST_AUTHORING_DIR = Path.of(
            "src", "test", "java", "org", "praxisplatform", "config", "ai", "authoring");

    @Test
    void keywordFallbackResolverClassRemainsRemoved() {
        Path legacyResolver = MAIN_AUTHORING_DIR.resolve("AgenticAuthoringKeywordFallbackResolver.java");
        assertThat(Files.exists(legacyResolver))
                .as("AgenticAuthoringKeywordFallbackResolver must remain removed from the authoring package")
                .isFalse();
    }

    @Test
    void intentResolverServiceDoesNotReferenceLegacyKeywordFallbackResolver() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringIntentResolverService.java"));
        assertThat(source)
                .doesNotContain("AgenticAuthoringKeywordFallbackResolver")
                .doesNotContain("keywordFallbackResolver")
                .doesNotContain("isDecisionMemoryRefinementPrompt")
                .doesNotContain("isOpenBusinessSurfaceAuthoringPrompt")
                .doesNotContain("answeredResourceCandidateClarification")
                .doesNotContain("isExplicitLocalUiCompositionPrompt")
                .doesNotContain("isExplicitLocalPageCompositionPrompt")
                .doesNotContain("prompt.contains(\"agora em grafico\")")
                .doesNotContain("prompt.contains(\"com grafico de pizza\")");
    }

    @Test
    void candidateScoringSupportHasBlockingActiveSemanticDecisionIsFreeOfLexicalUserPromptInspection() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringIntentCandidateScoringSupport.java"));
        assertThat(source)
                .doesNotContain("isExplicitLocalUiCompositionPrompt")
                .doesNotContain("isExplicitLocalPageCompositionPrompt")
                .doesNotContain("userPrompt.contains(\"como criar \")")
                .doesNotContain("userPrompt.contains(\"como configurar \")");
    }

    @Test
    void semanticDecisionPolicyReliesOnStructuredContractsWithoutLexicalPromptOverrides() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringSemanticDecisionPolicy.java"));
        assertThat(source)
                .doesNotContain("legacyKeywordFallback")
                .doesNotContain("rawIntentContainsAnalyticalVisualizationLanguage")
                .doesNotContain("promptImpliesAnalyticalVisualizationNeed")
                .doesNotContain("promptExplicitlyRequestsTable")
                .doesNotContain("containsAny(");
    }

    @Test
    void consultativeAnswerServiceUsesStructuredClassificationWithoutLexicalQuestionLists() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringConsultativeAnswerService.java"));
        assertThat(source)
                .doesNotContain("asksAvailability")
                .doesNotContain("isDataAndCapabilitiesQuestion(")
                .doesNotContain("isAnalyticalAvailabilityQuestion(")
                .doesNotContain("isCurrentScreenExplanationQuestion(")
                .doesNotContain("isRecommendationQuestion(")
                .doesNotContain("isComparisonQuestion(")
                .doesNotContain("isCapabilityQuestion(")
                .doesNotContain("tem esses dados")
                .doesNotContain("quais apis existem");
    }

    @Test
    void apiCatalogGuidanceSupportUsesStructuredDimensionRoutingWithoutPromptKeywordClassifiers() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringApiCatalogGuidanceSupport.java"));
        List<String> forbiddenClassifiers = List.of(
                "isPlatformAuthoringCapabilityQuestion(",
                "isAuthoringCapabilityOverviewQuestion(",
                "isSupportedControlsQuestion(",
                "isSupportedFilterControlsQuestion(",
                "isFieldRuleAuthoringQuestion(",
                "isReactiveCalculationQuestion(",
                "isAnalyticalCapabilityQuestion(",
                "isOperationalCapabilityQuestion(",
                "isDomainAvailabilityQuestion(",
                "isBroadApiCatalogCapabilityOverviewQuestion(",
                "isSpecificApiCatalogDimensionQuestion(",
                "isOpenApiCatalogDataQuestion(");
        for (String forbidden : forbiddenClassifiers) {
            assertThat(source).doesNotContain(forbidden);
        }
    }

    @Test
    void previewMessageSynthesizerChecksStructuredWarningForLocalEditorialComposition() throws IOException {
        String source = readSource(MAIN_AUTHORING_DIR.resolve("AgenticAuthoringPreviewMessageSynthesizerService.java"));
        assertThat(source)
                .contains("explicit-local-ui-composition-resource-selection-bypassed")
                .doesNotContain("prompt.contains(\"conteudo local\")")
                .doesNotContain("prompt.contains(\"conteúdo local\")");
    }

    @Test
    void defaultTestLlmIntentResolverFailsClosedOnUnmappedPrompts() throws IOException {
        String testSource = readSource(TEST_AUTHORING_DIR.resolve("AgenticAuthoringIntentResolverServiceTest.java"));
        assertThat(testSource)
                .contains("llm-intent-resolution-unmapped-test-fixture")
                .contains("Unmapped test fixture prompt in defaultTestLlmIntentResolver:")
                .doesNotContain("new AgenticAuthoringKeywordFallbackResolver(");
    }

    @Test
    void llmIntentSchemaAndParserExposeStructuredLocalEditorialCompositionAndSemanticRefinement() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgenticAuthoringLlmIntentResolverService llmResolver = new AgenticAuthoringLlmIntentResolverService(
                mock(AiProviderManagementService.class),
                objectMapper);

        String schemaJson = ReflectionTestUtils.invokeMethod(llmResolver, "schema");
        JsonNode schema = objectMapper.readTree(schemaJson);
        assertThat(schema.path("properties").has("localEditorialComposition")).isTrue();
        assertThat(schema.path("properties").has("semanticRefinement")).isTrue();
        assertThat(schema.path("required"))
                .extracting(JsonNode::asText)
                .contains("localEditorialComposition", "semanticRefinement");

        JsonNode payload = objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "page",
                  "semanticIntentClass": "component_authoring",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": null,
                  "followUpKind": "refinement",
                  "assistantMessage": "Preparei o rascunho editorial.",
                  "requiresGovernedAuthoring": false,
                  "localEditorialComposition": true,
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "queryConstraints": {
                    "appliesToDataSelection": false,
                    "filters": []
                  },
                  "semanticRefinement": {
                    "schemaVersion": "praxis-agentic-authoring-semantic-refinement.v1",
                    "refinementKind": "visual_projection",
                    "preserve": ["resource", "source"],
                    "replace": {
                      "artifactKind": "dashboard",
                      "visualIntent": "charts",
                      "chartType": "pie",
                      "source": null
                    },
                    "add": {
                      "filters": ["status"],
                      "axes": ["competencia"]
                    },
                    "remove": ["table"],
                    "rationale": "Refinamento estruturado pelo contrato LLM.",
                    "confidence": 0.94
                  },
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """);

        @SuppressWarnings("unchecked")
        Optional<AgenticAuthoringLlmIntentResolution> parsed =
                ReflectionTestUtils.invokeMethod(llmResolver, "toResolution", payload);

        assertThat(parsed).isPresent();
        AgenticAuthoringLlmIntentResolution resolution = parsed.orElseThrow();
        assertThat(resolution.localEditorialComposition()).isTrue();
        assertThat(resolution.semanticRefinement()).isNotNull();
        assertThat(resolution.semanticRefinement().active()).isTrue();
        assertThat(resolution.semanticRefinement().refinementKind()).isEqualTo("visual_projection");
        assertThat(resolution.semanticRefinement().preserve()).containsExactly("resource", "source");
        assertThat(resolution.semanticRefinement().replacement("artifactKind")).isEqualTo("dashboard");
        assertThat(resolution.semanticRefinement().replacement("chartType")).isEqualTo("pie");
        assertThat(resolution.semanticRefinement().add())
                .containsEntry("filters", List.of("status"))
                .containsEntry("axes", List.of("competencia"));
        assertThat(resolution.semanticRefinement().remove()).containsExactly("table");
    }

    @Test
    void intentResolverHonorsStructuredLocalEditorialAndSemanticRefinementWithoutLexicalKeywords() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgenticAuthoringLlmIntentResolverService llmResolver = mock(AgenticAuthoringLlmIntentResolverService.class);
        AgenticAuthoringSemanticRefinement structuredRefinement = new AgenticAuthoringSemanticRefinement(
                AgenticAuthoringSemanticRefinement.SCHEMA_VERSION,
                "visual_projection",
                List.of("resource", "source", "filters"),
                Map.of("artifactKind", "dashboard", "visualIntent", "charts"),
                Map.of("axes", List.of("departamento")),
                List.of("table"),
                "Refinamento estruturado do LLM sem dependencia de palavras-chave.",
                0.95d);
        when(llmResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "page",
                        "create_artifact",
                        null,
                        null,
                        "none",
                        "Composicao editorial pronta.",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        false,
                        "component_authoring",
                        null,
                        List.of(),
                        true,
                        AgenticAuthoringSemanticRefinement.none())));

        AgenticAuthoringIntentResolverService resolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                llmResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        AgenticAuthoringCandidate priorResource = new AgenticAuthoringCandidate(
                "/api/human-resources/folhas-pagamento",
                "post",
                "/schemas/filtered/folhas",
                "/api/human-resources/folhas-pagamento/filter",
                "POST",
                0.95d,
                "governed payroll resource",
                List.of("api-metadata", "semantic-retrieval"));
        AgenticAuthoringSemanticDecision activeDecision = AgenticAuthoringSemanticDecision.from(
                "create",
                "table",
                "create_artifact",
                priorResource,
                List.of(priorResource),
                null,
                List.of(),
                null,
                null,
                null,
                "session-structured",
                "turn-1",
                "Prompt anterior",
                "Prompt anterior",
                "Decisao inicial");

        AgenticAuthoringIntentResolutionResult localEditorialResult = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Prepare a composicao solicitada para apresentacao executiva.",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-structured",
                        "turn-2",
                        List.of(),
                        null,
                        List.of(),
                        null,
                        activeDecision));

        assertThat(localEditorialResult.selectedCandidate()).isNull();
        assertThat(localEditorialResult.warnings())
                .contains(
                        "explicit-local-ui-composition-resource-selection-bypassed",
                        "explicit-local-page-composition-normalized");

        when(llmResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "create",
                        "dashboard",
                        "create_artifact",
                        null,
                        null,
                        "refinement",
                        "Refinamento analitico pronto.",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        false,
                        "component_authoring",
                        null,
                        List.of(),
                        false,
                        structuredRefinement)));

        AgenticAuthoringIntentResolutionResult refinementResult = resolver.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Aplique a projecao analitica preservando o escopo atual.",
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-structured",
                        "turn-3",
                        List.of(),
                        null,
                        List.of(),
                        null,
                        activeDecision));

        assertThat(refinementResult.selectedCandidate()).isNotNull();
        assertThat(refinementResult.selectedCandidate().resourcePath())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(refinementResult.warnings())
                .contains("semantic-decision-memory-refinement-applied");
        assertThat(refinementResult.semanticDecision().refinement()).isEqualTo(structuredRefinement);
    }

    private static String readSource(Path relativePath) throws IOException {
        Path resolved = Files.exists(relativePath)
                ? relativePath
                : Path.of("praxis-config-starter").resolve(relativePath);
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }
}
