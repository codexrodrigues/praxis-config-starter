package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOption;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceCpfMaskTest {

  private AiOrchestratorService service;

  @BeforeEach
  void setUp() {
    service =
        new AiOrchestratorService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            null,
            mock(AiThreadService.class),
            mock(AiMessageService.class));
  }

  private Object contextOption(String value, String label, String example) throws Exception {
    Constructor<?> ctor =
        Class.forName("org.praxisplatform.config.service.AiOrchestratorService$ContextOption")
            .getDeclaredConstructor(String.class, String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(value, label, example);
  }

  @Test
  void inferFormatFromPromptReturnsCpfMask() {
    String prompt = "Formate a coluna CPF (000.000.000-00 (padrão CPF))";
    String mask =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "inferFormatFromPrompt", prompt, List.of());

    assertThat(mask).isEqualTo("000.000.000-00");
  }

  @Test
  void inferFormatFromPromptUsesCpfStandardMaskForNaturalRequest() {
    String mask =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "inferFormatFromPrompt", "Formate a coluna CPF.", List.of());

    assertThat(mask).isEqualTo("000.000.000-00");
  }

  @Test
  void applyInferredMissingFormatValuesUsesCpfStandardMaskForHumanRequest() {
    List<String> warnings = new ArrayList<>();
    @SuppressWarnings("unchecked")
    List<AiActionItem> actions =
        (List<AiActionItem>)
            ReflectionTestUtils.invokeMethod(
                service,
                "applyInferredMissingFormatValues",
                List.of(AiActionItem.builder().type("SET_FORMAT").field("cpf").build()),
                "formate a coluna cpf",
                List.of(),
                warnings);

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getValue()).isEqualTo("000.000.000-00");
    assertThat(warnings).anyMatch(warning -> warning.contains("Formato inferido"));
  }

  @Test
  void inferFormatFromPromptUsesFullDateForNaturalCompleteDateRequest() throws Exception {
    List<?> options =
        List.of(
            contextOption("shortDate", "Date short", "13/06/2022"),
            contextOption("fullDate", "Date full", "13 de junho de 2022"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "formate a coluna admissao como data completa",
                options);

    assertThat(value).isEqualTo("fullDate");
  }

  @Test
  void inferFormatFromPromptUsesMonthYearForNaturalMonthRequest() throws Exception {
    List<?> options =
        List.of(
            contextOption("shortDate", "Date short", "13/06/2022"),
            contextOption("MMM/yyyy", "Month/Year", "jun./2022"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "formate a coluna admissao como mes e ano",
                options);

    assertThat(value).isEqualTo("MMM/yyyy");
  }

  @Test
  void inferFormatFromPromptUsesBrazilianShortDateForHumanContinuation() throws Exception {
    List<?> options =
        List.of(
            contextOption("MMM/yyyy", "Month/Year", "jun./2022"),
            contextOption("dd/MM/yyyy", "Date dd/MM/yyyy", "13/06/2022"),
            contextOption("fullDate", "Date full", "segunda-feira, 13 de junho de 2022"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "Agora volte essa mesma data para o formato brasileiro curto.",
                options);

    assertThat(value).isEqualTo("dd/MM/yyyy");
  }

  @Test
  void answerTableFormatCapabilityQuestionListsDateFormatsFromCapabilities() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Admissão", "type": "date", "format": "dd/MM/yyyy" },
                { "field": "nome", "header": "Nome", "type": "string" }
              ]
            }
            """);
    List<?> options =
        List.of(
            contextOption("shortDate", "Date short", "13/06/2022"),
            contextOption("fullDate", "Date full", "segunda-feira, 13 de junho de 2022"),
            contextOption("MMM/yyyy", "Month/Year", "jun./2022"),
            contextOption("dd/MM/yyyy", "Date dd/MM/yyyy", "13/06/2022"),
            contextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 1.234,56"));

    String answer =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "answerTableFormatCapabilityQuestion",
                "Como eu posso formatar uma data dentro da tabela dinamica? Quais formatos existem e qual voce recomenda para a coluna Admissao?",
                currentState,
                options);

    assertThat(answer)
        .contains("**Formatos de data disponíveis**")
        .contains("`fullDate`")
        .contains("`MMM/yyyy`")
        .contains("`dd/MM/yyyy`")
        .contains("a coluna `Admissão`")
        .contains("Formato atual da coluna: `dd/MM/yyyy`");
    assertThat(answer).doesNotContain("BRL|symbol|2");
  }

  @Test
  void augmentFormatOptionsFallsBackToPublishedTablePresetsWhenCapabilitiesAreMissing()
      throws Exception {
    @SuppressWarnings("unchecked")
    List<Object> options =
        (List<Object>)
            ReflectionTestUtils.invokeMethod(
                service,
                "augmentFormatOptionsForPrompt",
                List.of(),
                "Quais formatos de data voce recomenda para a coluna Admissao?",
                "dataAdmissao");

    assertThat(options)
        .extracting(option -> ReflectionTestUtils.getField(option, "value"))
        .contains("dd/MM/yyyy", "fullDate", "MMM/yyyy", "BRL|symbol|2");
  }

  @Test
  void answerTableFormatCapabilityQuestionAcceptsHumanOptionsRequest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Data de Admissão", "type": "date", "format": "dd/MM/yyyy" }
              ]
            }
            """);
    @SuppressWarnings("unchecked")
    List<Object> options =
        (List<Object>)
            ReflectionTestUtils.invokeMethod(
                service,
                "augmentFormatOptionsForPrompt",
                List.of(),
                "Quais formatos de data voce recomenda para a coluna Data de Admissao? Mostre as opcoes para eu escolher.",
                "dataAdmissao");

    String answer =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "answerTableFormatCapabilityQuestion",
                "Quais formatos de data voce recomenda para a coluna Data de Admissao? Mostre as opcoes para eu escolher.",
                currentState,
                options);

    assertThat(answer)
        .contains("**Formatos de data disponíveis**")
        .contains("`dd/MM/yyyy`")
        .contains("`fullDate`")
        .contains("a coluna `Data de Admissão`");
  }

  @Test
  void answerTableFormatCapabilityQuestionListsCpfMaskOptionsWhenUserAsksToChoose() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "type": "string" },
                { "field": "nome", "header": "Nome", "type": "string" }
              ]
            }
            """);
    List<?> options =
        List.of(
            contextOption("dd/MM/yyyy", "Date dd/MM/yyyy", "13/06/2022"),
            contextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 1.234,56"));

    String answer =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "answerTableFormatCapabilityQuestion",
                "Quais formatos voce recomenda para a coluna CPF? Mostre as opcoes para eu escolher.",
                currentState,
                options);

    assertThat(answer)
        .contains("Formatos de máscara disponíveis")
        .contains("000.000.000-00")
        .contains("00000000000")
        .contains("coluna `CPF`");
  }

  @Test
  void buildComponentEditPlanFromPatchConvertsTableColumnFormat() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode patch =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "format": "MMM/yyyy" }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-table",
              "operations": [
                {
                  "operationId": "column.format.set",
                  "target": { "kind": "column" },
                  "inputSchema": {
                    "type": "object",
                    "properties": { "format": { "type": "string" } }
                  }
                }
              ]
            }
            """);
    List<String> warnings = new ArrayList<>();

    JsonNode plan =
        (JsonNode)
            ReflectionTestUtils.invokeMethod(
                service,
                "buildComponentEditPlanFromPatch",
                "praxis-table",
                patch,
                authoringManifest,
                warnings);

    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.format.set");
    assertThat(plan.at("/operations/0/target/field").asText()).isEqualTo("dataAdmissao");
    assertThat(plan.at("/operations/0/input/format").asText()).isEqualTo("MMM/yyyy");
    assertThat(warnings)
        .contains("praxis-table patch livre convertido para componentEditPlan manifest-backed.");
  }

  @Test
  void looksLikeTableFormatCommandAcceptsContextualHumanContinuation() {
    Boolean result =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "looksLikeTableFormatCommand",
                "Agora deixe essa mesma data so mes e ano.");

    assertThat(result).isTrue();
  }

  @Test
  void buildTableDateFormatActionOptionsReturnsClickableRecommendations() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Admissão", "type": "date", "format": "dd/MM/yyyy" }
              ]
            }
            """);
    List<?> options =
        List.of(
            contextOption("fullDate", "Date full", "segunda-feira, 13 de junho de 2022"),
            contextOption("MMM/yyyy", "Month/Year", "jun./2022"),
            contextOption("dd/MM/yyyy", "Date dd/MM/yyyy", "13/06/2022"));

    @SuppressWarnings("unchecked")
    List<AiOption> actionOptions =
        (List<AiOption>)
            ReflectionTestUtils.invokeMethod(
                service,
                "buildTableDateFormatActionOptions",
                "Como eu posso formatar uma data dentro da tabela dinamica? Qual voce recomenda para a coluna Admissao?",
                currentState,
                options);

    assertThat(actionOptions).hasSize(3);
    assertThat(actionOptions)
        .extracting(AiOption::getLabel)
        .containsExactly("Usar dd/MM/yyyy", "Usar data por extenso", "Usar mês/ano");
    assertThat(actionOptions)
        .extracting(AiOption::getValue)
        .contains(
            "formate a coluna dataAdmissao como dd/MM/yyyy",
            "formate a coluna dataAdmissao como data por extenso",
            "formate a coluna dataAdmissao como mes e ano");
    assertThat(actionOptions.get(1).getContextHints().at("/presentation/ctaLabel").asText())
        .isEqualTo("Aplicar formato");
  }

  @Test
  void buildTableDateFormatActionOptionsFallsBackToCanonicalDateActionsWhenCatalogIsMissing()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "primeiraAcao", "header": "Primeira ação", "type": "date", "format": "yyyy-MM-dd HH:mm" },
                { "field": "ultimaAcao", "header": "Última ação", "type": "date", "format": "yyyy-MM-dd HH:mm" },
                { "field": "titulo", "header": "Título", "type": "string" }
              ]
            }
            """);

    @SuppressWarnings("unchecked")
    List<AiOption> actionOptions =
        (List<AiOption>)
            ReflectionTestUtils.invokeMethod(
                service,
                "buildTableDateFormatActionOptions",
                "Quais formatos você recomenda para as datas de ação? Mostre opções para eu escolher.",
                currentState,
                List.of());

    assertThat(actionOptions).hasSize(3);
    assertThat(actionOptions)
        .extracting(AiOption::getValue)
        .contains(
            "formate as colunas primeiraAcao e ultimaAcao como dd/MM/yyyy",
            "formate as colunas primeiraAcao e ultimaAcao como data por extenso",
            "formate as colunas primeiraAcao e ultimaAcao como mes e ano");
    assertThat(actionOptions.get(0).getContextHints().at("/presentation/description").asText())
        .contains("Formato brasileiro");
    assertThat(actionOptions.get(0).getContextHints().at("/targetField").asText())
        .isEqualTo("Primeira ação e Última ação");
    assertThat(actionOptions.get(0).getContextHints().at("/optionSelected/targetFields/0").asText())
        .isEqualTo("primeiraAcao");
    assertThat(actionOptions.get(0).getContextHints().at("/optionSelected/targetFields/1").asText())
        .isEqualTo("ultimaAcao");
  }

  @Test
  void attachConsultativeTableActionOptionsAddsDateButtonsToQaAnswer() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "primeiraAcao", "header": "Primeira ação", "type": "date", "format": "yyyy-MM-dd HH:mm" },
                { "field": "ultimaAcao", "header": "Última ação", "type": "date", "format": "yyyy-MM-dd HH:mm" },
                { "field": "titulo", "header": "Título", "type": "string" }
              ]
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .userPrompt("Quais formatos você recomenda para as datas de ação? Mostre opções para eu escolher.")
            .build();
    AiOrchestratorResponse response =
        AiOrchestratorResponse.builder()
            .type("info")
            .message("Posso sugerir formatos de data para Primeira ação e Última ação.")
            .build();

    AiOrchestratorResponse enriched =
        (AiOrchestratorResponse)
            ReflectionTestUtils.invokeMethod(
                service,
                "attachConsultativeTableActionOptions",
                response,
                request,
                currentState,
                List.of());

    assertThat(enriched.getOptionPayloads()).hasSize(3);
    assertThat(enriched.getOptionPayloads())
        .extracting(AiOption::getLabel)
        .containsExactly("Usar dd/MM/yyyy", "Usar data por extenso", "Usar mês/ano");
    assertThat(enriched.getOptionPayloads().get(0).getContextHints().at("/presentation/ctaLabel").asText())
        .isEqualTo("Aplicar formato");
    assertThat(enriched.getOptionPayloads().get(0).getContextHints().at("/optionSelected/selection/value").asText())
        .isEqualTo("dd/MM/yyyy");
  }

  @Test
  void attachConsultativeTableActionOptionsAddsConditionalRuleButtonsToQaAnswer()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "id", "header": "ID" },
                { "field": "descricao", "header": "Descrição" },
                { "field": "tipo", "header": "Tipo" },
                { "field": "valor", "header": "Valor", "type": "currency", "format": "BRL|symbol|2" }
              ]
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .userPrompt(
                "Quais formas você recomenda para destacar descontos e valores altos? Mostre opções para eu escolher.")
            .build();
    AiOrchestratorResponse response =
        AiOrchestratorResponse.builder()
            .type("info")
            .message("Posso sugerir formas de destacar descontos e valores altos.")
            .build();

    AiOrchestratorResponse enriched =
        (AiOrchestratorResponse)
            ReflectionTestUtils.invokeMethod(
                service,
                "attachConsultativeTableActionOptions",
                response,
                request,
                currentState,
                List.of());

    assertThat(enriched.getOptionPayloads()).hasSize(3);
    assertThat(enriched.getOptionPayloads())
        .extracting(AiOption::getLabel)
        .containsExactly("Destacar descontos", "Destacar valores altos", "Badge para descontos");
    assertThat(enriched.getOptionPayloads().get(0).getContextHints().at("/presentation/ctaLabel").asText())
        .isEqualTo("Preparar ajuste");
    assertThat(enriched.getOptionPayloads().get(0).getContextHints().at("/guidedAction/operationIntent").asText())
        .isEqualTo("row.styleRule.add");
    assertThat(enriched.getOptionPayloads().get(0).getContextHints().has("optionSelected"))
        .isFalse();
  }

  @Test
  void buildTableDateFormatActionOptionsReturnsGuidedMaskActionsForCpf() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "type": "string" }
              ]
            }
            """);

    @SuppressWarnings("unchecked")
    List<AiOption> actionOptions =
        (List<AiOption>)
            ReflectionTestUtils.invokeMethod(
                service,
                "buildTableDateFormatActionOptions",
                "Quais formatos voce recomenda para a coluna CPF? Mostre as opcoes para eu escolher.",
                currentState,
                List.of(contextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 1.234,56")));

    assertThat(actionOptions)
        .extracting(AiOption::getLabel)
        .contains("CPF (padrão)", "CPF (apenas dígitos)");
    assertThat(actionOptions.get(0).getContextHints().at("/presentation/kind").asText())
        .isEqualTo("guided-option");
    assertThat(actionOptions.get(0).getContextHints().at("/presentation/ctaLabel").asText())
        .isEqualTo("Aplicar máscara");
    assertThat(actionOptions.get(0).getContextHints().at("/optionSelected/targetField").asText())
        .isEqualTo("cpf");
  }

  @Test
  void isConsultativeTableGuidanceQuestionDetectsGuidanceRequestWithoutTreatingCommandsAsQuestions() {
    Boolean guidance =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "isConsultativeTableGuidanceQuestion",
                "Como eu posso formatar uma data dentro da tabela dinamica? Qual voce recomenda?");
    Boolean optionsGuidance =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "isConsultativeTableGuidanceQuestion",
                "Quais formatos de data voce recomenda para a coluna Data de Admissao? Mostre as opcoes para eu escolher.");
    Boolean command =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "isConsultativeTableGuidanceQuestion",
                "formate a coluna admissao como data por extenso");

    assertThat(guidance).isTrue();
    assertThat(optionsGuidance).isTrue();
    assertThat(command).isFalse();
  }

  @Test
  void inferFormatFromPromptUsesPortugueseBooleanYesNoCustomOption() throws Exception {
    List<?> options =
        List.of(
            contextOption("true-false", "Boolean true/false", "true"),
            contextOption("yes-no", "Boolean yes/no", "Yes"),
            contextOption("custom|Sim|Nao", "Boolean custom (Sim/Nao)", "Sim"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "formate a coluna ativo como sim ou nao",
                options);

    assertThat(value).isEqualTo("custom|Sim|Nao");
  }

  @Test
  void inferFormatFromPromptUsesUppercaseForPortuguesePluralRequest() throws Exception {
    List<?> options =
        List.of(
            contextOption("uppercase", "String uppercase", "FUNCIONARIO"),
            contextOption("lowercase", "String lowercase", "funcionario"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "deixe a coluna funcionario em maiusculas",
                options);

    assertThat(value).isEqualTo("uppercase");
  }

  @Test
  void inferFormatFromPromptUsesUsdForPortugueseDollarRequest() throws Exception {
    List<?> options =
        List.of(
            contextOption("BRL|symbol|2", "Currency BRL symbol", "R$ 1.234,56"),
            contextOption("USD|symbol|2", "Currency USD symbol", "$1,234.56"));

    String value =
        (String)
            ReflectionTestUtils.invokeMethod(
                service,
                "inferFormatFromPrompt",
                "formate a coluna salario como dolar",
                options);

    assertThat(value).isEqualTo("USD|symbol|2");
  }

  @Test
  void hasResolvedFormatActionDetectsSetFormatWithValue() {
    Boolean resolved =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "hasResolvedFormatAction",
                List.of(AiActionItem.builder().type("SET_FORMAT").field("ativo").value("custom|Sim|Nao").build()));

    assertThat(resolved).isTrue();
  }

  @Test
  void normalizeFormatValueTreatsNullStringAsMissing() {
    String normalized =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "normalizeFormatValue", "null", List.of());

    assertThat(normalized).isNull();
  }

  @Test
  void buildMaskOptionsForCpfContainsCpfMask() {
    @SuppressWarnings("unchecked")
    List<?> options =
        (List<?>)
            ReflectionTestUtils.invokeMethod(
                service, "buildMaskOptionsForField", "cpf");

    assertThat(options).isNotEmpty();
    String firstValue =
        (String) ReflectionTestUtils.getField(options.get(0), "value");
    assertThat(firstValue).isEqualTo("000.000.000-00");
  }

  @Test
  void applySelectedFormatOverrideFillsMissingFormat() throws Exception {
    Constructor<?> ctor =
        Class.forName(
                "org.praxisplatform.config.service.AiOrchestratorService$ColumnDescriptor")
            .getDeclaredConstructor(String.class, String.class);
    ctor.setAccessible(true);
    Object column = ctor.newInstance("cpf", "CPF");

    List<?> updated =
        (List<?>)
            ReflectionTestUtils.invokeMethod(
                service,
                "applySelectedFormatOverride",
                List.of(
                    org.praxisplatform.config.dto.AiActionItem.builder()
                        .type("SET_FORMAT")
                        .field("cpf")
                        .value(null)
                        .build()),
                ReflectionTestUtils.invokeMethod(
                    service,
                    "extractSelectedFormatFromHints",
                    new ObjectMapper()
                        .readTree(
                            """
                            {
                              "optionSelected": {
                                "targetField": "cpf",
                                "selection": { "value": "000.000.000-00", "mode": "mask" }
                              }
                            }
                            """)),
                null,
                List.of(column),
                List.of("field", "header"),
                null);

    Object action = updated.get(0);
    String value = (String) ReflectionTestUtils.getField(action, "value");
    assertThat(value).isEqualTo("000.000.000-00");
  }

  @Test
  void normalizePatchDoesNotSetTypeForMaskFormat() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw =
        """
        {
          "columns": [
            { "field": "cpf", "format": "000.000.000-00" }
          ]
        }
        """;
    Object normalized =
        ReflectionTestUtils.invokeMethod(service, "normalizePatch", "praxis-table", mapper.readTree(raw));
    String serialized = mapper.writeValueAsString(normalized);
    assertThat(serialized).contains("\"format\":\"000.000.000-00\"");
    assertThat(serialized).doesNotContain("\"type\":\"date\"");
  }

  @Test
  void normalizesLegacyTableCapabilityChangeKindToCanonicalManifestOperation() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-table",
              "operations": [
                {
                  "operationId": "column.format.set",
                  "target": {
                    "kind": "column",
                    "resolver": "column-by-field",
                    "required": true
                  },
                  "inputSchema": {
                    "type": "object",
                    "required": ["format"],
                    "properties": {
                      "format": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    JsonNode plan =
        mapper.readTree(
            """
            {
              "schemaVersion": "praxis-component-edit-plan.v1",
              "componentId": "praxis-table",
              "operations": [
                {
                  "operationId": "set_column_format",
                  "target": { "kind": "column", "field": "cpf" },
                  "input": { "format": "000.000.000-00" }
                }
              ]
            }
            """);
    List<String> warnings = new ArrayList<>();

    JsonNode normalized =
        ReflectionTestUtils.invokeMethod(
            service,
            "normalizeComponentEditPlanForAuthoringManifest",
            plan,
            manifest,
            warnings);

    assertThat(normalized.at("/operations/0/operationId").asText()).isEqualTo("column.format.set");
    assertThat(normalized.at("/operations/0/input/format").asText()).isEqualTo("000.000.000-00");
    assertThat(warnings)
        .contains("component-edit-plan-operation-id-normalized:set_column_format:column.format.set");
  }

  @Test
  void computedCreationIntentIgnoresCpfMaskFormattingPrompt() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("add_column_computed")
            .category("format")
            .targetField("cpf")
            .needsClarification(true)
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Formate o campo CPF usando a mascara brasileira 000.000.000-00.")
            .schemaFields(
                mapper.readTree(
                    """
                    [
                      { "name": "cpf", "type": "string" },
                      { "name": "dataAdmissao", "type": "string", "format": "date" }
                    ]
                    """))
            .build();
    JsonNode currentState =
        mapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "type": "string" },
                { "field": "dataAdmissao", "header": "Admissão", "type": "date" }
              ]
            }
            """);

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "handleComputedCreationIntent",
            intent,
            request,
            currentState,
            new ArrayList<String>(),
            List.of(),
            List.of(),
            mapper.createObjectNode());

    assertThat(response).isNull();
  }

  @Test
  void buildFormatClarificationPayloadUsesStructuredOptionsForCpf() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_column")
            .category("format")
            .targetField("cpf")
            .needsClarification(true)
            .options(List.of("000.000.000-00 (formato CPF padrão)"))
            .build();

    Object payload =
        ReflectionTestUtils.invokeMethod(
            service, "buildFormatClarificationPayload", intent, List.of());

    @SuppressWarnings("unchecked")
    List<String> options = (List<String>) ReflectionTestUtils.getField(payload, "options");
    @SuppressWarnings("unchecked")
    List<?> payloads = (List<?>) ReflectionTestUtils.getField(payload, "payloads");

    assertThat(options).anyMatch(option -> option.contains("000.000.000-00"));
    assertThat(payloads).isNotEmpty();
  }

  @Test
  void buildClarificationMessageUsesTabsLanguageForTabsComponent() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_column")
            .category("unknown")
            .needsClarification(true)
            .missingContext(List.of("column"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-tabs")
            .componentType("tabs")
            .userPrompt("renomeie a primeira aba para consulta")
            .build();

    String message =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "buildClarificationMessage", intent, request);

    assertThat(message).isEqualTo("Qual aba ou item das abas devo ajustar?");
  }

  @Test
  void buildClarificationMessageDoesNotLeakRawMissingContextKeys() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_component")
            .category("tabs")
            .scope("component")
            .needsClarification(true)
            .missingContext(
                List.of(
                    "fields_required",
                    "attachments_support",
                    "visibility_permissions",
                    "preferred_label"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-tabs")
            .componentType("tabs")
            .userPrompt("preciso de uma parte separada para documentos do empregado")
            .build();

    String message =
        (String)
            ReflectionTestUtils.invokeMethod(
                service, "buildClarificationMessage", intent, request);

    assertThat(message).isEqualTo("Qual nome devo usar para essa nova aba?");
    assertThat(message)
        .doesNotContain("fields_required")
        .doesNotContain("attachments_support")
        .doesNotContain("visibility_permissions")
        .doesNotContain("preferred_label");
  }

  @Test
  void normalizeIntentDoesNotRequireColumnTargetForNonTableComponents() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_column")
            .category("tabs")
            .scope("config")
            .needsClarification(false)
            .build();

    AiIntentClassification normalized =
        (AiIntentClassification)
            ReflectionTestUtils.invokeMethod(
                service,
                "normalizeIntent",
                intent,
                null,
                "renomeie a primeira aba para consulta",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("tabs"),
                List.of(),
                false,
                List.of());

    assertThat(normalized.getMissingContext()).isNullOrEmpty();
    assertThat(normalized.getNeedsClarification()).isFalse();
  }

  @Test
  void mockProviderBuildsGenericTabsLabelActionPlan() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        CATALOGO DE ACOES (JSON):
        [{"id":"tab.label.set","patchTemplate":{"tabs":[{"id":"{{target}}","textLabel":"{{value}}"}]}}]

        CANDIDATOS DE ALVO (se houver):
        [{"path":"tabs[]","items":[{"id":"search","textLabel":"Buscar e listar"}]}]

        PEDIDO DO USUARIO: "a primeira aba deve ficar clara para consulta e busca"
        """;

    JsonNode actionPlan = mockProvider.generateJson(prompt);

    assertThat(actionPlan.path("actions")).hasSize(1);
    assertThat(actionPlan.path("actions").get(0).path("type").asText()).isEqualTo("tab.label.set");
    assertThat(actionPlan.path("actions").get(0).path("target").asText()).isEqualTo("search");
    assertThat(actionPlan.path("actions").get(0).path("value").asText()).isEqualTo("Consulta e busca");
  }

  @Test
  void mockProviderBuildsGenericTabsAddActionPlanForNewDetailsFormTab() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        CATALOGO DE ACOES (JSON):
        [
          {"id":"tab.add"},
          {"id":"tab.label.set","patchTemplate":{"tabs":[{"id":"{{target}}","textLabel":"{{value}}"}]}}
        ]

        PEDIDO DO USUARIO: "agora quero uma aba nova para ver os detalhes da pessoa selecionada, com um formulario simples"
        """;

    JsonNode actionPlan = mockProvider.generateJson(prompt);

    assertThat(actionPlan.path("actions")).hasSize(1);
    JsonNode action = actionPlan.path("actions").get(0);
    assertThat(action.path("type").asText()).isEqualTo("tab.add");
    assertThat(action.path("params").path("id").asText()).isEqualTo("detalhes-formulario");
    assertThat(action.path("params").path("textLabel").asText()).isEqualTo("Detalhes da pessoa");
    assertThat(action.path("params").path("content")).hasSize(1);
  }

  @Test
  void mockProviderBuildsGenericTabsAddActionPlanForSeparatedBusinessArea() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        CATALOGO DE ACOES (JSON):
        [
          {"id":"tab.add"},
          {"id":"tab.label.set","patchTemplate":{"tabs":[{"id":"{{target}}","textLabel":"{{value}}"}]}}
        ]

        PEDIDO DO USUARIO: "preciso de uma parte separada para acompanhar documentos e observacoes importantes do empregado"
        """;

    JsonNode actionPlan = mockProvider.generateJson(prompt);

    assertThat(actionPlan.path("actions")).hasSize(1);
    JsonNode action = actionPlan.path("actions").get(0);
    assertThat(action.path("type").asText()).isEqualTo("tab.add");
    assertThat(action.path("params").path("id").asText()).isEqualTo("documentos-observacoes");
    assertThat(action.path("params").path("textLabel").asText()).isEqualTo("Documentos e observacoes");
  }

  @Test
  void mockProviderBuildsDynamicFormLocalFieldAddActionPlan() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        CATALOGO DE ACOES (JSON):
        [
          {"id":"field.local.add"},
          {"id":"field.label.set"},
          {"id":"field.required.set"}
        ]

        PEDIDO DO USUARIO: "preciso de um campo a mais na parte de contato para informar whatsapp ou outro telefone de recado"
        """;

    JsonNode actionPlan = mockProvider.generateJson(prompt);

    assertThat(actionPlan.path("actions")).hasSize(1);
    JsonNode action = actionPlan.path("actions").get(0);
    assertThat(action.path("type").asText()).isEqualTo("field.local.add");
    assertThat(action.path("params").path("name").asText()).isEqualTo("whatsapp");
    assertThat(action.path("params").path("label").asText()).contains("WhatsApp");
    assertThat(action.path("params").path("source").asText()).isEqualTo("local");
    assertThat(action.path("params").path("sectionLabel").asText()).isEqualTo("Contato");
  }

  @Test
  void mockProviderClassifiesDynamicFormFieldAuthoringAsComponentScope() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        SCHEMA DE RESPOSTA (JSON):
        {"intent":"string","category":"string","scope":"string"}

        PEDIDO DO USUARIO: "preciso de um campo a mais na parte de contato para informar whatsapp ou outro telefone de recado"
        """;

    JsonNode intent = mockProvider.generateJson(prompt);

    assertThat(intent.path("intent").asText()).isEqualTo("update_component");
    assertThat(intent.path("category").asText()).isEqualTo("fields");
    assertThat(intent.path("scope").asText()).isEqualTo("component");
    assertThat(intent.path("targetField").isNull()).isTrue();
  }

  @Test
  void mockProviderClassifiesHumanContactStoragePromptAsFormFieldAuthoring() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        SCHEMA DE RESPOSTA (JSON):
        {"intent":"string","category":"string","scope":"string"}

        PEDIDO DO USUARIO: "preciso guardar um telefone alternativo para emergencias ou recados"
        """;

    JsonNode intent = mockProvider.generateJson(prompt);

    assertThat(intent.path("intent").asText()).isEqualTo("update_component");
    assertThat(intent.path("category").asText()).isEqualTo("fields");
    assertThat(intent.path("scope").asText()).isEqualTo("component");
  }

  @Test
  void mockProviderBuildsLocalFieldAddForHumanContactStoragePrompt() {
    MockAiService mockProvider = new MockAiService(new ObjectMapper());
    String prompt =
        """
        CATALOGO DE ACOES (JSON):
        [
          {"id":"field.local.add"},
          {"id":"field.label.set"},
          {"id":"field.required.set"}
        ]

        PEDIDO DO USUARIO: "preciso guardar um telefone alternativo para emergencias ou recados"
        """;

    JsonNode actionPlan = mockProvider.generateJson(prompt);

    assertThat(actionPlan.path("actions")).hasSize(1);
    JsonNode action = actionPlan.path("actions").get(0);
    assertThat(action.path("type").asText()).isEqualTo("field.local.add");
    assertThat(action.path("params").path("name").asText()).isEqualTo("telefoneRecado");
    assertThat(action.path("params").path("sectionLabel").asText()).isEqualTo("Contato");
  }

  @Test
  void actionPlanClarificationRespectsOptionalTargetFromAuthoringManifest() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode componentContext =
        mapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "tab.add",
                  "patchTemplate": { "tabs": [{ "id": "{{target}}", "textLabel": "{{params.textLabel}}" }] }
                }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    Object actionCatalog = ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("tab.add")
                        .params(
                            mapper.readTree(
                                """
                                { "id": "detalhes-formulario", "textLabel": "Detalhes da pessoa" }
                                """))
                        .build()))
            .build();

    Object clarification =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveActionPlanClarification",
            actionPlan,
            actionCatalog,
            List.of("search"),
            authoringManifest);

    assertThat(clarification).isNull();
  }

  @Test
  void manifestBackedComponentCanDeferClassifierClarificationToActionPlan() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode componentContext =
        mapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "tab.add",
                  "patchTemplate": { "tabs": [{ "id": "{{params.id}}", "textLabel": "{{params.textLabel}}" }] }
                }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    Object actionCatalog = ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_component")
            .category("tabs")
            .scope("component")
            .needsClarification(true)
            .missingContext(
                List.of(
                    "fields_required",
                    "attachments_support",
                    "visibility_permissions",
                    "preferred_label"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-tabs")
            .componentType("tabs")
            .userPrompt("preciso de uma parte separada para documentos do empregado")
            .build();

    Boolean shouldDefer =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "shouldDeferComponentClarificationToActionPlan",
                intent,
                request,
                actionCatalog,
                authoringManifest);

    assertThat(shouldDefer).isTrue();
  }

  @Test
  void manifestBackedComponentDoesNotDeferResourceClarification() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode componentContext =
        mapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "widget.add",
                  "patchTemplate": { "widgets": [{ "id": "{{params.id}}" }] }
                }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-dynamic-gridster-page",
              "operations": [
                {
                  "operationId": "widget.add",
                  "target": { "kind": "widget", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id"],
                    "properties": { "id": { "type": "string" } }
                  }
                }
              ]
            }
            """);
    Object actionCatalog = ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("update_component")
            .category("widgets")
            .scope("component")
            .needsClarification(true)
            .missingContext(List.of("resourcePath"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-dynamic-gridster-page")
            .componentType("page-builder")
            .userPrompt("crie uma tabela")
            .build();

    Boolean shouldDefer =
        (Boolean)
            ReflectionTestUtils.invokeMethod(
                service,
                "shouldDeferComponentClarificationToActionPlan",
                intent,
                request,
                actionCatalog,
                authoringManifest);

    assertThat(shouldDefer).isFalse();
  }

  @Test
  void derivesMinimalTabAddPlanForSafeHumanRequestWhenLlmReturnsNoAction() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode componentContext =
        mapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "tab.add",
                  "patchTemplate": { "tabs": [{ "id": "{{params.id}}", "textLabel": "{{params.textLabel}}" }] }
                }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    Object actionCatalog = ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-tabs")
            .componentType("tabs")
            .userPrompt(
                "preciso de uma parte separada para acompanhar documentos e observacoes importantes")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackComponentActionPlan",
            request,
            actionCatalog,
            mapper.createArrayNode(),
            authoringManifest);

    assertThat(fallback).isNotNull();
    assertThat(fallback.getActions()).hasSize(1);
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("tab.add");
    assertThat(action.getParams().path("id").asText()).isEqualTo("documentos-e-observacoes");
    assertThat(action.getParams().path("textLabel").asText()).isEqualTo("Documentos e observacoes");
  }

  @Test
  void componentEditPlanNormalizesNestedLlmParamsForManifestRequiredFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("tab.add")
                        .params(
                            mapper.readTree(
                                """
                                {
                                  "params": {
                                    "operacao": {
                                      "id": "treinamentos-e-certificacoes",
                                      "textLabel": "Treinamentos e certificacoes"
                                    }
                                  }
                                }
                                """))
                        .build()))
            .build();

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", actionPlan, authoringManifest);

    JsonNode input = componentEditPlan.path("operations").get(0).path("input");
    assertThat(input.path("id").asText()).isEqualTo("treinamentos-e-certificacoes");
    assertThat(input.path("textLabel").asText()).isEqualTo("Treinamentos e certificacoes");
  }

  @Test
  void componentEditPlanValidatorAcceptsNestedLlmOperationParamsForRequiredFields()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    JsonNode componentEditPlan =
        mapper.readTree(
            """
            {
              "schemaVersion": "praxis-component-edit-plan.v1",
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "input": {
                    "params": {
                      "operacao": {
                        "id": "treinamentos",
                        "textLabel": "Treinamentos"
                      }
                    }
                  }
                }
              ]
            }
            """);

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            componentEditPlan,
            authoringManifest);

    assertThat(failures).isEmpty();
  }

  @Test
  void componentEditPlanValidatorAcceptsLocalizedNestedAliasesForRequiredFields()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    JsonNode componentEditPlan =
        mapper.readTree(
            """
            {
              "schemaVersion": "praxis-component-edit-plan.v1",
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "input": {
                    "params": {
                      "operacao": {
                        "identificador": "cursos-realizados",
                        "rotulo": "Cursos realizados"
                      }
                    }
                  }
                }
              ]
            }
            """);

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            componentEditPlan,
            authoringManifest);

    assertThat(failures).isEmpty();
  }

  @Test
  void componentEditPlanValidatorDerivesTechnicalIdFromHumanLabelWhenMissing()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    JsonNode componentEditPlan =
        mapper.readTree(
            """
            {
              "schemaVersion": "praxis-component-edit-plan.v1",
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "input": {
                    "params": {
                      "operacao": {
                        "descricao": "Treinamentos e certificações"
                      }
                    }
                  }
                }
              ]
            }
            """);

    JsonNode normalizedInput =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanOperationInput",
            componentEditPlan.path("operations").get(0),
            authoringManifest.path("operations").get(0));
    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            componentEditPlan,
            authoringManifest);

    assertThat(failures).isEmpty();
    assertThat(normalizedInput.path("id").asText()).isEqualTo("treinamentos-e-certificacoes");
    assertThat(normalizedInput.path("textLabel").asText()).isEqualTo("Treinamentos e certificações");
  }

  @Test
  void componentActionPlanCompletesSafeTabAddRequiredFieldsFromHumanPrompt()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan incompletePlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("tab.add")
                        .params(mapper.createObjectNode())
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-tabs")
            .componentType("tabs")
            .userPrompt("quero uma parte separada para acompanhar cursos realizados")
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            incompletePlan,
            request,
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);
    JsonNode input = componentEditPlan.path("operations").get(0).path("input");

    assertThat(input.path("id").asText()).isEqualTo("cursos-realizados");
    assertThat(input.path("textLabel").asText()).isEqualTo("Cursos Realizados");
    assertThat(warnings)
        .contains(
            "componentEditPlan completou campos obrigatorios seguros a partir do pedido humano e authoringManifest.");
  }

  @Test
  void componentActionPlanPreservesComplexParamsSerializedAsJsonText()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "editableTargets": [{ "kind": "tabContent" }],
              "operations": [
                {
                  "operationId": "tab.content.set",
                  "target": { "kind": "tabContent", "resolver": "tab-or-link-by-id", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "widgets": { "type": "array", "items": { "type": "object" } }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("tab.content.set")
                        .target("buscar-e-listar")
                        .params(
                            mapper.getNodeFactory()
                                .textNode(
                                    """
                                    {"widgets":[{"id":"treinamentos-list","component":"praxis-list","title":"Treinamentos"}]}
                                    """))
                        .build()))
            .build();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
                .userPrompt("crie nesta aba uma lista de treinamentos")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            new ArrayList<>());
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);
    JsonNode operation = componentEditPlan.path("operations").get(0);

    assertThat(operation.path("operationId").asText()).isEqualTo("tab.content.set");
    assertThat(operation.path("target").path("id").asText()).isEqualTo("buscar-e-listar");
    assertThat(operation.path("input").path("widgets")).hasSize(1);
    assertThat(operation.path("input").path("widgets").get(0).path("component").asText())
        .isEqualTo("praxis-list");
  }

  @Test
  void componentActionPlanHonorsExplicitNewTabLabelAndAlignsContentTarget()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "editableTargets": [{ "kind": "tab" }, { "kind": "tabContent" }],
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                },
                {
                  "operationId": "tab.content.set",
                  "target": { "kind": "tabContent", "resolver": "tab-or-link-by-id", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "widgets": { "type": "array", "items": { "type": "object" } }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("tab.add")
                        .params(
                            mapper.readTree(
                                """
                                {
                                  "id": "curso-pessoa-responsavel-data-previs",
                                  "textLabel": "Curso Pessoa Responsavel Data Previs"
                                }
                                """))
                        .build(),
                    AiActionPlan.Action.builder()
                        .type("tab.content.set")
                        .target("curso-pessoa-responsavel-data-previs")
                        .params(
                            mapper.readTree(
                                """
                                {
                                  "widgets": [
                                    {
                                      "id": "cadastro-treinamento-form",
                                      "component": "praxis-form",
                                      "title": "Cadastro de treinamento"
                                    }
                                  ]
                                }
                                """))
                        .build()))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
                .userPrompt(
                    "Crie uma nova aba chamada Cadastro de Treinamento com um formulario simples para registrar curso, pessoa responsavel, data prevista e status.")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);

    JsonNode addInput = componentEditPlan.path("operations").get(0).path("input");
    JsonNode contentOperation = componentEditPlan.path("operations").get(1);
    assertThat(addInput.path("id").asText()).isEqualTo("cadastro-de-treinamento");
    assertThat(addInput.path("textLabel").asText()).isEqualTo("Cadastro de Treinamento");
    assertThat(contentOperation.path("operationId").asText()).isEqualTo("tab.content.set");
    assertThat(contentOperation.path("target").path("id").asText())
        .isEqualTo("cadastro-de-treinamento");
    assertThat(warnings)
        .contains(
            "tab.add respeitou rotulo explicito do pedido humano.",
            "Alvo tab.content.set alinhado a aba explicitamente criada no pedido humano.");
  }

  @Test
  void componentActionPlanInfersNewTabInlineFormContentWhenLlmOnlyAddsTab()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "editableTargets": [{ "kind": "tab" }, { "kind": "tabContent" }],
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                },
                {
                  "operationId": "tab.content.set",
                  "target": { "kind": "tabContent", "resolver": "tab-or-link-by-id", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "content": { "type": "array", "items": { "type": "object" } },
                      "widgets": { "type": "array", "items": { "type": "object" } }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(List.of(AiActionPlan.Action.builder()
                .type("tab.add")
                .params(mapper.readTree("""
                    {
                      "id": "cadastro-de-treinamento",
                      "textLabel": "Cadastro de Treinamento"
                    }
                    """))
                .build()))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
                .userPrompt(
                    "Crie uma nova aba chamada Cadastro de Treinamento com um formulario simples para registrar curso, pessoa responsavel, data prevista e status.")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);

    JsonNode operations = componentEditPlan.path("operations");
    assertThat(operations).hasSize(2);
    assertThat(operations.get(1).path("operationId").asText()).isEqualTo("tab.content.set");
    assertThat(operations.get(1).path("target").path("id").asText())
        .isEqualTo("cadastro-de-treinamento");
    JsonNode content = operations.get(1).path("input").path("content");
    assertThat(content).hasSize(4);
    assertThat(content.get(0).path("name").asText()).isEqualTo("curso");
    assertThat(content.get(0).path("controlType").asText()).isEqualTo("input");
    assertThat(content.get(1).path("name").asText()).isEqualTo("pessoaResponsavel");
    assertThat(content.get(2).path("controlType").asText()).isEqualTo("date");
    assertThat(content.get(3).path("controlType").asText()).isEqualTo("select");
    assertThat(warnings)
        .contains("tab.content.set inferido para conteudo solicitado junto da nova aba.");
  }

  @Test
  void componentActionPlanInfersInlineFormContentForImperativeCreateTabPrompt()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "editableTargets": [{ "kind": "tab" }, { "kind": "tabContent" }],
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                },
                {
                  "operationId": "tab.content.set",
                  "target": { "kind": "tabContent", "resolver": "tab-or-link-by-id", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "content": { "type": "array", "items": { "type": "object" } },
                      "widgets": { "type": "array", "items": { "type": "object" } }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(List.of(AiActionPlan.Action.builder()
                .type("tab.add")
                .params(mapper.readTree("""
                    {
                      "id": "acompanhamento",
                      "textLabel": "Acompanhamento"
                    }
                    """))
                .build()))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
                .userPrompt(
                    "Crie uma aba chamada Acompanhamento com um formulario para registrar titulo, responsavel, data prevista e status.")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);

    JsonNode operations = componentEditPlan.path("operations");
    assertThat(operations).hasSize(2);
    assertThat(operations.get(0).path("operationId").asText()).isEqualTo("tab.add");
    assertThat(operations.get(1).path("operationId").asText()).isEqualTo("tab.content.set");
    assertThat(operations.get(1).path("target").path("id").asText()).isEqualTo("acompanhamento");
    JsonNode content = operations.get(1).path("input").path("content");
    assertThat(content).hasSize(4);
    assertThat(content.get(0).path("name").asText()).isEqualTo("titulo");
    assertThat(content.get(1).path("name").asText()).isEqualTo("responsavel");
    assertThat(content.get(2).path("controlType").asText()).isEqualTo("date");
    assertThat(content.get(3).path("controlType").asText()).isEqualTo("select");
    assertThat(warnings)
        .contains("tab.content.set inferido para conteudo solicitado junto da nova aba.");
  }

  @Test
  void componentActionPlanInfersDynamicFormLocalFieldsFromHumanFormPrompt()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-dynamic-form",
              "editableTargets": [{ "kind": "localField" }, { "kind": "row" }],
              "operations": [
                {
                  "operationId": "field.local.add",
                  "target": { "kind": "localField", "resolver": "field-by-name", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["name", "label", "controlType", "source"],
                    "properties": {
                      "name": { "type": "string" },
                      "label": { "type": "string" },
                      "controlType": { "type": "string" },
                      "source": { "const": "local" },
                      "submitPolicy": { "enum": ["omit", "include", "includeWhenDirty"] }
                    }
                  }
                },
                {
                  "operationId": "layout.column.add",
                  "target": { "kind": "row", "resolver": "row-by-id-in-section", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id"],
                    "properties": { "id": { "type": "string" } }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(List.of(AiActionPlan.Action.builder()
                .type("layout.column.add")
                .params(mapper.createObjectNode())
                .build()))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-dynamic-form")
                .componentType("form")
                .userPrompt(
                    "Crie um formulario simples para cadastrar curso, pessoa responsavel, data prevista e status.")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);
    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            componentEditPlan,
            authoringManifest);

    JsonNode operations = componentEditPlan.path("operations");
    assertThat(operations).hasSize(4);
    assertThat(operations.get(0).path("operationId").asText()).isEqualTo("field.local.add");
    assertThat(operations.get(0).path("input").path("name").asText()).isEqualTo("curso");
    assertThat(operations.get(0).path("input").path("source").asText()).isEqualTo("local");
    assertThat(operations.get(0).path("input").path("submitPolicy").asText()).isEqualTo("include");
    assertThat(operations.get(1).path("input").path("name").asText()).isEqualTo("pessoaResponsavel");
    assertThat(operations.get(2).path("input").path("controlType").asText()).isEqualTo("date");
    assertThat(operations.get(3).path("input").path("label").asText()).isEqualTo("status");
    assertThat(operations.get(3).path("input").path("controlType").asText()).isEqualTo("select");
    assertThat(failures).isEmpty();
    assertThat(warnings)
        .contains("field.local.add inferido para campos solicitados no formulario.");
  }

  @Test
  void derivesFallbackDynamicFormLocalFieldPlanWhenLlmReturnsNoAction()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode componentContext =
        mapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "field.local.add",
                  "patchTemplate": { "fieldMetadata": [{ "name": "{{params.name}}" }] }
                }
              ]
            }
            """);
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-dynamic-form",
              "operations": [
                {
                  "operationId": "field.local.add",
                  "target": { "kind": "localField", "resolver": "field-by-name", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["name", "label", "controlType", "source"],
                    "properties": {
                      "name": { "type": "string" },
                      "label": { "type": "string" },
                      "controlType": { "type": "string" },
                      "source": { "const": "local" }
                    }
                  }
                }
              ]
            }
            """);
    Object actionCatalog = ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackComponentActionPlan",
            AiOrchestratorRequest.builder()
                .componentId("praxis-dynamic-form")
                .componentType("form")
                .userPrompt("Crie um formulario para registrar titulo, responsavel e status.")
                .build(),
            actionCatalog,
            mapper.createArrayNode(),
            authoringManifest);

    assertThat(fallback).isNotNull();
    assertThat(fallback.getActions()).hasSize(3);
    assertThat(fallback.getActions().get(0).getType()).isEqualTo("field.local.add");
    assertThat(fallback.getActions().get(0).getParams().path("name").asText()).isEqualTo("titulo");
    assertThat(fallback.getActions().get(2).getParams().path("controlType").asText()).isEqualTo("select");
  }

  @Test
  void componentActionPlanNormalizesFieldLikeWidgetsInsideTabContentSet()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode authoringManifest =
        mapper.readTree(
            """
            {
              "componentId": "praxis-tabs",
              "editableTargets": [{ "kind": "tab" }, { "kind": "tabContent" }],
              "operations": [
                {
                  "operationId": "tab.add",
                  "target": { "kind": "tab", "resolver": "tab-by-id-or-label", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "required": ["id", "textLabel"],
                    "properties": {
                      "id": { "type": "string" },
                      "textLabel": { "type": "string" }
                    }
                  }
                },
                {
                  "operationId": "tab.content.set",
                  "target": { "kind": "tabContent", "resolver": "tab-or-link-by-id", "required": true },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "content": { "type": "array", "items": { "type": "object" } },
                      "widgets": { "type": "array", "items": { "type": "object" } }
                    }
                  }
                }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(List.of(
                AiActionPlan.Action.builder()
                    .type("tab.add")
                    .params(mapper.readTree("""
                        {
                          "id": "cadastro-de-treinamento",
                          "textLabel": "Cadastro de Treinamento"
                        }
                        """))
                    .build(),
                AiActionPlan.Action.builder()
                    .type("tab.content.set")
                    .target("cadastro-de-treinamento")
                    .params(mapper.readTree("""
                        {
                          "content": "formulario simples",
                          "widgets": [
                            { "type": "text", "label": "Curso" },
                            { "type": "text", "label": "Pessoa responsavel" },
                            { "type": "date", "label": "Data prevista" },
                            { "type": "select", "label": "Status" }
                          ]
                        }
                        """))
                    .build()))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            AiOrchestratorRequest.builder()
                .componentId("praxis-tabs")
                .componentType("tabs")
                .userPrompt(
                    "Crie uma nova aba chamada Cadastro de Treinamento com um formulario simples para registrar curso, pessoa responsavel, data prevista e status.")
                .build(),
            mapper.createArrayNode(),
            mapper.createObjectNode(),
            authoringManifest,
            warnings);
    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", completed, authoringManifest);

    JsonNode input = componentEditPlan.path("operations").get(1).path("input");
    assertThat(input.has("widgets")).isFalse();
    JsonNode content = input.path("content");
    assertThat(content).hasSize(4);
    assertThat(content.get(0).path("name").asText()).isEqualTo("curso");
    assertThat(content.get(0).path("controlType").asText()).isEqualTo("input");
    assertThat(content.get(1).path("name").asText()).isEqualTo("pessoaResponsavel");
    assertThat(content.get(2).path("controlType").asText()).isEqualTo("date");
    assertThat(content.get(3).path("controlType").asText()).isEqualTo("select");
    assertThat(warnings)
        .contains("tab.content.set normalizou widgets com formato de campo para DynamicFieldMetadata[].");
  }
}
