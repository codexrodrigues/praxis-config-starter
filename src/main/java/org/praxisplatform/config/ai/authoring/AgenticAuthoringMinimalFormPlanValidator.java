package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;

public class AgenticAuthoringMinimalFormPlanValidator {

    static final String PROFILE_CREATE_MINIMAL_FORM = "create-minimal-form";
    static final String TARGET_APP_HELPDESK = "praxis-helpdesk-ui";
    static final String TARGET_COMPONENT_PAGE_BUILDER = "praxis-dynamic-page-builder";
    static final String SUBMIT_ACTION_REF = "POST /api/helpdesk/chamados";
    private static final List<String> ALLOWED_FIELDS = List.of("titulo", "descricao");
    private static final List<String> BLOCKED_FIELDS = List.of(
            "organizacaoId",
            "solicitanteId",
            "statusAtualId",
            "itemCatalogoId",
            "prioridadeId",
            "grupoResponsavelId",
            "responsavelId",
            "dataLimite"
    );

    public List<String> validate(JsonNode plan) {
        return validate(plan, null);
    }

    public List<String> validate(JsonNode plan, AgenticAuthoringIntentResolutionResult intentResolution) {
        if (plan == null || plan.isNull()) {
            return List.of("minimalFormPlan is required");
        }
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return validateLegacyHelpdeskPlan(plan);
        }
        return validateIntentBackedPlan(plan, intentResolution);
    }

    private List<String> validateLegacyHelpdeskPlan(JsonNode plan) {
        List<String> failures = new ArrayList<>();
        requireText(plan, "version", "1.0.0", failures);
        requireText(plan, "profileId", PROFILE_CREATE_MINIMAL_FORM, failures);
        requireText(plan, "targetApp", TARGET_APP_HELPDESK, failures);
        requireText(plan, "targetComponentId", TARGET_COMPONENT_PAGE_BUILDER, failures);
        requireText(plan, "submitActionRef", SUBMIT_ACTION_REF, failures);
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
            boolean hasTitle = false;
            for (JsonNode field : fields) {
                String name = text(field, "name");
                if (!ALLOWED_FIELDS.contains(name)) {
                    failures.add("field not allowed: " + name);
                }
                if ("titulo".equals(name)) {
                    hasTitle = true;
                    if (!field.path("required").asBoolean(false)) {
                        failures.add("titulo must be required");
                    }
                }
            }
            if (!hasTitle) {
                failures.add("titulo is required");
            }
            for (String blockedField : BLOCKED_FIELDS) {
                if (containsField(fields, blockedField)) {
                    failures.add("blocked field present: " + blockedField);
                }
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
                for (String blockedField : BLOCKED_FIELDS) {
                    if (blockedField.equals(name)) {
                        failures.add("blocked field present: " + blockedField);
                    }
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

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
