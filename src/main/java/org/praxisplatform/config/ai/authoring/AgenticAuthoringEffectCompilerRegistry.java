package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.scheduling.support.CronExpression;

public final class AgenticAuthoringEffectCompilerRegistry {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTargetResolverRegistry targetResolverRegistry;

    public AgenticAuthoringEffectCompilerRegistry(
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.targetResolverRegistry = Objects.requireNonNull(targetResolverRegistry, "targetResolverRegistry must not be null");
    }

    void appendCompiledEffects(
            String componentId,
            JsonNode operation,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            ArrayNode patchOperations,
            List<String> failures,
            List<String> warnings) {
        for (JsonNode effect : operation.path("effects")) {
            String effectKind = text(effect, "kind");
            AgenticAuthoringResolvedTarget resolved = null;
            if (operation.path("target").path("required").asBoolean(false)
                    && !domainHandlerCreatesTarget(text(effect, "handler"))) {
                resolved = targetResolverRegistry.resolve(componentId, operation, planOperation.path("target"), proposedConfig);
                if (!AgenticAuthoringTargetResolverRegistry.STATUS_RESOLVED.equals(resolved.status())) {
                    failures.add("target resolution failed during compile for "
                            + text(operation, "operationId")
                            + ": "
                            + String.join(", ", resolved.failures()));
                    continue;
                }
            }
            if ("compile-domain-patch".equals(effectKind)) {
                ObjectNode compiled = compileDomainPatch(
                        componentId,
                        operation,
                        effect,
                        planOperation,
                        resolved,
                        proposedConfig,
                        failures);
                if (compiled != null) {
                    patchOperations.add(compiled);
                }
                continue;
            }
            ObjectNode compiled = compileAndApplyEffect(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            if (compiled != null) {
                patchOperations.add(compiled);
            }
        }
    }

    private boolean domainHandlerCreatesTarget(String handler) {
        return "table-rule-builder-rule-add".equals(handler)
                || "table-rule-builder-effect-add".equals(handler)
                || "table-rule-builder-animation-set".equals(handler)
                || "visual-builder-node-add".equals(handler)
                || "visual-builder-edge-connect".equals(handler)
                || "visual-builder-variable-add".equals(handler)
                || "manual-field-add".equals(handler)
                || "editorial-data-block-add".equals(handler);
    }

    boolean supportsDomainPatchHandler(String handler) {
        return "stepper-step-reorder".equals(handler)
                || "stepper-step-remove".equals(handler)
                || "stepper-validation-rule-upsert".equals(handler)
                || "tabs.reorder-tab-and-preserve-selection".equals(handler)
                || "tabs.remove-tab-and-reselect".equals(handler)
                || "tabs.set-active-item".equals(handler)
                || "tabs.set-tab-or-link-disabled".equals(handler)
                || "tabs.set-tab-or-link-visible".equals(handler)
                || "tabs.set-tab-or-link-content".equals(handler)
                || "rich-content-block-add".equals(handler)
                || "rich-content-media-block-update".equals(handler)
                || "rich-content-link-remove".equals(handler)
                || "rich-content-timeline-item-add".equals(handler)
                || "rich-content-timeline-item-update".equals(handler)
                || "rich-content-timeline-item-remove".equals(handler)
                || "rich-content-sanitization-policy".equals(handler)
                || "rich-content-preset-apply".equals(handler)
                || "expansion-panel-remove".equals(handler)
                || "expansion-multi-expand-set".equals(handler)
                || "expansion-default-expanded-upsert".equals(handler)
                || "files-upload-presign-base-url".equals(handler)
                || "files-upload-direct-base-url".equals(handler)
                || "list-template-slot-set".equals(handler)
                || "cron-expression-set".equals(handler)
                || "cron-frequency-to-expression".equals(handler)
                || "cron-timezone-set".equals(handler)
                || "cron-preset-apply".equals(handler)
                || "cron-validation-diagnostics".equals(handler)
                || "cron-preview-generate".equals(handler)
                || "dialog-preset-apply".equals(handler)
                || "dialog-child-host-configure".equals(handler)
                || "dialog-child-operation-delegate".equals(handler)
                || "dynamic-fields-control-registration".equals(handler)
                || "dynamic-fields-alias-registration".equals(handler)
                || "dynamic-fields-alias-removal".equals(handler)
                || "dynamic-fields-selector-mapping-set".equals(handler)
                || "dynamic-fields-editor-coverage-validation".equals(handler)
                || "dynamic-fields-runtime-coverage-validation".equals(handler)
                || "metadata-field-property-set".equals(handler)
                || "metadata-control-type-set".equals(handler)
                || "metadata-option-source-configure".equals(handler)
                || "metadata-cascade-configure".equals(handler)
                || "metadata-renderer-configure".equals(handler)
                || "metadata-validation-rule-add".equals(handler)
                || "metadata-context-hint-set".equals(handler)
                || "metadata-normalization-apply".equals(handler)
                || "crud-resource-bind".equals(handler)
                || "crud-list-surface-configure".equals(handler)
                || "crud-create-surface-configure".equals(handler)
                || "crud-edit-surface-configure".equals(handler)
                || "crud-view-surface-configure".equals(handler)
                || "crud-delete-behavior-set".equals(handler)
                || "crud-dialog-host-set".equals(handler)
                || "crud-permissions-set".equals(handler)
                || "crud-child-operation-delegate".equals(handler)
                || "form-layout-field-cleanup".equals(handler)
                || "form-layout-section-cleanup".equals(handler)
                || "form-layout-reconciler".equals(handler)
                || "form-layout-visual-block-add".equals(handler)
                || "form-layout-visual-block-update".equals(handler)
                || "form-layout-visual-block-move".equals(handler)
                || "form-layout-visual-block-remove".equals(handler)
                || "table-rule-builder-rule-add".equals(handler)
                || "table-rule-builder-rule-remove".equals(handler)
                || "table-rule-builder-condition-set".equals(handler)
                || "table-rule-builder-effect-add".equals(handler)
                || "table-rule-builder-effect-update".equals(handler)
                || "table-rule-builder-effect-remove".equals(handler)
                || "table-rule-builder-preset-apply".equals(handler)
                || "table-rule-builder-animation-set".equals(handler)
                || "table-rule-builder-table-delegate".equals(handler)
                || "visual-builder-node-add".equals(handler)
                || "visual-builder-node-remove".equals(handler)
                || "visual-builder-node-configure".equals(handler)
                || "visual-builder-edge-connect".equals(handler)
                || "visual-builder-edge-remove".equals(handler)
                || "visual-builder-variable-add".equals(handler)
                || "visual-builder-condition-set".equals(handler)
                || "visual-builder-effect-set".equals(handler)
                || "visual-builder-dsl-round-trip-validate".equals(handler)
                || "manual-field-add".equals(handler)
                || "manual-field-remove".equals(handler)
                || "manual-field-label-set".equals(handler)
                || "manual-layout-configure".equals(handler)
                || "manual-toolbar-configure".equals(handler)
                || "manual-autosave-enabled-set".equals(handler)
                || "manual-submit-behavior-set".equals(handler)
                || "manual-metadata-bridge-configure".equals(handler)
                || "editorial-snapshot-set".equals(handler)
                || "editorial-fallback-configure".equals(handler)
                || "editorial-presentation-configure".equals(handler)
                || "editorial-adapter-bind".equals(handler)
                || "editorial-data-block-add".equals(handler)
                || "editorial-data-block-remove".equals(handler)
                || "editorial-field-binding-set".equals(handler)
                || "chart-series-add".equals(handler)
                || "chart-axis-configure".equals(handler)
                || "chart-data-resource-bind".equals(handler)
                || "chart-event-cross-filter-configure".equals(handler)
                || "chart-event-drilldown-configure".equals(handler)
                || "settings-panel-size-set".equals(handler)
                || "settings-panel-apply-behavior-set".equals(handler)
                || "settings-panel-save-behavior-set".equals(handler)
                || "settings-panel-reset-behavior-set".equals(handler)
                || "settings-panel-editor-host-configure".equals(handler);
    }

    private ObjectNode compileAndApplyEffect(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String effectKind = text(effect, "kind");
        String path = text(effect, "path");
        JsonNode input = planOperation.path("input");
        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", effectKind);
        compiled.put("effectKind", effectKind);
        compiled.put("path", path);
        if (effect.has("key")) {
            compiled.set("key", effect.path("key"));
        }
        if (resolved != null) {
            compiled.put("resolvedPath", resolved.path());
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));

        switch (effectKind) {
            case "merge-by-key" -> applyMergeByKey(effect, input, resolved, proposedConfig, compiled, failures);
            case "remove-by-key" -> applyRemoveByKey(effect, resolved, proposedConfig, compiled, failures);
            case "set-value" -> applySetValue(effect, input, resolved, proposedConfig, compiled, failures);
            case "merge-object" -> applyMergeObject(effect, input, proposedConfig, compiled, failures);
            case "reorder-by-key" -> applyReorderByKey(effect, input, resolved, proposedConfig, compiled, failures);
            case "append", "append-to-array" -> applyAppend(effect, input, resolved, proposedConfig, compiled, failures);
            case "append-unique" -> applyAppendUnique(effect, input, resolved, proposedConfig, compiled, failures);
            default -> {
                failures.add("unsupported effect kind for compile: " + effectKind + " in " + text(operation, "operationId"));
                return null;
            }
        }
        return compiled;
    }

