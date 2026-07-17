package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringUiCompositionPlanCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringUiCompositionPlanCompiler compiler =
            new AgenticAuthoringUiCompositionPlanCompiler(objectMapper);

    @Test
    void compilesCanonicalPlanIntoTerminalPagePatch() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "layoutPreset": "analytics-dashboard",
                  "widgets": [
                    {
                      "key": "comparison-chart",
                      "componentId": "praxis-chart",
                      "inputs": { "title": "Afastamentos por departamento" }
                    },
                    {
                      "key": "critical-employees",
                      "componentId": "praxis-table",
                      "inputs": { "resourcePath": "/employees" },
                      "outputs": { "selectionChange": "emit" }
                    }
                  ],
                  "bindings": [
                    {
                      "id": "department-cross-filter",
                      "from": {
                        "kind": "component-port",
                        "widget": "comparison-chart",
                        "port": "selectionChange",
                        "direction": "output"
                      },
                      "to": {
                        "kind": "component-port",
                        "widget": "critical-employees",
                        "port": "queryContext",
                        "direction": "input"
                      },
                      "intent": "data-projection",
                      "transform": {
                        "kind": "query-context",
                        "id": "department-key-filter",
                        "field": "departmentId",
                        "valueVar": "payload.key"
                      }
                    },
                    {
                      "id": "employee-360-open",
                      "from": {
                        "kind": "component-port",
                        "widget": "critical-employees",
                        "port": "rowActivated",
                        "direction": "output"
                      },
                      "to": {
                        "kind": "global-action",
                        "actionId": "surface.open",
                        "payloadExpr": "payload.employeeId"
                      },
                      "intent": "command-dispatch",
                      "transform": {
                        "kind": "pick-path",
                        "id": "employee-id",
                        "path": "employeeId"
                      }
                    }
                  ]
                }
                """);
        ObjectNode basePatch = objectMapper.createObjectNode();
        basePatch.put("profileId", "ui-composition-plan");
        basePatch.putArray("warnings").add("compiled-form-patch-materialized-by-page-builder");

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result = compiler.compile(plan, basePatch);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        JsonNode compiled = result.compiledFormPatch();
        JsonNode page = compiled.path("patch").path("page");
        assertThat(page.path("widgets")).hasSize(2);
        assertThat(page.at("/widgets/0/definition/id").asText()).isEqualTo("praxis-chart");
        assertThat(page.at("/widgets/0/definition/outputs/selectionChange").asText()).isEqualTo("emit");
        assertThat(page.at("/widgets/1/definition/outputs/selectionChange").asText()).isEqualTo("emit");
        assertThat(page.at("/canvas/items/comparison-chart/rowSpan").asInt()).isEqualTo(5);
        assertThat(page.at("/canvas/items/critical-employees/row").asInt()).isEqualTo(6);
        assertThat(page.at("/composition/links/0/to/ref/widget").asText()).isEqualTo("critical-employees");
        assertThat(page.at("/composition/links/0/transform/mode").asText()).isEqualTo("object-fragment");
        assertThat(page.at("/composition/links/0/transform/steps/0/kind").asText()).isEqualTo("object-template");
        assertThat(page.at("/composition/links/0/transform/steps/0/config/template/filters/departmentId").asText())
                .isEqualTo("${payload.key}");
        assertThat(page.at("/composition/links/0/transform/steps/0/output/semanticKind").asText())
                .isEqualTo("query-context");
        assertThat(page.at("/composition/links/0/transform/output/stableShape").asBoolean()).isTrue();
        assertThat(page.at("/composition/links/1/to/ref/actionId").asText()).isEqualTo("surface.open");
        assertThat(page.at("/composition/links/1/transform/steps/0/config/path").asText())
                .isEqualTo("employeeId");
        assertThat(compiled.path("warnings")).extracting(JsonNode::asText)
                .containsExactly("ui-composition-plan-compiled-by-config");
    }

    @Test
    void rejectsPlanThatCannotBeAttestedAsCanonicalPagePatch() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [{ "key": "chart", "componentId": "praxis-chart" }]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("ui-composition-plan-version-invalid");
        assertThat(result.compiledFormPatch()).isEmpty();
    }

    @Test
    void rejectsCanonicalCrossSurfaceReferenceViolations() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    { "key": "chart", "componentId": "praxis-chart" }
                  ],
                  "canvas": {
                    "items": { "missing-canvas-widget": {} }
                  },
                  "grouping": [
                    { "kind": "section", "id": "group", "widgetKeys": ["missing-group-widget"] },
                    { "kind": "tabs", "id": "group", "tabs": [] },
                    { "kind": "section", "id": "", "widgetKeys": [] }
                  ],
                  "deviceLayouts": {
                    "mobile": {
                      "canvas": { "items": { "missing-device-widget": {} } },
                      "widgetOverrides": { "missing-override-widget": {} },
                      "groupingOverrides": [
                        {
                          "widgetKeys": ["missing-device-group-widget"],
                          "tabs": [{ "widgetKeys": ["missing-device-tab-widget"] }]
                        }
                      ]
                    }
                  },
                  "slotAssignments": {
                    "missing-slot-widget": "rail",
                    "chart": ""
                  },
                  "bindings": [
                    {
                      "id": "nested-invalid",
                      "from": {
                        "kind": "component-port",
                        "widget": "chart",
                        "port": "selectionChange",
                        "direction": "output",
                        "nestedPath": [{ "kind": "widget", "componentType": "praxis-table" }]
                      },
                      "to": {
                        "kind": "global-action",
                        "actionId": "surface.open",
                        "payloadExpr": 42
                      }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-canvas-widget-not-found",
                "ui-composition-plan-grouping-widget-not-found",
                "ui-composition-plan-group-id-duplicated",
                "ui-composition-plan-group-id-required",
                "ui-composition-plan-device-layout-widget-not-found",
                "ui-composition-plan-device-layout-widget-override-not-found",
                "ui-composition-plan-device-layout-grouping-widget-not-found",
                "ui-composition-plan-slot-assignment-widget-not-found",
                "ui-composition-plan-slot-assignment-slot-required",
                "ui-composition-plan-endpoint-nested-terminal-widget-key-required",
                "ui-composition-plan-endpoint-global-action-payload-expr-invalid");
        assertThat(result.compiledFormPatch()).isEmpty();
    }

    @Test
    void rejectsNonTextualEndpointIdentityBeforeItCanBeCoercedIntoTheCompiledPatch() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    { "key": "42", "componentId": "praxis-chart", "inputs": {} }
                  ],
                  "bindings": [
                    {
                      "id": "numeric-component-endpoint",
                      "intent": "state-write",
                      "from": {
                        "kind": "component-port",
                        "widget": 42,
                        "port": 42,
                        "direction": "output"
                      },
                      "to": { "kind": "state", "path": 42 }
                    },
                    {
                      "id": "numeric-global-action-endpoint",
                      "intent": "command-dispatch",
                      "from": {
                        "kind": "component-port",
                        "widget": "42",
                        "port": "selectionChange",
                        "direction": "output"
                      },
                      "to": { "kind": "global-action", "actionId": 42 }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-endpoint-widget-not-found",
                "ui-composition-plan-endpoint-port-required",
                "ui-composition-plan-endpoint-state-path-required",
                "ui-composition-plan-endpoint-global-action-id-required");
        assertThat(result.compiledFormPatch()).isEmpty();
    }

    @Test
    void compilesStructuredTemplatesWithTheCanonicalTransformKindsAndModes() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    { "key": "source", "componentId": "praxis-list" },
                    { "key": "target", "componentId": "praxis-table" }
                  ],
                  "bindings": [
                    {
                      "id": "array-template",
                      "from": { "kind": "component-port", "widget": "source", "port": "selection", "direction": "output" },
                      "to": { "kind": "component-port", "widget": "target", "port": "items", "direction": "input" },
                      "intent": "data-projection",
                      "transform": { "kind": "template", "id": "array", "template": ["${payload.id}"] }
                    },
                    {
                      "id": "object-template",
                      "from": { "kind": "component-port", "widget": "source", "port": "selection", "direction": "output" },
                      "to": { "kind": "component-port", "widget": "target", "port": "query", "direction": "input" },
                      "intent": "data-projection",
                      "transform": { "kind": "template", "id": "object", "template": { "id": "${payload.id}" } }
                    },
                    {
                      "id": "scalar-template",
                      "from": { "kind": "component-port", "widget": "source", "port": "selection", "direction": "output" },
                      "to": { "kind": "state", "path": "selectedId" },
                      "intent": "state-write",
                      "transform": { "kind": "template", "id": "scalar", "template": "${payload.id}" }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isTrue();
        JsonNode links = result.compiledFormPatch().at("/patch/page/composition/links");
        assertThat(links.at("/0/transform/mode").asText()).isEqualTo("collection");
        assertThat(links.at("/0/transform/steps/0/kind").asText()).isEqualTo("array-template");
        assertThat(links.at("/1/transform/mode").asText()).isEqualTo("object-fragment");
        assertThat(links.at("/1/transform/steps/0/kind").asText()).isEqualTo("object-template");
        assertThat(links.at("/2/transform/mode").asText()).isEqualTo("single-value");
        assertThat(links.at("/2/transform/steps/0/kind").asText()).isEqualTo("template");
    }

    @Test
    void rejectsUnknownOrIncompleteTransformsInsteadOfFallingBackToPickPath() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    { "key": "source", "componentId": "praxis-list" },
                    { "key": "target", "componentId": "praxis-table" }
                  ],
                  "bindings": [
                    {
                      "id": "unknown-transform",
                      "from": { "kind": "component-port", "widget": "source", "port": "selection", "direction": "output" },
                      "to": { "kind": "component-port", "widget": "target", "port": "query", "direction": "input" },
                      "intent": "data-projection",
                      "transform": { "kind": "invented", "id": "unknown" }
                    },
                    {
                      "id": "incomplete-transform",
                      "from": { "kind": "component-port", "widget": "source", "port": "selection", "direction": "output" },
                      "to": { "kind": "state", "path": "selected" },
                      "intent": "state-write",
                      "transform": { "kind": "pick-path", "id": "", "path": "", "inputSource": "invented" }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-transform-kind-unsupported",
                "ui-composition-plan-transform-id-required",
                "ui-composition-plan-transform-path-required",
                "ui-composition-plan-transform-input-source-unsupported");
        assertThat(result.compiledFormPatch()).isEmpty();
    }

    @Test
    void rejectsInvalidTopLevelShapesAndBackendOwnedWidgetContracts() throws Exception {
        JsonNode invalidPlan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": { "chart": { "key": "chart", "componentId": "praxis-chart" } },
                  "bindings": {},
                  "canvas": [],
                  "grouping": {},
                  "deviceLayouts": [],
                  "slotAssignments": [],
                  "state": []
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(invalidPlan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-widgets-array-required",
                "ui-composition-plan-bindings-array-required",
                "ui-composition-plan-canvas-object-required",
                "ui-composition-plan-grouping-array-required",
                "ui-composition-plan-device-layouts-object-required",
                "ui-composition-plan-slot-assignments-object-required",
                "ui-composition-plan-state-object-required");

        JsonNode invalidPatch = objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "widgets": [
                        { "key": "missing-definition" },
                        { "key": "missing-definition" }
                      ]
                    }
                  }
                }
                """);
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(invalidPatch))
                .isEqualTo("compiled-page-widget-definition-required");

        JsonNode coerciveIdentifiers = objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "widgets": [
                        { "key": 42, "definition": { "id": "praxis-chart" } }
                      ]
                    }
                  }
                }
                """);
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(coerciveIdentifiers))
                .isEqualTo("compiled-page-widget-key-required");

        JsonNode numericComponentId = objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "widgets": [
                        { "key": "chart", "definition": { "id": 42 } }
                      ]
                    }
                  }
                }
                """);
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(numericComponentId))
                .isEqualTo("compiled-page-widget-component-id-required");

        JsonNode invalidDeviceLayout = objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "deviceLayouts": { "mobile": 42 },
                      "widgets": [
                        { "key": "chart", "definition": { "id": "praxis-chart" } }
                      ]
                    }
                  }
                }
                """);
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(invalidDeviceLayout))
                .isEqualTo("compiled-page-device-layout-variant-object-required");

        JsonNode invalidSlotAssignment = objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "slotAssignments": { "chart": 42 },
                      "widgets": [
                        { "key": "chart", "definition": { "id": "praxis-chart" } }
                      ]
                    }
                  }
                }
                """);
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(invalidSlotAssignment))
                .isEqualTo("compiled-page-slot-assignment-invalid");
    }
}
