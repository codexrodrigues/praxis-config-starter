package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.ActionCheck;
import org.praxisplatform.config.dto.AiPatchDiff;
import org.praxisplatform.config.dto.IntentAction;
import org.praxisplatform.config.dto.IntentPlan;
import org.springframework.test.util.ReflectionTestUtils;

class AiOrchestratorServiceCompletenessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiOrchestratorService service;

    @BeforeEach
    void setUp() {
        service = new AiOrchestratorService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper,
                null);
    }

    @Test
    void shouldMarkPlanCompleteWhenAllChecksPass() {
        IntentPlan plan = IntentPlan.builder()
                .intent("update")
                .actions(List.of(IntentAction.builder()
                        .id("update-title")
                        .checks(List.of(ActionCheck.builder()
                                .type("pathChanged")
                                .path("title")
                                .build()))
                        .build()))
                .build();
        List<AiPatchDiff> diffs = List.of(AiPatchDiff.builder()
                .path("title")
                .before(objectMapper.convertValue("Old", com.fasterxml.jackson.databind.JsonNode.class))
                .after(objectMapper.convertValue("New", com.fasterxml.jackson.databind.JsonNode.class))
                .build());

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "evaluateCompleteness",
                plan,
                diffs,
                null,
                null,
                null);

        assertThat(ReflectionTestUtils.getField(result, "complete")).isEqualTo(true);
    }

    @Test
    void shouldMarkPlanIncompleteWhenCheckFails() {
        IntentPlan plan = IntentPlan.builder()
                .intent("update")
                .actions(List.of(IntentAction.builder()
                        .id("toggle-flag")
                        .checks(List.of(ActionCheck.builder()
                                .type("pathEquals")
                                .path("config.enabled")
                                .value(objectMapper.convertValue(true, com.fasterxml.jackson.databind.JsonNode.class))
                                .build()))
                        .build()))
                .build();
        List<AiPatchDiff> diffs = List.of(AiPatchDiff.builder()
                .path("title")
                .before(objectMapper.convertValue("Old", com.fasterxml.jackson.databind.JsonNode.class))
                .after(objectMapper.convertValue("New", com.fasterxml.jackson.databind.JsonNode.class))
                .build());

        Object result = ReflectionTestUtils.invokeMethod(
                service,
                "evaluateCompleteness",
                plan,
                diffs,
                null,
                null,
                null);

        assertThat(ReflectionTestUtils.getField(result, "complete")).isEqualTo(false);
    }
}
