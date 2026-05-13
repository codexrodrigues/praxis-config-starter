package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

public class AgenticAuthoringMinimalFormPlanValidator {

    static final String PROFILE_CREATE_MINIMAL_FORM = "create-minimal-form";
    static final String TARGET_COMPONENT_PAGE_BUILDER = "praxis-dynamic-page-builder";

    public List<String> validate(JsonNode plan) {
        return validate(plan, null);
    }

    public List<String> validate(JsonNode plan, AgenticAuthoringIntentResolutionResult intentResolution) {
        if (plan == null || plan.isNull()) {
            return List.of("minimalFormPlan is required");
        }
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return validateUngroundedPlan(plan);
        }
        return validateIntentBackedPlan(plan, intentResolution);
    }

    private List<String> validateUngroundedPlan(JsonNode plan) {
        List<String> failures = new ArrayList<>();
        requireText(plan, "version", "1.0.0", failures);
        requireText(plan, "profileId", PROFILE_CREATE_MINIMAL_FORM, failures);
        JsonNode clarification = plan.path("clarificationNeed");
        if (!clarification.isObject() || !clarification.path("needed").asBoolean(false)) {
            failures.add("clarificationNeed.needed must be true when no intent-backed resource candidate is available");
        } else if (text(clarification, "code").isBlank() || "none".equals(text(clarification, "code"))) {
            failures.add("clarificationNeed.code must explain the missing grounding");
        }
        if (!text(plan, "submitActionRef").isBlank()) {
            failures.add("submitActionRef must be empty without an intent-backed resource candidate");
        }
        return List.copyOf(failures);
    }

    private List<String> validateIntentBackedPlan(JsonNode plan, AgenticAuthoringIntentResolutionResult intentResolution) {
        List<String> failures = new ArrayList<>();
        requireText(plan, "version", "1.0.0", failures);
        requireText(plan, "profileId", PROFILE_CREATE_MINIMAL_FORM, failures);
        requireText(plan, "targetApp", intentResolution.targetApp(), failures);
        requireText(plan, "targetComponentId", intentResolution.targetComponentId(), failures);
        AgenticAuthoringCandidate candidate = intentResolution.selectedCandidate();
        requireText(plan, "submitActionRef", submitActionRef(candidate), failures);
        if (text(plan, "apiUseCaseResolutionRef").isBlank()) {
            failures.add("apiUseCaseResolutionRef is required");
        }
        if (text(plan, "fieldSelectionPlanRef").isBlank()) {
            failures.add("fieldSelectionPlanRef is required");
        }
        JsonNode fields = plan.path("fields");
        if (!fields.isArray() || fields.isEmpty()) {
            failures.add("fields must not be empty");
        } else {
            for (JsonNode field : fields) {
                String name = text(field, "name");
                if (name.isBlank()) {
                    failures.add("field name is required");
                }
                String label = text(field, "label");
                if (label.isBlank()) {
                    failures.add("field label is required: " + name);
                }
                String controlType = text(field, "controlType");
                if (controlType.isBlank()) {
                    failures.add("field controlType is required: " + name);
                }
                validateCurrentPageFieldScope(name, intentResolution, failures);
            }
        }
        JsonNode clarification = plan.path("clarificationNeed");
        if (clarification.isObject() && clarification.path("needed").asBoolean(false)
                && "none".equals(text(clarification, "code"))) {
            failures.add("clarificationNeed.code must explain why clarification is needed");
        }
        if (!plan.path("sourceRefs").isArray() || plan.path("sourceRefs").isEmpty()) {
            failures.add("sourceRefs must not be empty");
        }
        return List.copyOf(failures);
    }

    private void validateCurrentPageFieldScope(
            String fieldName,
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> failures) {
        if (fieldName.isBlank()) {
            return;
        }
        JsonNode formSummary = targetFormSummary(intentResolution);
        String operationKind = intentResolution.operationKind();
        String changeKind = intentResolution.changeKind();
        if ("modify".equals(operationKind) && "add_field".equals(changeKind)
                && containsText(formSummary.path("fieldNames"), fieldName)) {
            failures.add("add_field duplicates existing field: " + fieldName);
        }
        if ("remove".equals(operationKind) && "remove_field".equals(changeKind)
                && !containsText(formSummary.path("localFieldNames"), fieldName)) {
            failures.add("remove_field requires current local/transient field: " + fieldName);
        }
    }

    private JsonNode targetFormSummary(AgenticAuthoringIntentResolutionResult intentResolution) {
        JsonNode currentPageSummary = intentResolution.currentPageSummary();
        if (currentPageSummary == null) {
            return MissingNode.getInstance();
        }
        JsonNode structuralSummary = structuralTargetFormSummary(currentPageSummary, intentResolution.target());
        if (structuralSummary.isObject() && structuralSummary.path("fieldNames").isArray()) {
            return structuralSummary;
        }
        JsonNode formWidgets = currentPageSummary.path("formWidgets");
        if (!formWidgets.isArray() || formWidgets.isEmpty()) {
            return MissingNode.getInstance();
        }
        AgenticAuthoringTarget target = intentResolution.target();
        if (target != null && target.widgetKey() != null && !target.widgetKey().isBlank()) {
            for (JsonNode formWidget : formWidgets) {
                if (target.widgetKey().equals(text(formWidget, "widgetKey"))) {
                    return formWidget;
                }
            }
        }
        return formWidgets.get(0);
    }

    private JsonNode structuralTargetFormSummary(JsonNode currentPageSummary, AgenticAuthoringTarget target) {
        JsonNode inspection = currentPageSummary.path("structuralInspection");
        JsonNode widgets = inspection.path("widgets");
        JsonNode fields = inspection.path("fields");
        if (!widgets.isArray() || widgets.isEmpty() || !fields.isArray()) {
            return MissingNode.getInstance();
        }
        String selectedWidgetKey = target == null ? "" : textValue(target.widgetKey());
        if (selectedWidgetKey.isBlank()) {
            for (JsonNode widget : widgets) {
                if ("form".equals(text(widget, "artifactKind"))) {
                    selectedWidgetKey = text(widget, "widgetKey");
                    break;
                }
            }
        }
        if (selectedWidgetKey.isBlank()) {
            return MissingNode.getInstance();
        }
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("widgetKey", selectedWidgetKey);
        ArrayNode fieldNames = summary.putArray("fieldNames");
        ArrayNode localFieldNames = summary.putArray("localFieldNames");
        ArrayNode serverBackedOverrideNames = summary.putArray("serverBackedOverrideNames");
        for (JsonNode field : fields) {
            if (!selectedWidgetKey.equals(text(field, "widgetKey"))) {
                continue;
            }
            String name = text(field, "name");
            if (name.isBlank()) {
                continue;
            }
            fieldNames.add(name);
            if ("transient".equals(text(field, "binding"))) {
                localFieldNames.add(name);
            } else {
                serverBackedOverrideNames.add(name);
            }
        }
        return summary;
    }

    private String submitActionRef(AgenticAuthoringCandidate candidate) {
        String method = candidate.submitMethod() == null || candidate.submitMethod().isBlank()
                ? candidate.operation()
                : candidate.submitMethod();
        return (method == null ? "POST" : method.toUpperCase()) + " " + candidate.submitUrl();
    }

    private void requireText(JsonNode node, String field, String expected, List<String> failures) {
        String actual = text(node, field);
        if (!expected.equals(actual)) {
            failures.add(field + " must be " + expected);
        }
    }

    private boolean containsField(JsonNode fields, String fieldName) {
        for (JsonNode field : fields) {
            if (fieldName.equals(text(field, "name"))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(JsonNode values, String expected) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (expected.equals(value.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String textValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
