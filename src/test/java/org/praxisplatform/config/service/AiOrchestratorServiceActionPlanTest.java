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
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiActionPlan;
import org.springframework.test.util.ReflectionTestUtils;

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
}
