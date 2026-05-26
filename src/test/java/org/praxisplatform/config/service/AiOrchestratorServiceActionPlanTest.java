package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.praxisplatform.config.dto.AiActionItem;
import org.praxisplatform.config.dto.AiActionPlan;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiOption;
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
  void shouldReturnRecoverableProviderTimeoutError() {
    AiProviderCallException timeout =
        AiProviderCallException.timeout("openai", new java.net.http.HttpTimeoutException("request timed out"));

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(service, "providerCallErrorResponse", timeout);

    assertThat(response.getType()).isEqualTo("error");
    assertThat(response.getCode()).isEqualTo("AI_PROVIDER_TIMEOUT");
    assertThat(response.getMessage()).contains("Tente novamente em instantes");
    assertThat(response.getExplanation()).contains("Nenhuma alteracao foi aplicada");
  }

  @Test
  void shouldReturnActionableProviderQuotaError() {
    AiProviderCallException quota =
        AiProviderCallException.fromHttpStatus("openai", 429, "insufficient_quota: check your plan and billing");

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(service, "providerCallErrorResponse", quota);

    assertThat(response.getType()).isEqualTo("error");
    assertThat(response.getCode()).isEqualTo("AI_PROVIDER_QUOTA_EXHAUSTED");
    assertThat(response.getMessage())
        .contains("quota")
        .contains("creditos")
        .contains("billing")
        .doesNotContain("temporariamente");
    assertThat(response.getExplanation()).contains("Nenhuma alteracao foi aplicada");
  }

  @Test
  void shouldUseOnlyCurrentTurnPromptForPlanningWhenThereIsNoMemory() {
    String prompt =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveCurrentTurnPlanningPrompt",
            "Quero adicionar na toolbar um botão para exportar somente as linhas selecionadas.");

    assertThat(prompt)
        .isEqualTo("Quero adicionar na toolbar um botão para exportar somente as linhas selecionadas.");
  }

  @Test
  void shouldUseConversationMemoryAsReferenceOnlyForPlanningContinuations() {
    AiMemoryContext memory =
        new AiMemoryContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Ative filtros avançados e deixe CPF e Ativo sempre visíveis.")
                    .build(),
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("assistant")
                    .content("Vou ativar os filtros avançados e deixar CPF, Ativo sempre visíveis.")
                    .build(),
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Inclua também Salário nesses filtros.")
                    .build()),
            8,
            false,
            null);

    String prompt =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveCurrentTurnPlanningPrompt",
            "Inclua também Salário nesses filtros.",
            memory);

    assertThat(prompt).contains("CURRENT_USER_REQUEST:");
    assertThat(prompt).contains("Inclua também Salário nesses filtros.");
    assertThat(prompt).contains("CONVERSATION_CONTEXT_FOR_REFERENCE_ONLY:");
    assertThat(prompt).contains("Use o pedido atual como unica instrucao nova.");
    assertThat(prompt).contains("Ative filtros avançados e deixe CPF e Ativo sempre visíveis.");
    assertThat(prompt).contains("Vou ativar os filtros avançados");
    assertThat(prompt).contains("previous-user: Ative filtros avançados");
    assertThat(prompt).contains("previous-assistant: Vou ativar os filtros avançados");
  }

  @Test
  void shouldHumanizeTechnicalFormatValuesInActionPlanExplanation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Data Admissao" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.format.set")
                        .target("dataAdmissao")
                        .params(objectMapper.readTree("{\"format\":\"longDate\"}"))
                        .build()))
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "Fallback.");

    assertThat(explanation).isEqualTo("Vou formatar a coluna Data Admissao como data por extenso.");
    assertThat(explanation).doesNotContain("longDate");
  }

  @Test
  void shouldLetAuthoringManifestDecideOptionalVisualInputs() throws Exception {
    JsonNode manifest =
        objectMapper.readTree(
            """
            {
              "operations": [
                {
                  "operationId": "column.conditionalStyle.add",
                  "target": { "required": true },
                  "inputSchema": {
                    "required": ["id", "condition"],
                    "properties": {
                      "id": { "type": "string" },
                      "condition": { "type": "object" },
                      "style": { "type": "object" },
                      "tooltip": { "type": "object" }
                    }
                  }
                }
              ]
            }
            """);

    List<String> filtered =
        ReflectionTestUtils.invokeMethod(
            service,
            "filterManifestOptionalMissingTokens",
            "column.conditionalStyle.add",
            List.of("params.borderLocation", "params.borderStyle", "params.style", "params.condition"),
            manifest);

    assertThat(filtered).containsExactly("params.condition");
  }

  @Test
  void shouldPruneStaleColumnActionsWhenCurrentIntentTargetsToolbarExport() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                new ArrayList<>(
                    List.of(
                        AiActionPlan.Action.builder()
                            .type("column.format.set")
                            .target("dataAdmissao")
                            .params(objectMapper.readTree("{\"format\":\"dd/MM/yyyy\"}"))
                            .build(),
                        AiActionPlan.Action.builder()
                            .type("column.format.set")
                            .target("dataAdmissao")
                            .params(objectMapper.readTree("{\"format\":\"MMM/yyyy\"}"))
                            .build(),
                        AiActionPlan.Action.builder()
                            .type("export.configure")
                            .params(objectMapper.readTree("{\"enabled\":true,\"scope\":\"selected\"}"))
                            .build())))
            .ambiguities(List.of())
            .build();
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .intent("toggle_feature")
            .scope("config")
            .category("toolbar")
            .targetField(null)
            .needsClarification(false)
            .build();
    JsonNode manifest =
        objectMapper.readTree(
            """
            {
              "componentId": "praxis-table",
              "operations": [
                {
                  "operationId": "column.format.set",
                  "scope": "column",
                  "targetKind": "column",
                  "affectedPaths": ["columns[].format"]
                },
                {
                  "operationId": "export.configure",
                  "scope": "global",
                  "targetKind": "export",
                  "affectedPaths": ["export"]
                }
              ]
            }
            """);
    List<String> warnings = new ArrayList<>();

    AiActionPlan pruned =
        ReflectionTestUtils.invokeMethod(
            service,
            "pruneTableActionPlanByClassifiedIntent",
            plan,
            intent,
            List.of(),
            manifest,
            warnings);

    assertThat(pruned.getActions()).hasSize(1);
    assertThat(pruned.getActions().get(0).getType()).isEqualTo("export.configure");
    assertThat(warnings).contains("table-action-plan-pruned-by-llm-intent:global-actions");
  }

  @Test
  void shouldKeepLatestIdempotentColumnActionForSameTarget() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                new ArrayList<>(
                    List.of(
                        AiActionPlan.Action.builder()
                            .type("column.format.set")
                            .target("dataAdmissao")
                            .params(objectMapper.readTree("{\"format\":\"dd/MM/yyyy\"}"))
                            .build(),
                        AiActionPlan.Action.builder()
                            .type("column.format.set")
                            .target("dataAdmissao")
                            .params(objectMapper.readTree("{\"format\":\"MMM/yyyy\"}"))
                            .build())))
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan result =
        ReflectionTestUtils.invokeMethod(service, "dedupeIdempotentTableActionsByTarget", plan, warnings);

    assertThat(result.getActions()).hasSize(1);
    assertThat(result.getActions().get(0).getTarget()).isEqualTo("dataAdmissao");
    assertThat(result.getActions().get(0).getParams().path("format").asText()).isEqualTo("MMM/yyyy");
    assertThat(warnings).contains("table-action-plan-idempotent-actions-deduped");
  }

  @Test
  void shouldBlockSemanticAccessibilityRegressionActionPlans() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("accessibility.configure")
                        .params(objectMapper.readTree("{\"enabled\":false,\"reduceMotion\":false}"))
                        .build()))
            .build();

    String message =
        ReflectionTestUtils.invokeMethod(service, "tableActionPlanSafetyViolationMessage", plan);

    assertThat(message)
        .contains("reduza proteções de acessibilidade")
        .contains("alto contraste")
        .contains("reduzir movimento");
  }

  @Test
  void shouldAllowSemanticAccessibilityProtectionActionPlans() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("accessibility.configure")
                        .params(objectMapper.readTree("{\"enabled\":true,\"highContrast\":true,\"reduceMotion\":true}"))
                        .build()))
            .build();

    String message =
        ReflectionTestUtils.invokeMethod(service, "tableActionPlanSafetyViolationMessage", plan);

    assertThat(message).isNull();
  }

  @Test
  void shouldBlockAccessibilityConfigureWithoutSpecificProtectionInput() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("accessibility.configure")
                        .params(objectMapper.readTree("{}"))
                        .build()))
            .build();

    String message =
        ReflectionTestUtils.invokeMethod(service, "tableActionPlanSafetyViolationMessage", plan);

    assertThat(message).contains("reduza proteções de acessibilidade");
  }

  @Test
  void shouldRecognizeToolbarIntentAsGlobalTableActionIntent() {
    AiIntentClassification intent =
        AiIntentClassification.builder().category("toolbar").scope("config").build();

    Boolean result =
        ReflectionTestUtils.invokeMethod(service, "isGlobalTableActionIntent", intent);

    assertThat(result).isTrue();
  }

  @Test
  void shouldAllowReviewableComponentEditPlanForConfirmationRequiredOperation() throws Exception {
    JsonNode manifest =
        objectMapper.readTree(
            """
            {
              "editableTargets": [
                { "kind": "export", "resolver": "export-config" }
              ],
              "operations": [
                {
                  "operationId": "export.configure",
                  "scope": "global",
                  "target": { "kind": "export", "resolver": "export-config", "required": false },
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "enabled": { "type": "boolean" },
                      "general": {
                        "type": "object",
                        "properties": {
                          "scope": { "enum": ["selected", "filtered", "all"] }
                        }
                      }
                    }
                  },
                  "requiresConfirmation": true
                }
              ]
            }
            """);
    JsonNode plan =
        objectMapper.readTree(
            """
            {
              "schemaVersion": "praxis-component-edit-plan.v1",
              "componentId": "praxis-table",
              "operations": [
                {
                  "operationId": "export.configure",
                  "input": {
                    "enabled": true,
                    "general": { "scope": "selected" }
                  }
                }
              ]
            }
            """);

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            manifest);

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldConvertTableRendererPatchToManifestBackedComponentEditPlan() throws Exception {
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "renderer": {
                    "type": "chip",
                    "chip": {
                      "textField": "severidade",
                      "variant": "filled",
                      "color": "primary"
                    }
                  }
                }
              ]
            }
            """);
    List<String> warnings = new ArrayList<>();

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromPatch",
            "praxis-table",
            patch,
            tableRendererManifest(),
            warnings);

    assertThat(plan).isNotNull();
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.renderer.set");
    assertThat(plan.at("/operations/0/target/field").asText()).isEqualTo("severidade");
    assertThat(plan.at("/operations/0/input/type").asText()).isEqualTo("chip");
    assertThat(plan.at("/operations/0/input/chip/textField").asText()).isEqualTo("severidade");
    assertThat(warnings).contains("praxis-table patch livre convertido para componentEditPlan manifest-backed.");

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            tableRendererManifest());

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldAugmentTableManifestFromRuntimeComponentEditPlanContract() throws Exception {
    JsonNode staleManifest =
        objectMapper.readTree(
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
                    "properties": { "format": { "type": "string" } }
                  }
                }
              ]
            }
            """);
    JsonNode runtimeContract =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "allowedOperationIds": [
                  "column.renderer.set",
                  "column.valueMapping.set",
                  "column.conditionalRenderer.add"
                ]
              }
            }
            """);

    JsonNode manifest =
        ReflectionTestUtils.invokeMethod(
            service,
            "augmentAuthoringManifestFromRuntimeContract",
            "praxis-table",
            staleManifest,
            runtimeContract);
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "renderer": {
                    "type": "chip",
                    "chip": { "textField": "severidade", "variant": "filled" }
                  }
                }
              ]
            }
            """);

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromPatch",
            "praxis-table",
            patch,
            manifest,
            new ArrayList<String>());

    assertThat(plan).isNotNull();
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.renderer.set");
    assertThat(manifest.at("/editableTargets").toString()).contains("renderer-in-column");
    assertThat(manifest.at("/editableTargets").toString()).contains("conditional-renderer-in-column");

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            manifest);

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldPromoteTableAuthoringExamplesToExecutableManifestOperations() throws Exception {
    JsonNode examplesOnlyManifest =
        objectMapper.readTree(
            """
            {
              "componentId": "praxis-table",
              "examples": [
                {
                  "operationId": "column.renderer.set",
                  "target": "severidade",
                  "request": "Mostre severidade como etiqueta visual"
                },
                {
                  "operationId": "column.conditionalRenderer.add",
                  "target": "severidade",
                  "request": "Use badge por severidade"
                }
              ]
            }
            """);

    JsonNode manifest =
        ReflectionTestUtils.invokeMethod(
            service,
            "augmentAuthoringManifestFromRuntimeContract",
            "praxis-table",
            examplesOnlyManifest,
            null);
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "renderer": {
                    "type": "badge",
                    "badge": { "textField": "severidade", "variant": "filled" }
                  }
                }
              ]
            }
            """);

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromPatch",
            "praxis-table",
            patch,
            manifest,
            new ArrayList<String>());

    assertThat(plan).isNotNull();
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.renderer.set");
    assertThat(manifest.at("/operations").toString()).contains("column.renderer.set");
    assertThat(manifest.at("/editableTargets").toString()).contains("renderer-in-column");

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            manifest);

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldConvertTableValueMappingAndConditionalRendererPatchToManifestBackedPlan()
      throws Exception {
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "valueMapping": {
                    "CRITICA": "Crítica",
                    "ALTA": "Alta"
                  },
                  "conditionalRenderers": [
                    {
                      "condition": { "==": [ { "var": "severidade" }, "CRITICA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": {
                          "text": "Crítica",
                          "variant": "filled",
                          "color": "warn"
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """);
    List<String> warnings = new ArrayList<>();

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromPatch",
            "praxis-table",
            patch,
            tableRendererManifest(),
            warnings);

    assertThat(plan).isNotNull();
    assertThat(plan.path("operations")).hasSize(2);
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.valueMapping.set");
    assertThat(plan.at("/operations/0/input/valueMapping/CRITICA").asText()).isEqualTo("Crítica");
    assertThat(plan.at("/operations/1/operationId").asText()).isEqualTo("column.conditionalRenderer.add");
    assertThat(plan.at("/operations/1/input/id").asText()).isEqualTo("renderer-severidade-1");
    assertThat(plan.at("/operations/1/input/renderer/type").asText()).isEqualTo("badge");

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            tableRendererManifest());

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldNormalizeTableConditionalRendererTextConditionToJsonLogic()
      throws Exception {
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "conditionalRenderers": [
                    {
                      "condition": "severidade == 'ALTA'",
                      "renderer": {
                        "type": "badge",
                        "badge": {
                          "textField": "severidade",
                          "variant": "filled",
                          "color": "warn"
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """);

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromPatch",
            "praxis-table",
            patch,
            tableRendererManifest(),
            new ArrayList<String>());

    assertThat(plan).isNotNull();
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("column.conditionalRenderer.add");
    assertThat(plan.at("/operations/0/input/condition/==/0/var").asText()).isEqualTo("severidade");
    assertThat(plan.at("/operations/0/input/condition/==/1").asText()).isEqualTo("ALTA");

    List<String> failures =
        ReflectionTestUtils.invokeMethod(
            service,
            "validateComponentEditPlanAgainstAuthoringManifest",
            plan,
            tableRendererManifest());

    assertThat(failures).isEmpty();
  }

  @Test
  void shouldRequestGovernedCategoricalSemanticsBeforeApplyingUngovernedValueRenderers()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "status", "field": "status" },
                    "input": {
                      "id": "state-chip-status-true",
                      "condition": { "==": [ { "var": "status" }, true ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Ativo", "variant": "filled", "color": "primary" }
                      }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "status", "field": "status" },
                    "input": {
                      "id": "renderer-status-confronto",
                      "condition": { "==": [ { "var": "status" }, "Confronto" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "status", "variant": "filled" }
                      }
                    }
                  }
                ]
              },
              "explanation": "Vou aplicar chips na coluna Status."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Aplique chips na coluna status usando os valores existentes da tabela.")
            .dataProfile(
                objectMapper.readTree(
                    """
                    {
                      "rowCount": 16,
                      "columns": {
                        "status": {
                          "inferredType": "string",
                          "cardinality": 4,
                          "topValues": ["Em observacao", "Capturado", "Livre", "Confronto"]
                        }
                      }
                    }
                    """))
            .build();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            new ArrayList<String>(),
            tableRendererManifest());

    assertThat(response.getType()).isEqualTo("clarification");
    assertThat(response.getComponentEditPlan()).isNull();
    assertThat(response.getMessage()).contains("decisão governada").contains("Status");
    assertThat(response.getOptions())
        .contains("Definir semântica visual governada", "Aplicar chips neutros por enquanto");
    assertThat(response.getOptionPayloads()).hasSize(2);
    assertThat(response.getOptionPayloads().get(0).getContextHints().at("/categoricalFieldSemantics/field").asText())
        .isEqualTo("status");
    assertThat(response.getWarnings())
        .contains("table-categorical-renderer-ungoverned-policy-collapsed-to-neutral");
  }

  @Test
  void shouldCompleteCategoricalBadgeRenderersFromCurrentStateValues()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.renderer.set",
                    "target": { "kind": "renderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "type": "badge",
                      "badge": { "variant": "filled", "textField": "severidade" }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-1",
                      "condition": { "==": [ { "var": "severidade" }, "ALTA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "warn" }
                      }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-2",
                      "condition": { "==": [ { "var": "severidade" }, "MEDIA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "accent" }
                      }
                    }
                  }
                ]
              },
              "explanation": "Vou ajustar a apresentacao visual da coluna Severidade."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("deixe a coluna severidade como etiquetas coloridas")
            .currentState(
                objectMapper.readTree(
                    """
                    {
                      "dataSource": {
                        "data": [
                          { "severidade": "CRITICA" },
                          { "severidade": "ALTA" },
                          { "severidade": "MEDIA" },
                          { "severidade": "BAIXA" }
                        ]
                      }
                    }
                    """))
            .build();
    List<String> warnings = new ArrayList<>();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            warnings,
            tableRendererManifest());

    JsonNode plan = response.getComponentEditPlan();
    assertThat(plan.path("operations")).hasSize(5);
    assertThat(plan.at("/operations/3/input/condition/==/1").asText()).isEqualTo("CRITICA");
    assertThat(plan.at("/operations/3/input/renderer/badge/color").asText()).isEqualTo("warn");
    assertThat(plan.at("/operations/4/input/condition/==/1").asText()).isEqualTo("BAIXA");
    assertThat(plan.at("/operations/4/input/renderer/badge/color").asText()).isEqualTo("success");
    assertThat(response.getWarnings())
        .contains("table-categorical-renderer-values-grounded-from-data-profile");
  }

  @Test
  void shouldNormalizeCompleteCategoricalBadgeRendererColors()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.renderer.set",
                    "target": { "kind": "renderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "type": "badge",
                      "badge": { "variant": "filled", "textField": "severidade" }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-1",
                      "condition": { "==": [ { "var": "severidade" }, "ALTA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "primary" }
                      }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-2",
                      "condition": { "==": [ { "var": "severidade" }, "MEDIA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "accent" }
                      }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-3",
                      "condition": { "==": [ { "var": "severidade" }, "BAIXA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "warn" }
                      }
                    }
                  },
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "severidade", "field": "severidade" },
                    "input": {
                      "id": "renderer-severidade-4",
                      "condition": { "==": [ { "var": "severidade" }, "CRITICA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "success" }
                      }
                    }
                  }
                ]
              },
              "explanation": "Vou ajustar a apresentacao visual da coluna Severidade."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("deixe a coluna severidade como etiquetas coloridas")
            .currentState(
                objectMapper.readTree(
                    """
                    {
                      "dataSource": {
                        "data": [
                          { "severidade": "ALTA" },
                          { "severidade": "MEDIA" },
                          { "severidade": "BAIXA" },
                          { "severidade": "CRITICA" }
                        ]
                      }
                    }
                    """))
            .build();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            new ArrayList<String>(),
            tableRendererManifest());

    JsonNode operations = response.getComponentEditPlan().path("operations");
    assertThat(operations.get(1).at("/input/renderer/badge/color").asText()).isEqualTo("warn");
    assertThat(operations.get(2).at("/input/renderer/badge/color").asText()).isEqualTo("accent");
    assertThat(operations.get(3).at("/input/renderer/badge/color").asText()).isEqualTo("success");
    assertThat(operations.get(4).at("/input/renderer/badge/color").asText()).isEqualTo("warn");
    assertThat(response.getWarnings())
        .contains("table-categorical-renderer-values-grounded-from-data-profile");
  }

  @Test
  void shouldPreserveHumanRequestedCategoricalRendererColorForResolvedValue()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "estadoCivil", "field": "estadoCivil" },
                    "input": {
                      "id": "renderer-estadoCivil-solteiro",
                      "condition": { "==": [ { "var": "estadoCivil" }, "solteiro" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "estadoCivil", "variant": "filled", "color": "primary" }
                      }
                    }
                  }
                ]
              },
              "explanation": "Vou ajustar o chip de Solteiro."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("agora deixe solteiro laranja e com tooltip pessoa solteira")
            .currentState(
                objectMapper.readTree(
                    """
                    {
                      "dataSource": {
                        "data": [
                          { "estadoCivil": "solteiro" },
                          { "estadoCivil": "viuvo" }
                        ]
                      }
                    }
                    """))
            .build();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            new ArrayList<String>(),
            tableRendererManifest());

    JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
    assertThat(operation.at("/input/renderer/chip/color").asText()).isEqualTo("#FFA500");
    assertThat(operation.at("/input/renderer/chip/variant").asText()).isEqualTo("filled");
    assertThat(operation.at("/input/tooltip/text").asText()).isEqualTo("pessoa solteira");
  }

  @Test
  void shouldApplyHumanRequestedCategoricalRendererVariantForResolvedValue()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.conditionalRenderer.add",
                    "target": { "kind": "conditionalRenderer", "id": "estadoCivil", "field": "estadoCivil" },
                    "input": {
                      "id": "renderer-estadoCivil-viuvo",
                      "condition": { "==": [ { "var": "estadoCivil" }, "viuvo" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "estadoCivil", "variant": "filled", "color": "primary" }
                      }
                    }
                  }
                ]
              },
              "explanation": "Vou ajustar o chip de Viúvo."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("agora deixe viuvo com contorno")
            .currentState(
                objectMapper.readTree(
                    """
                    {
                      "dataSource": {
                        "data": [
                          { "estadoCivil": "solteiro" },
                          { "estadoCivil": "viuvo" }
                        ]
                      }
                    }
                    """))
            .build();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            new ArrayList<String>(),
            tableRendererManifest());

    JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
    assertThat(operation.at("/input/renderer/chip/variant").asText()).isEqualTo("outlined");
  }

  @Test
  void shouldPromoteCategoricalStyleRefinementToRendererOperation()
      throws Exception {
    JsonNode result =
        objectMapper.readTree(
            """
            {
              "componentEditPlan": {
                "schemaVersion": "praxis-component-edit-plan.v1",
                "componentId": "praxis-table",
                "operations": [
                  {
                    "operationId": "column.conditionalStyle.add",
                    "target": { "kind": "rule", "id": "estadoCivil", "field": "estadoCivil" },
                    "input": {
                      "id": "style-estadoCivil-solteiro",
                      "condition": { "==": [ { "var": "estadoCivil" }, "solteiro" ] },
                      "style": { "backgroundColor": "#FFA500" },
                      "description": "Destacar Solteiro."
                    }
                  }
                ]
              },
              "explanation": "Vou destacar a coluna Estado Civil quando for Solteiro."
            }
            """);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("agora deixe solteiro laranja e com tooltip pessoa solteira")
            .currentState(
                objectMapper.readTree(
                    """
                    {
                      "columns": [
                        {
                          "field": "estadoCivil",
                          "renderer": {
                            "type": "chip",
                            "chip": { "textField": "estadoCivil", "variant": "filled" }
                          },
                          "conditionalRenderers": [
                            {
                              "condition": { "==": [ { "var": "estadoCivil" }, "solteiro" ] },
                              "renderer": {
                                "type": "chip",
                                "chip": { "textField": "estadoCivil", "variant": "filled", "color": "primary" }
                              }
                            },
                            {
                              "condition": { "==": [ { "var": "estadoCivil" }, "viuvo" ] },
                              "renderer": {
                                "type": "chip",
                                "chip": { "textField": "estadoCivil", "variant": "filled", "color": "accent" }
                              }
                            }
                          ]
                        }
                      ],
                      "dataSource": {
                        "data": [
                          { "estadoCivil": "solteiro" },
                          { "estadoCivil": "viuvo" }
                        ]
                      }
                    }
                    """))
            .build();

    AiOrchestratorResponse response =
        ReflectionTestUtils.invokeMethod(
            service,
            "componentEditPlanResponse",
            result,
            request,
            new ArrayList<String>(),
            tableRendererManifest());

    JsonNode operation = response.getComponentEditPlan().path("operations").get(0);
    assertThat(operation.path("operationId").asText()).isEqualTo("column.conditionalRenderer.add");
    assertThat(operation.at("/target/kind").asText()).isEqualTo("conditionalRenderer");
    assertThat(operation.at("/input/style").isMissingNode()).isTrue();
    assertThat(operation.at("/input/renderer/chip/color").asText()).isEqualTo("#FFA500");
    assertThat(operation.at("/input/renderer/chip/variant").asText()).isEqualTo("filled");
    assertThat(operation.at("/input/tooltip/text").asText()).isEqualTo("pessoa solteira");
    assertThat(response.getWarnings())
        .contains("table-categorical-style-promoted-to-renderer-refinement");
  }

  @Test
  void shouldContinueCategoricalRendererTooltipFromAmbiguousValueOptions()
      throws Exception {
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .ambiguities(
                List.of(
                    AiActionPlan.Ambiguity.builder()
                        .alias("estado civil")
                        .candidates(List.of("viuvo", "viúvo", "Viuvo"))
                        .reason("Valor categórico a refinar.")
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("adicione tooltip também dizendo pessoa viúva")
            .build();
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "estadoCivil",
                  "renderer": {
                    "type": "chip",
                    "chip": { "textField": "estadoCivil", "variant": "filled" }
                  },
                  "conditionalRenderers": [
                    {
                      "id": "renderer-estadoCivil-solteiro",
                      "condition": { "==": [ { "var": "estadoCivil" }, "solteiro" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "estadoCivil", "variant": "filled", "color": "#FFA500" }
                      },
                      "tooltip": { "text": "pessoa solteira", "position": "top" }
                    },
                    {
                      "id": "renderer-estadoCivil-viuvo",
                      "condition": { "==": [ { "var": "estadoCivil" }, "viuvo" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "estadoCivil", "variant": "outlined", "color": "accent" }
                      }
                    }
                  ]
                }
              ],
              "dataSource": {
                "data": [
                  { "estadoCivil": "solteiro" },
                  { "estadoCivil": "viuvo" }
                ]
              }
            }
            """);
    ArrayList<String> warnings = new ArrayList<>();

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildCategoricalRendererContinuationPlanFromAmbiguity",
            actionPlan,
            List.of("viuvo", "viúvo", "Viuvo"),
            request,
            null,
            currentState,
            tableRendererManifest(),
            warnings);

    assertThat(plan).isNotNull();
    JsonNode operation = plan.path("operations").get(0);
    assertThat(operation.path("operationId").asText()).isEqualTo("column.conditionalRenderer.add");
    assertThat(operation.at("/target/field").asText()).isEqualTo("estadoCivil");
    assertThat(operation.at("/input/condition/==/1").asText()).isEqualTo("viuvo");
    assertThat(operation.at("/input/renderer/chip/variant").asText()).isEqualTo("outlined");
    assertThat(operation.at("/input/tooltip/text").asText()).isEqualTo("pessoa viúva");
    assertThat(warnings)
        .contains("table-categorical-renderer-continuation-grounded-from-ambiguity");
  }

  @Test
  void shouldPreferSemanticTargetFieldWhenContinuingCategoricalRendererTooltip()
      throws Exception {
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .ambiguities(
                List.of(
                    AiActionPlan.Ambiguity.builder()
                        .alias("viuvo")
                        .candidates(List.of("viuvo", "viúvo", "Viuvo"))
                        .reason("Valor categórico a refinar.")
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("no estado civil viuvo, adicione tooltip dizendo pessoa viúva")
            .build();
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "nomeCompleto",
                  "renderer": {
                    "type": "compose",
                    "compose": { "items": [ { "type": "avatar" }, { "type": "chip" } ] }
                  }
                },
                {
                  "field": "estadoCivil",
                  "renderer": {
                    "type": "chip",
                    "chip": { "textField": "estadoCivil", "variant": "filled" }
                  },
                  "conditionalRenderers": [
                    {
                      "id": "renderer-estadoCivil-viuvo",
                      "condition": { "==": [ { "var": "estadoCivil" }, "viuvo" ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "textField": "estadoCivil", "variant": "outlined", "color": "accent" }
                      }
                    }
                  ]
                }
              ],
              "dataSource": {
                "data": [
                  { "nomeCompleto": "Mira Drax", "estadoCivil": "viuvo" }
                ]
              }
            }
            """);
    ArrayList<String> warnings = new ArrayList<>();

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildCategoricalRendererContinuationPlanFromAmbiguity",
            actionPlan,
            List.of("viuvo", "viúvo", "Viuvo"),
            request,
            "estadoCivil",
            currentState,
            tableRendererManifest(),
            warnings);

    assertThat(plan).isNotNull();
    JsonNode operation = plan.path("operations").get(0);
    assertThat(operation.at("/target/field").asText()).isEqualTo("estadoCivil");
    assertThat(operation.at("/input/condition/==/1").asText()).isEqualTo("viuvo");
    assertThat(operation.at("/input/tooltip/text").asText()).isEqualTo("pessoa viúva");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldNormalizePatchConvertedCategoricalRendererColorsUsingTurnDataProfile()
      throws Exception {
    JsonNode suggestedPatch =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "severidade",
                  "renderer": {
                    "type": "badge",
                    "badge": { "variant": "filled", "textField": "severidade" }
                  },
                  "conditionalRenderers": [
                    {
                      "id": "renderer-severidade-1",
                      "condition": { "==": [ { "var": "severidade" }, "ALTA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "primary" }
                      }
                    },
                    {
                      "id": "renderer-severidade-2",
                      "condition": { "==": [ { "var": "severidade" }, "MEDIA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "accent" }
                      }
                    },
                    {
                      "id": "renderer-severidade-3",
                      "condition": { "==": [ { "var": "severidade" }, "BAIXA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "warn" }
                      }
                    },
                    {
                      "id": "renderer-severidade-4",
                      "condition": { "==": [ { "var": "severidade" }, "CRITICA" ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "textField": "severidade", "variant": "filled", "color": "success" }
                      }
                    }
                  ]
                }
              ]
            }
            """);
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "severidade", "header": "Severidade" }
              ]
            }
            """);
    JsonNode dataProfile =
        objectMapper.readTree(
            """
            {
              "columns": {
                "severidade": {
                  "inferredType": "string",
                  "cardinality": 4,
                  "topValues": ["ALTA", "MEDIA", "BAIXA", "CRITICA"]
                }
              }
            }
            """);
    ThreadLocal<JsonNode> manifestThreadLocal =
        (ThreadLocal<JsonNode>) ReflectionTestUtils.getField(service, "turnAuthoringManifest");
    ThreadLocal<JsonNode> dataProfileThreadLocal =
        (ThreadLocal<JsonNode>) ReflectionTestUtils.getField(service, "turnDataProfile");
    manifestThreadLocal.set(tableRendererManifest());
    dataProfileThreadLocal.set(dataProfile);

    try {
      AiOrchestratorResponse response =
          ReflectionTestUtils.invokeMethod(
              service,
              "applySuggestedPatch",
              suggestedPatch,
              currentState,
              "praxis-table",
              new ArrayList<String>(),
              List.of(),
              List.of(),
              objectMapper.createObjectNode(),
              false);

      JsonNode operations = response.getComponentEditPlan().path("operations");
      assertThat(operations.get(1).at("/input/renderer/badge/color").asText()).isEqualTo("warn");
      assertThat(operations.get(2).at("/input/renderer/badge/color").asText()).isEqualTo("accent");
      assertThat(operations.get(3).at("/input/renderer/badge/color").asText()).isEqualTo("success");
      assertThat(operations.get(4).at("/input/renderer/badge/color").asText()).isEqualTo("warn");
      assertThat(response.getWarnings())
          .contains("table-categorical-renderer-values-grounded-from-data-profile");
    } finally {
      manifestThreadLocal.remove();
      dataProfileThreadLocal.remove();
    }
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
                { "field": "cpf", "header": "CPF" },
                {
                  "field": "id",
                  "header": "ID",
                  "conditionalStyles": [
                    {
                      "id": "style-id-gt-40000",
                      "condition": { ">": [ { "var": "id" }, 40000 ] },
                      "style": { "backgroundColor": "rgba(25, 118, 210, 0.12)" }
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
  void shouldInferManifestBackedBooleanValueMappingPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "ativo", "header": "Ativo" },
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
            .userPrompt("Mostre Ativo como Sim e Não.")
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
    assertThat(action.getType()).isEqualTo("column.valueMapping.set");
    assertThat(action.getTarget()).isEqualTo("ativo");
    assertThat(action.getParams().at("/valueMapping/true").asText()).isEqualTo("Sim");
    assertThat(action.getParams().at("/valueMapping/false").asText()).isEqualTo("Não");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", fallback, tableManifest());
    assertThat(componentEditPlan.at("/operations/0/operationId").asText())
        .isEqualTo("column.valueMapping.set");
    assertThat(componentEditPlan.at("/operations/0/input/valueMapping/true").asText()).isEqualTo("Sim");
  }

  @Test
  void shouldContinueBooleanStateRendererFromValueMappingContext() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "ativo",
                  "header": "Ativo",
                  "valueMapping": { "true": "Sim", "false": "Não" }
                },
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
            .userPrompt("Agora use badge suave.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Mostre Ativo como Sim e Não.")
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
    assertThat(fallback.getActions()).hasSize(2);
    AiActionPlan.Action trueAction = fallback.getActions().get(0);
    AiActionPlan.Action falseAction = fallback.getActions().get(1);
    assertThat(trueAction.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(trueAction.getTarget()).isEqualTo("ativo");
    assertThat(trueAction.getParams().path("condition").path("==").get(1).asBoolean()).isTrue();
    assertThat(trueAction.getParams().at("/renderer/type").asText()).isEqualTo("badge");
    assertThat(trueAction.getParams().at("/renderer/badge/text").asText()).isEqualTo("Sim");
    assertThat(falseAction.getParams().path("condition").path("==").get(1).asBoolean()).isFalse();
    assertThat(falseAction.getParams().at("/renderer/badge/text").asText()).isEqualTo("Não");
  }

  @Test
  void shouldKeepBooleanStateRendererContextWhenSwitchingToOutlinedChip() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "ativo",
                  "header": "Ativo",
                  "valueMapping": { "true": "Sim", "false": "Não" },
                  "conditionalRenderers": [
                    {
                      "condition": { "==": [ { "var": "ativo" }, true ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "text": "Sim", "color": "primary", "variant": "soft" }
                      }
                    },
                    {
                      "condition": { "==": [ { "var": "ativo" }, false ] },
                      "renderer": {
                        "type": "badge",
                        "badge": { "text": "Não", "color": "warn", "variant": "soft" }
                      }
                    }
                  ]
                },
                {
                  "field": "nomeCompleto",
                  "header": "Funcionário",
                  "renderer": {
                    "type": "compose",
                    "compose": {
                      "items": [
                        { "type": "avatar", "avatar": { "initialsField": "nomeCompleto" } },
                        { "type": "chip", "chip": { "textField": "nomeCompleto", "variant": "filled" } }
                      ]
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
            .userPrompt("Troque para chip com contorno.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Mostre Ativo como Sim e Não.")
                    .build(),
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Agora use badge suave.")
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
    assertThat(fallback.getActions()).hasSize(2);
    AiActionPlan.Action trueAction = fallback.getActions().get(0);
    assertThat(trueAction.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(trueAction.getTarget()).isEqualTo("ativo");
    assertThat(trueAction.getParams().at("/renderer/type").asText()).isEqualTo("chip");
    assertThat(trueAction.getParams().at("/renderer/chip/text").asText()).isEqualTo("Sim");
    assertThat(trueAction.getParams().at("/renderer/chip/variant").asText()).isEqualTo("outlined");
  }

  @Test
  void shouldKeepBooleanStateRendererContextWhenAddingTooltipToResolvedChipTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "ativo",
                  "header": "Ativo",
                  "conditionalRenderers": [
                    {
                      "condition": { "==": [ { "var": "ativo" }, true ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Sim", "color": "primary", "variant": "filled" }
                      }
                    },
                    {
                      "condition": { "==": [ { "var": "ativo" }, false ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Não", "color": "warn", "variant": "filled" }
                      }
                    }
                  ]
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    Object targetColumn = columns.get(0);
    AiActionPlan fallback =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildBooleanStateRendererActionPlan",
            "agora deixe o Sim um pouco mais discreto e com tooltip funcionario ativo",
            targetColumn,
            currentState);

    assertThat(fallback).isNotNull();
    AiActionPlan.Action trueAction = fallback.getActions().get(0);
    AiActionPlan.Action falseAction = fallback.getActions().get(1);
    assertThat(trueAction.getTarget()).isEqualTo("ativo");
    assertThat(trueAction.getParams().at("/renderer/chip/text").asText()).isEqualTo("Sim");
    assertThat(trueAction.getParams().at("/renderer/chip/color").asText()).isEqualTo("basic");
    assertThat(trueAction.getParams().at("/renderer/chip/variant").asText()).isEqualTo("soft");
    assertThat(trueAction.getParams().at("/tooltip/text").asText()).isEqualTo("funcionario ativo");
    assertThat(falseAction.getParams().has("tooltip")).isFalse();
  }

  @Test
  void shouldRespectExplicitBooleanChipTextRefinementWithoutChangingIntentRouting() throws Exception {
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, true ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "S", "color": "#66BB6A", "variant": "filled" }
                                  }
                                }
                                """))
                        .build(),
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, false ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "N", "color": "warn", "variant": "filled" }
                                  }
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    ReflectionTestUtils.invokeMethod(
        service,
        "normalizeTableBooleanLabelActionsFromPrompt",
        "nao use S, escreva Ativo no chip",
        plan,
        null);

    assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
        .isEqualTo("Ativo");
    assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
        .isEqualTo("Inativo");
  }

  @Test
  void shouldPreserveBooleanChipLabelsWhenOnlyRefiningStyle() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "ativo",
                  "header": "Ativo",
                  "conditionalRenderers": [
                    {
                      "condition": { "==": [ { "var": "ativo" }, true ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Ativo", "color": "primary", "variant": "filled" }
                      }
                    },
                    {
                      "condition": { "==": [ { "var": "ativo" }, false ] },
                      "renderer": {
                        "type": "chip",
                        "chip": { "text": "Inativo", "color": "warn", "variant": "filled" }
                      }
                    }
                  ]
                }
              ]
            }
            """);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, true ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "S", "color": "#A8E6A1", "variant": "filled" }
                                  }
                                }
                                """))
                        .build(),
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, false ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "N", "color": "warn", "variant": "filled" }
                                  }
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    ReflectionTestUtils.invokeMethod(
        service,
        "normalizeTableBooleanLabelActionsFromPrompt",
        "agora use verde suave",
        plan,
        currentState);

    assertThat(plan.getActions().get(0).getParams().at("/renderer/chip/text").asText())
        .isEqualTo("Ativo");
    assertThat(plan.getActions().get(1).getParams().at("/renderer/chip/text").asText())
        .isEqualTo("Inativo");
  }

  @Test
  void shouldExplainConditionalRendererTooltipAsTipNotCondition() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "ativo", "header": "Ativo" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, true ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "Ativo", "color": "#A5D6A7", "variant": "outlined" }
                                  },
                                  "description": "Adicionar dica (tooltip) indicando que o funcionário está ativo."
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "fallback");

    assertThat(explanation)
        .contains("Vou adicionar uma dica na coluna Ativo")
        .doesNotContain("quando adicionar dica");
  }

  @Test
  void shouldExplainConditionalRendererTooltipAndSoftVariantFromParams() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "ativo", "header": "Ativo" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("ativo")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "ativo" }, true ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "text": "Sim", "color": "success", "variant": "soft" }
                                  },
                                  "tooltip": { "text": "funcionario ativo", "position": "top" },
                                  "description": "Aplica Sim quando ativo for true."
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "fallback");

    assertThat(explanation)
        .contains("Ativo")
        .contains("rótulo Sim")
        .contains("cor verde")
        .contains("visual suave")
        .contains("dica \"funcionario ativo\"");
  }

  @Test
  void shouldExplainConditionalRendererColorFromParams() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "estadoCivil", "header": "Estado Civil" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("estadoCivil")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "estadoCivil" }, "solteiro" ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "textField": "estadoCivil", "color": "#FFA500", "variant": "filled" }
                                  },
                                  "tooltip": { "text": "pessoa solteira", "position": "top" },
                                  "description": "Aplica Solteiro quando estadoCivil for solteiro."
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "fallback");

    assertThat(explanation)
        .contains("Estado Civil")
        .contains("cor laranja suave")
        .contains("dica \"pessoa solteira\"");
  }

  @Test
  void shouldExplainCategoricalRendererTooltipContinuationNaturally() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "estadoCivil", "header": "Estado Civil" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalRenderer.add")
                        .target("estadoCivil")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "estadoCivil" }, "viuvo" ] },
                                  "renderer": {
                                    "type": "chip",
                                    "chip": { "textField": "estadoCivil", "variant": "outlined", "color": "accent" }
                                  },
                                  "tooltip": { "text": "pessoa viúva", "position": "top" },
                                  "description": "Adicionar tooltip para entradas viuvo mostrando 'pessoa viúva'."
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "fallback");

    assertThat(explanation)
        .contains("Vou adicionar uma dica na coluna Estado Civil para viuvo: \"pessoa viúva\".")
        .doesNotContain("entradas viuvo mostrando")
        .doesNotContain("Adicionar tooltip");
  }

  @Test
  void shouldExplainConditionalStyleWithoutGenericConditionOrOperationalTooltip() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Data Admissao" }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiActionPlan plan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("column.conditionalStyle.add")
                        .target("dataAdmissao")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "condition": { "==": [ { "var": "dataAdmissao" }, "2022" ] },
                                  "style": { "borderLeft": "2px solid rgba(25, 118, 210, 0.35)" },
                                  "tooltip": { "text": "Adicionar borda discreta para datas de admissão de 2022", "position": "top" },
                                  "description": "a condicao for atendida"
                                }
                                """))
                        .build()))
            .ambiguities(List.of())
            .build();

    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildActionPlanComponentEditExplanation",
            plan,
            columns,
            "fallback");

    assertThat(explanation)
        .contains("Vou destacar a coluna Data Admissao usando borda lateral")
        .contains("dica \"Borda discreta para datas de admissão de 2022\"")
        .doesNotContain("condicao")
        .doesNotContain("condição for atendida")
        .doesNotContain("Adicionar");
  }

  @Test
  void shouldPolishComponentEditPlanExplanationBeforeReturningToUser() {
    String explanation =
        ReflectionTestUtils.invokeMethod(
            service,
            "polishComponentEditPlanExplanation",
            "Vou destacar a coluna Data Admissao quando a condicao for atendida usando borda discreta com tooltip Borda discreta para datas de admissão ocorridas em 2022.");

    assertThat(explanation)
        .isEqualTo(
            "Vou destacar a coluna Data Admissao usando borda discreta com dica \"Borda discreta para datas de admissão ocorridas em 2022\".");
  }

  @Test
  void shouldInferManifestBackedButtonRendererPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
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
            .userPrompt("Mostre CPF como botão com contorno.")
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
    assertThat(action.getTarget()).isEqualTo("cpf");
    assertThat(action.getParams().path("type").asText()).isEqualTo("button");
    assertThat(action.getParams().at("/button/labelField").asText()).isEqualTo("cpf");
    assertThat(action.getParams().at("/button/variant").asText()).isEqualTo("outlined");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service, "buildComponentEditPlanFromActionPlan", fallback, tableManifest());
    assertThat(componentEditPlan.at("/operations/0/operationId").asText())
        .isEqualTo("column.renderer.set");
    assertThat(componentEditPlan.at("/operations/0/input/type").asText()).isEqualTo("button");
  }

  @Test
  void shouldContinueRichRendererTargetFromPreviousRendererState() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "cpf",
                  "header": "CPF",
                  "renderer": {
                    "type": "button",
                    "button": { "labelField": "cpf", "variant": "outlined", "color": "primary", "size": "small" }
                  }
                },
                { "field": "salario", "header": "Salário" },
                {
                  "field": "dataAdmissao",
                  "header": "Admissão",
                  "renderer": {
                    "type": "button",
                    "button": { "labelField": "dataAdmissao", "variant": "outlined", "color": "primary", "size": "small" }
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
            .userPrompt("Agora use botão de texto.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Mostre CPF como botão com contorno.")
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
    assertThat(action.getTarget()).isEqualTo("cpf");
    assertThat(action.getParams().path("type").asText()).isEqualTo("button");
    assertThat(action.getParams().at("/button/labelField").asText()).isEqualTo("cpf");
    assertThat(action.getParams().at("/button/variant").asText()).isEqualTo("text");
  }

  @Test
  void shouldInferManifestBackedIconRendererPlanFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "ativo", "header": "Ativo" },
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
            .userPrompt("Mostre Ativo como ícone de check.")
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
    assertThat(action.getTarget()).isEqualTo("ativo");
    assertThat(action.getParams().path("type").asText()).isEqualTo("icon");
    assertThat(action.getParams().at("/icon/name").asText()).isEqualTo("check_circle");
  }

  @Test
  void shouldContinueConditionalStyleBorderColorFromExistingTableRuleContext() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "salario",
                  "header": "Salário",
                  "conditionalStyles": [
                    {
                      "condition": { ">": [ { "var": "salario" }, 30000 ] },
                      "style": { "backgroundColor": "rgba(46, 125, 50, 0.18)" }
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
            .userPrompt("Para os menores coloque borda laranja.")
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
    assertThat(action.getParams().path("condition").path("<").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().at("/style/borderLeft").asText()).contains("245, 124, 0");
  }

  @Test
  void shouldContinueConditionalStyleWithComposedVisualsAndTooltip() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "salario",
                  "header": "Salário",
                  "conditionalStyles": [
                    {
                      "id": "style-salario-gt-40000",
                      "condition": { ">": [ { "var": "salario" }, 40000 ] },
                      "style": { "backgroundColor": "rgba(46, 125, 50, 0.18)", "fontWeight": "600" }
                    }
                  ]
                },
                { "field": "cpf", "header": "CPF" },
                {
                  "field": "id",
                  "header": "ID",
                  "conditionalStyles": [
                    {
                      "id": "style-id-gt-40000",
                      "condition": { ">": [ { "var": "id" }, 40000 ] },
                      "style": { "backgroundColor": "rgba(25, 118, 210, 0.12)" }
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
            .userPrompt("Também deixe texto verde, opacidade suave e tooltip salário alto.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Destaque salário acima de 40000 com fundo verde e negrito.")
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
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/style/color").asText()).contains("27, 94, 32");
    assertThat(action.getParams().at("/style/opacity").asText()).isEqualTo("0.82");
    assertThat(action.getParams().at("/tooltip/text").asText()).contains("Salário acima");
  }

  @Test
  void shouldNotResolveIdFromWordsContainingIdInConditionalStyleContinuation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                {
                  "field": "salario",
                  "header": "Salário",
                  "conditionalStyles": [
                    {
                      "id": "style-salario-gt-40000",
                      "condition": { ">": [ { "var": "salario" }, 40000 ] },
                      "style": { "backgroundColor": "rgba(46, 125, 50, 0.18)" }
                    }
                  ]
                },
                { "field": "id", "header": "ID" }
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
            .userPrompt("também deixe texto verde e opacidade suave")
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
    assertThat(action.getParams().path("condition").path(">").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/style/opacity").asText()).isEqualTo("0.82");
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
  void shouldPreserveAdvancedFilteringFieldsWhenOnlyChangingMode() throws Exception {
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
                      "alwaysVisibleFields": ["cpf", "salario"],
                      "selectedFieldIds": ["cpf", "salario"]
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
              "advancedMode": "filter"
            }
            """);

    JsonNode patch =
        ReflectionTestUtils.invokeMethod(service, "buildFilteringPatchFromHints", filteringHints, currentState);

    assertThat(patch).isNotNull();
    assertThat(patch.at("/behavior/filtering/advancedFilters/settings/mode").asText()).isEqualTo("filter");
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
  void shouldCompleteManifestBackedAdvancedFilterFieldsFromHumanPrompt() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "ativo", "header": "Ativo" },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("filter.advanced.configure")
                        .params(objectMapper.readTree("{\"enabled\":true}"))
                        .value(objectMapper.readTree("{\"enabled\":true,\"settings\":{\"showAdvanced\":true}}"))
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Ative filtros avançados e deixe CPF e Ativo sempre visíveis.")
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            request,
            objectMapper.createArrayNode(),
            currentState,
            tableManifest(),
            warnings);

    JsonNode params = completed.getActions().get(0).getParams();
    assertThat(params.path("enabled").asBoolean()).isTrue();
    assertThat(params.at("/settings/showAdvanced").asBoolean()).isTrue();
    assertThat(params.at("/settings/alwaysVisibleFields/0").asText()).isEqualTo("cpf");
    assertThat(params.at("/settings/alwaysVisibleFields/1").asText()).isEqualTo("ativo");
    assertThat(params.at("/settings/selectedFieldIds/0").asText()).isEqualTo("cpf");
    assertThat(params.at("/settings/selectedFieldIds/1").asText()).isEqualTo("ativo");
    assertThat(warnings).contains("filter.advanced.configure preservou campos citados no pedido humano.");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromActionPlan",
            completed,
            tableManifest());

    JsonNode input = componentEditPlan.at("/operations/0/input");
    assertThat(input.has("value")).isFalse();
    assertThat(input.at("/settings/alwaysVisibleFields/0").asText()).isEqualTo("cpf");
    assertThat(input.at("/settings/alwaysVisibleFields/1").asText()).isEqualTo("ativo");
  }

  @Test
  void shouldMergeAdvancedFilterFieldsWithCurrentStateWhenConfiguringIncrementally()
      throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "ativo", "header": "Ativo" },
                { "field": "salario", "header": "Salário" }
              ],
              "behavior": {
                "filtering": {
                  "advancedFilters": {
                    "enabled": true,
                    "settings": {
                      "alwaysVisibleFields": ["cpf", "ativo"],
                      "selectedFieldIds": ["cpf", "ativo"]
                    }
                  }
                }
              }
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("filter.advanced.configure")
                        .params(
                            objectMapper.readTree(
                                """
                                {
                                  "enabled": true,
                                  "settings": {
                                    "showAdvanced": true,
                                    "alwaysVisibleFields": ["salario"],
                                    "selectedFieldIds": ["salario"]
                                  }
                                }
                                """))
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Inclua também Salário nesses filtros.")
            .build();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            request,
            objectMapper.createArrayNode(),
            currentState,
            tableManifest(),
            new ArrayList<String>());

    JsonNode settings = completed.getActions().get(0).getParams().path("settings");
    assertThat(settings.path("alwaysVisibleFields").get(0).asText()).isEqualTo("cpf");
    assertThat(settings.path("alwaysVisibleFields").get(1).asText()).isEqualTo("ativo");
    assertThat(settings.path("alwaysVisibleFields").get(2).asText()).isEqualTo("salario");
    assertThat(settings.path("selectedFieldIds").get(0).asText()).isEqualTo("cpf");
    assertThat(settings.path("selectedFieldIds").get(1).asText()).isEqualTo("ativo");
    assertThat(settings.path("selectedFieldIds").get(2).asText()).isEqualTo("salario");
  }

  @Test
  void shouldGroundAdvancedFilterContinuationFieldsOnFilterCatalog() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "ativo", "header": "Ativo" },
                { "field": "salario", "header": "Salário" }
              ],
              "behavior": {
                "filtering": {
                  "advancedFilters": {
                    "enabled": true,
                    "settings": {
                      "alwaysVisibleFields": ["cpf", "ativo"],
                      "selectedFieldIds": ["cpf", "ativo"]
                    }
                  }
                }
              }
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("filter.advanced.fields.add")
                        .params(objectMapper.readTree("{\"fields\":[\"salario\"],\"selected\":true,\"alwaysVisible\":true}"))
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Inclua também Salário nesses filtros.")
            .contextHints(filterFieldCatalogHints())
            .build();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            request,
            objectMapper.createArrayNode(),
            currentState,
            tableManifest(),
            new ArrayList<String>());

    JsonNode params = completed.getActions().get(0).getParams();
    assertThat(params.at("/fields/0").asText()).isEqualTo("salarioBetween");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromActionPlan",
            completed,
            tableManifest());

    JsonNode input = componentEditPlan.at("/operations/0/input");
    assertThat(input.at("/fields/0").asText()).isEqualTo("salarioBetween");
  }

  @Test
  void shouldUseSelectedFilterFieldHintWhenCompletingAdvancedFilterContinuation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "dataAdmissao", "header": "Admissão" }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("filter.advanced.fields.add")
                        .params(objectMapper.createObjectNode())
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Inclua data de admissão nos filtros avançados.")
            .contextHints(filterFieldSelectionHints("dataAdmissaoRange"))
            .build();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            request,
            objectMapper.createArrayNode(),
            currentState,
            tableManifest(),
            new ArrayList<String>());

    JsonNode params = completed.getActions().get(0).getParams();
    assertThat(params.at("/fields/0").asText()).isEqualTo("dataAdmissaoRange");
  }

  @Test
  void shouldProjectFilterFieldClarificationOptionsFromCatalog() throws Exception {
    List<AiOption> options =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildFilterFieldOptionPayloads",
            List.of("Admissão", "dataAdmissaoRange", "dataAdmissaoLastDays"),
            filterFieldCatalogHints());

    assertThat(options).hasSize(2);
    assertThat(options).extracting(AiOption::getValue)
        .containsExactly("dataAdmissaoRange", "dataAdmissaoLastDays");
    assertThat(options).extracting(AiOption::getLabel)
        .containsExactly("Período de Admissão", "Admissões recentes");
    assertThat(options.get(0).getContextHints().at("/optionSelected/targetField").asText())
        .isEqualTo("dataAdmissaoRange");
    assertThat(options.get(0).getContextHints().at("/presentation/icon").asText())
        .isEqualTo("date_range");
    assertThat(options.get(0).getContextHints().at("/presentation/description").isMissingNode())
        .isTrue();
  }

  @Test
  void shouldResolveHumanTypoFilterFieldPromptsFromCatalog() throws Exception {
    JsonNode hints =
        objectMapper.readTree(
            """
            {
              "authoringContract": {
                "componentEditPlan": {
                  "filterFieldCatalog": {
                    "fields": [
                      {
                        "name": "dataNascimentoRange",
                        "label": "Período de Nascimento",
                        "aliases": ["nascimento", "data nascimento"]
                      },
                      {
                        "name": "dataAdmissaoRange",
                        "label": "Período de Admissão",
                        "aliases": ["admissão", "data admissão", "data de admissão"]
                      }
                    ]
                  }
                }
              }
            }
            """);

    List<String> vaguePeriod =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveFilterFieldsFromCatalogPrompt",
            "inclua periodo",
            hints);
    List<String> typoAdmission =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveFilterFieldsFromCatalogPrompt",
            "coloca admisao no filtro",
            hints);
    List<String> exactAdmission =
        ReflectionTestUtils.invokeMethod(
            service,
            "resolveFilterFieldsFromCatalogPrompt",
            "Período de Admissão",
            hints);

    assertThat(vaguePeriod).containsExactly("dataNascimentoRange", "dataAdmissaoRange");
    assertThat(typoAdmission).containsExactly("dataAdmissaoRange");
    assertThat(exactAdmission).containsExactly("dataAdmissaoRange");
  }

  @Test
  void shouldBuildFilterFieldClarificationForVagueHumanPrompt() throws Exception {
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("inclua periodo")
            .contextHints(filterFieldCatalogHints())
            .build();

    Object payload =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildFilterFieldClarificationPayloadFromPrompt",
            request);

    assertThat(payload).isNotNull();
    @SuppressWarnings("unchecked")
    List<AiOption> options = (List<AiOption>) ReflectionTestUtils.getField(payload, "payloads");
    assertThat(options).extracting(AiOption::getLabel)
        .contains("Período de Admissão");
    assertThat(options).extracting(AiOption::getValue)
        .contains("dataAdmissaoRange");
  }

  @Test
  void shouldGateFilterFieldClarificationBySemanticFilteringIntent() {
    AiIntentClassification filtering =
        AiIntentClassification.builder()
            .category("filtering")
            .build();
    AiIntentClassification conditional =
        AiIntentClassification.builder()
            .category("conditional")
            .build();

    Boolean allowFiltering =
        ReflectionTestUtils.invokeMethod(service, "shouldOfferFilterFieldClarification", filtering);
    Boolean allowConditional =
        ReflectionTestUtils.invokeMethod(service, "shouldOfferFilterFieldClarification", conditional);

    assertThat(allowFiltering).isTrue();
    assertThat(allowConditional).isFalse();
  }

  @Test
  void shouldHumanizeMissingContextClarificationKeys() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .category("conditional")
            .needsClarification(true)
            .missingContext(List.of("threshold", "applyTo", "color"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("destaca salarios altos com fundo verde suave")
            .build();

    String message =
        ReflectionTestUtils.invokeMethod(service, "buildClarificationMessage", intent, request);

    assertThat(message)
        .contains("qual limite define a condicao")
        .contains("onde aplicar o ajuste")
        .contains("qual cor ou estilo visual usar")
        .doesNotContain("threshold")
        .doesNotContain("applyTo")
        .doesNotContain("color");
  }

  @Test
  void shouldHumanizeSentenceLikeMissingContextFromLlm() {
    AiIntentClassification intent =
        AiIntentClassification.builder()
            .category("conditional")
            .needsClarification(true)
            .missingContext(
                List.of(
                    "confirmar coluna alvo",
                    "definir criterio para \"menores\"",
                    "valor limite",
                    "escopo aplicacao (todas linhas|seleção|top n)"))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("e os menores deixa laranja")
            .build();

    String message =
        ReflectionTestUtils.invokeMethod(service, "buildClarificationMessage", intent, request);

    assertThat(message)
        .contains("qual coluna usar")
        .contains("qual criterio define a condicao")
        .contains("qual valor limite usar")
        .contains("onde aplicar o ajuste")
        .doesNotContain("confirmar coluna alvo")
        .doesNotContain("mais detalhes sobre valor limite")
        .doesNotContain("escopo aplicacao");
  }

  @Test
  void shouldCompleteManifestBackedAdvancedFilterFieldContinuationOperations() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "cpf", "header": "CPF" },
                { "field": "ativo", "header": "Ativo" },
                { "field": "salario", "header": "Salário" }
              ]
            }
            """);
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(
                List.of(
                    AiActionPlan.Action.builder()
                        .type("filter.advanced.fields.remove")
                        .params(objectMapper.createObjectNode())
                        .build()))
            .build();
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora remova Ativo desses filtros.")
            .build();
    List<String> warnings = new ArrayList<>();

    AiActionPlan completed =
        ReflectionTestUtils.invokeMethod(
            service,
            "completeManifestBackedActionPlanInputs",
            actionPlan,
            request,
            objectMapper.createArrayNode(),
            currentState,
            tableManifest(),
            warnings);

    JsonNode params = completed.getActions().get(0).getParams();
    assertThat(params.at("/fields/0").asText()).isEqualTo("ativo");
    assertThat(warnings).contains("filter.advanced.fields.remove completou campos citados no pedido humano.");

    JsonNode componentEditPlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildComponentEditPlanFromActionPlan",
            completed,
            tableManifest());

    JsonNode input = componentEditPlan.at("/operations/0/input");
    assertThat(input.has("value")).isFalse();
    assertThat(input.at("/fields/0").asText()).isEqualTo("ativo");
  }

  private JsonNode filterFieldCatalogHints() throws Exception {
    return objectMapper.readTree(
        """
        {
          "authoringContract": {
            "componentEditPlan": {
              "filterFieldCatalog": {
                "source": "resource-filter-schema",
                "fields": [
                  {
                    "name": "cpf",
                    "label": "CPF",
                    "aliases": ["cpf"]
                  },
                  {
                    "name": "ativo",
                    "label": "Status",
                    "aliases": ["ativo", "status"]
                  },
                  {
                    "name": "salarioBetween",
                    "label": "Faixa Salarial",
                    "aliases": ["salario", "salário", "faixa salarial"]
                  },
                  {
                    "name": "dataAdmissaoRange",
                    "label": "Período de Admissão",
                    "aliases": ["admissão", "data admissão", "data de admissão"]
                  },
                  {
                    "name": "dataAdmissaoLastDays",
                    "label": "Admissões recentes",
                    "aliases": ["admissão", "data admissão", "recentes"]
                  }
                ]
              }
            }
          }
        }
        """);
  }

  private JsonNode filterFieldSelectionHints(String targetField) throws Exception {
    JsonNode hints = filterFieldCatalogHints();
    ((com.fasterxml.jackson.databind.node.ObjectNode) hints)
        .putObject("optionSelected")
        .put("targetField", targetField)
        .putObject("selection")
        .put("value", targetField);
    return hints;
  }

  @Test
  void shouldTreatManifestBackedGlobalTableActionAsClarificationDeferrable() throws Exception {
    AiActionPlan actionPlan =
        AiActionPlan.builder()
            .actions(List.of(AiActionPlan.Action.builder().type("filter.advanced.configure").build()))
            .build();

    Boolean result =
        ReflectionTestUtils.invokeMethod(
            service,
            "hasManifestBackedGlobalAction",
            actionPlan,
            tableManifest());

    assertThat(result).isTrue();
  }

  @Test
  void shouldDetectAdvancedFilterVisibleFieldReplacementPrompt() {
    Boolean replace =
        ReflectionTestUtils.invokeMethod(
            service,
            "looksLikeFilterFieldReplacementPrompt",
            "deixe só salário nos filtros visíveis");

    assertThat(replace).isTrue();
  }

  @Test
  void shouldBuildGovernedGlobalTablePropertyPlan() throws Exception {
    JsonNode patch =
        objectMapper.readTree(
            """
            {
              "behavior": {
                "pagination": { "enabled": true, "pageSize": 25 },
                "selection": { "enabled": true, "type": "multiple" }
              },
              "toolbar": { "visible": true },
              "export": { "enabled": true, "formats": ["excel", "csv"] }
            }
            """);

    JsonNode plan =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicTableComponentEditPlan",
            patch,
            tableManifest());

    assertThat(plan).isNotNull();
    assertThat(plan.at("/operations/0/operationId").asText()).isEqualTo("behavior.pagination.configure");
    assertThat(plan.at("/operations/0/input/pageSize").asInt()).isEqualTo(25);
    assertThat(plan.at("/operations/1/operationId").asText()).isEqualTo("behavior.selection.configure");
    assertThat(plan.at("/operations/1/input/type").asText()).isEqualTo("multiple");
    assertThat(plan.at("/operations/2/operationId").asText()).isEqualTo("toolbar.configure");
    assertThat(plan.at("/operations/2/input/visible").asBoolean()).isTrue();
    assertThat(plan.at("/operations/3/operationId").asText()).isEqualTo("export.configure");
    assertThat(plan.at("/operations/3/input/formats/0").asText()).isEqualTo("excel");
    assertThat(plan.at("/operations/3/input/formats/1").asText()).isEqualTo("csv");
  }

  @Test
  void shouldBuildGlobalTablePropertyPatchesFromHumanPrompts() throws Exception {
    JsonNode pagination =
        ReflectionTestUtils.invokeMethod(service, "buildPaginationPatch", "habilite paginação com 25 por página");
    assertThat(pagination).isNotNull();
    assertThat(pagination.at("/behavior/pagination/enabled").asBoolean()).isTrue();
    assertThat(pagination.at("/behavior/pagination/pageSize").asInt()).isEqualTo(25);

    JsonNode toolbar =
        ReflectionTestUtils.invokeMethod(service, "buildToolbarPatch", "mostre a toolbar no topo");
    assertThat(toolbar).isNotNull();
    assertThat(toolbar.at("/toolbar/visible").asBoolean()).isTrue();
    assertThat(toolbar.at("/toolbar/position").asText()).isEqualTo("top");

    JsonNode export =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildExportPatch",
            "habilite exportação para Excel e CSV",
            objectMapper.createObjectNode());
    assertThat(export).isNotNull();
    assertThat(export.at("/export/enabled").asBoolean()).isTrue();
    assertThat(export.at("/export/formats/0").asText()).isEqualTo("excel");
    assertThat(export.at("/export/formats/1").asText()).isEqualTo("csv");
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
  void shouldPreferComputedColumnForPropertyContinuationAfterComputedAuthoring() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário", "sortable": true, "filterable": true },
                {
                  "field": "bonusSalario",
                  "header": "Bônus",
                  "computed": {
                    "expression": { "*": [{ "var": "salario" }, 0.15] },
                    "outputType": "currency",
                    "format": "BRL|symbol|2",
                    "dependencies": ["salario"]
                  }
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    AiOrchestratorRequest request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("desabilite a ordenação dela")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("crie uma coluna bônus com 15% do salário")
                    .build()))
            .build();

    JsonNode sortablePatch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnSortablePatch",
            "desabilite a ordenação dela",
            columns,
            currentState,
            request);

    assertThat(sortablePatch).isNotNull();
    assertThat(sortablePatch.at("/columns/0/field").asText()).isEqualTo("bonusSalario");
    assertThat(sortablePatch.at("/columns/0/sortable").asBoolean()).isFalse();

    JsonNode filterablePatch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnFilterablePatch",
            "agora habilite filtro nela",
            columns,
            currentState,
            request);

    assertThat(filterablePatch).isNotNull();
    assertThat(filterablePatch.at("/columns/0/field").asText()).isEqualTo("bonusSalario");
    assertThat(filterablePatch.at("/columns/0/filterable").asBoolean()).isTrue();

    JsonNode stickyPatch =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildDeterministicColumnStickyPatch",
            "fixe ela no fim",
            columns,
            currentState,
            request);

    assertThat(stickyPatch).isNotNull();
    assertThat(stickyPatch.at("/columns/0/field").asText()).isEqualTo("bonusSalario");
    assertThat(stickyPatch.at("/columns/0/sticky").asText()).isEqualTo("end");
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
                { "field": "salario", "header": "Salário" },
                { "field": "id", "header": "ID" }
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
            .userPrompt("Agora deixe mais forte.")
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
    assertThat(action.getParams().path("condition").path(">").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().at("/animation/preset").asText()).isEqualTo("pulse-soft");
    assertThat(action.getParams().at("/animation/intensity").asText()).isEqualTo("strong");

    request =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Repita duas vezes.")
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
    assertThat(action.getParams().path("condition").path(">").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(30000L);
    assertThat(action.getParams().at("/animation/repeat").asInt()).isEqualTo(2);

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
  void shouldNotResolveIdFromWordsContainingIdInRowAnimationContinuation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                { "field": "id", "header": "ID" }
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
            .userPrompt("agora deixe mais forte")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("anime as linhas com salário acima de 40000 usando pulso")
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
    assertThat(action.getParams().path("condition").path(">").get(0).path("var").asText())
        .isEqualTo("salario");
    assertThat(action.getParams().path("condition").path(">").get(1).asLong()).isEqualTo(40000L);
    assertThat(action.getParams().at("/animation/intensity").asText()).isEqualTo("strong");
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

  @Test
  void shouldContinueComputedColumnHeaderAndFormatFromCurrentState() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                {
                  "field": "bonusSalario",
                  "header": "Bônus",
                  "computed": {
                    "expression": { "*": [ { "var": "salario" }, 0.15 ] },
                    "outputType": "number",
                    "format": "1.0-2",
                    "dependencies": [ "salario" ]
                  }
                }
              ]
            }
            """);
    List<?> columns =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractColumnDescriptors", currentState);
    List<?> actionCatalog =
        (List<?>) ReflectionTestUtils.invokeMethod(service, "extractComponentActions", tableActionCatalog());

    AiOrchestratorRequest renameRequest =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Renomeie para Bônus estimado.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Crie uma coluna bônus com 15% do salário.")
                    .build()))
            .build();

    AiActionPlan renamePlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            renameRequest,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(renamePlan).isNotNull();
    AiActionPlan.Action renameAction = renamePlan.getActions().get(0);
    assertThat(renameAction.getType()).isEqualTo("column.computed.add");
    assertThat(renameAction.getTarget()).isEqualTo("bonusSalario");
    assertThat(renameAction.getParams().path("header").asText()).isEqualTo("Bônus estimado");
    assertThat(renameAction.getParams().at("/expression/*/0/var").asText()).isEqualTo("salario");
    assertThat(renameAction.getParams().at("/expression/*/1").asDouble()).isEqualTo(0.15D);

    AiOrchestratorRequest currencyRequest =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Agora formate como moeda brasileira.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Crie uma coluna bônus com 15% do salário.")
                    .build()))
            .build();

    AiActionPlan currencyPlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            currencyRequest,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(currencyPlan).isNotNull();
    AiActionPlan.Action currencyAction = currencyPlan.getActions().get(0);
    assertThat(currencyAction.getType()).isEqualTo("column.computed.add");
    assertThat(currencyAction.getTarget()).isEqualTo("bonusSalario");
    assertThat(currencyAction.getParams().path("outputType").asText()).isEqualTo("currency");
    assertThat(currencyAction.getParams().path("format").asText()).isEqualTo("BRL|symbol|2");
    assertThat(currencyAction.getParams().at("/expression/*/0/var").asText()).isEqualTo("salario");
    assertThat(currencyAction.getParams().at("/expression/*/1").asDouble()).isEqualTo(0.15D);
  }

  @Test
  void shouldUseComputedColumnAsVisualContinuationTarget() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                {
                  "field": "bonusSalario",
                  "header": "Bônus estimado",
                  "computed": {
                    "expression": { "*": [ { "var": "salario" }, 0.15 ] },
                    "outputType": "currency",
                    "format": "BRL|symbol|2",
                    "dependencies": [ "salario" ]
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
            .userPrompt("Agora destaque os maiores que 6000 com fundo verde.")
            .messages(List.of(
                org.praxisplatform.config.dto.AiChatMessage.builder()
                    .role("user")
                    .content("Crie uma coluna bônus com 15% do salário.")
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
    assertThat(action.getTarget()).isEqualTo("bonusSalario");
    assertThat(action.getParams().at("/condition/>/0/var").asText()).isEqualTo("computed.bonusSalario");
    assertThat(action.getParams().at("/condition/>/1").asDouble()).isEqualTo(6000D);
  }

  @Test
  void shouldUseComputedColumnConditionalContextForBadgeAndRowAnimation() throws Exception {
    JsonNode currentState =
        objectMapper.readTree(
            """
            {
              "columns": [
                { "field": "salario", "header": "Salário" },
                {
                  "field": "bonusSalario",
                  "header": "Bônus estimado",
                  "computed": {
                    "expression": { "*": [ { "var": "salario" }, 0.15 ] },
                    "outputType": "currency",
                    "format": "BRL|symbol|2",
                    "dependencies": [ "salario" ]
                  },
                  "conditionalStyles": [
                    {
                      "condition": { ">": [ { "var": "computed.bonusSalario" }, 6000 ] },
                      "style": { "backgroundColor": "rgba(46, 125, 50, 0.18)" }
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
    List<org.praxisplatform.config.dto.AiChatMessage> messages = List.of(
        org.praxisplatform.config.dto.AiChatMessage.builder()
            .role("user")
            .content("Crie uma coluna bônus com 15% do salário.")
            .build(),
        org.praxisplatform.config.dto.AiChatMessage.builder()
            .role("user")
            .content("Agora destaque os maiores que 6000 com fundo verde.")
            .build());

    AiOrchestratorRequest badgeRequest =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Para esses maiores mostre badge Alto.")
            .messages(messages)
            .build();

    AiActionPlan badgePlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            badgeRequest,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(badgePlan).isNotNull();
    AiActionPlan.Action badgeAction = badgePlan.getActions().get(0);
    assertThat(badgeAction.getType()).isEqualTo("column.conditionalRenderer.add");
    assertThat(badgeAction.getTarget()).isEqualTo("bonusSalario");
    assertThat(badgeAction.getParams().at("/condition/>/0/var").asText()).isEqualTo("computed.bonusSalario");
    assertThat(badgeAction.getParams().at("/condition/>/1").asDouble()).isEqualTo(6000D);
    assertThat(badgeAction.getParams().at("/renderer/type").asText()).isEqualTo("badge");

    AiOrchestratorRequest animationRequest =
        AiOrchestratorRequest.builder()
            .componentId("praxis-table")
            .componentType("table")
            .userPrompt("Anime as linhas para esses maiores usando pulso.")
            .messages(messages)
            .build();

    AiActionPlan animationPlan =
        ReflectionTestUtils.invokeMethod(
            service,
            "deriveFallbackTableManifestActionPlan",
            animationRequest,
            AiIntentClassification.builder().build(),
            currentState,
            columns,
            actionCatalog,
            tableManifest());

    assertThat(animationPlan).isNotNull();
    AiActionPlan.Action animationAction = animationPlan.getActions().get(0);
    assertThat(animationAction.getType()).isEqualTo("row.conditionalRenderer.add");
    assertThat(animationAction.getParams().at("/condition/>/0/var").asText()).isEqualTo("computed.bonusSalario");
    assertThat(animationAction.getParams().at("/condition/>/1").asDouble()).isEqualTo(6000D);
    assertThat(animationAction.getParams().at("/animation/preset").asText()).isEqualTo("pulse-soft");
  }

  private JsonNode tableActionCatalog() throws Exception {
    return objectMapper.readTree(
        """
        {
          "actionCatalog": [
            { "id": "column.format.set" },
            { "id": "column.valueMapping.set" },
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
              "operationId": "column.valueMapping.set",
              "target": { "kind": "column", "resolver": "column-by-field", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["valueMapping"],
                "properties": {
                  "valueMapping": { "type": "object" }
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
                  "tooltip": { "type": "object" },
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
                  "avatar": { "type": "object" },
                  "button": { "type": "object" },
                  "icon": { "type": "object" },
                  "link": { "type": "object" },
                  "badge": { "type": "object" },
                  "chip": { "type": "object" }
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
            },
            {
              "operationId": "appearance.density.set",
              "inputSchema": { "type": "object", "properties": { "density": { "type": "string" } } }
            },
            {
              "operationId": "behavior.pagination.configure",
              "inputSchema": { "type": "object", "properties": { "enabled": { "type": "boolean" }, "pageSize": { "type": "number" } } }
            },
            {
              "operationId": "behavior.selection.configure",
              "inputSchema": { "type": "object", "properties": { "enabled": { "type": "boolean" }, "type": { "type": "string" } } }
            },
            {
              "operationId": "filter.advanced.configure",
              "target": { "kind": "filter", "resolver": "advanced-filters", "required": false },
              "inputSchema": {
                "type": "object",
                "properties": {
                  "enabled": { "type": "boolean" },
                  "settings": { "type": "object" }
                }
              }
            },
            {
              "operationId": "filter.advanced.fields.add",
              "target": { "kind": "filter", "resolver": "advanced-filters", "required": false },
              "inputSchema": {
                "type": "object",
                "required": ["fields"],
                "properties": {
                  "fields": { "type": "array", "items": { "type": "string" } },
                  "selected": { "type": "boolean" },
                  "alwaysVisible": { "type": "boolean" }
                }
              }
            },
            {
              "operationId": "filter.advanced.fields.remove",
              "target": { "kind": "filter", "resolver": "advanced-filters", "required": false },
              "inputSchema": {
                "type": "object",
                "required": ["fields"],
                "properties": {
                  "fields": { "type": "array", "items": { "type": "string" } },
                  "selected": { "type": "boolean" },
                  "alwaysVisible": { "type": "boolean" }
                }
              }
            },
            {
              "operationId": "toolbar.configure",
              "inputSchema": { "type": "object", "properties": { "visible": { "type": "boolean" }, "position": { "type": "string" } } }
            },
            {
              "operationId": "export.configure",
              "inputSchema": { "type": "object", "properties": { "enabled": { "type": "boolean" }, "formats": { "type": "array" } } }
            }
          ]
        }
        """);
  }

  private JsonNode tableRendererManifest() throws Exception {
    return objectMapper.readTree(
        """
        {
          "componentId": "praxis-table",
          "editableTargets": [
            { "kind": "column", "resolver": "column-by-field" },
            { "kind": "renderer", "resolver": "renderer-in-column" },
            { "kind": "conditionalRenderer", "resolver": "conditional-renderer-in-column-or-row" }
          ],
          "operations": [
            {
              "operationId": "column.renderer.set",
              "target": { "kind": "renderer", "resolver": "renderer-in-column", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["type"],
                "properties": {
                  "type": { "type": "string" },
                  "badge": { "type": "object" },
                  "chip": { "type": "object" }
                }
              }
            },
            {
              "operationId": "column.valueMapping.set",
              "target": { "kind": "column", "resolver": "column-by-field", "required": true },
              "inputSchema": {
                "type": "object",
                "required": ["valueMapping"],
                "properties": {
                  "valueMapping": { "type": "object" }
                }
              }
            },
            {
              "operationId": "column.conditionalRenderer.add",
              "target": {
                "kind": "conditionalRenderer",
                "resolver": "conditional-renderer-in-column-or-row",
                "required": true
              },
              "inputSchema": {
                "type": "object",
                "required": ["id", "condition"],
                "properties": {
                  "id": { "type": "string" },
                  "condition": { "type": "object" },
                  "renderer": { "type": "object" },
                  "animation": { "type": "object" },
                  "description": { "type": "string" }
                }
              }
            }
          ]
        }
        """);
  }
}
