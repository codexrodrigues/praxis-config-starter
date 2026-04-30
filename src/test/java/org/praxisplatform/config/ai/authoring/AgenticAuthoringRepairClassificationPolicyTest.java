package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringRepairClassificationPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classifiesRouteRequiredBeforeRepairablePreviewFailures() {
        AgenticAuthoringIntentResolutionResult intent = intent(
                "route_required",
                List.of("shared-rule-authoring-required"));
        AgenticAuthoringPreviewResult preview = preview(false, List.of("compile-skipped-invalid-minimal-form-plan"));

        assertThat(AgenticAuthoringRepairClassificationPolicy.classify(intent, preview))
                .isEqualTo("route_required");
    }

    @Test
    void classifiesUserClarificationBeforeRetry() {
        AgenticAuthoringIntentResolutionResult intent = intent(
                "clarification_required",
                List.of("resource-candidate-required"));
        AgenticAuthoringPreviewResult preview = preview(false, List.of("intent-resolution-selected-candidate-required"));

        assertThat(AgenticAuthoringRepairClassificationPolicy.classify(intent, preview))
                .isEqualTo("user_clarification_required");
    }

    @Test
    void classifiesInvalidPreviewAsRetryable() {
        assertThat(AgenticAuthoringRepairClassificationPolicy.classify(
                        intent("eligible", List.of()),
                        preview(false, List.of("fields must not be empty"))))
                .isEqualTo("retryable");
    }

    @Test
    void classifiesValidOrMissingPreviewAsNonRetryable() {
        assertThat(AgenticAuthoringRepairClassificationPolicy.classify(
                        intent("eligible", List.of()),
                        preview(true, List.of())))
                .isEqualTo("non_retryable");
        assertThat(AgenticAuthoringRepairClassificationPolicy.classify(
                        intent("eligible", List.of()),
                        null))
                .isEqualTo("non_retryable");
    }

    private AgenticAuthoringPreviewResult preview(boolean valid, List<String> failureCodes) {
        return new AgenticAuthoringPreviewResult(
                valid,
                failureCodes,
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null,
                null,
                valid ? "Preview ready." : "Preview failed.");
    }

    private AgenticAuthoringIntentResolutionResult intent(String gateStatus, List<String> failureCodes) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "page-builder",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", gateStatus, failureCodes),
                null,
                "Preview ready.",
                List.of(),
                List.of(),
                List.of(),
                failureCodes,
                objectMapper.createObjectNode());
    }
}
