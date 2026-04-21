package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringValidatorRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringValidatorRegistry registry =
            new AgenticAuthoringValidatorRegistry(new AgenticAuthoringTargetResolverRegistry());

    @Test
    void shouldValidateInputSchemaTypesEnumsRequiredAndAdditionalProperties() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.validateInputSchema(
                operationWithSchema("""
                        {
                          "type": "object",
                          "required": ["align"],
                          "properties": {
                            "align": { "enum": ["start", "center", "end"] },
                            "visible": { "type": "boolean" }
                          },
                          "additionalProperties": false
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "align": "middle",
                          "visible": "yes",
                          "extra": true
                        }
                        """),
                failures);

        assertThat(failures)
                .contains(
                        "operation input column.align.set.align must be one of [\"start\",\"center\",\"end\"]",
                        "operation input column.align.set.visible must be boolean",
                        "operation input column.align.set.extra is not allowed");
    }

    @Test
    void shouldRejectUnsafeRemoteResourceBindings() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-table",
                operationWithValidators("expansion.detailSource.configure", false, "remote-resource-binding-safe"),
                plan("{}", """
                        {
                          "resourcePath": {
                            "path": "https://evil.example/api/customers"
                          }
                        }
                        """),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("validator remote-resource-binding-safe failed for expansion.detailSource.configure: absolute remote URLs are not allowed in authoring plans");
    }

    @Test
    void shouldValidateFilterAndGroupingFieldsAgainstConfig() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-table",
                operationWithValidators("filter.advanced.configure", false, "filter-fields-exist"),
                plan("{}", """
                        {
                          "fields": ["email", "missing"]
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email" }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("validator fields-exist failed for filter.advanced.configure: unknown field missing");
    }

    @Test
    void shouldRequireResolvedFieldToBeLocalWhenValidatorDeclaresFieldIsLocal() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-dynamic-form",
                operation("field.local.remove", "field", "field-by-name-or-label", true, "field-is-local"),
                plan("\"email\"", "{}"),
                objectMapper.readTree("""
                        {
                          "fieldMetadata": [
                            { "name": "email", "label": "Email", "source": "schema" }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("validator field-is-local failed for field.local.remove: target field is not local");
    }

    @Test
    void shouldValidateDynamicFormLayoutAndVisualBlockSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "fieldMetadata": [
                    { "name": "email", "source": "local" },
                    { "name": "status", "source": "schema" }
                  ],
                  "sections": [
                    {
                      "id": "main",
                      "rows": [
                        {
                          "id": "r1",
                          "columns": [
                            {
                              "id": "c1",
                              "fields": ["email"],
                              "items": [
                                { "id": "intro", "type": "richContent", "document": { "kind": "praxis.rich-content", "version": "1.0.0", "nodes": [] } }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        registry.executeOperationValidators(
                "praxis-dynamic-form",
                operation("layout.field.move", "field", "field-by-name-or-label", true,
                        "field-exists-in-layout,layout-target-exists"),
                plan("\"status\"", "{ \"targetSectionId\": \"missing\", \"targetRowId\": \"r2\", \"targetColumnId\": \"c2\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-dynamic-form",
                operation("layout.visualBlock.add", "column", "column-by-id-in-row", true,
                        "visual-block-id-unique,rich-content-document-valid"),
                plan("\"c1\"", "{ \"id\": \"intro\", \"document\": { \"kind\": \"bad\", \"nodes\": {} } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-dynamic-form",
                operation("layout.visualBlock.update", "visualBlock", "layout-item-by-id", true,
                        "visual-block-exists"),
                plan("\"missing-block\"", "{}"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator field-exists-in-layout failed for layout.field.move: field is not referenced in layout status",
                        "validator layout-target-exists failed for layout.field.move: target section/row/column not found",
                        "validator visual-block-id-unique failed for layout.visualBlock.add: duplicate visual block id intro",
                        "validator rich-content-document-valid failed for layout.visualBlock.add: document must be a praxis.rich-content object with version and nodes[]",
                        "validator visual-block-exists failed for layout.visualBlock.update: target not found: missing-block");
    }

    @Test
    void shouldValidateTableRuleBuilderSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "columns": [
                    { "field": "status" }
                  ],
                  "ruleEffects": {
                    "rules": [
                      {
                        "ruleId": "sla",
                        "effects": [
                          { "effectId": "paint", "effectType": "fundo" }
                        ]
                      }
                    ]
                  }
                }
                """);

        registry.executeOperationValidators(
                "praxis-table-rule-builder",
                operation("rule.add", "rule", "table-rule-by-stable-id", true,
                        "scope-supported,condition-table-context-valid,condition-fields-known,effect-registry-supported"),
                plan("{ \"ruleId\": \"new\" }", "{ \"ruleId\": \"new\", \"scope\": \"cell\", \"condition\": { \"==\": [{ \"var\": \"missing\" }, true] }, \"effect\": { \"effectType\": \"unknown\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-table-rule-builder",
                operation("effect.add", "effect", "rule-effect-by-rule-and-effect-id", true,
                        "effect-id-unique,style-values-safe"),
                plan("{ \"ruleId\": \"sla\", \"effectId\": \"paint\" }", "{ \"ruleId\": \"sla\", \"effectId\": \"paint\", \"effectType\": \"fundo\", \"payload\": { \"url\": \"https://evil.example/style\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-table-rule-builder",
                operation("animation.set", "animation", "rule-animation-by-rule-and-effect-id", true,
                        "animation-preset-known,animation-override-valid"),
                plan("{ \"ruleId\": \"sla\", \"effectId\": \"paint\" }", "{ \"ruleId\": \"sla\", \"effectId\": \"paint\", \"animation\": { \"preset\": \"unknown\", \"durationMs\": 20000 } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-table-rule-builder",
                operation("tableIntegration.delegate", "tableDelegation", "praxis-table-authoring-operation", false,
                        "table-manifest-operation-known,table-target-valid"),
                plan("{}", "{ \"tableOperationId\": \"page.localConfig.write\", \"reason\": \"bad\", \"tableTarget\": \"status\" }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator scope-supported failed for rule.add: columnKey is required for cell scope",
                        "validator condition-fields-known failed for rule.add: unknown field missing",
                        "validator effect-registry-supported failed for rule.add: unsupported effectType unknown",
                        "validator effect-id-unique failed for effect.add: duplicate effectId paint",
                        "validator style-values-safe failed for effect.add: unsafe style payload",
                        "validator animation-preset-known failed for animation.set: unknown animation preset unknown",
                        "validator animation-override-valid failed for animation.set: durationMs out of bounds",
                        "validator table-manifest-operation-known failed for tableIntegration.delegate: unsupported tableOperationId page.localConfig.write",
                        "validator table-target-valid failed for tableIntegration.delegate: tableTarget must be an object");
    }

    @Test
    void shouldWarnWhenValidatorHasNoBackendImplementation() throws Exception {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-table",
                operationWithValidators("column.header.set", true, "not-implemented-validator"),
                plan("\"email\"", "{ \"header\": \"Contato\" }"),
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email", "header": "Email" }
                          ]
                        }
                        """),
                failures,
                warnings);

        assertThat(failures).isEmpty();
        assertThat(warnings)
                .contains("validator declared without backend implementation: not-implemented-validator for column.header.set");
    }

    @Test
    void shouldValidateSettingsPanelSizeAndApplySemantics() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-settings-panel",
                operation("panel.size.set", "panelSize", "settings-panel-size-and-resize", false,
                        "panel-size-safe,panel-min-max-consistent,resize-persistence-explicit"),
                plan("null", "{ \"width\": \"calc(100vw - 1rem)\", \"minWidth\": \"900px\", \"maxWidth\": \"640px\", \"persistSizeKey\": \"\" }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator panel-size-safe failed for panel.size.set: invalid width",
                        "validator panel-min-max-consistent failed for panel.size.set: minWidth must not exceed maxWidth",
                        "validator resize-persistence-explicit failed for panel.size.set: persistSizeKey must not be blank when provided");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-settings-panel",
                operation("panel.applyBehavior.set", "applyBehavior", "settings-value-provider-apply-contract", false,
                        "apply-does-not-close-panel"),
                plan("null", "{ \"closeAfterSave\": true }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("validator apply-does-not-close-panel failed for panel.applyBehavior.set: apply behavior must not close the settings panel");
    }

    @Test
    void shouldValidateSettingsPanelResetConfirmationAndEditorInputs() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-settings-panel",
                operation("panel.resetBehavior.set", "resetBehavior", "settings-value-provider-reset-contract", false,
                        "reset-requires-confirmation"),
                plan("null", "{ \"requireConfirmation\": false }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator reset-requires-confirmation failed for panel.resetBehavior.set: explicit confirmation is required",
                        "validator reset-requires-confirmation failed for panel.resetBehavior.set: reset confirmation cannot be disabled");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-settings-panel",
                operation("editor.host.configure", "editorHost", "settings-panel-editor-host", true,
                        "editor-component-registered,editor-inputs-serializable"),
                plan("{ \"id\": \"settings\" }", "{ \"componentId\": \"\", \"inputs\": { \"resourcePath\": \"https://evil.example/config\" } }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator editor-component-registered failed for editor.host.configure: componentId is required",
                        "validator editor-inputs-serializable failed for editor.host.configure: absolute remote URLs are not allowed in editor inputs");
    }

    @Test
    void shouldValidateDynamicFieldsAliasAndCoverageSemantics() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-dynamic-fields",
                operation("controlType.alias.add", "controlAlias", "normalized-control-type-alias", false,
                        "alias-resolves-deterministically,runtime-component-resolves,editor-tooling-discovers-control"),
                plan("null", "{ \"alias\": \"money\", \"controlType\": \"pdx-money\" }"),
                objectMapper.readTree("""
                        {
                          "componentRegistry": [
                            { "controlType": "pdx-money" }
                          ],
                          "controlTypeAliases": [
                            { "alias": "money", "normalizedAlias": "money", "controlType": "pdx-currency" }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains("validator alias-resolves-deterministically failed for controlType.alias.add: alias already maps to pdx-currency");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-dynamic-fields",
                operation("runtimeCoverage.validate", "runtimeCoverage", "component-registry-coverage", true,
                        "runtime-component-resolves,runtime-editor-coverage-not-divergent,coverage-evidence-present"),
                plan("\"pdx-money\"", "{ \"controlType\": \"pdx-money\", \"componentRegistered\": true }"),
                objectMapper.readTree("""
                        {
                          "runtimeCoverage": [
                            { "controlType": "pdx-money", "componentRegistered": true }
                          ],
                          "editorCoverage": [
                            { "controlType": "pdx-money", "metadataEditor": false }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator runtime-editor-coverage-not-divergent failed for runtimeCoverage.validate: runtime coverage exists but metadata editor coverage is false",
                        "validator coverage-evidence-present failed for runtimeCoverage.validate: evidence array is required");
    }

    @Test
    void shouldValidateCrudResourceUrlsDeleteAndDelegationSemantics() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-crud",
                operation("resource.bind", "resourceBinding", "crud-resource-by-path-or-key", true,
                        "resource-exists-in-api-metadata,resource-path-canonical,resource-key-stable,id-field-known"),
                plan("{ \"resourcePath\": \"/customers\" }", "{ \"resourcePath\": \"https://evil.example/customers\", \"resourceKey\": \"bad key\", \"idField\": \"missing\" }"),
                objectMapper.readTree("""
                        {
                          "fieldMetadata": [
                            { "name": "id" }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator url-canonical failed for resource.bind: resourcePath must be a relative canonical Praxis path",
                        "validator resource-key-stable failed for resource.bind: resourceKey must be stable identifier text",
                        "validator id-field-known failed for resource.bind: idField is not known missing");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-crud",
                operation("delete.enabled.set", "deleteAction", "crud-action-by-id:delete", true,
                        "delete-action-exists,destructive-delete-confirmed,submit-url-canonical"),
                plan("\"delete\"", "{ \"enabled\": true, \"autoDelete\": true, \"form\": { \"submitUrl\": \"https://evil.example/delete\" } }"),
                objectMapper.readTree("""
                        {
                          "actions": [
                            { "id": "delete" }
                          ]
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator destructive-delete-confirmed failed for delete.enabled.set: delete changes require confirmation",
                        "validator url-canonical failed for delete.enabled.set: form.submitUrl must be a relative canonical Praxis path");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-crud",
                operation("form.childOperation.delegate", "childOperation", "child-authoring-manifest-operation", false,
                        "child-manifest-available,child-operation-known,no-local-form-config-write"),
                plan("null", "{ \"childComponentId\": \"unknown\", \"formConfig\": { \"fields\": [] } }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator child-operation-known failed for form.childOperation.delegate: childComponentId and childOperationId are required",
                        "validator child-manifest-available failed for form.childOperation.delegate: unsupported childComponentId unknown",
                        "validator no-local-child-config-write failed for form.childOperation.delegate: child config must be delegated");
    }

    @Test
    void shouldValidateDialogPresetAndAccessibilitySemantics() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-dialog",
                operation("dialog.preset.apply", "preset", "praxis-dialog-global-preset-by-type-variant", false,
                        "preset-exists,accessibility-label-preserved"),
                plan("null", "{ \"dialogType\": \"confirm\", \"variant\": \"missing\", \"localConfig\": { } }"),
                objectMapper.readTree("""
                        {
                          "globalPresets": {
                            "confirm": { "ariaRole": "alertdialog" },
                            "variants": { "danger": { "themeColor": "dark" } }
                          },
                          "config": { }
                        }
                        """),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator preset-exists failed for dialog.preset.apply: variant not found missing",
                        "validator accessibility-label-preserved failed for dialog.preset.apply: dialog requires title, ariaLabel or ariaLabelledBy");
    }

    @Test
    void shouldValidateDialogClosePolicyAndChildDelegationInputs() throws Exception {
        List<String> failures = new ArrayList<>();

        registry.executeOperationValidators(
                "praxis-dialog",
                operation("dialog.closePolicy.set", "closePolicy", "praxis-dialog-close-policy-fields", false,
                        "close-policy-explicit,unsafe-close-confirmed-when-needed,alertdialog-focus-preserved"),
                plan("null", "{ \"disableClose\": true, \"autoFocus\": false }"),
                objectMapper.readTree("{ \"config\": { \"ariaRole\": \"alertdialog\" } }"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator unsafe-close-confirmed-when-needed failed for dialog.closePolicy.set: unsafe close/focus changes require confirmation",
                        "validator alertdialog-focus-preserved failed for dialog.closePolicy.set: alertdialog must keep an autofocus target reachable");

        failures.clear();
        registry.executeOperationValidators(
                "praxis-dialog",
                operation("childHost.configure", "childHost", "praxis-dialog-child-component-or-template-host", false,
                        "child-host-registered,child-inputs-serializable"),
                plan("null", "{ \"contentType\": \"component\", \"inputs\": { \"resourcePath\": \"https://evil.example/custom\" } }"),
                objectMapper.readTree("{}"),
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator child-host-registered failed for childHost.configure: componentId is required",
                        "validator child-inputs-serializable failed for childHost.configure: inputs must not contain absolute remote URLs");
    }

    @Test
    void shouldValidateChartFieldsResourceAxisAndEvents() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "availableFields": [
                    { "name": "month" },
                    { "name": "revenue" }
                  ],
                  "availableTargets": [
                    { "id": "orders-table" }
                  ],
                  "chartDocument": {
                    "version": "0.1.0",
                    "kind": "bar",
                    "source": { "kind": "praxis.stats", "resource": "/api/sales/stats" },
                    "dimensions": [{ "field": "month" }],
                    "metrics": [{ "field": "revenue", "aggregation": "sum", "axis": "primary" }]
                  }
                }
                """);

        registry.executeOperationValidators(
                "praxis-chart",
                operation("series.add", "series", "x-ui-chart-metric-by-field", false,
                        "series-field-exists,series-id-unique,secondary-axis-combo-only,series-field-aggregable"),
                plan("{}", "{ \"field\": \"missing\", \"aggregation\": \"median\", \"axis\": \"secondary\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-chart",
                operation("data.resource.bind", "dataBinding", "x-ui-chart-source-and-field-catalog", false,
                        "remote-resource-in-api-metadata,stats-operation-supported,bound-fields-exist"),
                plan("{}", "{ \"sourceKind\": \"praxis.stats\", \"resource\": \"https://evil.example/stats\", \"operation\": \"raw\", \"dimensions\": [{ \"field\": \"unknown\" }] }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-chart",
                operation("crossFilter.configure", "crossFilter", "x-ui-chart-events-cross-filter", false,
                        "event-target-governed,event-action-supported,event-mapping-fields-exist"),
                plan("{}", "{ \"event\": \"pointClick\", \"action\": \"unsafe\", \"target\": \"unknown-widget\", \"mapping\": { \"date\": \"missing\" } }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator chart-fields-exist failed for series.add: unknown field missing",
                        "validator secondary-axis-combo-only failed for series.add: secondary axis is supported only for combo charts",
                        "validator series-field-aggregable failed for series.add: unsupported aggregation median",
                        "validator remote-resource-in-api-metadata failed for data.resource.bind: resource must be a relative governed Praxis path",
                        "validator stats-operation-supported failed for data.resource.bind: unsupported stats operation raw",
                        "validator chart-fields-exist failed for data.resource.bind: unknown field unknown",
                        "validator event-target-governed failed for crossFilter.configure: unknown event target unknown-widget",
                        "validator event-action-supported failed for crossFilter.configure: unsupported event action unsafe",
                        "validator chart-fields-exist failed for crossFilter.configure: unknown field missing");
    }

    @Test
    void shouldValidateVisualBuilderGraphVariablesAndEffects() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "nodes": [
                    { "id": "root", "nodeId": "root", "nodeType": "group", "children": ["condition"] },
                    { "id": "condition", "nodeId": "condition", "nodeType": "condition", "parentId": "root", "children": [] }
                  ],
                  "contextVariables": [
                    { "name": "status", "scope": "row", "type": "string" }
                  ]
                }
                """);

        registry.executeOperationValidators(
                "praxis-visual-builder",
                operation("node.add", "node", "rule-node-by-id", true,
                        "node-id-unique,node-type-supported,parent-node-exists,node-config-compatible"),
                plan("{}", "{ \"nodeId\": \"root\", \"nodeType\": \"unsupported\", \"parentId\": \"missing\", \"configPatch\": [] }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-visual-builder",
                operation("edge.remove", "edge", "rule-node-edge-by-source-target", true,
                        "edge-exists,destructive-removal-confirmed"),
                plan("{ \"sourceNodeId\": \"condition\", \"targetNodeId\": \"missing\" }", "{ \"sourceNodeId\": \"condition\", \"targetNodeId\": \"missing\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-visual-builder",
                operation("variable.add", "contextVariable", "context-variable-by-name-scope", true,
                        "variable-id-unique,variable-scope-exists,context-variable-reference-valid"),
                plan("{}", "{ \"name\": \"status\", \"scope\": \"row\", \"defaultValue\": \"https://evil.example\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-visual-builder",
                operation("effect.set", "effect", "property-effect-by-node", true,
                        "effect-targets-exist,effect-properties-governed,effect-values-valid"),
                plan("\"condition\"", "{ \"nodeId\": \"condition\", \"targetType\": \"row\", \"targets\": [], \"properties\": { \"onclick\": \"alert(1)\", \"href\": \"https://evil.example\" } }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator node-id-unique failed for node.add: duplicate nodeId root",
                        "validator node-type-supported failed for node.add: unsupported nodeType unsupported",
                        "validator parent-node-exists failed for node.add: parent node not found missing",
                        "validator node-config-compatible failed for node.add: configPatch must be an object",
                        "validator edge-exists failed for edge.remove: edge not found condition -> missing",
                        "validator destructive-removal-confirmation failed for edge.remove: explicit confirmation is required",
                        "validator variable-id-unique failed for variable.add: duplicate variable row.status",
                        "validator effect-targets-exist failed for effect.set: targets must contain at least one target",
                        "validator effect-properties-governed failed for effect.set: unsafe inline handlers or HTML are not allowed",
                        "validator effect-values-valid failed for effect.set: remote absolute URLs are not allowed in effect values");
    }

    @Test
    void shouldValidateMetadataEditorAuthoringSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "fieldMetadata": { "name": "status", "label": "Status", "controlType": "select" },
                  "componentRegistry": [
                    { "controlType": "select" }
                  ],
                  "availableFields": [
                    { "name": "country" }
                  ],
                  "contextValidators": [
                    { "id": "required" }
                  ]
                }
                """);

        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("fieldMetadata.property.set", "fieldMetadata", "field-metadata-json-path", true,
                        "field-metadata-shape-canonical,field-path-supported-by-editor,metadata-round-trip"),
                plan("{ \"path\": \"bad.path\" }", "{ \"path\": \"bad.path\", \"value\": true }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("controlType.set", "controlType", "dynamic-fields-control-type-discovery", true,
                        "control-type-exists-in-discovery,editor-coverage-exists"),
                plan("\"missing\"", "{ \"controlType\": \"missing\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("optionSource.configure", "optionSource", "field-metadata-option-source", false,
                        "option-source-shape-canonical,remote-option-source-governed"),
                plan("{}", "{ \"kind\": \"resource\", \"resource\": \"https://evil.example/options\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("cascade.configure", "cascade", "metadata-editor-cascade-rules", false,
                        "cascade-backend-shape-preserved,cascade-fields-exist,cascade-cycle-free"),
                plan("{}", "{ \"dependentField\": \"country\", \"sourceField\": \"country\", \"dependencyFilterMap\": [], \"debounceMs\": -1 }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("renderer.configure", "renderer", "metadata-editor-renderer-property", true,
                        "renderer-editor-type-registered,visual-editor-coverage-required"),
                plan("{ \"propertyName\": \"label\" }", "{ \"propertyName\": \"label\", \"editorType\": \"\", \"options\": { \"href\": \"https://evil.example\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("validationRule.add", "validation", "field-metadata-validation-rules", false,
                        "validation-rule-canonical,context-validator-registered"),
                plan("{}", "{ \"rule\": {}, \"contextValidatorId\": \"missing-validator\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("contextHint.set", "contextHint", "metadata-editor-context-hints", false,
                        "context-hint-shape-canonical,context-hint-i18n-compatible"),
                plan("{}", "{ \"hintPath\": \"badHint\", \"value\": \"https://evil.example/help\", \"localeKey\": \"bad key\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-metadata-editor",
                operation("normalization.apply", "normalization", "metadata-editor-schema-normalizer", false,
                        "normalization-preserves-canonical-fields,runtime-editor-round-trip"),
                plan("{}", "{ \"mode\": \"destroy\" }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator field-path-supported-by-editor failed for fieldMetadata.property.set: unsupported metadata path bad.path",
                        "validator control-type-exists-in-discovery failed for controlType.set: controlType is not discoverable missing",
                        "validator editor-coverage-exists failed for controlType.set: editor coverage missing for missing",
                        "validator remote-option-source-governed failed for optionSource.configure: option sources must use relative governed Praxis paths",
                        "validator cascade-backend-shape-preserved failed for cascade.configure: dependencyFilterMap must be an object",
                        "validator cascade-backend-shape-preserved failed for cascade.configure: debounceMs must be non-negative",
                        "validator cascade-cycle-free failed for cascade.configure: dependentField cannot equal sourceField",
                        "validator renderer-editor-type-registered failed for renderer.configure: propertyName and editorType are required",
                        "validator visual-editor-coverage-required failed for renderer.configure: renderer config contains unsafe values",
                        "validator validation-rule-canonical failed for validationRule.add: rule must be a non-empty object",
                        "validator context-validator-registered failed for validationRule.add: context validator not registered missing-validator",
                        "validator context-hint-shape-canonical failed for contextHint.set: unsupported hint path badHint",
                        "validator context-hint-shape-canonical failed for contextHint.set: hint contains unsafe values",
                        "validator context-hint-i18n-compatible failed for contextHint.set: localeKey is not canonical",
                        "validator normalization-preserves-canonical-fields failed for normalization.apply: unsupported mode destroy");
    }

    @Test
    void shouldValidateManualFormAuthoringSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "currentConfig": {
                    "fieldMetadata": [
                      { "name": "email", "controlType": "text" }
                    ]
                  },
                  "componentRegistry": [
                    { "controlType": "text" }
                  ],
                  "enableCustomization": false
                }
                """);

        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("manualField.add", "manualField", "manual-form-field-by-name", true,
                        "manual-field-id-unique,control-type-discovered,metadata-bridge-does-not-redefine-schema"),
                plan("\"email\"", "{ \"fieldName\": \"email\", \"controlType\": \"missing\", \"schemaPatch\": {} }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("manualField.remove", "manualField", "manual-form-field-by-name", true,
                        "manual-field-exists,field-removal-confirmed"),
                plan("\"missing\"", "{ \"fieldName\": \"missing\", \"removeFromLayout\": true, \"clearPersistedValue\": true }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("manualField.label.set", "manualField", "manual-form-field-by-name", true,
                        "manual-field-exists,field-label-valid"),
                plan("\"email\"", "{ \"fieldName\": \"email\", \"label\": \"\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("layout.configure", "layout", "manual-form-layout", false,
                        "layout-field-references-valid,manual-layout-does-not-replace-host-template,delegates-form-config"),
                plan("{}", "{ \"fieldOrder\": [\"missing\"], \"hostTemplate\": \"<form></form>\", \"delegateAdvancedFormConfigTo\": \"other\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("toolbar.configure", "toolbar", "manual-form-customization-toolbar", false,
                        "toolbar-flags-supported,metadata-bridge-gated-by-customization"),
                plan("{}", "{ \"enableCustomization\": false, \"enabled\": true, \"editableFlags\": [\"bad\"] }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("autosave.enabled.set", "autosave", "manual-form-autosave-policy", false,
                        "autosave-explicit,autosave-debounce-safe,autosave-storage-available,persistence-key-deterministic"),
                plan("{}", "{ \"enabled\": true, \"debounceMs\": 50, \"storageKey\": \"bad key\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("submitBehavior.set", "submitBehavior", "manual-form-submit-behavior", false,
                        "submit-behavior-supported,delegates-form-config"),
                plan("{}", "{ \"action\": \"archive\", \"delegateFormSubmitTo\": \"other\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-manual-form",
                operation("metadataBridge.configure", "metadataBridge", "manual-field-metadata-bridge", false,
                        "delegates-field-metadata,metadata-bridge-gated-by-customization"),
                plan("{}", "{ \"enabled\": true, \"enableCustomization\": false, \"delegateFieldMetadataTo\": \"other\", \"delegateControlDiscoveryTo\": \"other\" }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator manual-field-id-unique failed for manualField.add: duplicate fieldName email",
                        "validator control-type-discovered failed for manualField.add: controlType is not discoverable missing",
                        "validator metadata-bridge-does-not-redefine-schema failed for manualField.add: manual-form must not redefine backend schema via schemaPatch",
                        "validator manual-field-exists failed for manualField.remove: field not found missing",
                        "validator field-removal-confirmed failed for manualField.remove: explicit confirmation is required",
                        "validator field-label-valid failed for manualField.label.set: label is required",
                        "validator layout-field-references-valid failed for layout.configure: unknown field missing",
                        "validator manual-layout-does-not-replace-host-template failed for layout.configure: host template replacement is not allowed",
                        "validator delegates-form-config failed for layout.configure: must delegate to praxis-dynamic-form",
                        "validator toolbar-flags-supported failed for toolbar.configure: unsupported flag bad",
                        "validator metadata-bridge-gated-by-customization failed for toolbar.configure: metadata bridge requires enableCustomization",
                        "validator autosave-debounce-safe failed for autosave.enabled.set: debounceMs must be between 100 and 60000",
                        "validator persistence-key-deterministic failed for autosave.enabled.set: storageKey is not deterministic",
                        "validator submit-behavior-supported failed for submitBehavior.set: unsupported action archive",
                        "validator delegates-form-config failed for submitBehavior.set: must delegate to praxis-dynamic-form",
                        "validator delegates-field-metadata failed for metadataBridge.configure: must delegate to praxis-metadata-editor",
                        "validator delegates-field-metadata failed for metadataBridge.configure: control discovery must delegate to praxis-dynamic-fields",
                        "validator metadata-bridge-gated-by-customization failed for metadataBridge.configure: metadata bridge requires enableCustomization");
    }

    @Test
    void shouldValidateEditorialFormsAuthoringSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "solution": {
                    "journeys": [
                      {
                        "journeyId": "main",
                        "steps": [
                          {
                            "stepId": "start",
                            "blocks": [
                              {
                                "blockId": "profile",
                                "fields": [ { "name": "email" } ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  },
                  "adapterRegistry": [
                    { "adapterId": "dynamic-form", "supportedDataBlockTypes": ["dataCollection"] }
                  ],
                  "snapshot": { "diagnostics": { "items": [] } }
                }
                """);

        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("snapshot.set", "snapshot", "editorial-runtime-snapshot", false,
                        "snapshot-shape-canonical,journey-exists,step-exists"),
                plan("{}", "{ \"solutionId\": \"\", \"journeyId\": \"missing\", \"stepId\": \"missing\", \"runtimeContextPatch\": [] }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("fallback.configure", "fallback", "editorial-runtime-fallback-state", false,
                        "fallback-explicit,fallback-diagnostic-backed,fallback-scope-exists"),
                plan("{}", "{ \"mode\": \"blocked\", \"scope\": { \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"missing\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("presentation.configure", "presentation", "editorial-solution-presentation", false,
                        "presentation-supported-by-runtime,presentation-does-not-mutate-domain-data,theme-tokens-valid"),
                plan("{}", "{ \"layout\": { \"orientation\": \"diagonal\" }, \"journeys\": [], \"theme\": { \"image\": \"https://evil.example/theme.png\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("adapter.bind", "adapter", "editorial-data-block-adapter-registry", true,
                        "adapter-exists,adapter-supports-data-block,adapter-component-valid,fallback-explicit"),
                plan("\"missing\"", "{ \"adapterId\": \"missing\", \"dataBlockType\": \"dataCollection\", \"componentRef\": \"foreign-widget\", \"fallbackModeWhenMissing\": \"blocked\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("dataBlock.add", "dataBlock", "editorial-journey-step-block-by-id", true,
                        "data-block-id-unique,journey-exists,step-exists"),
                plan("{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"profile\" }", "{ \"journeyId\": \"main\", \"stepId\": \"start\", \"block\": { \"blockId\": \"profile\", \"kind\": \"dataCollection\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("dataBlock.remove", "dataBlock", "editorial-journey-step-block-by-id", true,
                        "data-block-exists"),
                plan("{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"missing\" }", "{ \"journeyId\": \"main\", \"stepId\": \"start\", \"blockId\": \"missing\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-editorial-forms",
                operation("fieldBinding.set", "fieldBinding", "editorial-data-block-field-binding", true,
                        "field-binding-target-exists,field-binding-path-valid,delegates-field-metadata"),
                plan("{ \"blockId\": \"profile\", \"fieldName\": \"missing\" }", "{ \"blockId\": \"profile\", \"fieldName\": \"missing\", \"contextPath\": \"../bad\", \"delegateFieldMetadataTo\": \"other\" }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator snapshot-shape-canonical failed for snapshot.set: solutionId is required",
                        "validator snapshot-shape-canonical failed for snapshot.set: runtimeContextPatch must be an object",
                        "validator journey-exists failed for snapshot.set: journey not found missing",
                        "validator step-exists failed for snapshot.set: step not found missing",
                        "validator fallback-diagnostic-backed failed for fallback.configure: diagnosticCode is required for blocked",
                        "validator fallback-scope-exists failed for fallback.configure: block not found missing",
                        "validator presentation-supported-by-runtime failed for presentation.configure: unsupported layout orientation",
                        "validator presentation-does-not-mutate-domain-data failed for presentation.configure: presentation must not mutate journeys",
                        "validator theme-tokens-valid failed for presentation.configure: theme contains unsafe remote URL",
                        "validator adapter-exists failed for adapter.bind: adapter not registered missing",
                        "validator adapter-component-valid failed for adapter.bind: componentRef must be Praxis-owned",
                        "validator data-block-id-unique failed for dataBlock.add: duplicate blockId profile",
                        "validator data-block-exists failed for dataBlock.remove: block not found missing",
                        "validator field-binding-target-exists failed for fieldBinding.set: field not found missing",
                        "validator field-binding-path-valid failed for fieldBinding.set: contextPath is invalid",
                        "validator delegates-field-metadata failed for fieldBinding.set: must delegate to praxis-metadata-editor");
    }

    @Test
    void shouldValidatePageBuilderAuthoringSemantics() throws Exception {
        List<String> failures = new ArrayList<>();
        JsonNode config = objectMapper.readTree("""
                {
                  "canvas": {
                    "items": [
                      { "widgetKey": "formA", "x": 0, "y": 0, "w": 4, "h": 3 }
                    ]
                  },
                  "widgets": [
                    { "widgetKey": "formA", "definition": { "componentId": "praxis-dynamic-form", "inputs": {} } },
                    { "widgetKey": "formA" }
                  ],
                  "composition": {
                    "links": [
                      { "linkId": "link1", "from": { "widgetKey": "formA" }, "to": { "widgetKey": "missing" } }
                    ]
                  },
                  "componentRegistry": [
                    { "componentId": "praxis-dynamic-form" }
                  ]
                }
                """);

        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("canvas.configure", "canvas", "widget-page-canvas", true,
                        "canvas-columns-integer,canvas-row-unit-valid,canvas-gap-valid,canvas-items-reference-existing-widgets"),
                plan("{}", "{ \"columns\": 0, \"rowUnit\": -1, \"gap\": -1, \"items\": [ { \"widgetKey\": \"missing\" } ] }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("widget.add", "widget", "widget-by-stable-key", true,
                        "widget-key-unique,component-registered,canvas-item-valid,widget-key-not-array-index"),
                plan("\"0\"", "{ \"widgetKey\": \"0\", \"componentId\": \"missing\", \"canvasItem\": { \"x\": -1 } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("widget.remove", "widget", "widget-by-stable-key", true,
                        "widget-exists,destructive-removal-confirmed"),
                plan("\"missing\"", "{ \"widgetKey\": \"missing\" }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("composition.link.add", "compositionLink", "composition-link-by-id", true,
                        "composition-link-id-unique,composition-endpoints-resolve,nested-path-terminal-widget-key-required"),
                plan("\"link1\"", "{ \"linkId\": \"link1\", \"from\": { \"nestedPath\": \"value\" }, \"to\": { \"widgetKey\": \"missing\" } }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("state.set", "state", "page-state-path", true,
                        "state-path-valid,state-layer-valid"),
                plan("{ \"path\": \"../bad\", \"layer\": \"local\" }", "{ \"path\": \"../bad\", \"layer\": \"local\", \"value\": true }"),
                config,
                failures,
                new ArrayList<>());
        registry.executeOperationValidators(
                "praxis-page-builder",
                operation("childOperation.delegate", "childOperation", "widget-child-authoring-manifest-operation", false,
                        "widget-exists,child-manifest-available,child-operation-known,no-local-child-input-write"),
                plan("{}", "{ \"widgetKey\": \"missing\", \"childComponentId\": \"other\", \"childOperationId\": \"\", \"formConfig\": {} }"),
                config,
                failures,
                new ArrayList<>());

        assertThat(failures)
                .contains(
                        "validator canvas-columns-integer failed for canvas.configure: columns must be a positive integer",
                        "validator canvas-row-unit-valid failed for canvas.configure: rowUnit must be positive",
                        "validator canvas-gap-valid failed for canvas.configure: gap must be non-negative",
                        "validator canvas-items-reference-existing-widgets failed for canvas.configure: widget not found missing",
                        "validator component-registered failed for widget.add: component not registered missing",
                        "validator canvas-item-valid failed for widget.add: x must be a non-negative integer",
                        "validator widget-key-not-array-index failed for widget.add: widgetKey must not be an array index",
                        "validator widget-exists failed for widget.remove: widget not found missing",
                        "validator destructive-removal-confirmation failed for widget.remove: explicit confirmation is required",
                        "validator composition-link-id-unique failed for composition.link.add: duplicate linkId link1",
                        "validator composition-endpoints-resolve failed for composition.link.add: widget not found missing",
                        "validator nested-path-terminal-widget-key-required failed for composition.link.add: nestedPath endpoint requires widgetKey",
                        "validator state-path-valid failed for state.set: path is invalid",
                        "validator state-layer-valid failed for state.set: unsupported layer local",
                        "validator widget-exists failed for childOperation.delegate: widget not found missing",
                        "validator child-operation-known failed for childOperation.delegate: childComponentId and childOperationId are required",
                        "validator child-manifest-available failed for childOperation.delegate: unsupported childComponentId other",
                        "validator no-local-child-input-write failed for childOperation.delegate: child config must be delegated");
    }

    private JsonNode operationWithSchema(String inputSchemaJson) throws Exception {
        return objectMapper.readTree("""
                {
                  "operationId": "column.align.set",
                  "inputSchema": %s
                }
                """.formatted(inputSchemaJson));
    }

    private JsonNode operationWithValidators(
            String operationId,
            boolean targetRequired,
            String validators) throws Exception {
        return operation(operationId, "column", "column-by-field", targetRequired, validators);
    }

    private JsonNode operation(
            String operationId,
            String targetKind,
            String resolver,
            boolean targetRequired,
            String validators) throws Exception {
        String validatorArray = String.join(
                ", ",
                List.of(validators.split(",")).stream()
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(value -> "\"" + value + "\"")
                        .toList());
        return objectMapper.readTree("""
                {
                  "operationId": "%s",
                  "target": {
                    "kind": "%s",
                    "resolver": "%s",
                    "ambiguityPolicy": "fail",
                    "required": %s
                  },
                  "validators": [%s]
                }
                """.formatted(operationId, targetKind, resolver, targetRequired, validatorArray));
    }

    private JsonNode plan(String targetJson, String inputJson) throws Exception {
        return objectMapper.readTree("""
                {
                  "target": %s,
                  "input": %s
                }
                """.formatted(targetJson, inputJson));
    }
}
