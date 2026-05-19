package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServiceActionPlanTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
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
                objectMapper,
                null,
                mock(AiThreadService.class),
                mock(AiMessageService.class));
  }

  @Test
  void shouldPreferCurrentUserPromptOverConversationHistory() {
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora mostre a admissão por extenso.")
            .messages(
                List.of(
                    org.praxisplatform.config.dto.AiChatMessage.builder()
                        .role("user")
                        .content("Formate a coluna CPF.")
                        .build(),
                    org.praxisplatform.config.dto.AiChatMessage.builder()
                        .role("assistant")
                        .content("Vou formatar a coluna CPF.")
                        .build()))
            .build();

    String prompt = ReflectionTestUtils.invokeMethod(service, "resolveUserPrompt", request);

    assertThat(prompt).isEqualTo("Agora mostre a admissão por extenso.");
  }

  @Test
  void shouldApplyExtraTableActionWithParams() throws Exception {
    JsonNode componentContext =
        objectMapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "column.renderer.button.style.set",
                  "patchTemplate": {
                    "columns": [
                      {
                        "field": "{{target}}",
                        "renderer": {
                          "type": "button",
                          "button": {
                            "variant": "{{params.variant}}",
                            "color": "{{params.color}}"
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """);
    List<?> actionCatalog =
        (List<?>)
            ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    JsonNode params = objectMapper.readTree("{\"variant\":\"outlined\",\"color\":\"accent\"}");
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("COLUMN.RENDERER.BUTTON.STYLE.SET")
                        .target("status")
                        .params(params)
                        .build()))
            .ambiguities(List.of())
            .build();

    Object coverage =
        ReflectionTestUtils.invokeMethod(
            service,
            "applyActionPlanCoverage",
            plan,
            actionCatalog,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode());

    JsonNode patch = (JsonNode) ReflectionTestUtils.getField(coverage, "patch");
    List<?> missingActions = (List<?>) ReflectionTestUtils.getField(coverage, "missingActions");

    assertThat(missingActions)
        .anyMatch(action -> "COLUMN.RENDERER.BUTTON.STYLE.SET".equals(action));
    assertThat(patch.path("columns").isArray()).isTrue();
    JsonNode column = patch.path("columns").get(0);
    assertThat(column.path("field").asText()).isEqualTo("status");
    assertThat(column.path("renderer").path("type").asText()).isEqualTo("button");
    assertThat(column.path("renderer").path("button").path("variant").asText())
        .isEqualTo("outlined");
    assertThat(column.path("renderer").path("button").path("color").asText())
        .isEqualTo("accent");
  }

  @Test
  void shouldApplyExtraTableActionLinkTarget() throws Exception {
    JsonNode componentContext =
        objectMapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "column.renderer.link.target.set",
                  "patchTemplate": {
                    "columns": [
                      {
                        "field": "{{target}}",
                        "renderer": {
                          "type": "link",
                          "link": {
                            "target": "{{value}}"
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """);
    List<?> actionCatalog =
        (List<?>)
            ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("COLUMN.RENDERER.LINK.TARGET.SET")
                        .target("url")
                        .value(objectMapper.readTree("\"_blank\""))
                        .build()))
            .ambiguities(List.of())
            .build();

    Object coverage =
        ReflectionTestUtils.invokeMethod(
            service,
            "applyActionPlanCoverage",
            plan,
            actionCatalog,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode());

    JsonNode patch = (JsonNode) ReflectionTestUtils.getField(coverage, "patch");
    List<?> missingActions = (List<?>) ReflectionTestUtils.getField(coverage, "missingActions");

    assertThat(missingActions)
        .anyMatch(action -> "COLUMN.RENDERER.LINK.TARGET.SET".equals(action));
    JsonNode column = patch.path("columns").get(0);
    assertThat(column.path("field").asText()).isEqualTo("url");
    assertThat(column.path("renderer").path("type").asText()).isEqualTo("link");
    assertThat(column.path("renderer").path("link").path("target").asText())
        .isEqualTo("_blank");
  }

  @Test
  void shouldApplyExtraTableActionImageShape() throws Exception {
    JsonNode componentContext =
        objectMapper.readTree(
            """
            {
              "actionCatalog": [
                {
                  "id": "column.renderer.image.shape.set",
                  "patchTemplate": {
                    "columns": [
                      {
                        "field": "{{target}}",
                        "renderer": {
                          "type": "image",
                          "image": {
                            "shape": "{{value}}"
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """);
    List<?> actionCatalog =
        (List<?>)
            ReflectionTestUtils.invokeMethod(service, "extractComponentActions", componentContext);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("COLUMN.RENDERER.IMAGE.SHAPE.SET")
                        .target("avatar")
                        .value(objectMapper.readTree("\"circle\""))
                        .build()))
            .ambiguities(List.of())
            .build();

    Object coverage =
        ReflectionTestUtils.invokeMethod(
            service,
            "applyActionPlanCoverage",
            plan,
            actionCatalog,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode());

    JsonNode patch = (JsonNode) ReflectionTestUtils.getField(coverage, "patch");
    List<?> missingActions = (List<?>) ReflectionTestUtils.getField(coverage, "missingActions");

    assertThat(missingActions)
        .anyMatch(action -> "COLUMN.RENDERER.IMAGE.SHAPE.SET".equals(action));
    JsonNode column = patch.path("columns").get(0);
    assertThat(column.path("field").asText()).isEqualTo("avatar");
    assertThat(column.path("renderer").path("type").asText()).isEqualTo("image");
    assertThat(column.path("renderer").path("image").path("shape").asText())
        .isEqualTo("circle");
  }

  @Test
  void shouldFillMissingFormatValueFromActionPlan() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("SET_FORMAT")
                        .value(objectMapper.readTree("\"BRL|symbol|2\""))
                        .build()))
            .ambiguities(List.of())
            .build();
    List<AiActionItem> actions =
        List.of(
            AiActionItem.builder().type("SET_FORMAT").field("salario").value(null).build());

    List<?> updated =
        (List<?>)
            ReflectionTestUtils.invokeMethod(
                service,
                "applyPlanValueFallback",
                actions,
                plan,
                null,
                List.of(),
                Set.of("SET_FORMAT"),
                List.of());

    AiActionItem item = (AiActionItem) updated.get(0);
    assertThat(item.getValue()).isEqualTo("BRL|symbol|2");
  }

  @Test
  void shouldSuppressResourceBindingActionsForLocalEditorialPrompts() {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("dataSource.resourcePath.set")
                        .build(),
                    AiActionPlan.Action.builder()
                        .type("templating.trailing.status.chip")
                        .params(objectMapper.createObjectNode().put("expr", "${item.status}"))
                        .build()))
            .ambiguities(List.of())
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .userPrompt(
                "Refine esta lista local/editorial mantendo tudo sem API real e sem schema externo.")
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan normalized =
        ReflectionTestUtils.invokeMethod(
            service,
            "suppressLocalEditorialResourceBindingActions",
            plan,
            request,
            warnings);

    assertThat(normalized).isNotNull();
    assertThat(normalized.getActions())
        .extracting(AiActionPlan.Action::getType)
        .containsExactly("templating.trailing.status.chip");
    assertThat(warnings)
        .contains(
            "resource-binding-actions-suppressed-for-local-editorial:dataSource.resourcePath.set");
  }

  @Test
  void shouldNormalizeFormatValueFromLabel() throws Exception {
    JsonNode optionsNode =
        objectMapper.readTree(
            """
            [
              { "value": "BRL|symbol|2", "label": "Currency BRL symbol" }
            ]
            """);
    List<?> formatOptions =
        (List<?>)
            ReflectionTestUtils.invokeMethod(service, "parseOptionsArray", optionsNode);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("SET_FORMAT")
                        .value(objectMapper.readTree("\"Currency BRL symbol (BRL|symbol|2))\""))
                        .build()))
            .ambiguities(List.of())
            .build();

    AiActionPlan normalized =
        (AiActionPlan)
            ReflectionTestUtils.invokeMethod(
                service,
                "normalizeActionPlanFormatValues",
                plan,
                formatOptions,
                null,
                new ArrayList<>());

    assertThat(normalized.getActions()).hasSize(1);
    assertThat(normalized.getActions().get(0).getValue().asText()).isEqualTo("BRL|symbol|2");
  }

  @Test
  void shouldInferFormatValueFromPrompt() throws Exception {
    JsonNode optionsNode =
        objectMapper.readTree(
            """
            [
              { "value": "BRL|symbol|2", "label": "Currency BRL symbol" },
              { "value": "1.2-2", "label": "Number 2 decimals" }
            ]
            """);
    List<?> formatOptions =
        (List<?>)
            ReflectionTestUtils.invokeMethod(service, "parseOptionsArray", optionsNode);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("SET_FORMAT")
                        .target("total")
                        .value(null)
                        .build()))
            .ambiguities(List.of())
            .build();

    AiActionPlan normalized =
        (AiActionPlan)
            ReflectionTestUtils.invokeMethod(
                service,
                "normalizeActionPlanFormatValues",
                plan,
                formatOptions,
                "Formatar como moeda BRL",
                new ArrayList<>());

    assertThat(normalized.getActions()).hasSize(1);
    assertThat(normalized.getActions().get(0).getValue().asText()).isEqualTo("BRL");
  }

  @Test
  void shouldApplySingleActionTargetFallback() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("SET_FORMAT")
                        .value(objectMapper.readTree("\"BRL|symbol|2\""))
                        .build()))
            .ambiguities(List.of())
            .build();
    var intent = org.praxisplatform.config.dto.AiIntentClassification.builder()
        .targetField("salario")
        .build();

    AiActionPlan updated =
        (AiActionPlan)
            ReflectionTestUtils.invokeMethod(
                service,
                "applySingleActionTargetFallback",
                plan,
                intent,
                List.of(),
                List.of());

    assertThat(updated.getActions()).hasSize(1);
    assertThat(updated.getActions().get(0).getTarget()).isEqualTo("salario");
  }

  @Test
  void shouldInferManifestBackedTableConditionalStylePlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "nomeCompleto", "header": "Nome" },
                { "field": "salario", "header": "Salário" },
                { "field": "cpf", "header": "CPF" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Destaque em verde salarios acima de 30 mil com fundo suave e texto em negrito.")
            .build();
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .category("conditional")
            .targetField("salario")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            intent,
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    assertThat(fallback.getActions()).hasSize(1);
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalStyle.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().path("style").path("backgroundColor").asText()).contains("46, 125, 50");
    assertThat(action.getParams().path("style").path("fontWeight").asText()).isEqualTo("600");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", fallback, tableManifest());
    assertThat(componentEditPlan.at("/operations/0/operationId").asText())
        .isEqualTo("column.conditionalStyle.add");
    assertThat(componentEditPlan.at("/operations/0/input/id").asText()).startsWith("style-salario");
  }

  @Test
  void shouldContinueConditionalStylePlanFromExistingTableRuleContext() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "nomeCompleto", "header": "Nome" },
                {
                  "field": "salario",
                  "header": "Salário",
                  "conditionalStyles": [
                    {
                      "condition": { ">": [ { "var": "salario" }, 30000 ] },
                      "style": { "backgroundColor": "rgba(46, 125, 50, 0.18)" },
                      "description": "Aplica a regra quando Salário for maior que 30.000."
                    }
                  ]
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora destaque em laranja os menores.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().category("conditional").build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalStyle.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path("<").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().path("id").asText()).contains("lt");
    assertThat(action.getParams().path("style").path("backgroundColor").asText()).contains("245, 124, 0");
  }

  @Test
  void shouldContinueConditionalChipPlanFromExistingRendererContext() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "salario",
                  "header": "Salário",
                  "conditionalRenderers": [
                    {
                      "condition": { ">": [ { "var": "salario" }, 30000 ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Alto", "color": "primary", "variant": "filled" }
                      }
                    }
                  ]
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora para os menores mostre chip Baixo.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().category("conditional").build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().path("id").asText()).contains("lt");
    assertThat(action.getParams().at("/renderer/type").asText()).isEqualTo("chip");
    assertThat(action.getParams().at("/renderer/chip/text").asText()).isEqualTo("Baixo");
  }

  @Test
  void shouldInferManifestBackedTableConditionalChipPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Mostre um chip 'Alto' quando salario for maior que 30000.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().targetField("salario").build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().at("/renderer/type").asText()).isEqualTo("chip");
    assertThat(action.getParams().at("/renderer/chip/text").asText()).isEqualTo("Alto");
  }

  @Test
  void shouldInferManifestBackedTableCpfFormatPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Formate a coluna CPF.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().targetField("cpf").build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.format.set");
    assertThat(action.getTarget()).isEqualTo("cpf");
    assertThat(action.getParams().path("format").asText()).isEqualTo("000.000.000-00");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", fallback, tableManifest());
    assertThat(componentEditPlan.at("/operations/0/operationId").asText()).isEqualTo("column.format.set");
    assertThat(componentEditPlan.at("/operations/0/input/format").asText()).isEqualTo("000.000.000-00");
  }

  @Test
  void shouldInferManifestBackedAvatarRendererRepairPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "nomeCompleto",
                  "header": "Funcionário",
                  "renderer": {
                    "type": "compose",
                    "compose": {
                      "items": [
                        { "type": "avatar", "avatar": { "initialsExpr": "= (row.nomeCompleto || '?').slice(0,2)" } },
                        { "type": "chip", "chip": { "textField": "nomeCompleto", "color": "primary", "variant": "soft" } }
                      ]
                    }
                  }
                },
                { "field": "cpf", "header": "CPF" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Corrija o avatar da coluna Funcionário, ele não está sendo exibido corretamente.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    assertThat(fallback.getActions()).hasSize(1);
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.renderer.set");
    assertThat(action.getTarget()).isEqualTo("nomeCompleto");
    assertThat(action.getParams().path("type").asText()).isEqualTo("compose");
    assertThat(action.getParams().at("/compose/items/0/type").asText()).isEqualTo("avatar");
    assertThat(action.getParams().at("/compose/items/0/avatar/initialsField").asText())
        .isEqualTo("nomeCompleto");
    assertThat(action.getParams().at("/compose/items/1/type").asText()).isEqualTo("chip");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", fallback, tableManifest());
    assertThat(componentEditPlan.at("/operations/0/operationId").asText())
        .isEqualTo("column.renderer.set");
    assertThat(componentEditPlan.at("/operations/0/target/field").asText()).isEqualTo("nomeCompleto");
    assertThat(componentEditPlan.at("/operations/0/input/type").asText()).isEqualTo("compose");
    assertThat(componentEditPlan.at("/operations/0/input/compose/layout/align").asText())
        .isEqualTo("center");
  }

  @Test
  void shouldPreferSemanticNameFieldOverEmployeeHeaderOnIdForAvatarRenderer() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "id", "header": "Funcionário" },
                { "field": "nomeCompleto", "header": "Nome completo" },
                { "field": "cpf", "header": "CPF" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Corrija o avatar da coluna Funcionário.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.renderer.set");
    assertThat(action.getTarget()).isEqualTo("nomeCompleto");
    assertThat(action.getParams().at("/compose/items/0/avatar/initialsField").asText())
        .isEqualTo("nomeCompleto");
  }

  @Test
  void shouldContinueAvatarComposeRendererWithOutlinedChip() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "nomeCompleto",
                  "header": "Funcionário",
                  "renderer": {
                    "type": "compose",
                    "compose": {
                      "items": [
                        { "type": "avatar", "avatar": { "initialsField": "nomeCompleto", "altField": "nomeCompleto", "shape": "circle", "size": 32 } },
                        { "type": "chip", "chip": { "textField": "nomeCompleto", "color": "primary", "variant": "filled" } }
                      ],
                      "layout": { "direction": "row", "gap": 8, "align": "center", "ellipsis": true }
                    }
                  }
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Deixe o chip com contorno.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.renderer.set");
    assertThat(action.getTarget()).isEqualTo("nomeCompleto");
    assertThat(action.getParams().at("/compose/items/0/avatar/size").asInt()).isEqualTo(32);
    assertThat(action.getParams().at("/compose/items/1/chip/variant").asText()).isEqualTo("outlined");
  }

  @Test
  void shouldContinueAvatarComposeRendererIncreasingAvatarSize() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "nomeCompleto",
                  "header": "Funcionário",
                  "renderer": {
                    "type": "compose",
                    "compose": {
                      "items": [
                        { "type": "avatar", "avatar": { "initialsField": "nomeCompleto", "altField": "nomeCompleto", "shape": "circle", "size": 32 } },
                        { "type": "chip", "chip": { "textField": "nomeCompleto", "color": "primary", "variant": "outlined" } }
                      ],
                      "layout": { "direction": "row", "gap": 8, "align": "center", "ellipsis": true }
                    }
                  }
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Aumente um pouco o avatar.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.renderer.set");
    assertThat(action.getTarget()).isEqualTo("nomeCompleto");
    assertThat(action.getParams().at("/compose/items/0/avatar/size").asInt()).isEqualTo(40);
    assertThat(action.getParams().at("/compose/items/1/chip/variant").asText()).isEqualTo("outlined");
  }

  @Test
  void shouldContinueAvatarComposeRendererFromConversationEvenWhenCurrentStateIsStale() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "nomeCompleto", "header": "Funcionário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Aumente um pouco o avatar.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("deixe o chip com contorno")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.renderer.set");
    assertThat(action.getTarget()).isEqualTo("nomeCompleto");
    assertThat(action.getParams().at("/compose/items/0/avatar/size").asInt()).isEqualTo(40);
    assertThat(action.getParams().at("/compose/items/1/chip/variant").asText()).isEqualTo("outlined");
  }

  @Test
  void shouldInferDateFullFormatFromNaturalContinuationPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "format": "000.000.000-00" },
                { "field": "dataAdmissao", "header": "Admissão" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora mostre a admissão por extenso.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.format.set");
    assertThat(action.getTarget()).isEqualTo("dataAdmissao");
    assertThat(action.getParams().path("format").asText()).isEqualTo("fullDate");
  }

  @Test
  void shouldPreserveAdvancedFilteringFieldsWhenContinuingVisibleFilters() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "behavior": {
                "filtering": {
                  "enabled": true,
                  "advancedFilters": {
                    "enabled": true,
                    "settings": {
                      "mode": "card",
                      "alwaysVisibleFields": ["cpf"],
                      "selectedFieldIds": ["cpf"]
                    }
                  }
                }
              }
            }
            """);
    JsonNode filteringHints =
        objectMapper.readTree(
            """
            {
              "mode": "advanced",
              "fields": ["salario"]
            }
            """);

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(service, "buildFilteringPatchFromHints", filteringHints, currentState);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/behavior/filtering/advancedFilters/settings/alwaysVisibleFields/0").asText())
        .isEqualTo("cpf");
    assertThat(patch.at("/behavior/filtering/advancedFilters/settings/alwaysVisibleFields/1").asText())
        .isEqualTo("salario");
    assertThat(patch.at("/behavior/filtering/advancedFilters/settings/selectedFieldIds/0").asText())
        .isEqualTo("cpf");
    assertThat(patch.at("/behavior/filtering/advancedFilters/settings/selectedFieldIds/1").asText())
        .isEqualTo("salario");
  }

  @Test
  void shouldRemoveFieldFromCurrentAdvancedFilteringSelection() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "behavior": {
                "filtering": {
                  "advancedFilters": {
                    "settings": {
                      "alwaysVisibleFields": ["cpf", "salario", "dataAdmissao"],
                      "selectedFieldIds": ["cpf", "salario", "dataAdmissao"]
                    }
                  }
                }
              }
            }
            """);

    List<String> remaining =
        ReflectionTestUtils.invokeMethod(
            service,
            "removeFieldsFromCurrentAdvancedFilters",
            currentState,
            List.of("cpf"));

    assertThat(remaining).containsExactly("salario", "dataAdmissao");
  }

  @Test
  void shouldContinueColumnWidthFromLastVisualColumnTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "salario", "header": "Salário", "align": "right" },
                { "field": "dataAdmissao", "header": "Admissão", "format": "dd/MM/yyyy" },
                { "field": "ativo", "header": "Ativo", "visible": true, "filterable": true, "width": "160px" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("deixe com 160px")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("alinhe a coluna salário à direita")
                    .build(),
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("assistant")
                    .content("{\"target\":{\"field\":\"ativo\"},\"operationId\":\"column.visibility.set\"}")
                    .build(),
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .content("{\"componentEditPlan\":{\"operations\":[{\"operationId\":\"column.width.set\",\"target\":{\"field\":\"ativo\"}}]}}")
                    .build()))
            .build();

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnWidthPatch",
            "deixe com 160px",
            columns,
            currentState,
            request);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/columns/0/field").asText()).isEqualTo("salario");
    assertThat(patch.at("/columns/0/width").asText()).isEqualTo("160px");
  }

  @Test
  void shouldContinueColumnVisibilityFromConversationTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "visible": false },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("reative ela")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("oculte a coluna CPF")
                    .build()))
            .build();

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnVisibilityPatch",
            "reative ela",
            columns,
            currentState,
            request);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/columns/0/field").asText()).isEqualTo("cpf");
    assertThat(patch.at("/columns/0/visible").asBoolean()).isTrue();
  }

  @Test
  void shouldContinueColumnVisibilityFromConversationTextWhenColumnIsHiddenFromSnapshot() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                { "field": "dataAdmissao", "header": "Admissão" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("mostre ela de novo")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("oculte a coluna CPF")
                    .build()))
            .build();

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnVisibilityPatch",
            "mostre ela de novo",
            columns,
            currentState,
            request);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/columns/0/field").asText()).isEqualTo("cpf");
    assertThat(patch.at("/columns/0/visible").asBoolean()).isTrue();
  }

  @Test
  void shouldContinueColumnFilterableFromConversationTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "salario", "header": "Salário", "filterable": true }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("agora remova o filtro dela")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("habilite filtro na coluna salário")
                    .build()))
            .build();

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnFilterablePatch",
            "agora remova o filtro dela",
            columns,
            currentState,
            request);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/columns/0/field").asText()).isEqualTo("salario");
    assertThat(patch.at("/columns/0/filterable").asBoolean()).isFalse();
  }

  @Test
  void shouldNotTreatAdvancedFilteringPromptAsColumnFilterableFallback() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnFilterablePatch",
            "habilite filtros avançados em modo card para CPF e salário",
            columns,
            currentState,
            AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("habilite filtros avançados em modo card para CPF e salário")
                .build());

    assertThat(patch).isNull();
  }

  @Test
  void shouldNotTreatVisibleAdvancedFilterPromptAsColumnFilterableFallback() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnFilterablePatch",
            "remova CPF dos filtros visíveis",
            columns,
            currentState,
            AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("remova CPF dos filtros visíveis")
                .build());

    assertThat(patch).isNull();
  }

  @Test
  void shouldContinueColumnStickyFromConversationTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF", "sticky": "start" },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("agora fixe ela no fim")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("fixe a coluna CPF no início")
                    .build()))
            .build();

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnStickyPatch",
            "agora fixe ela no fim",
            columns,
            currentState,
            request);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/columns/0/field").asText()).isEqualTo("cpf");
    assertThat(patch.at("/columns/0/sticky").asText()).isEqualTo("end");
  }

  @Test
  void shouldInferManifestBackedTableComputedColumnPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                { "field": "dataAdmissao", "header": "Admissão" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Crie uma coluna de bônus de 10% sobre o salário.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.computed.add");
    assertThat(action.getTarget()).isEqualTo("bonusSalario");
    assertThat(action.getParams().path("field").asText()).isEqualTo("bonusSalario");
    assertThat(action.getParams().at("/expression/*/0/var").asText()).isEqualTo("salario");
    assertThat(action.getParams().at("/expression/*/1").asDouble()).isEqualTo(0.1D);
    assertThat(action.getParams().path("outputType").asText()).isEqualTo("currency");
    assertThat(action.getParams().path("format").asText()).isEqualTo("BRL|symbol|2");
  }

  @Test
  void shouldInferManifestBackedRowAnimationPlanAndContinueIt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Anime as linhas com salário acima de 30 mil usando pulso.")
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().targetField("salario").build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("row.conditionalRenderer.add");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().path("id").asText()).contains("gt");
    assertThat(action.getParams().at("/animation/preset").asText()).isEqualTo("pulse-soft");

    JsonNode continuedState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ],
              "rowConditionalRenderers": [
                {
                  "condition": { ">": [ { "var": "salario" }, 30000 ] },
                  "animation": { "preset": "pulse-soft", "trigger": "onAppear", "intensity": "normal", "repeat": "once" }
                }
              ]
            }
            """);
    request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora para os menores use fade.")
            .build();

    fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            continuedState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("row.conditionalRenderer.add");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().path("id").asText()).contains("lt");
    assertThat(action.getParams().at("/animation/preset").asText()).isEqualTo("fade-soft");
  }

  @Test
  void shouldContinueConditionalStyleFromConversationWhenCurrentStateIsStale() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Para os menores use laranja.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Também destaque salários acima de 40000 com fundo suave.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalStyle.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/style/backgroundColor").asText()).contains("245, 124, 0");
  }

  @Test
  void shouldContinueConditionalChipFromConversationWhenCurrentStateIsStale() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Para os menores mostre chip Baixo.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Mostre chip Alto para salários acima de 40000.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/renderer/chip/text").asText()).isEqualTo("Baixo");
  }

  @Test
  void shouldExtractUnquotedBadgeLabelFromHumanContinuation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Também mostre badge Premium para esses maiores.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Mostre chip Alto para salários acima de 40000.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(action.getTarget()).isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/renderer/type").asText()).isEqualTo("badge");
    assertThat(action.getParams().at("/renderer/badge/text").asText()).isEqualTo("Premium");
  }

  @Test
  void shouldContinueRowAnimationFromConversationWhenCurrentStateIsStale() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora para os menores use fade.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Anime as linhas com salário acima de 30 mil usando pulso.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("row.conditionalRenderer.add");
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().at("/animation/preset").asText()).isEqualTo("fade-soft");
  }

  @Test
  void shouldInferComputedColumnBaseFieldFromConversation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora faça uma comissão de 5%.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Crie uma coluna de bônus de 10% sobre o salário.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.computed.add");
    assertThat(action.getTarget()).isEqualTo("comissaoCalculada");
    assertThat(action.getParams().at("/expression/*/0/var").asText()).isEqualTo("salario");
    assertThat(action.getParams().at("/expression/*/1").asDouble()).isEqualTo(0.05D);
  }

  @Test
  void shouldContinueComputedColumnPercentageFromConversation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                {
                  "field": "comissaoCalculada",
                  "header": "Comissão calculada",
                  "computed": {
                    "expression": { "*": [ { "var": "salario" }, 0.05 ] },
                    "outputType": "currency",
                    "format": "BRL|symbol|2"
                  }
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Mude para 7%.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Crie uma coluna comissão de 5% do salário.")
                    .build()))
            .build();

    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            request,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(fallback).isNotNull();
    AiActionPlan.Action action = fallback.getActions().get(0);
    assertThat(action.getType()).isEqualTo("column.computed.add");
    assertThat(action.getTarget()).isEqualTo("comissaoCalculada");
    assertThat(action.getParams().at("/expression/*/0/var").asText()).isEqualTo("salario");
    assertThat(action.getParams().at("/expression/*/1").asDouble()).isEqualTo(0.07D);
    assertThat(action.getParams().path("format").asText()).isEqualTo("BRL|symbol|2");
  }

  private JsonNode tableActionCatalog() throws Exception {
    return objectMapper.readTree(
        """
        {
          "actionCatalog": [
            { "id": "column.format.set" },
            { "id": "column.renderer.set" },
            { "id": "column.conditionalStyle.add" },
            { "id": "column.conditionalRenderer.add" },
            { "id": "column.computed.add" },
            { "id": "row.conditionalRenderer.add" }
          ]
        }
        """);
  }

  private JsonNode tableManifest() throws Exception {
    return objectMapper.readTree(
        """
        {
          "componentId": "praxis-table",
          "operations": [
            {
              "operationId": "column.format.set",
              "target": { "kind": "column", "resolver": "column-by-field", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["format"],
                "properties": {
                  "format": { "type": "string" }
                }
              }
            },
            {
              "operationId": "column.conditionalStyle.add",
              "target": { "kind": "rule", "resolver": "style-rule-in-column-or-row", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["id", "condition"],
                "properties": {
                  "id": { "type": "string" },
                  "condition": { "type": "object" },
                  "style": { "type": "object" },
                  "description": { "type": "string" }
                }
              }
            },
            {
              "operationId": "column.renderer.set",
              "target": { "kind": "renderer", "resolver": "renderer-in-column", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["type"],
                "properties": {
                  "type": { "type": "string" },
                  "compose": { "type": "object" },
                  "avatar": { "type": "object" }
                }
              }
            },
            {
              "operationId": "column.conditionalRenderer.add",
              "target": { "kind": "conditionalRenderer", "resolver": "conditional-renderer-in-column-or-row", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["id", "condition"],
                "properties": {
                  "id": { "type": "string" },
                  "condition": { "type": "object" },
                  "renderer": { "type": "object" },
                  "description": { "type": "string" }
                }
              }
            },
            {
              "operationId": "column.computed.add",
              "target": { "kind": "column", "resolver": "column-by-field", "required": false },
              "inputSchema": {
                "type": "object",
                "required": ["field", "header", "expression"],
                "properties": {
                  "field": { "type": "string" },
                  "header": { "type": "string" },
                  "expression": { "type": "object" },
                  "outputType": { "type": "string" }
                }
              }
            },
            {
              "operationId": "row.conditionalRenderer.add",
              "target": { "kind": "conditionalRenderer", "resolver": "conditional-renderer-in-row", "required": false },
              "inputSchema": {
                "type": "object",
                "required": ["id", "condition"],
                "properties": {
                  "id": { "type": "string" },
                  "condition": { "type": "object" },
                  "animation": { "type": "object" },
                  "enabled": { "type": "boolean" },
                  "description": { "type": "string" }
                }
              }
            }
          ]
        }
        """);
  }
}
