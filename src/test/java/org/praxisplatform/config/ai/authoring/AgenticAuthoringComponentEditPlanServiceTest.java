package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringComponentEditPlanServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    @Mock
    private AgenticAuthoringManifestService manifestService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authorsStructuredPlanAndCompilesWithTheSameTransientContext() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-chart",
                  "configSchemaId": "PraxisXUiChartContract",
                  "manifestVersion": "1.0.0",
                  "runtimeInputs": [{
                    "name": "chartDocument",
                    "type": "PraxisChartDocument | PraxisXUiChartContract"
                  }],
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "title": "Configure cross-filter",
                    "effects": [{
                      "kind": "set",
                      "path": "chartDocument.events.crossFilter"
                    }],
                    "affectedPaths": ["chartDocument.events.crossFilter"],
                    "inputSchema": {
                      "type": "object",
                      "required": ["action", "target"],
                      "properties": {
                        "action": { "const": "filter-widget" },
                        "target": { "type": "string" }
                      }
                    }
                  }],
                  "examples": [{
                    "id": "configure-cross-filter",
                    "operationId": "crossFilter.configure",
                    "params": { "action": "filter-widget", "target": "employeesTable" },
                    "isPositive": true
                  }]
                }
                """);
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
        JsonNode config = objectMapper.readTree("""
                { "chartDocument": { "version": "0.1.0", "kind": "bar" } }
                """);
        JsonNode validationContext = objectMapper.readTree("""
                {
                  "availableTargets": [{
                    "id": "employeesTable",
                    "actions": ["filter-widget"],
                    "events": ["crossFilter"]
                  }]
                }
                """);
        JsonNode compiledPatch = objectMapper.readTree("""
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
        when(manifestService.getManifest("praxis-chart")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-chart"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(true, java.util.List.of(), java.util.List.of(), compiledPatch));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(
                        providerManagementService,
                        manifestService,
                        objectMapper,
                        9);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest("Conecte o filtro ao quadro de colaboradores", "openai", "gpt-5", "secret"),
                "praxis-chart",
                config,
                validationContext,
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.providerInvocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.phase()).isEqualTo("component_edit_plan");
            assertThat(invocation.status()).isEqualTo("success");
        });
        assertThat(result.compiledPatch().path("proposedConfig").path("chartDocument")
                .path("events").path("crossFilter").path("target").asText()).isEqualTo("employeesTable");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> schema = ArgumentCaptor.forClass(AiJsonSchema.class);
        ArgumentCaptor<AiCallConfig> callConfig = ArgumentCaptor.forClass(AiCallConfig.class);
        verify(providerManagementService, times(2)).generateJson(
                prompt.capture(), schema.capture(), callConfig.capture(), eq("tenant"), eq("user"), eq("local"));
        assertThat(prompt.getValue())
                .contains("Never route intent by keywords or regex")
                .contains("Apply only the delta requested by userPrompt in this turn")
                .contains("do not repeat or reapply its earlier effects")
                .contains("most recently materialized compatible structure in currentConfig")
                .contains("crossFilter.configure")
                .contains("employeesTable")
                .contains("transientValidationContext");
        assertThat(schema.getValue().jsonSchema())
                .contains("praxis-component-edit-plan.v1")
                .contains("crossFilter.configure");
        assertThat(callConfig.getValue().getTemperature()).isZero();
        assertThat(callConfig.getValue().getTimeoutSeconds()).isEqualTo(9);
        assertThat(callConfig.getValue().getInvocationTrace()).isNotNull();

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-chart"), compileRequest.capture());
        assertThat(compileRequest.getValue().config()).isEqualTo(config);
        assertThat(compileRequest.getValue().validationContext()).isEqualTo(validationContext);
        assertThat(compileRequest.getValue().plan()).isEqualTo(plan);
    }

    @Test
    void marksAnAlreadyMaterializedPlanAsNoOpWithoutRejectingTheGovernedDecision() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.header.set",
                    "effects": [{ "kind": "merge-object", "path": "columns" }]
                  }]
                }
                """);
        JsonNode config = objectMapper.readTree("""
                { "columns": [{ "field": "ativo", "header": "Status" }] }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.header.set",
                    "target": "ativo",
                    "input": { "header": "Status" }
                  }]
                }
                """);
        JsonNode compiledPatch = objectMapper.createObjectNode()
                .put("manifestVersion", "1.0.0")
                .set("proposedConfig", config.deepCopy());
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true, java.util.List.of(), java.util.List.of(), compiledPatch));

        AgenticAuthoringComponentEditPlanResult result =
                new AgenticAuthoringComponentEditPlanService(
                        providerManagementService, manifestService, objectMapper)
                        .compileGovernedPlan(
                                "praxis-table",
                                config,
                                plan,
                                objectMapper.createObjectNode());

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("component-edit-plan-no-op");
        assertThat(result.compiledPatch().path("proposedConfig")).isEqualTo(config);
        verify(providerManagementService, never()).generateJson(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void compilesAlreadyAuthoredVisibleTableFilterWithoutAnotherProviderCall() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "configSchemaId": "TableConfig",
                  "manifestVersion": "2.0.0",
                  "runtimeInputs": [{ "name": "config", "type": "TableConfig" }],
                  "operations": [{
                    "operationId": "filter.advanced.configure",
                    "affectedPaths": ["behavior.filtering.advancedFilters.settings"],
                    "inputSchema": { "type": "object" }
                  }]
                }
                """);
        JsonNode widgetInputs = objectMapper.readTree("""
                {
                  "resourcePath": "/api/human-resources/funcionarios",
                  "tableId": "funcionarios-table",
                  "queryContext": { "filters": { "departamentoIdsIn": [16, 17] } },
                  "config": { "title": "Funcionários", "columns": [] }
                }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "filter.advanced.configure",
                    "input": {
                      "enabled": true,
                      "settings": {
                        "mode": "filter",
                        "alwaysVisibleFields": ["departamentoIdsIn"],
                        "selectedFieldIds": ["departamentoIdsIn"]
                      }
                    }
                  }]
                }
                """);
        JsonNode validationContext = objectMapper.readTree("""
                { "filterSchemaFields": [{ "name": "departamentoIdsIn" }] }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("""
                                {
                                  "manifestVersion": "2.0.0",
                                  "proposedConfig": {
                                    "title": "Funcionários",
                                    "columns": [],
                                    "behavior": {
                                      "filtering": {
                                        "enabled": true,
                                        "advancedFilters": {
                                          "enabled": true,
                                          "settings": {
                                            "mode": "filter",
                                            "alwaysVisibleFields": ["departamentoIdsIn"],
                                            "selectedFieldIds": ["departamentoIdsIn"]
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                                """)));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(
                        providerManagementService,
                        manifestService,
                        objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.compileGovernedPlan(
                "praxis-table",
                widgetInputs,
                plan,
                validationContext);

        assertThat(result.valid()).isTrue();
        assertThat(result.providerInvocations()).isEmpty();
        assertThat(result.warnings()).contains(
                "component-edit-plan-source:governed-materializer",
                "component-edit-plan-config-input-bound:config");
        assertThat(result.compiledPatch().at("/proposedConfig/queryContext/filters/departamentoIdsIn").toString())
                .isEqualTo("[16,17]");
        assertThat(result.compiledPatch().at(
                "/proposedConfig/config/behavior/filtering/advancedFilters/settings/alwaysVisibleFields/0").asText())
                .isEqualTo("departamentoIdsIn");
        verify(providerManagementService, never()).generateJson(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        assertThat(compileRequest.getValue().config()).isEqualTo(widgetInputs.path("config"));
        assertThat(compileRequest.getValue().validationContext()).isEqualTo(validationContext);
        assertThat(compileRequest.getValue().plan()).isEqualTo(plan);
    }

    @Test
    void rejectsInvalidProviderEnvelopeBeforeManifestCompilation() throws Exception {
        when(manifestService.getManifest("praxis-chart")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-chart",
                  "operations": [{
                    "operationId": "crossFilter.configure",
                    "inputSchema": { "type": "object" }
                  }]
                }
                """));
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection("praxis-chart", "crossFilter.configure"), objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-component-edit-plan.v1",
                          "componentId": "praxis-table",
                          "operations": []
                        }
                        """));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(providerManagementService, manifestService, objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest("Ajuste o componente", "openai", "gpt-5", "secret"),
                "praxis-chart",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .contains("component-edit-plan-component-mismatch", "component-edit-plan-operations-required");
        verify(manifestService, never()).compilePatch(any(), any());
    }

    @Test
    void capturesACompactSelectionSchemaAndRejectsAnUnselectedParameterOperationBeforeCompilation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","inputSchema":{"type":"object","properties":{"renderer":{"type":"string"}}}},
                  {"operationId":"column.visibility.set","inputSchema":{"type":"object","properties":{"visible":{"type":"boolean"}}}},
                  {"operationId":"column.type.set","inputSchema":{"type":"object","properties":{"type":{"type":"string"}}}}
                ]}
                """);
        JsonNode selection = selection("praxis-table", "column.renderer.set", "column.visibility.set");
        JsonNode invalidPlan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","input":{"renderer":"compose"}},
                  {"operationId":"column.type.set","input":{"type":"text"}},
                  {"operationId":"column.visibility.set","input":{"visible":false}}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection, invalidPlan);
        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("compor foto e ocultar coluna", "openai", "gpt", "key"),
                        "praxis-table", objectMapper.createObjectNode(), objectMapper.createObjectNode(), "t", "u", "e");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("component-edit-plan-operations-outside-semantic-selection");
        verify(manifestService, never()).compilePatch(any(), any());
        ArgumentCaptor<AiJsonSchema> schemas = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(any(), schemas.capture(), any(), any(), any(), any());
        JsonNode selectionSchema = objectMapper.readTree(schemas.getAllValues().get(0).jsonSchema());
        JsonNode parameterSchema = objectMapper.readTree(schemas.getAllValues().get(1).jsonSchema());
        assertThat(selectionSchema.toString()).doesNotContain("inputSchema", "\"input\"", "\"target\"", "\"confirmed\"");
        assertThat(selectionSchema.at("/properties/selectedOperationIds/items/enum"))
                .extracting(JsonNode::toString).asString()
                .contains("column.renderer.set", "column.visibility.set", "column.type.set");
        assertThat(parameterSchema.toString())
                .contains("column.renderer.set", "column.visibility.set")
                .doesNotContain("column.type.set");
        assertThat(parameterSchema.at("/properties/operations/minItems").asInt()).isEqualTo(2);
        assertThat(parameterSchema.at("/properties/operations/maxItems").asInt())
                .isEqualTo(AgenticAuthoringProviderSchemaCompiler.MAX_PLAN_OPERATIONS);
    }

    @Test
    void normalizesExactSelectedOperationSetToCanonicalGraphOrderBeforeCompilation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","inputSchema":{"type":"object","properties":{"type":{"type":"string"}}}},
                  {"operationId":"column.visibility.set","inputSchema":{"type":"object","properties":{"visible":{"type":"boolean"}}}}
                ]}
                """);
        JsonNode selection = selection("praxis-table", "column.renderer.set", "column.visibility.set");
        JsonNode reversedPlan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[
                  {"operationId":"column.visibility.set","target":"avatarUrl","input":{"visible":false}},
                  {"operationId":"column.renderer.set","target":"id","input":{"type":"compose"}}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection, reversedPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.createObjectNode().putObject("proposedConfig")));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("compor foto e ocultar coluna", "openai", "gpt", "key"),
                        "praxis-table",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        "t",
                        "u",
                        "e");

        assertThat(result.valid()).isTrue();
        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        assertThat(compileRequest.getValue().plan().path("operations"))
                .extracting(operation -> operation.path("operationId").asText())
                .containsExactly("column.renderer.set", "column.visibility.set");
    }

    @Test
    void preservesRepeatedSelectedOperationForEveryAffectedTargetInCanonicalOrder() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","inputSchema":{"type":"object","properties":{"type":{"type":"string"}}}},
                  {"operationId":"column.visibility.set","inputSchema":{"type":"object","properties":{"visible":{"type":"boolean"}}}}
                ]}
                """);
        JsonNode selection = selection("praxis-table", "column.renderer.set", "column.visibility.set");
        JsonNode multiTargetPlan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[
                  {"operationId":"column.visibility.set","target":"dataAdmissao","input":{"visible":false}},
                  {"operationId":"column.renderer.set","target":"id","input":{"type":"compose"}},
                  {"operationId":"column.visibility.set","target":"cargo","input":{"visible":false}},
                  {"operationId":"column.visibility.set","target":"departamento","input":{"visible":false}},
                  {"operationId":"column.visibility.set","target":"estadoCivil","input":{"visible":false}}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection, multiTargetPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.createObjectNode().putObject("proposedConfig")));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest(
                                "Mantenha foto e código juntos e oculte data, cargo, departamento e estado civil",
                                "openai",
                                "gpt",
                                "key"),
                        "praxis-table",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        "t",
                        "u",
                        "e");

        assertThat(result.valid()).isTrue();
        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        assertThat(compileRequest.getValue().plan().path("operations"))
                .extracting(operation -> operation.path("operationId").asText())
                .containsExactly(
                        "column.renderer.set",
                        "column.visibility.set",
                        "column.visibility.set",
                        "column.visibility.set",
                        "column.visibility.set");
        assertThat(compileRequest.getValue().plan().path("operations"))
                .extracting(operation -> operation.path("target").asText())
                .containsExactly("id", "dataAdmissao", "cargo", "departamento", "estadoCivil");
    }

    @Test
    void compilesUniqueManifestDeclaredExampleBundleWithoutRegeneratingItsParameters() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId":"praxis-table",
                  "operations":[
                    {"operationId":"column.renderer.set","inputSchema":{"type":"object"}},
                    {"operationId":"column.conditionalRenderer.add","inputSchema":{"type":"object"}}
                  ],
                  "examples":[
                    {
                      "id":"active-base",
                      "request":"Use chip verde para ativo e vermelho para inativo",
                      "operationId":"column.renderer.set",
                      "target":"ativo",
                      "params":{"type":"chip","chip":{"textField":"ativo","color":"success"}},
                      "isPositive":true
                    },
                    {
                      "id":"inactive-exception",
                      "request":"Use chip verde para ativo e vermelho para inativo",
                      "operationId":"column.conditionalRenderer.add",
                      "target":"ativo",
                      "params":{
                        "id":"chip-inativo",
                        "condition":{"==":[{"var":"ativo"},false]},
                        "renderer":{"type":"chip","chip":{"text":"Inativo","color":"warn"}}
                      },
                      "isPositive":true
                    }
                  ]
                }
                """);
        JsonNode selection = selection(
                "praxis-table",
                "column.renderer.set",
                "column.conditionalRenderer.add");
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("""
                                {"proposedConfig":{"columns":[{"field":"ativo"}]}}
                                """)));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest(
                                "No status, deixa ativo verde e inativo vermelho",
                                "openai",
                                "gpt",
                                "key"),
                        "praxis-table",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        "t",
                        "u",
                        "e");

        assertThat(result.valid()).isTrue();
        assertThat(result.providerInvocations()).isEmpty();
        assertThat(result.warnings()).contains("component-edit-plan-source:manifest-example-bundle");
        assertThat(result.plan().path("operations"))
                .extracting(operation -> operation.path("operationId").asText())
                .containsExactlyInAnyOrder("column.renderer.set", "column.conditionalRenderer.add");
        JsonNode rendererOperation = findOperation(result.plan(), "column.renderer.set");
        JsonNode conditionalOperation = findOperation(result.plan(), "column.conditionalRenderer.add");
        assertThat(rendererOperation.path("target").asText()).isEqualTo("ativo");
        assertThat(rendererOperation.at("/input/chip/color").asText()).isEqualTo("success");
        assertThat(conditionalOperation.at("/input/renderer/chip/color").asText()).isEqualTo("warn");
        verify(providerManagementService, times(1))
                .generateJson(any(), any(), any(), any(), any(), any());
    }

    @Test
    void groundsSemanticFieldRolesFromManifestBundleToCurrentPhysicalColumns() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId":"praxis-table",
                  "operations":[
                    {"operationId":"column.renderer.set","inputSchema":{"type":"object"}},
                    {"operationId":"column.visibility.set","inputSchema":{"type":"object"}}
                  ],
                  "examples":[
                    {
                      "request":"Compor foto e código",
                      "operationId":"column.renderer.set",
                      "target":"codigo",
                      "params":{"type":"compose","compose":{"items":[
                        {"type":"avatar","avatar":{"srcField":"foto","altField":"nome","initialsField":"nome"}},
                        {"type":"value","field":"codigo"}
                      ]}},
                      "isPositive":true
                    },
                    {
                      "request":"Compor foto e código",
                      "operationId":"column.visibility.set",
                      "target":"foto",
                      "params":{"visible":false},
                      "isPositive":true
                    }
                  ]
                }
                """);
        JsonNode currentConfig = objectMapper.readTree("""
                {"columns":[
                  {"field":"id","header":"Código"},
                  {"field":"avatarUrl","header":"Foto"},
                  {"field":"nomeCompleto","header":"Nome Completo"}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection("praxis-table", "column.renderer.set", "column.visibility.set"));
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("{\"proposedConfig\":{}}")));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("Junte foto e código", "openai", "gpt", "key"),
                        "praxis-table", currentConfig, objectMapper.createObjectNode(), "t", "u", "e");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("component-edit-plan-source:manifest-example-bundle");
        JsonNode renderer = findOperation(result.plan(), "column.renderer.set");
        JsonNode visibility = findOperation(result.plan(), "column.visibility.set");
        assertThat(renderer.path("target").asText()).isEqualTo("id");
        assertThat(renderer.at("/input/compose/items/0/avatar/srcField").asText()).isEqualTo("avatarUrl");
        assertThat(renderer.at("/input/compose/items/0/avatar/altField").asText()).isEqualTo("nomeCompleto");
        assertThat(renderer.at("/input/compose/items/0/avatar/initialsField").asText()).isEqualTo("nomeCompleto");
        assertThat(renderer.at("/input/compose/items/1/field").asText()).isEqualTo("id");
        assertThat(visibility.path("target").asText()).isEqualTo("avatarUrl");
        verify(providerManagementService, times(1))
                .generateJson(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotMaterializeOnlyASubsetOfALargerManifestBundle() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId":"praxis-table",
                  "operations":[
                    {"operationId":"column.renderer.set","inputSchema":{"type":"object"}},
                    {"operationId":"column.conditionalRenderer.add","inputSchema":{"type":"object"}},
                    {"operationId":"column.visibility.set","inputSchema":{"type":"object"}}
                  ],
                  "examples":[
                    {
                      "request":"Apresente status e oculte a origem",
                      "operationId":"column.renderer.set",
                      "target":"ativo",
                      "params":{"type":"chip"},
                      "isPositive":true
                    },
                    {
                      "request":"Apresente status e oculte a origem",
                      "operationId":"column.conditionalRenderer.add",
                      "target":"ativo",
                      "params":{"id":"inativo","condition":{"var":"ativo"},"renderer":{"type":"chip"}},
                      "isPositive":true
                    },
                    {
                      "request":"Apresente status e oculte a origem",
                      "operationId":"column.visibility.set",
                      "target":"statusOrigem",
                      "params":{"visible":false},
                      "isPositive":true
                    }
                  ]
                }
                """);
        JsonNode selection = selection(
                "praxis-table",
                "column.renderer.set",
                "column.conditionalRenderer.add");
        JsonNode authoredPlan = objectMapper.readTree("""
                {
                  "schemaVersion":"praxis-component-edit-plan.v1",
                  "componentId":"praxis-table",
                  "operations":[
                    {"operationId":"column.conditionalRenderer.add","target":"ativo","input":{"id":"inativo"}},
                    {"operationId":"column.renderer.set","target":"ativo","input":{"type":"chip"}}
                  ]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection, authoredPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("{\"proposedConfig\":{}}")));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("Ajuste os dois chips", "openai", "gpt", "key"),
                        "praxis-table",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        "t",
                        "u",
                        "e");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain("component-edit-plan-source:manifest-example-bundle");
        assertThat(result.providerInvocations()).hasSize(1);
        verify(providerManagementService, times(2))
                .generateJson(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsRepeatedOperationsWhenTheyOmitASelectedDistinctEffect() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","inputSchema":{"type":"object","properties":{"type":{"type":"string"}}}},
                  {"operationId":"column.visibility.set","inputSchema":{"type":"object","properties":{"visible":{"type":"boolean"}}}}
                ]}
                """);
        JsonNode selection = selection("praxis-table", "column.renderer.set", "column.visibility.set");
        JsonNode duplicatedPlan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","target":"id","input":{"type":"compose"}},
                  {"operationId":"column.renderer.set","target":"id","input":{"type":"compose"}}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection, duplicatedPlan);

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("compor foto e ocultar coluna", "openai", "gpt", "key"),
                        "praxis-table",
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        "t",
                        "u",
                        "e");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("component-edit-plan-operations-outside-semantic-selection");
        verify(manifestService, never()).compilePatch(any(), any());
    }

    @Test
    void bindsManifestConfigSchemaToTheDeclaredWidgetRuntimeInputAndRewrapsTheResult() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "configSchemaId": "TableConfig",
                  "manifestVersion": "2.0.0",
                  "runtimeInputs": [
                    { "name": "config", "type": "TableConfig" },
                    { "name": "resourcePath", "type": "string" }
                  ],
                  "operations": [{
                    "operationId": "column.order.set",
                    "inputSchema": {
                      "type": "object",
                      "required": ["order"],
                      "properties": { "order": { "type": "number" } }
                    },
                    "effects": [{ "kind": "merge-by-key", "path": "columns[]", "key": "field" }],
                    "affectedPaths": ["columns[].order"]
                  }]
                }
                """);
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
        JsonNode widgetInputs = objectMapper.readTree("""
                {
                  "resourcePath": "/api/human-resources/funcionarios",
                  "tableId": "funcionarios-table",
                  "config": {
                    "title": "Funcionários",
                    "columns": [{ "field": "salario", "type": "number" }]
                  }
                }
                """);
        JsonNode compiledPatch = objectMapper.readTree("""
                {
                  "manifestVersion": "2.0.0",
                  "proposedConfig": {
                    "title": "Funcionários",
                    "columns": [{ "field": "salario", "type": "number", "order": 0 }]
                  }
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true, java.util.List.of(), java.util.List.of(), compiledPatch));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(providerManagementService, manifestService, objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest("Mova salário para o início", "openai", "gpt-5", "secret"),
                "praxis-table",
                widgetInputs,
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("component-edit-plan-config-input-bound:config");
        assertThat(result.compiledPatch().at("/proposedConfig/resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.compiledPatch().at("/proposedConfig/tableId").asText())
                .isEqualTo("funcionarios-table");
        assertThat(result.compiledPatch().at("/proposedConfig/config/columns/0/order").asInt()).isZero();

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        assertThat(compileRequest.getValue().config()).isEqualTo(widgetInputs.path("config"));
    }

    @Test
    void emitsAStrictProviderSchemaAndRemovesOnlyCompatibilityNullsBeforeManifestCompilation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [{
                    "operationId": "column.add",
                    "target": { "kind": "column", "resolver": "column-by-field", "required": false },
                    "inputSchema": {
                      "type": "object",
                      "required": ["field", "header"],
                      "properties": {
                        "field": { "type": "string" },
                        "header": { "type": "string" },
                        "type": {
                          "enum": ["string", "number", "date"],
                          "default": "string"
                        }
                      }
                    }
                  }, {
                    "operationId": "column.order.set",
                    "inputSchema": {
                      "type": "object",
                      "required": ["order"],
                      "properties": { "order": { "type": "number" } }
                    }
                  }]
                }
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.add",
                    "input": { "field": "email", "header": "E-mail", "type": null },
                    "target": null,
                    "confirmed": null
                  }]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selectionForPlan(providerPlan), providerPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("""
                                {"manifestVersion":"2.0.0","proposedConfig":{"columns":[{"field":"email"}]}}
                                """)));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(providerManagementService, manifestService, objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Adicione e-mail",
                        "openai",
                        "gpt-5.4-mini",
                        "secret",
                        semanticIntent("column.add")),
                "praxis-table",
                objectMapper.readTree("{\"columns\":[]}"),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        ArgumentCaptor<String> providerPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> providerSchema = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(
                providerPrompt.capture(), providerSchema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(providerPrompt.getValue())
                .contains("\"operationId\":\"column.add\"")
                .doesNotContain("column.order.set")
                .doesNotContain("llmDiagnostics", "currentPageSummary", "providerInvocations");
        JsonNode schema = objectMapper.readTree(providerSchema.getValue().jsonSchema());
        assertStrictObjects(schema);
        assertThat(schema.at("/properties/schemaVersion/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/componentId/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/operations/items/anyOf").isMissingNode()).isTrue();
        assertThat(schema.at("/properties/operations/items/properties/operationId/const").asText())
                .isEqualTo("column.add");
        assertThat(schema.at("/properties/operations/items/properties/input/properties/type/type"))
                .extracting(JsonNode::toString)
                .asString()
                .contains("string", "null");
        assertThat(schema.toString()).doesNotContain("\"default\"");

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        JsonNode canonicalOperation = compileRequest.getValue().plan().path("operations").get(0);
        assertThat(canonicalOperation.has("target")).isFalse();
        assertThat(canonicalOperation.has("confirmed")).isFalse();
        assertThat(canonicalOperation.path("input").has("type")).isFalse();
        assertThat(canonicalOperation.at("/input/field").asText()).isEqualTo("email");
    }

    @Test
    void adaptsFreeFormJsonLogicAndPresenceUnionsForStrictStructuredOutputs() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [{
                    "operationId": "row.styleRule.add",
                    "inputSchema": {
                      "type": "object",
                      "required": ["id", "condition"],
                      "properties": {
                        "id": { "type": "string" },
                        "condition": { "type": "object", "description": "AST Json Logic" },
                        "style": { "type": "object" },
                        "effects": { "type": "array" },
                        "typedEffects": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": {
                              "background": {
                                "type": "object",
                                "properties": { "color": { "type": "string" } }
                              },
                              "repeat": {
                                "oneOf": [
                                  { "enum": ["once", "loop"] },
                                  { "type": "number" }
                                ]
                              }
                            }
                          }
                        }
                      },
                      "anyOf": [
                        { "required": ["style"] },
                        { "required": ["effects"] }
                      ]
                    }
                  }]
                }
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "row.styleRule.add",
                    "input": {
                      "id": "salario-alto-vermelho",
                      "condition": "{\\\">\\\":[{\\\"var\\\":\\\"salario\\\"},30000]}",
                      "style": null,
                      "effects": "[{\\\"background\\\":{\\\"color\\\":\\\"#FDECEC\\\"}}]",
                      "typedEffects": [{
                        "background": { "color": "#FDECEC" },
                        "repeat": null
                      }]
                    },
                    "target": null,
                    "confirmed": null
                  }]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selectionForPlan(providerPlan), providerPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true,
                        java.util.List.of(),
                        java.util.List.of(),
                        objectMapper.readTree("""
                                {"manifestVersion":"2.0.0","proposedConfig":{"rowConditionalStyles":[]}}
                                """)));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(providerManagementService, manifestService, objectMapper);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Destaque de vermelho as linhas com salário acima de 30 mil",
                        "openai",
                        "gpt-5.6-terra",
                        "secret",
                        semanticIntent("row.styleRule.add")),
                "praxis-table",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        ArgumentCaptor<AiJsonSchema> providerSchema = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(
                any(), providerSchema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        JsonNode schema = objectMapper.readTree(providerSchema.getValue().jsonSchema());
        JsonNode input = schema.at("/properties/operations/items/properties/input");
        assertStrictObjects(schema);
        assertThat(input.has("anyOf")).isFalse();
        assertThat(input.at("/properties/condition/type").asText()).isEqualTo("string");
        assertThat(input.at("/properties/condition/description").asText())
                .contains("compact JSON text");
        assertThat(input.at("/properties/effects/type"))
                .extracting(JsonNode::toString)
                .asString()
                .contains("string", "null");
        assertThat(input.at("/properties/effects/description").asText())
                .contains("Canonical array", "compact JSON text");
        assertThat(input.at("/properties/typedEffects/items/properties/repeat/anyOf")).hasSize(2);
        assertThat(schema.toString()).doesNotContain("\"oneOf\"");

        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> compileRequest =
                ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService).compilePatch(eq("praxis-table"), compileRequest.capture());
        JsonNode canonicalInput = compileRequest.getValue().plan().at("/operations/0/input");
        assertThat(canonicalInput.at("/condition/>/0/var").asText()).isEqualTo("salario");
        assertThat(canonicalInput.at("/condition/>/1").asInt()).isEqualTo(30000);
        assertThat(canonicalInput.at("/effects/0/background/color").asText()).isEqualTo("#FDECEC");
        assertThat(canonicalInput.has("style")).isFalse();
    }

    @Test
    void repairsAPlanOnceFromCanonicalManifestValidationDiagnostics() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [{
                    "operationId": "row.styleRule.add",
                    "inputSchema": {
                      "type": "object",
                      "required": ["id", "condition", "cssClass"],
                      "properties": {
                        "id": { "type": "string" },
                        "condition": { "type": "object", "description": "AST Json Logic" },
                        "cssClass": { "type": "string" }
                      }
                    }
                  }]
                }
                """);
        ObjectNode rejectedProviderPlan = objectMapper.createObjectNode();
        rejectedProviderPlan.put("schemaVersion", "praxis-component-edit-plan.v1");
        rejectedProviderPlan.put("componentId", "praxis-table");
        ObjectNode rejectedInput = rejectedProviderPlan.putArray("operations")
                .addObject()
                .put("operationId", "row.styleRule.add")
                .putObject("input");
        rejectedInput.put("id", "salario-alto");
        var conditionArguments = objectMapper.createArrayNode();
        conditionArguments.addObject().put("var", "salario");
        conditionArguments.add(30000);
        ObjectNode rejectedCondition = objectMapper.createObjectNode();
        rejectedCondition.set("> ", conditionArguments);
        rejectedInput.put("condition", rejectedCondition.toString());
        rejectedInput.put("cssClass", "salario-alto");
        ObjectNode repairedProviderPlan = rejectedProviderPlan.deepCopy();
        ObjectNode repairedCondition = objectMapper.createObjectNode();
        repairedCondition.set(">", conditionArguments);
        ((ObjectNode) repairedProviderPlan.at("/operations/0/input"))
                .put("condition", repairedCondition.toString());
        JsonNode compiledPatch = objectMapper.readTree("""
                {
                  "manifestVersion": "2.0.0",
                  "proposedConfig": {
                    "rowConditionalStyles": [{
                      "id": "salario-alto",
                      "condition": { ">": [{ "var": "salario" }, 30000] },
                      "cssClass": "salario-alto"
                    }]
                  }
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(selectionForPlan(rejectedProviderPlan), rejectedProviderPlan, repairedProviderPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(
                        new AgenticAuthoringManifestCompileResult(
                                false,
                                java.util.List.of("validator computed-expression-valid failed for row.styleRule.add: RULE_OPERATOR_UNKNOWN"),
                                java.util.List.of(),
                                objectMapper.createObjectNode()),
                        new AgenticAuthoringManifestCompileResult(
                                true,
                                java.util.List.of(),
                                java.util.List.of(),
                                compiledPatch));
        AgenticAuthoringComponentEditPlanService service =
                new AgenticAuthoringComponentEditPlanService(
                        providerManagementService,
                        manifestService,
                        objectMapper,
                        9);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Destaque salários acima de 30 mil",
                        "openai",
                        "gpt-5.6-terra",
                        "secret",
                        semanticIntent("row.styleRule.add")),
                "praxis-table",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.providerInvocations())
                .extracting(invocation -> invocation.attempt())
                .containsExactly(1, 2);
        assertThat(result.plan().at("/operations/0/input/condition/>/1").asInt()).isEqualTo(30000);
        assertThat(result.warnings()).contains("component-edit-plan-provider:semantic-manifest-repair");
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(providerManagementService, times(3)).generateJson(
                prompts.capture(), any(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(prompts.getAllValues().get(2))
                .contains(
                        "The first plan was rejected by the canonical manifest validators",
                        "RULE_OPERATOR_UNKNOWN",
                        "component-authoring-repair-json");
    }

    @Test
    void preservesCanonicalColumnHeaderOperationAndTargetAcrossTableRefinement() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [
                    {
                      "operationId": "column.header.set",
                      "inputSchema": {
                        "type": "object",
                        "required": ["header"],
                        "properties": { "header": { "type": "string" } }
                      }
                    },
                    {
                      "operationId": "column.type.set",
                      "inputSchema": {
                        "type": "object",
                        "required": ["type"],
                        "properties": { "type": {} }
                      }
                    }
                  ]
                }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.header.set",
                    "input": { "header": "Status" },
                    "target": { "field": "ativo" },
                    "confirmed": null
                  }]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true, java.util.List.of(), java.util.List.of(), objectMapper.createObjectNode()));
        AgenticAuthoringComponentEditPlanService service = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper, 9);

        AgenticAuthoringComponentEditPlanResult result = service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Ativo vira Status.",
                        "openai",
                        "gpt-5.6-terra",
                        "secret",
                        semanticIntent("column.header.set")),
                "praxis-table",
                objectMapper.readTree("""
                        {
                          "columns": [
                            {"field": "ativo", "header": "Ativo"},
                            {"field": "nome", "header": "Nome"}
                          ]
                        }
                        """),
                objectMapper.createObjectNode(),
                "tenant",
                "user",
                "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.plan().path("operations").path(0).path("operationId").asText())
                .isEqualTo("column.header.set");
        assertThat(result.plan().path("operations").path(0).path("target").path("field").asText())
                .isEqualTo("ativo");
        assertThat(result.plan().path("operations").path(0).path("input").path("header").asText())
                .isEqualTo("Status");
        ArgumentCaptor<AiJsonSchema> schema = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(
                any(), schema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(schema.getValue().jsonSchema())
                .contains("column.header.set")
                .doesNotContain("column.type.set");
    }

    @Test
    void mapsAbstractFieldLabelDecisionToTableColumnHeaderOperation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [{
                    "operationId": "column.header.set",
                    "inputSchema": {
                      "type": "object",
                      "required": ["header"],
                      "properties": { "header": { "type": "string" } }
                    }
                  }]
                }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.header.set",
                    "input": { "header": "Status" },
                    "target": { "field": "ativo" },
                    "confirmed": null
                  }]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true, java.util.List.of(), java.util.List.of(), objectMapper.createObjectNode()));
        AgenticAuthoringComponentEditPlanService service = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper, 9);

        assertThat(service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Altere o rótulo do campo", "openai", "gpt-5.6-terra", "secret",
                        semanticIntent("field.label.set")),
                "praxis-table", objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                "tenant", "user", "local").valid()).isTrue();

        ArgumentCaptor<AiJsonSchema> schema = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(
                any(), schema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(schema.getValue().jsonSchema()).contains("column.header.set");
    }

    @Test
    void scopesCanonicalOperationIdWithoutCaseDrift() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "2.0.0",
                  "operations": [
                    {
                      "operationId": "column.conditionalRenderer.add",
                      "inputSchema": {
                        "type": "object",
                        "required": ["id"],
                        "properties": { "id": { "type": "string" } }
                      }
                    },
                    {
                      "operationId": "column.type.set",
                      "inputSchema": { "type": "object", "properties": { "type": {} } }
                    }
                  ]
                }
                """);
        JsonNode plan = objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-component-edit-plan.v1",
                  "componentId": "praxis-table",
                  "operations": [{
                    "operationId": "column.conditionalRenderer.add",
                    "input": { "id": "inactive-status" },
                    "target": { "field": "ativo" },
                    "confirmed": null
                  }]
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(
                        true, java.util.List.of(), java.util.List.of(), objectMapper.createObjectNode()));
        AgenticAuthoringComponentEditPlanService service = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper, 9);

        assertThat(service.generateAndCompile(
                new AgenticAuthoringPlanRequest(
                        "Use vermelho para inativos", "openai", "gpt-5.6-terra", "secret",
                        semanticIntent("column.conditionalrenderer.add")),
                "praxis-table", objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                "tenant", "user", "local").valid()).isTrue();

        ArgumentCaptor<AiJsonSchema> schema = ArgumentCaptor.forClass(AiJsonSchema.class);
        verify(providerManagementService, times(2)).generateJson(
                any(), schema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(schema.getValue().jsonSchema())
                .contains("column.conditionalRenderer.add")
                .doesNotContain("column.type.set");
    }

    private AgenticAuthoringIntentResolutionResult semanticIntent(String changeKind) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                changeKind,
                "semantic-manifest",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget("funcionarios-table", "praxis-table", "", "", "", ""),
                null,
                java.util.List.of(),
                new AgenticAuthoringGateResult("component-edit", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                objectMapper.createObjectNode());
    }

    @Test
    void compilesThePhotoAndCodeReferencePlanInSelectedOrderWithoutChangingUnrelatedState() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","inputSchema":{"type":"object","properties":{"renderer":{"type":"string"}}}},
                  {"operationId":"column.visibility.set","inputSchema":{"type":"object","properties":{"visible":{"type":"boolean"}}}}
                ]}
                """);
        JsonNode plan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[
                  {"operationId":"column.renderer.set","target":{"value":"codigo"},"input":{"renderer":"compose"}},
                  {"operationId":"column.visibility.set","target":{"value":"foto"},"input":{"visible":false}}
                ]}
                """);
        JsonNode preservedConfig = objectMapper.readTree("""
                {"columns":[{"field":"codigo"},{"field":"foto"}],"pageSize":25,"density":"compact"}
                """);
        JsonNode compiled = objectMapper.readTree("""
                {"proposedConfig":{"columns":[
                  {"field":"codigo","renderer":{"kind":"compose","layout":"row","imageField":"foto","textField":"codigo","imageSize":"small","imageShape":"circle"}},
                  {"field":"foto","visible":false}],"pageSize":25,"density":"compact"}}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selectionForPlan(plan), plan);
        when(manifestService.compilePatch(eq("praxis-table"), any()))
                .thenReturn(new AgenticAuthoringManifestCompileResult(true, java.util.List.of(), java.util.List.of(), compiled));
        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("Coloque a foto junto com o código", "openai", "gpt", "key"),
                        "praxis-table", preservedConfig, objectMapper.createObjectNode(), "t", "u", "e");
        assertThat(result.valid()).isTrue();
        assertThat(result.plan().path("operations")).extracting(operation -> operation.path("operationId").asText())
                .containsExactly("column.renderer.set", "column.visibility.set");
        assertThat(result.compiledPatch().at("/proposedConfig/columns/0/renderer/layout").asText()).isEqualTo("row");
        assertThat(result.compiledPatch().at("/proposedConfig/columns/0/renderer/imageShape").asText()).isEqualTo("circle");
        assertThat(result.compiledPatch().at("/proposedConfig/columns/1/visible").asBoolean()).isFalse();
        assertThat(result.compiledPatch().at("/proposedConfig/pageSize").asInt()).isEqualTo(25);
        assertThat(result.compiledPatch().at("/proposedConfig/density").asText()).isEqualTo("compact");
    }

    @Test
    void blocksASelectedRemovalAndMutationBeforeGeneratingOrCompilingThePlan() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","manifestVersion":"compatibility-test","operations":[
                  {"operationId":"column.remove","target":{"kind":"column"},"effects":[{"kind":"remove-by-key","path":"columns[]"}],"affectedPaths":["columns[]"],"preconditions":["target-exists"]},
                  {"operationId":"column.format.set","target":{"kind":"column"},"effects":[{"kind":"merge-by-key","path":"columns[].format"}],"affectedPaths":["columns[].format"],"preconditions":["target-exists"]}
                ]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), any(), any(), any()))
                .thenReturn(selection("praxis-table", "column.remove", "column.format.set"));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                new AgenticAuthoringPlanRequest("remova e formate a mesma coluna", "openai", "gpt", "key"),
                "praxis-table", objectMapper.createObjectNode(), objectMapper.createObjectNode(), "t", "u", "e");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("component-operation-selection-clarification-required");
        assertThat(result.warnings()).singleElement().asString().contains("component-operation-compatibility-conflict");
        verify(providerManagementService, times(1)).generateJson(any(), any(), any(), any(), any(), any());
        verify(manifestService, never()).compilePatch(any(), any());
    }

    private JsonNode selectionForPlan(JsonNode plan) {
        var ids = new java.util.ArrayList<String>();
        plan.path("operations").forEach(operation -> ids.add(operation.path("operationId").asText()));
        return selection(plan.path("componentId").asText(), ids.toArray(String[]::new));
    }

    private JsonNode selection(String componentId, String... operationIds) {
        ObjectNode selection = objectMapper.createObjectNode();
        selection.put("schemaVersion", AgenticAuthoringComponentOperationSelectionService.SCHEMA_VERSION);
        selection.put("componentId", componentId);
        var goals = selection.putArray("goals");
        for (String operationId : operationIds) {
            var goal = goals.addObject();
            goal.put("description", "Materialize " + operationId);
            goal.put("targetConcept", componentId);
            goal.putArray("operationIds").add(operationId);
        }
        selection.put("requiresClarification", false);
        selection.put("clarificationReason", "");
        var selected = selection.putArray("selectedOperationIds");
        for (String operationId : operationIds) selected.add(operationId);
        return selection;
    }

    private JsonNode findOperation(JsonNode plan, String operationId) {
        for (JsonNode operation : plan.path("operations")) {
            if (operationId.equals(operation.path("operationId").asText())) {
                return operation;
            }
        }
        throw new AssertionError("Operation not found: " + operationId);
    }

    private void assertStrictObjects(JsonNode schema) {
        if (schema.isObject()) {
            JsonNode type = schema.path("type");
            boolean declaresObject = "object".equals(type.asText());
            if (type.isArray()) {
                for (JsonNode value : type) {
                    declaresObject |= "object".equals(value.asText());
                }
            }
            if (declaresObject) {
                assertThat(schema.path("properties").isObject()).as(schema.toString()).isTrue();
                assertThat(schema.path("additionalProperties").isBoolean()).as(schema.toString()).isTrue();
                assertThat(schema.path("additionalProperties").asBoolean()).as(schema.toString()).isFalse();
                Set<String> properties = new LinkedHashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(properties::add);
                Set<String> required = new LinkedHashSet<>();
                schema.path("required").forEach(value -> required.add(value.asText()));
                assertThat(required).as(schema.toString()).containsExactlyInAnyOrderElementsOf(properties);
            }
            schema.forEach(this::assertStrictObjects);
        } else if (schema.isArray()) {
            schema.forEach(this::assertStrictObjects);
        }
    }
}
