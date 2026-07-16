package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

@Tag("unit")
class AgenticAuthoringProviderTelemetrySerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsInternalInvocationJournalOutOfPlanAndPreviewContracts() {
        AiProviderInvocationTelemetry invocation = new AiProviderInvocationTelemetry(
                "minimal_form_plan",
                1,
                "openai",
                "gpt-test",
                "responses-http",
                "success",
                null,
                10L,
                20,
                5,
                null,
                null,
                25,
                "response-id",
                "stop");
        AgenticAuthoringPlanResult plan = new AgenticAuthoringPlanResult(
                true,
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                List.of(invocation));
        AgenticAuthoringPreviewResult preview = new AgenticAuthoringPreviewResult(
                true,
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null,
                null,
                "Pronto para revisar.",
                List.of(invocation));

        assertThat(objectMapper.valueToTree(plan).has("providerInvocations")).isFalse();
        assertThat(objectMapper.valueToTree(preview).has("providerInvocations")).isFalse();
        assertThat(plan.providerInvocations()).containsExactly(invocation);
        assertThat(preview.providerInvocations()).containsExactly(invocation);
    }
}
