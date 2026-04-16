package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgenticAuthoringMinimalFormPlanValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringMinimalFormPlanValidator validator =
            new AgenticAuthoringMinimalFormPlanValidator();

    @Test
    void validateTreatsNullPlanAsValidationFailure() {
        assertThat(validator.validate(null, intentResolution()))
                .containsExactly("minimalFormPlan is required");
    }

    @Test
    void validateIntentBackedPlanTreatsNullFieldEntriesAsValidationFailures() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", AgenticAuthoringMinimalFormPlanValidator.PROFILE_CREATE_MINIMAL_FORM);
        plan.put("targetApp", "praxis-ui-angular");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("submitActionRef", "POST /api/human-resources/funcionarios");
        plan.put("apiUseCaseResolutionRef", "api-use-case");
        plan.put("fieldSelectionPlanRef", "field-selection");
        plan.putArray("sourceRefs").add("test");
        plan.putArray("fields").addNull();

        List<String> failures = validator.validate(plan, intentResolution());

        assertThat(failures).contains(
                "field name is required",
                "field label is required: ",
                "field controlType is required: ");
    }

    private AgenticAuthoringIntentResolutionResult intentResolution() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "remove",
                "form",
                "remove_field",
                "dynamic-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post",
                        1.0,
                        "test",
                        List.of()),
                List.of(),
                new AgenticAuthoringGateResult("test", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }
}
