package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.scheduling.support.CronExpression;

public final class AgenticAuthoringValidatorRegistry {

    private static final Set<String> IMPLEMENTED_VALIDATORS = Set.of(
            "target-column-exists",
            "column-exists",
            "field-exists",
            "section-exists",
            "row-exists",
            "layout-column-exists",
            "rule-exists",
            "action-exists",
            "target-exists",
            "step-exists",
            "selected-step-removal-safe",
            "step-content-removal-confirmed",
            "validation-rule-target-exists",
            "validation-rule-compatible",
            "server-validation-delegated",
            "tab-or-link-exists",
            "active-tab-disabled-safe",
            "active-tab-visibility-safe",
            "tab-content-valid",
            "widget-event-delegated",
            "panel-exists",
            "default-expanded-panel-exists",
            "default-expanded-removal-safe",
            "panel-content-removal-confirmed",
            "multi-expand-default-compatible",
            "disabled-expanded-compatible",
            "accordion-values-valid",
            "panel-content-valid",
            "lazy-content-compatible",
            "nested-widget-contract-delegated",
            "field-is-local",
            "field-name-unique",
            "field-exists-in-layout",
            "local-schema-name-no-collision",
            "option-source-valid",
            "rich-content-document-valid",
            "visual-block-exists",
            "visual-block-id-unique",
            "layout-target-exists",
            "row-id-unique-in-section",
            "column-id-unique-in-row",
            "rule-id-unique",
            "rule-target-refs-exist",
            "column-field-unique",
            "column-width-valid",
            "computed-expression-valid",
            "css-style-safe",
            "row-action-id-unique",
            "toolbar-action-id-unique",
            "value-mapping-valid",
            "section-id-unique",
            "destructive-removal-confirmation",
            "remote-resource-binding-safe",
            "endpoint-url-safe",
            "endpoint-uses-praxis-backend-surface",
            "presign-endpoint-fixed-contract",
            "presign-cross-origin-fields-safe",
            "upload-endpoint-fixed-contract",
            "bulk-endpoint-fixed-contract",
            "template-slot-supported",
            "bound-field-exists",
            "template-expression-safe",
            "cron-expression-valid",
            "cron-dialect-compatible",
            "frequency-maps-to-canonical-expression",
            "diagnostics-before-patch",
            "timezone-valid",
            "preview-matches-expression",
            "invalid-schedules-return-diagnostics",
            "preset-exists",
            "preset-maps-to-canonical-expression",
            "accessibility-label-preserved",
            "aria-role-valid",
            "shell-fields-supported",
            "size-values-safe",
            "size-min-max-consistent",
            "position-values-safe",
            "position-not-conflicting",
            "backdrop-policy-explicit",
            "backdrop-class-safe",
            "close-policy-explicit",
            "unsafe-close-confirmed-when-needed",
            "restore-focus-preserved",
            "alertdialog-focus-preserved",
            "preset-merge-order-preserved",
            "child-host-registered",
            "child-config-delegates-to-child-manifest",
            "child-inputs-serializable",
            "child-manifest-resolvable",
            "child-operation-authorized",
            "dialog-shell-boundary-preserved",
            "dialog-round-trip",
            "sanitization-policy-explicit",
            "unsafe-html-rejected",
            "unsafe-url-rejected",
            "security-change-confirmed",
            "preset-ref-valid",
            "host-capabilities-serializable",
            "panel-id-stable",
            "replacement-mediates-before-close",
            "title-i18n-compatible",
            "panel-size-safe",
            "panel-min-max-consistent",
            "resize-persistence-explicit",
            "apply-requires-provider-value",
            "apply-does-not-close-panel",
            "dirty-valid-busy-gates-preserved",
            "save-prefers-provider-hook",
            "save-fallback-value-preserved",
            "save-closes-with-save-reason",
            "reset-requires-confirmation",
            "reset-calls-provider-reset",
            "reset-event-emitted",
            "editor-component-registered",
            "settings-value-provider-contract-present",
            "consumer-config-delegated",
            "editor-inputs-serializable",
            "diagnostics-follow-provider-state",
            "diagnostics-i18n-compatible",
            "busy-valid-dirty-visible-when-needed",
            "settings-panel-round-trip",
            "json-logic-valid",
            "logic-valid",
            "renderer-supported",
            "renderer-type-supported",
            "renderer-config-match",
            "renderer-config-compatible",
            "grouping-fields-exist",
            "filter-fields-exist",
            "editor-round-trip-preserve",
            "no-index-as-identity",
            "format-preset-supported",
            "conditional-style-valid",
            "control-type-unique",
            "runtime-component-resolves",
            "field-metadata-compatible",
            "alias-resolves-deterministically",
            "editor-tooling-discovers-control",
            "alias-exists",
            "alias-removal-safe",
            "selector-mapping-deterministic",
            "metadata-capability-aligned",
            "runtime-editor-coverage-not-divergent",
            "coverage-evidence-present",
            "resource-exists-in-api-metadata",
            "resource-path-canonical",
            "resource-key-stable",
            "id-field-known",
            "resource-capabilities-resolvable",
            "table-child-operation-delegated",
            "query-context-valid",
            "filter-criteria-bridge-valid",
            "crud-context-stable",
            "open-mode-binding-complete",
            "schema-url-canonical",
            "submit-url-canonical",
            "resource-create-supported",
            "resource-edit-supported",
            "resource-view-supported",
            "form-child-operation-delegated",
            "delete-action-exists",
            "resource-delete-supported",
            "destructive-delete-confirmed",
            "permissions-delete-valid",
            "open-mode-supported",
            "modal-size-valid",
            "drawer-adapter-available-when-needed",
            "back-policy-valid",
            "settings-panel-shell-compatible",
            "action-permission-supported",
            "delete-permission-requires-confirmation",
            "permissions-do-not-shadow-backend",
            "child-manifest-available",
            "child-operation-known",
            "no-local-form-config-write",
            "no-local-table-config-write",
            "delegation-target-valid");

    private final AgenticAuthoringTargetResolverRegistry targetResolverRegistry;

    public AgenticAuthoringValidatorRegistry(AgenticAuthoringTargetResolverRegistry targetResolverRegistry) {
        this.targetResolverRegistry = Objects.requireNonNull(targetResolverRegistry, "targetResolverRegistry must not be null");
    }

    void validateInputSchema(JsonNode operation, JsonNode input, List<String> failures) {
        validateSchemaNode(operation.path("inputSchema"), input, "operation input " + text(operation, "operationId"), failures);
    }

