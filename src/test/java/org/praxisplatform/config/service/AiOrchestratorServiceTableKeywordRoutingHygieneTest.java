package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.ai.prompts.AiPromptTemplates;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOption;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceTableKeywordRoutingHygieneTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tableGeneratePatchFlowMustNotRouteThroughLegacyKeywordFallbacks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java"));
        String generatePatchBody = source.substring(
                source.indexOf("public AiOrchestratorResponse generatePatch"),
                source.indexOf("private AiActionPlan extractTableActionPlan"));

        assertThat(generatePatchBody)
                .doesNotContain("tryResolveTableDeterministicDirectFallback(")
                .doesNotContain("deriveFallbackTableManifestActionPlan(")
                .doesNotContain("deriveFallbackTableActions(")
                .doesNotContain("tryResolveFilteringPrompt(")
                .doesNotContain("enforceFormatIntentWhenFieldExists(")
                .doesNotContain("handleComputedCreationIntent(")
                .doesNotContain("tryResolveComputedFastPath(")
                .doesNotContain("matchActionsForClause(")
                .doesNotContain("splitPromptClauses(")
                .doesNotContain("local-text-fallback-table-actions-used");
        assertThat(generatePatchBody).contains("extractTableActionPlan(");
    }

    @Test
    void consultModeUsesGovernedTableFormatAnswerBeforeReturningLlmTechnicalText() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java"));
        String consultBody = source.substring(
                source.indexOf("if (\"consult\".equals(selectedResponseMode))"),
                source.indexOf("AiIntentClassification intent = classifyIntent", source.indexOf("if (\"consult\".equals(selectedResponseMode))")));

        assertThat(consultBody)
                .contains("answerTableFormatCapabilityQuestion(")
                .contains("response.setMessage(governedFormatAnswer)")
                .contains("table-format-capability-consultative-answer-used");
    }

    @Test
    void structuredGuidedFormatSelectionIsHandledBeforeConsultModeSelection() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java"));
        int guidedIndex = source.indexOf("format-option-selected-context-hint-manifest-backed");
        int consultModeIndex = source.indexOf("String selectedResponseMode = selectAuthoringResponseMode");

        assertThat(guidedIndex).isGreaterThan(0);
        assertThat(consultModeIndex).isGreaterThan(0);
        assertThat(guidedIndex).isLessThan(consultModeIndex);
    }

    @Test
    void qaPromptMustForbidHypotheticalEndpointInvention() {
        assertThat(AiPromptTemplates.PROMPT_QA)
                .contains("endpoint/resourcePath")
                .contains("não proponha caminhos hipotéticos")
                .contains("Não use exemplos de endpoint fictícios");
    }

    @Test
    void qaPromptMustAvoidRuntimeSelectionContractLeakage() {
        assertThat(AiPromptTemplates.PROMPT_QA)
                .contains("linhas selecionadas")
                .contains("registros selecionados")
                .contains("Não exponha termos técnicos internos")
                .contains("runtimeState.selection")
                .contains("selectedRecordsContext")
                .contains("sampleRows");
    }

    @Test
    void tableActionPlanPromptMustRequireSemanticSafetyGuardrails() {
        assertThat(AiPromptTemplates.PROMPT_TABLE_ACTION_PLAN)
                .contains("Não gere operação que reduza proteções de acessibilidade")
                .contains("preencha \"ambiguities\" em vez de escolher defaults")
                .contains("tableConversationMemory.lastComponentEditDecision")
                .contains("Não escolha operações globais como appearance.density.set");
        assertThat(AiPromptTemplates.PROMPT_INTENT_CLASSIFIER)
                .contains("tableConversationMemory.lastComponentEditDecision")
                .contains("Não reinterprete refinamentos visuais de uma coluna como ajuste global de tabela");
        assertThat(AiPromptTemplates.PROMPT_EXECUTION_ENRICHED)
                .contains("tableConversationMemory.lastComponentEditDecision")
                .contains("refine o lastTarget em vez de escolher uma operação global");
    }

    @Test
    void tableActionPlanSchemaUsesAuthoringManifestOperationsAsToolEnum() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    { "operationId": "column.format.set" },
                    { "operationId": "filter.advanced.configure" }
                  ]
                }
                """);

        AiJsonSchema schema = ReflectionTestUtils.invokeMethod(
                service,
                "buildTableActionPlanSchema",
                java.util.List.of(),
                manifest);

        assertThat(schema.targetClass())
                .as("action-plan calls already carry a canonical JSON Schema and must not ask providers to infer a Java bean schema")
                .isNull();
        JsonNode schemaJson = objectMapper.readTree(schema.jsonSchema());
        JsonNode operationEnum = schemaJson
                .path("properties")
                .path("actions")
                .path("items")
                .path("properties")
                .path("type")
                .path("enum");

        List<String> operationIds = new ArrayList<>();
        operationEnum.forEach(node -> operationIds.add(node.asText()));
        assertThat(operationIds).containsExactly("column.format.set", "filter.advanced.configure");
        assertThat(schemaJson
                .path("properties")
                .path("actions")
                .path("items")
                .path("properties")
                .path("params")
                .path("type")
                .asText()).isEqualTo("object");
    }

    @Test
    void tableOperationCatalogCarriesManifestExamplesForSemanticToolSelection() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    {
                      "operationId": "column.format.set",
                      "title": "Definir formato",
                      "scope": "column",
                      "target": { "kind": "column", "resolver": "column-by-field", "required": true },
                      "inputSchema": { "type": "object", "properties": { "format": { "type": "string" } } },
                      "affectedPaths": ["columns[].format"],
                      "validators": ["format-preset-supported"]
                    }
                  ],
                  "examples": [
                    {
                      "id": "format-cpf",
                      "request": "Formate a coluna CPF",
                      "operationId": "column.format.set",
                      "target": "cpf",
                      "params": { "format": "000.000.000-00" },
                      "isPositive": true
                    }
                  ]
                }
                """);

        JsonNode catalog = ReflectionTestUtils.invokeMethod(
                service,
                "buildManifestOperationCatalogNode",
                manifest);

        JsonNode firstOperation = catalog.path(0);
        assertThat(firstOperation.path("operationId").asText()).isEqualTo("column.format.set");
        assertThat(firstOperation.path("examples").path(0).path("request").asText())
                .isEqualTo("Formate a coluna CPF");
        assertThat(firstOperation.path("examples").path(0).path("params").path("format").asText())
                .isEqualTo("000.000.000-00");
    }

    @Test
    void tableFormatOptionsFromLlmIntentBecomeGuidedActionPayloads() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("format")
                .targetField("salario")
                .options(List.of("Moeda BRL", "Numero compacto"))
                .build();

        Boolean shouldOfferChoice = ReflectionTestUtils.invokeMethod(
                service,
                "shouldOfferFormatChoiceFromLlmIntent",
                true,
                intent,
                null);
        assertThat(shouldOfferChoice).isTrue();

        List<?> contextOptions = List.of(
                newContextOption("BRL|symbol|2", "Moeda BRL", "R$ 12.700,00"),
                newContextOption("compact", "Compacto", "12.7k"));
        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildFormatOptionPayloads",
                "salario",
                contextOptions);

        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).getLabel()).isEqualTo("Moeda BRL");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("targetField").asText())
                .isEqualTo("salario");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("selection").path("value").asText())
                .isEqualTo("BRL|symbol|2");
        assertThat(payloads.get(0).getContextHints().path("presentation").path("ctaLabel").asText())
                .isEqualTo("Aplicar formato");
    }

    @Test
    void booleanFormatPayloadsUseHumanPortugueseLabelsWithCanonicalValues() {
        AiOrchestratorService service = newService();
        List<?> contextOptions = List.of(
                newContextOption("active-inactive", "Boolean active/inactive", "Active / Inactive"),
                newContextOption("yes-no", "Boolean yes/no", "Yes / No"),
                newContextOption("true-false", "Boolean true/false", "true / false"));

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildFormatOptionPayloads",
                "ativo",
                contextOptions);

        assertThat(payloads).extracting(AiOption::getLabel)
                .containsExactly("Ativo/Inativo", "Sim/Não", "Verdadeiro/Falso");
        assertThat(payloads).extracting(AiOption::getValue)
                .containsExactly("active-inactive", "yes-no", "true-false");
        assertThat(payloads.get(0).getContextHints().path("presentation").path("description").asText())
                .contains("Ativo ou Inativo");
    }

    @Test
    void dateFormatPayloadsUseHumanPortugueseLabelsWithCanonicalValues() {
        AiOrchestratorService service = newService();
        List<?> contextOptions = List.of(
                newContextOption("shortDate", "Date short", "13/06/2022"),
                newContextOption("longDate", "Date long", "13 de junho de 2022"),
                newContextOption("fullDate", "Date full", "segunda-feira, 13 de junho de 2022"),
                newContextOption("MMM/yyyy", "Month/Year", "jun./2022"));

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildFormatOptionPayloads",
                "dataAdmissao",
                contextOptions);

        assertThat(payloads).extracting(AiOption::getLabel)
                .containsExactly("Data curta", "Data por extenso", "Data completa", "Mês e ano");
        assertThat(payloads).extracting(AiOption::getValue)
                .containsExactly("shortDate", "longDate", "fullDate", "MMM/yyyy");
        assertThat(payloads).extracting(AiOption::getExample)
                .containsExactly("13/06/2022", "13 de junho de 2022", "segunda-feira, 13 de junho de 2022", "jun./2022");
        assertThat(payloads.get(1).getContextHints().path("presentation").path("description").asText())
                .contains("por extenso")
                .contains("13 de junho de 2022")
                .doesNotContain("December");
    }

    @Test
    void consultativeDateFormatOptionsAreRankedByResolvedPromptRefinementOnlyAfterSemanticScope() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("format")
                .targetField("dataAdmissao")
                .build();
        List<?> contextOptions = List.of(
                newContextOption("shortDate", "Date short", "13/06/2022"),
                newContextOption("mediumDate", "Date medium", "13 de jun. de 2022"),
                newContextOption("longDate", "Date long", "13 de junho de 2022"),
                newContextOption("fullDate", "Date full", "segunda-feira, 13 de junho de 2022"),
                newContextOption("MMM/yyyy", "Month/Year", "jun./2022"),
                newContextOption("shortTime", "Time short", "09:30"),
                newContextOption("yyyy-MM-dd HH:mm", "Date time", "2022-06-13 09:30"));
        JsonNode dataProfile = objectMapper.createObjectNode()
                .set("columns", objectMapper.createObjectNode()
                        .set("dataAdmissao", objectMapper.createObjectNode()
                                .put("inferredType", "date")));

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildConsultativeFormatActionOptions",
                intent,
                contextOptions,
                dataProfile,
                "mostre a data de admissao por extenso");

        assertThat(payloads).extracting(AiOption::getValue)
                .startsWith("longDate", "fullDate");
        assertThat(payloads).extracting(AiOption::getValue)
                .doesNotContain("shortTime", "yyyy-MM-dd HH:mm");
        assertThat(payloads).extracting(AiOption::getLabel)
                .startsWith("Data por extenso", "Data completa");
    }

    @Test
    void consultativeDateFormatAnswerAvoidsCanonicalContractLeakage() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "dataAdmissao", "header": "Admissão", "type": "date", "format": "longDate" }
                  ]
                }
                """);
        List<?> contextOptions = List.of(
                newContextOption("dd/MM/yyyy", "Date dd/MM/yyyy", "13/06/2022"),
                newContextOption("fullDate", "Date full", "Friday, 01 December 2023"),
                newContextOption("MMM/yyyy", "Month/Year", "Dec/2023"),
                newContextOption("shortTime", "Time short", "09:30"),
                newContextOption("yyyy-MM-dd HH:mm", "Date time", "2022-06-13 09:30"));

        String answer = ReflectionTestUtils.invokeMethod(
                service,
                "answerTableFormatCapabilityQuestion",
                "como posso formatar a data de admissão?",
                currentState,
                contextOptions);

        assertThat(answer)
                .contains("**Data brasileira**")
                .contains("**Data completa**")
                .contains("**Mês e ano**")
                .contains("segunda-feira, 13 de junho de 2022")
                .doesNotContain("fullDate")
                .doesNotContain("MMM/yyyy")
                .doesNotContain("column.format.set")
                .doesNotContain("payload")
                .doesNotContain("December")
                .doesNotContain("Hora curta")
                .doesNotContain("Data e hora técnica");
    }

    @Test
    void formatReviewMessageUsesHumanLabelInsteadOfCanonicalValue() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan.Action action = AiActionPlan.Action.builder()
                .type("column.format.set")
                .target("ativo")
                .params(objectMapper.createObjectNode().put("format", "active-inactive"))
                .build();
        AiActionPlan actionPlan = AiActionPlan.builder()
                .actions(List.of(action))
                .build();

        String review = ReflectionTestUtils.invokeMethod(
                service,
                "buildActionPlanComponentEditExplanation",
                actionPlan,
                List.of(newColumnDescriptor("ativo", "Ativo")),
                "fallback");

        assertThat(review)
                .isEqualTo("Vou formatar a coluna Ativo como Ativo/Inativo.")
                .doesNotContain("active-inactive");
    }

    @Test
    void selectedFormatReviewMessageUsesHumanLabelInsteadOfCanonicalValue() throws Exception {
        AiOrchestratorService service = newService();
        Object selection = newSelectedFormatSelection("ativo", "yes-no");

        String review = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedFormatReviewMessage",
                selection,
                List.of(newColumnDescriptor("ativo", "Ativo")));

        assertThat(review)
                .isEqualTo("Vou formatar a coluna Ativo como Sim/Não.")
                .doesNotContain("yes-no");
    }

    @Test
    void consultativeTableAnswerGroundsMentionedBooleanColumnIntoActionButtons() throws Exception {
        AiOrchestratorService service = newService();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .userPrompt("quais opções você recomenda para mostrar a coluna ativo de forma mais amigável?")
                .build();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "ativo", "header": "Ativo", "type": "boolean" }
                  ]
                }
                """);
        JsonNode dataProfile = objectMapper.readTree("""
                {
                  "columns": {
                    "ativo": { "inferredType": "boolean" }
                  }
                }
                """);
        List<?> contextOptions = List.of(
                newContextOption("active-inactive", "Boolean active/inactive", "Active / Inactive"),
                newContextOption("yes-no", "Boolean yes/no", "Yes / No"),
                newContextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 12.700,00"));
        AiIntentClassification intent = AiIntentClassification.builder()
                .intent("ask_about_config")
                .category("format")
                .build();
        AiOrchestratorResponse response = AiOrchestratorResponse.builder()
                .type("info")
                .message("Você pode usar texto, chip ou badge.")
                .build();

        AiOrchestratorResponse enriched = ReflectionTestUtils.invokeMethod(
                service,
                "attachConsultativeTableActionOptions",
                response,
                request,
                currentState,
                contextOptions,
                intent,
                dataProfile);

        assertThat(enriched.getOptionPayloads()).isNotEmpty();
        assertThat(enriched.getOptionPayloads()).extracting(AiOption::getLabel)
                .contains("Ativo/Inativo", "Sim/Não");
        assertThat(enriched.getOptionPayloads()).extracting(AiOption::getValue)
                .contains("active-inactive", "yes-no")
                .doesNotContain("BRL|symbol|2");
        assertThat(intent.getTargetField()).isEqualTo("ativo");
    }

    @Test
    void singleChosenFormatOptionFromLlmIntentIsTreatedAsSelectedAction() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("format")
                .targetField("salario")
                .options(List.of("BRL|symbol|2"))
                .build();
        List<?> contextOptions = List.of(
                newContextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 12.700,00"),
                newContextOption("USD|symbol|2", "Currency USD symbol", "US$ 12,700.00"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedFormatFromLlmIntentOptions",
                intent,
                contextOptions);

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("salario");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("BRL|symbol|2");
        assertThat(ReflectionTestUtils.getField(selection, "mode")).isEqualTo("format");
    }

    @Test
    void tableRendererOptionsFromLlmIntentBecomeGuidedActionPayloads() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("renderer")
                .targetField("ativo")
                .options(List.of(
                        "Mostrar badge colorido (verde = ativo, cinza/vermelho = inativo)",
                        "Mostrar ícone (check / cruz) com label acessível"))
                .build();

        Boolean shouldOfferChoice = ReflectionTestUtils.invokeMethod(
                service,
                "shouldOfferRendererChoiceFromLlmIntent",
                true,
                intent,
                null);
        assertThat(shouldOfferChoice).isTrue();

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildRendererOptionPayloads",
                "ativo",
                intent.getOptions());

        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0).getLabel()).contains("badge colorido");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("targetField").asText())
                .isEqualTo("ativo");
        assertThat(payloads.get(0).getContextHints().path("optionSelected").path("selection").path("mode").asText())
                .isEqualTo("renderer");
        assertThat(payloads.get(0).getContextHints().path("presentation").path("ctaLabel").asText())
                .isEqualTo("Aplicar opção");
    }

    @Test
    void selectedRendererGuidedActionBecomesManifestActionPlan() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode hints = objectMapper.readTree("""
                {
                  "optionSelected": {
                    "targetField": "ativo",
                    "selection": {
                      "value": "Badge colorido (verde para ativo, vermelho/cinza para inativo)",
                      "mode": "renderer"
                    }
                  }
                }
                """);

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromHints",
                hints);
        assertThat(selection).isNotNull();

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("ativo").build(),
                objectMapper.readTree("{\"columns\":[{\"field\":\"ativo\",\"header\":\"Ativo\",\"type\":\"boolean\"}]}"),
                List.of(newColumnDescriptor("ativo", "Ativo")),
                List.of("field"));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getType()).isEqualTo("column.conditionalRenderer.add");
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("ativo");
        assertThat(plan.getActions().get(0).getParams().path("renderer").path("type").asText())
                .isEqualTo("badge");
    }

    @Test
    void selectedRendererGuidedBadgeWithTextUsesTableSupportedVariant() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode hints = objectMapper.readTree("""
                {
                  "optionSelected": {
                    "targetField": "ativo",
                    "selection": {
                      "value": "Badge com texto 'Sim' (verde) / 'Não' (vermelho)",
                      "mode": "renderer"
                    }
                  }
                }
                """);

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromHints",
                hints);

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("ativo").build(),
                objectMapper.readTree("{\"columns\":[{\"field\":\"ativo\",\"header\":\"Ativo\",\"type\":\"boolean\"}]}"),
                List.of(newColumnDescriptor("ativo", "Ativo")),
                List.of("field", "header"));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("Sim");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/badge/variant").asText())
                .isEqualTo("soft");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("Não");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/badge/variant").asText())
                .isEqualTo("soft");
    }

    @Test
    void selectedRendererGuidedShortBadgePreservesChosenLabelsOverPreviousMapping() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode hints = objectMapper.readTree("""
                {
                  "optionSelected": {
                    "targetField": "ativo",
                    "selection": {
                      "value": "Badge curto: 'S' / 'N' com cores suaves (verde / cinza)",
                      "mode": "renderer"
                    }
                  }
                }
                """);

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromHints",
                hints);

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("ativo").build(),
                objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "ativo",
                              "header": "Ativo",
                              "type": "boolean",
                              "valueMapping": { "true": "Sim", "false": "Não" }
                            }
                          ]
                        }
                        """),
                List.of(newColumnDescriptor("ativo", "Ativo")),
                List.of("field", "header"));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("S");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/badge/variant").asText())
                .isEqualTo("soft");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("N");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/badge/variant").asText())
                .isEqualTo("soft");
    }

    @Test
    void naturalContinuationForShortBooleanIndicatorOverridesPreviousLongMapping() throws Exception {
        AiOrchestratorService service = newService();

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildBooleanStateRendererActionPlan",
                "agora deixe esse indicador mais discreto e com texto curto",
                newColumnDescriptor("ativo", "Ativo"),
                objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "ativo",
                              "header": "Ativo",
                              "type": "boolean",
                              "valueMapping": { "true": "Ativo", "false": "Inativo" }
                            }
                          ]
                        }
                        """));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("S");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("N");
    }

    @Test
    void booleanChipVisualContinuationPreservesLabelsAndAppliesRequestedColors() throws Exception {
        AiOrchestratorService service = newService();

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildBooleanStateRendererActionPlan",
                "deixe o Sim verde suave e o Nao cinza discreto",
                newColumnDescriptor("ativo", "Ativo"),
                objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "ativo",
                              "header": "Ativo",
                              "type": "boolean",
                              "valueMapping": { "true": "Sim", "false": "Não" }
                            }
                          ]
                        }
                        """));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Sim");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("success");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Não");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("basic");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
    }

    @Test
    void naturalBooleanValueMappingContinuationPreservesActiveInactiveLabels() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(AiActionPlan.Action.builder()
                        .type("column.valueMapping.set")
                        .target("ativo")
                        .params(objectMapper.readTree("""
                                { "valueMapping": { "true": "S", "false": "N" } }
                                """))
                        .build()))
                .ambiguities(List.of())
                .build();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeTableBooleanLabelActionsFromPrompt",
                "prefiro ativo e inativo",
                plan,
                null);

        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getParams().at("/valueMapping/true").asText())
                .isEqualTo("Ativo");
        assertThat(plan.getActions().get(0).getParams().at("/valueMapping/false").asText())
                .isEqualTo("Inativo");
    }

    @Test
    void llmTablePlanForShortBooleanTextIsNormalizedBeforeMaterialization() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(
                        AiActionPlan.Action.builder()
                                .type("column.valueMapping.set")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        { "valueMapping": { "true": "true", "false": "false" } }
                                        """))
                                .build(),
                        AiActionPlan.Action.builder()
                                .type("column.renderer.set")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        { "type": "badge", "badge": { "textField": "ativo", "variant": "soft" } }
                                        """))
                                .build()))
                .ambiguities(List.of())
                .build();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeTableBooleanLabelActionsFromPrompt",
                "deixe a coluna ativo como badge com texto curto",
                plan,
                null);

        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getType()).isEqualTo("column.conditionalRenderer.add");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("S");
        assertThat(plan.getActions().get(1).getType()).isEqualTo("column.conditionalRenderer.add");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/badge/text").asText())
                .isEqualTo("N");
    }

    @Test
    void visualContinuationOverExistingBooleanChipRebuildsRendererPlanFromCurrentState() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(
                        AiActionPlan.Action.builder()
                                .type("column.conditionalRenderer.add")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        {
                                          "condition": { "==": [ { "var": "ativo" }, true ] },
                                          "renderer": { "type": "chip", "chip": { "text": "Sim", "color": "primary", "variant": "filled" } }
                                        }
                                        """))
                                .build(),
                        AiActionPlan.Action.builder()
                                .type("column.conditionalRenderer.add")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        {
                                          "condition": { "==": [ { "var": "ativo" }, false ] },
                                          "renderer": { "type": "chip", "chip": { "text": "Não", "color": "accent", "variant": "filled" } }
                                        }
                                        """))
                                .build()))
                .ambiguities(List.of())
                .build();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "ativo",
                      "header": "Ativo",
                      "type": "boolean",
                      "conditionalRenderers": [
                        {
                          "condition": { "==": [ { "var": "ativo" }, true ] },
                          "renderer": { "type": "chip", "chip": { "text": "Sim", "color": "primary", "variant": "filled" } }
                        },
                        {
                          "condition": { "==": [ { "var": "ativo" }, false ] },
                          "renderer": { "type": "chip", "chip": { "text": "Não", "color": "accent", "variant": "filled" } }
                        }
                      ]
                    }
                  ]
                }
                """);

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeTableBooleanLabelActionsFromPrompt",
                "deixe o Sim verde suave e o Nao cinza discreto",
                plan,
                currentState);

        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getType()).isEqualTo("column.conditionalRenderer.add");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Sim");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("success");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Não");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("basic");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
    }

    @Test
    void visualContinuationOverRepeatedBooleanRendererPlanDoesNotRequireCurrentState() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(
                        AiActionPlan.Action.builder()
                                .type("column.conditionalRenderer.add")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        {
                                          "condition": { "==": [ { "var": "ativo" }, true ] },
                                          "renderer": { "type": "chip", "chip": { "text": "Sim", "color": "primary", "variant": "filled" } }
                                        }
                                        """))
                                .build(),
                        AiActionPlan.Action.builder()
                                .type("column.conditionalRenderer.add")
                                .target("ativo")
                                .params(objectMapper.readTree("""
                                        {
                                          "condition": { "==": [ { "var": "ativo" }, false ] },
                                          "renderer": { "type": "chip", "chip": { "text": "Não", "color": "accent", "variant": "filled" } }
                                        }
                                        """))
                                .build()))
                .ambiguities(List.of())
                .build();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeTableBooleanLabelActionsFromPrompt",
                "deixe o Sim verde suave e o Nao cinza discreto",
                plan,
                null);

        assertThat(plan.getActions()).hasSize(2);
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Sim");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("success");
        assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
                .isEqualTo("Não");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/color").asText())
                .isEqualTo("basic");
        assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/variant").asText())
                .isEqualTo("soft");
    }

    @Test
    void consultativeRendererPromptRequestsGuidedChoicesEvenWithoutLlmOptions() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("renderer")
                .targetField("ativo")
                .options(List.of())
                .build();

        Boolean shouldOfferChoice = ReflectionTestUtils.invokeMethod(
                service,
                "shouldOfferRendererChoiceFromConsultativePrompt",
                true,
                intent,
                objectMapper.createObjectNode(),
                "como posso deixar a coluna ativo mais amigavel quais opcoes voce recomenda para eu escolher");

        assertThat(shouldOfferChoice).isTrue();

        @SuppressWarnings("unchecked")
        List<String> defaults = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "defaultRendererOptionsForField",
                "ativo");

        assertThat(defaults).hasSizeGreaterThan(1);
        assertThat(defaults.get(0)).contains("Badge");
    }

    @Test
    void tableContinuationWithRecoverableTargetProbesManifestPlannerBeforeClarification() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "ativo",
                      "header": "Ativo",
                      "type": "boolean",
                      "conditionalRenderers": [
                        { "when": { "var": "ativo" }, "renderer": { "type": "badge", "text": "Ativo" } }
                      ]
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .userPrompt("agora deixe esse indicador mais discreto e com texto curto")
                .build();

        Boolean shouldProbe = ReflectionTestUtils.invokeMethod(
                service,
                "shouldProbeTableActionsForMissingContext",
                true,
                List.of(newComponentAction("column.renderer.set")),
                List.of("indicator id or selector", "preferred short text"),
                request,
                currentState,
                List.of(newColumnDescriptor("ativo", "Ativo")));

        assertThat(shouldProbe).isTrue();
    }

    private AiOrchestratorService newService() {
        return new AiOrchestratorService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper,
                null,
                mock(AiThreadService.class),
                mock(AiMessageService.class));
    }

    private Object newColumnDescriptor(String field, String header) throws Exception {
        Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ColumnDescriptor");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(field, header);
    }

    private Object newComponentAction(String id) throws Exception {
        Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ComponentAction");
        Constructor<?> constructor = type.getDeclaredConstructor(
                String.class,
                List.class,
                JsonNode.class,
                String.class,
                String.class,
                JsonNode.class,
                List.class,
                List.class,
                String.class,
                Boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                id,
                List.of(),
                null,
                "COLUMN",
                "OBJECT",
                null,
                List.of(),
                List.of(),
                id,
                true);
    }

    private Object newContextOption(String value, String label, String example) {
        try {
            Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ContextOption");
            var constructor = type.getDeclaredConstructor(String.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(value, label, example);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object newSelectedFormatSelection(String targetField, String value) {
        try {
            Class<?> type = Class.forName("org.praxisplatform.config.service.AiOrchestratorService$SelectedFormatSelection");
            var constructor = type.getDeclaredConstructor(String.class, List.class, String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(targetField, List.of(targetField), value, "format");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
