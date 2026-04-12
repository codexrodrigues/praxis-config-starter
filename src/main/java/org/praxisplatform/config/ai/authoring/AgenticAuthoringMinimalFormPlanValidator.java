package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
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

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
