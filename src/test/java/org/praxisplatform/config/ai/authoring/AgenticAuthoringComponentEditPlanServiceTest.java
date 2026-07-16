package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                any(), any(), any(), eq("tenant"), eq("user"), eq("local"))).thenReturn(plan);
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
        verify(providerManagementService).generateJson(
                prompt.capture(), schema.capture(), callConfig.capture(), eq("tenant"), eq("user"), eq("local"));
        assertThat(prompt.getValue())
                .contains("Never route intent by keywords or regex")
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
                .thenReturn(objectMapper.readTree("""
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
                .thenReturn(plan);
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
                .thenReturn(providerPlan);
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
        verify(providerManagementService).generateJson(
                providerPrompt.capture(), providerSchema.capture(), any(), eq("tenant"), eq("user"), eq("local"));
        assertThat(providerPrompt.getValue())
                .contains("\"operationId\":\"column.add\"")
                .doesNotContain("column.order.set")
                .doesNotContain("llmDiagnostics", "currentPageSummary", "providerInvocations");
        JsonNode schema = objectMapper.readTree(providerSchema.getValue().jsonSchema());
        assertStrictObjects(schema);
        assertThat(schema.at("/properties/schemaVersion/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/componentId/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/operations/items/anyOf")).hasSize(1);
        assertThat(schema.at("/properties/operations/items/anyOf/0/properties/operationId/const").asText())
                .isEqualTo("column.add");
        assertThat(schema.at("/properties/operations/items/anyOf/0/properties/input/properties/type/type"))
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