    private ObjectNode compileDomainPatch(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String handler = text(effect, "handler");
        return switch (handler) {
            case "stepper-step-reorder" -> compileStepperStepReorder(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "stepper-step-remove" -> compileStepperStepRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "stepper-validation-rule-upsert" -> compileStepperValidationRuleUpsert(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.reorder-tab-and-preserve-selection" -> compileTabsReorderAndPreserveSelection(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.remove-tab-and-reselect" -> compileTabsRemoveAndReselect(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.set-active-item" -> compileTabsSetActiveItem(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "tabs.set-tab-or-link-disabled" -> compileTabsSetTabOrLinkBoolean(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures,
                    "disabled");
            case "tabs.set-tab-or-link-visible" -> compileTabsSetTabOrLinkBoolean(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures,
                    "visible");
            case "tabs.set-tab-or-link-content" -> compileTabsSetTabOrLinkContent(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-block-add" -> compileRichContentBlockAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "rich-content-preset-apply" -> compileRichContentPresetApply(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "rich-content-media-block-update" -> compileRichContentMediaBlockUpdate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-link-remove" -> compileRichContentLinkRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-timeline-item-add" -> compileRichContentTimelineItemAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-timeline-item-update" -> compileRichContentTimelineItemUpdate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-timeline-item-remove" -> compileRichContentTimelineItemRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "rich-content-sanitization-policy" -> compileRichContentSanitizationPolicy(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "expansion-panel-remove" -> compileExpansionPanelRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "expansion-multi-expand-set" -> compileExpansionMultiExpandSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "expansion-default-expanded-upsert" -> compileExpansionDefaultExpandedUpsert(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "files-upload-presign-base-url" -> compileFilesUploadEndpointBaseUrl(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures,
                    "presign");
            case "files-upload-direct-base-url" -> compileFilesUploadEndpointBaseUrl(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures,
                    "direct");
            case "list-template-slot-set" -> compileListTemplateSlotSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "cron-expression-set" -> compileCronExpressionSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "cron-frequency-to-expression" -> compileCronFrequencyToExpression(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "cron-timezone-set" -> compileCronTimezoneSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "cron-preset-apply" -> compileCronPresetApply(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "cron-validation-diagnostics" -> compileCronValidationDiagnostics(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            case "cron-preview-generate" -> compileCronPreviewGenerate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dialog-preset-apply" -> compileDialogPresetApply(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dialog-child-host-configure" -> compileDialogChildHostConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dialog-child-operation-delegate" -> compileDialogChildOperationDelegate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dynamic-fields-control-registration" -> compileDynamicFieldsControlRegistration(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dynamic-fields-alias-registration" -> compileDynamicFieldsAliasRegistration(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dynamic-fields-alias-removal" -> compileDynamicFieldsAliasRemoval(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dynamic-fields-selector-mapping-set" -> compileDynamicFieldsSelectorMappingSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "dynamic-fields-editor-coverage-validation" -> compileDynamicFieldsCoverageValidation(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "editorCoverage",
                    "validate-dynamic-fields-editor-coverage");
            case "dynamic-fields-runtime-coverage-validation" -> compileDynamicFieldsCoverageValidation(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "runtimeCoverage",
                    "validate-dynamic-fields-runtime-coverage");
            case "metadata-field-property-set" -> compileMetadataFieldPropertySet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-control-type-set" -> compileMetadataControlTypeSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-option-source-configure" -> compileMetadataOptionSourceConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-cascade-configure" -> compileMetadataCascadeConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-renderer-configure" -> compileMetadataRendererConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-validation-rule-add" -> compileMetadataValidationRuleAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-context-hint-set" -> compileMetadataContextHintSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "metadata-normalization-apply" -> compileMetadataNormalizationApply(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "crud-resource-bind" -> compileCrudResourceBind(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "crud-list-surface-configure" -> compileCrudListSurfaceConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            case "crud-create-surface-configure" -> compileCrudActionSurfaceConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "create",
                    failures);
            case "crud-edit-surface-configure" -> compileCrudActionSurfaceConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "edit",
                    failures);
            case "crud-view-surface-configure" -> compileCrudActionSurfaceConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "view",
                    failures);
            case "crud-delete-behavior-set" -> compileCrudDeleteBehaviorSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "crud-dialog-host-set" -> compileCrudDialogHostSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            case "crud-permissions-set" -> compileCrudPermissionsSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            case "crud-child-operation-delegate" -> compileCrudChildOperationDelegate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "form-layout-field-cleanup" -> compileFormLayoutFieldCleanup(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-section-cleanup" -> compileFormLayoutSectionCleanup(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-reconciler" -> compileFormLayoutReconciler(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-visual-block-add" -> compileFormLayoutVisualBlockAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-visual-block-update" -> compileFormLayoutVisualBlockUpdate(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-visual-block-move" -> compileFormLayoutVisualBlockMove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "form-layout-visual-block-remove" -> compileFormLayoutVisualBlockRemove(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    resolved,
                    proposedConfig,
                    failures);
            case "table-rule-builder-rule-add" -> compileTableRuleBuilderRuleAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-rule-remove" -> compileTableRuleBuilderRuleRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-condition-set" -> compileTableRuleBuilderConditionSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-effect-add" -> compileTableRuleBuilderEffectAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-effect-update" -> compileTableRuleBuilderEffectUpdate(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-effect-remove" -> compileTableRuleBuilderEffectRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-preset-apply" -> compileTableRuleBuilderPresetApply(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-animation-set" -> compileTableRuleBuilderAnimationSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "table-rule-builder-table-delegate" -> compileTableRuleBuilderTableDelegate(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-node-add" -> compileVisualBuilderNodeAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-node-remove" -> compileVisualBuilderNodeRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-node-configure" -> compileVisualBuilderNodeConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-edge-connect" -> compileVisualBuilderEdgeConnect(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-edge-remove" -> compileVisualBuilderEdgeRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-variable-add" -> compileVisualBuilderVariableAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-condition-set" -> compileVisualBuilderConditionSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-effect-set" -> compileVisualBuilderEffectSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "visual-builder-dsl-round-trip-validate" -> compileVisualBuilderDslRoundTripValidate(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "manual-field-add" -> compileManualFieldAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "manual-field-remove" -> compileManualFieldRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "manual-field-label-set" -> compileManualFieldLabelSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "manual-layout-configure" -> compileManualLayoutConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "manual-toolbar-configure" -> compileManualToolbarConfigure(
                    componentId, operation, effect, planOperation, proposedConfig);
            case "manual-autosave-enabled-set" -> compileManualAutosaveEnabledSet(
                    componentId, operation, effect, planOperation, proposedConfig);
            case "manual-submit-behavior-set" -> compileManualSubmitBehaviorSet(
                    componentId, operation, effect, planOperation, proposedConfig);
            case "manual-metadata-bridge-configure" -> compileManualMetadataBridgeConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-snapshot-set" -> compileEditorialSnapshotSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-fallback-configure" -> compileEditorialFallbackConfigure(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-presentation-configure" -> compileEditorialPresentationConfigure(
                    componentId, operation, effect, planOperation, proposedConfig);
            case "editorial-adapter-bind" -> compileEditorialAdapterBind(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-data-block-add" -> compileEditorialDataBlockAdd(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-data-block-remove" -> compileEditorialDataBlockRemove(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "editorial-field-binding-set" -> compileEditorialFieldBindingSet(
                    componentId, operation, effect, planOperation, proposedConfig, failures);
            case "chart-series-add" -> compileChartSeriesAdd(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "chart-axis-configure" -> compileChartAxisConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "chart-data-resource-bind" -> compileChartDataResourceBind(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    failures);
            case "chart-event-cross-filter-configure" -> compileChartEventConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "crossFilter",
                    "configure-chart-cross-filter-event",
                    failures);
            case "chart-event-drilldown-configure" -> compileChartEventConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "drillDown",
                    "configure-chart-drilldown-event",
                    failures);
            case "settings-panel-size-set" -> compileSettingsPanelSizeSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            case "settings-panel-apply-behavior-set" -> compileSettingsPanelBehaviorSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "applyBehavior",
                    "set-settings-panel-apply-behavior");
            case "settings-panel-save-behavior-set" -> compileSettingsPanelBehaviorSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "saveBehavior",
                    "set-settings-panel-save-behavior");
            case "settings-panel-reset-behavior-set" -> compileSettingsPanelBehaviorSet(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig,
                    "resetBehavior",
                    "set-settings-panel-reset-behavior");
            case "settings-panel-editor-host-configure" -> compileSettingsPanelEditorHostConfigure(
                    componentId,
                    operation,
                    effect,
                    planOperation,
                    proposedConfig);
            default -> {
                failures.add("domain compiler is required for operation: " + text(operation, "operationId"));
                yield null;
            }
        };
    }

    private ObjectNode compileStepperStepReorder(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode steps = arrayAt(proposedConfig, "steps", false);
        if (steps == null) {
            failures.add("stepper-step-reorder path is not an array: steps[]");
            return null;
        }
        String stepId = resolved != null ? text(resolved.value(), "id") : "";
        int fromIndex = indexOfObjectByKey(steps, "id", stepId);
        if (fromIndex < 0) {
            failures.add("stepper-step-reorder target not found: steps[] id=" + stepId);
            return null;
        }
        String beforeStepId = text(planOperation.path("input"), "beforeStepId");
        if (beforeStepId.isBlank()) {
            failures.add("stepper-step-reorder requires beforeStepId");
            return null;
        }
        int beforeIndex = indexOfObjectByKey(steps, "id", beforeStepId);
        if (beforeIndex < 0) {
            failures.add("stepper-step-reorder before step not found: steps[] id=" + beforeStepId);
            return null;
        }

        int selectedIndexBefore = proposedConfig.path("selectedIndex").asInt(-1);
        String selectedStepId = selectedIndexBefore >= 0 && selectedIndexBefore < steps.size()
                ? text(steps.get(selectedIndexBefore), "id")
                : "";

        JsonNode moved = steps.remove(fromIndex);
        int targetIndex = beforeIndex;
        if (fromIndex < beforeIndex) {
            targetIndex = beforeIndex - 1;
        }
        steps.insert(targetIndex, moved);

        int selectedIndexAfter = selectedIndexBefore;
        if (!selectedStepId.isBlank()) {
            selectedIndexAfter = indexOfObjectByKey(steps, "id", selectedStepId);
            if (selectedIndexAfter >= 0) {
                proposedConfig.put("selectedIndex", selectedIndexAfter);
            }
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "reorder-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "steps[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", stepId);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", targetIndex);
        compiled.put("beforeStepId", beforeStepId);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileStepperStepRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode steps = arrayAt(proposedConfig, "steps", false);
        if (steps == null) {
            failures.add("stepper-step-remove path is not an array: steps[]");
            return null;
        }
        String stepId = resolved != null ? text(resolved.value(), "id") : "";
        int removedIndex = indexOfObjectByKey(steps, "id", stepId);
        if (removedIndex < 0) {
            failures.add("stepper-step-remove target not found: steps[] id=" + stepId);
            return null;
        }
        if (steps.size() == 1) {
            failures.add("stepper-step-remove cannot remove the only step");
            return null;
        }

        int selectedIndexBefore = proposedConfig.path("selectedIndex").asInt(-1);
        String selectedStepId = selectedIndexBefore >= 0 && selectedIndexBefore < steps.size()
                ? text(steps.get(selectedIndexBefore), "id")
                : "";
        String replacementStepId = text(planOperation.path("input"), "replacementStepId");
        int replacementIndexBeforeRemoval = -1;
        if (!replacementStepId.isBlank()) {
            replacementIndexBeforeRemoval = indexOfObjectByKey(steps, "id", replacementStepId);
            if (replacementIndexBeforeRemoval < 0 || replacementIndexBeforeRemoval == removedIndex) {
                failures.add("stepper-step-remove replacement step not found: steps[] id=" + replacementStepId);
                return null;
            }
        }
        JsonNode removedValue = steps.get(removedIndex).deepCopy();
        steps.remove(removedIndex);

        int selectedIndexAfter = selectedIndexBefore;
        if (!replacementStepId.isBlank()) {
            selectedIndexAfter = replacementIndexBeforeRemoval > removedIndex
                    ? replacementIndexBeforeRemoval - 1
                    : replacementIndexBeforeRemoval;
        } else if (stepId.equals(selectedStepId)) {
            selectedIndexAfter = Math.min(removedIndex, steps.size() - 1);
        } else if (selectedIndexBefore > removedIndex) {
            selectedIndexAfter = selectedIndexBefore - 1;
        }
        if (selectedIndexAfter >= 0) {
            proposedConfig.put("selectedIndex", selectedIndexAfter);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "remove-step-and-reselect");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "steps[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", stepId);
        compiled.put("removedIndex", removedIndex);
        compiled.set("removedValue", removedValue);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        if (!replacementStepId.isBlank()) {
            compiled.put("replacementStepId", replacementStepId);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileStepperValidationRuleUpsert(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode step)) {
            failures.add("stepper-validation-rule-upsert target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        JsonNode input = planOperation.path("input");
        String stepId = text(step, "id");
        String inputStepId = text(input, "stepId");
        if (!inputStepId.isBlank() && !inputStepId.equals(stepId)) {
            failures.add("stepper-validation-rule-upsert input stepId does not match target: " + inputStepId);
            return null;
        }
        if (input.path("remote").asBoolean(false)) {
            failures.add("stepper-validation-rule-upsert remote validation must be delegated to host serverValidate");
            return null;
        }
        JsonNode ruleInput = input.path("rule");
        if (!ruleInput.isObject()) {
            failures.add("stepper-validation-rule-upsert requires rule object");
            return null;
        }

        ObjectNode form = step.path("form") instanceof ObjectNode existingForm
                ? existingForm
                : step.putObject("form");
        ObjectNode formConfig = form.path("config") instanceof ObjectNode existingConfig
                ? existingConfig
                : form.putObject("config");
        ArrayNode formRules = formConfig.path("formRules") instanceof ArrayNode existingRules
                ? existingRules
                : formConfig.putArray("formRules");

        String fieldName = text(input, "fieldName");
        String ruleId = firstText(ruleInput, "id", "name", "key");
        if (ruleId.isBlank()) {
            ruleId = nextStepperValidationRuleId(formRules, stepId, fieldName);
        }
        ObjectNode rule = objectMapper.createObjectNode();
        if (ruleInput instanceof ObjectNode ruleObject) {
            rule.setAll(ruleObject);
        }
        rule.put("id", ruleId);
        if (!rule.hasNonNull("name")) {
            rule.put("name", ruleId);
        }
        rule.put("context", "validation");
        rule.put("targetType", fieldName.isBlank() ? "step" : "field");
        ArrayNode targets = objectMapper.createArrayNode();
        targets.add(fieldName.isBlank() ? stepId : fieldName);
        rule.set("targets", targets);
        if (!fieldName.isBlank()) {
            ArrayNode targetFields = objectMapper.createArrayNode();
            targetFields.add(fieldName);
            rule.set("targetFields", targetFields);
        }
        ObjectNode effectNode = rule.path("effect") instanceof ObjectNode existingEffect
                ? existingEffect
                : objectMapper.createObjectNode();
        if (!effectNode.has("condition")) {
            JsonNode condition = firstPresent(ruleInput, "condition", "logic", "expression");
            effectNode.set("condition", condition.isMissingNode() ? objectMapper.nullNode() : condition);
        }
        ObjectNode properties = effectNode.path("properties") instanceof ObjectNode existingProperties
                ? existingProperties
                : objectMapper.createObjectNode();
        String message = text(input, "message");
        if (!message.isBlank()) {
            properties.put("message", message);
            step.put("errorMessage", message);
        }
        properties.put("valid", false);
        effectNode.set("properties", properties);
        rule.set("effect", effectNode);

        int existingIndex = indexOfObjectByKey(formRules, "id", ruleId);
        if (existingIndex >= 0) {
            formRules.set(existingIndex, rule);
        } else {
            formRules.add(rule);
        }
        step.put("hasError", true);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "upsert-step-validation-rule");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", resolved == null ? "" : resolved.path() + ".form.config.formRules[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("stepId", stepId);
        compiled.put("ruleId", ruleId);
        if (!fieldName.isBlank()) {
            compiled.put("fieldName", fieldName);
        }
        compiled.put("upsertedIndex", existingIndex >= 0 ? existingIndex : formRules.size() - 1);
        compiled.set("value", rule);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentMediaBlockUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode mediaBlock)) {
            failures.add("rich-content-media-block-update target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        String nodeType = firstText(mediaBlock, "type", "kind");
        if (!"mediaBlock".equals(nodeType)) {
            failures.add("rich-content-media-block-update target is not mediaBlock: " + nodeType);
            return null;
        }

        JsonNode input = planOperation.path("input");
        ObjectNode value = objectMapper.createObjectNode();
        if (input.has("title")) {
            mediaBlock.set("title", input.path("title"));
            value.set("title", input.path("title"));
        }
        if (input.has("titleExpr")) {
            mediaBlock.set("titleExpr", input.path("titleExpr"));
            value.set("titleExpr", input.path("titleExpr"));
        }
        if (input.has("subtitle")) {
            mediaBlock.set("subtitle", input.path("subtitle"));
            value.set("subtitle", input.path("subtitle"));
        }
        if (input.has("subtitleExpr")) {
            mediaBlock.set("subtitleExpr", input.path("subtitleExpr"));
            value.set("subtitleExpr", input.path("subtitleExpr"));
        }
        ObjectNode avatarPatch = objectMapper.createObjectNode();
        if (input.has("avatarName")) {
            avatarPatch.set("name", input.path("avatarName"));
        }
        if (input.has("avatarImageSrc")) {
            avatarPatch.set("imageSrc", input.path("avatarImageSrc"));
        }
        if (!avatarPatch.isEmpty()) {
            ObjectNode avatar = mediaBlock.path("avatar") instanceof ObjectNode existingAvatar
                    ? existingAvatar
                    : mediaBlock.putObject("avatar");
            avatar.setAll(avatarPatch);
            value.set("avatar", avatarPatch);
        }
        if (value.isEmpty()) {
            failures.add("rich-content-media-block-update requires at least one supported field");
            return null;
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "merge-rich-media-block");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", resolved == null ? "" : resolved.path());
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", text(mediaBlock, "id"));
        compiled.set("value", value);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentBlockAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode nodes = arrayAt(proposedConfig, "document.nodes", true);
        if (nodes == null) {
            failures.add("rich-content-block-add path is not an array: document.nodes[]");
            return null;
        }
        JsonNode input = planOperation.path("input");
        String type = text(input, "type");
        if (type.isBlank()) {
            failures.add("rich-content-block-add requires type");
            return null;
        }

        ObjectNode node = objectMapper.createObjectNode();
        if (input.path("node") instanceof ObjectNode inputNode) {
            node.setAll(inputNode);
        }
        if (!node.hasNonNull("type") && !node.hasNonNull("kind")) {
            node.put("type", type);
        }
        if (!node.hasNonNull("id")) {
            node.put("id", nextRichContentNodeId(nodes, type));
        }
        String nodeId = text(node, "id");
        if (nodeId.isBlank()) {
            failures.add("rich-content-block-add requires node.id or generated id");
            return null;
        }
        if (indexOfObjectByKey(nodes, "id", nodeId) >= 0) {
            failures.add("rich-content-block-add duplicate node id: document.nodes[] id=" + nodeId);
            return null;
        }

        int insertedIndex = nodes.size();
        String beforeBlockId = text(input, "beforeBlockId");
        String afterBlockId = text(input, "afterBlockId");
        if (!beforeBlockId.isBlank()) {
            insertedIndex = indexOfObjectByKey(nodes, "id", beforeBlockId);
            if (insertedIndex < 0) {
                failures.add("rich-content-block-add before block not found: document.nodes[] id=" + beforeBlockId);
                return null;
            }
        } else if (!afterBlockId.isBlank()) {
            int afterIndex = indexOfObjectByKey(nodes, "id", afterBlockId);
            if (afterIndex < 0) {
                failures.add("rich-content-block-add after block not found: document.nodes[] id=" + afterBlockId);
                return null;
            }
            insertedIndex = afterIndex + 1;
        }
        nodes.insert(insertedIndex, node);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "insert-rich-block");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[]");
        compiled.put("keyValue", nodeId);
        compiled.put("insertedIndex", insertedIndex);
        if (!beforeBlockId.isBlank()) {
            compiled.put("beforeBlockId", beforeBlockId);
        }
        if (!afterBlockId.isBlank()) {
            compiled.put("afterBlockId", afterBlockId);
        }
        compiled.set("value", node);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentPresetApply(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode nodes = arrayAt(proposedConfig, "document.nodes", true);
        if (nodes == null) {
            failures.add("rich-content-preset-apply path is not an array: document.nodes[]");
            return null;
        }
        JsonNode input = planOperation.path("input");
        if (!(input.path("ref") instanceof ObjectNode ref)) {
            failures.add("rich-content-preset-apply requires ref object");
            return null;
        }
        if (!validatePresetRef(ref, failures)) {
            return null;
        }
        JsonNode inputs = input.path("inputs").isObject()
                ? input.path("inputs").deepCopy()
                : objectMapper.createObjectNode();
        if (!isSerializablePresetInputs(inputs)) {
            failures.add("rich-content-preset-apply inputs must be serializable JSON");
            return null;
        }

        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "preset");
        node.put("id", presetNodeId(ref, nodes));
        node.set("ref", ref.deepCopy());
        node.set("inputs", inputs);

        String replaceBlockId = text(input, "replaceBlockId");
        int index = nodes.size();
        JsonNode previousValue = MissingNode.getInstance();
        String op = "insert-rich-preset";
        if (!replaceBlockId.isBlank()) {
            index = indexOfObjectByKey(nodes, "id", replaceBlockId);
            if (index < 0) {
                failures.add("rich-content-preset-apply replace block not found: document.nodes[] id=" + replaceBlockId);
                return null;
            }
            previousValue = nodes.get(index).deepCopy();
            node.put("id", replaceBlockId);
            nodes.set(index, node);
            op = "replace-rich-block-with-preset";
        } else {
            String nodeId = text(node, "id");
            if (indexOfObjectByKey(nodes, "id", nodeId) >= 0) {
                node.put("id", nextRichContentNodeId(nodes, "preset"));
            }
            nodes.add(node);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", op);
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[]");
        compiled.put("keyValue", text(node, "id"));
        compiled.put("index", index);
        if (!previousValue.isMissingNode()) {
            compiled.set("previousValue", previousValue);
        }
        compiled.set("value", node);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentLinkRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode nodes = arrayAt(proposedConfig, "document.nodes", false);
        if (nodes == null) {
            failures.add("rich-content-link-remove path is not an array: document.nodes[]");
            return null;
        }
        int removedIndex = indexOfResolvedArrayTarget(resolved);
        if (removedIndex < 0 || removedIndex >= nodes.size()) {
            String linkId = resolved != null ? text(resolved.value(), "id") : "";
            removedIndex = indexOfObjectByKey(nodes, "id", linkId);
        }
        if (removedIndex < 0 || removedIndex >= nodes.size()) {
            failures.add("rich-content-link-remove target not found in document.nodes[]");
            return null;
        }
        JsonNode removedNode = nodes.get(removedIndex);
        String nodeType = firstText(removedNode, "type", "kind");
        if (!"link".equals(nodeType)) {
            failures.add("rich-content-link-remove target is not link: " + nodeType);
            return null;
        }
        String linkId = text(removedNode, "id");
        if (linkId.isBlank()) {
            failures.add("rich-content-link-remove requires link id");
            return null;
        }

        boolean preserveLabelAsText = planOperation.path("input").path("preserveLabelAsText").asBoolean(false);
        ObjectNode replacement = null;
        if (preserveLabelAsText) {
            String replacementId = linkId + "-text";
            if (indexOfObjectByKey(nodes, "id", replacementId) >= 0) {
                failures.add("rich-content-link-remove duplicate replacement node id: document.nodes[] id=" + replacementId);
                return null;
            }
            String label = firstText(removedNode, "label", "text", "title");
            if (label.isBlank()) {
                failures.add("rich-content-link-remove preserveLabelAsText requires link label");
                return null;
            }
            replacement = objectMapper.createObjectNode();
            replacement.put("id", replacementId);
            replacement.put("type", "text");
            replacement.put("text", label);
        }

        JsonNode removed = nodes.remove(removedIndex);
        if (replacement != null) {
            nodes.insert(removedIndex, replacement);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", preserveLabelAsText ? "replace-rich-link-with-text" : "remove-rich-link");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", linkId);
        compiled.put("removedIndex", removedIndex);
        compiled.set("removedValue", removed);
        compiled.put("preserveLabelAsText", preserveLabelAsText);
        if (replacement != null) {
            compiled.set("replacementValue", replacement);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentTimelineItemAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode timelineBlock)) {
            failures.add("rich-content-timeline-item-add target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        String nodeType = firstText(timelineBlock, "type", "kind");
        if (!"timeline".equals(nodeType) && !"timelineBlock".equals(nodeType)) {
            failures.add("rich-content-timeline-item-add target is not timeline: " + nodeType);
            return null;
        }
        String timelineBlockId = text(timelineBlock, "id");
        String inputTimelineBlockId = text(planOperation.path("input"), "timelineBlockId");
        if (timelineBlockId.isBlank()) {
            failures.add("rich-content-timeline-item-add requires timeline block id");
            return null;
        }
        if (!inputTimelineBlockId.isBlank() && !timelineBlockId.equals(inputTimelineBlockId)) {
            failures.add("rich-content-timeline-item-add target does not match input timelineBlockId: " + inputTimelineBlockId);
            return null;
        }
        if (!(planOperation.path("input").path("item") instanceof ObjectNode inputItem)) {
            failures.add("rich-content-timeline-item-add requires item object");
            return null;
        }

        ArrayNode items;
        if (timelineBlock.path("items").isMissingNode() || timelineBlock.path("items").isNull()) {
            items = timelineBlock.putArray("items");
        } else if (timelineBlock.path("items") instanceof ArrayNode existingItems) {
            items = existingItems;
        } else {
            failures.add("rich-content-timeline-item-add path is not an array: document.nodes[].items[]");
            return null;
        }

        ObjectNode item = inputItem.deepCopy();
        if (!item.hasNonNull("id")) {
            item.put("id", nextRichTimelineItemId(items));
        }
        String itemId = text(item, "id");
        if (itemId.isBlank()) {
            failures.add("rich-content-timeline-item-add requires item.id or generated id");
            return null;
        }
        if (indexOfObjectByKey(items, "id", itemId) >= 0) {
            failures.add("rich-content-timeline-item-add duplicate item id: document.nodes[].items[] id=" + itemId);
            return null;
        }

        int insertedIndex = items.size();
        String beforeItemId = text(planOperation.path("input"), "beforeItemId");
        String afterItemId = text(planOperation.path("input"), "afterItemId");
        if (!beforeItemId.isBlank()) {
            insertedIndex = indexOfObjectByKey(items, "id", beforeItemId);
            if (insertedIndex < 0) {
                failures.add("rich-content-timeline-item-add before item not found: document.nodes[].items[] id=" + beforeItemId);
                return null;
            }
        } else if (!afterItemId.isBlank()) {
            int afterIndex = indexOfObjectByKey(items, "id", afterItemId);
            if (afterIndex < 0) {
                failures.add("rich-content-timeline-item-add after item not found: document.nodes[].items[] id=" + afterItemId);
                return null;
            }
            insertedIndex = afterIndex + 1;
        }
        items.insert(insertedIndex, item);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "insert-rich-timeline-item");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[].items[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("timelineBlockId", timelineBlockId);
        compiled.put("keyValue", itemId);
        compiled.put("insertedIndex", insertedIndex);
        if (!beforeItemId.isBlank()) {
            compiled.put("beforeItemId", beforeItemId);
        }
        if (!afterItemId.isBlank()) {
            compiled.put("afterItemId", afterItemId);
        }
        compiled.set("value", item);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentTimelineItemUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode item)) {
            failures.add("rich-content-timeline-item-update target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        JsonNode input = planOperation.path("input");
        String timelineBlockId = text(input, "timelineBlockId");
        if (timelineBlockId.isBlank()) {
            failures.add("rich-content-timeline-item-update requires timelineBlockId");
            return null;
        }
        String itemIdBefore = text(item, "id");
        ObjectNode value = objectMapper.createObjectNode();
        if (input.path("patch") instanceof ObjectNode patch) {
            if (!applyTimelineItemPatch(item, patch, value, failures)) {
                return null;
            }
        }
        String field = text(input, "field");
        if (!field.isBlank()) {
            if (!timelineItemFields().contains(field)) {
                failures.add("rich-content-timeline-item-update unsupported field: " + field);
                return null;
            }
            if (!input.has("value")) {
                failures.add("rich-content-timeline-item-update field update requires value");
                return null;
            }
            item.set(field, input.path("value"));
            value.set(field, input.path("value"));
        }
        if (value.isEmpty()) {
            failures.add("rich-content-timeline-item-update requires patch or field");
            return null;
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "merge-rich-timeline-item");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[].items[]");
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("timelineBlockId", timelineBlockId);
        compiled.put("keyValue", itemIdBefore);
        compiled.set("value", value);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private boolean applyTimelineItemPatch(
            ObjectNode item,
            ObjectNode patch,
            ObjectNode value,
            List<String> failures) {
        if (patch.has("id") && !text(item, "id").equals(text(patch, "id"))) {
            failures.add("rich-content-timeline-item-update cannot change item id");
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            if ("id".equals(fieldName)) {
                continue;
            }
            if (!timelineItemFields().contains(fieldName)) {
                failures.add("rich-content-timeline-item-update unsupported patch field: " + fieldName);
                return false;
            }
            item.set(fieldName, field.getValue());
            value.set(fieldName, field.getValue());
        }
        return true;
    }

    private ObjectNode compileRichContentTimelineItemRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String timelineBlockId = text(input, "timelineBlockId");
        if (timelineBlockId.isBlank()) {
            failures.add("rich-content-timeline-item-remove requires timelineBlockId");
            return null;
        }
        if (resolved == null || resolved.path() == null || resolved.path().isBlank()) {
            failures.add("rich-content-timeline-item-remove target not found");
            return null;
        }
        int itemIndex = indexOfResolvedArrayTarget(resolved);
        if (itemIndex < 0) {
            failures.add("rich-content-timeline-item-remove target path is not an indexed timeline item: " + resolved.path());
            return null;
        }
        String blockPath = timelineBlockPathFromItemPath(resolved.path());
        JsonNode blockNode = nodeAtResolvedPath(proposedConfig, blockPath);
        if (!(blockNode instanceof ObjectNode timelineBlock)) {
            failures.add("rich-content-timeline-item-remove timeline block not found: " + blockPath);
            return null;
        }
        ArrayNode items = timelineBlock.path("items") instanceof ArrayNode array ? array : null;
        if (items == null) {
            failures.add("rich-content-timeline-item-remove path is not an array: " + blockPath + ".items[]");
            return null;
        }
        if (itemIndex >= items.size()) {
            failures.add("rich-content-timeline-item-remove item index out of bounds: " + itemIndex);
            return null;
        }
        JsonNode removedValue = items.get(itemIndex).deepCopy();
        String itemId = text(removedValue, "id");
        items.remove(itemIndex);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "remove-rich-timeline-item");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.nodes[].items[]");
        compiled.put("resolvedPath", resolved.path());
        compiled.set("resolvedValue", resolved.value());
        compiled.put("timelineBlockId", timelineBlockId);
        compiled.put("keyValue", itemId);
        compiled.put("removedIndex", itemIndex);
        compiled.set("removedValue", removedValue);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileRichContentSanitizationPolicy(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.isObject()) {
            failures.add("rich-content-sanitization-policy requires object input");
            return null;
        }
        ObjectNode policy = objectMapper.createObjectNode();
        copySanitizationPolicyField(input, policy, "allowHtml");
        copySanitizationPolicyField(input, policy, "allowedUrlProtocols");
        copySanitizationPolicyField(input, policy, "allowImageDataUrls");
        copySanitizationPolicyField(input, policy, "maxNodeDepth");
        copySanitizationPolicyField(input, policy, "maxNodeCount");
        if (policy.isEmpty()) {
            failures.add("rich-content-sanitization-policy requires at least one policy field");
            return null;
        }
        if (!validateSanitizationPolicy(policy, failures)) {
            return null;
        }
        ObjectNode document = objectAt(proposedConfig, "document", true);
        if (document == null) {
            failures.add("rich-content-sanitization-policy document is not an object");
            return null;
        }
        ObjectNode currentPolicy = document.path("sanitizationPolicy") instanceof ObjectNode existing
                ? existing
                : document.putObject("sanitizationPolicy");
        JsonNode previousPolicy = currentPolicy.deepCopy();
        currentPolicy.setAll(policy);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "set-rich-content-sanitization-policy");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "document.sanitizationPolicy");
        compiled.set("previousValue", previousPolicy);
        compiled.set("value", policy);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private void copySanitizationPolicyField(JsonNode input, ObjectNode policy, String field) {
        if (input.has(field)) {
            policy.set(field, input.path(field));
        }
    }

    private boolean validateSanitizationPolicy(ObjectNode policy, List<String> failures) {
        if (policy.has("allowHtml") && policy.path("allowHtml").asBoolean(true)) {
            failures.add("rich-content-sanitization-policy does not allow arbitrary HTML");
        }
        if (policy.path("allowedUrlProtocols").isArray()) {
            for (JsonNode protocol : policy.path("allowedUrlProtocols")) {
                String normalized = protocol.asText("").replace(":", "").toLowerCase();
                if (!safeRichContentUrlProtocols().contains(normalized)) {
                    failures.add("rich-content-sanitization-policy unsafe URL protocol: " + protocol.asText(""));
                }
            }
        }
        if (policy.has("maxNodeDepth")) {
            int maxNodeDepth = policy.path("maxNodeDepth").asInt(-1);
            if (maxNodeDepth < 1 || maxNodeDepth > 20) {
                failures.add("rich-content-sanitization-policy maxNodeDepth must be between 1 and 20");
            }
        }
        if (policy.has("maxNodeCount")) {
            int maxNodeCount = policy.path("maxNodeCount").asInt(-1);
            if (maxNodeCount < 1 || maxNodeCount > 1000) {
                failures.add("rich-content-sanitization-policy maxNodeCount must be between 1 and 1000");
            }
        }
        return failures.stream().noneMatch(failure -> failure.startsWith("rich-content-sanitization-policy"));
    }

    private ObjectNode compileTabsSetActiveItem(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.set-active-item path is not an array: tabs[]");
            return null;
        }
        int selectedIndex = indexOfResolvedArrayTarget(resolved);
        if (selectedIndex < 0 || selectedIndex >= tabs.size()) {
            String tabId = resolved != null ? text(resolved.value(), "id") : "";
            selectedIndex = indexOfObjectByKey(tabs, "id", tabId);
        }
        if (selectedIndex < 0 || selectedIndex >= tabs.size()) {
            failures.add("tabs.set-active-item target not found in tabs[]");
            return null;
        }
        JsonNode selectedTab = tabs.get(selectedIndex);
        String selectedTabId = text(selectedTab, "id");

        int groupSelectedIndexBefore = proposedConfig.path("group").path("selectedIndex").asInt(-1);
        int navSelectedIndexBefore = proposedConfig.path("nav").path("selectedIndex").asInt(-1);
        objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndex);
        if (proposedConfig.path("nav").isObject()) {
            objectAt(proposedConfig, "nav", true).put("selectedIndex", selectedIndex);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "set-active-index");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "group.selectedIndex");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("selectedIndex", selectedIndex);
        compiled.put("selectedTabId", selectedTabId);
        compiled.put("groupSelectedIndexBefore", groupSelectedIndexBefore);
        compiled.put("groupSelectedIndexAfter", selectedIndex);
        compiled.put("navSelectedIndexBefore", navSelectedIndexBefore);
        if (proposedConfig.path("nav").isObject()) {
            compiled.put("navSelectedIndexAfter", selectedIndex);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsSetTabOrLinkBoolean(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures,
            String fieldName) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode item)) {
            failures.add(text(effect, "handler") + " target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        JsonNode inputValue = planOperation.path("input").path(fieldName);
        if (!inputValue.isBoolean()) {
            failures.add(text(effect, "handler") + " requires boolean input field: " + fieldName);
            return null;
        }
        boolean before = item.has(fieldName) ? item.path(fieldName).asBoolean(!"visible".equals(fieldName)) : !"visible".equals(fieldName);
        boolean after = inputValue.asBoolean();
        item.put(fieldName, after);

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "set-tab-or-link-" + fieldName);
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", resolved == null ? "" : resolved.path() + "." + fieldName);
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", text(item, "id"));
        compiled.put("field", fieldName);
        compiled.put("before", before);
        compiled.put("after", after);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsSetTabOrLinkContent(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode item)) {
            failures.add("tabs.set-tab-or-link-content target not found: " + (resolved == null ? "" : resolved.path()));
            return null;
        }
        JsonNode input = planOperation.path("input");
        ObjectNode value = objectMapper.createObjectNode();
        for (String field : List.of("textLabel", "icon", "disabled", "visible", "content", "widgets")) {
            if (input.has(field)) {
                item.set(field, input.path(field));
                value.set(field, input.path(field));
            }
        }
        if (value.isEmpty()) {
            failures.add("tabs.set-tab-or-link-content requires at least one supported content field");
            return null;
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "merge-tab-or-link-content");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", resolved == null ? "" : resolved.path());
        compiled.put("resolvedPath", resolved == null ? "" : resolved.path());
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", text(item, "id"));
        compiled.set("value", value);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", input);
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsRemoveAndReselect(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.remove-tab-and-reselect path is not an array: tabs[]");
            return null;
        }
        String tabId = resolved != null ? text(resolved.value(), "id") : "";
        int removedIndex = indexOfObjectByKey(tabs, "id", tabId);
        if (removedIndex < 0) {
            failures.add("tabs.remove-tab-and-reselect target not found: tabs[] id=" + tabId);
            return null;
        }

        JsonNode group = proposedConfig.path("group");
        int selectedIndexBefore = group.path("selectedIndex").asInt(-1);
        String selectedTabId = selectedIndexBefore >= 0 && selectedIndexBefore < tabs.size()
                ? text(tabs.get(selectedIndexBefore), "id")
                : "";
        String replacementActiveTabId = text(planOperation.path("input"), "replacementActiveTabId");

        tabs.remove(removedIndex);

        int selectedIndexAfter = selectedIndexBefore;
        if (tabs.isEmpty()) {
            selectedIndexAfter = -1;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (!replacementActiveTabId.isBlank()) {
            int replacementIndex = indexOfObjectByKey(tabs, "id", replacementActiveTabId);
            if (replacementIndex < 0) {
                failures.add("tabs.remove-tab-and-reselect replacement tab not found: tabs[] id=" + replacementActiveTabId);
                return null;
            }
            selectedIndexAfter = replacementIndex;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (tabId.equals(selectedTabId)) {
            selectedIndexAfter = Math.min(removedIndex, tabs.size() - 1);
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        } else if (selectedIndexBefore > removedIndex) {
            selectedIndexAfter = selectedIndexBefore - 1;
            objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "remove-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "tabs[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", tabId);
        compiled.put("removedIndex", removedIndex);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        if (!replacementActiveTabId.isBlank()) {
            compiled.put("replacementActiveTabId", replacementActiveTabId);
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileTabsReorderAndPreserveSelection(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode tabs = arrayAt(proposedConfig, "tabs", false);
        if (tabs == null) {
            failures.add("tabs.reorder-tab-and-preserve-selection path is not an array: tabs[]");
            return null;
        }
        String tabId = resolved != null ? text(resolved.value(), "id") : "";
        int fromIndex = indexOfObjectByKey(tabs, "id", tabId);
        if (fromIndex < 0) {
            failures.add("tabs.reorder-tab-and-preserve-selection target not found: tabs[] id=" + tabId);
            return null;
        }
        String beforeTabId = text(planOperation.path("input"), "beforeTabId");
        if (beforeTabId.isBlank()) {
            failures.add("tabs.reorder-tab-and-preserve-selection requires beforeTabId");
            return null;
        }
        int beforeIndex = indexOfObjectByKey(tabs, "id", beforeTabId);
        if (beforeIndex < 0) {
            failures.add("tabs.reorder-tab-and-preserve-selection before tab not found: tabs[] id=" + beforeTabId);
            return null;
        }

        JsonNode group = proposedConfig.path("group");
        int selectedIndexBefore = group.path("selectedIndex").asInt(-1);
        String selectedTabId = selectedIndexBefore >= 0 && selectedIndexBefore < tabs.size()
                ? text(tabs.get(selectedIndexBefore), "id")
                : "";

        JsonNode moved = tabs.remove(fromIndex);
        int targetIndex = beforeIndex;
        if (fromIndex < beforeIndex) {
            targetIndex = beforeIndex - 1;
        }
        tabs.insert(targetIndex, moved);

        int selectedIndexAfter = selectedIndexBefore;
        if (!selectedTabId.isBlank()) {
            selectedIndexAfter = indexOfObjectByKey(tabs, "id", selectedTabId);
            if (selectedIndexAfter >= 0) {
                objectAt(proposedConfig, "group", true).put("selectedIndex", selectedIndexAfter);
            }
        }

        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("op", "reorder-by-key");
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        compiled.put("path", "tabs[]");
        compiled.put("resolvedPath", resolved != null ? resolved.path() : "");
        if (resolved != null) {
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.put("keyValue", tabId);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", targetIndex);
        compiled.put("beforeTabId", beforeTabId);
        compiled.put("selectedIndexBefore", selectedIndexBefore);
        compiled.put("selectedIndexAfter", selectedIndexAfter);
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileExpansionPanelRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode panels = arrayAt(proposedConfig, "panels", false);
        if (panels == null) {
            failures.add("expansion-panel-remove path is not an array: panels[]");
            return null;
        }
        String panelId = resolved != null ? text(resolved.value(), "id") : "";
        int removedIndex = indexOfObjectByKey(panels, "id", panelId);
        if (removedIndex < 0) {
            failures.add("expansion-panel-remove target not found: panels[] id=" + panelId);
            return null;
        }
        boolean removedWasExpanded = panels.get(removedIndex).path("expanded").asBoolean(false);
        JsonNode removedValue = panels.remove(removedIndex);
        String replacementPanelId = text(planOperation.path("input"), "replacementExpandedPanelId");
        int replacementIndex = -1;
        if (removedWasExpanded && !replacementPanelId.isBlank()) {
            replacementIndex = indexOfObjectByKey(panels, "id", replacementPanelId);
            if (replacementIndex < 0) {
                panels.insert(removedIndex, removedValue);
                failures.add("expansion-panel-remove replacement panel not found: panels[] id=" + replacementPanelId);
                return null;
            }
            collapseAllPanelsExcept(panels, replacementPanelId);
            ((ObjectNode) panels.get(replacementIndex)).put("expanded", true);
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "remove-expansion-panel");
        compiled.put("path", "panels[]");
        compiled.put("keyValue", panelId);
        compiled.put("removedIndex", removedIndex);
        compiled.put("removedWasExpanded", removedWasExpanded);
        compiled.put("replacementExpandedPanelId", replacementPanelId);
        compiled.put("replacementIndex", replacementIndex);
        compiled.set("removedValue", removedValue);
        return compiled;
    }

    private ObjectNode compileFilesUploadEndpointBaseUrl(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures,
            String defaultStrategy) {
        JsonNode input = planOperation.path("input");
        String baseUrl = normalizeFilesBaseUrl(text(input, "baseUrl"));
        if (baseUrl.isBlank()) {
            failures.add(text(effect, "handler") + " requires baseUrl");
            return null;
        }
        if (isUnsafeFilesBaseUrl(baseUrl)) {
            failures.add(text(effect, "handler") + " rejects unsafe baseUrl: " + baseUrl);
            return null;
        }
        String strategy = text(input, "strategy");
        if (strategy.isBlank()) {
            strategy = defaultStrategy;
        }
        if (!Set.of("direct", "presign", "auto").contains(strategy)) {
            failures.add(text(effect, "handler") + " strategy must be direct, presign or auto");
            return null;
        }

        String previousBaseUrl = text(proposedConfig, "baseUrl");
        String previousStrategy = text(proposedConfig, "strategy");
        proposedConfig.put("baseUrl", baseUrl);
        proposedConfig.put("strategy", strategy);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-files-upload-endpoint-base-url");
        compiled.put("path", "baseUrl");
        compiled.put("previousBaseUrl", previousBaseUrl);
        compiled.put("baseUrl", baseUrl);
        compiled.put("previousStrategy", previousStrategy);
        compiled.put("strategy", strategy);
        compiled.put("derivedUploadPath", baseUrl + "/upload");
        compiled.put("derivedBulkPath", baseUrl + "/bulk");
        if ("presign".equals(strategy) || "files-upload-presign-base-url".equals(text(effect, "handler"))) {
            compiled.put("derivedPresignPath", baseUrl + "/upload/presign");
        }
        return compiled;
    }

    private ObjectNode compileListTemplateSlotSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String slot = text(input, "slot");
        if (!listTemplateSlots().contains(slot)) {
            failures.add("list-template-slot-set unsupported slot: " + slot);
            return null;
        }
        String resolvedSlot = resolved != null ? text(resolved.value(), "slot") : "";
        if (!resolvedSlot.isBlank() && !slot.equals(resolvedSlot)) {
            failures.add("list-template-slot-set target slot does not match input slot");
            return null;
        }
        JsonNode template = input.path("template");
        if (template.isMissingNode() || template.isNull()) {
            failures.add("list-template-slot-set requires input.template");
            return null;
        }

        ObjectNode templating = objectAt(proposedConfig, "templating", true);
        JsonNode previousValue = templating.path(slot);
        templating.set(slot, template);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "set-list-template-slot");
        compiled.put("path", "templating." + slot);
        compiled.put("slot", slot);
        compiled.set("previousValue", previousValue.isMissingNode() ? MissingNode.getInstance() : previousValue);
        compiled.set("value", template);
        return compiled;
    }

    private ObjectNode compileCronExpressionSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String cron = text(input, "cron").trim();
        String dialect = textOrDefault(input, "dialect", "unix");
        boolean seconds = input.path("seconds").asBoolean(cronFieldCount(cron) == 6);
        String springCron = springCronExpression(cron, seconds);
        if (!isValidSpringCron(springCron)) {
            failures.add("cron-expression-set invalid expression: " + cron);
            return null;
        }
        writeCronExpression(proposedConfig, "customCron", cron, dialect, seconds);
        ObjectNode diagnostics = cronDiagnostics(cron, springCron, timezoneFromConfig(proposedConfig), dialect);
        proposedConfig.set("diagnostics", diagnostics);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-cron-expression");
        compiled.put("path", "schedule.expression");
        compiled.put("cron", cron);
        compiled.put("dialect", dialect);
        compiled.put("seconds", seconds);
        compiled.set("diagnostics", diagnostics);
        return compiled;
    }

    private ObjectNode compileCronFrequencyToExpression(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String kind = text(input, "kind");
        String dialect = textOrDefault(input, "dialect", textOrDefault(proposedConfig.path("schedule").path("expression"), "dialect", "unix"));
        String cron = cronFromFrequency(kind, input.path("recurrence"), failures);
        if (cron.isBlank()) {
            return null;
        }
        String springCron = springCronExpression(cron, false);
        if (!isValidSpringCron(springCron)) {
            failures.add("cron-frequency-to-expression compiled invalid expression: " + cron);
            return null;
        }
        ObjectNode schedule = objectAt(proposedConfig, "schedule", true);
        schedule.put("kind", kind);
        schedule.set("recurrence", input.path("recurrence"));
        writeCronExpression(proposedConfig, kind, cron, dialect, false);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-cron-frequency");
        compiled.put("path", "schedule");
        compiled.put("kind", kind);
        compiled.put("cron", cron);
        compiled.put("dialect", dialect);
        return compiled;
    }

    private ObjectNode compileCronTimezoneSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        String timezone = text(planOperation.path("input"), "timezone");
        ZoneId zone = zoneId(timezone, failures, "cron-timezone-set");
        if (zone == null) {
            return null;
        }
        objectAt(proposedConfig, "schedule", true).put("timezone", zone.getId());
        objectAt(proposedConfig, "metadata", true).put("timezone", zone.getId());
        ObjectNode diagnostics = cronDiagnosticsFromConfig(proposedConfig, zone);
        proposedConfig.set("diagnostics", diagnostics);
        proposedConfig.set("preview", cronPreviewFromConfig(proposedConfig, zone, 5, ZonedDateTime.now(zone)));

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-cron-timezone");
        compiled.put("path", "schedule.timezone");
        compiled.put("timezone", zone.getId());
        compiled.set("diagnostics", diagnostics);
        return compiled;
    }

    private ObjectNode compileCronPresetApply(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode preset = resolved == null ? MissingNode.getInstance() : resolved.value();
        String cron = firstText(preset, "cron", "expression", "value");
        if (cron.isBlank()) {
            failures.add("cron-preset-apply preset does not define cron expression");
            return null;
        }
        String timezone = textOrDefault(planOperation.path("input"), "timezone", timezoneFromConfig(proposedConfig));
        String springCron = springCronExpression(cron, cronFieldCount(cron) == 6);
        if (!isValidSpringCron(springCron)) {
            failures.add("cron-preset-apply preset cron is invalid: " + cron);
            return null;
        }
        writeCronExpression(proposedConfig, "customCron", cron, textOrDefault(preset, "dialect", "unix"), cronFieldCount(cron) == 6);
        objectAt(proposedConfig, "schedule", true).put("timezone", timezone);
        proposedConfig.put("value", cron);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "apply-cron-preset");
        compiled.put("path", "schedule.expression");
        compiled.put("cron", cron);
        compiled.put("timezone", timezone);
        return compiled;
    }

    private ObjectNode compileCronValidationDiagnostics(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        String cron = text(planOperation.path("input"), "cron");
        if (cron.isBlank()) {
            cron = currentCron(proposedConfig);
        }
        String timezone = textOrDefault(planOperation.path("input"), "timezone", timezoneFromConfig(proposedConfig));
        String springCron = springCronExpression(cron, cronFieldCount(cron) == 6);
        ObjectNode diagnostics = cronDiagnostics(cron, springCron, timezone, textOrDefault(planOperation.path("input"), "dialect", "unix"));
        proposedConfig.set("diagnostics", diagnostics);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "validate-cron-expression");
        compiled.put("path", "diagnostics");
        compiled.set("diagnostics", diagnostics);
        return compiled;
    }

    private ObjectNode compileCronPreviewGenerate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        String timezone = textOrDefault(planOperation.path("input"), "timezone", timezoneFromConfig(proposedConfig));
        ZoneId zone = zoneId(timezone, failures, "cron-preview-generate");
        if (zone == null) {
            return null;
        }
        int occurrences = planOperation.path("input").path("occurrences").canConvertToInt()
                ? planOperation.path("input").path("occurrences").asInt()
                : proposedConfig.path("metadata").path("previewOccurrences").asInt(5);
        occurrences = Math.max(1, Math.min(occurrences, 25));
        ZonedDateTime from = parsePreviewFrom(text(planOperation.path("input"), "from"), zone);
        ObjectNode diagnostics = cronDiagnosticsFromConfig(proposedConfig, zone);
        ArrayNode preview = cronPreviewFromConfig(proposedConfig, zone, occurrences, from);
        proposedConfig.set("diagnostics", diagnostics);
        proposedConfig.set("preview", preview);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "generate-cron-preview");
        compiled.put("path", "preview");
        compiled.set("preview", preview);
        compiled.set("diagnostics", diagnostics);
        return compiled;
    }

    private ObjectNode compileSettingsPanelSizeSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        ObjectNode config = objectAt(proposedConfig, "config", true);
        JsonNode previousConfig = config.deepCopy();
        copyIfPresent(input, config, "minWidth");
        copyIfPresent(input, config, "maxWidth");
        copyIfPresent(input, config, "resizable");
        copyIfPresent(input, config, "persistSizeKey");
        copyIfPresent(input, config, "expanded");
        if (input.has("width")) {
            objectAt(proposedConfig, "runtime", true).set("width", input.path("width"));
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-settings-panel-size");
        compiled.put("path", "config");
        compiled.set("previousConfig", previousConfig);
        compiled.set("config", config.deepCopy());
        compiled.set("runtime", proposedConfig.path("runtime"));
        return compiled;
    }

    private ObjectNode compileDialogPresetApply(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String dialogType = text(input, "dialogType");
        String variant = text(input, "variant");
        ObjectNode mergedConfig = objectMapper.createObjectNode();
        mergeObject(mergedConfig, dialogPresetConfig(proposedConfig, dialogType));
        if (!variant.isBlank()) {
            JsonNode variantConfig = dialogPresetVariants(proposedConfig).path(variant);
            if (!variantConfig.isObject()) {
                failures.add("dialog-preset-apply variant not found: " + variant);
                return null;
            }
            mergeObject(mergedConfig, variantConfig);
        }
        mergeObject(mergedConfig, input.path("localConfig"));
        ObjectNode config = objectAt(proposedConfig, "config", true);
        JsonNode previousConfig = config.deepCopy();
        mergeObject(config, mergedConfig);
        if (!variant.isBlank()) {
            proposedConfig.put("variant", variant);
        }
        proposedConfig.put("dialogType", dialogType);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "apply-dialog-preset");
        compiled.put("path", "config");
        compiled.put("dialogType", dialogType);
        compiled.put("variant", variant);
        compiled.set("previousConfig", previousConfig);
        compiled.set("config", config.deepCopy());
        return compiled;
    }

    private ObjectNode compileDialogChildHostConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String contentType = text(input, "contentType");
        ObjectNode content = objectAt(proposedConfig, "content", true);
        JsonNode previousContent = content.deepCopy();
        content.put("type", contentType);
        if ("component".equals(contentType)) {
            String childComponentId = text(input, "componentId");
            if (childComponentId.isBlank()) {
                failures.add("dialog-child-host-configure componentId is required for component content");
                return null;
            }
            content.put("componentId", childComponentId);
            proposedConfig.put("componentId", childComponentId);
        } else if ("template".equals(contentType)) {
            String templateId = text(input, "templateId");
            if (templateId.isBlank()) {
                failures.add("dialog-child-host-configure templateId is required for template content");
                return null;
            }
            content.put("templateId", templateId);
        } else {
            failures.add("dialog-child-host-configure unsupported contentType: " + contentType);
            return null;
        }
        if (input.has("inputs")) {
            proposedConfig.set("inputs", input.path("inputs").deepCopy());
            content.set("inputs", input.path("inputs").deepCopy());
        }
        if (input.has("data")) {
            proposedConfig.set("data", input.path("data").deepCopy());
            content.set("data", input.path("data").deepCopy());
        }
        String childManifestComponentId = text(input, "childManifestComponentId");
        if (!childManifestComponentId.isBlank()) {
            ObjectNode delegated = objectAt(proposedConfig, "delegatedChildManifest", true);
            delegated.put("componentId", childManifestComponentId);
            delegated.put("status", "child-config-owned-by-child-manifest");
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-dialog-child-host");
        compiled.put("path", "content");
        compiled.set("previousContent", previousContent);
        compiled.set("content", content.deepCopy());
        compiled.set("inputs", proposedConfig.path("inputs"));
        compiled.set("data", proposedConfig.path("data"));
        return compiled;
    }

    private ObjectNode compileDialogChildOperationDelegate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String childManifestComponentId = text(input, "childManifestComponentId");
        String childOperationId = text(input, "operationId");
        if (childManifestComponentId.isBlank() || childOperationId.isBlank()) {
            failures.add("dialog-child-operation-delegate requires childManifestComponentId and operationId");
            return null;
        }
        ObjectNode delegatedOperation = objectMapper.createObjectNode();
        delegatedOperation.put("componentId", childManifestComponentId);
        delegatedOperation.put("operationId", childOperationId);
        if (input.has("target")) {
            delegatedOperation.set("target", input.path("target"));
        }
        delegatedOperation.set("input", input.path("params").deepCopy());
        delegatedOperation.put("status", "delegated-to-child-manifest");

        ObjectNode inputs = objectAt(proposedConfig, "inputs", true);
        JsonNode previousInputs = inputs.deepCopy();
        inputs.set("__praxisAuthoringDelegatedPatch", delegatedOperation);
        ObjectNode data = objectAt(proposedConfig, "data", true);
        data.set("__praxisAuthoringDelegatedPatch", delegatedOperation.deepCopy());

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "delegate-dialog-child-operation");
        compiled.put("path", "inputs.__praxisAuthoringDelegatedPatch");
        compiled.set("previousInputs", previousInputs);
        compiled.set("delegatedOperation", delegatedOperation);
        return compiled;
    }