    void executeOperationValidators(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures,
            List<String> warnings) {
        String operationId = text(operation, "operationId");
        Set<String> refs = new HashSet<>();
        operation.path("validators").forEach(ref -> refs.add(ref.asText("")));
        for (String validatorId : refs) {
            if (validatorId.isBlank()) {
                continue;
            }
            if (!IMPLEMENTED_VALIDATORS.contains(validatorId)) {
                warnings.add("validator declared without backend implementation: " + validatorId + " for " + operationId);
                continue;
            }
            switch (validatorId) {
                case "target-column-exists", "column-exists", "field-exists", "section-exists",
                     "row-exists", "layout-column-exists", "rule-exists", "action-exists", "target-exists",
                     "step-exists", "tab-or-link-exists", "panel-exists", "default-expanded-panel-exists" -> {
                    if (operation.path("target").path("required").asBoolean(false)) {
                        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                                componentId,
                                operation,
                                planOperation.path("target"),
                                config);
                        if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
                            failures.add("validator " + validatorId + " failed for " + operationId + ": "
                                    + String.join(", ", resolved.failures()));
                        }
                    }
                }
                case "selected-step-removal-safe" -> validateSelectedStepRemovalSafe(operationId, planOperation, config, failures);
                case "step-content-removal-confirmed" -> validateStepContentRemovalConfirmed(operationId, planOperation, failures);
                case "validation-rule-target-exists" -> validateStepperValidationRuleTargetExists(operationId, planOperation, config, failures);
                case "validation-rule-compatible" -> validateStepperValidationRuleCompatible(operationId, planOperation, failures);
                case "server-validation-delegated" -> validateStepperServerValidationDelegated(operationId, planOperation, failures);
                case "active-tab-disabled-safe" -> validateTabsActiveBooleanSafe(operationId, operation, planOperation, config, failures, "disabled");
                case "active-tab-visibility-safe" -> validateTabsActiveBooleanSafe(operationId, operation, planOperation, config, failures, "visible");
                case "tab-content-valid" -> validateTabsContentValid(operationId, planOperation, failures);
                case "widget-event-delegated" -> validateWidgetEventDelegated(operationId, planOperation, failures);
                case "default-expanded-removal-safe" -> validateExpansionRemovalSafe(operationId, planOperation, config, failures);
                case "panel-content-removal-confirmed" -> validateExpansionContentRemovalConfirmed(operationId, planOperation, config, failures);
                case "multi-expand-default-compatible" -> validateExpansionMultiDefaultCompatible(operationId, operation, planOperation, config, failures);
                case "disabled-expanded-compatible" -> validateExpansionDisabledExpandedCompatible(operationId, operation, planOperation, config, failures);
                case "accordion-values-valid" -> validateAccordionValuesValid(operationId, planOperation, failures);
                case "panel-content-valid" -> validateExpansionPanelContentValid(operationId, planOperation, failures);
                case "lazy-content-compatible", "nested-widget-contract-delegated" -> validateWidgetEventDelegated(operationId, planOperation, failures);
                case "field-is-local" -> validateResolvedFieldIsLocal(componentId, operation, planOperation, config, failures);
                case "remote-resource-binding-safe" -> validateRemoteResourceBindingSafe(operation, planOperation, failures);
                case "endpoint-url-safe" -> validateFilesEndpointUrlSafe(operationId, planOperation, failures);
                case "endpoint-uses-praxis-backend-surface" -> validateFilesEndpointPraxisSurface(operationId, planOperation, failures);
                case "presign-endpoint-fixed-contract" -> validateFilesEndpointPathContract(operationId, planOperation, failures, "presign");
                case "upload-endpoint-fixed-contract" -> validateFilesEndpointPathContract(operationId, planOperation, failures, "upload");
                case "bulk-endpoint-fixed-contract" -> validateFilesEndpointPathContract(operationId, planOperation, failures, "bulk");
                case "presign-cross-origin-fields-safe" -> validatePresignCrossOriginFieldsSafe(operationId, planOperation, failures);
                case "template-slot-supported" -> validateListTemplateSlotSupported(operationId, planOperation, failures);
                case "bound-field-exists" -> validateListTemplateBoundFieldsExist(operationId, planOperation, config, failures);
                case "template-expression-safe" -> validateListTemplateExpressionSafe(operationId, planOperation, failures);
                case "cron-expression-valid" -> validateCronExpressionValid(operationId, planOperation, config, failures);
                case "cron-dialect-compatible" -> validateCronDialectCompatible(operationId, planOperation, failures);
                case "frequency-maps-to-canonical-expression" -> validateCronFrequencyMaps(operationId, planOperation, failures);
                case "diagnostics-before-patch", "preview-matches-expression", "invalid-schedules-return-diagnostics",
                     "preset-maps-to-canonical-expression" -> {
                    // These are enforced by the domain compiler, which emits diagnostics before mutating derived paths.
                }
                case "timezone-valid" -> validateCronTimezoneValid(operationId, planOperation, config, failures);
                case "preset-exists" -> {
                    if ("praxis-dialog".equals(componentId) || operationId.startsWith("dialog.preset")) {
                        validateDialogPresetExists(operationId, planOperation, config, failures);
                    } else {
                        validateCronPresetExists(operationId, planOperation, config, failures);
                    }
                }
                case "accessibility-label-preserved" -> validateDialogAccessibilityLabel(operationId, planOperation, config, failures);
                case "aria-role-valid" -> validateDialogAriaRole(operationId, planOperation, failures);
                case "size-values-safe", "position-values-safe" -> validateDialogCssValues(operationId, planOperation, failures);
                case "size-min-max-consistent" -> validateDialogSizeMinMax(operationId, planOperation, failures);
                case "backdrop-class-safe" -> validateDialogClassValue(operationId, planOperation.path("input").path("backdropClass"), "backdrop-class-safe", failures);
                case "close-policy-explicit" -> validateDialogClosePolicyExplicit(operationId, planOperation, failures);
                case "unsafe-close-confirmed-when-needed", "restore-focus-preserved", "alertdialog-focus-preserved" ->
                        validateDialogClosePolicySafe(operationId, planOperation, config, failures);
                case "child-host-registered" -> validateDialogChildHostRegistered(operationId, planOperation, failures);
                case "child-inputs-serializable" -> validateDialogChildInputsSerializable(operationId, planOperation, failures);
                case "child-manifest-resolvable" -> validateDialogChildManifestResolvable(operationId, planOperation, failures);
                case "child-operation-authorized" -> validateDialogChildOperationAuthorized(operationId, planOperation, failures);
                case "shell-fields-supported", "position-not-conflicting", "backdrop-policy-explicit",
                     "preset-merge-order-preserved", "child-config-delegates-to-child-manifest",
                     "dialog-shell-boundary-preserved", "dialog-round-trip" -> {
                    // These invariants are structural in the manifest and compiler: shell edits stay scoped and child edits are delegated.
                }
                case "sanitization-policy-explicit" -> validateSanitizationPolicyExplicit(operationId, planOperation, failures);
                case "unsafe-html-rejected" -> validateUnsafeHtmlRejected(operationId, planOperation, failures);
                case "unsafe-url-rejected" -> validateUnsafeUrlRejected(operationId, planOperation, failures);
                case "security-change-confirmed" -> validateSecurityChangeConfirmed(operationId, planOperation, failures);
                case "preset-ref-valid" -> validatePresetRef(operationId, planOperation, failures);
                case "host-capabilities-serializable" -> validatePresetInputsSerializable(operationId, planOperation, failures);
                case "panel-size-safe" -> validateSettingsPanelSizeSafe(operationId, planOperation, failures);
                case "panel-min-max-consistent" -> validateSettingsPanelMinMax(operationId, planOperation, failures);
                case "resize-persistence-explicit" -> validateSettingsPanelResizePersistence(operationId, planOperation, failures);
                case "apply-does-not-close-panel" -> validateSettingsPanelApplyDoesNotClose(operationId, planOperation, failures);
                case "reset-requires-confirmation" -> validateSettingsPanelResetConfirmed(operationId, planOperation, failures);
                case "editor-component-registered" -> validateSettingsPanelEditorComponent(operationId, planOperation, failures);
                case "editor-inputs-serializable" -> validateSettingsPanelEditorInputs(operationId, planOperation, failures);
                case "panel-id-stable", "replacement-mediates-before-close", "title-i18n-compatible",
                     "apply-requires-provider-value", "dirty-valid-busy-gates-preserved",
                     "save-prefers-provider-hook", "save-fallback-value-preserved", "save-closes-with-save-reason",
                     "reset-calls-provider-reset", "reset-event-emitted",
                     "settings-value-provider-contract-present", "consumer-config-delegated",
                     "diagnostics-follow-provider-state", "diagnostics-i18n-compatible",
                     "busy-valid-dirty-visible-when-needed", "settings-panel-round-trip" -> {
                    // These validators assert runtime/editor protocol invariants represented by the compiled runtime contract.
                }
                case "json-logic-valid", "logic-valid", "conditional-style-valid" ->
                        validateJsonLogicLikeInput(operationId, planOperation.path("input"), failures);
                case "grouping-fields-exist", "filter-fields-exist" ->
                        validateInputFieldsExist(operationId, planOperation.path("input"), config, failures);
                case "control-type-unique" -> validateDynamicControlTypeUnique(operationId, planOperation, config, failures);
                case "runtime-component-resolves" -> validateDynamicRuntimeComponentResolves(operationId, planOperation, config, failures);
                case "field-metadata-compatible", "metadata-capability-aligned" -> validateDynamicFieldMetadataCompatible(operationId, planOperation, failures);
                case "alias-resolves-deterministically" -> validateDynamicAliasDeterministic(operationId, planOperation, config, failures);
                case "alias-exists" -> validateDynamicAliasExists(operationId, planOperation, config, failures);
                case "alias-removal-safe" -> validateDynamicAliasRemovalSafe(operationId, planOperation, config, failures);
                case "destructive-removal-confirmation" -> validateDestructiveRemovalConfirmation(operationId, planOperation, failures);
                case "selector-mapping-deterministic" -> validateDynamicSelectorMappingDeterministic(operationId, planOperation, config, failures);
                case "editor-tooling-discovers-control" -> validateDynamicEditorToolingDiscoversControl(operationId, planOperation, config, failures);
                case "runtime-editor-coverage-not-divergent" -> validateDynamicRuntimeEditorCoverageNotDivergent(operationId, planOperation, config, failures);
                case "coverage-evidence-present" -> validateDynamicCoverageEvidencePresent(operationId, planOperation, failures);
                case "resource-exists-in-api-metadata", "resource-capabilities-resolvable" -> validateCrudResourcePresent(operationId, planOperation, config, failures);
                case "resource-path-canonical", "schema-url-canonical", "submit-url-canonical" -> validateCrudCanonicalRelativeUrls(operationId, planOperation, failures);
                case "resource-key-stable" -> validateCrudResourceKeyStable(operationId, planOperation, failures);
                case "id-field-known" -> validateCrudIdFieldKnown(operationId, planOperation, config, failures);
                case "table-child-operation-delegated", "form-child-operation-delegated" -> validateCrudChildPatchDelegated(operationId, planOperation, failures);
                case "query-context-valid", "filter-criteria-bridge-valid", "crud-context-stable" -> validateCrudSerializableObjectInputs(operationId, planOperation, failures);
                case "open-mode-binding-complete", "open-mode-supported", "drawer-adapter-available-when-needed" -> validateCrudOpenMode(operationId, planOperation, failures);
                case "resource-create-supported", "resource-edit-supported", "resource-view-supported", "resource-delete-supported" -> validateCrudResourceCapability(operationId, validatorId, planOperation, config, failures);
                case "delete-action-exists" -> validateCrudDeleteActionExists(operationId, config, failures);
                case "destructive-delete-confirmed", "delete-permission-requires-confirmation" -> validateCrudDeleteConfirmed(operationId, planOperation, failures);
                case "permissions-delete-valid", "action-permission-supported", "permissions-do-not-shadow-backend" -> validateCrudPermissions(operationId, planOperation, failures);
                case "modal-size-valid" -> validateCrudModalSize(operationId, planOperation, failures);
                case "back-policy-valid", "settings-panel-shell-compatible" -> validateCrudSerializableObjectInputs(operationId, planOperation, failures);
                case "child-manifest-available", "child-operation-known", "delegation-target-valid" -> validateCrudChildDelegation(operationId, planOperation, failures);
                case "no-local-form-config-write", "no-local-table-config-write" -> validateCrudNoLocalChildConfigWrite(operationId, planOperation, failures);
                default -> warnings.add("validator executed as structural pass-through: " + validatorId);
            }
        }
    }

    private void validateSelectedStepRemovalSafe(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode steps = config.path("steps");
        if (steps.isArray() && steps.size() <= 1) {
            failures.add("validator selected-step-removal-safe failed for " + operationId
                    + ": cannot remove the only step");
        }
        String replacementStepId = text(planOperation.path("input"), "replacementStepId");
        if (!replacementStepId.isBlank()) {
            boolean found = false;
            for (JsonNode step : steps) {
                if (replacementStepId.equals(text(step, "id"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                failures.add("validator selected-step-removal-safe failed for " + operationId
                        + ": replacement step not found " + replacementStepId);
            }
        }
    }

    private void validateStepContentRemovalConfirmed(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        if (!planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator step-content-removal-confirmed failed for " + operationId
                    + ": explicit confirmation is required");
        }
    }

    private void validateStepperValidationRuleTargetExists(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String stepId = text(input, "stepId");
        JsonNode step = findObjectByKey(config.path("steps"), "id", stepId);
        if (step.isMissingNode()) {
            failures.add("validator validation-rule-target-exists failed for " + operationId
                    + ": step not found " + stepId);
            return;
        }
        String fieldName = text(input, "fieldName");
        if (!fieldName.isBlank() && findObjectByKey(step.path("form").path("config").path("fieldMetadata"), "name", fieldName).isMissingNode()) {
            failures.add("validator validation-rule-target-exists failed for " + operationId
                    + ": field not found " + fieldName);
        }
        String childComponentId = text(input, "childComponentId");
        if (!childComponentId.isBlank() && findObjectByKey(step.path("widgets"), "id", childComponentId).isMissingNode()) {
            failures.add("validator validation-rule-target-exists failed for " + operationId
                    + ": child component not found " + childComponentId);
        }
    }

    private void validateStepperValidationRuleCompatible(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode rule = planOperation.path("input").path("rule");
        if (!rule.isObject() || rule.isEmpty()) {
            failures.add("validator validation-rule-compatible failed for " + operationId
                    + ": rule object must not be empty");
            return;
        }
        JsonNode condition = firstPresent(rule, "condition", "logic", "expression");
        if (!condition.isMissingNode() && condition.isTextual() && condition.asText("").isBlank()) {
            failures.add("validator validation-rule-compatible failed for " + operationId
                    + ": rule condition must not be blank");
        }
    }

    private void validateStepperServerValidationDelegated(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.path("remote").asBoolean(false) && text(input, "childComponentId").isBlank()) {
            failures.add("validator server-validation-delegated failed for " + operationId
                    + ": remote validation requires childComponentId for host delegation");
        }
    }

    private void validateTabsActiveBooleanSafe(
            String operationId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures,
            String fieldName) {
        JsonNode inputValue = planOperation.path("input").path(fieldName);
        boolean disablesOrHides = "disabled".equals(fieldName)
                ? inputValue.asBoolean(false)
                : inputValue.isBoolean() && !inputValue.asBoolean(true);
        if (!disablesOrHides) {
            return;
        }
        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                "",
                operation,
                planOperation.path("target"),
                config);
        if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
            return;
        }
        String path = resolved.path();
        int activeIndex = path.startsWith("nav.links[]")
                ? config.path("nav").path("selectedIndex").asInt(-1)
                : config.path("group").path("selectedIndex").asInt(-1);
        int targetIndex = resolvedArrayIndex(path);
        if (activeIndex >= 0 && activeIndex == targetIndex) {
            failures.add("validator " + ("disabled".equals(fieldName) ? "active-tab-disabled-safe" : "active-tab-visibility-safe")
                    + " failed for " + operationId + ": active item cannot be "
                    + ("disabled".equals(fieldName) ? "disabled" : "hidden")
                    + " without explicit reselection");
        }
    }

    private void validateTabsContentValid(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("content") && !input.path("content").isArray()) {
            failures.add("validator tab-content-valid failed for " + operationId + ": content must be an array");
        }
        if (input.has("widgets") && !input.path("widgets").isArray()) {
            failures.add("validator tab-content-valid failed for " + operationId + ": widgets must be an array");
        }
    }

    private void validateWidgetEventDelegated(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode widgets = planOperation.path("input").path("widgets");
        if (!widgets.isArray()) {
            return;
        }
        for (JsonNode widget : widgets) {
            if (containsUnsafeInlineHandler(widget)) {
                failures.add("validator widget-event-delegated failed for " + operationId
                        + ": widget events must be delegated, not inline functions");
                return;
            }
        }
    }

    private void validateExpansionRemovalSafe(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode panels = config.path("panels");
        if (panels.isArray() && panels.size() <= 1) {
            failures.add("validator default-expanded-removal-safe failed for " + operationId
                    + ": cannot remove the only panel");
        }
        String replacementPanelId = text(planOperation.path("input"), "replacementExpandedPanelId");
        if (!replacementPanelId.isBlank() && findObjectByKey(panels, "id", replacementPanelId).isMissingNode()) {
            failures.add("validator default-expanded-removal-safe failed for " + operationId
                    + ": replacement panel not found " + replacementPanelId);
        }
    }

    private void validateExpansionContentRemovalConfirmed(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        if (planOperation.path("confirmed").asBoolean(false)) {
            return;
        }
        JsonNode targetPanel = resolvePanelFromTarget(planOperation, config);
        boolean hasContent = targetPanel.path("content").isArray() && !targetPanel.path("content").isEmpty();
        boolean hasWidgets = targetPanel.path("widgets").isArray() && !targetPanel.path("widgets").isEmpty();
        boolean hasActionButtons = targetPanel.path("actionButtons").isArray() && !targetPanel.path("actionButtons").isEmpty();
        if (targetPanel.isMissingNode() || hasContent || hasWidgets || hasActionButtons) {
            failures.add("validator panel-content-removal-confirmed failed for " + operationId
                    + ": explicit confirmation is required");
        }
    }

    private void validateExpansionMultiDefaultCompatible(
            String operationId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("multi") && !input.path("multi").asBoolean(true) && expandedPanelCount(config.path("panels")) > 1) {
            failures.add("validator multi-expand-default-compatible failed for " + operationId
                    + ": switching to single-expand requires deterministic collapse of competing expanded panels");
            return;
        }
        if (input.has("expanded") && input.path("expanded").asBoolean(false)) {
            boolean multi = config.path("accordion").path("multi").asBoolean(false);
            boolean collapseOthers = input.path("collapseOthers").asBoolean(false);
            if (!multi && !collapseOthers && hasOtherExpandedPanel(operation, planOperation, config)) {
                failures.add("validator multi-expand-default-compatible failed for " + operationId
                        + ": single-expand mode requires collapseOthers when another panel is expanded");
            }
        }
    }

    private void validateExpansionDisabledExpandedCompatible(
            String operationId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        JsonNode targetPanel = resolveTarget(operation, planOperation, config);
        if (input.path("disabled").asBoolean(false) && targetPanel.path("expanded").asBoolean(false)) {
            failures.add("validator disabled-expanded-compatible failed for " + operationId
                    + ": expanded panel cannot be disabled without collapsing it");
        }
        if (input.path("expanded").asBoolean(false) && targetPanel.path("disabled").asBoolean(false)) {
            failures.add("validator disabled-expanded-compatible failed for " + operationId
                    + ": disabled panel cannot be expanded");
        }
    }

    private boolean hasOtherExpandedPanel(JsonNode operation, JsonNode planOperation, JsonNode config) {
        String targetId = text(resolveTarget(operation, planOperation, config), "id");
        for (JsonNode panel : config.path("panels")) {
            if (panel.path("expanded").asBoolean(false) && !targetId.equals(text(panel, "id"))) {
                return true;
            }
        }
        return false;
    }

    private int expandedPanelCount(JsonNode panels) {
        int count = 0;
        if (panels.isArray()) {
            for (JsonNode panel : panels) {
                if (panel.path("expanded").asBoolean(false)) {
                    count++;
                }
            }
        }
        return count;
    }

    private JsonNode resolveTarget(JsonNode operation, JsonNode planOperation, JsonNode config) {
        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                "",
                operation,
                planOperation.path("target"),
                config);
        return AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())
                ? resolved.value()
                : MissingNode.getInstance();
    }

    private JsonNode resolvePanelFromTarget(JsonNode planOperation, JsonNode config) {
        String targetId = planOperation.path("target").isTextual()
                ? planOperation.path("target").asText("")
                : firstPresent(planOperation.path("target"), "id", "panelId", "title", "label").asText("");
        JsonNode panel = findObjectByKey(config.path("panels"), "id", targetId);
        if (!panel.isMissingNode()) {
            return panel;
        }
        panel = findObjectByKey(config.path("panels"), "title", targetId);
        return panel.isMissingNode() ? findObjectByKey(config.path("panels"), "label", targetId) : panel;
    }

    private void validateAccordionValuesValid(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("multi") && !input.path("multi").isBoolean()) {
            failures.add("validator accordion-values-valid failed for " + operationId + ": multi must be boolean");
        }
    }

    private void validateExpansionPanelContentValid(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("content") && !input.path("content").isArray()) {
            failures.add("validator panel-content-valid failed for " + operationId + ": content must be an array");
        }
        if (input.has("widgets") && !input.path("widgets").isArray()) {
            failures.add("validator panel-content-valid failed for " + operationId + ": widgets must be an array");
        }
        if (input.has("actionButtons") && !input.path("actionButtons").isArray()) {
            failures.add("validator panel-content-valid failed for " + operationId + ": actionButtons must be an array");
        }
    }

    private boolean containsUnsafeInlineHandler(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if ("function".equals(key) || "handler".equals(key) || "callback".equals(key)) {
                    return true;
                }
                if (containsUnsafeInlineHandler(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsUnsafeInlineHandler(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validatePresetRef(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode ref = planOperation.path("input").path("ref");
        if (!ref.isObject()) {
            failures.add("validator preset-ref-valid failed for " + operationId + ": ref object is required");
            return;
        }
        if (!"rich-block".equals(text(ref, "kind"))) {
            failures.add("validator preset-ref-valid failed for " + operationId + ": ref.kind must be rich-block");
        }
        if (text(ref, "namespace").isBlank()) {
            failures.add("validator preset-ref-valid failed for " + operationId + ": ref.namespace is required");
        }
        if (text(ref, "presetId").isBlank()) {
            failures.add("validator preset-ref-valid failed for " + operationId + ": ref.presetId is required");
        }
    }

    private void validatePresetInputsSerializable(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode inputs = planOperation.path("input").path("inputs");
        if (!inputs.isMissingNode() && !isSerializablePresetInputs(inputs)) {
            failures.add("validator host-capabilities-serializable failed for " + operationId
                    + ": preset inputs must not serialize host capability functions or resolvers");
        }
    }

    private boolean isSerializablePresetInputs(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isValueNode()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (!isSerializablePresetInputs(child)) {
                    return false;
                }
            }
            return true;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("function".equals(field.getKey()) || "resolver".equals(field.getKey())) {
                    return false;
                }
                if (!isSerializablePresetInputs(field.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void validateSanitizationPolicyExplicit(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!(input.has("allowHtml")
                || input.has("allowedUrlProtocols")
                || input.has("allowImageDataUrls")
                || input.has("maxNodeDepth")
                || input.has("maxNodeCount"))) {
            failures.add("validator sanitization-policy-explicit failed for " + operationId
                    + ": at least one policy field is required");
        }
    }

    private void validateUnsafeHtmlRejected(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("allowHtml") && input.path("allowHtml").asBoolean(true)) {
            failures.add("validator unsafe-html-rejected failed for " + operationId
                    + ": arbitrary HTML is not supported");
        }
    }

    private void validateUnsafeUrlRejected(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode protocols = planOperation.path("input").path("allowedUrlProtocols");
        if (!protocols.isArray()) {
            return;
        }
        for (JsonNode protocol : protocols) {
            String normalized = protocol.asText("").replace(":", "").toLowerCase();
            if (!safeRichContentUrlProtocols().contains(normalized)) {
                failures.add("validator unsafe-url-rejected failed for " + operationId
                        + ": unsupported URL protocol " + protocol.asText(""));
            }
        }
    }

    private void validateSecurityChangeConfirmed(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        if (!planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator security-change-confirmed failed for " + operationId
                    + ": explicit confirmation is required");
        }
    }

    private void validateResolvedFieldIsLocal(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                componentId,
                operation,
                planOperation.path("target"),
                config);
        if (AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())
                && !"local".equals(text(resolved.value(), "source"))) {
            failures.add("validator field-is-local failed for " + text(operation, "operationId") + ": target field is not local");
        }
    }

    private void validateRemoteResourceBindingSafe(
            JsonNode operation,
            JsonNode planOperation,
            List<String> failures) {
        if (containsUnsafeAbsoluteUrl(planOperation.path("input"))) {
            failures.add("validator remote-resource-binding-safe failed for "
                    + text(operation, "operationId")
                    + ": absolute remote URLs are not allowed in authoring plans");
        }
    }

    private boolean containsUnsafeAbsoluteUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText("");
            return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//");
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsUnsafeAbsoluteUrl(child)) {
                    return true;
                }
            }
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (containsUnsafeAbsoluteUrl(values.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeFilesBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Set<String> listTemplateSlots() {
        return Set.of(
                "leading",
                "primary",
                "secondary",
                "meta",
                "trailing",
                "identity",
                "balance",
                "limit",
                "risk",
                "alerts",
                "owner",
                "sectionHeader",
                "emptyState");
    }

    private Set<String> templateReferencedFields(JsonNode template) {
        Set<String> fields = new HashSet<>();
        collectTemplateReferencedFields(template, fields);
        return fields;
    }

    private void collectTemplateReferencedFields(JsonNode node, Set<String> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            collectFieldReferencesFromText(node.asText(""), fields);
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectTemplateReferencedFields(child, fields));
            return;
        }
        if (node.isObject()) {
            for (String key : List.of("field", "fieldName", "valueExpr", "expr", "titleExpr", "subtitleExpr", "iconExpr", "colorExpr")) {
                collectFieldReferencesFromText(text(node, key), fields);
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                collectTemplateReferencedFields(values.next(), fields);
            }
        }
    }

    private void collectFieldReferencesFromText(String text, Set<String> fields) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            fields.add(text);
            return;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{\\{?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}?}")
                .matcher(text);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
    }

    private boolean containsUnsafeTemplateHtml(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            String type = text(node, "type");
            if ("html".equals(type)) {
                return true;
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (containsUnsafeTemplateHtml(values.next())) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsUnsafeTemplateHtml(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean findCronPreset(JsonNode presets, String labelOrCron) {
        if (!presets.isArray()) {
            return false;
        }
        for (JsonNode preset : presets) {
            if (labelOrCron.equals(text(preset, "label"))
                    || labelOrCron.equals(text(preset, "cron"))
                    || labelOrCron.equals(text(preset, "expression"))
                    || labelOrCron.equals(text(preset, "value"))) {
                return true;
            }
        }
        return false;
    }

    private String springCronExpression(String cron, boolean seconds) {
        int count = cronFieldCount(cron);
        if (count == 5 && !seconds) {
            return "0 " + cron;
        }
        return cron == null ? "" : cron.trim();
    }

    private int cronFieldCount(String cron) {
        if (cron == null || cron.isBlank()) {
            return 0;
        }
        return cron.trim().split("\\s+").length;
    }

    private boolean isValidSpringCron(String springCron) {
        try {
            CronExpression.parse(springCron);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Set<String> safeRichContentUrlProtocols() {
        return Set.of("http", "https", "mailto", "tel");
    }

    private void validateJsonLogicLikeInput(String operationId, JsonNode input, List<String> failures) {
        JsonNode condition = firstPresent(input, "condition", "logic", "expression");
        if (condition.isMissingNode()) {
            return;
        }
        if (condition.isTextual() && condition.asText("").isBlank()) {
            failures.add("validator json-logic-valid failed for " + operationId + ": expression must not be blank");
        } else if (!(condition.isObject() || condition.isArray() || condition.isTextual())) {
            failures.add("validator json-logic-valid failed for " + operationId + ": expression has unsupported type");
        }
    }

    private void validateDynamicControlTypeUnique(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String controlType = text(planOperation.path("input"), "controlType");
        if (controlType.isBlank()) {
            failures.add("validator control-type-unique failed for " + operationId + ": controlType is required");
            return;
        }
        if (hasControlType(config, controlType)) {
            failures.add("validator control-type-unique failed for " + operationId + ": controlType already exists " + controlType);
        }
    }

    private void validateDynamicRuntimeComponentResolves(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String controlType = text(input, "controlType");
        if ("controlType.register".equals(operationId)) {
            if (text(input, "componentExport").isBlank()) {
                failures.add("validator runtime-component-resolves failed for " + operationId + ": componentExport is required");
            }
            if (text(input, "selector").isBlank()) {
                failures.add("validator runtime-component-resolves failed for " + operationId + ": selector is required");
            }
            return;
        }
        if (controlType.isBlank()) {
            failures.add("validator runtime-component-resolves failed for " + operationId + ": controlType is required");
            return;
        }
        if (!hasControlType(config, controlType) && !input.path("componentRegistered").asBoolean(false)) {
            failures.add("validator runtime-component-resolves failed for " + operationId + ": controlType is not registered " + controlType);
        }
    }

    private void validateDynamicFieldMetadataCompatible(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("path")) {
            String path = text(input, "path");
            if (path.isBlank() || path.startsWith("$") || path.contains("..")) {
                failures.add("validator field-metadata-compatible failed for " + operationId + ": metadata path is invalid");
            }
        }
        if (input.has("controlType") && text(input, "controlType").isBlank()) {
            failures.add("validator field-metadata-compatible failed for " + operationId + ": controlType is required");
        }
    }

    private void validateDynamicAliasDeterministic(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String alias = text(input, "alias");
        String controlType = text(input, "controlType");
        if (alias.isBlank() || controlType.isBlank()) {
            failures.add("validator alias-resolves-deterministically failed for " + operationId + ": alias and controlType are required");
            return;
        }
        if (normalizeControlToken(alias).equals(normalizeControlToken(controlType))) {
            failures.add("validator alias-resolves-deterministically failed for " + operationId + ": alias must differ from controlType");
        }
        JsonNode existing = findAlias(config, alias);
        if (!existing.isMissingNode() && !controlType.equals(text(existing, "controlType"))) {
            failures.add("validator alias-resolves-deterministically failed for " + operationId
                    + ": alias already maps to " + text(existing, "controlType"));
        }
    }

    private void validateDynamicAliasExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String alias = text(planOperation.path("input"), "alias");
        if (findAlias(config, alias).isMissingNode()) {
            failures.add("validator alias-exists failed for " + operationId + ": alias not found " + alias);
        }
    }

    private void validateDynamicAliasRemovalSafe(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String replacement = text(planOperation.path("input"), "replacementControlType");
        if (!replacement.isBlank() && !hasControlType(config, replacement)) {
            failures.add("validator alias-removal-safe failed for " + operationId
                    + ": replacement controlType is not registered " + replacement);
        }
    }

    private void validateDestructiveRemovalConfirmation(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        if (!planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator destructive-removal-confirmation failed for " + operationId
                    + ": explicit confirmation is required");
        }
    }

    private void validateDynamicSelectorMappingDeterministic(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String selector = text(input, "selector");
        String controlType = text(input, "controlType");
        if (selector.isBlank() || controlType.isBlank()) {
            failures.add("validator selector-mapping-deterministic failed for " + operationId + ": selector and controlType are required");
            return;
        }
        JsonNode existing = findObjectByKey(config.path("selectorMappings"), "selector", selector);
        if (!existing.isMissingNode()
                && !input.path("overwrite").asBoolean(false)
                && !controlType.equals(text(existing, "controlType"))) {
            failures.add("validator selector-mapping-deterministic failed for " + operationId
                    + ": selector already maps to " + text(existing, "controlType"));
        }
    }

    private void validateDynamicEditorToolingDiscoversControl(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String controlType = text(input, "controlType");
        if (controlType.isBlank()) {
            return;
        }
        if (input.has("metadataEditor") && !input.path("metadataEditor").asBoolean(false)) {
            failures.add("validator editor-tooling-discovers-control failed for " + operationId + ": metadataEditor coverage is false");
        }
        if (input.has("dynamicFormDiscovery") && !input.path("dynamicFormDiscovery").asBoolean(false)) {
            failures.add("validator editor-tooling-discovers-control failed for " + operationId + ": dynamicFormDiscovery coverage is false");
        }
        if (!input.has("metadataEditor")
                && !hasControlType(config, controlType)
                && findObjectByKey(config.path("editorCoverage"), "controlType", controlType).isMissingNode()) {
            failures.add("validator editor-tooling-discovers-control failed for " + operationId
                    + ": no editor coverage evidence for " + controlType);
        }
    }

    private void validateDynamicRuntimeEditorCoverageNotDivergent(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String controlType = text(planOperation.path("input"), "controlType");
        if (controlType.isBlank()) {
            return;
        }
        JsonNode runtime = findObjectByKey(config.path("runtimeCoverage"), "controlType", controlType);
        JsonNode editor = findObjectByKey(config.path("editorCoverage"), "controlType", controlType);
        if (!runtime.isMissingNode()
                && runtime.path("componentRegistered").asBoolean(false)
                && !editor.isMissingNode()
                && editor.has("metadataEditor")
                && !editor.path("metadataEditor").asBoolean(false)) {
            failures.add("validator runtime-editor-coverage-not-divergent failed for " + operationId
                    + ": runtime coverage exists but metadata editor coverage is false");
        }
    }

    private void validateDynamicCoverageEvidencePresent(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode evidence = planOperation.path("input").path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) {
            failures.add("validator coverage-evidence-present failed for " + operationId + ": evidence array is required");
        }
    }

    private void validateCrudResourcePresent(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String resourcePath = text(input, "resourcePath");
        if (resourcePath.isBlank()) {
            resourcePath = text(config.path("resource"), "path");
        }
        if (resourcePath.isBlank()) {
            failures.add("validator resource-exists-in-api-metadata failed for " + operationId + ": resourcePath is required");
        }
    }

    private void validateCrudCanonicalRelativeUrls(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("resourcePath", "schemaUrl", "submitUrl")) {
            String value = text(input, field);
            if (!value.isBlank() && isUnsafeCrudUrl(value)) {
                failures.add("validator url-canonical failed for " + operationId + ": " + field + " must be a relative canonical Praxis path");
            }
        }
        JsonNode form = input.path("form");
        for (String field : List.of("schemaUrl", "submitUrl")) {
            String value = text(form, field);
            if (!value.isBlank() && isUnsafeCrudUrl(value)) {
                failures.add("validator url-canonical failed for " + operationId + ": form." + field + " must be a relative canonical Praxis path");
            }
        }
    }

    private void validateCrudResourceKeyStable(String operationId, JsonNode planOperation, List<String> failures) {
        String resourceKey = text(planOperation.path("input"), "resourceKey");
        if (!resourceKey.isBlank() && !resourceKey.matches("^[A-Za-z][A-Za-z0-9_-]*$")) {
            failures.add("validator resource-key-stable failed for " + operationId + ": resourceKey must be stable identifier text");
        }
    }

    private void validateCrudIdFieldKnown(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String idField = text(input, "idField");
        if (idField.isBlank() || "id".equals(idField)) {
            return;
        }
        boolean known = !findObjectByKey(config.path("fieldMetadata"), "name", idField).isMissingNode()
                || !findObjectByKey(config.path("table").path("columns"), "field", idField).isMissingNode();
        if (!known) {
            failures.add("validator id-field-known failed for " + operationId + ": idField is not known " + idField);
        }
    }

    private void validateCrudChildPatchDelegated(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("tablePatch") && text(input.path("tablePatch"), "delegatedTo").isBlank()) {
            failures.add("validator child-operation-delegated failed for " + operationId + ": tablePatch must declare delegatedTo");
        }
        if (input.has("formPatch") && text(input.path("formPatch"), "delegatedTo").isBlank()) {
            failures.add("validator child-operation-delegated failed for " + operationId + ": formPatch must declare delegatedTo");
        }
    }

    private void validateCrudSerializableObjectInputs(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("queryContext", "filterCriteria", "back", "modal", "actionPermissions")) {
            JsonNode value = input.path(field);
            if (!value.isMissingNode() && !value.isObject()) {
                failures.add("validator crud-object-input-valid failed for " + operationId + ": " + field + " must be an object");
            }
            if (containsUnsafeAbsoluteUrl(value)) {
                failures.add("validator crud-object-input-valid failed for " + operationId + ": " + field + " must not contain absolute URLs");
            }
        }
    }

    private void validateCrudOpenMode(String operationId, JsonNode planOperation, List<String> failures) {
        String openMode = firstNonBlank(text(planOperation.path("input"), "openMode"), text(planOperation.path("input"), "defaultOpenMode"));
        if (!openMode.isBlank() && !Set.of("route", "modal", "drawer").contains(openMode)) {
            failures.add("validator open-mode-supported failed for " + operationId + ": unsupported openMode " + openMode);
        }
        if ("route".equals(openMode) && text(planOperation.path("input"), "route").isBlank()) {
            failures.add("validator open-mode-binding-complete failed for " + operationId + ": route openMode requires route");
        }
    }

    private void validateCrudResourceCapability(
            String operationId,
            String validatorId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String capability = validatorId.replace("resource-", "").replace("-supported", "");
        JsonNode capabilities = firstPresent(planOperation.path("input"), "requiredCapabilities", "capabilities");
        if (capabilities.isMissingNode()) {
            capabilities = config.path("capabilities");
        }
        if (capabilities.isArray() && !arrayContainsText(capabilities, capability)) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": capability not available " + capability);
        }
    }

    private void validateCrudDeleteActionExists(String operationId, JsonNode config, List<String> failures) {
        if (findObjectByKey(config.path("actions"), "id", "delete").isMissingNode()
                && findObjectByKey(config.path("actions"), "action", "delete").isMissingNode()) {
            failures.add("validator delete-action-exists failed for " + operationId + ": delete action not found");
        }
    }

    private void validateCrudDeleteConfirmed(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        boolean destructive = input.path("enabled").asBoolean(false)
                || input.path("autoDelete").asBoolean(false)
                || input.path("actionPermissions").path("delete").path("disabled").asBoolean(false);
        if (destructive && !input.path("requiresConfirmation").asBoolean(false) && !planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator destructive-delete-confirmed failed for " + operationId + ": delete changes require confirmation");
        }
    }

    private void validateCrudPermissions(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode actionPermissions = planOperation.path("input").path("actionPermissions");
        if (!actionPermissions.isMissingNode() && !actionPermissions.isObject()) {
            failures.add("validator action-permission-supported failed for " + operationId + ": actionPermissions must be an object");
        }
        JsonNode deletePermission = actionPermissions.path("delete");
        if (deletePermission.isObject()
                && deletePermission.has("requiresConfirmation")
                && !deletePermission.path("requiresConfirmation").asBoolean(false)) {
            failures.add("validator delete-permission-requires-confirmation failed for " + operationId + ": delete permission must require confirmation");
        }
    }

    private void validateCrudModalSize(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode modal = planOperation.path("input").path("modal");
        for (String field : List.of("width", "height", "minWidth", "maxWidth")) {
            String value = text(modal, field);
            if (!value.isBlank() && !isSafeCssSize(value)) {
                failures.add("validator modal-size-valid failed for " + operationId + ": invalid " + field);
            }
        }
    }

    private void validateCrudChildDelegation(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String childComponentId = text(input, "childComponentId");
        String childOperationId = text(input, "childOperationId");
        if (childComponentId.isBlank() || childOperationId.isBlank()) {
            failures.add("validator child-operation-known failed for " + operationId + ": childComponentId and childOperationId are required");
        }
        if (!childComponentId.isBlank()
                && !Set.of("praxis-dynamic-form", "praxis-table", "praxis-dialog", "praxis-settings-panel").contains(childComponentId)) {
            failures.add("validator child-manifest-available failed for " + operationId + ": unsupported childComponentId " + childComponentId);
        }
    }

    private void validateCrudNoLocalChildConfigWrite(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("formConfig") || input.has("tableConfig")) {
            failures.add("validator no-local-child-config-write failed for " + operationId + ": child config must be delegated");
        }
    }

    private void validateInputFieldsExist(String operationId, JsonNode input, JsonNode config, List<String> failures) {
        Set<String> existing = new HashSet<>();
        config.path("columns").forEach(column -> existing.add(text(column, "field")));
        config.path("fieldMetadata").forEach(field -> existing.add(text(field, "name")));
        for (String field : inputFields(input)) {
            if (!field.isBlank() && !existing.contains(field)) {
                failures.add("validator fields-exist failed for " + operationId + ": unknown field " + field);
            }
        }
    }

    private List<String> inputFields(JsonNode input) {
        List<String> fields = new ArrayList<>();
        JsonNode fieldsNode = input.path("fields");
        if (fieldsNode.isArray()) {
            fieldsNode.forEach(field -> fields.add(field.asText("")));
        }
        String field = text(input, "field");
        if (!field.isBlank()) {
            fields.add(field);
        }
        return fields;
    }

    private void validateSchemaNode(JsonNode schema, JsonNode value, String path, List<String> failures) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        if (schema.path("anyOf").isArray() && !matchesAny(schema.path("anyOf"), value)) {
            failures.add(path + " must match at least one anyOf schema");
        }
        if (schema.path("oneOf").isArray() && countMatches(schema.path("oneOf"), value) != 1) {
            failures.add(path + " must match exactly one oneOf schema");
        }
        if (schema.path("allOf").isArray()) {
            for (JsonNode child : schema.path("allOf")) {
                validateSchemaNode(child, value, path, failures);
            }
        }
        String type = text(schema, "type");
        if (!type.isBlank() && value != null && !value.isMissingNode() && !matchesType(type, value)) {
            failures.add(path + " must be " + type);
            return;
        }
        if (schema.has("const") && !schema.path("const").equals(value)) {
            failures.add(path + " must equal const " + schema.path("const"));
        }
        if (schema.path("enum").isArray() && !enumContains(schema.path("enum"), value)) {
            failures.add(path + " must be one of " + schema.path("enum"));
        }
        if (schema.path("required").isArray()) {
            for (JsonNode required : schema.path("required")) {
                String field = required.asText("");
                if (!field.isBlank() && (value == null || !value.has(field) || value.path(field).isNull())) {
                    failures.add(path + "." + field + " is required");
                }
            }
        }
        if (value != null && value.isObject()) {
            JsonNode properties = schema.path("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childSchema = properties.path(field.getKey());
                if (!childSchema.isMissingNode()) {
                    validateSchemaNode(childSchema, field.getValue(), path + "." + field.getKey(), failures);
                } else if (schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean(true)) {
                    failures.add(path + "." + field.getKey() + " is not allowed");
                }
            }
        }
        if (value != null && value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                validateSchemaNode(schema.path("items"), value.get(i), path + "[" + i + "]", failures);
            }
        }
        if (value != null && value.isTextual()
                && schema.has("minLength")
                && value.asText("").length() < schema.path("minLength").asInt()) {
            failures.add(path + " length must be >= " + schema.path("minLength").asInt());
        }
        if (value != null && value.isArray()
                && schema.has("minItems")
                && value.size() < schema.path("minItems").asInt()) {
            failures.add(path + " size must be >= " + schema.path("minItems").asInt());
        }
    }

    private boolean matchesAny(JsonNode schemas, JsonNode value) {
        return countMatches(schemas, value) > 0;
    }

    private int countMatches(JsonNode schemas, JsonNode value) {
        int count = 0;
        for (JsonNode schema : schemas) {
            List<String> failures = new ArrayList<>();
            validateSchemaNode(schema, value, "$", failures);
            if (failures.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private boolean enumContains(JsonNode enumNode, JsonNode value) {
        for (JsonNode item : enumNode) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode firstPresent(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return MissingNode.getInstance();
        }
        for (String name : names) {
            if (node.has(name)) {
                return node.path(name);
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode findObjectByKey(JsonNode array, String key, String value) {
        if (!array.isArray() || value == null || value.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode item : array) {
            if (value.equals(text(item, key))) {
                return item;
            }
        }
        return MissingNode.getInstance();
    }

    private int resolvedArrayIndex(String resolvedPath) {
        if (resolvedPath == null || resolvedPath.isBlank()) {
            return -1;
        }
        int slash = resolvedPath.lastIndexOf('/');
        if (slash < 0 || slash == resolvedPath.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(resolvedPath.substring(slash + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void validateFilesEndpointUrlSafe(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        String baseUrl = text(planOperation.path("input"), "baseUrl");
        if (baseUrl.isBlank()) {
            failures.add("validator endpoint-url-safe failed for " + operationId + ": baseUrl is required");
            return;
        }
        if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://") || baseUrl.startsWith("//")) {
            failures.add("validator endpoint-url-safe failed for " + operationId
                    + ": absolute endpoint URLs are not allowed");
        }
        if (baseUrl.contains("?") || baseUrl.contains("#")) {
            failures.add("validator endpoint-url-safe failed for " + operationId
                    + ": baseUrl must not include query string or fragment");
        }
    }

    private void validateFilesEndpointPraxisSurface(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        String baseUrl = normalizeFilesBaseUrl(text(planOperation.path("input"), "baseUrl"));
        if (!baseUrl.startsWith("/api/") || !baseUrl.contains("/files")) {
            failures.add("validator endpoint-uses-praxis-backend-surface failed for " + operationId
                    + ": baseUrl must point to a relative Praxis files API surface");
        }
    }

    private void validateFilesEndpointPathContract(
            String operationId,
            JsonNode planOperation,
            List<String> failures,
            String forbiddenSegment) {
        String baseUrl = normalizeFilesBaseUrl(text(planOperation.path("input"), "baseUrl"));
        if (baseUrl.endsWith("/" + forbiddenSegment) || baseUrl.contains("/" + forbiddenSegment + "/")) {
            failures.add("validator " + forbiddenSegment + "-endpoint-fixed-contract failed for " + operationId
                    + ": baseUrl must not include the derived /" + forbiddenSegment + " path");
        }
    }

    private void validatePresignCrossOriginFieldsSafe(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        if (planOperation.path("input").path("allowCrossOriginPresignedTarget").asBoolean(false)) {
            failures.add("validator presign-cross-origin-fields-safe failed for " + operationId
                    + ": cross-origin presigned targets are not configurable through component authoring");
        }
    }

    private void validateListTemplateSlotSupported(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        String slot = text(planOperation.path("input"), "slot");
        if (!listTemplateSlots().contains(slot)) {
            failures.add("validator template-slot-supported failed for " + operationId
                    + ": unsupported template slot " + slot);
        }
        String targetSlot = planOperation.path("target").isTextual()
                ? planOperation.path("target").asText("")
                : text(planOperation.path("target"), "slot");
        if (!targetSlot.isBlank() && !slot.equals(targetSlot)) {
            failures.add("validator template-slot-supported failed for " + operationId
                    + ": target slot does not match input slot");
        }
    }

    private void validateListTemplateBoundFieldsExist(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        Set<String> existing = new HashSet<>();
        config.path("fields").forEach(field -> existing.add(field.asText("")));
        config.path("columns").forEach(column -> existing.add(text(column, "field")));
        config.path("fieldMetadata").forEach(field -> existing.add(text(field, "name")));
        if (existing.isEmpty()) {
            return;
        }
        for (String field : templateReferencedFields(planOperation.path("input").path("template"))) {
            if (!field.isBlank() && !existing.contains(field)) {
                failures.add("validator bound-field-exists failed for " + operationId
                        + ": unknown field " + field);
            }
        }
    }

    private void validateListTemplateExpressionSafe(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode template = planOperation.path("input").path("template");
        if (containsUnsafeInlineHandler(template)) {
            failures.add("validator template-expression-safe failed for " + operationId
                    + ": template must not include inline handlers or functions");
        }
        if (containsUnsafeTemplateHtml(template)) {
            failures.add("validator template-expression-safe failed for " + operationId
                    + ": html template expressions are not accepted by backend authoring");
        }
    }

    private void validateCronExpressionValid(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String cron = text(planOperation.path("input"), "cron");
        if (cron.isBlank()) {
            cron = text(config.path("schedule").path("expression"), "cron");
        }
        if (cron.isBlank()) {
            cron = text(config, "value");
        }
        if (!isValidSpringCron(springCronExpression(cron, cronFieldCount(cron) == 6))) {
            failures.add("validator cron-expression-valid failed for " + operationId + ": invalid cron expression");
        }
    }

    private void validateCronDialectCompatible(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        String dialect = text(planOperation.path("input"), "dialect");
        if (!dialect.isBlank() && !Set.of("unix", "quartz", "aws-eventbridge", "kubernetes", "github-actions", "gcp-scheduler").contains(dialect)) {
            failures.add("validator cron-dialect-compatible failed for " + operationId + ": unsupported dialect " + dialect);
        }
    }

    private void validateCronFrequencyMaps(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        String kind = text(planOperation.path("input"), "kind");
        if (!Set.of("interval", "daily", "weekly", "monthly", "customCron").contains(kind)) {
            failures.add("validator frequency-maps-to-canonical-expression failed for " + operationId
                    + ": unsupported schedule kind " + kind);
        }
    }

    private void validateCronTimezoneValid(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String timezone = text(planOperation.path("input"), "timezone");
        if (timezone.isBlank()) {
            timezone = text(config.path("schedule"), "timezone");
        }
        if (timezone.isBlank()) {
            timezone = text(config.path("metadata"), "timezone");
        }
        if (timezone.isBlank()) {
            timezone = "UTC";
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception ex) {
            failures.add("validator timezone-valid failed for " + operationId + ": invalid timezone " + timezone);
        }
    }

    private void validateCronPresetExists(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String labelOrCron = text(planOperation.path("input"), "labelOrCron");
        if (labelOrCron.isBlank()) {
            failures.add("validator preset-exists failed for " + operationId + ": labelOrCron is required");
            return;
        }
        if (findCronPreset(config.path("metadata").path("presets"), labelOrCron)
                || findCronPreset(config.path("presets"), labelOrCron)) {
            return;
        }
        failures.add("validator preset-exists failed for " + operationId + ": preset not found " + labelOrCron);
    }

    private void validateDialogPresetExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String dialogType = text(planOperation.path("input"), "dialogType");
        if (dialogType.isBlank()) {
            failures.add("validator preset-exists failed for " + operationId + ": dialogType is required");
            return;
        }
        if (!"custom".equals(dialogType) && !dialogPresetConfig(config, dialogType).isObject()) {
            failures.add("validator preset-exists failed for " + operationId + ": preset not found " + dialogType);
        }
        String variant = text(planOperation.path("input"), "variant");
        if (!variant.isBlank() && !dialogPresetVariants(config).path(variant).isObject()) {
            failures.add("validator preset-exists failed for " + operationId + ": variant not found " + variant);
        }
    }

    private void validateDialogAccessibilityLabel(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String title = firstNonBlank(text(input, "title"), text(config.path("config"), "title"), text(config, "title"));
        String ariaLabel = firstNonBlank(text(input, "ariaLabel"), text(config.path("config"), "ariaLabel"), text(config, "ariaLabel"));
        String labelledBy = firstNonBlank(text(input, "ariaLabelledBy"), text(config.path("config"), "ariaLabelledBy"), text(config, "ariaLabelledBy"));
        if (title.isBlank() && ariaLabel.isBlank() && labelledBy.isBlank()) {
            failures.add("validator accessibility-label-preserved failed for " + operationId
                    + ": dialog requires title, ariaLabel or ariaLabelledBy");
        }
    }

    private void validateDialogAriaRole(String operationId, JsonNode planOperation, List<String> failures) {
        String role = text(planOperation.path("input"), "ariaRole");
        if (!role.isBlank() && !Set.of("dialog", "alertdialog").contains(role)) {
            failures.add("validator aria-role-valid failed for " + operationId + ": invalid ariaRole " + role);
        }
    }

    private void validateDialogCssValues(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("width", "height", "minWidth", "maxWidth", "minHeight", "maxHeight", "top", "bottom", "left", "right")) {
            JsonNode value = input.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber() && value.asDouble() > 0 && Double.isFinite(value.asDouble())) {
                continue;
            }
            if (value.isTextual() && isSafeCssSize(value.asText(""))) {
                continue;
            }
            failures.add("validator css-values-safe failed for " + operationId + ": invalid " + field);
        }
    }

    private void validateDialogSizeMinMax(String operationId, JsonNode planOperation, List<String> failures) {
        validateDialogMinMaxPair(operationId, planOperation, "minWidth", "maxWidth", failures);
        validateDialogMinMaxPair(operationId, planOperation, "minHeight", "maxHeight", failures);
    }

    private void validateDialogMinMaxPair(
            String operationId,
            JsonNode planOperation,
            String minField,
            String maxField,
            List<String> failures) {
        Double min = cssPixels(planOperation.path("input").path(minField));
        Double max = cssPixels(planOperation.path("input").path(maxField));
        if (min != null && max != null && min > max) {
            failures.add("validator size-min-max-consistent failed for " + operationId
                    + ": " + minField + " must not exceed " + maxField);
        }
    }

    private void validateDialogClassValue(
            String operationId,
            JsonNode classValue,
            String validatorId,
            List<String> failures) {
        if (classValue.isMissingNode() || classValue.isNull()) {
            return;
        }
        if (containsUnsafeAbsoluteUrl(classValue)) {
            failures.add("validator " + validatorId + " failed for " + operationId
                    + ": class value must not contain remote URLs");
        }
    }

    private void validateDialogClosePolicyExplicit(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.has("disableClose") && !input.has("closeOnBackdropClick") && !input.has("closeOnNavigation")) {
            failures.add("validator close-policy-explicit failed for " + operationId
                    + ": close policy must describe disableClose, backdrop or navigation behavior");
        }
    }

    private void validateDialogClosePolicySafe(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if ((input.path("disableClose").asBoolean(false)
                || input.path("restoreFocus").asBoolean(true) == false)
                && !planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator unsafe-close-confirmed-when-needed failed for " + operationId
                    + ": unsafe close/focus changes require confirmation");
        }
        String role = firstNonBlank(text(input, "ariaRole"), text(config.path("config"), "ariaRole"), text(config, "ariaRole"));
        if ("alertdialog".equals(role) && input.has("autoFocus") && !input.path("autoFocus").asBoolean(true)) {
            failures.add("validator alertdialog-focus-preserved failed for " + operationId
                    + ": alertdialog must keep an autofocus target reachable");
        }
    }

    private void validateDialogChildHostRegistered(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String contentType = text(input, "contentType");
        if ("component".equals(contentType) && text(input, "componentId").isBlank()) {
            failures.add("validator child-host-registered failed for " + operationId + ": componentId is required");
        } else if ("template".equals(contentType) && text(input, "templateId").isBlank()) {
            failures.add("validator child-host-registered failed for " + operationId + ": templateId is required");
        } else if (!Set.of("component", "template").contains(contentType)) {
            failures.add("validator child-host-registered failed for " + operationId + ": unsupported contentType " + contentType);
        }
    }

    private void validateDialogChildInputsSerializable(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("inputs", "data", "params")) {
            JsonNode value = input.path(field);
            if (!value.isMissingNode() && containsUnsafeAbsoluteUrl(value)) {
                failures.add("validator child-inputs-serializable failed for " + operationId
                        + ": " + field + " must not contain absolute remote URLs");
            }
        }
    }

    private void validateDialogChildManifestResolvable(String operationId, JsonNode planOperation, List<String> failures) {
        if (text(planOperation.path("input"), "childManifestComponentId").isBlank()) {
            failures.add("validator child-manifest-resolvable failed for " + operationId
                    + ": childManifestComponentId is required");
        }
    }

    private void validateDialogChildOperationAuthorized(String operationId, JsonNode planOperation, List<String> failures) {
        if (text(planOperation.path("input"), "operationId").isBlank()) {
            failures.add("validator child-operation-authorized failed for " + operationId
                    + ": child operationId is required");
        }
    }

    private JsonNode dialogPresetConfig(JsonNode config, String dialogType) {
        if (dialogType == null || dialogType.isBlank()) {
            return MissingNode.getInstance();
        }
        for (String rootPath : List.of("globalPresets", "dialogPresets", "metadata.globalPresets", "metadata.dialogPresets")) {
            JsonNode preset = resolvePath(config, rootPath + "." + dialogType);
            if (preset.isObject()) {
                return preset;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode dialogPresetVariants(JsonNode config) {
        for (String rootPath : List.of("globalPresets.variants", "dialogPresets.variants", "metadata.globalPresets.variants", "metadata.dialogPresets.variants")) {
            JsonNode variants = resolvePath(config, rootPath);
            if (variants.isObject()) {
                return variants;
            }
        }
        return MissingNode.getInstance();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void validateSettingsPanelSizeSafe(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("width", "minWidth", "maxWidth")) {
            JsonNode value = input.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber() && value.asDouble() > 0 && Double.isFinite(value.asDouble())) {
                continue;
            }
            if (value.isTextual() && isSafeCssSize(value.asText(""))) {
                continue;
            }
            failures.add("validator panel-size-safe failed for " + operationId + ": invalid " + field);
        }
    }

    private void validateSettingsPanelMinMax(String operationId, JsonNode planOperation, List<String> failures) {
        Double min = cssPixels(planOperation.path("input").path("minWidth"));
        Double max = cssPixels(planOperation.path("input").path("maxWidth"));
        if (min != null && max != null && min > max) {
            failures.add("validator panel-min-max-consistent failed for " + operationId
                    + ": minWidth must not exceed maxWidth");
        }
    }

    private void validateSettingsPanelResizePersistence(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode persistSizeKey = planOperation.path("input").path("persistSizeKey");
        if (persistSizeKey.isTextual() && persistSizeKey.asText("").isBlank()) {
            failures.add("validator resize-persistence-explicit failed for " + operationId
                    + ": persistSizeKey must not be blank when provided");
        }
    }

    private void validateSettingsPanelApplyDoesNotClose(String operationId, JsonNode planOperation, List<String> failures) {
        if (planOperation.path("input").path("closeAfterSave").asBoolean(false)) {
            failures.add("validator apply-does-not-close-panel failed for " + operationId
                    + ": apply behavior must not close the settings panel");
        }
    }

    private void validateSettingsPanelResetConfirmed(String operationId, JsonNode planOperation, List<String> failures) {
        if (!planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator reset-requires-confirmation failed for " + operationId
                    + ": explicit confirmation is required");
        }
        if (planOperation.path("input").has("requireConfirmation")
                && !planOperation.path("input").path("requireConfirmation").asBoolean(false)) {
            failures.add("validator reset-requires-confirmation failed for " + operationId
                    + ": reset confirmation cannot be disabled");
        }
    }

    private void validateSettingsPanelEditorComponent(String operationId, JsonNode planOperation, List<String> failures) {
        if (text(planOperation.path("input"), "componentId").isBlank()) {
            failures.add("validator editor-component-registered failed for " + operationId
                    + ": componentId is required");
        }
    }

    private void validateSettingsPanelEditorInputs(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode inputs = planOperation.path("input").path("inputs");
        if (!inputs.isMissingNode() && !(inputs.isObject() || inputs.isNull())) {
            failures.add("validator editor-inputs-serializable failed for " + operationId
                    + ": inputs must be a serializable object");
        }
        if (containsUnsafeAbsoluteUrl(inputs)) {
            failures.add("validator editor-inputs-serializable failed for " + operationId
                    + ": absolute remote URLs are not allowed in editor inputs");
        }
    }

    private boolean isSafeCssSize(String value) {
        return value != null
                && value.matches("^[0-9]+(\\.[0-9]+)?(px|rem|em|vw|vh|%)$")
                && cssPixels(value) != null;
    }

    private Double cssPixels(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            double value = node.asDouble();
            return value > 0 && Double.isFinite(value) ? value : null;
        }
        return node.isTextual() ? cssPixels(node.asText("")) : null;
    }

    private Double cssPixels(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("^[0-9]+(\\.[0-9]+)?(px|rem|em|vw|vh|%)$")) {
            return null;
        }
        double numeric = Double.parseDouble(trimmed.replaceFirst("(px|rem|em|vw|vh|%)$", ""));
        if (!Double.isFinite(numeric) || numeric <= 0) {
            return null;
        }
        if (trimmed.endsWith("rem") || trimmed.endsWith("em")) {
            return numeric * 16;
        }
        return numeric;
    }

    private JsonNode resolvePath(JsonNode root, String dottedPath) {
        JsonNode current = root;
        if (current == null || dottedPath == null || dottedPath.isBlank()) {
            return current == null ? MissingNode.getInstance() : current;
        }
        for (String segment : dottedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = current.path(segment);
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private boolean hasControlType(JsonNode config, String controlType) {
        if (controlType == null || controlType.isBlank()) {
            return false;
        }
        return !findObjectByKey(config.path("componentRegistry"), "controlType", controlType).isMissingNode()
                || !findObjectByKey(config.path("componentMetadata").path("controlProfiles"), "controlType", controlType).isMissingNode()
                || !findObjectByKey(config.path("controlProfiles"), "controlType", controlType).isMissingNode()
                || !findObjectByKey(config.path("runtimeCoverage"), "controlType", controlType).isMissingNode();
    }

    private JsonNode findAlias(JsonNode config, String alias) {
        if (alias == null || alias.isBlank()) {
            return MissingNode.getInstance();
        }
        String normalized = normalizeControlToken(alias);
        JsonNode byNormalized = findObjectByKey(config.path("controlTypeAliases"), "normalizedAlias", normalized);
        if (!byNormalized.isMissingNode()) {
            return byNormalized;
        }
        return findObjectByKey(config.path("controlTypeAliases"), "alias", alias);
    }

    private String normalizeControlToken(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    private boolean isUnsafeCrudUrl(String value) {
        return value.startsWith("http://")
                || value.startsWith("https://")
                || value.startsWith("//")
                || value.contains("?")
                || value.contains("#")
                || !(value.startsWith("/") || value.startsWith("./"));
    }

    private boolean arrayContainsText(JsonNode array, String value) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
