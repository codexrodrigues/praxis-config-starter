package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringComponentEditPreviewServiceTest {

    @Mock
    private AgenticAuthoringPlanService planService;

    @Mock
    private AgenticAuthoringPatchCompilerService patchCompilerService;

    @Mock
    private AgenticAuthoringComponentEditPlanService componentEditPlanService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializesOnlyCompiledInputsIntoSelectedWidget() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-chart",
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "input": { "action": "filter-widget", "target": "employeesTable" }
                  }]
                }
                """);
        JsonNode compiled = objectMapper.readTree("""
                {
                  "manifestVersion": "1.0.0",
                  "proposedConfig": {
                    "chartDocument": {
                      "version": "0.1.0",
                      "kind": "bar",
                      "events": {
                        "crossFilter": { "action": "filter-widget", "target": "employeesTable" }
                      }
                    }
                  }
                }
                """);
        when(componentEditPlanService.generateAndCompile(
                any(), eq("praxis-chart"), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringComponentEditPlanResult(
                        true,
                        List.of(),
                        List.of(),
                        plan,
                        compiled));
        AgenticAuthoringPreviewService service = previewService();
        AgenticAuthoringPlanRequest request = request(contextHints(false), "chartOne", "praxis-chart");

        AgenticAuthoringPreviewResult result = service.preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        JsonNode page = result.compiledFormPatch().path("patch").path("page");
        assertThat(page.path("widgets").get(0).path("definition").path("inputs")
                .path("chartDocument").path("events").path("crossFilter").path("target").asText())
                .isEqualTo("employeesTable");
        assertThat(page.toString()).doesNotContain("availableTargets").doesNotContain("contextDiagnostics");
        assertThat(result.compiledFormPatch().path("componentEdit").path("componentId").asText())
                .isEqualTo("praxis-chart");
        assertThat(result.assistantMessage())
                .contains("crossFilter.configure")
                .doesNotContain("column.visibility.set", "uncompiled-operation");

        ArgumentCaptor<JsonNode> config = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> validationContext = ArgumentCaptor.forClass(JsonNode.class);
        verify(componentEditPlanService).generateAndCompile(
                any(), eq("praxis-chart"), config.capture(), validationContext.capture(),
                eq("tenant"), eq("user"), eq("local"));
        assertThat(config.getValue().path("chartDocument").path("kind").asText()).isEqualTo("bar");
        assertThat(validationContext.getValue().path("availableTargets").get(0).path("events").get(0).asText())
                .isEqualTo("crossFilter");
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
    }

    @Test
    void materializesACompiledTableConfigInsideThePersistedWidgetInputsEnvelope() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.order.set",
                    "target": { "kind": "column", "field": "salario" },
                    "input": { "order": 0 }
                  }]
                }
                """);
        JsonNode compiled = objectMapper.readTree("""
                {
                  "manifestVersion": "2.0.0",
                  "proposedConfig": {
                    "resourcePath": "/api/human-resources/funcionarios",
                    "tableId": "funcionarios-table",
                    "config": {
                      "toolbar": { "visible": true, "title": "Funcionários" },
                      "columns": [{ "field": "salario", "type": "number", "order": 0 }]
                    }
                  }
                }
                """);
        when(componentEditPlanService.generateAndCompile(
                any(), eq("praxis-table"), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringComponentEditPlanResult(
                        true,
                        List.of(),
                        List.of("component-edit-plan-config-input-bound:config"),
                        plan,
                        compiled));

        AgenticAuthoringPreviewResult result = previewService().preview(
                tableRequest(), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        JsonNode inputs = result.compiledFormPatch().at("/patch/page/widgets/0/definition/inputs");
        assertThat(inputs.path("resourcePath").asText()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(inputs.path("tableId").asText()).isEqualTo("funcionarios-table");
        assertThat(inputs.at("/config/columns/0/order").asInt()).isZero();
        assertThat(result.warnings()).contains("component-edit-plan-config-input-bound:config");
        assertThat(result.assistantMessage())
                .contains("column.order.set", "demais configurações atuais serão preservadas");
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
    }

    @Test
    void refinesAndRecompilesThePersistedCompositionPlanForComponentManifestEdits() throws Exception {
        JsonNode operationPlan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.order.set",
                    "target": { "kind": "column", "field": "salario" },
                    "input": { "order": 0 }
                  }]
                }
                """);
        JsonNode compiled = objectMapper.readTree("""
                {
                  "manifestVersion": "2.0.0",
                  "proposedConfig": {
                    "resourcePath": "/api/human-resources/funcionarios",
                    "tableId": "funcionarios-table",
                    "config": {
                      "toolbar": { "visible": true, "title": "Funcionários" },
                      "columns": [{ "field": "salario", "type": "number", "order": 0 }]
                    }
                  }
                }
                """);
        JsonNode persistedCompositionPlan = objectMapper.readTree("""
                {
                  "kind": "praxis.ui-composition-plan",
                  "version": "1.0",
                  "layoutPreset": "single-table-page",
                  "widgets": [{
                    "key": "funcionarios-table",
                    "componentId": "praxis-table",
                    "inputs": {
                      "resourcePath": "/api/human-resources/funcionarios",
                      "tableId": "funcionarios-table",
                      "config": {
                        "toolbar": { "visible": true, "title": "Funcionários" },
                        "columns": [{ "field": "salario", "type": "number" }]
                      }
                    }
                  }]
                }
                """);
        when(componentEditPlanService.generateAndCompile(
                any(), eq("praxis-table"), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringComponentEditPlanResult(
                        true,
                        List.of(),
                        List.of("component-edit-plan-config-input-bound:config"),
                        operationPlan,
                        compiled));

        AgenticAuthoringPreviewResult result = previewService().previewWithPersistedUiCompositionPlan(
                tableRequest(),
                "tenant",
                "user",
                "local",
                null,
                persistedCompositionPlan);

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().at("/widgets/0/inputs/config/columns/0/order").asInt())
                .isZero();
        assertThat(result.uiCompositionPlan().at("/widgets/0/inputs/config/toolbar/title").asText())
                .isEqualTo("Funcionários");
        assertThat(result.compiledFormPatch().at(
                "/patch/page/widgets/0/definition/inputs/config/columns/0/order").asInt())
                .isZero();
        assertThat(result.warnings()).contains(
                "persisted-ui-composition-refined-by-component-manifest",
                "ui-composition-plan-compiled-by-config");
        assertThat(persistedCompositionPlan.at("/widgets/0/inputs/config/columns/0/order").isMissingNode())
                .isTrue();
    }

    @Test
    void derivesComponentEditContextFromResolvedSemanticTargetWhenWidgetIsNotPinned() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "filter.advanced.fields.add",
                    "input": {
                      "fields": ["estadoCivil"],
                      "selected": true,
                      "alwaysVisible": false
                    }
                  }]
                }
                """);
        JsonNode compiled = objectMapper.readTree("""
                {
                  "manifestVersion": "2.0.0",
                  "proposedConfig": {
                    "resourcePath": "/api/human-resources/funcionarios",
                    "tableId": "funcionarios-table",
                    "config": {
                      "toolbar": { "visible": true, "title": "Funcionários" },
                      "behavior": {
                        "filtering": {
                          "enabled": true,
                          "advancedFilters": {
                            "enabled": true,
                            "settings": {
                              "alwaysVisibleFields": ["departamentoNome"],
                              "selectedFieldIds": ["departamentoNome", "estadoCivil"]
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """);
        when(componentEditPlanService.generateAndCompile(
                any(), eq("praxis-table"), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringComponentEditPlanResult(
                        true,
                        List.of(),
                        List.of("component-edit-plan-config-input-bound:config"),
                        plan,
                        compiled));

        AgenticAuthoringPreviewResult result = previewService().preview(
                tableFilterContinuationRequestWithoutPinnedContext(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes())
                .doesNotContain("intent-resolution-artifact-requires-ui-composition-plan");
        assertThat(result.warnings()).contains(
                "compiled-from-component-authoring-manifest",
                "component-edit-context-derived-from-semantic-target");
        JsonNode inputs = result.compiledFormPatch().at("/patch/page/widgets/0/definition/inputs");
        assertThat(inputs.at(
                "/config/behavior/filtering/advancedFilters/settings/alwaysVisibleFields/0").asText())
                .isEqualTo("departamentoNome");
        assertThat(inputs.at(
                "/config/behavior/filtering/advancedFilters/settings/selectedFieldIds/1").asText())
                .isEqualTo("estadoCivil");
        assertThat(result.assistantMessage())
                .contains("filter.advanced.fields.add", "demais configurações atuais serão preservadas")
                .doesNotContain("demais configurações de filtro serão preservadas");
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
    }

    @Test
    void failsClosedWhenContextResolverReportedAnError() throws Exception {
        AgenticAuthoringPreviewResult result = previewService().preview(
                request(contextHints(true), "chartOne", "praxis-chart"),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "component-authoring-context-unavailable",
                "component-authoring-context-diagnostic:chart-target-catalog-unavailable");
        verify(componentEditPlanService, never()).generateAndCompile(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failsClosedWhenSemanticTargetDoesNotMatchPinnedWidget() throws Exception {
        AgenticAuthoringPreviewResult result = previewService().preview(
                request(contextHints(false), "anotherWidget", "praxis-chart"),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("component-edit-plan-semantic-target-mismatch");
        verify(componentEditPlanService, never()).generateAndCompile(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void compilesManifestOwnedEditAgainstDirectComponentStateWithoutPageBuilderWidget() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table",
                 "operations":[{"operationId":"appearance.density.set","input":{"density":"compact"}}]}
                """);
        JsonNode compiled = objectMapper.readTree("""
                {"manifestVersion":"2.0.0","proposedConfig":{
                  "columns":[{"field":"name","header":"Nome"}],
                  "appearance":{"density":"compact"}}}
                """);
        when(componentEditPlanService.generateAndCompile(
                any(), eq("praxis-table"), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringComponentEditPlanResult(
                        true, List.of(), List.of(), plan, compiled));

        AgenticAuthoringPreviewResult result = previewService().preview(
                directTableRequest(), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch().path("profileId").asText())
                .isEqualTo("component-manifest-edit");
        assertThat(result.compiledFormPatch().at("/patch/appearance/density").asText())
                .isEqualTo("compact");
        assertThat(result.compiledFormPatch().at("/componentEdit/plan/operations/0/operationId").asText())
                .isEqualTo("appearance.density.set");
        assertThat(result.warnings()).contains("component-edit-target-is-local-component");

        ArgumentCaptor<JsonNode> config = ArgumentCaptor.forClass(JsonNode.class);
        verify(componentEditPlanService).generateAndCompile(
                any(), eq("praxis-table"), config.capture(), any(),
                eq("tenant"), eq("user"), eq("local"));
        assertThat(config.getValue().at("/appearance/density").asText()).isEqualTo("comfortable");
    }

    private AgenticAuthoringPreviewService previewService() {
        return new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(),
                null,
                null,
                null,
                componentEditPlanService);
    }

    private AgenticAuthoringPlanRequest directTableRequest() throws Exception {
        JsonNode currentConfig = objectMapper.readTree("""
                {"columns":[{"field":"name","header":"Nome"}],
                 "appearance":{"density":"comfortable"}}
                """);
        JsonNode contextHints = objectMapper.readTree("""
                {"contract":"table-component-edit-plan","authoringManifestRef":{
                  "componentId":"praxis-table","source":"PRAXIS_TABLE_AUTHORING_MANIFEST"}}
                """);
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "appearance.density.set",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-table",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
        return new AgenticAuthoringPlanRequest(
                "Defina a densidade da tabela como compacta.",
                "openai",
                "gpt-5.4-mini",
                "secret",
                currentConfig,
                intent,
                null,
                null,
                List.of(),
                null,
                List.of(),
                contextHints);
    }

    private AgenticAuthoringPlanRequest request(
            JsonNode contextHints,
            String targetWidgetKey,
            String targetComponentId) throws Exception {
        JsonNode currentPage = objectMapper.readTree("""
                {
                  "widgets": [{
                    "key": "chartOne",
                    "definition": {
                      "id": "praxis-chart",
                      "inputs": {
                        "chartDocument": { "version": "0.1.0", "kind": "bar" }
                      }
                    }
                  }],
                  "composition": { "links": [] }
                }
                """);
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "chart",
                "configure_cross_filter",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(targetWidgetKey, targetComponentId, "", "", "", ""),
                null,
                List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
        return new AgenticAuthoringPlanRequest(
                "Use o departamento selecionado para filtrar a tabela",
                "openai",
                "gpt-5",
                "secret",
                currentPage,
                intent,
                null,
                null,
                List.of(),
                null,
                List.of(),
                contextHints);
    }

    private JsonNode contextHints(boolean error) throws Exception {
        return objectMapper.readTree("""
                {
                  "selectedWidgetKey": "chartOne",
                  "selectedComponentId": "praxis-chart",
                  "authoringManifestRef": {
                    "componentId": "praxis-chart",
                    "version": "1.0.0",
                    "source": "PRAXIS_CHART_AUTHORING_MANIFEST"
                  },
                  "validationContext": {
                    "availableTargets": [{
                      "id": "employeesTable",
                      "actions": ["filter-widget"],
                      "events": ["crossFilter"]
                    }]
                  },
                  "contextDiagnostics": %s
                }
                """.formatted(error
                ? "[{\"code\":\"chart-target-catalog-unavailable\",\"severity\":\"error\"}]"
                : "[]"));
    }

    private AgenticAuthoringPlanRequest tableRequest() throws Exception {
        JsonNode currentPage = objectMapper.readTree("""
                {
                  "version": "1.0.0",
                  "widgets": [{
                    "key": "funcionarios-table",
                    "definition": {
                      "id": "praxis-table",
                      "inputs": {
                        "resourcePath": "/api/human-resources/funcionarios",
                        "tableId": "funcionarios-table",
                        "config": {
                          "toolbar": { "visible": true, "title": "Funcionários" },
                          "columns": [{ "field": "salario", "type": "number" }]
                        }
                      }
                    }
                  }]
                }
                """);
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "selectedWidgetKey": "funcionarios-table",
                  "selectedComponentId": "praxis-table",
                  "authoringManifestRef": {
                    "componentId": "praxis-table",
                    "version": "2.0.0",
                    "source": "PRAXIS_TABLE_AUTHORING_MANIFEST"
                  },
                  "validationContext": {},
                  "contextDiagnostics": []
                }
                """);
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "set_column_order",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget("funcionarios-table", "praxis-table", "", "", "", ""),
                null,
                List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
        return new AgenticAuthoringPlanRequest(
                "Mova a coluna salário para o início",
                "openai",
                "gpt-5",
                "secret",
                currentPage,
                intent,
                null,
                null,
                List.of(),
                null,
                List.of(),
                contextHints);
    }

    private AgenticAuthoringPlanRequest tableFilterContinuationRequestWithoutPinnedContext() throws Exception {
        JsonNode currentPage = objectMapper.readTree("""
                {
                  "version": "1.0.0",
                  "widgets": [{
                    "key": "funcionarios-table",
                    "definition": {
                      "id": "praxis-table",
                      "inputs": {
                        "resourcePath": "/api/human-resources/funcionarios",
                        "tableId": "funcionarios-table",
                        "config": {
                          "toolbar": { "visible": true, "title": "Funcionários" },
                          "behavior": {
                            "filtering": {
                              "advancedFilters": {
                                "enabled": true,
                                "settings": {
                                  "alwaysVisibleFields": ["departamentoNome"],
                                  "selectedFieldIds": ["departamentoNome"]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }]
                }
                """);
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "filter.advanced.fields.add",
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "funcionarios-table",
                        "praxis-table",
                        "/api/human-resources/funcionarios",
                        "",
                        "",
                        "get"),
                null,
                List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", List.of()),
                null,
                "Vou adicionar Estado Civil aos filtros avançados. "
                        + "As demais configurações de filtro serão preservadas.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                null,
                null);
        return new AgenticAuthoringPlanRequest(
                "Agora adicione Estado Civil aos filtros avançados e mantenha Departamento visível.",
                "openai",
                "gpt-5.6-terra",
                "secret",
                currentPage,
                intent,
                null,
                null,
                List.of(),
                null,
                List.of(),
                null);
    }
}
