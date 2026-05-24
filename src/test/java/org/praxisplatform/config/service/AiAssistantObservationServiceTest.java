package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiAssistantObservation;
import org.praxisplatform.config.domain.AiAssistantObservationFeedback;
import org.praxisplatform.config.dto.AiAssistantObservationFeedbackRequest;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.repository.AiAssistantObservationFeedbackRepository;
import org.praxisplatform.config.repository.AiAssistantObservationRepository;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiAssistantObservationServiceTest {

    @Mock
    private AiAssistantObservationRepository observationRepository;

    @Mock
    private AiAssistantObservationFeedbackRepository feedbackRepository;

    private AiAssistantObservationService service;

    @BeforeEach
    void setUp() {
        service = new AiAssistantObservationService(
                observationRepository,
                feedbackRepository,
                new AiSensitiveDataRedactor(),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "hashSecret", "test-secret");
        MDC.clear();
    }

    @Test
    void capturePersistsRedactedPreviewAndCorrelationId() {
        ArgumentCaptor<AiAssistantObservation> captor = ArgumentCaptor.forClass(AiAssistantObservation.class);
        when(observationRepository.saveAndFlush(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        MDC.put("requestId", "req-1");

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .schemaHash("hash")
                .contractVersion("v1.1")
                .build();
        UUID observationId = service.capture(
                request,
                "tenant-a",
                "user-a",
                "prod",
                AiAssistantObservationService.SURFACE_SYNC_PATCH,
                "Use token secret=abc123 para configurar a tabela");

        AiAssistantObservation saved = captor.getValue();
        assertThat(observationId).isEqualTo(saved.getObservationId());
        assertThat(request.getObservationId()).isEqualTo(observationId);
        assertThat(saved.getRequestId()).isEqualTo("req-1");
        assertThat(saved.getPromptHash()).isNotBlank();
        assertThat(saved.getPromptPreview()).isEqualTo("[redacted: secret-like content detected]");
        assertThat(saved.getAdmissionOutcome()).isEqualTo("captured");
        assertThat(saved.getSurface()).isEqualTo("sync_patch");
    }

    @Test
    void feedbackMarksObservationAsUserNegative() {
        UUID observationId = UUID.randomUUID();
        AiAssistantObservation observation = AiAssistantObservation.builder()
                .observationId(observationId)
                .tenantId("tenant-a")
                .environment("prod")
                .qualityOutcome("unresolved")
                .build();
        when(observationRepository.findById(observationId)).thenReturn(Optional.of(observation));
        when(feedbackRepository.save(any())).thenAnswer(invocation -> {
            AiAssistantObservationFeedback feedback = invocation.getArgument(0);
            feedback.setFeedbackId(UUID.randomUUID());
            return feedback;
        });

        var response = service.attachFeedback(
                observationId,
                new AiAssistantObservationFeedbackRequest(
                        "negative",
                        "wrong_answer",
                        "A resposta mostrou email pessoa@empresa.com"),
                new AiPrincipalContext("tenant-a", "user-a", "prod", true));

        assertThat(response.rating()).isEqualTo("negative");
        assertThat(response.commentPreview()).contains("[REDACTED]");
        assertThat(observation.getQualityOutcome()).isEqualTo("user_negative");
    }

    @Test
    void recordLlmCallStoresSubcallTelemetryInSafeMetadata() throws Exception {
        UUID observationId = UUID.randomUUID();
        AiAssistantObservation observation = AiAssistantObservation.builder()
                .observationId(observationId)
                .tenantId("tenant-a")
                .environment("prod")
                .safeMetadata("{\"schemaVersion\":\"praxis-ai-assistant-observation-metadata.v1\"}")
                .build();
        when(observationRepository.findById(observationId)).thenReturn(Optional.of(observation));

        service.recordLlmCall(
                observationId,
                "openai",
                "gpt-5-mini",
                "authoring_response_mode_classification",
                1234,
                4567,
                null);

        var metadata = new ObjectMapper().readTree(observation.getSafeMetadata());
        assertThat(observation.getProvider()).isEqualTo("openai");
        assertThat(observation.getModel()).isEqualTo("gpt-5-mini");
        assertThat(observation.getLlmCallCount()).isEqualTo(1);
        assertThat(observation.getLatencyMs()).isEqualTo(4567);
        assertThat(metadata.path("lastLlmCall").path("callType").asText())
                .isEqualTo("authoring_response_mode_classification");
        assertThat(metadata.path("lastLlmCall").path("promptLength").asInt()).isEqualTo(1234);
        assertThat(metadata.path("llmCalls")).hasSize(1);
    }

    @Test
    void markCompletedDoesNotEraseLastLlmLatencyWhenTotalLatencyIsUnavailable() {
        UUID observationId = UUID.randomUUID();
        AiAssistantObservation observation = AiAssistantObservation.builder()
                .observationId(observationId)
                .tenantId("tenant-a")
                .environment("prod")
                .latencyMs(9057L)
                .qualityOutcome("unresolved")
                .build();
        when(observationRepository.findById(observationId)).thenReturn(Optional.of(observation));

        service.markCompleted(observationId, null, 0L);

        assertThat(observation.getLatencyMs()).isEqualTo(9057L);
        assertThat(observation.getTerminalOutcome()).isEqualTo("error");
    }

    @Test
    void getRejectsCrossTenantAccess() {
        UUID observationId = UUID.randomUUID();
        AiAssistantObservation observation = AiAssistantObservation.builder()
                .observationId(observationId)
                .tenantId("tenant-a")
                .environment("prod")
                .build();
        when(observationRepository.findById(observationId)).thenReturn(Optional.of(observation));

        assertThatThrownBy(() -> service.get(
                observationId,
                new AiPrincipalContext("tenant-b", "user-b", "prod", true)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
