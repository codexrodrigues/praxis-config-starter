package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringUiCompositionPlanCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringUiCompositionPlanCompiler compiler =
            new AgenticAuthoringUiCompositionPlanCompiler(objectMapper);

    @Test
    void compilesCertifiedBusinessCommandRuntimeFixtureWithoutAiProvider() throws Exception {
        Path fixture = Path.of(
                "tools",
                "e2e",
                "fixtures",
                "business-command-runtime-excellence.ui-composition-plan.json");
        assertThat(Files.isRegularFile(fixture)).isTrue();

        JsonNode plan = objectMapper.readTree(Files.readString(fixture));
        assertThat(canonicalSha256(plan))
                .as("the certified semantic plan identity must be platform-independent")
                .isEqualTo("694dba6cb77d9f243754dc7c48d82c8f3ae12313c70cbfd7729c6b6943f4e9d8");
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        assertThat(result.failureCodes()).isEmpty();
        JsonNode page = result.compiledFormPatch().at("/patch/page");
        assertThat(page.path("layoutPreset").asText()).isEqualTo("master-detail-dashboard");
        assertThat(page.path("widgets")).hasSize(2);
        assertThat(page.at("/widgets/0/key").asText()).isEqualTo("funcionarios-master");
        assertThat(page.at("/widgets/0/definition/id").asText()).isEqualTo("praxis-table");
        assertThat(page.at("/widgets/0/definition/inputs/resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(page.at("/widgets/0/definition/inputs/config/actions/row/discovery/enabled").asBoolean())
                .isTrue();
        assertThat(page.at("/widgets/1/definition/id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(page.path("composition").path("links")).hasSize(2);
        assertThat(page.at("/composition/links/0/intent").asText()).isEqualTo("state-write");
        assertThat(page.at("/composition/links/1/intent").asText()).isEqualTo("state-read");
        assertThat(canonicalSha256(page))
                .as("the Java materialization must equal the TypeScript compiler projection")
                .isEqualTo("8c5742a203354a30724c3614cdd748a682c8a953c6829e2f98312b2289c9af31");
        assertThat(result.compiledFormPatch().path("warnings")).extracting(JsonNode::asText)
                .containsExactly("ui-composition-plan-compiled-by-config");
    }

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

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        assertThat(result.failureCodes()).isEmpty();
        JsonNode compiled = result.compiledFormPatch();
        JsonNode page = compiled.path("patch").path("page");
        assertThat(page.path("widgets")).hasSize(2);
        assertThat(page.at("/widgets/0/definition/id").asText()).isEqualTo("praxis-chart");
        assertThat(page.at("/widgets/0/definition/outputs/selectionChange").asText()).isEqualTo("emit");
        assertThat(page.at("/widgets/1/definition/outputs/selectionChange").asText()).isEqualTo("emit");
        assertThat(page.at("/canvas/items/comparison-chart/rowSpan").asInt()).isEqualTo(4);
        assertThat(page.at("/canvas/items/critical-employees/row").asInt()).isEqualTo(5);
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
    void compilesGlobalActionSourcesToCanonicalStateAndComponentTargets() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "state": { "values": { "decisionResult": null } },
                  "widgets": [
                    { "key": "decision-summary", "componentId": "praxis-rich-content" }
                  ],
                  "bindings": [
                    {
                      "id": "peopleOps.decision.accepted->state.decisionResult",
                      "from": {
                        "kind": "global-action",
                        "actionId": "peopleOps.decision.accepted"
                      },
                      "to": { "kind": "state", "path": "decisionResult" },
                      "intent": "state-write"
                    },
                    {
                      "id": "peopleOps.decision.accepted->decision-summary.context",
                      "from": {
                        "kind": "global-action",
                        "actionId": "peopleOps.decision.accepted"
                      },
                      "to": {
                        "kind": "component-port",
                        "widget": "decision-summary",
                        "port": "context",
                        "direction": "input"
                      },
                      "intent": "event-propagation"
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        JsonNode links = result.compiledFormPatch().at("/patch/page/composition/links");
        assertThat(links).hasSize(2);
        assertThat(links.at("/0/from")).isEqualTo(objectMapper.readTree("""
                { "kind": "global-action", "ref": { "actionId": "peopleOps.decision.accepted" } }
                """));
        assertThat(links.at("/0/to/ref/path").asText()).isEqualTo("decisionResult");
        assertThat(links.at("/1/from")).isEqualTo(links.at("/0/from"));
        assertThat(links.at("/1/to/ref/widget").asText()).isEqualTo("decision-summary");
        assertThat(links.at("/1/to/ref/port").asText()).isEqualTo("context");
    }

    @Test
    void rejectsNonExecutableGlobalActionSourceFieldsAndRequiresTextualActionId() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [],
                  "bindings": [
                    {
                      "id": "invalid-global-action-source",
                      "from": {
                        "kind": "global-action",
                        "actionId": 42,
                        "payload": { "decisionId": 42 },
                        "label": "Decision accepted",
                        "resultType": "decision-room-accepted"
                      },
                      "to": { "kind": "state", "path": "decisionResult" },
                      "intent": "state-write"
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-endpoint-global-action-id-required",
                "ui-composition-plan-endpoint-global-action-source-field-unsupported");
        assertThat(result.compiledFormPatch()).isEmpty();
    }

    @Test
    void materializesKeyOnlyAuthoringCopyFromFallbackDictionary() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "i18n": {
                    "fallbackLocale": "pt-BR",
                    "dictionaries": {
                      "pt-BR": { "employee.title": "Gestão de pessoas" },
                      "en-US": { "employee.title": "People operations" }
                    }
                  },
                  "widgets": [
                    {
                      "key": "employee-portfolio",
                      "componentId": "praxis-crud",
                      "shell": { "title": { "key": "employee.title" } },
                      "inputs": {
                        "metadata": {
                          "toolbar": { "title": { "key": "employee.title" } }
                        }
                      }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        JsonNode page = result.compiledFormPatch().path("patch").path("page");
        assertThat(page.at("/widgets/0/shell/title/text").asText())
                .isEqualTo("Gestão de pessoas");
        assertThat(page.at("/widgets/0/definition/inputs/metadata/toolbar/title/text").asText())
                .isEqualTo("Gestão de pessoas");
        assertThat(plan.at("/widgets/0/shell/title").has("text")).isFalse();
    }

    @Test
    void compilesSelectionSyncsAndInheritedContextWithPageBuilderParity() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "i18n": {
                    "locale": "pt-BR",
                    "messages": { "employee.title": "Gestão de pessoas" }
                  },
                  "context": { "tenantId": "demo-enterprise" },
                  "layout": { "columns": 12, "gap": "16px" },
                  "state": {
                    "values": {
                      "selection": {
                        "employeeId": null,
                        "employeeIdentity": null,
                        "employee": null
                      }
                    }
                  },
                  "widgets": [
                    {
                      "key": "employee-portfolio",
                      "componentId": "praxis-crud",
                      "shell": {
                        "kind": "dashboard-card",
                        "title": { "key": "employee.title", "text": "Gestão de pessoas" }
                      }
                    },
                    {
                      "key": "employee-tabs",
                      "componentId": "praxis-tabs",
                      "inputs": {
                        "config": {
                          "tabs": [
                            {
                              "id": "payroll",
                              "widgets": [
                                {
                                  "id": "praxis-related-resource-outlet",
                                  "childWidgetKey": "employee-payroll",
                                  "inputs": { "surfaceId": "payroll-history" }
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                  ],
                  "selectionSyncs": [
                    {
                      "id": "employee-selection",
                      "intent": "selection-sync",
                      "sources": [
                        {
                          "kind": "component-port",
                          "widget": "employee-portfolio",
                          "port": "rowClick",
                          "direction": "output"
                        },
                        {
                          "kind": "component-port",
                          "widget": "employee-portfolio",
                          "port": "rowAction",
                          "direction": "output"
                        }
                      ],
                      "target": { "kind": "state", "path": "selection.", "layer": "values" },
                      "mapping": {
                        "employeeId": {
                          "path": "payload.row.id",
                          "condition": { ">": [{ "var": "payload.row.id" }, 0] }
                        },
                        "employeeIdentity": "payload.resourceIdentity",
                        "employee": "payload.row"
                      },
                      "policy": { "missingValuePolicy": "skip", "distinct": true },
                      "metadata": { "source": "ui-composition-plan" }
                    }
                  ],
                  "contextScopes": [
                    {
                      "id": "employee-dossier",
                      "context": {
                        "parentResourcePath": {
                          "kind": "constant",
                          "value": "human-resources/funcionarios"
                        },
                        "parentResourceId": {
                          "kind": "state",
                          "path": "selection.employeeId",
                          "layer": "values",
                          "initial": null
                        },
                        "parentIdentity": {
                          "kind": "state",
                          "path": "selection.employeeIdentity",
                          "layer": "values",
                          "condition": { "!!": { "var": "selection.employeeIdentity" } }
                        }
                      },
                      "targets": [
                        {
                          "widget": "employee-portfolio",
                          "inherit": ["parentResourcePath"]
                        },
                        {
                          "widget": "employee-tabs",
                          "nestedPath": [
                            { "kind": "tab", "id": "payroll", "index": 0 },
                            {
                              "kind": "widget",
                              "key": "employee-payroll",
                              "componentType": "praxis-related-resource-outlet"
                            }
                          ]
                        }
                      ],
                      "policy": { "missingValuePolicy": "skip", "distinct": true },
                      "metadata": { "source": "ui-composition-plan" }
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        assertThat(result.failureCodes()).isEmpty();
        JsonNode page = result.compiledFormPatch().at("/patch/page");
        assertThat(page.at("/i18n/locale").asText()).isEqualTo("pt-BR");
        assertThat(page.at("/context/tenantId").asText()).isEqualTo("demo-enterprise");
        assertThat(page.at("/layout/columns").asInt()).isEqualTo(12);
        assertThat(page.path("canvas").isMissingNode()).isTrue();
        assertThat(page.at("/widgets/0/shell/kind").asText()).isEqualTo("dashboard-card");
        assertThat(page.at("/widgets/0/definition/outputs/rowClick").asText()).isEqualTo("emit");
        assertThat(page.at("/widgets/0/definition/outputs/rowAction").asText()).isEqualTo("emit");
        assertThat(page.at("/widgets/0/definition/inputs/parentResourcePath").asText())
                .isEqualTo("human-resources/funcionarios");
        assertThat(page.at(
                        "/widgets/1/definition/inputs/config/tabs/0/widgets/0/inputs/parentResourcePath")
                .asText())
                .isEqualTo("human-resources/funcionarios");
        assertThat(page.at(
                        "/widgets/1/definition/inputs/config/tabs/0/widgets/0/inputs/parentResourceId")
                .isNull())
                .isTrue();
        assertThat(plan.at(
                        "/widgets/1/inputs/config/tabs/0/widgets/0/inputs/parentResourceId")
                .isMissingNode())
                .isTrue();

        JsonNode links = page.at("/composition/links");
        assertThat(links).hasSize(8);
        assertThat(links.path(0).path("id").asText())
                .isEqualTo("employee-selection:employee-portfolio.rowClick->state.selection.employeeId");
        assertThat(links.at("/0/to/ref/path").asText()).isEqualTo("selection.employeeId");
        assertThat(links.at("/0/to/ref/layer").asText()).isEqualTo("values");
        assertThat(links.at("/0/condition/>/0/var").asText()).isEqualTo("payload.row.id");
        assertThat(links.at("/0/transform/steps/0/config/path").asText()).isEqualTo("payload.row.id");
        assertThat(links.at("/0/policy/missingValuePolicy").asText()).isEqualTo("skip");
        assertThat(links.path(6).path("id").asText())
                .isEqualTo(
                        "employee-dossier:state.selection.employeeId->employee-tabs.parentResourceId#employee-payroll");
        assertThat(links.at("/6/from/ref/layer").asText()).isEqualTo("values");
        assertThat(links.at("/6/to/ref/nestedPath/0/id").asText()).isEqualTo("payroll");
        assertThat(links.at("/7/condition/!!/var").asText()).isEqualTo("selection.employeeIdentity");
    }

    @Test
    void materializesContextConstantsInsideNavigationLinksAndExpansionPanels() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    {
                      "key": "employee-navigation",
                      "componentId": "praxis-tabs",
                      "inputs": {
                        "config": {
                          "nav": {
                            "links": [
                              {
                                "id": "career-link",
                                "widgets": [
                                  {
                                    "id": "praxis-related-resource-outlet",
                                    "childWidgetKey": "employee-career",
                                    "inputs": { "surfaceId": "career" }
                                  }
                                ]
                              }
                            ]
                          }
                        }
                      }
                    },
                    {
                      "key": "employee-expansion",
                      "componentId": "praxis-expansion",
                      "inputs": {
                        "config": {
                          "panels": [
                            {
                              "id": "equipment-panel",
                              "widgets": [
                                {
                                  "id": "praxis-related-resource-outlet",
                                  "childWidgetKey": "employee-equipment",
                                  "inputs": { "surfaceId": "equipment" }
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                  ],
                  "contextScopes": [
                    {
                      "id": "employee-dossier",
                      "context": {
                        "parentResourcePath": {
                          "kind": "constant",
                          "value": "human-resources/funcionarios"
                        }
                      },
                      "targets": [
                        {
                          "widget": "employee-navigation",
                          "nestedPath": [
                            { "kind": "nav" },
                            { "kind": "link", "id": "career-link", "index": 0 },
                            { "kind": "widget", "key": "employee-career" }
                          ]
                        },
                        {
                          "widget": "employee-expansion",
                          "nestedPath": [
                            { "kind": "panel", "id": "equipment-panel", "index": 0 },
                            { "kind": "widget", "key": "employee-equipment" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid())
                .withFailMessage("Compilation failures: %s", result.failureCodes())
                .isTrue();
        JsonNode page = result.compiledFormPatch().at("/patch/page");
        assertThat(page.at(
                        "/widgets/0/definition/inputs/config/nav/links/0/widgets/0/inputs/parentResourcePath")
                .asText())
                .isEqualTo("human-resources/funcionarios");
        assertThat(page.at(
                        "/widgets/1/definition/inputs/config/panels/0/widgets/0/inputs/parentResourcePath")
                .asText())
                .isEqualTo("human-resources/funcionarios");
        assertThat(page.at("/composition/links")).isEmpty();
    }

    @Test
    void rejectsInvalidSelectionSyncsAndContextScopesBeforeMaterialization() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "widgets": [
                    {
                      "key": "employee-tabs",
                      "componentId": "praxis-tabs",
                      "inputs": { "config": { "tabs": [] } }
                    }
                  ],
                  "selectionSyncs": [
                    {
                      "id": "employee-selection",
                      "intent": "selection-sync",
                      "sources": [
                        {
                          "kind": "component-port",
                          "widget": "missing-widget",
                          "port": "rowClick",
                          "direction": "input"
                        },
                        { "kind": "state", "path": "selection.employeeId" }
                      ],
                      "target": { "kind": "state", "path": "selection" },
                      "mapping": { "employeeId": "" }
                    }
                  ],
                  "contextScopes": [
                    {
                      "id": "employee-dossier",
                      "context": {
                        "parentResourceId": {
                          "kind": "state",
                          "path": "selection.employeeId"
                        },
                        "invalidConstant": { "kind": "constant" }
                      },
                      "targets": [
                        {
                          "widget": "employee-tabs",
                          "nestedPath": [
                            { "kind": "tab", "id": "missing" },
                            { "kind": "widget", "key": "missing-outlet" }
                          ],
                          "inherit": ["missingContext"]
                        }
                      ]
                    }
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains(
                "ui-composition-plan-endpoint-widget-not-found",
                "ui-composition-plan-selection-sync-source-direction-invalid",
                "ui-composition-plan-selection-sync-source-component-port-required",
                "ui-composition-plan-selection-sync-source-path-required",
                "ui-composition-plan-context-scope-constant-value-required",
                "ui-composition-plan-context-scope-nested-target-not-found",
                "ui-composition-plan-context-scope-inherited-key-not-found");
        assertThat(result.compiledFormPatch()).isEmpty();
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

    @Test
    void rejectsEveryOwnerDefinedMasterDetailSemanticErrorWithStablePaths() throws Exception {
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"employee-workspace",
                  "layoutPresetOptions":{"presetFamily":"unknown-master-detail-family"},
                  "widgets":[
                    {"key":"master","componentId":"custom-a","role":"master"},
                    {"key":"detail","componentId":"custom-b","role":"detail"}
                  ]
                }
                """,
                "master-detail-preset-family-unknown@layoutPresetOptions.presetFamily");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "slotAssignments":{"master":"invented-slot"},
                  "widgets":[
                    {"key":"master","componentId":"custom-a","role":"master"},
                    {"key":"detail","componentId":"custom-b","role":"detail"}
                  ]
                }
                """,
                "master-detail-slot-unknown@slotAssignments.master");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "slotAssignments":{"conflict":"detail-table"},
                  "widgets":[
                    {"key":"conflict","componentId":"custom-a","role":"master"},
                    {"key":"master","componentId":"custom-b","role":"master"}
                  ]
                }
                """,
                "master-detail-role-slot-conflict@slotAssignments.conflict");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "widgets":[
                    {"key":"master-a","componentId":"custom-a","role":"master"},
                    {"key":"master-b","componentId":"custom-b","role":"master"}
                  ]
                }
                """,
                "master-detail-master-ambiguous@widgets",
                "master-detail-detail-required@widgets");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "widgets":[{"key":"detail","componentId":"custom-a","role":"detail"}]
                }
                """,
                "master-detail-master-required@widgets");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"analytics-overview",
                  "widgets":[
                    {"key":"master","componentId":"custom-a","role":"master"},
                    {"key":"detail","componentId":"custom-b","role":"detail"}
                  ]
                }
                """,
                "master-detail-preset-conflict@layoutPreset");
        assertMasterDetailDiagnostics(
                """
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "slotAssignments":{"detail-a":"detail-table","detail-b":"detail-table"},
                  "widgets":[
                    {"key":"master","componentId":"custom-a","role":"master"},
                    {"key":"detail-a","componentId":"custom-b","role":"detail"},
                    {"key":"detail-b","componentId":"custom-c","role":"detail"}
                  ]
                }
                """,
                "master-detail-slot-cardinality-exceeded@slotAssignments");
    }

    @Test
    void preservesOwnerDefinedMasterDetailFallbackWarningsAndIncrementsBuilderIdentity() throws Exception {
        JsonNode plan = objectMapper.readTree("""
                {
                  "version":"1.0","kind":"praxis.ui-composition-plan",
                  "layoutPreset":"master-detail-dashboard",
                  "widgets":[
                    {"key":"master","componentId":"custom-master","role":"master"},
                    {"key":"detail-a","componentId":"custom-a","role":"detail"},
                    {"key":"detail-b","componentId":"custom-b","role":"detail"},
                    {"key":"detail-c","componentId":"custom-c","role":"detail"},
                    {"key":"detail-d","componentId":"custom-d","role":"detail"},
                    {"key":"detail-e","componentId":"custom-e","role":"detail"},
                    {"key":"extension","componentId":"custom-extension"}
                  ]
                }
                """);

        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch().path("builderVersion").asText())
                .isEqualTo(AgenticAuthoringUiCompositionPlanCompiler.BUILDER_VERSION);
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.code() + "@" + diagnostic.path())
                .containsExactly(
                        "master-detail-widget-role-fallback@widgets.6.role",
                        "master-detail-detail-slot-fallback@widgets.5.role");
        assertThat(result.compiledFormPatch().path("warnings"))
                .extracting(JsonNode::asText)
                .contains(
                        "master-detail-widget-role-fallback",
                        "master-detail-detail-slot-fallback");
    }

    private void assertMasterDetailDiagnostics(String planJson, String... expected) throws Exception {
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(objectMapper.readTree(planJson), objectMapper.createObjectNode());

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting(diagnostic -> diagnostic.code() + "@" + diagnostic.path())
                .containsExactly(expected);
    }

    private String canonicalSha256(JsonNode value) throws Exception {
        byte[] bytes = objectMapper.writeValueAsString(canonicalize(value))
                .getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            var fields = new ArrayList<String>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            fields.forEach(field -> result.set(field, canonicalize(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }
}
