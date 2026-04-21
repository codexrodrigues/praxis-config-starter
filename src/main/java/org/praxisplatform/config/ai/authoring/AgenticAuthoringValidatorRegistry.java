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
            "sanitization-policy-explicit",
            "unsafe-html-rejected",
            "unsafe-url-rejected",
            "security-change-confirmed",
            "preset-ref-valid",
            "host-capabilities-serializable",
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
            "conditional-style-valid");

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
                case "preset-exists" -> validateCronPresetExists(operationId, planOperation, config, failures);
                case "sanitization-policy-explicit" -> validateSanitizationPolicyExplicit(operationId, planOperation, failures);
                case "unsafe-html-rejected" -> validateUnsafeHtmlRejected(operationId, planOperation, failures);
                case "unsafe-url-rejected" -> validateUnsafeUrlRejected(operationId, planOperation, failures);
                case "security-change-confirmed" -> validateSecurityChangeConfirmed(operationId, planOperation, failures);
                case "preset-ref-valid" -> validatePresetRef(operationId, planOperation, failures);
                case "host-capabilities-serializable" -> validatePresetInputsSerializable(operationId, planOperation, failures);
                case "json-logic-valid", "logic-valid", "conditional-style-valid" ->
                        validateJsonLogicLikeInput(operationId, planOperation.path("input"), failures);
                case "grouping-fields-exist", "filter-fields-exist" ->
                        validateInputFieldsExist(operationId, planOperation.path("input"), config, failures);
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

    private String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
