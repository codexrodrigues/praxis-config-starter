package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiTurnEventService;

@Tag("unit")
class AgenticAuthoringReplayAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAppendReplayBundleAsTurnEvent() throws Exception {
        AiTurnEventService turnEventService = org.mockito.Mockito.mock(AiTurnEventService.class);
        AgenticAuthoringReplayAuditService service = new AgenticAuthoringReplayAuditService(turnEventService, objectMapper);
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "dev", true);
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        JsonNode replayBundle = objectMapper.readTree("""
                {
                  "profileId": "create-minimal-form",
                  "catalogReleaseId": "catalog-a"
                }
                """);
        AgenticAuthoringDryRunResult dryRun = new AgenticAuthoringDryRunResult(
                true,
                List.of(new AgenticAuthoringGateResult("compiled-form-patch", "passed", List.of())),
                List.of(),
                List.of("round-trip-not-run"),
                objectMapper.createObjectNode().putObject("page")
        );
        AiTurnEventEnvelope expected = AiTurnEventEnvelope.builder()
                .streamId(streamId)
                .threadId(threadId)
                .turnId(turnId)
                .type(AgenticAuthoringReplayAuditService.EVENT_TYPE_AUTHORING_REPLAY)
                .build();
        when(turnEventService.appendEvent(
                eq(principal),
                eq(streamId),
                eq(threadId),
                eq(turnId),
                eq(AgenticAuthoringReplayAuditService.EVENT_TYPE_AUTHORING_REPLAY),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(expected);

        AiTurnEventEnvelope appended = service.appendReplayEvent(new AgenticAuthoringReplayAuditRequest(
                principal,
                streamId,
                threadId,
                turnId,
                replayBundle,
                dryRun
        ));

        assertThat(appended).isSameAs(expected);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(turnEventService).appendEvent(
                eq(principal),
                eq(streamId),
                eq(threadId),
                eq(turnId),
                eq(AgenticAuthoringReplayAuditService.EVENT_TYPE_AUTHORING_REPLAY),
                payloadCaptor.capture()
        );
        JsonNode payload = objectMapper.valueToTree(payloadCaptor.getValue());
        assertThat(payload.path("replayBundle").path("profileId").asText()).isEqualTo("create-minimal-form");
        assertThat(payload.path("dryRun").path("valid").asBoolean()).isTrue();
        assertThat(payload.path("dryRun").path("gates").get(0).path("gateId").asText()).isEqualTo("compiled-form-patch");
        assertThat(payload.path("dryRun").path("warnings").get(0).asText()).isEqualTo("round-trip-not-run");
        assertThat(payload.path("dryRun").has("patch")).isFalse();
    }
}