    private ObjectNode compileSettingsPanelBehaviorSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            String behaviorName,
            String opName) {
        ObjectNode runtimeContracts = objectAt(proposedConfig, "runtime.settingsPanel", true);
        JsonNode previousValue = runtimeContracts.path(behaviorName);
        runtimeContracts.set(behaviorName, planOperation.path("input").deepCopy());

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", opName);
        compiled.put("path", "runtime.settingsPanel." + behaviorName);
        compiled.set("previousValue", previousValue.isMissingNode() ? MissingNode.getInstance() : previousValue);
        compiled.set("value", runtimeContracts.path(behaviorName));
        return compiled;
    }

    private ObjectNode compileSettingsPanelEditorHostConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        ObjectNode content = objectAt(proposedConfig, "config.content", true);
        JsonNode previousContent = content.deepCopy();
        content.put("component", text(input, "componentId"));
        if (input.has("inputs")) {
            content.set("inputs", input.path("inputs").deepCopy());
        }
        if (input.has("editorContract")) {
            objectAt(proposedConfig, "runtime.settingsPanel.editorHost", true)
                    .set("editorContract", input.path("editorContract"));
        }
        String delegatedComponentId = text(input, "configManifestComponentId");
        if (!delegatedComponentId.isBlank()) {
            ObjectNode delegatedPatch = objectAt(proposedConfig, "delegatedConsumerPatch", true);
            delegatedPatch.put("componentId", delegatedComponentId);
            delegatedPatch.put("status", "delegated-to-component-manifest");
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-settings-panel-editor-host");
        compiled.put("path", "config.content");
        compiled.set("previousContent", previousContent);
        compiled.set("content", content.deepCopy());
        compiled.set("delegatedConsumerPatch", proposedConfig.path("delegatedConsumerPatch"));
        return compiled;
    }

    private ObjectNode compileDynamicFieldsControlRegistration(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String controlType = text(input, "controlType");
        String selector = text(input, "selector");
        if (controlType.isBlank() || selector.isBlank() || text(input, "componentExport").isBlank()) {
            failures.add("dynamic-fields-control-registration requires controlType, selector, and componentExport");
            return null;
        }
        ArrayNode registry = arrayAt(proposedConfig, "componentRegistry", true);
        ObjectNode entry = findObjectByKey(registry, "controlType", controlType);
        JsonNode previousEntry = entry == null ? MissingNode.getInstance() : entry.deepCopy();
        if (entry == null) {
            entry = objectMapper.createObjectNode();
            entry.put("controlType", controlType);
            registry.add(entry);
        }
        entry.put("selector", selector);
        entry.put("componentExport", text(input, "componentExport"));
        if (input.has("lazyImportPath")) {
            entry.set("lazyImportPath", input.path("lazyImportPath"));
        }
        entry.put("packageOwned", input.path("packageOwned").asBoolean(true));

        ObjectNode metadata = objectAt(proposedConfig, "componentMetadata", true);
        ArrayNode profiles = arrayAt(metadata, "controlProfiles", true);
        ObjectNode profile = findObjectByKey(profiles, "controlType", controlType);
        if (profile == null) {
            profile = objectMapper.createObjectNode();
            profile.put("controlType", controlType);
            profiles.add(profile);
        }
        profile.put("selector", selector);
        profile.put("componentExport", text(input, "componentExport"));

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "register-dynamic-field-control");
        compiled.put("path", "componentRegistry[]");
        compiled.set("previousEntry", previousEntry);
        compiled.set("entry", entry.deepCopy());
        compiled.set("metadataProfile", profile.deepCopy());
        return compiled;
    }

    private ObjectNode compileDynamicFieldsAliasRegistration(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String alias = text(input, "alias");
        String controlType = text(input, "controlType");
        if (alias.isBlank() || controlType.isBlank()) {
            failures.add("dynamic-fields-alias-registration requires alias and controlType");
            return null;
        }
        ArrayNode aliases = arrayAt(proposedConfig, "controlTypeAliases", true);
        String normalizedAlias = normalizeControlToken(alias);
        ObjectNode entry = findObjectByKey(aliases, "normalizedAlias", normalizedAlias);
        JsonNode previousEntry = entry == null ? MissingNode.getInstance() : entry.deepCopy();
        if (entry == null) {
            entry = objectMapper.createObjectNode();
            aliases.add(entry);
        }
        entry.put("alias", alias);
        entry.put("normalizedAlias", normalizedAlias);
        entry.put("controlType", controlType);
        if (input.has("reason")) {
            entry.set("reason", input.path("reason"));
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "register-dynamic-field-alias");
        compiled.put("path", "controlTypeAliases[]");
        compiled.set("previousEntry", previousEntry);
        compiled.set("entry", entry.deepCopy());
        return compiled;
    }

    private ObjectNode compileDynamicFieldsAliasRemoval(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String alias = text(input, "alias");
        if (alias.isBlank()) {
            failures.add("dynamic-fields-alias-removal requires alias");
            return null;
        }
        ArrayNode aliases = arrayAt(proposedConfig, "controlTypeAliases", false);
        if (aliases == null) {
            failures.add("dynamic-fields-alias-removal path is not an array: controlTypeAliases[]");
            return null;
        }
        String normalizedAlias = normalizeControlToken(alias);
        int removedIndex = indexOfObjectByKey(aliases, "normalizedAlias", normalizedAlias);
        if (removedIndex < 0) {
            removedIndex = indexOfObjectByKey(aliases, "alias", alias);
        }
        if (removedIndex < 0) {
            failures.add("dynamic-fields-alias-removal alias not found: " + alias);
            return null;
        }
        JsonNode removed = aliases.remove(removedIndex);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-dynamic-field-alias");
        compiled.put("path", "controlTypeAliases[]");
        compiled.put("removedIndex", removedIndex);
        compiled.set("removedEntry", removed);
        if (input.has("replacementControlType")) {
            compiled.set("replacementControlType", input.path("replacementControlType"));
        }
        return compiled;
    }

    private ObjectNode compileDynamicFieldsSelectorMappingSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String selector = text(input, "selector");
        String controlType = text(input, "controlType");
        if (selector.isBlank() || controlType.isBlank()) {
            failures.add("dynamic-fields-selector-mapping-set requires selector and controlType");
            return null;
        }
        ArrayNode mappings = arrayAt(proposedConfig, "selectorMappings", true);
        ObjectNode entry = findObjectByKey(mappings, "selector", selector);
        JsonNode previousEntry = entry == null ? MissingNode.getInstance() : entry.deepCopy();
        if (entry == null) {
            entry = objectMapper.createObjectNode();
            mappings.add(entry);
        }
        entry.put("selector", selector);
        entry.put("controlType", controlType);
        entry.put("overwrite", input.path("overwrite").asBoolean(false));
        if (input.has("source")) {
            entry.set("source", input.path("source"));
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-dynamic-field-selector-mapping");
        compiled.put("path", "selectorMappings[]");
        compiled.set("previousEntry", previousEntry);
        compiled.set("entry", entry.deepCopy());
        return compiled;
    }

    private ObjectNode compileDynamicFieldsCoverageValidation(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            String coveragePath,
            String opName) {
        JsonNode input = planOperation.path("input");
        String controlType = text(input, "controlType");
        ArrayNode coverage = arrayAt(proposedConfig, coveragePath, true);
        ObjectNode entry = findObjectByKey(coverage, "controlType", controlType);
        JsonNode previousEntry = entry == null ? MissingNode.getInstance() : entry.deepCopy();
        if (entry == null) {
            entry = objectMapper.createObjectNode();
            entry.put("controlType", controlType);
            coverage.add(entry);
        }
        mergeObject(entry, input);
        entry.put("validated", true);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", opName);
        compiled.put("path", coveragePath + "[]");
        compiled.set("previousEntry", previousEntry);
        compiled.set("entry", entry.deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataFieldPropertySet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String path = text(input, "path");
        if (path.isBlank() || input.path("value").isMissingNode()) {
            failures.add("metadata-field-property-set requires input.path and input.value");
            return null;
        }
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        JsonNode previousValue = fieldMetadata.path(path).deepCopy();
        if (input.path("merge").asBoolean(false) && fieldMetadata.path(path).isObject() && input.path("value").isObject()) {
            mergeObject((ObjectNode) fieldMetadata.path(path), input.path("value"));
        } else {
            fieldMetadata.set(path, input.path("value").deepCopy());
        }
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-metadata-field-property");
        compiled.put("path", "fieldMetadata." + path);
        compiled.put("propertyPath", path);
        compiled.set("previousValue", previousValue);
        compiled.set("value", fieldMetadata.path(path).deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataControlTypeSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String controlType = text(input, "controlType");
        if (controlType.isBlank()) {
            failures.add("metadata-control-type-set requires input.controlType");
            return null;
        }
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        JsonNode previousControlType = fieldMetadata.path("controlType").deepCopy();
        if (!input.path("preserveCompatibleProperties").asBoolean(true)) {
            fieldMetadata.remove(List.of("options", "optionSource", "validators", "conditionalRequired", "conditionalDisplay"));
        }
        fieldMetadata.put("controlType", controlType);
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-metadata-control-type");
        compiled.put("path", "fieldMetadata.controlType");
        compiled.set("previousValue", previousControlType);
        compiled.put("controlType", controlType);
        return compiled;
    }

    private ObjectNode compileMetadataOptionSourceConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        ObjectNode optionSource = fieldMetadata.path("optionSource") instanceof ObjectNode object
                ? object
                : fieldMetadata.putObject("optionSource");
        JsonNode previousValue = optionSource.deepCopy();
        mergeObject(optionSource, input);
        if (input.has("options")) {
            fieldMetadata.set("options", input.path("options").deepCopy());
        }
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-metadata-option-source");
        compiled.put("path", "fieldMetadata.optionSource");
        compiled.set("previousValue", previousValue);
        compiled.set("value", optionSource.deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataCascadeConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String dependentField = text(input, "dependentField");
        if (dependentField.isBlank()) {
            failures.add("metadata-cascade-configure requires input.dependentField");
            return null;
        }
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        JsonNode previousValue = fieldMetadata.deepCopy();
        ArrayNode dependencyFields = fieldMetadata.path("dependencyFields") instanceof ArrayNode array
                ? array
                : fieldMetadata.putArray("dependencyFields");
        addTextUnique(dependencyFields, dependentField);
        copyIfPresent(input, fieldMetadata, "sourceField");
        copyIfPresent(input, fieldMetadata, "dependencyFilterMap");
        fieldMetadata.put("enableDependencyCascade", true);
        fieldMetadata.put("resetOnDependentChange", true);
        if (input.has("strategy")) {
            fieldMetadata.set("dependencyMergeStrategy", input.path("strategy"));
        }
        if (input.has("debounceMs")) {
            fieldMetadata.set("dependencyDebounceMs", input.path("debounceMs"));
        }
        if (input.has("loadMode")) {
            fieldMetadata.set("dependencyLoadOnChange", input.path("loadMode"));
        }
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-metadata-cascade");
        compiled.put("path", "fieldMetadata.dependencyFields");
        compiled.set("previousValue", previousValue);
        compiled.set("value", fieldMetadata.deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataRendererConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String propertyName = text(input, "propertyName");
        String editorType = text(input, "editorType");
        if (propertyName.isBlank() || editorType.isBlank()) {
            failures.add("metadata-renderer-configure requires propertyName and editorType");
            return null;
        }
        ArrayNode properties = arrayAt(proposedConfig, "properties", true);
        ObjectNode property = findObjectByKey(properties, "name", propertyName);
        JsonNode previousValue = property == null ? MissingNode.getInstance() : property.deepCopy();
        if (property == null) {
            property = objectMapper.createObjectNode();
            property.put("name", propertyName);
            properties.add(property);
        }
        property.put("editorType", editorType);
        copyIfPresent(input, property, "group");
        copyIfPresent(input, property, "required");
        copyIfPresent(input, property, "options");
        ArrayNode editorCoverage = arrayAt(proposedConfig, "editorCoverage", true);
        ObjectNode coverage = findObjectByKey(editorCoverage, "propertyName", propertyName);
        if (coverage == null) {
            coverage = objectMapper.createObjectNode();
            coverage.put("propertyName", propertyName);
            editorCoverage.add(coverage);
        }
        coverage.put("editorType", editorType);
        coverage.put("covered", true);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-metadata-renderer");
        compiled.put("path", "properties[]");
        compiled.set("previousValue", previousValue);
        compiled.set("value", property.deepCopy());
        compiled.set("editorCoverage", coverage.deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataValidationRuleAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.path("rule").isObject()) {
            failures.add("metadata-validation-rule-add requires object input.rule");
            return null;
        }
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        ArrayNode validators = fieldMetadata.path("validators") instanceof ArrayNode array
                ? array
                : fieldMetadata.putArray("validators");
        ObjectNode rule = (ObjectNode) input.path("rule").deepCopy();
        copyIfPresent(input, rule, "message");
        copyIfPresent(input, rule, "contextValidatorId");
        validators.add(rule);
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-metadata-validation-rule");
        compiled.put("path", "fieldMetadata.validators[]");
        compiled.put("insertedIndex", validators.size() - 1);
        compiled.set("value", rule);
        return compiled;
    }

    private ObjectNode compileMetadataContextHintSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String hintPath = text(input, "hintPath");
        if (hintPath.isBlank() || input.path("value").isMissingNode()) {
            failures.add("metadata-context-hint-set requires hintPath and value");
            return null;
        }
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        JsonNode previousValue = fieldMetadata.path(hintPath).deepCopy();
        fieldMetadata.set(hintPath, input.path("value").deepCopy());
        if (input.has("localeKey")) {
            ObjectNode i18n = objectAt(proposedConfig, "i18nHints", true);
            i18n.set(hintPath, input.path("localeKey"));
        }
        metadataWriteNormalizedSeed(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-metadata-context-hint");
        compiled.put("path", "fieldMetadata." + hintPath);
        compiled.set("previousValue", previousValue);
        compiled.set("value", fieldMetadata.path(hintPath).deepCopy());
        return compiled;
    }

    private ObjectNode compileMetadataNormalizationApply(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        ObjectNode normalizedSeed = objectAt(proposedConfig, "normalizedSeed", true);
        JsonNode previousSeed = normalizedSeed.deepCopy();
        mergeObject(normalizedSeed, fieldMetadata);
        normalizedSeed.put("normalized", true);
        normalizedSeed.put("mode", textOrDefault(input, "mode", "preserve-advanced-properties"));
        if (input.has("preserveUnknownCanonicalFields")) {
            normalizedSeed.set("preserveUnknownCanonicalFields", input.path("preserveUnknownCanonicalFields"));
        }
        ObjectNode form = objectAt(proposedConfig, "form", true);
        form.set("fieldMetadata", fieldMetadata.deepCopy());
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "apply-metadata-normalization");
        compiled.put("path", "normalizedSeed");
        compiled.set("previousValue", previousSeed);
        compiled.set("value", normalizedSeed.deepCopy());
        return compiled;
    }

    private ObjectNode metadataFieldMetadata(ObjectNode proposedConfig, boolean create) {
        JsonNode fieldMetadata = proposedConfig.path("fieldMetadata");
        if (fieldMetadata instanceof ObjectNode object) {
            return object;
        }
        if (fieldMetadata instanceof ArrayNode array) {
            for (JsonNode item : array) {
                if (item instanceof ObjectNode object) {
                    return object;
                }
            }
            if (create) {
                ObjectNode object = objectMapper.createObjectNode();
                array.add(object);
                return object;
            }
            return null;
        }
        JsonNode normalizedSeed = proposedConfig.path("normalizedSeed");
        if (normalizedSeed instanceof ObjectNode object) {
            if (create) {
                proposedConfig.set("fieldMetadata", object.deepCopy());
                return (ObjectNode) proposedConfig.path("fieldMetadata");
            }
            return object;
        }
        if (create) {
            return proposedConfig.putObject("fieldMetadata");
        }
        return null;
    }

    private void metadataWriteNormalizedSeed(ObjectNode proposedConfig) {
        ObjectNode fieldMetadata = metadataFieldMetadata(proposedConfig, true);
        ObjectNode normalizedSeed = objectAt(proposedConfig, "normalizedSeed", true);
        mergeObject(normalizedSeed, fieldMetadata);
        ObjectNode form = objectAt(proposedConfig, "form", true);
        form.set("fieldMetadata", fieldMetadata.deepCopy());
    }

    private ObjectNode compileCrudResourceBind(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String resourcePath = text(input, "resourcePath");
        if (resourcePath.isBlank()) {
            failures.add("crud-resource-bind requires resourcePath");
            return null;
        }
        ObjectNode resource = objectAt(proposedConfig, "resource", true);
        JsonNode previousResource = resource.deepCopy();
        resource.put("path", resourcePath);
        if (input.has("resourceKey")) {
            resource.set("resourceKey", input.path("resourceKey"));
        }
        if (input.has("idField")) {
            resource.set("idField", input.path("idField"));
        }
        if (input.has("endpointKey")) {
            resource.set("endpointKey", input.path("endpointKey"));
        }
        if (input.has("queryContext")) {
            proposedConfig.set("queryContext", input.path("queryContext").deepCopy());
        }
        if (!proposedConfig.has("filterCriteria")) {
            proposedConfig.set("filterCriteria", objectMapper.createObjectNode());
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "bind-crud-resource");
        compiled.put("path", "resource");
        compiled.set("previousResource", previousResource);
        compiled.set("resource", resource.deepCopy());
        compiled.set("queryContext", proposedConfig.path("queryContext"));
        return compiled;
    }

    private ObjectNode compileCrudListSurfaceConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        JsonNode previousTable = proposedConfig.path("table").deepCopy();
        if (input.has("tablePatch")) {
            ObjectNode table = objectAt(proposedConfig, "table", true);
            mergeObject(table, input.path("tablePatch"));
        }
        if (input.has("queryContext")) {
            proposedConfig.set("queryContext", input.path("queryContext").deepCopy());
        }
        if (input.has("filterCriteria")) {
            proposedConfig.set("filterCriteria", input.path("filterCriteria").deepCopy());
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-crud-list-surface");
        compiled.put("path", "table");
        compiled.set("previousTable", previousTable);
        compiled.set("table", proposedConfig.path("table"));
        compiled.set("queryContext", proposedConfig.path("queryContext"));
        compiled.set("filterCriteria", proposedConfig.path("filterCriteria"));
        return compiled;
    }

    private ObjectNode compileCrudActionSurfaceConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            String expectedActionId,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String actionId = textOrDefault(input, "actionId", expectedActionId);
        if (!expectedActionId.equals(actionId)) {
            failures.add("crud surface configure expected actionId " + expectedActionId + " but received " + actionId);
            return null;
        }
        ObjectNode action = crudAction(proposedConfig, actionId, true);
        JsonNode previousAction = action.deepCopy();
        copyCrudActionFields(input, action);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-crud-action-surface");
        compiled.put("path", "actions[]");
        compiled.put("actionId", actionId);
        compiled.set("previousAction", previousAction);
        compiled.set("action", action.deepCopy());
        return compiled;
    }

    private ObjectNode compileCrudDeleteBehaviorSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode action = crudAction(proposedConfig, "delete", true);
        JsonNode previousAction = action.deepCopy();
        if (input.has("enabled")) {
            action.put("disabled", !input.path("enabled").asBoolean());
        }
        copyIfPresent(input, action, "requiresConfirmation");
        copyIfPresent(input, action, "autoDelete");
        if (input.has("form")) {
            ObjectNode form = action.path("form") instanceof ObjectNode object ? object : action.putObject("form");
            mergeObject(form, input.path("form"));
        }
        if (action.path("autoDelete").asBoolean(false) && !action.path("requiresConfirmation").asBoolean(false)) {
            failures.add("crud-delete-behavior-set autoDelete requires requiresConfirmation");
            return null;
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-crud-delete-behavior");
        compiled.put("path", "actions[]");
        compiled.set("previousAction", previousAction);
        compiled.set("action", action.deepCopy());
        return compiled;
    }

    private ObjectNode compileCrudDialogHostSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        ObjectNode defaults = objectAt(proposedConfig, "defaults", true);
        JsonNode previousDefaults = defaults.deepCopy();
        if (input.has("defaultOpenMode")) {
            defaults.set("openMode", input.path("defaultOpenMode"));
        }
        if (input.has("modal")) {
            ObjectNode modal = defaults.path("modal") instanceof ObjectNode object ? object : defaults.putObject("modal");
            mergeObject(modal, input.path("modal"));
        }
        if (input.has("back")) {
            defaults.set("back", input.path("back").deepCopy());
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-crud-dialog-host-defaults");
        compiled.put("path", "defaults");
        compiled.set("previousDefaults", previousDefaults);
        compiled.set("defaults", defaults.deepCopy());
        return compiled;
    }

    private ObjectNode compileCrudPermissionsSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        ArrayNode actions = arrayAt(proposedConfig, "actions", true);
        JsonNode previousActions = actions.deepCopy();
        boolean denyWhenMissing = input.path("denyWhenMissingCapability").asBoolean(true);
        Set<String> requiredCapabilities = textSet(input.path("requiredCapabilities"));
        JsonNode actionPermissions = input.path("actionPermissions");
        for (JsonNode actionNode : actions) {
            if (!(actionNode instanceof ObjectNode action)) {
                continue;
            }
            String actionId = firstText(action, "id", "action", "name");
            JsonNode permission = actionPermissions.path(actionId);
            if (!permission.isMissingNode()) {
                if (permission.has("disabled")) {
                    action.set("disabled", permission.path("disabled"));
                }
                if (permission.has("visibleWhen")) {
                    action.set("visibleWhen", permission.path("visibleWhen"));
                }
                if (permission.has("requiresConfirmation")) {
                    action.set("requiresConfirmation", permission.path("requiresConfirmation"));
                }
            } else if (denyWhenMissing && !requiredCapabilities.isEmpty() && !requiredCapabilities.contains(actionId)) {
                action.put("disabled", true);
            }
            if ("delete".equals(actionId)) {
                action.put("requiresConfirmation", true);
            }
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-crud-permissions");
        compiled.put("path", "actions[]");
        compiled.set("previousActions", previousActions);
        compiled.set("actions", actions.deepCopy());
        return compiled;
    }

    private ObjectNode compileCrudChildOperationDelegate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String childComponentId = text(input, "childComponentId");
        String childOperationId = text(input, "childOperationId");
        if (childComponentId.isBlank() || childOperationId.isBlank() || text(input, "reason").isBlank()) {
            failures.add("crud-child-operation-delegate requires childComponentId, childOperationId, and reason");
            return null;
        }
        ArrayNode delegated = arrayAt(proposedConfig, "delegatedAuthoringOperations", true);
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("childComponentId", childComponentId);
        entry.put("childOperationId", childOperationId);
        entry.put("reason", text(input, "reason"));
        if (input.has("childTarget")) {
            entry.set("childTarget", input.path("childTarget").deepCopy());
        }
        if (input.has("childParams")) {
            entry.set("childParams", input.path("childParams").deepCopy());
        }
        entry.put("status", "delegated-to-child-manifest");
        delegated.add(entry);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "delegate-crud-child-operation");
        compiled.put("path", "delegatedAuthoringOperations[]");
        compiled.put("appendedIndex", delegated.size() - 1);
        compiled.set("delegatedOperation", entry);
        return compiled;
    }

    private ObjectNode compileExpansionMultiExpandSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (!input.path("multi").isBoolean()) {
            failures.add("expansion-multi-expand-set requires boolean input.multi");
            return null;
        }
        boolean multi = input.path("multi").asBoolean();
        ObjectNode accordion = objectAt(proposedConfig, "accordion", true);
        boolean previousMulti = accordion.path("multi").asBoolean(false);
        accordion.put("multi", multi);

        ArrayNode panels = arrayAt(proposedConfig, "panels", false);
        String preservedExpandedPanelId = "";
        if (!multi && panels != null) {
            preservedExpandedPanelId = firstExpandedPanelId(panels);
            if (!preservedExpandedPanelId.isBlank()) {
                collapseAllPanelsExcept(panels, preservedExpandedPanelId);
            }
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-expansion-multi-expand");
        compiled.put("path", "accordion.multi");
        compiled.put("previousValue", previousMulti);
        compiled.put("value", multi);
        compiled.put("preservedExpandedPanelId", preservedExpandedPanelId);
        return compiled;
    }

    private ObjectNode compileExpansionDefaultExpandedUpsert(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode panels = arrayAt(proposedConfig, "panels", false);
        if (panels == null) {
            failures.add("expansion-default-expanded-upsert path is not an array: panels[]");
            return null;
        }
        String panelId = resolved != null ? text(resolved.value(), "id") : text(planOperation.path("input"), "panelId");
        int panelIndex = indexOfObjectByKey(panels, "id", panelId);
        if (panelIndex < 0 || !(panels.get(panelIndex) instanceof ObjectNode targetPanel)) {
            failures.add("expansion-default-expanded-upsert target not found: panels[] id=" + panelId);
            return null;
        }
        if (targetPanel.path("disabled").asBoolean(false) && planOperation.path("input").path("expanded").asBoolean(false)) {
            failures.add("expansion-default-expanded-upsert cannot expand disabled panel: " + panelId);
            return null;
        }
        boolean expanded = planOperation.path("input").path("expanded").asBoolean(false);
        boolean previousExpanded = targetPanel.path("expanded").asBoolean(false);
        boolean multi = proposedConfig.path("accordion").path("multi").asBoolean(false);
        boolean collapseOthers = planOperation.path("input").path("collapseOthers").asBoolean(false);
        if (expanded && (!multi || collapseOthers)) {
            collapseAllPanelsExcept(panels, panelId);
        }
        targetPanel.put("expanded", expanded);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "set-expansion-default-expanded");
        compiled.put("path", "panels[].expanded");
        compiled.put("keyValue", panelId);
        compiled.put("panelIndex", panelIndex);
        compiled.put("previousValue", previousExpanded);
        compiled.put("value", expanded);
        compiled.put("collapseOthers", collapseOthers || (expanded && !multi));
        return compiled;
    }

    private ObjectNode compileFormLayoutFieldCleanup(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String fieldName = firstNonBlank(text(resolved == null ? MissingNode.getInstance() : resolved.value(), "name"), targetText(planOperation.path("target")));
        if (fieldName.isBlank()) {
            failures.add("form-layout-field-cleanup requires a resolved field name");
            return null;
        }
        ArrayNode fields = arrayAt(proposedConfig, "fieldMetadata", false);
        int removedIndex = -1;
        if (fields != null) {
            removedIndex = indexOfObjectByKey(fields, "name", fieldName);
            if (removedIndex >= 0) {
                fields.remove(removedIndex);
            }
        }
        int removedRefs = removeFormFieldLayoutRefs(proposedConfig, fieldName);
        if (removedIndex < 0 && removedRefs == 0) {
            failures.add("form-layout-field-cleanup target not found: " + fieldName);
            return null;
        }
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "remove-form-local-field-and-layout-refs");
        compiled.put("path", "fieldMetadata[]");
        compiled.put("key", "name");
        compiled.put("keyValue", fieldName);
        compiled.put("removedIndex", removedIndex);
        compiled.put("layoutRefsRemoved", removedRefs);
        return compiled;
    }

    private ObjectNode compileFormLayoutSectionCleanup(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String sectionId = firstNonBlank(text(resolved == null ? MissingNode.getInstance() : resolved.value(), "id"), targetText(planOperation.path("target")));
        ArrayNode sections = arrayAt(proposedConfig, "sections", false);
        if (sectionId.isBlank() || sections == null) {
            failures.add("form-layout-section-cleanup requires an existing section target");
            return null;
        }
        int removedIndex = indexOfObjectByKey(sections, "id", sectionId);
        if (removedIndex < 0) {
            failures.add("form-layout-section-cleanup target not found: " + sectionId);
            return null;
        }
        JsonNode removed = sections.remove(removedIndex);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "remove-form-section-and-layout");
        compiled.put("path", "sections[]");
        compiled.put("key", "id");
        compiled.put("keyValue", sectionId);
        compiled.put("removedIndex", removedIndex);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileFormLayoutReconciler(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String fieldName = firstNonBlank(text(resolved == null ? MissingNode.getInstance() : resolved.value(), "name"), targetText(planOperation.path("target")));
        JsonNode input = planOperation.path("input");
        ObjectNode targetColumn = formColumnByIds(proposedConfig, text(input, "targetSectionId"), text(input, "targetRowId"), text(input, "targetColumnId"));
        if (fieldName.isBlank() || targetColumn == null) {
            failures.add("form-layout-reconciler requires field and target section/row/column");
            return null;
        }
        int removedRefs = removeFormFieldLayoutRefs(proposedConfig, fieldName);
        boolean useItems = targetColumn.path("items").isArray();
        int insertedIndex;
        if (useItems) {
            ArrayNode items = formColumnItems(targetColumn, true);
            ObjectNode fieldItem = objectMapper.createObjectNode();
            fieldItem.put("type", "field");
            fieldItem.put("field", fieldName);
            insertedIndex = boundedInsertIndex(input, items.size(), failures, "form-layout-reconciler");
            if (insertedIndex < 0) {
                return null;
            }
            items.insert(insertedIndex, fieldItem);
        } else {
            ArrayNode fields = formColumnFields(targetColumn, true);
            insertedIndex = boundedInsertIndex(input, fields.size(), failures, "form-layout-reconciler");
            if (insertedIndex < 0) {
                return null;
            }
            fields.insert(insertedIndex, fieldName);
        }
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "move-form-field-layout-ref");
        compiled.put("path", useItems ? "sections[].rows[].columns[].items" : "sections[].rows[].columns[].fields");
        compiled.put("key", "field");
        compiled.put("keyValue", fieldName);
        compiled.put("layoutRefsRemoved", removedRefs);
        compiled.put("insertedIndex", insertedIndex);
        compiled.put("targetSectionId", text(input, "targetSectionId"));
        compiled.put("targetRowId", text(input, "targetRowId"));
        compiled.put("targetColumnId", text(input, "targetColumnId"));
        return compiled;
    }

    private ObjectNode compileFormLayoutVisualBlockAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String id = text(input, "id");
        ObjectNode targetColumn = formColumnFromResolved(proposedConfig, resolved);
        if (id.isBlank() || targetColumn == null) {
            failures.add("form-layout-visual-block-add requires input.id and a resolved column");
            return null;
        }
        ArrayNode items = formColumnItems(targetColumn, true);
        int insertedIndex = boundedInsertIndex(input, items.size(), failures, "form-layout-visual-block-add");
        if (insertedIndex < 0) {
            return null;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("type", "richContent");
        item.put("kind", "richContent");
        item.set("document", input.path("document").deepCopy());
        copyIfPresent(input, item, "layout");
        copyIfPresent(input, item, "rootClassName");
        items.insert(insertedIndex, item);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "add-form-visual-block");
        compiled.put("path", "sections[].rows[].columns[].items");
        compiled.put("key", "id");
        compiled.put("keyValue", id);
        compiled.put("insertedIndex", insertedIndex);
        compiled.set("value", item);
        return compiled;
    }

    private ObjectNode compileFormLayoutVisualBlockUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode resolvedNode = resolved == null ? MissingNode.getInstance() : nodeAtResolvedPath(proposedConfig, resolved.path());
        if (!(resolvedNode instanceof ObjectNode item)) {
            failures.add("form-layout-visual-block-update requires a resolved visual block");
            return null;
        }
        JsonNode input = planOperation.path("input");
        copyIfPresent(input, item, "document");
        copyIfPresent(input, item, "layout");
        copyIfPresent(input, item, "rootClassName");
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "update-form-visual-block");
        compiled.put("path", resolved.path());
        compiled.put("key", "id");
        compiled.put("keyValue", text(item, "id"));
        compiled.set("value", item);
        return compiled;
    }

    private ObjectNode compileFormLayoutVisualBlockMove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode targetColumn = formColumnByIds(proposedConfig, text(input, "targetSectionId"), text(input, "targetRowId"), text(input, "targetColumnId"));
        ArrayNode sourceItems = formItemsArrayFromResolvedPath(proposedConfig, resolved == null ? "" : resolved.path());
        if (targetColumn == null || sourceItems == null || resolved == null) {
            failures.add("form-layout-visual-block-move requires resolved block and target section/row/column");
            return null;
        }
        int sourceIndex = indexFromResolvedArrayPath(resolved.path());
        if (sourceIndex < 0 || sourceIndex >= sourceItems.size()) {
            failures.add("form-layout-visual-block-move source index is invalid");
            return null;
        }
        JsonNode item = sourceItems.remove(sourceIndex);
        ArrayNode targetItems = formColumnItems(targetColumn, true);
        int insertedIndex = boundedInsertIndex(input, targetItems.size(), failures, "form-layout-visual-block-move");
        if (insertedIndex < 0) {
            return null;
        }
        targetItems.insert(insertedIndex, item);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "move-form-visual-block");
        compiled.put("path", "sections[].rows[].columns[].items");
        compiled.put("key", "id");
        compiled.put("keyValue", text(item, "id"));
        compiled.put("sourceIndex", sourceIndex);
        compiled.put("insertedIndex", insertedIndex);
        compiled.put("targetSectionId", text(input, "targetSectionId"));
        compiled.put("targetRowId", text(input, "targetRowId"));
        compiled.put("targetColumnId", text(input, "targetColumnId"));
        return compiled;
    }

    private ObjectNode compileFormLayoutVisualBlockRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        ArrayNode sourceItems = formItemsArrayFromResolvedPath(proposedConfig, resolved == null ? "" : resolved.path());
        int sourceIndex = indexFromResolvedArrayPath(resolved == null ? "" : resolved.path());
        if (sourceItems == null || sourceIndex < 0 || sourceIndex >= sourceItems.size()) {
            failures.add("form-layout-visual-block-remove requires a resolved visual block");
            return null;
        }
        JsonNode removed = sourceItems.remove(sourceIndex);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, resolved);
        compiled.put("op", "remove-form-visual-block");
        compiled.put("path", "sections[].rows[].columns[].items");
        compiled.put("key", "id");
        compiled.put("keyValue", text(removed, "id"));
        compiled.put("removedIndex", sourceIndex);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderRuleAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String ruleId = text(input, "ruleId");
        if (ruleId.isBlank()) {
            failures.add("table-rule-builder-rule-add requires input.ruleId");
            return null;
        }
        ArrayNode rules = tableRuleRules(proposedConfig, true);
        if (findObjectByAnyKey(rules, List.of("ruleId", "id"), ruleId) != null) {
            failures.add("table-rule-builder-rule-add duplicate ruleId: " + ruleId);
            return null;
        }
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("ruleId", ruleId);
        rule.put("id", ruleId);
        copyIfPresent(input, rule, "scope");
        copyIfPresent(input, rule, "columnKey");
        if (input.has("condition")) {
            rule.set("condition", input.path("condition").deepCopy());
        }
        ArrayNode effects = rule.putArray("effects");
        if (input.path("effect").isObject()) {
            ObjectNode effectValue = input.path("effect").deepCopy();
            if (!effectValue.has("effectId")) {
                effectValue.put("effectId", ruleId + "-effect");
            }
            effects.add(effectValue);
        }
        int insertedIndex = rules.size();
        String insertAfter = text(input, "insertAfterRuleId");
        if (!insertAfter.isBlank()) {
            int afterIndex = indexOfObjectByAnyKey(rules, List.of("ruleId", "id"), insertAfter);
            if (afterIndex < 0) {
                failures.add("table-rule-builder-rule-add insertAfterRuleId not found: " + insertAfter);
                return null;
            }
            insertedIndex = afterIndex + 1;
            rules.insert(insertedIndex, rule);
        } else {
            rules.add(rule);
        }
        appendTableRuleDelegation(proposedConfig, "rule.add", input, ruleId);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-table-rule-builder-rule");
        compiled.put("path", "ruleEffects.rules[]");
        compiled.put("key", "ruleId");
        compiled.put("keyValue", ruleId);
        compiled.put("insertedIndex", insertedIndex);
        compiled.set("value", rule);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderRuleRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        String ruleId = firstNonBlank(text(planOperation.path("input"), "ruleId"), targetText(planOperation.path("target")));
        ArrayNode rules = tableRuleRules(proposedConfig, false);
        int index = rules == null ? -1 : indexOfObjectByAnyKey(rules, List.of("ruleId", "id"), ruleId);
        if (index < 0) {
            failures.add("table-rule-builder-rule-remove target not found: " + ruleId);
            return null;
        }
        JsonNode removed = rules.remove(index);
        appendTableRuleDelegation(proposedConfig, "rule.remove", planOperation.path("input"), ruleId);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-table-rule-builder-rule");
        compiled.put("path", "ruleEffects.rules[]");
        compiled.put("key", "ruleId");
        compiled.put("keyValue", ruleId);
        compiled.put("removedIndex", index);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderConditionSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode rule = tableRuleByInput(proposedConfig, planOperation.path("input"));
        if (rule == null) {
            failures.add("table-rule-builder-condition-set rule not found: " + text(planOperation.path("input"), "ruleId"));
            return null;
        }
        JsonNode input = planOperation.path("input");
        if ("merge".equals(text(input, "mode")) && rule.path("condition").isObject()) {
            mergeObject((ObjectNode) rule.path("condition"), input.path("condition"));
        } else {
            rule.set("condition", input.path("condition").deepCopy());
        }
        copyIfPresent(input, rule, "scope");
        copyIfPresent(input, rule, "columnKey");
        appendTableRuleDelegation(proposedConfig, "condition.set", input, text(rule, "ruleId"));
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-table-rule-builder-condition");
        compiled.put("path", "ruleEffects.rules[].condition");
        compiled.put("key", "ruleId");
        compiled.put("keyValue", text(rule, "ruleId"));
        compiled.set("value", rule.path("condition"));
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderEffectAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode rule = tableRuleByInput(proposedConfig, planOperation.path("input"));
        JsonNode input = planOperation.path("input");
        String effectId = text(input, "effectId");
        if (rule == null || effectId.isBlank()) {
            failures.add("table-rule-builder-effect-add requires existing rule and input.effectId");
            return null;
        }
        ArrayNode effects = tableRuleEffects(rule, true);
        if (findObjectByAnyKey(effects, List.of("effectId", "id"), effectId) != null) {
            failures.add("table-rule-builder-effect-add duplicate effectId: " + effectId);
            return null;
        }
        ObjectNode newEffect = objectMapper.createObjectNode();
        newEffect.put("effectId", effectId);
        newEffect.put("id", effectId);
        newEffect.put("effectType", text(input, "effectType"));
        if (input.has("payload")) {
            newEffect.set("payload", input.path("payload").deepCopy());
        }
        effects.add(newEffect);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-table-rule-builder-effect");
        compiled.put("path", "ruleEffects.rules[].effects[]");
        compiled.put("key", "effectId");
        compiled.put("keyValue", effectId);
        compiled.put("ruleId", text(rule, "ruleId"));
        compiled.put("insertedIndex", effects.size() - 1);
        compiled.set("value", newEffect);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderEffectUpdate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode targetEffect = tableRuleEffectByInput(proposedConfig, planOperation.path("input"));
        if (targetEffect == null) {
            failures.add("table-rule-builder-effect-update effect not found: " + text(planOperation.path("input"), "effectId"));
            return null;
        }
        JsonNode payload = planOperation.path("input").path("payload");
        if ("replace".equals(text(planOperation.path("input"), "mode")) || !targetEffect.path("payload").isObject()) {
            targetEffect.set("payload", payload.deepCopy());
        } else {
            mergeObject((ObjectNode) targetEffect.path("payload"), payload);
        }
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "update-table-rule-builder-effect");
        compiled.put("path", "ruleEffects.rules[].effects[].payload");
        compiled.put("key", "effectId");
        compiled.put("keyValue", text(targetEffect, "effectId"));
        compiled.set("value", targetEffect);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderEffectRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode rule = tableRuleByInput(proposedConfig, input);
        if (rule == null) {
            failures.add("table-rule-builder-effect-remove rule not found: " + text(input, "ruleId"));
            return null;
        }
        ArrayNode effects = tableRuleEffects(rule, false);
        String effectId = text(input, "effectId");
        int index = effects == null ? -1 : indexOfObjectByAnyKey(effects, List.of("effectId", "id"), effectId);
        if (index < 0) {
            failures.add("table-rule-builder-effect-remove effect not found: " + effectId);
            return null;
        }
        JsonNode removed = effects.remove(index);
        if (effects.isEmpty() && input.path("removeEmptyRule").asBoolean(false)) {
            ArrayNode rules = tableRuleRules(proposedConfig, false);
            int ruleIndex = indexOfObjectByAnyKey(rules, List.of("ruleId", "id"), text(rule, "ruleId"));
            if (ruleIndex >= 0) {
                rules.remove(ruleIndex);
            }
        }
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-table-rule-builder-effect");
        compiled.put("path", "ruleEffects.rules[].effects[]");
        compiled.put("key", "effectId");
        compiled.put("keyValue", effectId);
        compiled.put("removedIndex", index);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderPresetApply(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode rule = tableRuleByInput(proposedConfig, planOperation.path("input"));
        JsonNode input = planOperation.path("input");
        String presetKey = text(input, "presetKey");
        if (rule == null || !tableRulePresetKeys().contains(presetKey)) {
            failures.add("table-rule-builder-preset-apply requires existing rule and known preset");
            return null;
        }
        ArrayNode effects = tableRuleEffects(rule, true);
        if (!input.path("mergeWithExisting").asBoolean(false)) {
            effects.removeAll();
        }
        ObjectNode presetEffect = tableRulePresetEffect(presetKey);
        if (input.has("scope")) {
            rule.set("scope", input.path("scope"));
        }
        effects.add(presetEffect);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "apply-table-rule-builder-preset");
        compiled.put("path", "ruleEffects.rules[].effects[]");
        compiled.put("key", "presetKey");
        compiled.put("keyValue", presetKey);
        compiled.set("value", presetEffect);
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderAnimationSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode targetEffect = text(input, "effectId").isBlank()
                ? firstTableRuleEffect(tableRuleByInput(proposedConfig, input))
                : tableRuleEffectByInput(proposedConfig, input);
        if (targetEffect == null) {
            failures.add("table-rule-builder-animation-set effect not found for rule: " + text(input, "ruleId"));
            return null;
        }
        targetEffect.set("animation", input.path("animation").deepCopy());
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-table-rule-builder-animation");
        compiled.put("path", "ruleEffects.rules[].effects[].animation");
        compiled.put("key", "effectId");
        compiled.put("keyValue", text(targetEffect, "effectId"));
        compiled.set("value", targetEffect.path("animation"));
        return compiled;
    }

    private ObjectNode compileTableRuleBuilderTableDelegate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String tableOperationId = text(input, "tableOperationId");
        if (tableOperationId.isBlank()) {
            failures.add("table-rule-builder-table-delegate requires input.tableOperationId");
            return null;
        }
        ObjectNode delegation = appendTableRuleDelegation(proposedConfig, tableOperationId, input, tableOperationId);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "delegate-table-rule-builder-table-operation");
        compiled.put("path", "delegatedAuthoringOperations[]");
        compiled.put("key", "tableOperationId");
        compiled.put("keyValue", tableOperationId);
        compiled.set("value", delegation);
        return compiled;
    }

    private ObjectNode compileVisualBuilderNodeAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String nodeId = text(input, "nodeId");
        if (nodeId.isBlank()) {
            failures.add("visual-builder-node-add requires input.nodeId");
            return null;
        }
        ArrayNode nodes = visualNodes(proposedConfig, true);
        if (findObjectByAnyKey(nodes, List.of("id", "nodeId"), nodeId) != null) {
            failures.add("visual-builder-node-add duplicate nodeId: " + nodeId);
            return null;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", nodeId);
        node.put("nodeId", nodeId);
        copyIfPresent(input, node, "nodeType");
        copyIfPresent(input, node, "nodeType", "type");
        copyIfPresent(input, node, "label");
        copyIfPresent(input, node, "parentId");
        if (input.has("config")) {
            node.set("config", input.path("config").deepCopy());
        }
        if (input.has("metadata")) {
            node.set("metadata", input.path("metadata").deepCopy());
        }
        node.putArray("children");
        nodes.add(node);
        String parentId = text(input, "parentId");
        if (!parentId.isBlank()) {
            ObjectNode parent = visualNodeById(proposedConfig, parentId);
            if (parent == null) {
                failures.add("visual-builder-node-add parent not found: " + parentId);
                return null;
            }
            addTextUnique(visualChildren(parent, true), nodeId);
        } else {
            addTextUnique(visualRootNodes(proposedConfig, true), nodeId);
        }
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-visual-builder-node");
        compiled.put("path", "nodes[]");
        compiled.put("key", "id");
        compiled.put("keyValue", nodeId);
        compiled.set("value", node);
        return compiled;
    }

    private ObjectNode compileVisualBuilderNodeRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        String nodeId = firstNonBlank(text(planOperation.path("input"), "nodeId"), targetText(planOperation.path("target")));
        ArrayNode nodes = visualNodes(proposedConfig, false);
        int index = nodes == null ? -1 : indexOfObjectByAnyKey(nodes, List.of("id", "nodeId"), nodeId);
        if (index < 0) {
            failures.add("visual-builder-node-remove target not found: " + nodeId);
            return null;
        }
        if (planOperation.path("input").path("removeDescendants").asBoolean(false)) {
            removeVisualDescendants(proposedConfig, nodeId);
            nodes = visualNodes(proposedConfig, false);
            index = indexOfObjectByAnyKey(nodes, List.of("id", "nodeId"), nodeId);
        } else if (visualNodeById(proposedConfig, nodeId).path("children").isArray()
                && visualNodeById(proposedConfig, nodeId).path("children").size() > 0
                && !planOperation.path("input").path("preserveDisconnectedChildren").asBoolean(false)) {
            failures.add("visual-builder-node-remove requires removeDescendants or preserveDisconnectedChildren for node with children: " + nodeId);
            return null;
        } else if (planOperation.path("input").path("preserveDisconnectedChildren").asBoolean(false)) {
            for (JsonNode childId : visualNodeById(proposedConfig, nodeId).path("children")) {
                ObjectNode child = visualNodeById(proposedConfig, childId.asText(""));
                if (child != null) {
                    child.remove("parentId");
                    addTextUnique(visualRootNodes(proposedConfig, true), text(child, "id"));
                }
            }
        }
        JsonNode removed = nodes.remove(index);
        removeVisualEdgesTo(proposedConfig, nodeId);
        removeTextValue(visualRootNodes(proposedConfig, false), nodeId);
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-visual-builder-node");
        compiled.put("path", "nodes[]");
        compiled.put("key", "id");
        compiled.put("keyValue", nodeId);
        compiled.put("removedIndex", index);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileVisualBuilderNodeConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode node = visualNodeById(proposedConfig, text(planOperation.path("input"), "nodeId"));
        if (node == null) {
            failures.add("visual-builder-node-configure target not found: " + text(planOperation.path("input"), "nodeId"));
            return null;
        }
        JsonNode input = planOperation.path("input");
        copyIfPresent(input, node, "label");
        copyIfPresent(input, node, "expanded");
        if (input.path("configPatch").isObject()) {
            ObjectNode config = node.path("config") instanceof ObjectNode object ? object : node.putObject("config");
            mergeObject(config, input.path("configPatch"));
        }
        if (input.path("metadataPatch").isObject()) {
            ObjectNode metadata = node.path("metadata") instanceof ObjectNode object ? object : node.putObject("metadata");
            mergeObject(metadata, input.path("metadataPatch"));
        }
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-visual-builder-node");
        compiled.put("path", "nodes[].config");
        compiled.put("key", "id");
        compiled.put("keyValue", text(node, "id"));
        compiled.set("value", node);
        return compiled;
    }

    private ObjectNode compileVisualBuilderEdgeConnect(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode source = visualNodeById(proposedConfig, text(input, "sourceNodeId"));
        ObjectNode target = visualNodeById(proposedConfig, text(input, "targetNodeId"));
        if (source == null || target == null) {
            failures.add("visual-builder-edge-connect requires existing source and target nodes");
            return null;
        }
        if (visualNodeReachable(proposedConfig, text(input, "targetNodeId"), text(input, "sourceNodeId"))) {
            failures.add("visual-builder-edge-connect would create a cycle: "
                    + text(input, "sourceNodeId") + " -> " + text(input, "targetNodeId"));
            return null;
        }
        ArrayNode children = visualChildren(source, true);
        int index = input.has("insertIndex")
                ? boundedRawInsertIndex(input.path("insertIndex"), children.size(), failures, "visual-builder-edge-connect")
                : boundedInsertIndex(input, children.size(), failures, "visual-builder-edge-connect");
        if (index < 0) {
            return null;
        }
        removeTextValue(children, text(input, "targetNodeId"));
        children.insert(Math.min(index, children.size()), text(input, "targetNodeId"));
        target.put("parentId", text(input, "sourceNodeId"));
        removeTextValue(visualRootNodes(proposedConfig, false), text(input, "targetNodeId"));
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "connect-visual-builder-edge");
        compiled.put("path", "nodes[].children");
        compiled.put("sourceNodeId", text(input, "sourceNodeId"));
        compiled.put("targetNodeId", text(input, "targetNodeId"));
        return compiled;
    }

    private ObjectNode compileVisualBuilderEdgeRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode source = visualNodeById(proposedConfig, text(input, "sourceNodeId"));
        ObjectNode target = visualNodeById(proposedConfig, text(input, "targetNodeId"));
        if (source == null || !removeTextValue(visualChildren(source, false), text(input, "targetNodeId"))) {
            failures.add("visual-builder-edge-remove edge not found");
            return null;
        }
        if (target != null && text(input, "sourceNodeId").equals(text(target, "parentId"))) {
            target.remove("parentId");
            if (!input.path("removeOrphanTarget").asBoolean(false)) {
                addTextUnique(visualRootNodes(proposedConfig, true), text(input, "targetNodeId"));
            } else {
                removeVisualDescendants(proposedConfig, text(input, "targetNodeId"));
                ArrayNode nodes = visualNodes(proposedConfig, false);
                int index = indexOfObjectByAnyKey(nodes, List.of("id", "nodeId"), text(input, "targetNodeId"));
                if (index >= 0) {
                    nodes.remove(index);
                }
            }
        }
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-visual-builder-edge");
        compiled.put("path", "nodes[].children");
        compiled.put("sourceNodeId", text(input, "sourceNodeId"));
        compiled.put("targetNodeId", text(input, "targetNodeId"));
        return compiled;
    }

    private ObjectNode compileVisualBuilderVariableAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String name = text(input, "name");
        String scope = text(input, "scope");
        ArrayNode variables = arrayAt(proposedConfig, "contextVariables", true);
        if (findVisualVariable(variables, name, scope) != null) {
            failures.add("visual-builder-variable-add duplicate variable: " + scope + "." + name);
            return null;
        }
        ObjectNode variable = objectMapper.createObjectNode();
        copyIfPresent(input, variable, "name");
        copyIfPresent(input, variable, "scope");
        copyIfPresent(input, variable, "type");
        copyIfPresent(input, variable, "valueType");
        copyIfPresent(input, variable, "defaultValue");
        copyIfPresent(input, variable, "description");
        variables.add(variable);
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-visual-builder-variable");
        compiled.put("path", "contextVariables[]");
        compiled.put("key", "name");
        compiled.put("keyValue", name);
        compiled.set("value", variable);
        return compiled;
    }

    private ObjectNode compileVisualBuilderConditionSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode node = visualNodeById(proposedConfig, text(planOperation.path("input"), "nodeId"));
        if (node == null) {
            failures.add("visual-builder-condition-set target not found: " + text(planOperation.path("input"), "nodeId"));
            return null;
        }
        ObjectNode config = node.path("config") instanceof ObjectNode object ? object : node.putObject("config");
        if ("merge".equals(text(planOperation.path("input"), "mode")) && config.path("condition").isObject()) {
            mergeObject((ObjectNode) config.path("condition"), planOperation.path("input").path("condition"));
        } else {
            config.set("condition", planOperation.path("input").path("condition").deepCopy());
        }
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-visual-builder-condition");
        compiled.put("path", "nodes[].config.condition");
        compiled.put("key", "id");
        compiled.put("keyValue", text(node, "id"));
        compiled.set("value", config.path("condition"));
        return compiled;
    }

    private ObjectNode compileVisualBuilderEffectSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        ObjectNode node = visualNodeById(proposedConfig, text(planOperation.path("input"), "nodeId"));
        if (node == null) {
            failures.add("visual-builder-effect-set target not found: " + text(planOperation.path("input"), "nodeId"));
            return null;
        }
        ObjectNode config = node.path("config") instanceof ObjectNode object ? object : node.putObject("config");
        JsonNode input = planOperation.path("input");
        copyIfPresent(input, config, "targetType");
        copyIfPresent(input, config, "targets");
        copyIfPresent(input, config, "properties");
        copyIfPresent(input, config, "propertiesWhenFalse");
        copyIfPresent(input, config, "conditionNodeId");
        writeVisualCurrentJson(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-visual-builder-effect");
        compiled.put("path", "nodes[].config");
        compiled.put("key", "id");
        compiled.put("keyValue", text(node, "id"));
        compiled.set("value", config);
        return compiled;
    }

    private ObjectNode compileVisualBuilderDslRoundTripValidate(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        writeVisualCurrentJson(proposedConfig);
        ArrayNode validationErrors = arrayAt(proposedConfig, "validationErrors", true);
        validationErrors.removeAll();
        appendVisualGraphValidationErrors(proposedConfig, validationErrors);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "validate-visual-builder-dsl-round-trip");
        compiled.put("path", "currentJSON");
        compiled.set("value", proposedConfig.path("currentJSON"));
        compiled.set("validationErrors", validationErrors);
        return compiled;
    }

    private ObjectNode compileManualFieldAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String fieldName = text(input, "fieldName");
        String controlType = text(input, "controlType");
        if (fieldName.isBlank() || controlType.isBlank()) {
            failures.add("manual-field-add requires fieldName and controlType");
            return null;
        }
        ArrayNode fieldMetadata = manualFieldMetadata(proposedConfig, true);
        if (indexOfObjectByKey(fieldMetadata, "name", fieldName) >= 0) {
            failures.add("manual-field-add duplicate fieldName: " + fieldName);
            return null;
        }
        ObjectNode field = objectMapper.createObjectNode();
        field.put("name", fieldName);
        field.put("controlType", controlType);
        field.put("source", textOrDefault(input, "source", "manual-template"));
        copyIfPresent(input, field, "label");
        copyIfPresent(input, field, "required");
        copyIfPresent(input, field, "sectionId");
        copyIfPresent(input, field, "delegateFieldMetadataTo");
        fieldMetadata.add(field);
        syncManualFieldMetadata(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-manual-field");
        compiled.put("path", "currentConfig.fieldMetadata[]");
        compiled.put("insertedIndex", fieldMetadata.size() - 1);
        compiled.set("value", field.deepCopy());
        return compiled;
    }

    private ObjectNode compileManualFieldRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String fieldName = firstNonBlank(text(input, "fieldName"), targetText(planOperation.path("target")));
        ArrayNode fieldMetadata = manualFieldMetadata(proposedConfig, false);
        int index = indexOfObjectByKey(fieldMetadata, "name", fieldName);
        if (index < 0) {
            failures.add("manual-field-remove target not found: " + fieldName);
            return null;
        }
        JsonNode removed = fieldMetadata.remove(index);
        int removedLayoutRefs = input.path("removeFromLayout").asBoolean(false)
                ? removeFormFieldLayoutRefs(proposedConfig.path("currentConfig").path("sections"), fieldName)
                : 0;
        if (input.path("clearPersistedValue").asBoolean(false)) {
            ObjectNode form = objectAt(proposedConfig, "form", true);
            if (form.path("value") instanceof ObjectNode value) {
                value.remove(fieldName);
            }
        }
        syncManualFieldMetadata(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-manual-field");
        compiled.put("path", "currentConfig.fieldMetadata[]");
        compiled.put("removedIndex", index);
        compiled.put("removedLayoutRefs", removedLayoutRefs);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileManualFieldLabelSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String fieldName = firstNonBlank(text(input, "fieldName"), targetText(planOperation.path("target")));
        String label = text(input, "label");
        if (fieldName.isBlank() || label.isBlank()) {
            failures.add("manual-field-label-set requires fieldName and label");
            return null;
        }
        ObjectNode field = manualFieldByName(proposedConfig, fieldName);
        if (field == null) {
            failures.add("manual-field-label-set target not found: " + fieldName);
            return null;
        }
        JsonNode previousValue = field.deepCopy();
        field.put("label", label);
        if (input.path("updatePlaceholderWhenEmpty").asBoolean(false) && text(field, "placeholder").isBlank()) {
            field.put("placeholder", label);
        }
        syncManualFieldMetadata(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-manual-field-label");
        compiled.put("path", "currentConfig.fieldMetadata[].label");
        compiled.set("previousValue", previousValue);
        compiled.set("value", field.deepCopy());
        return compiled;
    }

    private ObjectNode compileManualLayoutConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode currentConfig = objectAt(proposedConfig, "currentConfig", true);
        JsonNode previousSections = currentConfig.path("sections").deepCopy();
        if (input.has("usePathNames")) {
            proposedConfig.set("usePathNames", input.path("usePathNames").deepCopy());
        }
        if (input.has("sections")) {
            currentConfig.set("sections", input.path("sections").deepCopy());
        }
        if (input.has("fieldOrder")) {
            ArrayNode ordered = objectMapper.createArrayNode();
            ArrayNode existing = manualFieldMetadata(proposedConfig, false);
            for (JsonNode fieldNameNode : input.path("fieldOrder")) {
                String fieldName = fieldNameNode.asText("");
                ObjectNode field = findObjectByKey(existing, "name", fieldName);
                if (field == null) {
                    failures.add("manual-layout-configure fieldOrder target not found: " + fieldName);
                    return null;
                }
                ordered.add(field.deepCopy());
            }
            currentConfig.set("fieldMetadata", ordered);
        }
        syncManualFieldMetadata(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-manual-layout");
        compiled.put("path", "currentConfig.sections");
        compiled.set("previousSections", previousSections);
        compiled.set("sections", currentConfig.path("sections"));
        return compiled;
    }

    private ObjectNode compileManualToolbarConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        JsonNode previousValue = proposedConfig.path("enableCustomization").deepCopy();
        if (input.has("enableCustomization")) {
            proposedConfig.set("enableCustomization", input.path("enableCustomization").deepCopy());
        }
        if (input.has("editableFlags")) {
            proposedConfig.set("editableFlags", input.path("editableFlags").deepCopy());
        }
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-manual-toolbar");
        compiled.put("path", "enableCustomization");
        compiled.set("previousValue", previousValue);
        compiled.set("value", proposedConfig.path("enableCustomization"));
        return compiled;
    }

    private ObjectNode compileManualAutosaveEnabledSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        JsonNode previousOptions = proposedConfig.path("persistenceOptions").deepCopy();
        proposedConfig.set("enableAutoSave", input.path("enabled").deepCopy());
        if (input.has("debounceMs")) {
            proposedConfig.set("autoSaveDebounceMs", input.path("debounceMs").deepCopy());
        }
        ObjectNode options = objectAt(proposedConfig, "persistenceOptions", true);
        copyIfPresent(input, options, "storageKey");
        copyIfPresent(input, options, "namespace");
        copyIfPresent(input, options, "tenantId");
        copyIfPresent(input, options, "profileId");
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-manual-autosave");
        compiled.put("path", "enableAutoSave");
        compiled.set("previousPersistenceOptions", previousOptions);
        compiled.set("persistenceOptions", options.deepCopy());
        return compiled;
    }

    private ObjectNode compileManualSubmitBehaviorSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        JsonNode input = planOperation.path("input");
        ObjectNode behavior = objectAt(proposedConfig, "currentConfig.behavior", true);
        JsonNode previousBehavior = behavior.deepCopy();
        mergeObject(behavior, input);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-manual-submit-behavior");
        compiled.put("path", "currentConfig.behavior");
        compiled.set("previousBehavior", previousBehavior);
        compiled.set("behavior", behavior.deepCopy());
        return compiled;
    }

    private ObjectNode compileManualMetadataBridgeConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String fieldName = text(input, "fieldName");
        if (!fieldName.isBlank() && manualFieldByName(proposedConfig, fieldName) == null) {
            failures.add("manual-metadata-bridge-configure field not found: " + fieldName);
            return null;
        }
        ObjectNode bridge = objectAt(proposedConfig, "metadataBridge", true);
        JsonNode previousBridge = bridge.deepCopy();
        mergeObject(bridge, input);
        if (input.has("enabled")) {
            proposedConfig.set("enableCustomization", input.path("enabled").deepCopy());
        }
        if (!fieldName.isBlank()) {
            ObjectNode field = manualFieldByName(proposedConfig, fieldName);
            copyIfPresent(input, field, "delegateFieldMetadataTo");
            copyIfPresent(input, field, "delegateControlDiscoveryTo");
        }
        syncManualFieldMetadata(proposedConfig);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-manual-metadata-bridge");
        compiled.put("path", "metadataBridge");
        compiled.set("previousBridge", previousBridge);
        compiled.set("metadataBridge", bridge.deepCopy());
        return compiled;
    }

    private ArrayNode manualFieldMetadata(ObjectNode proposedConfig, boolean create) {
        ArrayNode fromCurrentConfig = arrayAt(proposedConfig, "currentConfig.fieldMetadata", create);
        if (fromCurrentConfig != null) {
            return fromCurrentConfig;
        }
        return arrayAt(proposedConfig, "fieldMetadata", create);
    }

    private ObjectNode manualFieldByName(ObjectNode proposedConfig, String fieldName) {
        return findObjectByKey(manualFieldMetadata(proposedConfig, false), "name", fieldName);
    }

    private void syncManualFieldMetadata(ObjectNode proposedConfig) {
        ArrayNode fieldMetadata = manualFieldMetadata(proposedConfig, true);
        proposedConfig.set("currentFieldMetadata", fieldMetadata.deepCopy());
        ObjectNode form = objectAt(proposedConfig, "form", true);
        form.set("fieldMetadata", fieldMetadata.deepCopy());
    }

    private ObjectNode compileEditorialSnapshotSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String solutionId = text(input, "solutionId");
        if (solutionId.isBlank()) {
            failures.add("editorial-snapshot-set requires solutionId");
            return null;
        }
        ObjectNode solution = objectAt(proposedConfig, "solution", true);
        solution.put("solutionId", solutionId);
        copyIfPresent(input, solution, "journeyId", "activeJourneyId");
        copyIfPresent(input, solution, "stepId", "activeStepId");

        ObjectNode instance = objectAt(proposedConfig, "instance", true);
        copyIfPresent(input, instance, "instanceId");
        if (input.path("instanceContextPatch").isObject()) {
            ObjectNode context = objectAt(instance, "context", true);
            mergeObject(context, input.path("instanceContextPatch"));
        }
        if (input.path("runtimeContextPatch").isObject()) {
            ObjectNode runtimeContext = objectAt(proposedConfig, "runtimeContext", true);
            mergeObject(runtimeContext, input.path("runtimeContextPatch"));
        }
        if (!text(input, "journeyId").isBlank() && editorialJourney(proposedConfig, text(input, "journeyId")) == null) {
            failures.add("editorial-snapshot-set journey not found: " + text(input, "journeyId"));
            return null;
        }
        if (!text(input, "stepId").isBlank()
                && editorialStep(proposedConfig, text(input, "journeyId"), text(input, "stepId")) == null) {
            failures.add("editorial-snapshot-set step not found: " + text(input, "stepId"));
            return null;
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-editorial-snapshot");
        compiled.put("path", "solution");
        compiled.set("solution", solution.deepCopy());
        compiled.set("instance", instance.deepCopy());
        compiled.set("runtimeContext", proposedConfig.path("runtimeContext").deepCopy());
        return compiled;
    }

    private ObjectNode compileEditorialFallbackConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String mode = text(input, "mode");
        if (mode.isBlank()) {
            failures.add("editorial-fallback-configure requires mode");
            return null;
        }
        ObjectNode hostConfig = objectAt(proposedConfig, "hostConfig", true);
        hostConfig.put("emitOperationalEvents", true);
        ArrayNode diagnostics = arrayAt(proposedConfig, "snapshot.diagnostics.items", true);
        ObjectNode diagnostic = objectMapper.createObjectNode();
        diagnostic.put("code", textOrDefault(input, "diagnosticCode", "editorial-fallback-" + mode));
        diagnostic.put("mode", mode);
        copyIfPresent(input, diagnostic, "progressionBlocked");
        copyIfPresent(input, diagnostic, "requiresEngineAttention");
        if (input.path("scope").isObject()) {
            diagnostic.set("scope", input.path("scope").deepCopy());
        }
        diagnostics.add(diagnostic);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-editorial-fallback");
        compiled.put("path", "snapshot.diagnostics.items[]");
        compiled.set("value", diagnostic);
        return compiled;
    }

    private ObjectNode compileEditorialPresentationConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig) {
        ObjectNode presentation = objectAt(proposedConfig, "solution.presentation", true);
        JsonNode previousPresentation = presentation.deepCopy();
        mergeObject(presentation, planOperation.path("input"));

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-editorial-presentation");
        compiled.put("path", "solution.presentation");
        compiled.set("previousPresentation", previousPresentation);
        compiled.set("presentation", presentation.deepCopy());
        return compiled;
    }

    private ObjectNode compileEditorialAdapterBind(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String adapterId = text(input, "adapterId");
        String dataBlockType = text(input, "dataBlockType");
        if (adapterId.isBlank() || dataBlockType.isBlank()) {
            failures.add("editorial-adapter-bind requires adapterId and dataBlockType");
            return null;
        }
        ArrayNode registry = editorialAdapterRegistry(proposedConfig, false);
        ObjectNode registered = findObjectByAnyKey(registry, List.of("adapterId", "id"), adapterId);
        if (registered == null) {
            failures.add("editorial-adapter-bind adapter not registered: " + adapterId);
            return null;
        }
        ObjectNode hostConfig = objectAt(proposedConfig, "hostConfig", true);
        if (input.has("forwardOperationalEvents")) {
            hostConfig.set("forwardAdapterOperationalEvents", input.path("forwardOperationalEvents").deepCopy());
        }
        ArrayNode bindings = arrayAt(proposedConfig, "hostConfig.dataBlockAdapters", true);
        ObjectNode binding = findObjectByAnyKey(bindings, List.of("adapterId", "id"), adapterId);
        if (binding == null) {
            binding = objectMapper.createObjectNode();
            bindings.add(binding);
        }
        binding.put("adapterId", adapterId);
        binding.put("dataBlockType", dataBlockType);
        copyIfPresent(input, binding, "componentRef");
        copyIfPresent(input, binding, "requiredInputs");
        copyIfPresent(input, binding, "fallbackModeWhenMissing");

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "bind-editorial-adapter");
        compiled.put("path", "hostConfig.dataBlockAdapters[]");
        compiled.put("key", "adapterId");
        compiled.put("keyValue", adapterId);
        compiled.set("value", binding.deepCopy());
        return compiled;
    }

    private ObjectNode compileEditorialDataBlockAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String journeyId = text(input, "journeyId");
        String stepId = text(input, "stepId");
        JsonNode blockInput = input.path("block");
        String blockId = text(blockInput, "blockId");
        if (journeyId.isBlank() || stepId.isBlank() || blockId.isBlank()) {
            failures.add("editorial-data-block-add requires journeyId, stepId and block.blockId");
            return null;
        }
        ObjectNode step = editorialStep(proposedConfig, journeyId, stepId);
        if (step == null) {
            failures.add("editorial-data-block-add step not found: " + journeyId + "/" + stepId);
            return null;
        }
        ArrayNode blocks = step.path("blocks") instanceof ArrayNode array ? array : step.putArray("blocks");
        if (findObjectByAnyKey(blocks, List.of("blockId", "id"), blockId) != null) {
            failures.add("editorial-data-block-add duplicate blockId: " + blockId);
            return null;
        }
        ObjectNode block = blockInput.deepCopy();
        int insertedIndex = editorialInsertIndex(blocks, input.path("insert"), failures);
        if (!failures.isEmpty()) {
            return null;
        }
        if (insertedIndex >= blocks.size()) {
            blocks.add(block);
            insertedIndex = blocks.size() - 1;
        } else {
            blocks.insert(insertedIndex, block);
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-editorial-data-block");
        compiled.put("path", "solution.journeys[].steps[].blocks[]");
        compiled.put("key", "blockId");
        compiled.put("keyValue", blockId);
        compiled.put("insertedIndex", insertedIndex);
        compiled.set("value", block.deepCopy());
        return compiled;
    }

    private ObjectNode compileEditorialDataBlockRemove(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String journeyId = text(input, "journeyId");
        String stepId = text(input, "stepId");
        String blockId = text(input, "blockId");
        ObjectNode step = editorialStep(proposedConfig, journeyId, stepId);
        ArrayNode blocks = step == null ? null : step.path("blocks") instanceof ArrayNode array ? array : null;
        int index = indexOfObjectByAnyKey(blocks, List.of("blockId", "id"), blockId);
        if (index < 0) {
            failures.add("editorial-data-block-remove block not found: " + blockId);
            return null;
        }
        JsonNode removed = blocks.remove(index);
        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "remove-editorial-data-block");
        compiled.put("path", "solution.journeys[].steps[].blocks[]");
        compiled.put("key", "blockId");
        compiled.put("keyValue", blockId);
        compiled.put("removedIndex", index);
        compiled.set("removedValue", removed);
        return compiled;
    }

    private ObjectNode compileEditorialFieldBindingSet(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String blockId = text(input, "blockId");
        String fieldName = text(input, "fieldName");
        String contextPath = text(input, "contextPath");
        ObjectNode block = editorialBlockById(proposedConfig, blockId);
        if (block == null) {
            failures.add("editorial-field-binding-set block not found: " + blockId);
            return null;
        }
        if (fieldName.isBlank() || contextPath.isBlank()) {
            failures.add("editorial-field-binding-set requires fieldName and contextPath");
            return null;
        }
        ObjectNode bindings = block.path("fieldBindings") instanceof ObjectNode object ? object : block.putObject("fieldBindings");
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("contextPath", contextPath);
        copyIfPresent(input, binding, "mode");
        copyIfPresent(input, binding, "valueMode");
        copyIfPresent(input, binding, "delegateFieldMetadataTo");
        bindings.set(fieldName, binding);
        ObjectNode formData = objectAt(proposedConfig, "runtimeContext.formData", true);
        if ("replace".equals(text(input, "valueMode"))) {
            setDottedValue(formData, contextPath, MissingNode.getInstance());
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "set-editorial-field-binding");
        compiled.put("path", "solution.journeys[].steps[].blocks[].fieldBindings");
        compiled.put("key", "fieldName");
        compiled.put("keyValue", fieldName);
        compiled.set("value", binding);
        return compiled;
    }

    private ObjectNode editorialJourney(ObjectNode proposedConfig, String journeyId) {
        return findObjectByAnyKey(arrayAt(proposedConfig, "solution.journeys", false), List.of("journeyId", "id"), journeyId);
    }

    private ObjectNode editorialStep(ObjectNode proposedConfig, String journeyId, String stepId) {
        ObjectNode journey = editorialJourney(proposedConfig, journeyId);
        if (journey == null) {
            return null;
        }
        return findObjectByAnyKey(journey.path("steps") instanceof ArrayNode array ? array : null, List.of("stepId", "id"), stepId);
    }

    private ObjectNode editorialBlockById(ObjectNode proposedConfig, String blockId) {
        JsonNode journeys = proposedConfig.path("solution").path("journeys");
        if (!journeys.isArray()) {
            return null;
        }
        for (JsonNode journey : journeys) {
            for (JsonNode step : journey.path("steps")) {
                ObjectNode block = findObjectByAnyKey(step.path("blocks") instanceof ArrayNode array ? array : null,
                        List.of("blockId", "id"),
                        blockId);
                if (block != null) {
                    return block;
                }
            }
        }
        return null;
    }

    private ArrayNode editorialAdapterRegistry(ObjectNode proposedConfig, boolean create) {
        ArrayNode adapters = arrayAt(proposedConfig, "adapterRegistry", create);
        if (adapters != null) {
            return adapters;
        }
        return arrayAt(proposedConfig, "dataBlockAdapters", create);
    }

    private int editorialInsertIndex(ArrayNode blocks, JsonNode insert, List<String> failures) {
        String mode = textOrDefault(insert, "mode", "append");
        String relativeToBlockId = text(insert, "relativeToBlockId");
        if ("append".equals(mode) || mode.isBlank()) {
            return blocks.size();
        }
        int relativeIndex = indexOfObjectByAnyKey(blocks, List.of("blockId", "id"), relativeToBlockId);
        if (relativeIndex < 0) {
            failures.add("editorial-data-block-add relative block not found: " + relativeToBlockId);
            return blocks.size();
        }
        return "insertAfter".equals(mode) ? relativeIndex + 1 : relativeIndex;
    }

    private ObjectNode baseDomainPatch(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            AgenticAuthoringResolvedTarget resolved) {
        ObjectNode compiled = objectMapper.createObjectNode();
        compiled.put("componentId", componentId);
        compiled.put("operationId", text(operation, "operationId"));
        compiled.put("effectKind", "compile-domain-patch");
        compiled.put("domainHandler", text(effect, "handler"));
        if (resolved != null) {
            compiled.put("resolvedPath", resolved.path());
            compiled.set("resolvedValue", resolved.value());
        }
        compiled.set("target", planOperation.path("target"));
        compiled.set("input", planOperation.path("input"));
        compiled.set("affectedPaths", operation.path("affectedPaths"));
        compiled.set("submissionImpact", operation.path("submissionImpact"));
        return compiled;
    }

    private ObjectNode compileChartSeriesAdd(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String field = text(input, "field");
        if (field.isBlank()) {
            failures.add("chart-series-add requires input.field");
            return null;
        }
        ArrayNode metrics = arrayAt(proposedConfig, "chartDocument.metrics", true);
        if (metrics == null) {
            failures.add("chart-series-add path is not an array: chartDocument.metrics[]");
            return null;
        }
        if (indexOfObjectByKey(metrics, "field", field) >= 0) {
            failures.add("chart-series-add duplicate metric field: " + field);
            return null;
        }

        ObjectNode metric = objectMapper.createObjectNode();
        metric.put("field", field);
        copyIfPresent(input, metric, "label");
        copyIfPresent(input, metric, "aggregation");
        copyIfPresent(input, metric, "seriesKind");
        copyIfPresent(input, metric, "axis");
        copyIfPresent(input, metric, "color");
        copyIfPresent(input, metric, "format");

        int insertedIndex = metrics.size();
        String afterField = text(input, "afterField");
        if (!afterField.isBlank()) {
            int afterIndex = indexOfObjectByKey(metrics, "field", afterField);
            if (afterIndex < 0) {
                failures.add("chart-series-add afterField target not found: " + afterField);
                return null;
            }
            insertedIndex = afterIndex + 1;
            metrics.insert(insertedIndex, metric);
        } else {
            metrics.add(metric);
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "add-chart-series");
        compiled.put("path", "chartDocument.metrics[]");
        compiled.put("key", "field");
        compiled.put("keyValue", field);
        compiled.put("insertedIndex", insertedIndex);
        compiled.set("value", metric);
        return compiled;
    }

    private ObjectNode compileChartAxisConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        ObjectNode chartDocument = objectAt(proposedConfig, "chartDocument", true);
        if (chartDocument == null) {
            failures.add("chart-axis-configure chartDocument is not an object");
            return null;
        }

        if (input.has("orientation")) {
            chartDocument.set("orientation", input.path("orientation"));
        }
        String dimensionField = text(input, "dimensionField");
        if (!dimensionField.isBlank()) {
            ArrayNode dimensions = arrayAt(proposedConfig, "chartDocument.dimensions", true);
            ObjectNode dimension = findObjectByKey(dimensions, "field", dimensionField);
            if (dimension == null) {
                dimension = objectMapper.createObjectNode();
                dimension.put("field", dimensionField);
                dimensions.add(dimension);
            }
            copyIfPresent(input, dimension, "dimensionRole", "role");
        }
        String metricField = text(input, "metricField");
        if (!metricField.isBlank()) {
            ArrayNode metrics = arrayAt(proposedConfig, "chartDocument.metrics", true);
            ObjectNode metric = findObjectByKey(metrics, "field", metricField);
            if (metric == null) {
                failures.add("chart-axis-configure metric target not found: " + metricField);
                return null;
            }
            copyIfPresent(input, metric, "metricAxis", "axis");
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "configure-chart-axis");
        compiled.put("path", "chartDocument");
        compiled.set("value", input);
        return compiled;
    }

    private ObjectNode compileChartDataResourceBind(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        String sourceKind = text(input, "sourceKind");
        if (sourceKind.isBlank()) {
            failures.add("chart-data-resource-bind requires input.sourceKind");
            return null;
        }
        ObjectNode chartDocument = objectAt(proposedConfig, "chartDocument", true);
        if (chartDocument == null) {
            failures.add("chart-data-resource-bind chartDocument is not an object");
            return null;
        }
        ObjectNode source = objectMapper.createObjectNode();
        source.put("kind", sourceKind);
        copyIfPresent(input, source, "resource");
        copyIfPresent(input, source, "operation");
        chartDocument.set("source", source);
        if (input.path("dimensions").isArray()) {
            chartDocument.set("dimensions", input.path("dimensions").deepCopy());
        }
        if (input.path("metrics").isArray()) {
            chartDocument.set("metrics", input.path("metrics").deepCopy());
        }

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", "bind-chart-data-resource");
        compiled.put("path", "chartDocument.source");
        compiled.set("value", chartDocument);
        return compiled;
    }

    private ObjectNode compileChartEventConfigure(
            String componentId,
            JsonNode operation,
            JsonNode effect,
            JsonNode planOperation,
            ObjectNode proposedConfig,
            String eventKey,
            String op,
            List<String> failures) {
        JsonNode input = planOperation.path("input");
        if (text(input, "action").isBlank()) {
            failures.add(text(effect, "handler") + " requires input.action");
            return null;
        }
        ObjectNode events = objectAt(proposedConfig, "chartDocument.events", true);
        if (events == null) {
            failures.add(text(effect, "handler") + " chartDocument.events is not an object");
            return null;
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("event", textOrDefault(input, "event", eventKey));
        copyIfPresent(input, event, "action");
        copyIfPresent(input, event, "target");
        copyIfPresent(input, event, "mapping");
        events.set(eventKey, event);

        ObjectNode compiled = baseDomainPatch(componentId, operation, effect, planOperation, null);
        compiled.put("op", op);
        compiled.put("path", "chartDocument.events." + eventKey);
        compiled.set("value", event);
        return compiled;
    }

    private void collapseAllPanelsExcept(ArrayNode panels, String expandedPanelId) {
        for (JsonNode panel : panels) {
            if (panel instanceof ObjectNode object) {
                object.put("expanded", expandedPanelId.equals(text(object, "id")));
            }
        }
    }

    private String firstExpandedPanelId(ArrayNode panels) {
        for (JsonNode panel : panels) {
            if (panel.path("expanded").asBoolean(false)) {
                return text(panel, "id");
            }
        }
        return "";
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

    private void writeCronExpression(ObjectNode proposedConfig, String kind, String cron, String dialect, boolean seconds) {
        ObjectNode schedule = objectAt(proposedConfig, "schedule", true);
        schedule.put("kind", kind);
        ObjectNode expression = objectAt(proposedConfig, "schedule.expression", true);
        expression.put("cron", cron);
        expression.put("dialect", dialect);
        expression.put("seconds", seconds);
        proposedConfig.put("value", cron);
    }

    private String cronFromFrequency(String kind, JsonNode recurrence, List<String> failures) {
        int minute = recurrence.path("minute").asInt(0);
        int hour = recurrence.path("hour").asInt(0);
        return switch (kind) {
            case "daily" -> "%d %d * * *".formatted(minute, hour);
            case "weekly" -> "%d %d * * %s".formatted(minute, hour, textOrDefault(recurrence, "dayOfWeek", "MON"));
            case "monthly" -> "%d %d %d * *".formatted(minute, hour, recurrence.path("dayOfMonth").asInt(1));
            case "interval" -> {
                int everyMinutes = recurrence.path("everyMinutes").asInt(0);
                if (everyMinutes <= 0 || everyMinutes > 1440) {
                    failures.add("cron-frequency-to-expression interval requires everyMinutes between 1 and 1440");
                    yield "";
                }
                yield "*/%d * * * *".formatted(everyMinutes);
            }
            case "customCron" -> text(recurrence, "cron");
            default -> {
                failures.add("cron-frequency-to-expression unsupported kind: " + kind);
                yield "";
            }
        };
    }

    private ObjectNode cronDiagnosticsFromConfig(ObjectNode proposedConfig, ZoneId zone) {
        String cron = currentCron(proposedConfig);
        return cronDiagnostics(cron, springCronExpression(cron, cronFieldCount(cron) == 6), zone.getId(),
                textOrDefault(proposedConfig.path("schedule").path("expression"), "dialect", "unix"));
    }

    private ObjectNode cronDiagnostics(String cron, String springCron, String timezone, String dialect) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("cron", cron);
        diagnostics.put("dialect", dialect);
        diagnostics.put("timezone", timezone);
        ArrayNode errors = diagnostics.putArray("errors");
        if (cron == null || cron.isBlank()) {
            errors.add("cron expression is required");
        } else if (!isValidSpringCron(springCron)) {
            errors.add("cron expression is invalid");
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception ex) {
            errors.add("timezone is invalid");
        }
        diagnostics.put("valid", errors.isEmpty());
        return diagnostics;
    }

    private ArrayNode cronPreviewFromConfig(ObjectNode proposedConfig, ZoneId zone, int occurrences, ZonedDateTime from) {
        ArrayNode preview = objectMapper.createArrayNode();
        String cron = currentCron(proposedConfig);
        String springCron = springCronExpression(cron, cronFieldCount(cron) == 6);
        if (!isValidSpringCron(springCron)) {
            return preview;
        }
        CronExpression expression = CronExpression.parse(springCron);
        ZonedDateTime cursor = from.withZoneSameInstant(zone);
        for (int i = 0; i < occurrences; i++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                break;
            }
            preview.add(next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            cursor = next;
        }
        return preview;
    }

    private ZonedDateTime parsePreviewFrom(String from, ZoneId zone) {
        if (from == null || from.isBlank()) {
            return ZonedDateTime.now(zone);
        }
        try {
            return ZonedDateTime.parse(from).withZoneSameInstant(zone);
        } catch (Exception ex) {
            return ZonedDateTime.now(zone);
        }
    }

    private ZoneId zoneId(String timezone, List<String> failures, String handler) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (Exception ex) {
            failures.add(handler + " invalid timezone: " + timezone);
            return null;
        }
    }

    private JsonNode dialogPresetConfig(ObjectNode proposedConfig, String dialogType) {
        if (dialogType == null || dialogType.isBlank()) {
            return MissingNode.getInstance();
        }
        for (String rootPath : List.of("globalPresets", "dialogPresets", "metadata.globalPresets", "metadata.dialogPresets")) {
            JsonNode preset = resolvePath(proposedConfig, rootPath + "." + dialogType);
            if (preset.isObject()) {
                return preset;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode dialogPresetVariants(ObjectNode proposedConfig) {
        for (String rootPath : List.of("globalPresets.variants", "dialogPresets.variants", "metadata.globalPresets.variants", "metadata.dialogPresets.variants")) {
            JsonNode variants = resolvePath(proposedConfig, rootPath);
            if (variants.isObject()) {
                return variants;
            }
        }
        return MissingNode.getInstance();
    }

    private String timezoneFromConfig(ObjectNode proposedConfig) {
        String timezone = text(proposedConfig.path("schedule"), "timezone");
        if (timezone.isBlank()) {
            timezone = text(proposedConfig.path("metadata"), "timezone");
        }
        return timezone.isBlank() ? "UTC" : timezone;
    }

    private String currentCron(ObjectNode proposedConfig) {
        String cron = text(proposedConfig.path("schedule").path("expression"), "cron");
        return cron.isBlank() ? text(proposedConfig, "value") : cron;
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

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = text(node, field);
        return value.isBlank() ? defaultValue : value;
    }

    private String normalizeControlToken(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    private ObjectNode crudAction(ObjectNode proposedConfig, String actionId, boolean create) {
        ArrayNode actions = arrayAt(proposedConfig, "actions", create);
        if (actions == null || actionId == null || actionId.isBlank()) {
            return null;
        }
        ObjectNode action = findObjectByKey(actions, "id", actionId);
        if (action == null) {
            action = findObjectByKey(actions, "action", actionId);
        }
        if (action == null && create) {
            action = objectMapper.createObjectNode();
            action.put("id", actionId);
            actions.add(action);
        }
        return action;
    }

    private void copyCrudActionFields(JsonNode input, ObjectNode action) {
        copyIfPresent(input, action, "openMode");
        copyIfPresent(input, action, "route");
        copyIfPresent(input, action, "formId");
        copyIfPresent(input, action, "params");
        copyIfPresent(input, action, "back");
        if (input.has("form")) {
            ObjectNode form = action.path("form") instanceof ObjectNode object ? object : action.putObject("form");
            mergeObject(form, input.path("form"));
        }
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        if (source != null && source.has(sourceField)) {
            target.set(targetField, source.path(sourceField).deepCopy());
        }
    }

    private Set<String> textSet(JsonNode array) {
        if (!array.isArray()) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        array.forEach(item -> values.add(item.asText("")));
        return values;
    }

    private boolean isUnsafeFilesBaseUrl(String baseUrl) {
        return baseUrl.startsWith("http://")
                || baseUrl.startsWith("https://")
                || baseUrl.startsWith("//")
                || baseUrl.contains("?")
                || baseUrl.contains("#")
                || !baseUrl.startsWith("/api/")
                || !baseUrl.contains("/files")
                || baseUrl.endsWith("/upload")
                || baseUrl.endsWith("/bulk")
                || baseUrl.endsWith("/presign")
                || baseUrl.contains("/upload/")
                || baseUrl.contains("/bulk/")
                || baseUrl.contains("/presign/");
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

    private void applyMergeByKey(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : "";
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("merge-by-key path is not an array: " + path);
            return;
        }
        ObjectNode target = findObjectByKey(array, key, keyValue);
        if (target == null) {
            failures.add("merge-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        mergeObject(target, input);
        compiled.put("keyValue", keyValue);
        compiled.set("value", input);
    }

    private void applyRemoveByKey(
            JsonNode effect,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : "";
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("remove-by-key path is not an array: " + path);
            return;
        }
        int index = indexOfObjectByKey(array, key, keyValue);
        if (index < 0) {
            failures.add("remove-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        array.remove(index);
        compiled.put("keyValue", keyValue);
        compiled.put("removedIndex", index);
    }

    private void applySetValue(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String tail = tailAfterArray(path);
        JsonNode value = valueForSetEffect(effect, input, tail);
        if (resolved != null && !tail.isBlank()) {
            JsonNode targetNode = nodeAtResolvedPath(proposedConfig, resolved.path());
            if (targetNode instanceof ObjectNode targetObject) {
                setDottedValue(targetObject, tail, value);
                compiled.put("resolvedPath", resolved.path() + "." + tail);
            } else {
                failures.add("set-value resolved target is not an object: " + resolved.path());
                return;
            }
        } else {
            setDottedValue(proposedConfig, normalizeDottedPath(path), value);
        }
        compiled.set("value", value);
    }

    private void applyMergeObject(
            JsonNode effect,
            JsonNode input,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = normalizeDottedPath(text(effect, "path"));
        JsonNode target = objectAt(proposedConfig, path, true);
        if (target instanceof ObjectNode targetObject) {
            mergeObject(targetObject, input);
            compiled.set("value", input);
        } else {
            failures.add("merge-object path is not an object: " + path);
        }
    }

    private void applyReorderByKey(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String path = text(effect, "path");
        String key = text(effect, "key");
        String keyValue = resolved != null ? text(resolved.value(), key) : text(input, key);
        if (key.isBlank()) {
            failures.add("reorder-by-key requires key for path: " + path);
            return;
        }
        if (keyValue.isBlank()) {
            failures.add("reorder-by-key target key is missing for path: " + path);
            return;
        }
        ArrayNode array = arrayAt(proposedConfig, collectionPath(path), false);
        if (array == null) {
            failures.add("reorder-by-key path is not an array: " + path);
            return;
        }
        int fromIndex = indexOfObjectByKey(array, key, keyValue);
        if (fromIndex < 0) {
            failures.add("reorder-by-key target not found: " + path + " " + key + "=" + keyValue);
            return;
        }
        JsonNode item = array.remove(fromIndex);
        int toIndex = resolveReorderIndex(array, input, key, path, failures);
        if (toIndex < 0) {
            array.insert(fromIndex, item);
            return;
        }
        array.insert(toIndex, item);
        compiled.put("keyValue", keyValue);
        compiled.put("fromIndex", fromIndex);
        compiled.put("toIndex", toIndex);
    }

    private void applyAppend(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        ArrayNode array = arrayForAppend(effect, resolved, proposedConfig, failures);
        if (array == null) {
            return;
        }
        array.add(input);
        compiled.set("value", input);
        compiled.put("appendedIndex", array.size() - 1);
    }

    private void applyAppendUnique(
            JsonNode effect,
            JsonNode input,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            ObjectNode compiled,
            List<String> failures) {
        String key = text(effect, "key");
        if (key.isBlank()) {
            failures.add("append-unique requires key for path: " + text(effect, "path"));
            return;
        }
        String keyValue = text(input, key);
        if (keyValue.isBlank()) {
            failures.add("append-unique input is missing key " + key + " for path: " + text(effect, "path"));
            return;
        }
        ArrayNode array = arrayForAppend(effect, resolved, proposedConfig, failures);
        if (array == null) {
            return;
        }
        int existingIndex = indexOfObjectByKey(array, key, keyValue);
        if (existingIndex >= 0) {
            failures.add("append-unique duplicate value for " + text(effect, "path") + " " + key + "=" + keyValue);
            return;
        }
        array.add(input);
        compiled.put("keyValue", keyValue);
        compiled.set("value", input);
        compiled.put("appendedIndex", array.size() - 1);
    }

    private ArrayNode arrayForAppend(
            JsonNode effect,
            AgenticAuthoringResolvedTarget resolved,
            ObjectNode proposedConfig,
            List<String> failures) {
        String path = text(effect, "path");
        ObjectNode root = proposedConfig;
        String arrayPath = collectionPath(path);
        if (resolved != null && path.contains("[].")) {
            JsonNode targetNode = nodeAtResolvedPath(proposedConfig, resolved.path());
            if (!(targetNode instanceof ObjectNode targetObject)) {
                failures.add("append resolved target is not an object: " + resolved.path());
                return null;
            }
            root = targetObject;
            arrayPath = collectionPath(tailAfterLastArray(path));
        }
        ArrayNode array = arrayAt(root, arrayPath, true);
        if (array == null) {
            failures.add("append path is not an array: " + path);
        }
        return array;
    }

    private JsonNode valueForSetEffect(JsonNode effect, JsonNode input, String tail) {
        String inputPath = text(effect, "inputPath");
        if (!inputPath.isBlank()) {
            JsonNode value = resolvePath(input, inputPath);
            return value.isMissingNode() ? MissingNode.getInstance() : value;
        }
        if (!tail.isBlank() && input != null && input.has(tail)) {
            return input.path(tail);
        }
        return input == null ? MissingNode.getInstance() : input;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source != null && source.has(field)) {
            target.set(field, source.path(field));
        }
    }

    private void mergeObject(ObjectNode target, JsonNode input) {
        if (input == null || !input.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            target.set(field.getKey(), field.getValue());
        }
    }

    private ObjectNode findObjectByKey(ArrayNode array, String key, String keyValue) {
        int index = indexOfObjectByKey(array, key, keyValue);
        return index >= 0 && array.get(index) instanceof ObjectNode object ? object : null;
    }

    private int indexOfObjectByKey(ArrayNode array, String key, String keyValue) {
        if (array == null || key == null || key.isBlank()) {
            return -1;
        }
        for (int i = 0; i < array.size(); i++) {
            if (keyValue.equals(text(array.get(i), key))) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfResolvedArrayTarget(AgenticAuthoringResolvedTarget resolved) {
        if (resolved == null || resolved.path() == null || resolved.path().isBlank()) {
            return -1;
        }
        int slash = resolved.path().lastIndexOf('/');
        if (slash < 0 || slash == resolved.path().length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(resolved.path().substring(slash + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String nextRichContentNodeId(ArrayNode nodes, String type) {
        String normalizedType = type == null || type.isBlank()
                ? "block"
                : type.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase();
        for (int i = nodes.size() + 1; ; i++) {
            String candidate = normalizedType + "-" + i;
            if (indexOfObjectByKey(nodes, "id", candidate) < 0) {
                return candidate;
            }
        }
    }

    private String nextRichTimelineItemId(ArrayNode items) {
        for (int i = items.size() + 1; ; i++) {
            String candidate = "timeline-item-" + i;
            if (indexOfObjectByKey(items, "id", candidate) < 0) {
                return candidate;
            }
        }
    }

    private String nextStepperValidationRuleId(ArrayNode rules, String stepId, String fieldName) {
        String suffix = fieldName == null || fieldName.isBlank() ? stepId : fieldName;
        String normalized = suffix == null || suffix.isBlank()
                ? "rule"
                : suffix.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
        String base = "validation-" + (normalized.isBlank() ? "rule" : normalized);
        String candidate = base;
        for (int i = 2; indexOfObjectByKey(rules, "id", candidate) >= 0; i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private String timelineBlockPathFromItemPath(String itemPath) {
        int marker = itemPath == null ? -1 : itemPath.lastIndexOf(".items[]/");
        return marker < 0 ? "" : itemPath.substring(0, marker);
    }

    private boolean validatePresetRef(ObjectNode ref, List<String> failures) {
        String kind = text(ref, "kind");
        String namespace = text(ref, "namespace");
        String presetId = text(ref, "presetId");
        if (!"rich-block".equals(kind)) {
            failures.add("rich-content-preset-apply ref.kind must be rich-block");
        }
        if (namespace.isBlank()) {
            failures.add("rich-content-preset-apply ref.namespace is required");
        }
        if (presetId.isBlank()) {
            failures.add("rich-content-preset-apply ref.presetId is required");
        }
        return failures.stream().noneMatch(failure -> failure.startsWith("rich-content-preset-apply"));
    }

    private boolean isSerializablePresetInputs(JsonNode inputs) {
        if (inputs == null || inputs.isMissingNode()) {
            return true;
        }
        if (inputs.isValueNode() || inputs.isArray()) {
            for (JsonNode child : inputs) {
                if (!isSerializablePresetInputs(child)) {
                    return false;
                }
            }
            return true;
        }
        if (inputs.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = inputs.fields();
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

    private String presetNodeId(ObjectNode ref, ArrayNode nodes) {
        String presetId = text(ref, "presetId");
        String normalized = presetId.isBlank()
                ? "preset"
                : presetId.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
        String candidateBase = normalized.isBlank() ? "preset" : normalized;
        String candidate = candidateBase;
        for (int suffix = 2; indexOfObjectByKey(nodes, "id", candidate) >= 0; suffix++) {
            candidate = candidateBase + "-" + suffix;
        }
        return candidate;
    }

    private Set<String> safeRichContentUrlProtocols() {
        return Set.of("http", "https", "mailto", "tel");
    }

    private Set<String> timelineItemFields() {
        return Set.of(
                "title",
                "titleExpr",
                "subtitle",
                "subtitleExpr",
                "meta",
                "metaExpr",
                "icon",
                "iconExpr",
                "badge",
                "badgeExpr");
    }

    private int resolveReorderIndex(
            ArrayNode array,
            JsonNode input,
            String key,
            String path,
            List<String> failures) {
        String beforeKeyValue = firstText(input, "beforeId", "beforeKey", "beforePanelId", "beforeBlockId");
        if (!beforeKeyValue.isBlank()) {
            int beforeIndex = indexOfObjectByKey(array, key, beforeKeyValue);
            if (beforeIndex < 0) {
                failures.add("reorder-by-key before target not found: " + path + " " + key + "=" + beforeKeyValue);
                return -1;
            }
            return beforeIndex;
        }
        String afterKeyValue = firstText(input, "afterId", "afterKey", "afterPanelId", "afterBlockId");
        if (!afterKeyValue.isBlank()) {
            int afterIndex = indexOfObjectByKey(array, key, afterKeyValue);
            if (afterIndex < 0) {
                failures.add("reorder-by-key after target not found: " + path + " " + key + "=" + afterKeyValue);
                return -1;
            }
            return afterIndex + 1;
        }
        if (input != null && input.has("index") && input.path("index").canConvertToInt()) {
            int index = input.path("index").asInt();
            if (index < 0 || index > array.size()) {
                failures.add("reorder-by-key index is out of bounds: " + index + " for " + path);
                return -1;
            }
            return index;
        }
        failures.add("reorder-by-key requires beforeId, afterId or index for path: " + path);
        return -1;
    }

    private ObjectNode formColumnFromResolved(ObjectNode proposedConfig, AgenticAuthoringResolvedTarget resolved) {
        if (resolved == null || resolved.path() == null || resolved.path().isBlank()) {
            return null;
        }
        JsonNode node = nodeAtResolvedPath(proposedConfig, resolved.path());
        return node instanceof ObjectNode object ? object : null;
    }

    private ObjectNode formColumnByIds(ObjectNode proposedConfig, String sectionId, String rowId, String columnId) {
        if (sectionId.isBlank() || rowId.isBlank() || columnId.isBlank()) {
            return null;
        }
        JsonNode sections = proposedConfig.path("sections");
        if (!sections.isArray()) {
            return null;
        }
        for (JsonNode section : sections) {
            if (!sectionId.equals(text(section, "id"))) {
                continue;
            }
            for (JsonNode row : section.path("rows")) {
                if (!rowId.equals(text(row, "id"))) {
                    continue;
                }
                for (JsonNode column : row.path("columns")) {
                    if (column instanceof ObjectNode object && columnId.equals(text(column, "id"))) {
                        return object;
                    }
                }
            }
        }
        return null;
    }

    private ArrayNode formColumnItems(ObjectNode column, boolean create) {
        JsonNode items = column.path("items");
        if (items instanceof ArrayNode array) {
            return array;
        }
        return create ? column.putArray("items") : null;
    }

    private ArrayNode formColumnFields(ObjectNode column, boolean create) {
        JsonNode fields = column.path("fields");
        if (fields instanceof ArrayNode array) {
            return array;
        }
        return create ? column.putArray("fields") : null;
    }

    private int removeFormFieldLayoutRefs(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        int removed = 0;
        if (node instanceof ObjectNode object) {
            JsonNode fields = object.path("fields");
            if (fields instanceof ArrayNode fieldArray) {
                for (int i = fieldArray.size() - 1; i >= 0; i--) {
                    if (fieldName.equals(fieldArray.get(i).asText(""))) {
                        fieldArray.remove(i);
                        removed++;
                    }
                }
            }
            JsonNode items = object.path("items");
            if (items instanceof ArrayNode itemArray) {
                for (int i = itemArray.size() - 1; i >= 0; i--) {
                    JsonNode item = itemArray.get(i);
                    if (isFormFieldLayoutItem(item, fieldName)) {
                        itemArray.remove(i);
                        removed++;
                    } else {
                        removed += removeFormFieldLayoutRefs(item, fieldName);
                    }
                }
            }
            Iterator<JsonNode> children = object.elements();
            while (children.hasNext()) {
                JsonNode child = children.next();
                if (child != fields && child != items) {
                    removed += removeFormFieldLayoutRefs(child, fieldName);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                removed += removeFormFieldLayoutRefs(child, fieldName);
            }
        }
        return removed;
    }

    private boolean isFormFieldLayoutItem(JsonNode item, String fieldName) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return false;
        }
        if (item.isTextual()) {
            return fieldName.equals(item.asText(""));
        }
        if (!item.isObject()) {
            return false;
        }
        String type = firstNonBlank(text(item, "type"), text(item, "kind"));
        return ("field".equals(type) || type.isBlank())
                && fieldName.equals(firstNonBlank(text(item, "field"), text(item, "fieldName"), text(item, "name")));
    }

    private ArrayNode formItemsArrayFromResolvedPath(ObjectNode proposedConfig, String resolvedPath) {
        int marker = resolvedPath == null ? -1 : resolvedPath.lastIndexOf(".items[]/");
        if (marker < 0) {
            return null;
        }
        String columnPath = resolvedPath.substring(0, marker);
        JsonNode column = nodeAtResolvedPath(proposedConfig, columnPath);
        if (column instanceof ObjectNode object) {
            return formColumnItems(object, false);
        }
        return null;
    }

    private int indexFromResolvedArrayPath(String resolvedPath) {
        int marker = resolvedPath == null ? -1 : resolvedPath.lastIndexOf("[]/");
        if (marker < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(resolvedPath.substring(marker + 3));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int boundedInsertIndex(JsonNode input, int size, List<String> failures, String handler) {
        if (!input.has("index")) {
            return size;
        }
        if (!input.path("index").canConvertToInt()) {
            failures.add(handler + " index must be an integer");
            return -1;
        }
        int index = input.path("index").asInt();
        if (index < 0 || index > size) {
            failures.add(handler + " index is out of bounds: " + index);
            return -1;
        }
        return index;
    }

    private int boundedRawInsertIndex(JsonNode indexNode, int size, List<String> failures, String handler) {
        if (!indexNode.canConvertToInt()) {
            failures.add(handler + " insertIndex must be an integer");
            return -1;
        }
        int index = indexNode.asInt();
        if (index < 0 || index > size) {
            failures.add(handler + " insertIndex is out of bounds: " + index);
            return -1;
        }
        return index;
    }

    private ArrayNode visualNodes(ObjectNode proposedConfig, boolean create) {
        return arrayAt(proposedConfig, "nodes", create);
    }

    private ArrayNode visualRootNodes(ObjectNode proposedConfig, boolean create) {
        return arrayAt(proposedConfig, "rootNodes", create);
    }

    private ArrayNode visualChildren(ObjectNode node, boolean create) {
        JsonNode children = node.path("children");
        if (children instanceof ArrayNode array) {
            return array;
        }
        return create ? node.putArray("children") : null;
    }

    private ObjectNode visualNodeById(ObjectNode proposedConfig, String nodeId) {
        ArrayNode nodes = visualNodes(proposedConfig, false);
        return nodes == null ? null : findObjectByAnyKey(nodes, List.of("id", "nodeId"), nodeId);
    }

    private ObjectNode findVisualVariable(ArrayNode variables, String name, String scope) {
        if (variables == null) {
            return null;
        }
        for (JsonNode variable : variables) {
            if (variable instanceof ObjectNode object
                    && name.equals(text(object, "name"))
                    && scope.equals(text(object, "scope"))) {
                return object;
            }
        }
        return null;
    }

    private boolean removeTextValue(ArrayNode array, String value) {
        if (array == null) {
            return false;
        }
        boolean removed = false;
        for (int i = array.size() - 1; i >= 0; i--) {
            if (value.equals(array.get(i).asText(""))) {
                array.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    private void addTextUnique(ArrayNode array, String value) {
        if (array == null || value == null || value.isBlank()) {
            return;
        }
        for (JsonNode item : array) {
            if (value.equals(item.asText(""))) {
                return;
            }
        }
        array.add(value);
    }

    private void removeVisualEdgesTo(ObjectNode proposedConfig, String nodeId) {
        ArrayNode nodes = visualNodes(proposedConfig, false);
        if (nodes == null) {
            return;
        }
        for (JsonNode node : nodes) {
            if (node instanceof ObjectNode object) {
                removeTextValue(visualChildren(object, false), nodeId);
            }
        }
    }

    private void removeVisualDescendants(ObjectNode proposedConfig, String nodeId) {
        ObjectNode node = visualNodeById(proposedConfig, nodeId);
        if (node == null) {
            return;
        }
        for (JsonNode childId : node.path("children")) {
            String child = childId.asText("");
            if (!child.isBlank()) {
                removeVisualDescendants(proposedConfig, child);
                ArrayNode nodes = visualNodes(proposedConfig, false);
                int childIndex = indexOfObjectByAnyKey(nodes, List.of("id", "nodeId"), child);
                if (childIndex >= 0) {
                    nodes.remove(childIndex);
                }
                removeTextValue(visualRootNodes(proposedConfig, false), child);
            }
        }
    }

    private boolean visualNodeReachable(ObjectNode proposedConfig, String sourceNodeId, String targetNodeId) {
        return visualNodeReachable(proposedConfig, sourceNodeId, targetNodeId, new HashSet<>());
    }

    private boolean visualNodeReachable(ObjectNode proposedConfig, String sourceNodeId, String targetNodeId, Set<String> visited) {
        if (sourceNodeId == null || sourceNodeId.isBlank() || !visited.add(sourceNodeId)) {
            return false;
        }
        if (sourceNodeId.equals(targetNodeId)) {
            return true;
        }
        ObjectNode source = visualNodeById(proposedConfig, sourceNodeId);
        if (source == null) {
            return false;
        }
        for (JsonNode child : source.path("children")) {
            if (visualNodeReachable(proposedConfig, child.asText(""), targetNodeId, visited)) {
                return true;
            }
        }
        return false;
    }

    private void appendVisualGraphValidationErrors(ObjectNode proposedConfig, ArrayNode validationErrors) {
        ArrayNode nodes = visualNodes(proposedConfig, false);
        if (nodes == null) {
            validationErrors.add("nodes array is missing");
            return;
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes) {
            String nodeId = firstNonBlank(text(node, "id"), text(node, "nodeId"));
            if (nodeId.isBlank()) {
                validationErrors.add("node id is missing");
            } else if (!ids.add(nodeId)) {
                validationErrors.add("duplicate node id: " + nodeId);
            }
        }
        for (JsonNode node : nodes) {
            String sourceId = firstNonBlank(text(node, "id"), text(node, "nodeId"));
            for (JsonNode child : node.path("children")) {
                String childId = child.asText("");
                ObjectNode childNode = visualNodeById(proposedConfig, childId);
                if (childNode == null) {
                    validationErrors.add("edge references missing node: " + sourceId + " -> " + childId);
                } else if (!text(childNode, "parentId").isBlank() && !sourceId.equals(text(childNode, "parentId"))) {
                    validationErrors.add("child parentId mismatch: " + childId);
                }
            }
            if (visualNodeHasCycle(proposedConfig, sourceId, new HashSet<>())) {
                validationErrors.add("cycle detected at node: " + sourceId);
            }
        }
    }

    private boolean visualNodeHasCycle(ObjectNode proposedConfig, String sourceNodeId, Set<String> visited) {
        if (sourceNodeId == null || sourceNodeId.isBlank() || !visited.add(sourceNodeId)) {
            return false;
        }
        ObjectNode source = visualNodeById(proposedConfig, sourceNodeId);
        if (source == null) {
            return false;
        }
        for (JsonNode child : source.path("children")) {
            String childId = child.asText("");
            if (sourceNodeId.equals(childId) || visualNodeReachable(proposedConfig, childId, sourceNodeId, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private void writeVisualCurrentJson(ObjectNode proposedConfig) {
        ObjectNode currentJson = objectMapper.createObjectNode();
        currentJson.put("kind", "praxis.visual-builder.dsl");
        currentJson.put("version", "1.0.0");
        currentJson.set("nodes", proposedConfig.path("nodes").deepCopy());
        currentJson.set("rootNodes", proposedConfig.path("rootNodes").deepCopy());
        currentJson.set("contextVariables", proposedConfig.path("contextVariables").deepCopy());
        proposedConfig.set("currentJSON", currentJson);
    }

    private ArrayNode tableRuleRules(ObjectNode proposedConfig, boolean create) {
        return arrayAt(proposedConfig, "ruleEffects.rules", create);
    }

    private ObjectNode tableRuleByInput(ObjectNode proposedConfig, JsonNode input) {
        String ruleId = text(input, "ruleId");
        ArrayNode rules = tableRuleRules(proposedConfig, false);
        return rules == null ? null : findObjectByAnyKey(rules, List.of("ruleId", "id"), ruleId);
    }

    private ArrayNode tableRuleEffects(ObjectNode rule, boolean create) {
        JsonNode effects = rule.path("effects");
        if (effects instanceof ArrayNode array) {
            return array;
        }
        return create ? rule.putArray("effects") : null;
    }

    private ObjectNode tableRuleEffectByInput(ObjectNode proposedConfig, JsonNode input) {
        ObjectNode rule = tableRuleByInput(proposedConfig, input);
        if (rule == null) {
            return null;
        }
        ArrayNode effects = tableRuleEffects(rule, false);
        return effects == null ? null : findObjectByAnyKey(effects, List.of("effectId", "id"), text(input, "effectId"));
    }

    private ObjectNode firstTableRuleEffect(ObjectNode rule) {
        if (rule == null) {
            return null;
        }
        ArrayNode effects = tableRuleEffects(rule, false);
        if (effects == null || effects.isEmpty() || !(effects.get(0) instanceof ObjectNode object)) {
            return null;
        }
        return object;
    }

    private ObjectNode appendTableRuleDelegation(ObjectNode proposedConfig, String operationId, JsonNode input, String identity) {
        ArrayNode delegated = arrayAt(proposedConfig, "delegatedAuthoringOperations", true);
        ObjectNode delegation = objectMapper.createObjectNode();
        delegation.put("componentId", "praxis-table");
        delegation.put("operationId", operationId);
        delegation.put("identity", identity);
        delegation.set("input", input.deepCopy());
        delegated.add(delegation);
        return delegation;
    }

    private ObjectNode tableRulePresetEffect(String presetKey) {
        ObjectNode effect = objectMapper.createObjectNode();
        effect.put("effectId", "preset-" + presetKey);
        effect.put("id", "preset-" + presetKey);
        effect.put("effectType", switch (presetKey) {
            case "aprovado" -> "estilo";
            case "alerta", "erro" -> "fundo";
            default -> "tooltip";
        });
        ObjectNode payload = effect.putObject("payload");
        payload.put("presetKey", presetKey);
        payload.put("semantic", true);
        return effect;
    }

    private Set<String> tableRulePresetKeys() {
        return Set.of("aprovado", "alerta", "erro", "info");
    }

    private ObjectNode findObjectByAnyKey(ArrayNode array, List<String> keys, String value) {
        if (array == null) {
            return null;
        }
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) {
                continue;
            }
            for (String key : keys) {
                if (value.equals(text(object, key))) {
                    return object;
                }
            }
        }
        return null;
    }

    private int indexOfObjectByAnyKey(ArrayNode array, List<String> keys, String value) {
        if (array == null) {
            return -1;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            for (String key : keys) {
                if (value.equals(text(item, key))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private ObjectNode objectAt(ObjectNode root, String dottedPath, boolean create) {
        JsonNode node = nodeAt(root, dottedPath, create, true);
        return node instanceof ObjectNode object ? object : null;
    }

    private ArrayNode arrayAt(ObjectNode root, String dottedPath, boolean create) {
        JsonNode node = nodeAt(root, dottedPath, create, false);
        return node instanceof ArrayNode array ? array : null;
    }

    private JsonNode nodeAt(ObjectNode root, String dottedPath, boolean create, boolean objectLeaf) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return root;
        }
        ObjectNode current = root;
        String[] segments = dottedPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            String segment = cleanPathSegment(segments[i]);
            boolean last = i == segments.length - 1;
            JsonNode child = current.path(segment);
            if (last) {
                if ((child == null || child.isMissingNode()) && create) {
                    child = objectLeaf ? current.putObject(segment) : current.putArray(segment);
                }
                return child;
            }
            ObjectNode childObject;
            if (child instanceof ObjectNode object) {
                childObject = object;
            } else {
                if (!create) {
                    return MissingNode.getInstance();
                }
                childObject = current.putObject(segment);
            }
            current = childObject;
        }
        return current;
    }

    private void setDottedValue(ObjectNode root, String dottedPath, JsonNode value) {
        String normalized = normalizeDottedPath(dottedPath);
        int lastDot = normalized.lastIndexOf('.');
        ObjectNode parent = lastDot < 0 ? root : objectAt(root, normalized.substring(0, lastDot), true);
        String field = lastDot < 0 ? normalized : normalized.substring(lastDot + 1);
        parent.set(cleanPathSegment(field), value);
    }

    private JsonNode nodeAtResolvedPath(ObjectNode root, String resolvedPath) {
        JsonNode current = root;
        if (resolvedPath == null || resolvedPath.isBlank()) {
            return current;
        }
        for (String segment : resolvedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            int arrayMarker = segment.indexOf("[]/");
            if (arrayMarker >= 0) {
                String arrayName = segment.substring(0, arrayMarker);
                int index = Integer.parseInt(segment.substring(arrayMarker + 3));
                current = current.path(arrayName);
                if (!current.isArray() || index < 0 || index >= current.size()) {
                    return MissingNode.getInstance();
                }
                current = current.get(index);
            } else {
                current = current.path(segment);
            }
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private JsonNode resolvePath(JsonNode root, String dottedPath) {
        JsonNode current = root;
        if (dottedPath == null || dottedPath.isBlank()) {
            return current;
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

    private String collectionPath(String path) {
        return normalizeDottedPath(path).replace("[]", "");
    }

    private String normalizeDottedPath(String path) {
        return path == null ? "" : path.replace("[]", "");
    }

    private String cleanPathSegment(String segment) {
        return segment == null ? "" : segment.replace("[]", "");
    }

    private String tailAfterArray(String path) {
        int marker = path == null ? -1 : path.indexOf("[].");
        return marker < 0 ? "" : path.substring(marker + 3);
    }

    private String tailAfterLastArray(String path) {
        int marker = path == null ? -1 : path.lastIndexOf("[].");
        return marker < 0 ? path : path.substring(marker + 3);
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String targetText(JsonNode target) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            return "";
        }
        if (target.isTextual()) {
            return target.asText("");
        }
        return firstNonBlank(text(target, "name"), text(target, "field"), text(target, "id"), text(target, "value"), text(target, "label"));
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return MissingNode.getInstance();
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return MissingNode.getInstance();
    }
}
