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
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiChatMessage;
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
        int consultStart = source.indexOf("if (\"consult\".equals(selectedResponseMode))");
        int governedAnswerIndex = source.indexOf("answerTableFormatCapabilityQuestion(", consultStart);
        int warningIndex = source.indexOf("table-format-capability-consultative-answer-used", governedAnswerIndex);
        int responseIndex = source.indexOf("response.setMessage(governedFormatAnswer)", governedAnswerIndex);
        int fallbackIntentIndex = source.indexOf("AiIntentClassification intent = preclassifiedIntent != null", consultStart);

        assertThat(consultStart).isGreaterThan(0);
        assertThat(governedAnswerIndex).isGreaterThan(consultStart);
        assertThat(warningIndex).isGreaterThan(governedAnswerIndex);
        assertThat(responseIndex).isGreaterThan(governedAnswerIndex);
        assertThat(fallbackIntentIndex).isGreaterThan(responseIndex);
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
    void semanticRendererIntentPromptUsesResolvedTargetWhenColumnNamesOverlap() throws Exception {
        AiOrchestratorService service = newService();
        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromSemanticIntentPrompt",
                AiIntentClassification.builder()
                        .category("renderer")
                        .targetField("statusPriority")
                        .build(),
                "badge");

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "status", "header": "Status", "type": "string" },
                            { "field": "priority", "header": "Priority", "type": "string" },
                            { "field": "statusPriority", "header": "Status Priority", "type": "string" }
                          ]
                        }
                        """),
                List.of(
                        newColumnDescriptor("status", "Status"),
                        newColumnDescriptor("priority", "Priority"),
                        newColumnDescriptor("statusPriority", "Status Priority")),
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).isNotEmpty();
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("badge");
    }

    @Test
    void computedColumnClarificationAnswerContinuesCreationBeforeUnknownTargetValidation() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan misroutedPlan = AiActionPlan.builder()
                .actions(List.of(AiActionPlan.Action.builder()
                        .type("column.visibility.set")
                        .target("statusPriority")
                        .build()))
                .ambiguities(List.of())
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .userPrompt("statusPriority")
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Crie uma coluna calculada Status Priority combinando Status e Priority")
                                .build(),
                        AiChatMessage.builder()
                                .role("assistant")
                                .content("Preciso da coluna correta para aplicar o ajuste.")
                                .build(),
                        AiChatMessage.builder()
                                .role("user")
                                .content("statusPriority")
                                .build()))
                .build();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" }
                  ]
                }
                """);
        JsonNode manifest = objectMapper.readTree("""
                {
                  "operations": [
                    { "operationId": "column.computed.add" },
                    { "operationId": "column.visibility.set" }
                  ]
                }
                """);
        List<String> warnings = new ArrayList<>();
        List<Object> columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"));

        String selectedField = ReflectionTestUtils.invokeMethod(
                service,
                "selectedNewComputedFieldFromClarificationAnswer",
                request,
                currentState,
                columns);
        String previousPrompt = ReflectionTestUtils.invokeMethod(
                service,
                "latestComputedColumnCreationPromptFromConversation",
                request);
        @SuppressWarnings("unchecked")
        List<String> baseFields = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "computedBaseFieldsFromPrompt",
                previousPrompt,
                columns);

        assertThat(selectedField).isEqualTo("statusPriority");
        assertThat(previousPrompt).isEqualTo("Crie uma coluna calculada Status Priority combinando Status e Priority");
        assertThat(baseFields).containsExactly("status", "priority");

        AiActionPlan continued = ReflectionTestUtils.invokeMethod(
                service,
                "continueComputedColumnCreationFromClarificationAnswer",
                misroutedPlan,
                request,
                currentState,
                columns,
                manifest,
                warnings);

        assertThat(continued).isNotNull();
        assertThat(continued.getActions()).hasSize(1);
        AiActionPlan.Action action = continued.getActions().get(0);
        assertThat(action.getType()).isEqualTo("column.computed.add");
        assertThat(action.getTarget()).isEqualTo("statusPriority");
        assertThat(action.getParams().path("field").asText()).isEqualTo("statusPriority");
        assertThat(action.getParams().path("outputType").asText()).isEqualTo("string");
        assertThat(action.getParams().path("dependencies").toString())
                .isEqualTo("[\"status\",\"priority\"]");
        assertThat(action.getParams().path("expression").path("cat").toString())
                .contains("\"var\":\"status\"")
                .contains("\"var\":\"priority\"");
        assertThat(warnings)
                .contains("column.computed.add continuado a partir de clarificacao governada de campo novo.");
    }

    @Test
    void selectedTwoLineRendererUsesComputedDependenciesAsComposeItems() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode hints = objectMapper.readTree("""
                {
                  "optionSelected": {
                    "targetField": "statusPriority",
                    "selection": {
                      "value": "two_lines",
                      "mode": "renderer",
                      "field": "statusPriority"
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
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "status", "header": "Status", "type": "string" },
                            { "field": "priority", "header": "Priority", "type": "string" },
                            {
                              "field": "statusPriority",
                              "header": "Status Priority",
                              "type": "string",
                              "computed": {
                                "dependencies": ["status", "priority"]
                              }
                            }
                          ]
                        }
                        """),
                List.of(
                        newColumnDescriptor("status", "Status"),
                        newColumnDescriptor("priority", "Priority"),
                        newColumnDescriptor("statusPriority", "Status Priority")),
                List.of("field", "header"));

        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getType()).isEqualTo("column.renderer.set");
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
        assertThat(plan.getActions().get(0).getParams().at("/compose/layout/direction").asText())
                .isEqualTo("column");
        assertThat(plan.getActions().get(0).getParams().at("/compose/items/0/field").asText())
                .isEqualTo("status");
        assertThat(plan.getActions().get(0).getParams().at("/compose/items/1/field").asText())
                .isEqualTo("priority");
    }

    @Test
    void selectedTwoLineRendererBecomesManifestBackedComponentEditPlan() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(AiActionPlan.Action.builder()
                        .type("column.renderer.set")
                        .target("statusPriority")
                        .params(objectMapper.readTree("""
                                {
                                  "type": "compose",
                                  "compose": {
                                    "items": [
                                      { "type": "value", "field": "status" },
                                      { "type": "value", "field": "priority" }
                                    ],
                                    "layout": { "direction": "column", "gap": 2, "align": "start" }
                                  }
                                }
                                """))
                        .build()))
                .build();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "operations": [
                    {
                      "operationId": "column.renderer.set",
                      "target": { "kind": "renderer", "resolver": "renderer-in-column" },
                      "inputSchema": {
                        "type": "object",
                        "required": ["type"],
                        "properties": {
                          "type": { "type": "string" },
                          "compose": { "type": "object" }
                        }
                      }
                    }
                  ]
                }
                """);

        JsonNode componentEditPlan = ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromActionPlan",
                plan,
                manifest);

        assertThat(componentEditPlan).isNotNull();
        assertThat(componentEditPlan.at("/operations/0/operationId").asText())
                .isEqualTo("column.renderer.set");
        assertThat(componentEditPlan.at("/operations/0/target/field").asText())
                .isEqualTo("statusPriority");
        assertThat(componentEditPlan.at("/operations/0/input/type").asText())
                .isEqualTo("compose");
        assertThat(componentEditPlan.at("/operations/0/input/compose/items/0/field").asText())
                .isEqualTo("status");
    }

    @Test
    void selectedTwoLineRendererBuildsCanonicalPlanWhenManifestTemplateIsMissing() throws Exception {
        AiOrchestratorService service = newService();
        AiActionPlan plan = AiActionPlan.builder()
                .actions(List.of(AiActionPlan.Action.builder()
                        .type("column.renderer.set")
                        .target("statusPriority")
                        .params(objectMapper.readTree("""
                                {
                                  "type": "compose",
                                  "compose": {
                                    "items": [
                                      { "type": "value", "field": "status" },
                                      { "type": "value", "field": "priority" }
                                    ],
                                    "layout": { "direction": "column", "gap": 2, "align": "start" }
                                  }
                                }
                                """))
                        .build()))
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Duas linhas: linha 1 = Status, linha 2 = Prioridade")
                .build();
        List<String> warnings = new ArrayList<>();

        JsonNode componentEditPlan = ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromActionPlanOrCanonicalRendererSelection",
                plan,
                objectMapper.readTree("""
                        {
                          "componentId": "praxis-table",
                          "operations": []
                        }
                        """),
                request,
                warnings);

        assertThat(componentEditPlan).isNotNull();
        assertThat(componentEditPlan.at("/operations/0/operationId").asText())
                .isEqualTo("column.renderer.set");
        assertThat(componentEditPlan.at("/operations/0/target/field").asText())
                .isEqualTo("statusPriority");
        assertThat(componentEditPlan.at("/operations/0/input/type").asText())
                .isEqualTo("compose");
        assertThat(componentEditPlan.at("/operations/0/input/compose/items/1/field").asText())
                .isEqualTo("priority");
        assertThat(warnings).contains("renderer-option-selected-canonical-plan-built-without-manifest-template");
    }

    @Test
    void neutralRendererFallbackKeepsComposeRenderable() {
        AiOrchestratorService service = newService();

        JsonNode operation = ReflectionTestUtils.invokeMethod(
                service,
                "buildNeutralCategoricalColumnRendererOperation",
                "statusPriority",
                "compose");

        assertThat(operation).isNotNull();
        assertThat(operation.at("/operationId").asText()).isEqualTo("column.renderer.set");
        assertThat(operation.at("/target/field").asText()).isEqualTo("statusPriority");
        assertThat(operation.at("/input/type").asText()).isEqualTo("compose");
        assertThat(operation.at("/input/compose/items/0/type").asText()).isEqualTo("value");
        assertThat(operation.at("/input/compose/items/0/field").asText()).isEqualTo("statusPriority");
        assertThat(operation.at("/input/compose/layout/direction").asText()).isEqualTo("column");
    }

    @Test
    void iconRendererKeepsFieldValueAsAccessibleLabel() {
        AiOrchestratorService service = newService();

        JsonNode params = ReflectionTestUtils.invokeMethod(
                service,
                "buildIconColumnRenderer",
                "icone",
                "statusPriority",
                null);

        assertThat(params).isNotNull();
        assertThat(params.at("/type").asText()).isEqualTo("icon");
        assertThat(params.at("/icon/name").asText()).isEqualTo("check_circle");
        assertThat(params.at("/icon/textField").asText()).isEqualTo("statusPriority");
    }

    @Test
    void englishTwoLineGuidedOptionResolvesToCanonicalTwoLineRenderer() throws Exception {
        AiOrchestratorService service = newService();

        Object value = ReflectionTestUtils.invokeMethod(
                service,
                "rendererSelectionValueFromPrompt",
                "two-line (status on first line, prioridade on second)");

        assertThat(value).isEqualTo("two_lines");
    }

    @Test
    void canonicalTwoLineRendererValueResolvesToCanonicalTwoLineRenderer() throws Exception {
        AiOrchestratorService service = newService();

        Object value = ReflectionTestUtils.invokeMethod(
                service,
                "rendererSelectionValueFromPrompt",
                "Aplique a apresentacao visual two_lines na coluna statusPriority.");

        assertThat(value).isEqualTo("two_lines");
    }

    @Test
    void guidedHumanTwoLineLabelIsTreatedAsRendererSelection() throws Exception {
        AiOrchestratorService service = newService();

        Object selectionLike = ReflectionTestUtils.invokeMethod(
                service,
                "promptLooksLikeRendererOptionSelection",
                "Dot condicional + texto em duas linhas (ponto colorido representando prioridade e texto de status em duas linhas)");
        Object value = ReflectionTestUtils.invokeMethod(
                service,
                "rendererSelectionValueFromPrompt",
                "Dot condicional + texto em duas linhas (ponto colorido representando prioridade e texto de status em duas linhas)");

        assertThat(selectionLike).isEqualTo(true);
        assertThat(value).isEqualTo("two_lines");
    }

    @Test
    void tableAuthoringManifestAlwaysMaterializesCanonicalRendererSetOperation() throws Exception {
        AiOrchestratorService service = newService();

        JsonNode manifest = ReflectionTestUtils.invokeMethod(
                service,
                "augmentAuthoringManifestFromRuntimeContract",
                "praxis-table",
                objectMapper.readTree("""
                        {
                          "componentId": "praxis-table",
                          "operations": []
                        }
                        """),
                objectMapper.readTree("{}"));

        assertThat(manifest).isNotNull();
        assertThat(manifest.path("operations"))
                .anySatisfy(operation -> assertThat(operation.path("operationId").asText())
                        .isEqualTo("column.renderer.set"));
    }

    @Test
    void semanticTwoLineRendererSelectionWithUnderscoreUsesComposePlan() throws Exception {
        AiOrchestratorService service = newService();
        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromSemanticIntentPrompt",
                AiIntentClassification.builder()
                        .category("renderer")
                        .targetField("statusPriority")
                        .build(),
                "duas_linhas (Status na primeira linha; Priority na segunda linha)");

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "status", "header": "Status", "type": "string" },
                            { "field": "priority", "header": "Priority", "type": "string" },
                            {
                              "field": "statusPriority",
                              "header": "Status Priority",
                              "type": "string",
                              "computed": {
                                "dependencies": ["status", "priority"]
                              }
                            }
                          ]
                        }
                        """),
                List.of(
                        newColumnDescriptor("status", "Status"),
                        newColumnDescriptor("priority", "Priority"),
                        newColumnDescriptor("statusPriority", "Status Priority")),
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void selectedRendererPromptContextPrefersConversationTargetOverOverlappingLlmTarget() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("two_lines")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("quais opcoes de apresentacao para a coluna statuspriority")
                                .build()))
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("status").build(),
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("status").build(),
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void mixedIconTwoLineSelectionUsesTwoLineComposeAndConversationTarget() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("ícone + duas_linhas (ícone à esquerda, texto em duas linhas)")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("quais opcoes de apresentacao para a coluna statuspriority")
                                .build()))
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("status").build(),
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("status").build(),
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
        assertThat(plan.getActions().get(0).getParams().at("/compose/items/0/field").asText())
                .isEqualTo("status");
        assertThat(plan.getActions().get(0).getParams().at("/compose/items/1/field").asText())
                .isEqualTo("priority");
    }

    @Test
    void guidedTwoLineQuickReplyTextIsNotTreatedAsOptionDiscoveryPrompt() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Duas linhas (linha 1: Status, linha 2: Priority). Opção guiada. Aplicar opção.")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quais opcoes de apresentacao para a coluna statusPriority?")
                                .build()))
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void guidedLayoutTwoLineQuickReplyTextIsNotTreatedAsOptionDiscoveryPrompt() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Layout em duas linhas (Status na primeira linha, Prioridade na segunda). Aplica esta apresentação visual na coluna")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quais opcoes de apresentacao para a coluna statusPriority?")
                                .build()))
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void guidedTextTwoLineQuickReplyTextIsNotTreatedAsOptionDiscoveryPrompt() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Texto em duas linhas (Status / Prioridade). Aplica esta apresentação visual na coluna")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quais opcoes de apresentacao para a coluna statusPriority?")
                                .build()))
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void guidedTwoLineSelectionUsesUniqueComputedColumnWhenConversationTargetIsMissing() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "type": "string",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Two-line (linha 1: status, linha 2: prioridade ou detalhe). Aplica esta apresentação visual na coluna")
                .currentState(currentState)
                .build();
        var columns = List.of(
                newColumnDescriptor("status", "Status"),
                newColumnDescriptor("priority", "Priority"),
                newColumnDescriptor("statusPriority", "Status Priority"));

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                null,
                request,
                columns);
        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                null,
                currentState,
                columns,
                List.of("field", "header"));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
        assertThat(plan).isNotNull();
        assertThat(plan.getActions()).hasSize(1);
        assertThat(plan.getActions().get(0).getTarget()).isEqualTo("statusPriority");
        assertThat(plan.getActions().get(0).getParams().path("type").asText()).isEqualTo("compose");
    }

    @Test
    void guidedTwoLineSelectionRecoversConversationColumnWhenSnapshotIsLate() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("duas linhas")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quais opcoes de apresentacao visual estao disponiveis para a coluna Status Priority?")
                                .build(),
                        AiChatMessage.builder()
                                .role("assistant")
                                .content("Encontrei algumas formas de apresentar statusPriority. Escolha uma opção para aplicar.")
                                .build()))
                .build();

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                null,
                request,
                List.of(
                        newColumnDescriptor("status", "Status"),
                        newColumnDescriptor("priority", "Priority")));

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("two_lines");
    }

    @Test
    void presentationOptionsQuestionIsNotMistakenForTwoLineSelection() throws Exception {
        AiOrchestratorService service = newService();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Quais opcoes de apresentacao para statusPriority? Quero comparar badge, icone, alinhamento e duas linhas.")
                .build();

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                request,
                List.of(
                        newColumnDescriptor("status", "Status"),
                        newColumnDescriptor("priority", "Priority"),
                        newColumnDescriptor("statusPriority", "Status Priority")));

        assertThat(selection).isNull();
    }

    @Test
    void alignmentClarificationAnswerContinuesToCanonicalAlignmentRenderer() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        List<?> columns = List.of(
                newColumnDescriptor("statusPriority", "Status Priority"));
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("center")
                .currentState(currentState)
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quero aplicar a opcao de alinhamento na coluna Status Priority.")
                                .build(),
                        AiChatMessage.builder()
                                .role("assistant")
                                .content("Preciso confirmar mais detalhes sobre alignment option antes de continuar.")
                                .build()))
                .build();

        Object selection = ReflectionTestUtils.invokeMethod(
                service,
                "extractSelectedRendererFromPromptContext",
                null,
                request,
                columns);

        assertThat(selection).isNotNull();
        assertThat(ReflectionTestUtils.getField(selection, "targetField")).isEqualTo("statusPriority");
        assertThat(ReflectionTestUtils.getField(selection, "value")).isEqualTo("alignment:center");

        AiActionPlan plan = ReflectionTestUtils.invokeMethod(
                service,
                "buildSelectedRendererActionPlan",
                selection,
                null,
                currentState,
                columns,
                List.of());

        JsonNode componentEditPlan = ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromActionPlanOrCanonicalRendererSelection",
                plan,
                objectMapper.readTree("""
                        {
                          "componentId": "praxis-table",
                          "operations": []
                        }
                        """),
                request,
                new ArrayList<String>());

        assertThat(componentEditPlan).isNotNull();
        assertThat(componentEditPlan.at("/operations/0/operationId").asText())
                .isEqualTo("column.renderer.set");
        assertThat(componentEditPlan.at("/operations/0/target/field").asText())
                .isEqualTo("statusPriority");
        assertThat(componentEditPlan.at("/operations/0/input/type").asText())
                .isEqualTo("compose");
        assertThat(componentEditPlan.at("/operations/0/input/compose/layout/align").asText())
                .isEqualTo("center");
    }

    @Test
    void rendererPlanRepairReanchorsOverlappingTargetFromConversation() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [
                    {
                      "operationId": "column.conditionalRenderer.add",
                      "target": { "kind": "conditionalRenderer", "field": "status" },
                      "input": {
                        "renderer": { "type": "badge", "badge": { "variant": "soft" } }
                      }
                    }
                  ]
                }
                """);
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status", "header": "Status", "type": "string" },
                    { "field": "priority", "header": "Priority", "type": "string" },
                    { "field": "statusPriority", "header": "Status Priority", "type": "string" }
                  ]
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Badge (etiqueta colorida indicando combinação Status Priority)")
                .currentState(currentState)
                .build();

        JsonNode repaired = ReflectionTestUtils.invokeMethod(
                service,
                "repairTableRendererTargetFromConversation",
                plan,
                request,
                new ArrayList<String>());

        assertThat(repaired.at("/operations/0/operationId").asText()).isEqualTo("column.renderer.set");
        assertThat(repaired.at("/operations/0/target/field").asText()).isEqualTo("statusPriority");
        assertThat(repaired.at("/operations/0/input/type").asText()).isEqualTo("badge");
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
    void consultativeAlignmentRendererPromptOffersDirectionalGuidedChoices() {
        AiOrchestratorService service = newService();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("renderer")
                .targetField("statusPriority")
                .options(List.of())
                .build();
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Quero aplicar a opcao de alinhamento na coluna Status Priority.")
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "buildConsultativeRendererChoiceResponse",
                intent,
                request,
                objectMapper.createObjectNode(),
                new ArrayList<String>());

        assertThat(response).isNotNull();
        assertThat(response.getOptionPayloads()).hasSize(3);
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getValue)
                .containsExactly("alignment:left", "alignment:center", "alignment:right");
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getLabel)
                .containsExactly("Alinhar à esquerda", "Alinhar ao centro", "Alinhar à direita");
        assertThat(response.getOptionPayloads().get(1).getContextHints()
                .at("/optionSelected/selection/mode").asText())
                .isEqualTo("renderer");
    }

    @Test
    void alignmentAmbiguityOptionsBecomeRendererPayloadsWithConversationTarget() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        List<?> columns = List.of(newColumnDescriptor("statusPriority", "Status Priority"));
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Quero aplicar a opcao de alinhamento na coluna Status Priority")
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Quero aplicar a opcao de alinhamento na coluna Status Priority")
                                .build()))
                .currentState(currentState)
                .build();

        @SuppressWarnings("unchecked")
        List<AiOption> payloads = (List<AiOption>) ReflectionTestUtils.invokeMethod(
                service,
                "buildAlignmentRendererOptionPayloadsFromAmbiguity",
                List.of("left", "center", "right"),
                request,
                columns,
                AiIntentClassification.builder().category("renderer").targetField("center").build(),
                currentState);

        assertThat(payloads).hasSize(3);
        assertThat(payloads)
                .extracting(AiOption::getValue)
                .containsExactly("alignment:left", "alignment:center", "alignment:right");
        assertThat(payloads.get(1).getContextHints().at("/optionSelected/targetField").asText())
                .isEqualTo("statusPriority");
        assertThat(payloads.get(1).getContextHints().at("/optionSelected/selection/value").asText())
                .isEqualTo("alignment:center");
    }

    @Test
    void pendingAlignmentChoiceReturnsGuidedRendererPayloadsBeforePatchMaterialization() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        List<?> columns = List.of(newColumnDescriptor("statusPriority", "Status Priority"));
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Quero aplicar a opcao de alinhamento na coluna Status Priority.")
                .currentState(currentState)
                .build();
        AiIntentClassification intent = AiIntentClassification.builder()
                .category("renderer")
                .targetField("statusPriority")
                .needsClarification(true)
                .missingContext(List.of("alignment_choice"))
                .options(List.of("left", "center", "right"))
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "resolvePendingAlignmentChoiceClarification",
                intent,
                request,
                columns,
                currentState);

        assertThat(response).isNotNull();
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getLabel)
                .containsExactly("Alinhar à esquerda", "Alinhar ao centro", "Alinhar à direita");
        assertThat(response.getOptionPayloads().get(1).getContextHints()
                .at("/optionSelected/selection/value").asText())
                .isEqualTo("alignment:center");
    }

    @Test
    void emptyAlignmentActionReturnsGuidedRendererPayloadsInsteadOfInvalidBlankAlign() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ]
                }
                """);
        List<?> columns = List.of(newColumnDescriptor("statusPriority", "Status Priority"));
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Quero aplicar a opcao de alinhamento na coluna Status Priority.")
                .currentState(currentState)
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "resolveMissingAlignmentActionClarification",
                List.of(AiActionItem.builder()
                        .type("COLUMN.ALIGN.SET")
                        .field("statusPriority")
                        .value("")
                        .build()),
                request,
                columns,
                AiIntentClassification.builder().category("renderer").targetField("statusPriority").build(),
                currentState);

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("Escolha o alinhamento da coluna.");
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getValue)
                .containsExactly("alignment:left", "alignment:center", "alignment:right");
        assertThat(response.getOptionPayloads().get(1).getContextHints()
                .at("/optionSelected/targetField").asText())
                .isEqualTo("statusPriority");
    }

    @Test
    void neutralBadgeForComputedCategoricalValuesFromCurrentStateRequiresGovernedSemanticsChoice() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode currentState = objectMapper.readTree("""
                {
                  "columns": [
                    {
                      "field": "statusPriority",
                      "header": "Status Priority",
                      "computed": {
                        "dependencies": ["status", "priority"]
                      }
                    }
                  ],
                  "rows": [
                    { "statusPriority": "PLANEJADA - ALTA" },
                    { "statusPriority": "PAUSADA - MEDIA" },
                    { "statusPriority": "FALHOU - CRITICA" }
                  ]
                }
                """);
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "schemaVersion": "praxis-component-edit-plan.v1",
                    "componentId": "praxis-table",
                    "operations": [
                      {
                        "operationId": "column.renderer.set",
                        "target": { "kind": "renderer", "field": "statusPriority" },
                        "input": {
                          "type": "badge",
                          "badge": {
                            "textField": "statusPriority",
                            "variant": "soft"
                          }
                        }
                      }
                    ]
                  }
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Badge (cor por prioridade)")
                .currentState(currentState)
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                new ArrayList<String>(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("clarification");
        assertThat(response.getMessage())
                .contains("não encontrei uma decisão governada");
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getLabel)
                .containsExactly(
                        "Definir semântica visual governada",
                        "Aplicar chips neutros por enquanto");
    }

    @Test
    void selectedColorBadgeOptionDoesNotSilentlyMaterializeNeutralRendererWithoutGovernedSemantics() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "schemaVersion": "praxis-component-edit-plan.v1",
                    "componentId": "praxis-table",
                    "operations": [
                      {
                        "operationId": "column.renderer.set",
                        "target": { "kind": "renderer", "field": "statusPriority" },
                        "input": {
                          "type": "badge",
                          "badge": {
                            "textField": "statusPriority",
                            "variant": "soft"
                          }
                        }
                      }
                    ]
                  }
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Etiqueta / badge colorida por valor inteiro")
                .currentState(objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "statusPriority",
                              "header": "Status Priority",
                              "computed": {
                                "dependencies": ["status", "priority"]
                              }
                            }
                          ]
                        }
                        """))
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                new ArrayList<String>(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("clarification");
        assertThat(response.getWarnings())
                .contains("table-categorical-renderer-color-policy-blocked-before-neutral-materialization");
        assertThat(response.getOptionPayloads())
                .extracting(AiOption::getLabel)
                .containsExactly(
                        "Definir semântica visual governada",
                        "Aplicar chips neutros por enquanto");
    }

    @Test
    void selectedColorBadgeOptionFromConversationDoesNotSilentlyMaterializeNeutralRenderer() throws Exception {
        AiOrchestratorService service = newService();
        JsonNode result = objectMapper.readTree("""
                {
                  "componentEditPlan": {
                    "schemaVersion": "praxis-component-edit-plan.v1",
                    "componentId": "praxis-table",
                    "operations": [
                      {
                        "operationId": "column.renderer.set",
                        "target": { "kind": "renderer", "field": "statusPriority" },
                        "input": {
                          "type": "badge",
                          "badge": {
                            "textField": "statusPriority",
                            "variant": "soft"
                          }
                        }
                      }
                    ]
                  }
                }
                """);
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("apply_selected_option")
                .messages(List.of(
                        AiChatMessage.builder()
                                .role("user")
                                .content("Badge colorido por prioridade (cores para ALTA/CRITICA/MEDIA)")
                                .build()))
                .currentState(objectMapper.readTree("""
                        {
                          "columns": [
                            {
                              "field": "statusPriority",
                              "header": "Status Priority",
                              "computed": {
                                "dependencies": ["status", "priority"]
                              }
                            }
                          ]
                        }
                        """))
                .build();

        AiOrchestratorResponse response = ReflectionTestUtils.invokeMethod(
                service,
                "componentEditPlanResponse",
                result,
                request,
                new ArrayList<String>(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("clarification");
        assertThat(response.getWarnings())
                .contains("table-categorical-renderer-color-policy-blocked-before-neutral-materialization");
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
