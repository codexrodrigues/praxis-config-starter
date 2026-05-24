package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiAssistantObservationResponse(
        UUID observationId,
        String requestId,
        String tenantId,
        String environment,
        String userId,
        String surface,
        String componentId,
        String componentType,
        String routeKey,
        String variantId,
        String schemaHash,
        String contractVersion,
        UUID sessionId,
        UUID clientTurnId,
        UUID threadId,
        UUID turnId,
        UUID streamId,
        String promptHash,
        String promptPreview,
        Integer promptLength,
        String admissionOutcome,
        String terminalOutcome,
        String qualityOutcome,
        String errorCategory,
        String errorCode,
        String errorMessagePreview,
        String provider,
        String model,
        int llmCallCount,
        Long latencyMs,
        Instant createdAt,
        Instant updatedAt,
        List<AiAssistantObservationFeedbackResponse> feedback
) {
}
