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
