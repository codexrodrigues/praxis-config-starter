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
