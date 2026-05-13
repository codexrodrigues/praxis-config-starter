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
            "field-metadata-shape-canonical",
            "field-path-supported-by-editor",
            "metadata-round-trip",
            "control-type-exists-in-discovery",
            "editor-coverage-exists",
            "option-source-shape-canonical",
            "remote-option-source-governed",
            "cascade-backend-shape-preserved",
            "cascade-fields-exist",
            "cascade-cycle-free",
            "renderer-editor-type-registered",
            "visual-editor-coverage-required",
            "validation-rule-canonical",
            "context-validator-registered",
            "context-hint-shape-canonical",
            "context-hint-i18n-compatible",
            "normalization-preserves-canonical-fields",
            "runtime-editor-round-trip",
            "manual-field-id-unique",
            "control-type-discovered",
            "host-template-field-exists",
            "metadata-bridge-does-not-redefine-schema",
            "manual-form-round-trip",
            "manual-field-exists",
            "field-removal-confirmed",
            "layout-field-references-valid",
            "field-label-valid",
            "manual-layout-does-not-replace-host-template",
            "delegates-form-config",
            "toolbar-flags-supported",
            "metadata-bridge-gated-by-customization",
            "autosave-explicit",
            "autosave-debounce-safe",
            "autosave-storage-available",
            "persistence-key-deterministic",
            "submit-behavior-supported",
            "delegates-field-metadata",
            "snapshot-shape-canonical",
            "journey-exists",
            "diagnostics-preserved",
            "editorial-round-trip",
            "fallback-explicit",
            "fallback-diagnostic-backed",
            "fallback-scope-exists",
            "presentation-supported-by-runtime",
            "presentation-does-not-mutate-domain-data",
            "theme-tokens-valid",
            "adapter-exists",
            "adapter-supports-data-block",
            "adapter-component-valid",
            "data-block-id-unique",
            "data-block-exists",
            "field-binding-target-exists",
            "field-binding-path-valid",
            "widget-page-definition-valid",
            "no-legacy-grid-page-definition",
            "page-context-json-valid",
            "settings-panel-round-trip-valid",
            "canvas-columns-integer",
            "canvas-row-unit-valid",
            "canvas-gap-valid",
            "canvas-items-reference-existing-widgets",
            "widget-key-unique",
            "component-registered",
            "canvas-item-valid",
            "child-inputs-delegated",
            "widget-key-not-array-index",
            "widget-exists",
            "composition-links-cleaned",
            "canvas-item-targets-existing-widget",
            "canvas-overlap-policy-valid",
            "widget-shell-shape-valid",
            "settings-panel-bridge-available",
            "child-inputs-not-mutated",
            "composition-link-id-unique",
            "composition-endpoints-resolve",
            "nested-path-terminal-widget-key-required",
            "json-logic-condition-valid",
            "no-legacy-connections-write",
            "composition-link-exists",
            "composition-links-still-valid",
            "ui-composition-plan-valid",
            "compiled-page-valid",
            "widget-keys-unique",
            "state-path-valid",
            "state-layer-valid",
            "state-json-valid",
            "composition-state-links-still-valid",
            "preview-result-valid",
            "compiled-page-patch-present",
            "no-envelope-runtime-persistence",
            "runtime-page-valid",
            "page-identity-complete",
            "etag-policy-valid",
            "component-config-editor-respected",
            "no-local-child-input-write",
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
            "delegation-target-valid",
            "chart-document-shape",
            "chart-version-supported",
            "chart-type-supported",
            "chart-type-series-axis-compatible",
            "pie-single-metric",
            "combo-minimum-series",
            "series-field-exists",
            "series-field-aggregable",
            "series-id-unique",
            "series-exists",
            "destructive-series-removal-confirmed",
            "chart-keeps-required-metric",
            "axis-field-exists",
            "secondary-axis-combo-only",
            "cartesian-dimension-required",
            "remote-resource-in-api-metadata",
            "bound-fields-exist",
            "stats-operation-supported",
            "query-context-structured",
            "query-context-fields-exist",
            "query-context-safe-values",
            "cross-filter-output-structured",
            "event-target-governed",
            "event-mapping-fields-exist",
            "event-action-supported",
            "drilldown-target-governed",
            "selection-output-structured",
            "feature-toggle-valid",
            "editor-runtime-round-trip",
            "scope-supported",
            "condition-table-context-valid",
            "effect-registry-supported",
            "table-owned-config-delegated",
            "table-renderer-references-clean",
            "condition-fields-known",
            "condition-operators-supported",
            "effect-id-unique",
            "style-values-safe",
            "preview-class-not-persisted",
            "effect-exists",
            "runtime-effect-compilable",
            "empty-rule-policy-valid",
            "preset-key-known",
            "preset-effect-compilable",
            "animation-preset-known",
            "animation-alias-known",
            "animation-override-valid",
            "animation-runtime-supported",
            "table-manifest-operation-known",
            "table-target-valid",
            "node-id-unique",
            "node-type-supported",
            "parent-node-exists",
            "node-exists",
            "node-config-compatible",
            "edges-reference-existing-nodes",
            "edge-exists",
            "graph-valid",
            "graph-acyclic-where-required",
            "dsl-round-trip-stable",
            "variable-id-unique",
            "variable-scope-exists",
            "context-variable-reference-valid",
            "effect-targets-exist",
            "effect-properties-governed",
            "effect-values-valid",
            "registry-integrity-valid",
            "destructive-removal-confirmed");

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
                     "tab-or-link-exists", "panel-exists", "default-expanded-panel-exists" -> {
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
                case "field-exists-in-layout" -> validateFormFieldExistsInLayout(componentId, operation, planOperation, config, failures);
                case "visual-block-exists" -> validateResolvedTarget(componentId, operation, planOperation, config, validatorId, failures);
                case "visual-block-id-unique" -> validateFormVisualBlockIdUnique(operationId, planOperation, config, failures);
                case "layout-target-exists" -> validateFormLayoutTargetExists(operationId, planOperation, config, failures);
                case "rich-content-document-valid" -> validateFormRichContentDocument(operationId, planOperation, failures);
                case "editor-round-trip-preserve", "no-index-as-identity" -> {
                    // These are structural: operations resolve by stable ids and compile only canonical editor/runtime paths.
                }
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
                case "field-metadata-shape-canonical" -> validateMetadataFieldShape(operationId, config, failures);
                case "field-path-supported-by-editor" -> validateMetadataFieldPath(operationId, planOperation, failures);
                case "control-type-exists-in-discovery" -> validateMetadataControlTypeExists(operationId, planOperation, config, failures);
                case "editor-coverage-exists" -> validateMetadataEditorCoverage(operationId, planOperation, config, failures);
                case "option-source-shape-canonical" -> validateMetadataOptionSourceShape(operationId, planOperation, failures);
                case "remote-option-source-governed" -> validateMetadataRemoteOptionSourceGoverned(operationId, planOperation, failures);
                case "cascade-backend-shape-preserved" -> validateMetadataCascadeBackendShape(operationId, planOperation, failures);
                case "cascade-fields-exist" -> validateMetadataCascadeFieldsExist(operationId, planOperation, config, failures);
                case "cascade-cycle-free" -> validateMetadataCascadeCycleFree(operationId, planOperation, failures);
                case "renderer-editor-type-registered", "visual-editor-coverage-required" -> validateMetadataRendererEditorType(operationId, planOperation, failures);
                case "validation-rule-canonical" -> validateMetadataValidationRule(operationId, planOperation, failures);
                case "context-validator-registered" -> validateMetadataContextValidatorRegistered(operationId, planOperation, config, failures);
                case "context-hint-shape-canonical" -> validateMetadataContextHintShape(operationId, planOperation, failures);
                case "context-hint-i18n-compatible" -> validateMetadataContextHintI18n(operationId, planOperation, failures);
                case "normalization-preserves-canonical-fields" -> validateMetadataNormalization(operationId, planOperation, failures);
                case "metadata-round-trip", "runtime-editor-round-trip" -> {
                    // Round-trip is enforced by compiling fieldMetadata and normalizedSeed together.
                }
                case "manual-field-id-unique" -> validateManualFieldIdUnique(operationId, planOperation, config, failures);
                case "manual-field-exists", "host-template-field-exists" -> validateManualFieldExists(operationId, planOperation, config, failures);
                case "control-type-discovered" -> validateManualControlTypeDiscovered(operationId, planOperation, config, failures);
                case "metadata-bridge-does-not-redefine-schema" -> validateManualMetadataBridgeNoSchemaRedefinition(operationId, planOperation, failures);
                case "field-removal-confirmed" -> validateManualFieldRemovalConfirmed(operationId, planOperation, failures);
                case "layout-field-references-valid" -> validateManualLayoutFieldReferences(operationId, planOperation, config, failures);
                case "field-label-valid" -> validateManualFieldLabelValid(operationId, planOperation, failures);
                case "manual-layout-does-not-replace-host-template" -> validateManualLayoutHostTemplate(operationId, planOperation, failures);
                case "delegates-form-config" -> validateManualDelegatesFormConfig(operationId, planOperation, failures);
                case "toolbar-flags-supported" -> validateManualToolbarFlags(operationId, planOperation, failures);
                case "metadata-bridge-gated-by-customization" -> validateManualMetadataBridgeGated(operationId, planOperation, config, failures);
                case "autosave-explicit" -> validateManualAutosaveExplicit(operationId, planOperation, failures);
                case "autosave-debounce-safe" -> validateManualAutosaveDebounce(operationId, planOperation, failures);
                case "autosave-storage-available" -> validateManualAutosaveStorage(operationId, planOperation, config, failures);
                case "persistence-key-deterministic" -> validateManualPersistenceKey(operationId, planOperation, failures);
                case "submit-behavior-supported" -> validateManualSubmitBehavior(operationId, planOperation, failures);
                case "delegates-field-metadata" -> validateManualDelegatesFieldMetadata(operationId, planOperation, failures);
                case "manual-form-round-trip" -> {
                    // Round-trip is enforced by syncing currentConfig.fieldMetadata, currentFieldMetadata and form.fieldMetadata.
                }
                case "snapshot-shape-canonical" -> validateEditorialSnapshotShape(operationId, planOperation, config, failures);
                case "journey-exists" -> validateEditorialJourneyExists(operationId, planOperation, config, failures);
                case "step-exists" -> {
                    if ("praxis-editorial-forms".equals(componentId)) {
                        validateEditorialStepExists(operationId, planOperation, config, failures);
                    } else if (operation.path("target").path("required").asBoolean(false)) {
                        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                                componentId,
                                operation,
                                planOperation.path("target"),
                                config);
                        if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
                            failures.add("validator step-exists failed for " + operationId + ": "
                                    + String.join(", ", resolved.failures()));
                        }
                    }
                }
                case "diagnostics-preserved", "editorial-round-trip" -> {
                    // Enforced by compiling canonical snapshot/solution/runtimeContext paths together.
                }
                case "fallback-explicit" -> validateEditorialFallbackExplicit(operationId, planOperation, failures);
                case "fallback-diagnostic-backed" -> validateEditorialFallbackDiagnosticBacked(operationId, planOperation, failures);
                case "fallback-scope-exists" -> validateEditorialFallbackScopeExists(operationId, planOperation, config, failures);
                case "presentation-supported-by-runtime" -> validateEditorialPresentationSupported(operationId, planOperation, failures);
                case "presentation-does-not-mutate-domain-data" -> validateEditorialPresentationNoDomainMutation(operationId, planOperation, failures);
                case "theme-tokens-valid" -> validateEditorialThemeTokens(operationId, planOperation, failures);
                case "adapter-exists" -> validateEditorialAdapterExists(operationId, planOperation, config, failures);
                case "adapter-supports-data-block" -> validateEditorialAdapterSupportsDataBlock(operationId, planOperation, config, failures);
                case "adapter-component-valid" -> validateEditorialAdapterComponent(operationId, planOperation, failures);
                case "data-block-id-unique" -> validateEditorialDataBlockIdUnique(operationId, planOperation, config, failures);
                case "data-block-exists" -> validateEditorialDataBlockExists(operationId, planOperation, config, failures);
                case "field-binding-target-exists" -> validateEditorialFieldBindingTargetExists(operationId, planOperation, config, failures);
                case "field-binding-path-valid" -> validateEditorialFieldBindingPath(operationId, planOperation, failures);
                case "widget-page-definition-valid", "runtime-page-valid", "compiled-page-valid" ->
                        validatePageBuilderPageShape(operationId, planOperation, config, validatorId, failures);
                case "no-legacy-grid-page-definition", "no-legacy-connections-write", "no-envelope-runtime-persistence",
                     "settings-panel-round-trip-valid", "settings-panel-bridge-available",
                     "child-inputs-delegated", "child-inputs-not-mutated", "composition-links-cleaned",
                     "canvas-overlap-policy-valid", "composition-links-still-valid",
                     "composition-state-links-still-valid", "component-config-editor-respected" -> {
                    // Structural page-builder invariants are enforced by compiling only WidgetPageDefinition paths and delegation envelopes.
                }
                case "page-context-json-valid", "state-json-valid" -> validatePageBuilderSerializableInput(operationId, planOperation, validatorId, failures);
                case "canvas-columns-integer" -> validatePageBuilderPositiveInt(operationId, planOperation.path("input"), "columns", validatorId, failures);
                case "canvas-row-unit-valid" -> validatePageBuilderPositiveNumber(operationId, planOperation.path("input"), "rowUnit", validatorId, failures);
                case "canvas-gap-valid" -> validatePageBuilderNonNegativeNumber(operationId, planOperation.path("input"), "gap", validatorId, failures);
                case "canvas-items-reference-existing-widgets" -> validatePageBuilderCanvasItemsReferenceWidgets(operationId, planOperation, config, failures);
                case "canvas-item-valid" -> validatePageBuilderCanvasItem(operationId, planOperation, failures);
                case "canvas-item-targets-existing-widget" -> validatePageBuilderCanvasItemTarget(operationId, planOperation, config, failures);
                case "widget-key-unique" -> validatePageBuilderWidgetKeyUnique(operationId, planOperation, config, failures);
                case "widget-keys-unique" -> validatePageBuilderWidgetKeysUnique(operationId, planOperation, config, failures);
                case "widget-key-not-array-index" -> validatePageBuilderWidgetKeyNotIndex(operationId, planOperation, failures);
                case "widget-exists" -> validatePageBuilderWidgetExists(operationId, planOperation, config, failures);
                case "component-registered" -> validatePageBuilderComponentRegistered(operationId, planOperation, config, failures);
                case "widget-shell-shape-valid" -> validatePageBuilderShellShape(operationId, planOperation, failures);
                case "composition-link-id-unique" -> validatePageBuilderCompositionLinkUnique(operationId, planOperation, config, failures);
                case "composition-link-exists" -> validatePageBuilderCompositionLinkExists(operationId, planOperation, config, failures);
                case "composition-endpoints-resolve" -> validatePageBuilderCompositionEndpoints(operationId, planOperation, config, failures);
                case "nested-path-terminal-widget-key-required" -> validatePageBuilderNestedPathTerminal(operationId, planOperation, failures);
                case "json-logic-condition-valid" -> validateJsonLogicLikeInput(operationId, planOperation.path("input").path("condition"), failures);
                case "ui-composition-plan-valid" -> validatePageBuilderUiCompositionPlan(operationId, planOperation, failures);
                case "state-path-valid" -> validatePageBuilderStatePath(operationId, planOperation, failures);
                case "state-layer-valid" -> validatePageBuilderStateLayer(operationId, planOperation, failures);
                case "preview-result-valid", "compiled-page-patch-present" -> validatePageBuilderPreviewPatch(operationId, planOperation, config, failures);
                case "page-identity-complete" -> validatePageBuilderPageIdentity(operationId, planOperation, failures);
                case "etag-policy-valid" -> validatePageBuilderEtag(operationId, planOperation, failures);
                case "no-local-child-input-write" -> validatePageBuilderNoLocalChildInputWrite(operationId, planOperation, failures);
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
                case "chart-document-shape" -> validateChartDocumentShape(operationId, planOperation, config, failures);
                case "chart-version-supported" -> validateChartVersionSupported(operationId, planOperation, config, failures);
                case "chart-type-supported" -> validateChartTypeSupported(operationId, planOperation, config, failures);
                case "chart-type-series-axis-compatible" -> validateChartTypeSeriesAxisCompatible(operationId, planOperation, config, failures);
                case "pie-single-metric" -> validateChartPieSingleMetric(operationId, planOperation, config, failures);
                case "combo-minimum-series" -> validateChartComboMinimumSeries(operationId, planOperation, config, failures);
                case "series-field-exists", "axis-field-exists", "bound-fields-exist",
                     "query-context-fields-exist", "event-mapping-fields-exist" ->
                        validateChartInputFieldsExist(operationId, planOperation, config, failures);
                case "series-field-aggregable" -> validateChartAggregation(operationId, planOperation, failures);
                case "series-id-unique" -> validateChartSeriesUnique(operationId, planOperation, config, failures);
                case "series-exists" -> validateChartSeriesExists(operationId, planOperation, config, failures);
                case "destructive-series-removal-confirmed" -> validateChartSeriesRemovalConfirmed(operationId, planOperation, failures);
                case "chart-keeps-required-metric" -> validateChartKeepsMetric(operationId, config, failures);
                case "secondary-axis-combo-only" -> validateChartSecondaryAxisComboOnly(operationId, planOperation, config, failures);
                case "cartesian-dimension-required" -> validateChartCartesianDimensionRequired(operationId, planOperation, config, failures);
                case "remote-resource-in-api-metadata" -> validateChartRemoteResource(operationId, planOperation, failures);
                case "stats-operation-supported" -> validateChartStatsOperation(operationId, planOperation, failures);
                case "query-context-structured", "query-context-safe-values",
                     "cross-filter-output-structured", "selection-output-structured" ->
                        validateChartStructuredInput(operationId, planOperation, failures);
                case "event-target-governed", "drilldown-target-governed" ->
                        validateChartEventTarget(operationId, planOperation, config, failures);
                case "event-action-supported" -> validateChartEventAction(operationId, planOperation, failures);
                case "feature-toggle-valid" -> validateChartFeatureToggle(operationId, planOperation, failures);
                case "editor-runtime-round-trip" -> {
                    // Round-trip is enforced by compiling only canonical chartDocument paths consumed by editor and runtime.
                }
                case "scope-supported" -> validateTableRuleScope(operationId, planOperation, failures);
                case "condition-table-context-valid", "condition-operators-supported" -> validateTableRuleCondition(operationId, planOperation, failures);
                case "condition-fields-known" -> validateTableRuleConditionFields(operationId, planOperation, config, failures);
                case "effect-registry-supported" -> validateTableRuleEffectType(operationId, planOperation, failures);
                case "effect-id-unique" -> validateTableRuleEffectIdUnique(operationId, planOperation, config, failures);
                case "effect-exists" -> validateTableRuleEffectExists(operationId, planOperation, config, failures);
                case "style-values-safe" -> validateTableRuleStyleSafe(operationId, planOperation, failures);
                case "preset-key-known", "preset-effect-compilable" -> validateTableRulePreset(operationId, planOperation, failures);
                case "animation-preset-known", "animation-alias-known", "animation-override-valid", "animation-runtime-supported" ->
                        validateTableRuleAnimation(operationId, planOperation, failures);
                case "table-manifest-operation-known" -> validateTableRuleTableOperation(operationId, planOperation, failures);
                case "table-target-valid" -> validateTableRuleTableTarget(operationId, planOperation, failures);
                case "table-owned-config-delegated", "table-renderer-references-clean", "preview-class-not-persisted",
                     "runtime-effect-compilable", "empty-rule-policy-valid" -> {
                    // Enforced by compiled delegation and by refusing direct writes to table-owned config paths.
                }
                case "node-id-unique" -> validateVisualNodeIdUnique(operationId, planOperation, config, failures);
                case "node-type-supported" -> validateVisualNodeTypeSupported(operationId, planOperation, failures);
                case "parent-node-exists" -> validateVisualParentNodeExists(operationId, planOperation, config, failures);
                case "node-exists" -> validateVisualNodeExists(operationId, planOperation, config, failures);
                case "node-config-compatible" -> validateVisualNodeConfigCompatible(operationId, planOperation, failures);
                case "edges-reference-existing-nodes" -> validateVisualEdgesReferenceExistingNodes(operationId, planOperation, config, failures);
                case "edge-exists" -> validateVisualEdgeExists(operationId, planOperation, config, failures);
                case "graph-valid", "graph-acyclic-where-required" -> validateVisualGraphValid(operationId, config, failures);
                case "variable-id-unique" -> validateVisualVariableIdUnique(operationId, planOperation, config, failures);
                case "variable-scope-exists" -> validateVisualVariableScope(operationId, planOperation, failures);
                case "context-variable-reference-valid" -> validateVisualContextVariableReference(operationId, planOperation, failures);
                case "effect-targets-exist" -> validateVisualEffectTargets(operationId, planOperation, failures);
                case "effect-properties-governed" -> validateVisualEffectPropertiesGoverned(operationId, planOperation, failures);
                case "effect-values-valid" -> validateVisualEffectValues(operationId, planOperation, failures);
                case "destructive-removal-confirmed" -> validateDestructiveRemovalConfirmation(operationId, planOperation, failures);
                case "dsl-round-trip-stable", "registry-integrity-valid" -> {
                    validateVisualGraphValid(operationId, config, failures);
                    validateJsonLogicLikeInput(operationId, planOperation.path("input"), failures);
                }
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

    private void validateResolvedTarget(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            JsonNode config,
            String validatorId,
            List<String> failures) {
        AgenticAuthoringResolvedTarget resolved = targetResolverRegistry.resolve(
                componentId,
                operation,
                planOperation.path("target"),
                config);
        if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
            failures.add("validator " + validatorId + " failed for " + text(operation, "operationId") + ": "
                    + String.join(", ", resolved.failures()));
        }
    }

    private void validateFormFieldExistsInLayout(
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
        String fieldName = AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())
                ? text(resolved.value(), "name")
                : targetText(planOperation.path("target"));
        if (fieldName.isBlank() || !formLayoutContainsField(config.path("sections"), fieldName)) {
            failures.add("validator field-exists-in-layout failed for " + text(operation, "operationId")
                    + ": field is not referenced in layout " + fieldName);
        }
    }

    private void validateFormVisualBlockIdUnique(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String id = text(planOperation.path("input"), "id");
        if (!id.isBlank() && formLayoutContainsVisualBlock(config, id)) {
            failures.add("validator visual-block-id-unique failed for " + operationId + ": duplicate visual block id " + id);
        }
    }

    private void validateFormLayoutTargetExists(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String sectionId = text(input, "targetSectionId");
        String rowId = text(input, "targetRowId");
        String columnId = text(input, "targetColumnId");
        if (sectionId.isBlank() && rowId.isBlank() && columnId.isBlank()) {
            return;
        }
        if (!formLayoutContainsColumn(config, sectionId, rowId, columnId)) {
            failures.add("validator layout-target-exists failed for " + operationId
                    + ": target section/row/column not found");
        }
    }

    private void validateFormRichContentDocument(
            String operationId,
            JsonNode planOperation,
            List<String> failures) {
        JsonNode document = planOperation.path("input").path("document");
        if (document.isMissingNode()) {
            return;
        }
        if (!document.isObject()
                || !"praxis.rich-content".equals(text(document, "kind"))
                || text(document, "version").isBlank()
                || !document.path("nodes").isArray()) {
            failures.add("validator rich-content-document-valid failed for " + operationId
                    + ": document must be a praxis.rich-content object with version and nodes[]");
        }
    }

    private boolean formLayoutContainsColumn(JsonNode config, String sectionId, String rowId, String columnId) {
        if (sectionId.isBlank() || rowId.isBlank() || columnId.isBlank()) {
            return false;
        }
        for (JsonNode section : config.path("sections")) {
            if (!sectionId.equals(text(section, "id"))) {
                continue;
            }
            for (JsonNode row : section.path("rows")) {
                if (!rowId.equals(text(row, "id"))) {
                    continue;
                }
                for (JsonNode column : row.path("columns")) {
                    if (columnId.equals(text(column, "id"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean formLayoutContainsField(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return fieldName.equals(node.asText(""));
        }
        if (node.isObject()) {
            if (fieldName.equals(firstNonBlank(text(node, "field"), text(node, "fieldName")))) {
                return true;
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (formLayoutContainsField(values.next(), fieldName)) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (formLayoutContainsField(child, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean formLayoutContainsVisualBlock(JsonNode node, String id) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            if (id.equals(text(node, "id"))
                    && ("richContent".equals(text(node, "type")) || "richContent".equals(text(node, "kind")))) {
                return true;
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (formLayoutContainsVisualBlock(values.next(), id)) {
                    return true;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (formLayoutContainsVisualBlock(child, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateTableRuleScope(String operationId, JsonNode planOperation, List<String> failures) {
        String scope = text(planOperation.path("input"), "scope");
        if (!scope.isBlank() && !Set.of("cell", "row", "column", "table").contains(scope)) {
            failures.add("validator scope-supported failed for " + operationId + ": unsupported scope " + scope);
        }
        if ("cell".equals(scope) || "column".equals(scope)) {
            if (text(planOperation.path("input"), "columnKey").isBlank()) {
                failures.add("validator scope-supported failed for " + operationId + ": columnKey is required for " + scope + " scope");
            }
        }
    }

    private void validateTableRuleCondition(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode condition = planOperation.path("input").path("condition");
        if (condition.isMissingNode()) {
            return;
        }
        if (!condition.isObject() || condition.isEmpty()) {
            failures.add("validator condition-table-context-valid failed for " + operationId + ": condition must be a non-empty object");
            return;
        }
        if (containsUnsafeAbsoluteUrl(condition)) {
            failures.add("validator condition-table-context-valid failed for " + operationId + ": condition must not contain remote URLs");
        }
    }

    private void validateTableRuleConditionFields(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        Set<String> fields = new HashSet<>();
        collectTemplateReferencedFields(planOperation.path("input").path("condition"), fields);
        for (String field : fields) {
            if (!fieldExists(config.path("columns"), field) && !fieldExists(config.path("fieldMetadata"), field)) {
                failures.add("validator condition-fields-known failed for " + operationId + ": unknown field " + field);
            }
        }
    }

    private void validateTableRuleEffectType(String operationId, JsonNode planOperation, List<String> failures) {
        String effectType = text(planOperation.path("input"), "effectType");
        if (effectType.isBlank()) {
            JsonNode effect = planOperation.path("input").path("effect");
            effectType = firstNonBlank(text(effect, "effectType"), text(effect, "type"));
        }
        if (!effectType.isBlank() && !tableRuleEffectTypes().contains(effectType)) {
            failures.add("validator effect-registry-supported failed for " + operationId + ": unsupported effectType " + effectType);
        }
    }

    private void validateTableRuleEffectIdUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String ruleId = text(planOperation.path("input"), "ruleId");
        String effectId = text(planOperation.path("input"), "effectId");
        JsonNode rule = findTableRule(config, ruleId);
        if (rule != null && findObjectByAnyKey(rule.path("effects"), List.of("effectId", "id"), effectId) != null) {
            failures.add("validator effect-id-unique failed for " + operationId + ": duplicate effectId " + effectId);
        }
    }

    private void validateTableRuleEffectExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String ruleId = text(planOperation.path("input"), "ruleId");
        String effectId = text(planOperation.path("input"), "effectId");
        JsonNode rule = findTableRule(config, ruleId);
        if (rule == null || findObjectByAnyKey(rule.path("effects"), List.of("effectId", "id"), effectId) == null) {
            failures.add("validator effect-exists failed for " + operationId + ": effect not found " + effectId);
        }
    }

    private void validateTableRuleStyleSafe(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode payload = firstPresent(planOperation.path("input"), "payload", "effect");
        if (containsUnsafeAbsoluteUrl(payload) || containsUnsafeTemplateHtml(payload)) {
            failures.add("validator style-values-safe failed for " + operationId + ": unsafe style payload");
        }
    }

    private void validateTableRulePreset(String operationId, JsonNode planOperation, List<String> failures) {
        String presetKey = text(planOperation.path("input"), "presetKey");
        if (!Set.of("aprovado", "alerta", "erro", "info").contains(presetKey)) {
            failures.add("validator preset-key-known failed for " + operationId + ": unknown presetKey " + presetKey);
        }
    }

    private void validateTableRuleAnimation(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode animation = planOperation.path("input").path("animation");
        if (!animation.isObject()) {
            failures.add("validator animation-override-valid failed for " + operationId + ": animation must be an object");
            return;
        }
        String preset = text(animation, "preset");
        if (!preset.isBlank() && !tableRuleAnimationPresets().contains(preset)) {
            failures.add("validator animation-preset-known failed for " + operationId + ": unknown animation preset " + preset);
        }
        int duration = animation.path("durationMs").asInt(0);
        if (duration < 0 || duration > 10000) {
            failures.add("validator animation-override-valid failed for " + operationId + ": durationMs out of bounds");
        }
    }

    private void validateTableRuleTableOperation(String operationId, JsonNode planOperation, List<String> failures) {
        String tableOperationId = text(planOperation.path("input"), "tableOperationId");
        if (tableOperationId.isBlank() || tableOperationId.startsWith("page.") || tableOperationId.contains("localConfig")) {
            failures.add("validator table-manifest-operation-known failed for " + operationId + ": unsupported tableOperationId " + tableOperationId);
        }
    }

    private void validateTableRuleTableTarget(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode target = planOperation.path("input").path("tableTarget");
        if (!target.isMissingNode() && !target.isObject()) {
            failures.add("validator table-target-valid failed for " + operationId + ": tableTarget must be an object");
        }
    }

    private Set<String> tableRuleEffectTypes() {
        return Set.of("estilo", "layout", "icone", "fundo", "animacao", "tooltip");
    }

    private Set<String> tableRuleAnimationPresets() {
        return Set.of(
                "info-soft",
                "success-confirm",
                "warning-attention",
                "critical-alert",
                "audit-review",
                "sync-pending",
                "sla-warning",
                "sla-breach",
                "risk-elevated",
                "risk-critical",
                "audit-warning",
                "sync-warning");
    }

    private JsonNode findTableRule(JsonNode config, String ruleId) {
        JsonNode rules = config.path("ruleEffects").path("rules");
        return findObjectByAnyKey(rules, List.of("ruleId", "id"), ruleId);
    }

    private JsonNode findObjectByAnyKey(JsonNode array, List<String> keys, String value) {
        if (!array.isArray()) {
            return null;
        }
        for (JsonNode item : array) {
            for (String key : keys) {
                if (value.equals(text(item, key))) {
                    return item;
                }
            }
        }
        return null;
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

    private void validateMetadataFieldShape(String operationId, JsonNode config, List<String> failures) {
        JsonNode fieldMetadata = config.path("fieldMetadata");
        if (fieldMetadata.isMissingNode() || fieldMetadata.isNull()) {
            failures.add("validator field-metadata-shape-canonical failed for " + operationId + ": fieldMetadata is required");
            return;
        }
        if (!(fieldMetadata.isObject() || fieldMetadata.isArray())) {
            failures.add("validator field-metadata-shape-canonical failed for " + operationId + ": fieldMetadata must be object or array");
        }
    }

    private void validateMetadataFieldPath(String operationId, JsonNode planOperation, List<String> failures) {
        String path = text(planOperation.path("input"), "path");
        if (path.isBlank() || !metadataFieldPaths().contains(path)) {
            failures.add("validator field-path-supported-by-editor failed for " + operationId + ": unsupported metadata path " + path);
        }
    }

    private void validateMetadataControlTypeExists(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String controlType = text(planOperation.path("input"), "controlType");
        if (controlType.isBlank()) {
            controlType = text(planOperation.path("target"), "controlType");
        }
        if (controlType.isBlank() || !hasControlType(config, controlType)) {
            failures.add("validator control-type-exists-in-discovery failed for " + operationId + ": controlType is not discoverable " + controlType);
        }
    }

    private void validateMetadataEditorCoverage(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String controlType = text(planOperation.path("input"), "controlType");
        if (controlType.isBlank()) {
            return;
        }
        JsonNode coverage = findObjectByKey(config.path("editorCoverage"), "controlType", controlType);
        if (coverage.isMissingNode() && !hasControlType(config, controlType)) {
            failures.add("validator editor-coverage-exists failed for " + operationId + ": editor coverage missing for " + controlType);
        }
    }

    private void validateMetadataOptionSourceShape(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String kind = text(input, "kind");
        if (kind.isBlank()) {
            return;
        }
        if (!Set.of("static", "remote", "resource", "lookup").contains(kind)) {
            failures.add("validator option-source-shape-canonical failed for " + operationId + ": unsupported option source kind " + kind);
        }
        if ("static".equals(kind) && input.has("options") && !input.path("options").isArray()) {
            failures.add("validator option-source-shape-canonical failed for " + operationId + ": static options must be an array");
        }
        if (Set.of("remote", "resource", "lookup").contains(kind)
                && text(input, "resource").isBlank()
                && text(input, "endpoint").isBlank()) {
            failures.add("validator option-source-shape-canonical failed for " + operationId + ": governed resource or endpoint is required");
        }
    }

    private void validateMetadataRemoteOptionSourceGoverned(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String resource = text(input, "resource");
        String endpoint = text(input, "endpoint");
        if ((!resource.isBlank() && isUnsafeCrudUrl(resource))
                || (!endpoint.isBlank() && isUnsafeCrudUrl(endpoint))
                || containsUnsafeAbsoluteUrl(input.path("resource"))
                || containsUnsafeAbsoluteUrl(input.path("endpoint"))) {
            failures.add("validator remote-option-source-governed failed for " + operationId
                    + ": option sources must use relative governed Praxis paths");
        }
    }

    private void validateMetadataCascadeBackendShape(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("dependencyFilterMap") && !input.path("dependencyFilterMap").isObject()) {
            failures.add("validator cascade-backend-shape-preserved failed for " + operationId + ": dependencyFilterMap must be an object");
        }
        if (input.has("debounceMs") && input.path("debounceMs").asInt(-1) < 0) {
            failures.add("validator cascade-backend-shape-preserved failed for " + operationId + ": debounceMs must be non-negative");
        }
    }

    private void validateMetadataCascadeFieldsExist(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of(text(input, "dependentField"), text(input, "sourceField"))) {
            if (!field.isBlank() && !metadataFieldExists(config, field)) {
                failures.add("validator cascade-fields-exist failed for " + operationId + ": field not found " + field);
            }
        }
    }

    private void validateMetadataCascadeCycleFree(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String dependentField = text(input, "dependentField");
        String sourceField = text(input, "sourceField");
        if (!dependentField.isBlank() && dependentField.equals(sourceField)) {
            failures.add("validator cascade-cycle-free failed for " + operationId + ": dependentField cannot equal sourceField");
        }
    }

    private void validateMetadataRendererEditorType(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String propertyName = text(input, "propertyName");
        String editorType = text(input, "editorType");
        if (propertyName.isBlank() || editorType.isBlank()) {
            failures.add("validator renderer-editor-type-registered failed for " + operationId + ": propertyName and editorType are required");
        }
        if (containsUnsafeInlineHandler(input) || containsUnsafeTemplateHtml(input) || containsUnsafeAbsoluteUrl(input)) {
            failures.add("validator visual-editor-coverage-required failed for " + operationId + ": renderer config contains unsafe values");
        }
    }

    private void validateMetadataValidationRule(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode rule = planOperation.path("input").path("rule");
        if (!rule.isObject() || rule.isEmpty()) {
            failures.add("validator validation-rule-canonical failed for " + operationId + ": rule must be a non-empty object");
        }
        if (containsUnsafeAbsoluteUrl(rule) || containsUnsafeInlineHandler(rule)) {
            failures.add("validator validation-rule-canonical failed for " + operationId + ": rule contains unsafe values");
        }
    }

    private void validateMetadataContextValidatorRegistered(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String contextValidatorId = text(planOperation.path("input"), "contextValidatorId");
        if (contextValidatorId.isBlank()) {
            return;
        }
        JsonNode contextValidators = config.path("contextValidators");
        if (contextValidators.isArray()
                && findObjectByKey(contextValidators, "id", contextValidatorId).isMissingNode()
                && findObjectByKey(contextValidators, "validatorId", contextValidatorId).isMissingNode()) {
            failures.add("validator context-validator-registered failed for " + operationId
                    + ": context validator not registered " + contextValidatorId);
        }
    }

    private void validateMetadataContextHintShape(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String hintPath = text(input, "hintPath");
        if (hintPath.isBlank() || !metadataHintPaths().contains(hintPath)) {
            failures.add("validator context-hint-shape-canonical failed for " + operationId + ": unsupported hint path " + hintPath);
        }
        if (input.path("value").isMissingNode()) {
            failures.add("validator context-hint-shape-canonical failed for " + operationId + ": value is required");
        }
        if (containsUnsafeInlineHandler(input.path("value")) || containsUnsafeAbsoluteUrl(input.path("value"))) {
            failures.add("validator context-hint-shape-canonical failed for " + operationId + ": hint contains unsafe values");
        }
    }

    private void validateMetadataContextHintI18n(String operationId, JsonNode planOperation, List<String> failures) {
        String localeKey = text(planOperation.path("input"), "localeKey");
        if (!localeKey.isBlank() && (localeKey.contains(" ") || localeKey.startsWith(".") || localeKey.endsWith("."))) {
            failures.add("validator context-hint-i18n-compatible failed for " + operationId + ": localeKey is not canonical");
        }
    }

    private void validateMetadataNormalization(String operationId, JsonNode planOperation, List<String> failures) {
        String mode = text(planOperation.path("input"), "mode");
        if (!mode.isBlank()
                && !Set.of("hydrate-seed", "coerce-types", "apply-defaults", "preserve-advanced-properties").contains(mode)) {
            failures.add("validator normalization-preserves-canonical-fields failed for " + operationId + ": unsupported mode " + mode);
        }
    }

    private void validateManualFieldIdUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String fieldName = text(planOperation.path("input"), "fieldName");
        if (!fieldName.isBlank() && manualFieldExists(config, fieldName)) {
            failures.add("validator manual-field-id-unique failed for " + operationId + ": duplicate fieldName " + fieldName);
        }
    }

    private void validateManualFieldExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String fieldName = firstNonBlank(text(planOperation.path("input"), "fieldName"), targetText(planOperation.path("target")));
        if (fieldName.isBlank() || !manualFieldExists(config, fieldName)) {
            failures.add("validator manual-field-exists failed for " + operationId + ": field not found " + fieldName);
        }
    }

    private void validateManualControlTypeDiscovered(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String controlType = text(planOperation.path("input"), "controlType");
        if (!controlType.isBlank() && !hasControlType(config, controlType)) {
            failures.add("validator control-type-discovered failed for " + operationId + ": controlType is not discoverable " + controlType);
        }
    }

    private void validateManualMetadataBridgeNoSchemaRedefinition(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("schema", "schemaPatch", "jsonSchema", "resourceSchema", "backendSchema")) {
            if (input.has(field)) {
                failures.add("validator metadata-bridge-does-not-redefine-schema failed for " + operationId
                        + ": manual-form must not redefine backend schema via " + field);
            }
        }
    }

    private void validateManualFieldRemovalConfirmed(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if ((input.path("clearPersistedValue").asBoolean(false) || input.path("removeFromLayout").asBoolean(false))
                && !planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator field-removal-confirmed failed for " + operationId + ": explicit confirmation is required");
        }
    }

    private void validateManualLayoutFieldReferences(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        Set<String> known = manualFieldNames(config);
        for (String field : manualLayoutInputFields(planOperation.path("input"))) {
            if (!field.isBlank() && !known.contains(field)) {
                failures.add("validator layout-field-references-valid failed for " + operationId + ": unknown field " + field);
            }
        }
    }

    private void validateManualFieldLabelValid(String operationId, JsonNode planOperation, List<String> failures) {
        String label = text(planOperation.path("input"), "label");
        if (label.isBlank()) {
            failures.add("validator field-label-valid failed for " + operationId + ": label is required");
        }
    }

    private void validateManualLayoutHostTemplate(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("hostTemplate") || input.has("template") || input.has("templateHtml")) {
            failures.add("validator manual-layout-does-not-replace-host-template failed for " + operationId
                    + ": host template replacement is not allowed");
        }
    }

    private void validateManualDelegatesFormConfig(String operationId, JsonNode planOperation, List<String> failures) {
        String delegate = firstNonBlank(text(planOperation.path("input"), "delegateAdvancedFormConfigTo"),
                text(planOperation.path("input"), "delegateFormSubmitTo"));
        if (!delegate.isBlank() && !"praxis-dynamic-form".equals(delegate)) {
            failures.add("validator delegates-form-config failed for " + operationId + ": must delegate to praxis-dynamic-form");
        }
    }

    private void validateManualToolbarFlags(String operationId, JsonNode planOperation, List<String> failures) {
        Set<String> supported = Set.of("required", "readOnly", "hidden", "disabled", "openMetadataEditor");
        for (JsonNode flag : planOperation.path("input").path("editableFlags")) {
            String value = flag.asText("");
            if (!supported.contains(value)) {
                failures.add("validator toolbar-flags-supported failed for " + operationId + ": unsupported flag " + value);
            }
        }
    }

    private void validateManualMetadataBridgeGated(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        boolean asksBridge = input.path("enabled").asBoolean(false)
                || "praxis-metadata-editor".equals(text(input, "delegateFieldMetadataTo"))
                || "openMetadataEditor".equals(text(input, "openMode"));
        boolean customizationEnabled = input.path("enableCustomization").asBoolean(config.path("enableCustomization").asBoolean(false));
        if (asksBridge && !customizationEnabled && input.has("enableCustomization")) {
            failures.add("validator metadata-bridge-gated-by-customization failed for " + operationId
                    + ": metadata bridge requires enableCustomization");
        }
    }

    private void validateManualAutosaveExplicit(String operationId, JsonNode planOperation, List<String> failures) {
        if (!planOperation.path("input").has("enabled")) {
            failures.add("validator autosave-explicit failed for " + operationId + ": enabled is required");
        }
    }

    private void validateManualAutosaveDebounce(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode debounce = planOperation.path("input").path("debounceMs");
        if (!debounce.isMissingNode() && (debounce.asLong(-1) < 100 || debounce.asLong() > 60000)) {
            failures.add("validator autosave-debounce-safe failed for " + operationId + ": debounceMs must be between 100 and 60000");
        }
    }

    private void validateManualAutosaveStorage(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        if (!planOperation.path("input").path("enabled").asBoolean(false)) {
            return;
        }
        boolean storageAvailable = config.path("storageAvailable").asBoolean(false)
                || config.path("asyncConfigStorageAvailable").asBoolean(false)
                || !text(planOperation.path("input"), "storageKey").isBlank();
        if (!storageAvailable) {
            failures.add("validator autosave-storage-available failed for " + operationId + ": storageKey or storage provider is required");
        }
    }

    private void validateManualPersistenceKey(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String storageKey = text(input, "storageKey");
        if (!storageKey.isBlank() && (storageKey.contains(" ") || storageKey.contains("..") || storageKey.startsWith("/"))) {
            failures.add("validator persistence-key-deterministic failed for " + operationId + ": storageKey is not deterministic");
        }
    }

    private void validateManualSubmitBehavior(String operationId, JsonNode planOperation, List<String> failures) {
        String action = text(planOperation.path("input"), "action");
        if (!action.isBlank() && !Set.of("submit", "saveDraft", "reset", "cancel", "custom").contains(action)) {
            failures.add("validator submit-behavior-supported failed for " + operationId + ": unsupported action " + action);
        }
    }

    private void validateManualDelegatesFieldMetadata(String operationId, JsonNode planOperation, List<String> failures) {
        String delegate = text(planOperation.path("input"), "delegateFieldMetadataTo");
        if (!delegate.isBlank() && !"praxis-metadata-editor".equals(delegate)) {
            failures.add("validator delegates-field-metadata failed for " + operationId + ": must delegate to praxis-metadata-editor");
        }
        String discovery = text(planOperation.path("input"), "delegateControlDiscoveryTo");
        if (!discovery.isBlank() && !"praxis-dynamic-fields".equals(discovery)) {
            failures.add("validator delegates-field-metadata failed for " + operationId + ": control discovery must delegate to praxis-dynamic-fields");
        }
    }

    private void validateEditorialSnapshotShape(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String solutionId = text(planOperation.path("input"), "solutionId");
        if (solutionId.isBlank()) {
            failures.add("validator snapshot-shape-canonical failed for " + operationId + ": solutionId is required");
        }
        if (!config.path("solution").isObject()) {
            failures.add("validator snapshot-shape-canonical failed for " + operationId + ": solution must be an object");
        }
        if (planOperation.path("input").has("runtimeContextPatch")
                && !planOperation.path("input").path("runtimeContextPatch").isObject()) {
            failures.add("validator snapshot-shape-canonical failed for " + operationId + ": runtimeContextPatch must be an object");
        }
    }

    private void validateEditorialJourneyExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String journeyId = firstNonBlank(text(planOperation.path("input"), "journeyId"), text(planOperation.path("target"), "journeyId"));
        if (!journeyId.isBlank() && editorialJourney(config, journeyId) == null) {
            failures.add("validator journey-exists failed for " + operationId + ": journey not found " + journeyId);
        }
    }

    private void validateEditorialStepExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String journeyId = firstNonBlank(text(planOperation.path("input"), "journeyId"), text(planOperation.path("target"), "journeyId"));
        String stepId = firstNonBlank(text(planOperation.path("input"), "stepId"), text(planOperation.path("target"), "stepId"));
        if (!stepId.isBlank() && editorialStep(config, journeyId, stepId) == null) {
            failures.add("validator step-exists failed for " + operationId + ": step not found " + stepId);
        }
    }

    private void validateEditorialFallbackExplicit(String operationId, JsonNode planOperation, List<String> failures) {
        String mode = firstNonBlank(text(planOperation.path("input"), "mode"), text(planOperation.path("input"), "fallbackModeWhenMissing"));
        if (!Set.of("normal", "warning", "degraded", "blocked").contains(mode)) {
            failures.add("validator fallback-explicit failed for " + operationId + ": unsupported mode " + mode);
        }
    }

    private void validateEditorialFallbackDiagnosticBacked(String operationId, JsonNode planOperation, List<String> failures) {
        String mode = text(planOperation.path("input"), "mode");
        if (Set.of("warning", "degraded", "blocked").contains(mode) && text(planOperation.path("input"), "diagnosticCode").isBlank()) {
            failures.add("validator fallback-diagnostic-backed failed for " + operationId + ": diagnosticCode is required for " + mode);
        }
    }

    private void validateEditorialFallbackScopeExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode scope = planOperation.path("input").path("scope");
        if (!scope.isObject()) {
            return;
        }
        String journeyId = text(scope, "journeyId");
        String stepId = text(scope, "stepId");
        String blockId = text(scope, "blockId");
        if (!journeyId.isBlank() && editorialJourney(config, journeyId) == null) {
            failures.add("validator fallback-scope-exists failed for " + operationId + ": journey not found " + journeyId);
        }
        if (!stepId.isBlank() && editorialStep(config, journeyId, stepId) == null) {
            failures.add("validator fallback-scope-exists failed for " + operationId + ": step not found " + stepId);
        }
        if (!blockId.isBlank() && editorialBlock(config, journeyId, stepId, blockId) == null) {
            failures.add("validator fallback-scope-exists failed for " + operationId + ": block not found " + blockId);
        }
    }

    private void validateEditorialPresentationSupported(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("layout") && input.path("layout").has("orientation")
                && !Set.of("horizontal", "vertical").contains(text(input.path("layout"), "orientation"))) {
            failures.add("validator presentation-supported-by-runtime failed for " + operationId + ": unsupported layout orientation");
        }
        if (input.has("stepper") && input.path("stepper").has("orientation")
                && !Set.of("horizontal", "vertical").contains(text(input.path("stepper"), "orientation"))) {
            failures.add("validator presentation-supported-by-runtime failed for " + operationId + ": unsupported stepper orientation");
        }
    }

    private void validateEditorialPresentationNoDomainMutation(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("journeys", "steps", "blocks", "runtimeContext", "fieldMetadata", "data")) {
            if (input.has(field)) {
                failures.add("validator presentation-does-not-mutate-domain-data failed for " + operationId
                        + ": presentation must not mutate " + field);
            }
        }
    }

    private void validateEditorialThemeTokens(String operationId, JsonNode planOperation, List<String> failures) {
        if (containsUnsafeAbsoluteUrl(planOperation.path("input").path("theme"))) {
            failures.add("validator theme-tokens-valid failed for " + operationId + ": theme contains unsafe remote URL");
        }
    }

    private void validateEditorialAdapterExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String adapterId = text(planOperation.path("input"), "adapterId");
        if (adapterId.isBlank() || editorialAdapter(config, adapterId) == null) {
            failures.add("validator adapter-exists failed for " + operationId + ": adapter not registered " + adapterId);
        }
    }

    private void validateEditorialAdapterSupportsDataBlock(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String adapterId = text(planOperation.path("input"), "adapterId");
        String dataBlockType = text(planOperation.path("input"), "dataBlockType");
        JsonNode adapter = editorialAdapter(config, adapterId);
        if (adapter == null || dataBlockType.isBlank()) {
            return;
        }
        JsonNode supported = firstPresent(adapter, "supportedDataBlockTypes", "supports");
        if (supported.isArray() && !arrayContainsText(supported, dataBlockType)) {
            failures.add("validator adapter-supports-data-block failed for " + operationId
                    + ": adapter " + adapterId + " does not support " + dataBlockType);
        }
    }

    private void validateEditorialAdapterComponent(String operationId, JsonNode planOperation, List<String> failures) {
        String componentRef = text(planOperation.path("input"), "componentRef");
        if (!componentRef.isBlank() && !(componentRef.startsWith("praxis-") || componentRef.startsWith("@praxisui/"))) {
            failures.add("validator adapter-component-valid failed for " + operationId + ": componentRef must be Praxis-owned");
        }
    }

    private void validateEditorialDataBlockIdUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String blockId = text(input.path("block"), "blockId");
        if (!blockId.isBlank() && editorialBlock(config, text(input, "journeyId"), text(input, "stepId"), blockId) != null) {
            failures.add("validator data-block-id-unique failed for " + operationId + ": duplicate blockId " + blockId);
        }
    }

    private void validateEditorialDataBlockExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String blockId = text(input, "blockId");
        if (blockId.isBlank() || editorialBlock(config, text(input, "journeyId"), text(input, "stepId"), blockId) == null) {
            failures.add("validator data-block-exists failed for " + operationId + ": block not found " + blockId);
        }
    }

    private void validateEditorialFieldBindingTargetExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode input = planOperation.path("input");
        String blockId = text(input, "blockId");
        String fieldName = text(input, "fieldName");
        JsonNode block = editorialBlock(config, "", "", blockId);
        if (block == null) {
            failures.add("validator field-binding-target-exists failed for " + operationId + ": block not found " + blockId);
            return;
        }
        if (!fieldName.isBlank() && !editorialBlockFieldExists(block, fieldName)) {
            failures.add("validator field-binding-target-exists failed for " + operationId + ": field not found " + fieldName);
        }
    }

    private void validateEditorialFieldBindingPath(String operationId, JsonNode planOperation, List<String> failures) {
        String contextPath = text(planOperation.path("input"), "contextPath");
        if (contextPath.isBlank() || contextPath.startsWith("$") || contextPath.contains("..") || contextPath.contains("://")) {
            failures.add("validator field-binding-path-valid failed for " + operationId + ": contextPath is invalid");
        }
    }

    private JsonNode editorialJourney(JsonNode config, String journeyId) {
        JsonNode journeys = config.path("solution").path("journeys");
        if (!journeys.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode journey : journeys) {
            if (matchesAnyKey(journey, List.of("journeyId", "id"), journeyId)) {
                return journey;
            }
        }
        return null;
    }

    private JsonNode editorialStep(JsonNode config, String journeyId, String stepId) {
        JsonNode journey = editorialJourney(config, journeyId);
        if (journey == null || journey.isMissingNode()) {
            return null;
        }
        for (JsonNode step : journey.path("steps")) {
            if (matchesAnyKey(step, List.of("stepId", "id"), stepId)) {
                return step;
            }
        }
        return null;
    }

    private JsonNode editorialBlock(JsonNode config, String journeyId, String stepId, String blockId) {
        JsonNode journeys = config.path("solution").path("journeys");
        if (!journeys.isArray()) {
            return null;
        }
        for (JsonNode journey : journeys) {
            if (!journeyId.isBlank() && !matchesAnyKey(journey, List.of("journeyId", "id"), journeyId)) {
                continue;
            }
            for (JsonNode step : journey.path("steps")) {
                if (!stepId.isBlank() && !matchesAnyKey(step, List.of("stepId", "id"), stepId)) {
                    continue;
                }
                for (JsonNode block : step.path("blocks")) {
                    if (matchesAnyKey(block, List.of("blockId", "id"), blockId)) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private JsonNode editorialAdapter(JsonNode config, String adapterId) {
        for (String path : List.of("adapterRegistry", "dataBlockAdapters", "hostConfig.dataBlockAdapters")) {
            JsonNode adapters = resolvePath(config, path);
            if (!adapters.isArray()) {
                continue;
            }
            for (JsonNode adapter : adapters) {
                if (matchesAnyKey(adapter, List.of("adapterId", "id"), adapterId)) {
                    return adapter;
                }
            }
        }
        return null;
    }

    private boolean editorialBlockFieldExists(JsonNode block, String fieldName) {
        if (block.path("fieldBindings").has(fieldName)) {
            return true;
        }
        for (JsonNode field : block.path("fields")) {
            if (fieldName.equals(field.asText("")) || matchesAnyKey(field, List.of("name", "fieldName", "field"), fieldName)) {
                return true;
            }
        }
        return false;
    }

    private void validatePageBuilderPageShape(String operationId, JsonNode planOperation, JsonNode config, String validatorId, List<String> failures) {
        JsonNode page = firstPresent(planOperation.path("input"), "page");
        if (page.isMissingNode()) {
            page = planOperation.path("input").path("compiledFormPatch").path("patch").path("page");
        }
        if (page.isMissingNode()) {
            page = config;
        }
        if (!page.isObject()) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": page must be an object");
        }
    }

    private void validatePageBuilderSerializableInput(String operationId, JsonNode planOperation, String validatorId, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (("page-context-json-valid".equals(validatorId) && input.has("context") && !input.path("context").isObject())
                || ("state-json-valid".equals(validatorId) && input.path("value").isMissingNode())) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": value must be JSON compatible");
        }
    }

    private void validatePageBuilderPositiveInt(String operationId, JsonNode input, String field, String validatorId, List<String> failures) {
        if (input.has(field) && (!input.path(field).canConvertToInt() || input.path(field).asInt() <= 0)) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": " + field + " must be a positive integer");
        }
    }

    private void validatePageBuilderPositiveNumber(String operationId, JsonNode input, String field, String validatorId, List<String> failures) {
        if (input.has(field) && (!input.path(field).isNumber() || input.path(field).asDouble() <= 0)) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": " + field + " must be positive");
        }
    }

    private void validatePageBuilderNonNegativeNumber(String operationId, JsonNode input, String field, String validatorId, List<String> failures) {
        if (input.has(field) && (!input.path(field).isNumber() || input.path(field).asDouble() < 0)) {
            failures.add("validator " + validatorId + " failed for " + operationId + ": " + field + " must be non-negative");
        }
    }

    private void validatePageBuilderCanvasItemsReferenceWidgets(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode items = planOperation.path("input").path("items").isArray() ? planOperation.path("input").path("items") : config.path("canvas").path("items");
        for (JsonNode item : items) {
            String widgetKey = firstNonBlank(text(item, "widgetKey"), text(item, "key"), text(item, "id"));
            if (!widgetKey.isBlank() && pageBuilderWidget(config, widgetKey).isMissingNode()) {
                failures.add("validator canvas-items-reference-existing-widgets failed for " + operationId + ": widget not found " + widgetKey);
            }
        }
    }

    private void validatePageBuilderCanvasItem(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode item = planOperation.path("input").path("canvasItem");
        if (item.isMissingNode()) {
            return;
        }
        for (String field : List.of("x", "y", "w", "h")) {
            if (item.has(field) && (!item.path(field).canConvertToInt() || item.path(field).asInt() < 0)) {
                failures.add("validator canvas-item-valid failed for " + operationId + ": " + field + " must be a non-negative integer");
            }
        }
    }

    private void validatePageBuilderCanvasItemTarget(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String widgetKey = text(planOperation.path("input"), "widgetKey");
        if (!widgetKey.isBlank() && pageBuilderWidget(config, widgetKey).isMissingNode()) {
            failures.add("validator canvas-item-targets-existing-widget failed for " + operationId + ": widget not found " + widgetKey);
        }
    }

    private void validatePageBuilderWidgetKeyUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String widgetKey = text(planOperation.path("input"), "widgetKey");
        if (!widgetKey.isBlank() && !pageBuilderWidget(config, widgetKey).isMissingNode()) {
            failures.add("validator widget-key-unique failed for " + operationId + ": duplicate widgetKey " + widgetKey);
        }
    }

    private void validatePageBuilderWidgetKeysUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        Set<String> seen = new HashSet<>();
        JsonNode widgets = planOperation.path("input").path("uiCompositionPlan").path("page").path("widgets").isArray()
                ? planOperation.path("input").path("uiCompositionPlan").path("page").path("widgets")
                : config.path("widgets");
        for (JsonNode widget : widgets) {
            String widgetKey = firstNonBlank(text(widget, "widgetKey"), text(widget, "key"), text(widget, "id"));
            if (!widgetKey.isBlank() && !seen.add(widgetKey)) {
                failures.add("validator widget-keys-unique failed for " + operationId + ": duplicate widgetKey " + widgetKey);
            }
        }
    }

    private void validatePageBuilderWidgetKeyNotIndex(String operationId, JsonNode planOperation, List<String> failures) {
        String widgetKey = text(planOperation.path("input"), "widgetKey");
        if (widgetKey.matches("\\d+")) {
            failures.add("validator widget-key-not-array-index failed for " + operationId + ": widgetKey must not be an array index");
        }
    }

    private void validatePageBuilderWidgetExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String widgetKey = firstNonBlank(text(planOperation.path("input"), "widgetKey"), targetText(planOperation.path("target")));
        if (widgetKey.isBlank() || pageBuilderWidget(config, widgetKey).isMissingNode()) {
            failures.add("validator widget-exists failed for " + operationId + ": widget not found " + widgetKey);
        }
    }

    private void validatePageBuilderComponentRegistered(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String componentId = text(planOperation.path("input"), "componentId");
        if (componentId.isBlank()) {
            failures.add("validator component-registered failed for " + operationId + ": componentId is required");
            return;
        }
        JsonNode registry = firstPresent(config, "componentRegistry", "componentCatalog", "availableComponents");
        if (registry.isArray() && !arrayContainsObjectKey(registry, List.of("componentId", "id", "selector"), componentId)) {
            failures.add("validator component-registered failed for " + operationId + ": component not registered " + componentId);
        }
    }

    private void validatePageBuilderShellShape(String operationId, JsonNode planOperation, List<String> failures) {
        if (planOperation.path("input").has("shell") && !planOperation.path("input").path("shell").isObject()) {
            failures.add("validator widget-shell-shape-valid failed for " + operationId + ": shell must be an object");
        }
    }

    private void validatePageBuilderCompositionLinkUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String linkId = text(planOperation.path("input"), "linkId");
        if (!linkId.isBlank() && !pageBuilderCompositionLink(config, linkId).isMissingNode()) {
            failures.add("validator composition-link-id-unique failed for " + operationId + ": duplicate linkId " + linkId);
        }
    }

    private void validatePageBuilderCompositionLinkExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String linkId = firstNonBlank(text(planOperation.path("input"), "linkId"), targetText(planOperation.path("target")));
        if (linkId.isBlank() || pageBuilderCompositionLink(config, linkId).isMissingNode()) {
            failures.add("validator composition-link-exists failed for " + operationId + ": link not found " + linkId);
        }
    }

    private void validatePageBuilderCompositionEndpoints(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        for (JsonNode endpoint : List.of(planOperation.path("input").path("from"), planOperation.path("input").path("to"))) {
            String widgetKey = text(endpoint, "widgetKey");
            if (!widgetKey.isBlank() && pageBuilderWidget(config, widgetKey).isMissingNode()) {
                failures.add("validator composition-endpoints-resolve failed for " + operationId + ": widget not found " + widgetKey);
            }
        }
    }

    private void validatePageBuilderNestedPathTerminal(String operationId, JsonNode planOperation, List<String> failures) {
        for (JsonNode endpoint : List.of(planOperation.path("input").path("from"), planOperation.path("input").path("to"))) {
            if (endpoint.has("nestedPath") && text(endpoint, "widgetKey").isBlank()) {
                failures.add("validator nested-path-terminal-widget-key-required failed for " + operationId + ": nestedPath endpoint requires widgetKey");
            }
        }
    }

    private void validatePageBuilderUiCompositionPlan(String operationId, JsonNode planOperation, List<String> failures) {
        if (!planOperation.path("input").path("uiCompositionPlan").isObject()) {
            failures.add("validator ui-composition-plan-valid failed for " + operationId + ": uiCompositionPlan must be an object");
        }
    }

    private void validatePageBuilderStatePath(String operationId, JsonNode planOperation, List<String> failures) {
        String path = text(planOperation.path("input"), "path");
        if (path.isBlank() || path.startsWith("$") || path.contains("..") || path.contains("://")) {
            failures.add("validator state-path-valid failed for " + operationId + ": path is invalid");
        }
    }

    private void validatePageBuilderStateLayer(String operationId, JsonNode planOperation, List<String> failures) {
        String layer = text(planOperation.path("input"), "layer");
        if (!layer.isBlank() && !Set.of("page", "session").contains(layer)) {
            failures.add("validator state-layer-valid failed for " + operationId + ": unsupported layer " + layer);
        }
    }

    private void validatePageBuilderPreviewPatch(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        JsonNode page = planOperation.path("input").path("compiledFormPatch").path("patch").path("page");
        if (!page.isObject()) {
            page = config.path("compiledFormPatch").path("patch").path("page");
        }
        if (!page.isObject()) {
            failures.add("validator compiled-page-patch-present failed for " + operationId + ": compiledFormPatch.patch.page is required");
        }
    }

    private void validatePageBuilderPageIdentity(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode identity = planOperation.path("input").path("pageIdentity");
        for (String field : List.of("componentType", "componentId", "scope")) {
            if (text(identity, field).isBlank()) {
                failures.add("validator page-identity-complete failed for " + operationId + ": " + field + " is required");
            }
        }
    }

    private void validatePageBuilderEtag(String operationId, JsonNode planOperation, List<String> failures) {
        if (planOperation.path("input").has("etag") && !planOperation.path("input").path("etag").isTextual()) {
            failures.add("validator etag-policy-valid failed for " + operationId + ": etag must be a string");
        }
    }

    private void validatePageBuilderNoLocalChildInputWrite(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (input.has("inputs") || input.has("childInputs") || input.has("formConfig") || input.has("tableConfig")) {
            failures.add("validator no-local-child-input-write failed for " + operationId + ": child config must be delegated");
        }
    }

    private JsonNode pageBuilderWidget(JsonNode config, String widgetKey) {
        for (JsonNode widget : config.path("widgets")) {
            if (matchesAnyKey(widget, List.of("widgetKey", "key", "id"), widgetKey)) {
                return widget;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode pageBuilderCompositionLink(JsonNode config, String linkId) {
        for (JsonNode link : config.path("composition").path("links")) {
            if (matchesAnyKey(link, List.of("linkId", "id"), linkId)) {
                return link;
            }
        }
        return MissingNode.getInstance();
    }

    private boolean arrayContainsObjectKey(JsonNode array, List<String> keys, String value) {
        for (JsonNode item : array) {
            if (matchesAnyKey(item, keys, value)) {
                return true;
            }
        }
        return false;
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

    private void validateChartDocumentShape(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        JsonNode document = chartDocumentCandidate(planOperation, config);
        if (!document.isObject()) {
            failures.add("validator chart-document-shape failed for " + operationId + ": chartDocument must be an object");
            return;
        }
        if (text(document, "kind").isBlank()) {
            failures.add("validator chart-document-shape failed for " + operationId + ": kind is required");
        }
        if (!document.path("source").isObject()) {
            failures.add("validator chart-document-shape failed for " + operationId + ": source must be an object");
        }
    }

    private void validateChartVersionSupported(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String version = firstNonBlank(
                text(planOperation.path("input"), "version"),
                text(config.path("chartDocument"), "version"));
        if (!version.isBlank() && !"0.1.0".equals(version)) {
            failures.add("validator chart-version-supported failed for " + operationId + ": unsupported version " + version);
        }
    }

    private void validateChartTypeSupported(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String kind = chartKind(planOperation, config);
        if (!kind.isBlank() && !chartKinds().contains(kind)) {
            failures.add("validator chart-type-supported failed for " + operationId + ": unsupported kind " + kind);
        }
    }

    private void validateChartTypeSeriesAxisCompatible(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String kind = chartKind(planOperation, config);
        if (Set.of("pie", "donut").contains(kind) && hasSecondaryAxis(chartMetricsCandidate(planOperation, config))) {
            failures.add("validator chart-type-series-axis-compatible failed for " + operationId
                    + ": pie and donut charts cannot use secondary axes");
        }
        String seriesKind = text(planOperation.path("input"), "seriesKind");
        if (!seriesKind.isBlank() && Set.of("pie", "donut", "scatter").contains(kind)) {
            failures.add("validator chart-type-series-axis-compatible failed for " + operationId
                    + ": seriesKind is not supported by " + kind);
        }
    }

    private void validateChartPieSingleMetric(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String kind = chartKind(planOperation, config);
        JsonNode metrics = chartMetricsCandidate(planOperation, config);
        if (Set.of("pie", "donut").contains(kind) && metrics.isArray() && metrics.size() > 1) {
            failures.add("validator pie-single-metric failed for " + operationId + ": pie and donut charts support one metric");
        }
    }

    private void validateChartComboMinimumSeries(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String kind = chartKind(planOperation, config);
        JsonNode metrics = chartMetricsCandidate(planOperation, config);
        if ("combo".equals(kind) && metrics.isArray() && metrics.size() < 2) {
            failures.add("validator combo-minimum-series failed for " + operationId + ": combo charts require at least two metrics");
        }
    }

    private void validateChartInputFieldsExist(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        Set<String> known = chartKnownFields(config);
        for (String field : chartInputFields(planOperation.path("input"))) {
            if (!field.isBlank() && !known.isEmpty() && !known.contains(field)) {
                failures.add("validator chart-fields-exist failed for " + operationId + ": unknown field " + field);
            }
        }
    }

    private void validateChartSeriesUnique(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String field = text(planOperation.path("input"), "field");
        if (!field.isBlank() && fieldExists(config.path("chartDocument").path("metrics"), field)) {
            failures.add("validator series-id-unique failed for " + operationId + ": duplicate series field " + field);
        }
    }

    private void validateChartAggregation(String operationId, JsonNode planOperation, List<String> failures) {
        String aggregation = text(planOperation.path("input"), "aggregation");
        if (!aggregation.isBlank() && !Set.of("sum", "avg", "min", "max", "count", "distinct-count").contains(aggregation)) {
            failures.add("validator series-field-aggregable failed for " + operationId
                    + ": unsupported aggregation " + aggregation);
        }
    }

    private void validateChartSeriesExists(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String field = targetText(planOperation.path("target"));
        if (!field.isBlank() && !fieldExists(config.path("chartDocument").path("metrics"), field)) {
            failures.add("validator series-exists failed for " + operationId + ": series not found " + field);
        }
    }

    private void validateChartSeriesRemovalConfirmed(String operationId, JsonNode planOperation, List<String> failures) {
        if (!planOperation.path("confirmed").asBoolean(false)) {
            failures.add("validator destructive-series-removal-confirmed failed for " + operationId
                    + ": removing a series requires confirmation");
        }
    }

    private void validateChartKeepsMetric(String operationId, JsonNode config, List<String> failures) {
        JsonNode metrics = config.path("chartDocument").path("metrics");
        if (metrics.isArray() && metrics.size() <= 1) {
            failures.add("validator chart-keeps-required-metric failed for " + operationId
                    + ": chart must keep at least one metric");
        }
    }

    private void validateChartSecondaryAxisComboOnly(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String axis = firstNonBlank(text(planOperation.path("input"), "axis"), text(planOperation.path("input"), "metricAxis"));
        if ("secondary".equals(axis) && !"combo".equals(chartKind(planOperation, config))) {
            failures.add("validator secondary-axis-combo-only failed for " + operationId
                    + ": secondary axis is supported only for combo charts");
        }
    }

    private void validateChartCartesianDimensionRequired(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String kind = chartKind(planOperation, config);
        JsonNode dimensions = firstPresent(planOperation.path("input"), "dimensions");
        if (dimensions.isMissingNode()) {
            dimensions = config.path("chartDocument").path("dimensions");
        }
        if (Set.of("bar", "combo", "horizontal-bar", "line", "area", "stacked-bar", "stacked-area", "scatter").contains(kind)
                && (!dimensions.isArray() || dimensions.isEmpty())
                && text(planOperation.path("input"), "dimensionField").isBlank()) {
            failures.add("validator cartesian-dimension-required failed for " + operationId
                    + ": cartesian charts require a dimension");
        }
    }

    private void validateChartRemoteResource(String operationId, JsonNode planOperation, List<String> failures) {
        String resource = text(planOperation.path("input"), "resource");
        if (!resource.isBlank() && (containsUnsafeAbsoluteUrl(planOperation.path("input").path("resource")) || !resource.startsWith("/"))) {
            failures.add("validator remote-resource-in-api-metadata failed for " + operationId
                    + ": resource must be a relative governed Praxis path");
        }
    }

    private void validateChartStatsOperation(String operationId, JsonNode planOperation, List<String> failures) {
        String sourceKind = text(planOperation.path("input"), "sourceKind");
        String operation = text(planOperation.path("input"), "operation");
        if ("praxis.stats".equals(sourceKind) && !Set.of("group-by", "timeseries", "distribution").contains(operation)) {
            failures.add("validator stats-operation-supported failed for " + operationId
                    + ": unsupported stats operation " + operation);
        }
    }

    private void validateChartStructuredInput(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.isObject()) {
            failures.add("validator structured-input failed for " + operationId + ": input must be an object");
        }
        if (containsUnsafeAbsoluteUrl(input)) {
            failures.add("validator query-context-safe-values failed for " + operationId
                    + ": chart authoring values must not contain absolute remote URLs");
        }
    }

    private void validateChartEventTarget(
            String operationId,
            JsonNode planOperation,
            JsonNode config,
            List<String> failures) {
        String target = text(planOperation.path("input"), "target");
        if (target.isBlank()) {
            return;
        }
        JsonNode availableTargets = config.path("availableTargets");
        if (availableTargets.isArray() && !fieldExists(availableTargets, target)) {
            failures.add("validator event-target-governed failed for " + operationId + ": unknown event target " + target);
        }
    }

    private void validateChartEventAction(String operationId, JsonNode planOperation, List<String> failures) {
        String action = text(planOperation.path("input"), "action");
        if (!Set.of("filter-widget", "open-detail", "navigate", "update-context", "emit").contains(action)) {
            failures.add("validator event-action-supported failed for " + operationId + ": unsupported event action " + action);
        }
    }

    private void validateChartFeatureToggle(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.path("enabled").isBoolean()) {
            failures.add("validator feature-toggle-valid failed for " + operationId + ": enabled must be boolean");
        }
    }

    private JsonNode chartDocumentCandidate(JsonNode planOperation, JsonNode config) {
        JsonNode input = planOperation.path("input");
        if (input.has("kind") && input.has("source")) {
            return input;
        }
        return config.path("chartDocument");
    }

    private String chartKind(JsonNode planOperation, JsonNode config) {
        return firstNonBlank(text(planOperation.path("input"), "kind"), text(config.path("chartDocument"), "kind"));
    }

    private Set<String> chartKinds() {
        return Set.of("bar", "combo", "horizontal-bar", "line", "pie", "donut", "area", "stacked-bar", "stacked-area", "scatter");
    }

    private JsonNode chartMetricsCandidate(JsonNode planOperation, JsonNode config) {
        JsonNode metrics = firstPresent(planOperation.path("input"), "metrics");
        return metrics.isMissingNode() ? config.path("chartDocument").path("metrics") : metrics;
    }

    private boolean hasSecondaryAxis(JsonNode metrics) {
        if (!metrics.isArray()) {
            return false;
        }
        for (JsonNode metric : metrics) {
            if ("secondary".equals(text(metric, "axis"))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> chartKnownFields(JsonNode config) {
        Set<String> fields = new HashSet<>();
        collectFieldNames(config.path("availableFields"), fields, "name", "field", "id");
        collectFieldNames(config.path("fieldMetadata"), fields, "name", "field", "id");
        collectFieldNames(config.path("columns"), fields, "field", "name", "id");
        collectFieldNames(config.path("chartDocument").path("dimensions"), fields, "field", "name", "id");
        collectFieldNames(config.path("chartDocument").path("metrics"), fields, "field", "name", "id");
        return fields;
    }

    private void collectFieldNames(JsonNode array, Set<String> fields, String... keys) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            for (String key : keys) {
                String value = text(item, key);
                if (!value.isBlank()) {
                    fields.add(value);
                }
            }
        }
    }

    private Set<String> chartInputFields(JsonNode input) {
        Set<String> fields = new HashSet<>();
        collectChartInputFields(input, fields);
        return fields;
    }

    private void collectChartInputFields(JsonNode node, Set<String> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectChartInputFields(child, fields));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String key : List.of("field", "dimensionField", "metricField")) {
            String value = text(node, key);
            if (!value.isBlank()) {
                fields.add(value);
            }
        }
        JsonNode mapping = node.path("mapping");
        if (mapping.isObject()) {
            Iterator<JsonNode> values = mapping.elements();
            while (values.hasNext()) {
                String value = values.next().asText("");
                if (!value.isBlank()) {
                    fields.add(value);
                }
            }
        }
        Iterator<JsonNode> values = node.elements();
        while (values.hasNext()) {
            collectChartInputFields(values.next(), fields);
        }
    }

    private boolean fieldExists(JsonNode array, String value) {
        return !findObjectByKey(array, "field", value).isMissingNode()
                || !findObjectByKey(array, "id", value).isMissingNode()
                || !findObjectByKey(array, "name", value).isMissingNode();
    }

    private String targetText(JsonNode target) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            return "";
        }
        if (target.isTextual()) {
            return target.asText("");
        }
        return firstNonBlank(text(target, "field"), text(target, "id"), text(target, "name"), text(target, "value"));
    }

    private void validateInputFieldsExist(String operationId, JsonNode input, JsonNode config, List<String> failures) {
        Set<String> existing = filterOperation(operationId) ? knownFilterFields(config) : knownTableFields(config);
        for (String field : inputFields(input)) {
            if (!field.isBlank() && !existing.contains(field)) {
                failures.add("validator fields-exist failed for " + operationId + ": unknown field " + field);
            }
        }
    }

    private boolean filterOperation(String operationId) {
        return operationId != null && operationId.startsWith("filter.");
    }

    private Set<String> knownTableFields(JsonNode config) {
        Set<String> existing = new HashSet<>();
        collectFieldNames(config.path("columns"), existing);
        collectFieldNames(config.path("fieldMetadata"), existing);
        return existing;
    }

    private Set<String> knownFilterFields(JsonNode config) {
        Set<String> existing = new HashSet<>();
        for (String path : List.of(
                "filterRequestFieldMetadata",
                "filterFieldMetadata",
                "filterSchemaFields",
                "schemaVerification.filterFields",
                "inputs.filterRequestFieldMetadata",
                "inputs.filterFieldMetadata",
                "inputs.filterSchemaFields",
                "inputs.schemaVerification.filterFields")) {
            collectFieldNames(resolvePath(config, path), existing);
        }
        for (String path : List.of(
                "filterRequestSchema",
                "filterSchema",
                "requestSchema",
                "schemas.filterRequest",
                "schemas.filter",
                "schemaVerification.filterRequestSchema",
                "inputs.filterRequestSchema",
                "inputs.filterSchema",
                "inputs.requestSchema",
                "inputs.schemas.filterRequest",
                "inputs.schemas.filter",
                "inputs.schemaVerification.filterRequestSchema")) {
            collectJsonSchemaPropertyNames(resolvePath(config, path), existing);
        }
        if (existing.isEmpty()) {
            existing.addAll(knownTableFields(config));
        }
        return existing;
    }

    private void collectFieldNames(JsonNode fieldsNode, Set<String> fields) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return;
        }
        for (JsonNode field : fieldsNode) {
            if (field.isTextual()) {
                addIfNotBlank(fields, field.asText(""));
            } else if (field.isObject()) {
                addIfNotBlank(fields, firstNonBlank(text(field, "name"), text(field, "field"), text(field, "id")));
            }
        }
    }

    private void collectJsonSchemaPropertyNames(JsonNode schema, Set<String> fields) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                addIfNotBlank(fields, names.next());
            }
        }
        for (String combinator : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = schema.path(combinator);
            if (branches.isArray()) {
                branches.forEach(branch -> collectJsonSchemaPropertyNames(branch, fields));
            }
        }
    }

    private void addIfNotBlank(Set<String> fields, String field) {
        if (field != null && !field.isBlank()) {
            fields.add(field);
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
        JsonNode settings = input.path("settings");
        for (String fieldArray : List.of("alwaysVisibleFields", "selectedFieldIds")) {
            JsonNode array = settings.path(fieldArray);
            if (array.isArray()) {
                array.forEach(fieldName -> fields.add(fieldName.asText("")));
            }
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

    private void validateVisualNodeIdUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String nodeId = text(planOperation.path("input"), "nodeId");
        if (!nodeId.isBlank() && visualNodeExists(config, nodeId)) {
            failures.add("validator node-id-unique failed for " + operationId + ": duplicate nodeId " + nodeId);
        }
    }

    private void validateVisualNodeTypeSupported(String operationId, JsonNode planOperation, List<String> failures) {
        String nodeType = text(planOperation.path("input"), "nodeType");
        if (!nodeType.isBlank() && !Set.of(
                "root",
                "group",
                "condition",
                "effect",
                "operator",
                "value",
                "variable",
                "action",
                "branch").contains(nodeType)) {
            failures.add("validator node-type-supported failed for " + operationId + ": unsupported nodeType " + nodeType);
        }
    }

    private void validateVisualParentNodeExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String parentId = text(planOperation.path("input"), "parentId");
        if (!parentId.isBlank() && !visualNodeExists(config, parentId)) {
            failures.add("validator parent-node-exists failed for " + operationId + ": parent node not found " + parentId);
        }
    }

    private void validateVisualNodeExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String nodeId = firstNonBlank(
                text(planOperation.path("input"), "nodeId"),
                targetText(planOperation.path("target")));
        if (!nodeId.isBlank() && !visualNodeExists(config, nodeId)) {
            failures.add("validator node-exists failed for " + operationId + ": node not found " + nodeId);
        }
    }

    private void validateVisualNodeConfigCompatible(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        for (String field : List.of("config", "configPatch", "metadata", "metadataPatch")) {
            JsonNode value = input.path(field);
            if (!value.isMissingNode() && !value.isObject()) {
                failures.add("validator node-config-compatible failed for " + operationId + ": " + field + " must be an object");
            }
        }
    }

    private void validateVisualEdgesReferenceExistingNodes(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String sourceNodeId = text(planOperation.path("input"), "sourceNodeId");
        String targetNodeId = text(planOperation.path("input"), "targetNodeId");
        if (!sourceNodeId.isBlank() && !visualNodeExists(config, sourceNodeId)) {
            failures.add("validator edges-reference-existing-nodes failed for " + operationId + ": source node not found " + sourceNodeId);
        }
        if (!targetNodeId.isBlank() && !visualNodeExists(config, targetNodeId)) {
            failures.add("validator edges-reference-existing-nodes failed for " + operationId + ": target node not found " + targetNodeId);
        }
        validateVisualGraphReferences(operationId, config, failures);
    }

    private void validateVisualEdgeExists(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String sourceNodeId = text(planOperation.path("input"), "sourceNodeId");
        String targetNodeId = text(planOperation.path("input"), "targetNodeId");
        JsonNode source = visualNode(config, sourceNodeId);
        if (source.isMissingNode() || !arrayContainsText(source.path("children"), targetNodeId)) {
            failures.add("validator edge-exists failed for " + operationId + ": edge not found "
                    + sourceNodeId + " -> " + targetNodeId);
        }
    }

    private void validateVisualGraphValid(String operationId, JsonNode config, List<String> failures) {
        JsonNode nodes = config.path("nodes");
        if (!nodes.isArray()) {
            failures.add("validator graph-valid failed for " + operationId + ": nodes array is required");
            return;
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            String nodeId = firstNonBlank(text(node, "id"), text(node, "nodeId"));
            if (nodeId.isBlank()) {
                failures.add("validator graph-valid failed for " + operationId + ": node id is required");
            } else if (!ids.add(nodeId)) {
                failures.add("validator graph-valid failed for " + operationId + ": duplicate node id " + nodeId);
            }
        }
        validateVisualGraphReferences(operationId, config, failures);
        for (String nodeId : ids) {
            if (visualNodeHasCycle(config, nodeId, new HashSet<>())) {
                failures.add("validator graph-acyclic-where-required failed for " + operationId + ": cycle detected at " + nodeId);
                return;
            }
        }
    }

    private void validateVisualGraphReferences(String operationId, JsonNode config, List<String> failures) {
        JsonNode nodes = config.path("nodes");
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String sourceNodeId = firstNonBlank(text(node, "id"), text(node, "nodeId"));
            for (JsonNode child : node.path("children")) {
                String childId = child.asText("");
                if (!visualNodeExists(config, childId)) {
                    failures.add("validator edges-reference-existing-nodes failed for " + operationId
                            + ": child node not found " + childId);
                }
                JsonNode childNode = visualNode(config, childId);
                if (!childNode.isMissingNode() && !text(childNode, "parentId").isBlank()
                        && !sourceNodeId.equals(text(childNode, "parentId"))) {
                    failures.add("validator graph-valid failed for " + operationId + ": parentId mismatch for " + childId);
                }
            }
        }
    }

    private void validateVisualVariableIdUnique(String operationId, JsonNode planOperation, JsonNode config, List<String> failures) {
        String name = text(planOperation.path("input"), "name");
        String scope = text(planOperation.path("input"), "scope");
        if (!name.isBlank() && !scope.isBlank()) {
            for (JsonNode variable : config.path("contextVariables")) {
                if (name.equals(text(variable, "name")) && scope.equals(text(variable, "scope"))) {
                    failures.add("validator variable-id-unique failed for " + operationId + ": duplicate variable " + scope + "." + name);
                    return;
                }
            }
        }
    }

    private void validateVisualVariableScope(String operationId, JsonNode planOperation, List<String> failures) {
        String scope = text(planOperation.path("input"), "scope");
        if (!scope.isBlank() && !Set.of("row", "table", "global", "context", "form").contains(scope)) {
            failures.add("validator variable-scope-exists failed for " + operationId + ": unsupported scope " + scope);
        }
    }

    private void validateVisualContextVariableReference(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (text(input, "name").isBlank() || text(input, "scope").isBlank()) {
            failures.add("validator context-variable-reference-valid failed for " + operationId
                    + ": name and scope are required");
        }
        if (containsUnsafeAbsoluteUrl(input.path("defaultValue"))) {
            failures.add("validator context-variable-reference-valid failed for " + operationId
                    + ": defaultValue must not contain remote absolute URLs");
        }
    }

    private void validateVisualEffectTargets(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode targets = planOperation.path("input").path("targets");
        if (!targets.isArray() || targets.isEmpty()) {
            failures.add("validator effect-targets-exist failed for " + operationId + ": targets must contain at least one target");
        }
    }

    private void validateVisualEffectPropertiesGoverned(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode properties = planOperation.path("input").path("properties");
        if (!properties.isObject()) {
            failures.add("validator effect-properties-governed failed for " + operationId + ": properties must be an object");
            return;
        }
        if (containsUnsafeInlineHandler(properties)
                || containsUnsafeTemplateHtml(properties)
                || containsUnsafeAbsoluteUrl(properties)) {
            failures.add("validator effect-properties-governed failed for " + operationId
                    + ": unsafe inline handlers or HTML are not allowed");
        }
    }

    private void validateVisualEffectValues(String operationId, JsonNode planOperation, List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (containsUnsafeAbsoluteUrl(input.path("properties"))
                || containsUnsafeAbsoluteUrl(input.path("propertiesWhenFalse"))) {
            failures.add("validator effect-values-valid failed for " + operationId
                    + ": remote absolute URLs are not allowed in effect values");
        }
    }

    private boolean visualNodeExists(JsonNode config, String nodeId) {
        return !visualNode(config, nodeId).isMissingNode();
    }

    private JsonNode visualNode(JsonNode config, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode node : config.path("nodes")) {
            if (nodeId.equals(text(node, "id")) || nodeId.equals(text(node, "nodeId"))) {
                return node;
            }
        }
        return MissingNode.getInstance();
    }

    private boolean visualNodeReachable(JsonNode config, String sourceNodeId, String targetNodeId, Set<String> visited) {
        if (sourceNodeId == null || sourceNodeId.isBlank() || !visited.add(sourceNodeId)) {
            return false;
        }
        if (sourceNodeId.equals(targetNodeId)) {
            return true;
        }
        JsonNode source = visualNode(config, sourceNodeId);
        if (source.isMissingNode()) {
            return false;
        }
        for (JsonNode child : source.path("children")) {
            if (visualNodeReachable(config, child.asText(""), targetNodeId, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean visualNodeHasCycle(JsonNode config, String sourceNodeId, Set<String> visited) {
        if (sourceNodeId == null || sourceNodeId.isBlank() || !visited.add(sourceNodeId)) {
            return false;
        }
        JsonNode source = visualNode(config, sourceNodeId);
        if (source.isMissingNode()) {
            return false;
        }
        for (JsonNode child : source.path("children")) {
            String childId = child.asText("");
            if (sourceNodeId.equals(childId) || visualNodeReachable(config, childId, sourceNodeId, new HashSet<>())) {
                return true;
            }
        }
        return false;
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

    private boolean metadataFieldExists(JsonNode config, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        JsonNode fieldMetadata = config.path("fieldMetadata");
        if (fieldMetadata.isObject()) {
            return fieldName.equals(text(fieldMetadata, "name"))
                    || metadataFieldPaths().contains(fieldName)
                    || fieldMetadata.has(fieldName);
        }
        if (fieldMetadata.isArray()) {
            for (JsonNode field : fieldMetadata) {
                if (fieldName.equals(text(field, "name")) || fieldName.equals(text(field, "field"))) {
                    return true;
                }
            }
        }
        for (JsonNode field : config.path("availableFields")) {
            if (fieldName.equals(text(field, "name")) || fieldName.equals(text(field, "field"))) {
                return true;
            }
        }
        for (JsonNode field : config.path("fields")) {
            if (fieldName.equals(text(field, "name")) || fieldName.equals(text(field, "field"))) {
                return true;
            }
        }
        return false;
    }

    private boolean manualFieldExists(JsonNode config, String fieldName) {
        return manualFieldNames(config).contains(fieldName);
    }

    private Set<String> manualFieldNames(JsonNode config) {
        Set<String> names = new HashSet<>();
        for (String path : List.of("currentConfig.fieldMetadata", "currentFieldMetadata", "fieldMetadata", "form.fieldMetadata")) {
            JsonNode fields = resolvePath(config, path);
            if (fields.isArray()) {
                for (JsonNode field : fields) {
                    String name = firstNonBlank(text(field, "name"), text(field, "field"), text(field, "id"));
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private Set<String> manualLayoutInputFields(JsonNode input) {
        Set<String> fields = new HashSet<>();
        collectManualLayoutFields(input.path("sections"), fields);
        collectManualLayoutFields(input.path("rows"), fields);
        collectManualLayoutFields(input.path("columns"), fields);
        for (JsonNode field : input.path("fieldOrder")) {
            fields.add(field.asText(""));
        }
        return fields;
    }

    private void collectManualLayoutFields(JsonNode node, Set<String> fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectManualLayoutFields(child, fields);
            }
            return;
        }
        if (node.isObject()) {
            String field = firstNonBlank(text(node, "field"), text(node, "fieldName"), text(node, "name"));
            String type = firstNonBlank(text(node, "type"), text(node, "kind"));
            if (!field.isBlank() && ("field".equals(type) || node.has("field") || node.has("fieldName"))) {
                fields.add(field);
            }
            for (JsonNode value : node.path("fields")) {
                fields.add(value.asText(""));
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                collectManualLayoutFields(values.next(), fields);
            }
        }
    }

    private Set<String> metadataFieldPaths() {
        return Set.of(
                "name",
                "label",
                "description",
                "controlType",
                "placeholder",
                "defaultValue",
                "group",
                "order",
                "required",
                "disabled",
                "readOnly",
                "hidden",
                "source",
                "transient",
                "submitPolicy",
                "hint",
                "helpText",
                "tooltip",
                "options",
                "optionSource",
                "validators",
                "conditionalRequired",
                "conditionalDisplay",
                "visibleIn",
                "ariaLabel",
                "ariaDescribedBy");
    }

    private Set<String> metadataHintPaths() {
        return Set.of("hint", "helpText", "description", "tooltip", "ariaLabel", "ariaDescribedBy");
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

    private boolean matchesAnyKey(JsonNode item, List<String> keys, String targetValue) {
        if (targetValue == null || targetValue.isBlank()) {
            return false;
        }
        for (String key : keys) {
            if (targetValue.equals(text(item, key))) {
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
