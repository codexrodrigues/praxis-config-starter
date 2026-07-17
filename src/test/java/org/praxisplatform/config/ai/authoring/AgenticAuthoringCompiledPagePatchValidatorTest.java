package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringCompiledPagePatchValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsTheCanonicalWidgetPageContract() throws Exception {
        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(validPatch()))
                .isEmpty();
    }

    @Test
    void rejectsCanvasWithoutRequiredCanonicalFields() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page")).putObject("canvas");

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-canvas-mode-invalid");
    }

    @Test
    void rejectsNonObjectCompositionLink() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page/composition")).putArray("links").add(42);

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-composition-link-object-required");
    }

    @Test
    void rejectsNumericPortInsteadOfCoercingItToText() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page/composition/links/0/from/ref")).put("port", 42);

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-composition-component-port-required");
    }

    @Test
    void rejectsInvalidComponentPortDirection() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page/composition/links/0/from/ref")).put("direction", "input");

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-composition-component-direction-invalid");
    }

    @Test
    void rejectsRailWithoutItsRequiredSide() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page")).putArray("grouping").addObject()
                .put("kind", "rail")
                .put("id", "critical-rail")
                .putArray("widgetKeys")
                .add("critical-employees");

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-group-rail-side-invalid");
    }

    @Test
    void rejectsReferencesToWidgetsOutsideThePage() throws Exception {
        ObjectNode patch = validPatch();
        ((ObjectNode) patch.at("/patch/page/canvas/items"))
                .set("missing-widget", patch.at("/patch/page/canvas/items/critical-employees").deepCopy());

        assertThat(AgenticAuthoringCompiledPagePatchValidator.terminalApplyBlockReason(patch))
                .isEqualTo("compiled-page-canvas-widget-not-found");
    }

    private ObjectNode validPatch() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "patch": {
                    "page": {
                      "widgets": [
                        {
                          "key": "critical-employees",
                          "definition": {
                            "id": "praxis-table",
                            "inputs": {},
                            "outputs": { "selectionChange": "emit" },
                            "bindingOrder": []
                          }
                        }
                      ],
                      "canvas": {
                        "mode": "grid",
                        "columns": 12,
                        "items": {
                          "critical-employees": {
                            "col": 1,
                            "row": 1,
                            "colSpan": 12,
                            "rowSpan": 6
                          }
                        }
                      },
                      "composition": {
                        "version": "1.0.0",
                        "links": [
                          {
                            "id": "selection-to-state",
                            "intent": "state-write",
                            "from": {
                              "kind": "component-port",
                              "ref": {
                                "widget": "critical-employees",
                                "port": "selectionChange",
                                "direction": "output"
                              }
                            },
                            "to": {
                              "kind": "state",
                              "ref": {
                                "path": "selection.employee",
                                "layer": "values",
                                "writable": true
                              }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
                """);
    }
}
